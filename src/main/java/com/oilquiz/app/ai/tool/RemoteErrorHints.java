package com.oilquiz.app.ai.tool;

/**
 * 远程连接失败时的**人话提示**（2026-11-14）。
 *
 * <p>为什么单独一类：以前失败直接把异常原文甩到界面上（`不可达: failed to connect to
 * /192.168.1.2 (port 8218) ...`），用户只看到"不能"，不知道下一步做什么。这里把异常/HTTP
 * 状态码翻译成「一句话原因 + 现在怎么做」，原始文本只进日志。</p>
 *
 * <p>纯 Java、不依赖 Android，方便单测。</p>
 */
public final class RemoteErrorHints {

    /** 一条可展示的提示。 */
    public static final class Hint {
        /** 一句话原因（给人看的）。 */
        public final String headline;
        /** 现在怎么做（分步骤，用 ；分隔）。 */
        public final String action;
        /** 原始异常/响应，仅用于日志。 */
        public final String raw;

        Hint(String headline, String action, String raw) {
            this.headline = headline == null ? "" : headline;
            this.action = action == null ? "" : action;
            this.raw = raw == null ? "" : raw;
        }

        /** 界面上显示的两段式文本。 */
        public String display() {
            if (action.isEmpty()) return headline;
            return headline + "\n" + action;
        }
    }

    private RemoteErrorHints() {
    }

    /** 从探测异常生成提示。 */
    public static Hint of(Throwable e) {
        String raw = e == null ? "" : (String.valueOf(e.getMessage()) + " / " + e.getClass().getSimpleName());
        String msg = raw.toLowerCase(java.util.Locale.US);

        if (msg.contains("unknownhost") || msg.contains("unable to resolve host") || msg.contains("nodename nor servname")) {
            return new Hint("找不到这台电脑（域名解析失败）",
                    "① 地址改成电脑的局域网 IP，如 http://192.168.1.2:8218；"
                            + "② 如果用域名/穿隧，确认穿透服务还在运行（电脑上 HskService 是否启动）", raw);
        }
        if (msg.contains("no route to host") || msg.contains("network is unreachable")
                || msg.contains("ehostunreach") || msg.contains("enetunreach")) {
            return new Hint("手机和电脑不在同一个网络",
                    "① 看手机 WiFi 详情里的 IP，应该和电脑同网段（如都是 192.168.1.x）；"
                            + "② 若手机是 192.168.101.x，说明无线路由器还是路由模式，要改 AP/桥接；"
                            + "③ 确认连的不是访客 WiFi", raw);
        }
        if (msg.contains("connection refused") || msg.contains("econnrefused") || msg.contains("connect failed")) {
            return new Hint("电脑上没有程序在监听这个端口",
                    "① 确认电脑上 DSH 正在运行、远程插件已启用；"
                            + "② 电脑浏览器打开 http://127.0.0.1:8218/health，应返回 {\"ok\":true,...}；"
                            + "③ 仍不行就在电脑上运行 tools/check-remote-lan.ps1 看哪一项 [FAIL]", raw);
        }
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("etimedout")) {
            return new Hint("能到电脑，但连接被挡住或没响应",
                    "① 最常见：Windows 把这个网络当成「公用」，防火墙拦入站——管理员 PowerShell 执行 "
                            + "Set-NetConnectionProfile -InterfaceAlias \"以太网\" -NetworkCategory Private；"
                            + "② 手机浏览器直接打开 http://电脑IP:8218/health 验证；"
                            + "③ 电脑上跑 tools/check-remote-lan.ps1", raw);
        }
        if (msg.contains("certificate") || msg.contains("ssl") || msg.contains("cert")) {
            return new Hint("HTTPS 证书不对",
                    "① 局域网直连请用 http:// 开头（不是 https://）；"
                            + "② 只有穿隧域名才用 https", raw);
        }
        if (msg.contains("cleartext")) {
            return new Hint("系统拦截了明文 HTTP",
                    "① 地址用 http:// 且端口 8218；② 若系统限制明文，改用 https 穿隧域名", raw);
        }
        if (msg.contains("http 401") || msg.contains("http 403") || msg.contains("unauthorized")) {
            return new Hint("令牌不对或已失效",
                    "① 重新复制电脑上 tools/dsh-plugin-smartquiz-remote/.token 的 32 位整串（不要带空格）；"
                            + "② 或在电脑打开 http://127.0.0.1:8218/pair 用手机扫码重新配对", raw);
        }
        if (msg.contains("http 404")) {
            return new Hint("地址或端口不对",
                    "① 端口必须是 8218（不是 45972 这类临时端口）；"
                            + "② 地址只填 http://电脑IP:8218，不要带 /api 之类的路径", raw);
        }
        if (msg.contains("http 502") || msg.contains("http 503") || msg.contains("http 504")) {
            return new Hint("穿隧/前置服务连不到电脑",
                    "① 电脑上确认穿隧客户端（如 HskService）已启动；"
                            + "② 同网段时直接用 http://电脑IP:8218，不走穿隧", raw);
        }
        if (msg.contains("http 500") || msg.contains("http 5")) {
            return new Hint("电脑端插件出错了",
                    "① 重启电脑上的 DSH 再试；② 仍失败看电脑 DSH 日志里 smartquiz-remote 的报错", raw);
        }
        if (msg.contains("malformed") || msg.contains("illegalargument") || msg.contains("unsupported")) {
            return new Hint("地址格式不对",
                    "① 正确格式：http://192.168.1.2:8218（有 http://，有端口，结尾不带 /）", raw);
        }
        return new Hint("连不上电脑",
                "① 手机浏览器打开 http://电脑IP:8218/health 看是否能返回 {\"ok\":true,...}；"
                        + "② 电脑上运行 tools/check-remote-lan.ps1；"
                        + "③ 详细排查见 docs/remote_dsh-usage.md 第九节", raw);
    }

    /** 从 HTTP 状态码生成提示（自检 / v2 接口用）。 */
    public static Hint ofHttp(int code, String body) {
        String raw = "HTTP " + code + " " + (body == null ? "" : body);
        if (code == 401 || code == 403) {
            return new Hint("令牌不对或已失效",
                    "① 重新复制 .token 整串；② 或在电脑 http://127.0.0.1:8218/pair 扫码重配", raw);
        }
        if (code == 404) {
            return new Hint("接口或端口不对（版本不匹配）",
                    "① 端口应为 8218；② 电脑端插件可能需要更新或重启 DSH", raw);
        }
        if (code == 409) {
            return new Hint("电脑端当前状态不允许这个操作",
                    "等电脑上这轮任务结束再试（可看状态条/面板的任务数）", raw);
        }
        if (code >= 500) {
            return new Hint("电脑端插件处理失败",
                    "重启电脑上的 DSH 再试；仍失败看电脑端日志", raw);
        }
        return new Hint("电脑返回了错误（HTTP " + code + "）",
                "在电脑上运行 tools/check-remote-lan.ps1，或看 docs/remote_dsh-usage.md 第九节", raw);
    }
}
