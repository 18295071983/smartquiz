package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.util.ChatTextUtils;

import org.json.JSONObject;

/**
 * 生成状态条（全局可复用 View 渲染组件）。
 *
 * 从 AIChatActivity refreshNativeStateUI / startThinkingRollerAnim /
 * thinkingRefreshRunnable 的渲染逻辑抽取：顶部 native 状态机阶段（GenPhase）
 * + KV 增量缓存状态 + 思考段单行刮刀式滑入动画 + 800ms 轮询。
 *
 * <p>数据源通过 {@link NativeSource} 注入（页面传 LlamaHelper/InferenceRouter
 * 的读取），组件只做"读数据 → 渲染"；不持有 Activity 引用。</p>
 *
 * <p>复用方式（示例）：</p>
 * <pre>
 * GenerationStatusBar bar = new GenerationStatusBar(context, tvGenPhase, tvKvStats, source);
 * bar.startPolling();        // onResume
 * bar.onThinkingSegment(disp, lineCount); // 流式思考回调里喂显示文本
 * bar.stopPolling();         // onDestroy
 * </pre>
 */
public class GenerationStatusBar {

    private static final long STATE_POLL_INTERVAL_MS = 800L;

    /** native/模型状态读取（页面注入，如 LlamaHelper + InferenceRouter 的适配） */
    public interface NativeSource {
        /** 当前是否在线模型（在线不适用 native 状态条） */
        boolean isUsingOnlineModel();
        /** LlamaHelper.getGenPhase()：JSON 字符串（phase/running），可 null */
        String getGenPhase();
        /** 正文 decode 速度（LlamaHelper.getDecodeSpeed），<=0 表示不可用 */
        float getDecodeSpeed();
        /** prefill 进度 JSON（LlamaHelper.getPrefillProgress），可 null */
        String getPrefillProgress();
        /** 阶段吞吐（LlamaHelper.getPhaseSpeed） */
        float getPhaseSpeed();
        /** KV 缓存统计 JSON（LlamaHelper.getKvCacheStats），可 null */
        String getKvCacheStats();
    }

    private final Context context;
    private final TextView tvGenPhase;
    private final TextView tvKvStats;
    private final NativeSource source;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            refresh();
            pollHandler.postDelayed(this, STATE_POLL_INTERVAL_MS);
        }
    };
    private boolean polling;

    private String lastThinkShown;
    private int lastThinkLineCount;

    public GenerationStatusBar(Context context, TextView tvGenPhase, TextView tvKvStats, NativeSource source) {
        this.context = context.getApplicationContext();
        this.tvGenPhase = tvGenPhase;
        this.tvKvStats = tvKvStats;
        this.source = source;
    }

    // ==================== 轮询 ====================

    public void startPolling() {
        if (polling) return;
        polling = true;
        refresh();
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, STATE_POLL_INTERVAL_MS);
    }

    public void stopPolling() {
        polling = false;
        pollHandler.removeCallbacksAndMessages(null);
    }

    /** 思考段到达（页面流式回调里喂显示文本与行数）：去重 + 换行触发滑入动画 */
    public void onThinkingSegment(String disp, int lineCount) {
        if (tvGenPhase == null || disp == null) return;
        if (!disp.equals(lastThinkShown)) {
            tvGenPhase.setVisibility(View.VISIBLE);
            tvGenPhase.setText(disp);
            lastThinkShown = disp;
        }
        if (lineCount != lastThinkLineCount) {
            lastThinkLineCount = lineCount;
            startThinkingRollerAnim();
        }
    }

    /** 重置思考跟踪（新会话/新推理轮次时调用） */
    public void resetThinkingTrack() {
        lastThinkShown = null;
        lastThinkLineCount = 0;
    }

    // ==================== 核心渲染 ====================

    /**
     * 刷新状态条：在线模型隐藏 native 区；本地按 GenPhase（PREPROCESS/THINKING/
     * GENERATING）显示阶段与速度；空闲显示 KV 缓存状态。
     */
    public void refresh() {
        try {
            if (source.isUsingOnlineModel()) {
                // 在线模型：KV 隐藏；tvGenPhase 由在线 thinking observe 实时驱动，
                // 不强制 GONE（避免 800ms 轮询与实时 observe 互相覆盖闪烁）
                if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
                return;
            }
            if (tvGenPhase != null) {
                String j = source.getGenPhase();
                boolean show = false;
                if (j != null && !j.isEmpty()) {
                    JSONObject o = new JSONObject(j);
                    String phase = o.optString("phase", "IDLE");
                    boolean running = o.optBoolean("running", false);
                    if (running) {
                        if ("GENERATING".equals(phase)) {
                            float ds = source.getDecodeSpeed();
                            if (ds > 0) {
                                tvGenPhase.setText(String.format(context.getString(R.string.h_743faf7e), ds));
                            } else {
                                tvGenPhase.setText(context.getString(R.string.h_ad0acdc5));
                            }
                        } else if ("PREPROCESS".equals(phase)) {
                            String pp = source.getPrefillProgress();
                            if (pp != null && !pp.isEmpty()) {
                                try {
                                    JSONObject po = new JSONObject(pp);
                                    int done = po.optInt("done", 0);
                                    int total = po.optInt("total", 0);
                                    int pct = po.optInt("pct", 0);
                                    int prompt = po.optInt("prompt", 0);
                                    if (total > 0) {
                                        float ps = source.getPhaseSpeed();
                                        if (ps > 0) {
                                            if (prompt > 0) {
                                                tvGenPhase.setText(String.format(context.getString(R.string.h_2a8f9dc2), prompt, pct, ps));
                                            } else {
                                                tvGenPhase.setText(String.format(context.getString(R.string.h_f8a477f6), pct, ps));
                                            }
                                        } else {
                                            if (prompt > 0) {
                                                tvGenPhase.setText(String.format(context.getString(R.string.h_e3ad3e92), prompt, pct));
                                            } else {
                                                tvGenPhase.setText(String.format(context.getString(R.string.h_2dcef5d6), pct));
                                            }
                                        }
                                    } else {
                                        tvGenPhase.setText(context.getString(R.string.h_803f889b));
                                    }
                                } catch (Exception ignored) {
                                    tvGenPhase.setText(context.getString(R.string.h_803f889b));
                                }
                            } else {
                                tvGenPhase.setText(context.getString(R.string.h_803f889b));
                            }
                            resetThinkingTrack();
                        } else if ("THINKING".equals(phase)) {
                            // 顶部单行由 thinking 事件驱动（120ms 节流）；此处仅首次初始化
                            if (lastThinkShown == null) {
                                tvGenPhase.setText("💭 ");
                                lastThinkShown = "💭 ";
                            }
                        } else {
                            tvGenPhase.setText("⏳ " + ChatTextUtils.phaseToCn(phase));
                            resetThinkingTrack();
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;
                    }
                }
                if (!show) tvGenPhase.setVisibility(View.GONE);
            }
            if (tvKvStats != null) {
                boolean runningNow = false;
                String gp = source.getGenPhase();
                if (gp != null && !gp.isEmpty()) {
                    try {
                        runningNow = new JSONObject(gp).optBoolean("running", false);
                    } catch (Exception ignored) {}
                }
                if (runningNow) {
                    tvKvStats.setVisibility(View.GONE);
                } else {
                    String j = source.getKvCacheStats();
                    boolean show = false;
                    if (j != null && !j.isEmpty()) {
                        JSONObject o = new JSONObject(j);
                        double hit = o.optDouble("hit_rate_pct", -1);
                        double usage = o.optDouble("ctx_usage_pct", -1);
                        int plans = o.optInt("plans", 0);
                        if (hit >= 0 && plans > 0) {
                            tvKvStats.setText(String.format(context.getString(R.string.h_3e238a20),
                                    hit, usage >= 0 ? usage : 0));
                            tvKvStats.setVisibility(View.VISIBLE);
                            show = true;
                        }
                    }
                    if (!show) tvKvStats.setVisibility(View.GONE);
                }
            }
        } catch (Throwable t) {
            // 解析失败/异常：隐藏状态条，不影响主流程
            if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
            if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
        }
    }

    /** 单行段落切换动画：新内容从左往右刮刀式滑入 + 柔和淡入 */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            AnimationSet set = new AnimationSet(true);
            TranslateAnimation ta = new TranslateAnimation(
                    TranslateAnimation.RELATIVE_TO_SELF, -0.6f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0f);
            ta.setDuration(320);
            ta.setInterpolator(new DecelerateInterpolator());
            AlphaAnimation aa = new AlphaAnimation(0.3f, 1f);
            aa.setDuration(320);
            set.addAnimation(ta);
            set.addAnimation(aa);
            tvGenPhase.startAnimation(set);
        } catch (Exception ignored) {}
    }
}
