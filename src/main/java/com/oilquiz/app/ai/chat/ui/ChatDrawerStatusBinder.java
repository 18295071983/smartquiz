package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.engine.contract.ContextUsage;
import com.oilquiz.app.ai.engine.contract.GenSignal;
import com.oilquiz.app.ai.engine.contract.GenSignalSource;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.InferenceType;

import java.lang.ref.WeakReference;

/**
 * 工具抽屉里的「本次会话状态」区块绑定（2026-10-25，2026-10-30 改为实时刷新）。
 *
 * <p>事实源是聊天页上的几个隐藏控件：{@code service_status_text}（当前模型/服务状态）、
 * {@code tv_api_balance}、{@code tv_token_stats}、{@code tv_gen_phase}、{@code tv_kv_stats}、
 * {@code tv_context_stats}——它们一直被各自的刷新方（ServiceStatusManager、统计条、
 * native 状态机、完成回调）写入。v2 把头部收起来之后没地方看，这里把同一份数重新露出来。</p>
 *
 * <p><b>实时刷新</b>：抽屉打开期间每 {@link #LIVE_REFRESH_MS} 拉一次，关闭即停；
 * 另外 KV 与上下文两行优先直接读引擎契约（{@link GenSignalSource#kvStats()} /
 * {@link GenSignalSource#contextUsage()}），因为这两个源的标题控件在生成期间会被隐藏，
 * 文本可能停在上一轮，直读契约才是当下值。其余行照抄源控件文本。</p>
 *
 * <p>2026-10-30：<b>不再按源控件可见性过滤</b>。v2 头部整块收起后源控件虽被"收起来"，
 * 但仍在被真实数据写入；按可见性过滤会让抽屉永远显示「暂无数据」，这正是
 * "抽屉里 token/KV/上下文没接线"的实际原因。空串仍按「暂无数据」展示。</p>
 *
 * <p>2026-10-31：<b>三态分支取代文案嗅探</b>。引擎按
 * {@link InferenceRouter#getEffectiveInferenceType()} 判定本地 / 在线 / NPU：本地直读引擎契约，
 * 在线照抄 API 侧源控件，NPU 的 KV/上下文/本地 token 三行显式写「NPU 不上报」——
 * 这三行在 NPU 侧没有生产者（{@code NpuSignalAdapter} 恒返回 null），照抄源控件只会把
 * 上一次 llama.cpp 会话的残留数字当成本轮值。</p>
 */
public final class ChatDrawerStatusBinder {

    /** 抽屉打开期间的实时刷新间隔（毫秒）：够跟上每个 token 的统计，又不至于每帧读控件 */
    private static final long LIVE_REFRESH_MS = 500L;

    private static final Handler LIVE_HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable liveTask;
    private static WeakReference<Activity> liveActivity;
    /** 上一次写入抽屉的文本：相同就不 setText，避免 500ms 轮询不断触发布局 */
    private static final java.util.HashMap<Integer, String> LAST_TEXT = new java.util.HashMap<>();

    /**
     * 成对的 {源控件 id, 抽屉里的值控件 id}。
     * 写成一张表是为了让"少接一个"这种漂移一眼可见，而不是散成几段 setXxx。
     */
    private static final int[][] PAIRS = {
            {R.id.service_status_text, R.id.tv_drawer_model},
            {R.id.tv_api_balance, R.id.tv_drawer_balance},
            {R.id.tv_token_stats, R.id.tv_drawer_tokens},
            {R.id.tv_kv_stats, R.id.tv_drawer_kv},
            {R.id.tv_context_stats, R.id.tv_drawer_context},
    };

    private ChatDrawerStatusBinder() {
    }

    /** 绑定一次：先刷一遍值；抽屉打开时持续刷新，关闭即停。 */
    public static void bind(Activity activity) {
        if (activity == null) return;
        try {
            stopLiveRefresh();
            LAST_TEXT.clear();
            refresh(activity);
            androidx.drawerlayout.widget.DrawerLayout drawer =
                    activity.findViewById(R.id.drawer_layout);
            if (drawer != null) {
                drawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                    @Override
                    public void onDrawerOpened(View drawerView) {
                        if (drawerView != null && drawerView.getId() == R.id.tool_drawer) {
                            refresh(activity);
                            startLiveRefresh(activity);
                        }
                    }

                    @Override
                    public void onDrawerClosed(View drawerView) {
                        if (drawerView != null && drawerView.getId() == R.id.tool_drawer) {
                            stopLiveRefresh();
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
            // 绑定失败不影响抽屉本身的功能（工具/模型/清空等入口都在）
        }
    }

    private static void startLiveRefresh(final Activity activity) {
        stopLiveRefresh();
        liveActivity = new WeakReference<>(activity);
        liveTask = new Runnable() {
            @Override
            public void run() {
                Activity a = liveActivity != null ? liveActivity.get() : null;
                if (a == null || a.isFinishing() || a.isDestroyed()) {
                    liveTask = null;
                    return;
                }
                refresh(a);
                LIVE_HANDLER.postDelayed(this, LIVE_REFRESH_MS);
            }
        };
        LIVE_HANDLER.postDelayed(liveTask, LIVE_REFRESH_MS);
    }

    private static void stopLiveRefresh() {
        if (liveTask != null) {
            LIVE_HANDLER.removeCallbacks(liveTask);
        }
        liveTask = null;
        liveActivity = null;
    }

    /**
     * 把最新值写进抽屉。KV / 上下文优先读引擎契约（生成期间源控件可能被隐藏或停在上一轮），
     * 其余行抄源控件文本；空值统一显示「暂无数据」。
     */
    public static void refresh(Activity activity) {
        if (activity == null) return;
        try {
            final InferenceType engine = currentEngine(activity);
            boolean online = engine == InferenceType.ONLINE;
            // NPU：KV / 上下文 / 本地 token 这三行**没有生产者** —— NpuSignalAdapter 侧恒为 null，
            // 而源控件里残留的是上一次 llama.cpp 会话的数字，抄过来就是拿别处的账冒充当前引擎。
            boolean npu = engine == InferenceType.NPU;
            // 一次刷新只取一次 KV 统计（JNI + JSON 解析），供 KV/Token 两行共用
            GenSignal.KvStats kv = (online || npu) ? null : GenSignalSource.kvStats();

            for (int[] pair : PAIRS) {
                View src = activity.findViewById(pair[0]);
                TextView target = activity.findViewById(pair[1]) instanceof TextView
                        ? (TextView) activity.findViewById(pair[1]) : null;
                if (target == null) continue;

                String value = null;
                if (npu && isEngineMetricRow(pair[1])) {
                    value = NPU_NO_METRIC;
                } else if (!online && !npu) {
                    if (pair[1] == R.id.tv_drawer_kv) {
                        value = liveKvText(kv);
                    } else if (pair[1] == R.id.tv_drawer_context) {
                        value = liveContextText();
                    } else if (pair[1] == R.id.tv_drawer_tokens) {
                        value = liveLocalTokensText(kv);
                    }
                }
                if (value == null) {
                    value = src instanceof TextView
                            ? (((TextView) src).getText() == null ? "" : ((TextView) src).getText().toString())
                            : "";
                }
                String finalText = TextUtils.isEmpty(value)
                        ? activity.getString(R.string.chat_v2_no_data) : value;
                // 值没变就不写：setText 即使同值也会 requestLayout，500ms 轮询会持续抖动
                if (!TextUtils.equals(LAST_TEXT.get(pair[1]), finalText)) {
                    target.setText(finalText);
                    LAST_TEXT.put(pair[1], finalText);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 本地 Token 用量：入 = 本轮请求真实 prompt token，出 = 本轮生成 token。
     * 数据来自 KV 记账（Agent 模式）或 native 聊天上下文（普通对话）。
     * 只在本地引擎下调用（在线/NPU 由 {@link #refresh} 的三态分支挡掉），故此处不再自行判引擎。
     */
    private static String liveLocalTokensText(GenSignal.KvStats kv) {
        try {
            if (kv != null && kv.promptTokens >= 0 && kv.generatedTokens >= 0) {
                return "入 " + kv.promptTokens + " · 出 " + kv.generatedTokens;
            }
            int in = com.oilquiz.app.ai.jni.LlamaHelper.getChatKvNewTokens();
            if (in >= 0) {
                int out = com.oilquiz.app.ai.jni.LlamaHelper.getTokenCount();
                return "入 " + in + " · 出 " + Math.max(0, out);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** NPU 下三行引擎指标的统一占位：不出数字，也不抄 native 的旧账。 */
    private static final String NPU_NO_METRIC = "NPU 不上报";

    /** 只有这三行是"引擎级指标"；余额/模型名两行照常抄源控件。 */
    private static boolean isEngineMetricRow(int targetId) {
        return targetId == R.id.tv_drawer_kv
                || targetId == R.id.tv_drawer_context
                || targetId == R.id.tv_drawer_tokens;
    }

    /**
     * 引擎三态的权威判据：本地 / 在线 / NPU，与顶部横幅同源 —— 都走
     * {@link InferenceRouter#getEffectiveInferenceType()}（本轮**实际执行**的引擎，
     * 不是配置选了哪个；开着 NPU 开关实际回退跑 llama.cpp 时，两处不能各说一套）。
     *
     * <p>2026-10-31 改：此前这里靠"横幅文案里有没有『在线』两个字"反推引擎，于是
     * NPU 被归成本地，去读 native 的 KV/上下文账（NPU 侧没有生产者）。
     * 判据取不到时按本地处理：最坏只是少读一次契约，不会把别的引擎的数当成本轮的。</p>
     */
    private static InferenceType currentEngine(Activity activity) {
        try {
            InferenceType t = InferenceRouter.getInstance(activity).getEffectiveInferenceType();
            if (t != null) return t;
        } catch (Throwable ignored) {
        }
        return InferenceType.LOCAL;
    }

    /**
     * KV 缓存命中率（只管命中，不混入上下文占用）：
     * 优先 Agent 增量缓存的计划命中率；普通本地聊天用 native 的"复用 token / 总请求 token"。
     * 只在本地引擎下调用（在线/NPU 由 {@link #refresh} 的三态分支挡掉），返回 null 交给源控件兜底。
     */
    private static String liveKvText(GenSignal.KvStats kv) {
        try {
            if (kv != null && kv.isDisplayable()) {
                return String.format(java.util.Locale.ROOT, "命中 %.0f%%", kv.hitRatePercent);
            }
            int reuse = com.oilquiz.app.ai.jni.LlamaHelper.getChatKvReusePercent();
            if (reuse >= 0) {
                return "命中 " + reuse + "%";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 上下文占用：百分比 + 已用/窗口；引擎没有可用窗口时返回 null 交给源控件兜底。 */
    private static String liveContextText() {
        try {
            ContextUsage cu = GenSignalSource.contextUsage();
            if (cu != null && cu.hasWindow() && cu.used >= 0 && cu.used != ContextUsage.UNKNOWN) {
                int percent = (int) Math.min(100, cu.used * 100 / cu.window);
                return percent + "% · " + shortTokens(cu.used) + "/" + shortTokens(cu.window);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String shortTokens(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return String.format(java.util.Locale.ROOT, "%.1fK", n / 1000.0);
        return String.format(java.util.Locale.ROOT, "%.1fM", n / 1000000.0);
    }
}
