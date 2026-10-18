package com.oilquiz.app.ai.chat.util;

/**
 * 工具结果的显示口径（C0-T2）。
 *
 * <p>工具卡此前把 {@code formatForUi} 的整段文本直接塞进 TextView——一次
 * {@code python_execute} 几十 KB 输出就是一块超长 TextView，测量/绘制/复用全都变贵，
 * 是聊天流滚动卡顿的直接来源之一。这里只负责"截一个安全预览"：
 * 行数与字数双上限，先命中哪个算哪个。
 *
 * <p>只影响显示：{@code ToolCallInfo.result} 与历史持久化里仍是全文，
 * 用户点"查看全部"时才一次性构建完整文本的弹层。
 */
public final class ToolResultText {

    /** 预览最多显示多少个字符 */
    public static final int MAX_PREVIEW_CHARS = 2000;

    /** 预览最多显示多少行 */
    public static final int MAX_PREVIEW_LINES = 8;

    private ToolResultText() {
    }

    /** 预览结果：文本 + 是否被截断 + 原文总字数 */
    public static final class Preview {
        public final String text;
        public final boolean truncated;
        public final int totalChars;

        public Preview(String text, boolean truncated, int totalChars) {
            this.text = text == null ? "" : text;
            this.truncated = truncated;
            this.totalChars = totalChars;
        }
    }

    /**
     * 取工具卡预览文本。
     *
     * <p>两个约束必须守住，否则截出来的字符串会显示成乱码或半个字形：
     * <ul>
     *   <li>不切在代理对中间（emoji / 生僻字是 {@code char[]} 上的两个单位）；</li>
     *   <li>不切在 {@code \r\n} 中间（Windows 换行被拆开后 {@code \r} 会渲染成方块）。</li>
     * </ul>
     */
    public static Preview preview(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Preview("", false, raw == null ? 0 : raw.length());
        }
        int total = raw.length();
        int end = Math.min(total, MAX_PREVIEW_CHARS);

        // 行数上限：数到第 MAX_PREVIEW_LINES 行行尾就停
        int lines = 0;
        for (int i = 0; i < end; i++) {
            if (raw.charAt(i) == '\n' && ++lines >= MAX_PREVIEW_LINES) {
                end = i;
                break;
            }
        }

        // 收尾别切半个字符：代理对高位、行尾 \r 都退一格
        if (end > 1 && Character.isHighSurrogate(raw.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && raw.charAt(end - 1) == '\r') {
            end--;
        }

        boolean truncated = end < total;
        // 截断处补省略号：预览末尾往往是半句话，没有 … 看起来像"结果就这么长"
        return new Preview(truncated ? raw.substring(0, end) + "…" : raw, truncated, total);
    }
}
