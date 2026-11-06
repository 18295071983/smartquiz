package com.oilquiz.app.ai.chat.component;

/**
 * 卡片动作里的启动目标解析（把 Agent 常写的 am 命令/包名归一成可直接启动的值）。
 * 纯逻辑，便于单测；不执行任何系统调用。
 */
final class CardActionTarget {

    private CardActionTarget() {
    }

    /**
     * 从 am start 命令里抽出包名/组件或 -d URL；其他 am 子命令由调用方拒绝。
     * 支持：am start -n pkg/.Act、am start -a ... -d https://...、am start -p pkg。
     */
    static String parseAmTarget(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = s.replaceFirst("(?i)^am\\s+", "");
        s = s.replaceFirst("(?i)^start\\s+", "");
        int n = s.indexOf("-n ");
        if (n >= 0) {
            return readAmValue(s.substring(n + 3));
        }
        int d = s.indexOf("-d ");
        if (d >= 0) {
            return readAmValue(s.substring(d + 3));
        }
        int p = s.indexOf("-p ");
        if (p >= 0) {
            return readAmValue(s.substring(p + 3));
        }
        for (String token : s.split("\\s+")) {
            if (looksLikePackage(token)) return unwrapQuotes(token);
        }
        return unwrapQuotes(s);
    }

    /**
     * 读一个 am 参数值：带引号就取到**配对引号**为止（"amapuri://…&amp;style=2"），
     * 不带引号就取到第一个空格。旧实现一律截到空格，带引号的值会把首尾引号一起带出去，
     * 后面再按首个 '/' 截包名，就得到 {@code "amapuri:} 这种根本不存在的包名。
     */
    private static String readAmValue(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        char first = v.charAt(0);
        if (first == '"' || first == '\'') {
            int end = v.indexOf(first, 1);
            if (end > 0) return v.substring(1, end).trim();
        }
        int space = v.indexOf(' ');
        return (space > 0 ? v.substring(0, space) : v).trim();
    }

    private static String unwrapQuotes(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    static boolean looksLikePackage(String value) {
        return value != null
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){2,}");
    }

    static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 是否是带 scheme 的 URI（{@code amapuri://…} / {@code file://…} / {@code mailto:…}）。
     *
     * <p>判据只认"字母开头的 scheme + 冒号"，包名没有冒号，所以不会把
     * {@code com.tencent.mm} 误判成 URI。{@code am start -d} 的语义本来就是
     * ACTION_VIEW(data)，这类值必须交给链接/协议转交层，不能按首个 '/' 截成包名。</p>
     */
    static boolean isSchemeUri(String value) {
        if (value == null) return false;
        return value.trim().matches("^[A-Za-z][A-Za-z0-9+.\\-]*:.+");
    }
}
