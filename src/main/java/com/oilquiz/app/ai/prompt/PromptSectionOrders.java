package com.oilquiz.app.ai.prompt;

/**
 * 中心化的 prompt 分段顺序常量 —— 对应 dsh 的 {@code SECTION_ORDERS} 表。
 * 全局唯一分配位置，避免各注册方自行猜数导致顺序漂移；等序按段名字典序兜底。
 * <p>
 * 典型布局：身份(-1000) → persona 前缀(0) → 思考指令(100) → 指导(200) → 工具说明(500)
 * → 输出规范(9900) → persona 后缀(10200)。
 */
public final class PromptSectionOrders {

    public static final int HARNESS_IDENTITY = -1000;
    public static final int PERSONA_PREFIX = 0;
    public static final int THINKING_INSTRUCTION = 100;
    public static final int GUIDANCE = 200;
    public static final int TOOL_DESCRIPTIONS = 500;
    public static final int STRUCTURED_OUTPUT = 9900;
    public static final int PERSONA_SUFFIX = 10200;

    /** 动态上下文段的标准位置 */
    public static final int CONTEXT_RUNTIME = 100;
    public static final int CONTEXT_SESSION = 200;

    private PromptSectionOrders() {
    }
}
