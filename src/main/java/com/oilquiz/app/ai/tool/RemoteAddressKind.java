package com.oilquiz.app.ai.tool;

/**
 * 远程地址分类（2026-11-16）。
 *
 * <p><b>为什么需要</b>：用户把 Tailscale 组网地址（100.64.0.0/10）填进"隧道域名"，
 * 手机连接页就把它显示成「隧道域名 …（配对页会多出一张公网二维码）」——
 * 实际他走的是局域网/组网直连，看着就像"显示错乱"。</p>
 *
 * <p>三类：局域网（10/8、172.16/12、192.168/16、回环）、组网（VPN，100.64/10）、公网（其它/域名）。</p>
 */
public final class RemoteAddressKind {

    public enum Kind {
        LAN, MESH, PUBLIC
    }

    private RemoteAddressKind() {
    }

    /** 取出 host（支持带协议、端口、路径）。 */
    public static String hostOf(String address) {
        if (address == null) {
            return "";
        }
        String s = address.trim();
        if (s.isEmpty()) {
            return "";
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s;
    }

    public static boolean isLan(String address) {
        String h = hostOf(address);
        if (h.isEmpty()) {
            return false;
        }
        if ("127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h)) {
            return true;
        }
        String[] p = h.split("\\.");
        if (p.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 10) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            return a == 192 && b == 168;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 组网（VPN mesh）：Tailscale / ZeroTier 常用的 100.64.0.0/10。 */
    public static boolean isMesh(String address) {
        String h = hostOf(address);
        String[] p = h.split("\\.");
        if (p.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static Kind classify(String address) {
        if (isLan(address)) {
            return Kind.LAN;
        }
        if (isMesh(address)) {
            return Kind.MESH;
        }
        return Kind.PUBLIC;
    }

    /** 人话标签（直接用于界面）。 */
    public static String label(String address) {
        switch (classify(address)) {
            case LAN:
                return "局域网";
            case MESH:
                return "组网（VPN）";
            default:
                return "公网";
        }
    }

    /**
     * 这一行"用在哪"的补充说明 —— 不同类别对用户的意义完全不同。
     */
    public static String usageHint(String address) {
        switch (classify(address)) {
            case LAN:
                return "手机与电脑在同一局域网即可直连";
            case MESH:
                return "同一组网内的设备可直连，不需要公网";
            default:
                return "公网地址，配对页会多出一张公网二维码";
        }
    }
}
