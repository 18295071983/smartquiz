package com.oilquiz.app.ai.tool;

/**
 * 远程连接失败提示（2026-11-14，第三版：按用户要求压到最短）。
 *
 * <p>用户原话："这么简单，就两步嘛，一个就是网络不通不是不在一个网段，另外一个就是你设置成公网了，
 * 要改成专用就行了"。所以文案只保留**两个主要原因**，其余情况各一行；不再给步骤列表。</p>
 */
public final class RemoteErrorHints {

    /** 电脑上的一键修复入口。 */
    public static final String FIX_TOOL = "tools\\fix-remote-lan.cmd";

    public static final class Hint {
        public final String headline;
        public final String action;
        public final String raw;

        Hint(String headline, String action, String raw) {
            this.headline = headline == null ? "" : headline;
            this.action = action == null ? "" : action;
            this.raw = raw == null ? "" : raw;
        }

        /** 两行：原因 + 怎么办（不超过两行）。 */
        public String display() {
            return action.isEmpty() ? headline : headline + "\n" + action;
        }
    }

    private RemoteErrorHints() {
    }

    /** 网络层异常：只有两种原因（不同网段 / 公用网络拦了）。 */
    public static Hint of(Throwable e) {
        String raw = e == null ? "" : (String.valueOf(e.getMessage()) + " / " + e.getClass().getSimpleName());
        String msg = raw.toLowerCase(java.util.Locale.US);

        if (msg.contains("no route to host") || msg.contains("network is unreachable")
                || msg.contains("ehostunreach") || msg.contains("enetunreach")
                || msg.contains("unknownhost") || msg.contains("unable to resolve host")
                || msg.contains("nodename nor servname")) {
            return new Hint("网络不通：手机和电脑不在同一个网",
                    "连同一个 WiFi；手机 IP 是 192.168.101.x 就让无线路由器改桥接", raw);
        }
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("etimedout")) {
            return new Hint("网络不通：电脑网络是「公用」，被拦了",
                    "电脑上双击 " + FIX_TOOL + " → 弹窗点「是」，跑完再试", raw);
        }
        if (msg.contains("connection refused") || msg.contains("econnrefused") || msg.contains("connect failed")) {
            return new Hint("电脑上的 DSH 没开",
                    "在电脑上打开 DSH，等 10 秒再点「快捷自测」", raw);
        }
        if (msg.contains("http 401") || msg.contains("http 403") || msg.contains("unauthorized")) {
            return new Hint("配对过期了",
                    "在电脑上打开配对页，用手机重新扫码一次", raw);
        }
        if (msg.contains("http 404")) {
            return new Hint("地址不对",
                    "只填 http://电脑IP:8218（端口 8218）", raw);
        }
        if (msg.contains("certificate") || msg.contains("ssl") || msg.contains("cert")
                || msg.contains("cleartext") || msg.contains("malformed")) {
            return new Hint("地址格式不对",
                    "用 http://电脑IP:8218，不要 https、不要带路径", raw);
        }
        if (msg.contains("http 5")) {
            return new Hint("电脑端出错了",
                    "重启电脑上的 DSH 再试", raw);
        }
        return new Hint("网络不通：手机和电脑不在同一个网",
                "连同一个 WiFi；还不行就在电脑上双击 " + FIX_TOOL + " 点「是」", raw);
    }

    /** HTTP 状态码：同样压到两行。 */
    public static Hint ofHttp(int code, String body) {
        String raw = "HTTP " + code + " " + (body == null ? "" : body);
        if (code == 401 || code == 403) {
            return new Hint("配对过期了", "在电脑上打开配对页，用手机重新扫码一次", raw);
        }
        if (code == 404) {
            return new Hint("地址不对", "只填 http://电脑IP:8218", raw);
        }
        if (code == 409) {
            return new Hint("电脑正忙", "等这一轮跑完再试", raw);
        }
        if (code >= 500) {
            return new Hint("电脑端出错了", "重启电脑上的 DSH 再试", raw);
        }
        return new Hint("电脑返回了错误", "点「快捷自测」看哪一步不对", raw);
    }
}
