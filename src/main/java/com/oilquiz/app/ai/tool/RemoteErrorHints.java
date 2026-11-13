package com.oilquiz.app.ai.tool;

/**
 * 远程连接失败时的**人话提示**（2026-11-14，第二版）。
 *
 * <p>第一版把 PowerShell 命令塞给用户，被指出"用户会弄还用你教"。第二版原则：</p>
 * <ul>
 *   <li>只说用户能做的动作（打开 DSH / 连同一个 WiFi / 双击一个文件 / 点重试）</li>
 *   <li>不出现命令行、不出现英文异常、不提端口术语以外的概念</li>
 *   <li>需要电脑端修的场景，统一引导到「双击 tools\fix-remote-lan.cmd」这一个入口</li>
 *   <li>最多 3 步，第一步一定是最简单的</li>
 * </ul>
 *
 * <p>原始异常保留在 {@link Hint#raw}，只写日志。</p>
 */
public final class RemoteErrorHints {

    /** 电脑上的一键修复入口（用户视角只认这个文件）。 */
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

        public String display() {
            if (action.isEmpty()) return headline;
            return headline + "\n" + action;
        }
    }

    private RemoteErrorHints() {
    }

    public static Hint of(Throwable e) {
        String raw = e == null ? "" : (String.valueOf(e.getMessage()) + " / " + e.getClass().getSimpleName());
        String msg = raw.toLowerCase(java.util.Locale.US);

        if (msg.contains("unknownhost") || msg.contains("unable to resolve host") || msg.contains("nodename nor servname")) {
            return new Hint("地址里的电脑名找不到",
                    "① 把地址改成 http://电脑IP:8218（在电脑上开 DSH 能看到 IP，或用「扫码配对」自动填）\n"
                            + "② 如果地址里是域名，说明穿透程序没开：在电脑上打开它再试", raw);
        }
        if (msg.contains("no route to host") || msg.contains("network is unreachable")
                || msg.contains("ehostunreach") || msg.contains("enetunreach")) {
            return new Hint("手机和电脑不在同一个网络",
                    "① 手机连上和电脑同一个 WiFi（不是访客 WiFi）\n"
                            + "② 看手机 IP：如果是 192.168.101.x 开头，说明无线路由器没桥接，需要让人把无线路由改成『桥接/AP 模式』", raw);
        }
        if (msg.contains("connection refused") || msg.contains("econnrefused") || msg.contains("connect failed")) {
            return new Hint("电脑上的 DSH 没在运行",
                    "① 在电脑上打开 DSH，等 10 秒\n"
                            + "② 回到这里点「快捷自测」\n"
                            + "③ 还不行：在电脑上双击 " + FIX_TOOL + "，弹窗点「是」，跑完再重试", raw);
        }
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("etimedout")) {
            return new Hint("电脑的安全设置把手机挡住了",
                    "① 在电脑上双击 " + FIX_TOOL + "，弹窗里点「是」，等它自己跑完\n"
                            + "② 回到这里点「快捷自测」\n"
                            + "（它只做两件事：允许手机访问、放行 DSH 端口，不会改别的东西）", raw);
        }
        if (msg.contains("certificate") || msg.contains("ssl") || msg.contains("cert")) {
            return new Hint("地址开头写错了",
                    "在家里连电脑要用 http:// 开头，不要用 https://\n"
                            + "例：http://192.168.1.2:8218", raw);
        }
        if (msg.contains("cleartext")) {
            return new Hint("地址格式不对",
                    "用 http://电脑IP:8218 这种格式", raw);
        }
        if (msg.contains("http 401") || msg.contains("http 403") || msg.contains("unauthorized")) {
            return new Hint("配对信息过期了",
                    "① 在电脑上打开配对页，用手机重新扫码（最快）\n"
                            + "② 或在电脑上重新复制一次令牌，粘贴到下面的令牌框", raw);
        }
        if (msg.contains("http 404")) {
            return new Hint("地址或端口不对",
                    "地址只填 http://电脑IP:8218\n"
                            + "端口必须是 8218；手机上看到的 45972 这类数字不是端口，不用填", raw);
        }
        if (msg.contains("http 502") || msg.contains("http 503") || msg.contains("http 504")) {
            return new Hint("电脑不在线（外网访问时）",
                    "在家请让手机连同一个 WiFi，直接连电脑\n"
                            + "在外网才需要穿透：在电脑上打开穿透程序再试", raw);
        }
        if (msg.contains("http 5")) {
            return new Hint("电脑端程序出错了",
                    "在电脑上重启 DSH，等 10 秒再点「快捷自测」", raw);
        }
        if (msg.contains("malformed") || msg.contains("illegalargument") || msg.contains("unsupported")) {
            return new Hint("地址格式不对",
                    "正确写法：http://192.168.1.2:8218\n"
                            + "要有 http://、要有冒号和端口、结尾不要带 /", raw);
        }
        return new Hint("连不上电脑",
                "① 点「快捷自测」，它会告诉你卡在哪一步\n"
                        + "② 电脑上双击 " + FIX_TOOL + "，弹窗点「是」\n"
                        + "③ 确认手机和电脑连的是同一个 WiFi", raw);
    }

    public static Hint ofHttp(int code, String body) {
        String raw = "HTTP " + code + " " + (body == null ? "" : body);
        if (code == 401 || code == 403) {
            return new Hint("配对信息过期了",
                    "① 在电脑上打开配对页，用手机重新扫码（最快）\n"
                            + "② 或重新复制电脑上的令牌，粘贴后再连", raw);
        }
        if (code == 404) {
            return new Hint("接口对不上（多半是端口或版本）",
                    "地址只填 http://电脑IP:8218；如果电脑刚升级过 DSH，重启一次再试", raw);
        }
        if (code == 409) {
            return new Hint("电脑正在忙",
                    "等电脑上这一轮任务跑完，再点一次", raw);
        }
        if (code >= 500) {
            return new Hint("电脑端程序出错了",
                    "在电脑上重启 DSH，等 10 秒再试", raw);
        }
        return new Hint("电脑返回了错误",
                "点「快捷自测」看具体是哪一步；还不行就在电脑上双击 " + FIX_TOOL, raw);
    }
}
