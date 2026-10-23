package com.oilquiz.app.ai.chat.component;

/**
 * 填空答案的比对口径（单一写者，2026-10-23）。
 *
 * <p>为什么单独成类：题库（{@code Question.TYPE_FILL}）与作答页早就有填空/简答，
 * 聊天卡这次补上后，判题规则必须只有一份，否则同一道题在聊天里和在答题页会一个对一个错。
 * 规则刻意保守：<b>忽略空白、全角/半角差异、大小写</b>，其余必须逐字相等——
 * 不做同义词/拼音/模糊匹配，避免"答错被判对"。</p>
 *
 * <p>多个可接受答案用 {@code |}（或全角 {@code ｜}）分隔，例如
 * {@code "北京|首都北京"}。简答题不走这里：简答由用户自评。</p>
 */
public final class QuizAnswerMatcher {

    private QuizAnswerMatcher() {
    }

    /** 用户答案是否命中了任一可接受答案；空输入/空答案一律不命中 */
    public static boolean matchesAny(String userInput, String answerSpec) {
        String user = normalize(userInput);
        if (user.isEmpty() || answerSpec == null) return false;
        for (String candidate : answerSpec.split("[|｜]")) {
            String c = normalize(candidate);
            if (!c.isEmpty() && c.equals(user)) return true;
        }
        return false;
    }

    /**
     * 比对前的归一：去所有空白（含全角空格）、全角 ASCII → 半角、统一小写。
     *
     * <p>去空白是有意的：中文填空里"北 京"和"北京"、数字"3.14"和"3 . 14"应视为同一个答案；
     * 代价是"1 2"与"12"也会相等，对填空场景可接受（如需严格逐字，调用方自己先校验）。</p>
     */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3000' || Character.isWhitespace(c)) continue;
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);   // 全角 ASCII（！～）→ 半角
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
