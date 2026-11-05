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
            String component = s.substring(n + 3).trim();
            int space = component.indexOf(' ');
            if (space > 0) component = component.substring(0, space);
            return component;
        }
        int d = s.indexOf("-d ");
        if (d >= 0) {
            String url = s.substring(d + 3).trim();
            int space = url.indexOf(' ');
            if (space > 0) url = url.substring(0, space);
            return url;
        }
        int p = s.indexOf("-p ");
        if (p >= 0) {
            String pkg = s.substring(p + 3).trim();
            int space = pkg.indexOf(' ');
            if (space > 0) pkg = pkg.substring(0, space);
            return pkg;
        }
        for (String token : s.split("\\s+")) {
            if (looksLikePackage(token)) return token;
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
}
