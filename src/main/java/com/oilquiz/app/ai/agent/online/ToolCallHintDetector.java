package com.oilquiz.app.ai.agent.online;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断一段"最终回答"是否在暗示「我还要调用工具」，用于在模型把工具调用写进正文、
 * 却没给出 {@code tool_calls} 时补一条格式纠正提示。
 *
 * <p><b>为什么要单独成类</b>：这段判断直接决定"这一轮是收尾还是再转一圈"。判断错了的代价不对称——
 * 漏判只是少一次纠正，误判却会把一条已经说完的回答按回循环里重答一遍。2026-10-28 的真机会话
 * （com.oilquiz.app 会话 f135ecaa，问"文件工具是不是没有 write 工具"）就是误判样本：回答里引用
 * {@code `file_generator(action=create)`} 这种"工具名+括号"的写法，被当成伪调用，于是同一个结论
 * 被重答 8 次、白插 10 条格式提示。所以这里把判定独立出来，用单测钉住"解释工具时不该触发"。
 *
 * <p><b>行内代码与代码块的区别对待</b>：反引号里的 {@code `xxx(...)`} 是"在解释工具"，不是"要调用"，
 * 先剥掉再判断；但围栏代码块里的 {@code ```json {"name":"weather",...} ```} 恰恰是最典型的
 * "把调用写进正文"的失败形态，必须保留下来给 JSON 规则去命中。
 */
final class ToolCallHintDetector {

    /** 只看前 800 字，长回答的判断成本可控（与历史行为一致） */
    private static final int SCAN_LIMIT = 800;

    /** 行内代码：`...`（不跨行）。围栏代码块故意不剥——伪调用常藏在那里 */
    private static final Pattern INLINE_CODE =
            Pattern.compile("`[^`\\n]*`");

    private static final String[] ZH_VERBS = {
            "调用", "使用", "执行", "运用", "启用", "需要调用", "我要调用", "我将调用", "准备调用"
    };

    /** 英文必须"动作 + tool/function"同时出现；"let me check the file" 这类不算 */
    private static final Pattern EN_EXPLICIT = Pattern.compile(
            "(i will|i'll|i am going to|i need to|i want to|let me|please)\\s+"
          + "(call|use|invoke|execute)\\s+(the\\s+)?(\\w+\\s+)?(tool|function)",
            Pattern.CASE_INSENSITIVE);

    private ToolCallHintDetector() {
    }

    /**
     * @return 命中原因（写日志用）；未命中返回 {@code null}
     */
    static String detectReason(String content, Collection<String> toolIds) {
        if (content == null || toolIds == null || toolIds.isEmpty()) return null;
        String text = content.trim();
        if (text.isEmpty()) return null;
        if (text.length() > SCAN_LIMIT) text = text.substring(0, SCAN_LIMIT);

        // 行内代码剥掉（解释工具），围栏代码块保留（伪调用常藏在这里）
        String prose = stripInlineCode(text);
        String lowerProse = prose.toLowerCase(Locale.ROOT);

        // 1. 伪代码调用语法：工具名 + 左括号（半角/全角），只扫正文
        for (String tid : toolIds) {
            String tLower = tid.toLowerCase(Locale.ROOT);
            if (containsCallSyntax(lowerProse, tLower)) {
                return "pseudo-call '" + tid + "(...)'";
            }
        }

        // 2. JSON 片段里的工具名（如 {"name":"weather"} 却没进 tool_calls），围栏代码块里的同样算
        if (lowerProse.contains("\"name\"")) {
            for (String tid : toolIds) {
                String tLower = tid.toLowerCase(Locale.ROOT);
                if (lowerProse.contains("\"" + tLower + "\"")) {
                    return "JSON tool name '" + tid + "'";
                }
            }
        }

        // 3. 中文明确动作句式：动作动词 + 紧邻（≤15 字符）的注册工具名
        for (String verb : ZH_VERBS) {
            int idx = prose.indexOf(verb);
            while (idx >= 0) {
                int end = Math.min(prose.length(), idx + verb.length() + 15);
                String window = prose.substring(idx, end).toLowerCase(Locale.ROOT);
                for (String tid : toolIds) {
                    if (window.contains(tid.toLowerCase(Locale.ROOT))) {
                        return "zh verb '" + verb + "' + tool '" + tid + "'";
                    }
                }
                idx = prose.indexOf(verb, idx + 1);
            }
        }

        // 4. 英文明确句式
        if (EN_EXPLICIT.matcher(lowerProse).find()) {
            return "en explicit tool/function call intent";
        }

        return null;
    }

    /** 工具名 + 左括号，且工具名左边界不能是字母数字（避免 my_file_generator 之类的误命中） */
    private static boolean containsCallSyntax(String lowerText, String lowerToolId) {
        int from = 0;
        while (true) {
            int i = lowerText.indexOf(lowerToolId, from);
            if (i < 0) return false;
            if (i > 0) {
                char prev = lowerText.charAt(i - 1);
                if (Character.isLetterOrDigit(prev) || prev == '_') {
                    from = i + 1;
                    continue;
                }
            }
            int after = i + lowerToolId.length();
            if (after < lowerText.length()) {
                String tail = lowerText.substring(after, Math.min(lowerText.length(), after + 2));
                if (tail.startsWith("(") || tail.startsWith(" (") || tail.startsWith("（")
                        || tail.startsWith(" （")) {
                    return true;
                }
            }
            from = i + 1;
        }
    }

    static String stripInlineCode(String text) {
        return INLINE_CODE.matcher(text).replaceAll(" ");
    }
}
