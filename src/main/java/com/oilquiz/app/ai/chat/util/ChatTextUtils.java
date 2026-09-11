package com.oilquiz.app.ai.chat.util;

/**
 * 聊天通用文本/格式化工具（全局可复用）。
 *
 * 从 AIChatActivity 纯逻辑小工具抽取：状态阶段中文映射、上下文窗口缩写、
 * 关键词命中、思考文本行数统计。无状态、零 UI 依赖。
 */
public final class ChatTextUtils {

    private ChatTextUtils() {}

    /** 状态机阶段英文 → 中文显示 */
    public static String phaseToCn(String phase) {
        if ("PREPROCESS".equals(phase)) return "预处理";
        if ("THINKING".equals(phase)) return "思考中";
        if ("GENERATING".equals(phase)) return "生成中";
        if ("COMPLETE".equals(phase)) return "完成";
        return phase != null ? phase : "";
    }

    /** 上下文窗口缩写（1.2M / 8K / 原始值） */
    public static String formatCtxWindow(int window) {
        if (window >= 1000000) {
            return (window / 1000000) + "M";
        }
        if (window >= 1000) {
            return (window / 1000) + "K";
        }
        return String.valueOf(window);
    }

    /** 文本是否命中任一关键词（大小写不敏感） */
    public static boolean containsKeyword(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (kw != null && text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 思考文本按 \n 计行数（用于判断是否出现新段） */
    public static int countThinkingLines(String content) {
        if (content == null) return 0;
        int n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') n++;
        }
        return n;
    }
}
