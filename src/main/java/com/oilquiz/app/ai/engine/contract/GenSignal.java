package com.oilquiz.app.ai.engine.contract;

/**
 * 生成状态的**引擎无关契约**。
 *
 * <p><b>为什么要有它</b>：本项目有两个推理后端（llama.cpp 与 GenieX NPU），UI（状态栏、上下文仪表、
 * token 徽标）需要展示的"当前生成状态"是**同一件事**，但数据来源不同。历史上各引擎各自向外暴露
 * 字符串化 JSON（{@code getGenPhase()} / {@code getPrefillProgress()} / {@code getKvCacheStats()}），
 * UI 直接解析<b>隐含的字段名</b>。于是每引入一个新引擎就要重新踩一遍：字段名不一致时
 * {@code optInt("total", 0)} 会静默取到默认值，UI 不报错、只是悄悄显示错的内容。</p>
 *
 * <p>已发生的真实故障（都因这个契约缺失）：
 * <ul>
 *   <li>NPU 的 prefill 进度字段是 {@code progress/tokens}，而状态栏读的是 {@code done/total/pct}
 *       → 永远取不到进度，只剩兜底文案「⏳ 预处理」（llama.cpp 的术语）；</li>
 *   <li>NPU 的 {@code InferencePhase} 沿用了 llama.cpp 的枚举名（PREPROCESS/THINKING/GENERATING），
 *       状态栏按名字分支，于是把 llama.cpp 的文案与进度语义套在了 NPU 上。</li>
 * </ul>
 *
 * <p><b>契约规则</b>（新增引擎时必须遵守）：
 * <ol>
 *   <li>引擎适配器负责把自身状态**翻译**成本类；UI 只读本类，不认识任何引擎。</li>
 *   <li>字段缺失用 {@link #UNKNOWN} / 空串表达，**不允许用 0 冒充"存在但为零"**；
 *       本类用 {@link Phase#IDLE} 与 {@code hasProgress()} 明确区分"没有进度"与"进度为 0"。</li>
 *   <li>文案（如「处理提示」）由引擎提供，UI 不硬编码引擎术语。</li>
 *   <li>引擎不具备的可选能力（如 NPU 没有 KV 缓存统计）用 {@code null} 表达，
 *       UI 据此**隐藏对应控件**，而不是显示假数据。</li>
 * </ol>
 */
public final class GenSignal {

    /** 数值未知的哨兵（比 0 更明确：0 可能是真实的零值） */
    public static final float UNKNOWN = -1f;

    /**
     * 生成阶段。**这是唯一允许 UI 分支的枚举**——它语义明确，且与任何引擎的实现细节无关。
     *
     * <p>注意与"引擎加载阶段"区分：加载属于引擎生命周期（{@code NpuEngineState.Stage} /
     * {@code AIServiceState.ServiceStage}），不属于"这条消息在生成中的哪一步"。</p>
     */
    public enum Phase {
        /** 没有生成在进行 */
        IDLE,
        /** 正在处理输入（prefill / 提示词处理） */
        PREPROCESS,
        /** 正在生成思考段 */
        THINKING,
        /** 正在生成正文 */
        GENERATING
    }

    /** 阶段；不可为 null */
    public final Phase phase;
    /** 是否有生成在进行（UI 据此显示/隐藏状态条） */
    public final boolean running;
    /**
     * 当前阶段的进度百分比 0-100；{@link #UNKNOWN} 表示**该引擎/该阶段没有可用的进度**。
     *
     * <p>这是修掉"字段名不一致导致静默降级"的关键：llama.cpp 的 prefill 有逐 token 进度，
     * NPU 的 prefill 是阶段量（通常直接 0→100）。两者都用这一个字段表达，
     * 引擎没有进度时给 {@link #UNKNOWN}，UI 就不显示百分比而不是显示 0%。</p>
     */
    public final float progressPercent;
    /** 已处理/已生成的 token 数；{@link #UNKNOWN} 表示不可用 */
    public final float tokens;
    /** 本阶段的中文标签（**由引擎提供**，UI 不再按枚举名硬编码术语）；可为空串 */
    public final String phaseLabel;
    /** 正文解码速度（t/s）；{@link #UNKNOWN} 表示不可用 */
    public final float decodeSpeed;
    /** 本阶段吞吐（t/s）；{@link #UNKNOWN} 表示不可用。与 decodeSpeed 的区别由引擎定义 */
    public final float phaseSpeed;
    /** 引擎名（仅用于日志/诊断，UI 不应据此分支） */
    public final String engineName;

    private GenSignal(Builder b) {
        this.phase = b.phase;
        this.running = b.running;
        this.progressPercent = b.progressPercent;
        this.tokens = b.tokens;
        this.phaseLabel = b.phaseLabel == null ? "" : b.phaseLabel;
        this.decodeSpeed = b.decodeSpeed;
        this.phaseSpeed = b.phaseSpeed;
        this.engineName = b.engineName == null ? "unknown" : b.engineName;
    }

    /** 是否可用于显示（有生成在进行） */
    public boolean isActive() {
        return running && phase != Phase.IDLE;
    }

    /** 该阶段是否有可用的进度百分比 */
    public boolean hasProgress() {
        return progressPercent != UNKNOWN;
    }

    /** 该阶段是否有可用的 token 数 */
    public boolean hasTokens() {
        return tokens != UNKNOWN;
    }

    @Override
    public String toString() {
        return "GenSignal{" + engineName + " phase=" + phase + " running=" + running
                + " progress=" + progressPercent + " tokens=" + tokens
                + " decode=" + decodeSpeed + " label=" + phaseLabel + "}";
    }

    // ==================== 构造 ====================

    public static Builder builder(String engineName) {
        return new Builder(engineName);
    }

    /** 空闲信号（无生成） */
    public static GenSignal idle(String engineName) {
        return builder(engineName).phase(Phase.IDLE).running(false).build();
    }

    public static final class Builder {
        private final String engineName;
        private Phase phase = Phase.IDLE;
        private boolean running = false;
        private float progressPercent = UNKNOWN;
        private float tokens = UNKNOWN;
        private String phaseLabel = "";
        private float decodeSpeed = UNKNOWN;
        private float phaseSpeed = UNKNOWN;

        Builder(String engineName) {
            this.engineName = engineName;
        }

        public Builder phase(Phase p) { this.phase = p == null ? Phase.IDLE : p; return this; }
        public Builder running(boolean r) { this.running = r; return this; }
        public Builder progressPercent(float p) { this.progressPercent = p; return this; }
        public Builder tokens(float t) { this.tokens = t; return this; }
        public Builder phaseLabel(String l) { this.phaseLabel = l; return this; }
        public Builder decodeSpeed(float s) { this.decodeSpeed = s; return this; }
        public Builder phaseSpeed(float s) { this.phaseSpeed = s; return this; }

        /** 速度 <=0 视为不可用（避免把"没测到"当成 0 t/s 显示） */
        public Builder decodeSpeedOrUnknown(float s) { return decodeSpeed(s > 0 ? s : UNKNOWN); }

        /** 速度 <=0 视为不可用 */
        public Builder phaseSpeedOrUnknown(float s) { return phaseSpeed(s > 0 ? s : UNKNOWN); }

        /** 进度：负数或 >100 视为不可用 */
        public Builder progressOrUnknown(float p) {
            return progressPercent(p >= 0 && p <= 100 ? p : UNKNOWN);
        }

        /** token 数：负数视为不可用 */
        public Builder tokensOrUnknown(float t) { return tokens(t >= 0 ? t : UNKNOWN); }

        public GenSignal build() { return new GenSignal(this); }
    }

    // ==================== 可选能力：KV 缓存统计 ====================

    /**
     * KV 缓存统计 —— **可选能力**。
     *
     * <p>llama.cpp 有（增量 KV 缓存），NPU 侧 SDK 未暴露。引擎不具备时适配器返回 {@code null}，
     * UI 隐藏该控件。这样"引擎能力差异"被显式建模，而不是靠"返回空 JSON 让 UI 猜"。</p>
     */
    public static final class KvStats {
        /** 缓存命中率百分比 0-100 */
        public final double hitRatePercent;
        /** 上下文占用百分比 0-100；{@link #UNKNOWN} 表示不可用 */
        public final double ctxUsagePercent;
        /** 缓存计划条目数 */
        public final int plans;

        public KvStats(double hitRatePercent, double ctxUsagePercent, int plans) {
            this.hitRatePercent = hitRatePercent;
            this.ctxUsagePercent = ctxUsagePercent;
            this.plans = plans;
        }

        /** 是否足够构成一行可显示的统计 */
        public boolean isDisplayable() {
            return hitRatePercent >= 0 && plans > 0;
        }
    }
}
