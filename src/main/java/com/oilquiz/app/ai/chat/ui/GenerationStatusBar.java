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

    /**
     * native/模型状态读取（页面注入）。
     *
     * <p><b>2026-10-07 契约重构</b>：原先本接口暴露 6 个字符串化 JSON 读取方法
     * （{@code getGenPhase} / {@code getPrefillProgress} / {@code getKvCacheStats} …），
     * 由状态栏自行解析**隐含字段名**。这导致每引入一个新引擎都要重踩一次：字段名不一致时
     * {@code optInt("total", 0)} 静默取默认值，界面不报错、只是悄悄显示错的文案
     * （实测：NPU 下一直显示 llama.cpp 术语「⏳ 预处理」）。</p>
     *
     * <p>现在改为只暴露<b>引擎无关契约</b> {@link GenSignal}：引擎适配器负责翻译，
     * 渲染层不认识任何引擎的字段名，因此不会再出现静默失配。旧的 JSON 方法保留为
     * {@code default} 空实现仅为兼容既有注入方，渲染逻辑已不再使用。</p>
     */
    public interface NativeSource {
        /** 当前是否在线模型（在线不适用 native 状态条）。属路由决策，不在生成状态契约内。 */
        boolean isUsingOnlineModel();

        /**
         * 当前生成状态（引擎无关契约）。实现方可直接返回
         * {@code GenSignalSource.current()}，把引擎分派交给契约层。
         */
        default com.oilquiz.app.ai.engine.contract.GenSignal getSignal() {
            return com.oilquiz.app.ai.engine.contract.GenSignalSource.current();
        }

        /**
         * KV 缓存统计（可选能力）。返回 null 表示当前引擎不具备 → UI 隐藏该控件。
         */
        default com.oilquiz.app.ai.engine.contract.GenSignal.KvStats getKvStats() {
            return com.oilquiz.app.ai.engine.contract.GenSignalSource.kvStats();
        }

        // ===== 以下为旧接口，保留仅为兼容既有注入方；渲染层已不再调用 =====
        /** @deprecated 契约重构后由 {@link #getSignal()} 取代 */
        @Deprecated
        default String getGenPhase() { return ""; }
        /** @deprecated 契约重构后由 {@link #getSignal()} 取代 */
        @Deprecated
        default float getDecodeSpeed() { return -1f; }
        /** @deprecated 契约重构后由 {@link #getSignal()} 取代 */
        @Deprecated
        default String getPrefillProgress() { return ""; }
        /** @deprecated 契约重构后由 {@link #getSignal()} 取代 */
        @Deprecated
        default float getPhaseSpeed() { return -1f; }
        /** @deprecated 契约重构后由 {@link #getKvStats()} 取代 */
        @Deprecated
        default String getKvCacheStats() { return ""; }
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
    /**
     * 上一次动画触发时的思考内容长度。
     *
     * <p>修闪烁（2026-10-06）：原先用调用方传进来的 lineCount 判"是否换段"，而调用方
     * 传的是常量 1、初值 0 —— 两者永远不等，于是**每个思考片段（120ms 节流）都重启一次
     * 刮刀动画**。该动画是 AlphaAnimation(0.3→1) + TranslateAnimation(-0.6→0)，重启即把
     * alpha 打回 0.3、位移打回 -0.6 自身宽度 → 文字一明一暗 + 左右乱跳，就是用户看到的"闪烁"。
     * 现在改为按"思考文本是否新增了一整行"判断：内容增长只刷新文字，真正换段才滑入一次。</p>
     */
    private int lastThinkLen;

    /**
     * 上一次渲染的阶段。
     *
     * <p>修"处理提示循环出现 + 刮刀重复滑入"（2026-10-07）：原先 PREPROCESS 分支**每次刷新都调**
     * {@link #resetThinkingTrack()}（把 {@code lastThinkShown} 置 null）。而 {@link #refresh()}
     * 由 800ms 轮询 + 每个 token 回调触发，于是：
     * <ul>
     *   <li>思考段的 {@code lastThinkShown} 被反复清空 → 每个片段都被判为"新段" → 刮刀动画重复滑入；</li>
     *   <li>配合"阶段卡在 PREPROCESS"的缺陷，状态栏看起来就在循环显示「处理提示」。</li>
     * </ul>
     * 现在只在**阶段真正发生变化**时重置，重复刷新同一阶段不再产生副作用。</p>
     */
    private com.oilquiz.app.ai.engine.contract.GenSignal.Phase lastPhase;

    public GenerationStatusBar(Context context, TextView tvGenPhase, TextView tvKvStats, NativeSource source) {
        this.context = context.getApplicationContext();
        this.tvGenPhase = tvGenPhase;
        this.tvKvStats = tvKvStats;
        this.source = source;
    }

    /** 文本变了才 setText（setText 无条件会触发 measure/layout，高频调用会抖动） */
    private static void setTextIfChanged(TextView tv, CharSequence text) {
        if (tv == null || text == null) return;
        CharSequence cur = tv.getText();
        if (cur != null && cur.toString().contentEquals(text)) return;
        tv.setText(text);
    }

    /**
     * 可见性变了才 setVisibility。
     * 原先 refresh() 每 800ms 无条件调用一次 setVisibility(VISIBLE/GONE)——即使值没变也是
     * 一次 requestLayout+重绘，属于无谓抖动。
     */
    private static void setVisibilityIfChanged(View v, int visibility) {
        if (v == null) return;
        if (v.getVisibility() == visibility) return;
        v.setVisibility(visibility);
    }

    /** 思考文本是否新增了完整一行（换段），用于决定是否滑入动画 */
    private static int countNewlines(CharSequence s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
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

    /**
     * 思考段到达（页面流式回调里喂显示文本）：内容变了才刷新文字；
     * **只有换段（思考文本新增一整行）才滑入动画**，避免高频重启导致的断续抖动/闪烁。
     *
     * @param lineCount 兼容参数：调用方传什么都不再决定动画时机（旧调用方传的是常量 1，
     *                  正是它导致"每段都动画"）；是否换段由本类按文本内的换行数自行判断。
     */
    public void onThinkingSegment(String disp, int lineCount) {
        if (tvGenPhase == null || disp == null) return;
        if (disp.equals(lastThinkShown)) return;   // 文本没变：什么都不做（避免无谓重绘）
        setVisibilityIfChanged(tvGenPhase, View.VISIBLE);
        setTextIfChanged(tvGenPhase, disp);
        lastThinkShown = disp;
        int nl = countNewlines(disp);
        if (nl > lastThinkLen) {                   // 换段：滑入一次
            startThinkingRollerAnim();
        }
        lastThinkLen = nl;
    }

    /** 重置思考跟踪（新会话/新推理轮次时调用） */
    public void resetThinkingTrack() {
        lastThinkShown = null;
        lastThinkLen = 0;
    }

    /**
     * 阶段渲染 —— **完全基于引擎无关契约**，不再解析任何引擎的 JSON、也不判断引擎。
     *
     * <p>文案映射抽到 {@link com.oilquiz.app.ai.engine.contract.GenSignalTextMapper}（纯函数，可单测）；
     * 本方法只负责"取文本 → 写 TextView → 管可见性"。</p>
     */
    private void renderPhase(com.oilquiz.app.ai.engine.contract.GenSignal signal) {
        // 只在阶段**真正变化**时做重置/首次占位，重复刷新同一阶段不再有副作用
        final boolean phaseChanged = signal.phase != lastPhase;
        lastPhase = signal.phase;

        String text = com.oilquiz.app.ai.engine.contract.GenSignalTextMapper.toStatusText(
                signal, context::getString);
        if (text != null) {
            setTextIfChanged(tvGenPhase, text);
        }
        if (signal.phase == com.oilquiz.app.ai.engine.contract.GenSignal.Phase.THINKING) {
            // 思考段：顶部单行由 thinking 事件驱动（120ms 节流）；此处仅**首次进入**时占位
            if (phaseChanged && lastThinkShown == null) {
                setTextIfChanged(tvGenPhase, "💭 ");
                lastThinkShown = "💭 ";
            }
        } else if (signal.phase == com.oilquiz.app.ai.engine.contract.GenSignal.Phase.PREPROCESS) {
            // 仅阶段切换时重置轨道；若每次刷新都重置，会清掉思考段的 lastThinkShown
            // → 每个思考片段都被判为新段 → 刮刀动画重复滑入
            if (phaseChanged) {
                resetThinkingTrack();
            }
        }
    }

    // ==================== 核心渲染 ====================

    /**
     * 刷新状态条。
     *
     * <p><b>契约重构（2026-10-07）</b>：本方法原先直接解析三段 native JSON 并按引擎分支，
     * 导致新引擎必须改这里（且字段名不一致时会静默降级）。现在只消费
     * {@link com.oilquiz.app.ai.engine.contract.GenSignal}，与引擎彻底解耦。</p>
     */
    public void refresh() {
        try {
            if (source.isUsingOnlineModel()) {
                // 在线模型：KV 隐藏；tvGenPhase 由在线 thinking observe 实时驱动，
                // 不强制 GONE（避免 800ms 轮询与实时 observe 互相覆盖闪烁）
                setVisibilityIfChanged(tvKvStats, View.GONE);
                return;
            }

            final com.oilquiz.app.ai.engine.contract.GenSignal signal = source.getSignal();

            if (tvGenPhase != null) {
                if (signal != null && signal.isActive()) {
                    renderPhase(signal);
                    // 注意：可见性必须与本次是否写了文本一致，否则"文字变了但仍是 GONE"会看不见
                    setVisibilityIfChanged(tvGenPhase, View.VISIBLE);
                } else {
                    // 回到空闲：清掉阶段记忆，下一轮推理才能重新走"首次进入"占位逻辑
                    lastPhase = null;
                    setVisibilityIfChanged(tvGenPhase, View.GONE);
                }
            }

            if (tvKvStats != null) {
                if (signal != null && signal.isActive()) {
                    // 生成中不显示 KV 统计
                    setVisibilityIfChanged(tvKvStats, View.GONE);
                } else {
                    // KV 统计是**可选能力**：引擎不具备时 getKvStats() 返回 null → 隐藏控件
                    final com.oilquiz.app.ai.engine.contract.GenSignal.KvStats kv = source.getKvStats();
                    if (kv != null && kv.isDisplayable()) {
                        setTextIfChanged(tvKvStats, String.format(context.getString(R.string.h_3e238a20),
                                kv.hitRatePercent, kv.ctxUsagePercent >= 0 ? kv.ctxUsagePercent : 0));
                        setVisibilityIfChanged(tvKvStats, View.VISIBLE);
                    } else {
                        setVisibilityIfChanged(tvKvStats, View.GONE);
                    }
                }
            }
        } catch (Throwable t) {
            // 解析失败/异常：隐藏状态条，不影响主流程
            setVisibilityIfChanged(tvGenPhase, View.GONE);
            setVisibilityIfChanged(tvKvStats, View.GONE);
        }
    }

    /** 单行段落切换动画：新内容从左往右刮刀式滑入 + 柔和淡入（只在换段时调用一次） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            // 先清掉上一段动画：若上一次还没播完就叠加/重启，alpha 会被打回 0.3、位移打回 -0.6，
            // 视觉上就是闪烁；换段时应当"从干净状态滑入"。
            tvGenPhase.clearAnimation();
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
