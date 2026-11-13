package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「快捷自测」：点一下就按顺序跑完连接链路上的每一步，直接告诉用户**卡在哪、为什么、怎么修**
 * （2026-11-14）。
 *
 * <p>为什么不复用 /selfcheck：那个查的是"连上之后电脑端状态"，前提是已经连上。
 * 这个查的是"为什么连不上"——地址格式 → 令牌 → 手机网络 → DNS → TCP → /health → /status。
 * 每一步独立可失败，后面的步骤不会因为前一步失败而跳过（能多给一条线索就多给一条）。</p>
 *
 * <p>阻塞方法，调用方必须放后台线程。</p>
 */
public final class RemoteSelfTest {

    /** 单步结果。 */
    public static final class Step {
        public final String name;
        /** PASS / FAIL / WARN */
        public final String level;
        public final String detail;
        public final String hint;

        Step(String name, String level, String detail, String hint) {
            this.name = name == null ? "" : name;
            this.level = level == null ? "FAIL" : level;
            this.detail = detail == null ? "" : detail;
            this.hint = hint == null ? "" : hint;
        }

        public boolean ok() {
            return !"FAIL".equals(level);
        }

        String line() {
            String mark = "PASS".equals(level) ? "✅" : ("WARN".equals(level) ? "⚠️" : "❌");
            StringBuilder sb = new StringBuilder();
            sb.append(mark).append(' ').append(name);
            if (!detail.isEmpty()) sb.append("：").append(detail);
            if ("FAIL".equals(level) && !hint.isEmpty()) sb.append('\n').append("    → ").append(hint);
            return sb.toString();
        }
    }

    /** 一次自测的完整结果。 */
    public static final class Report {
        public final List<Step> steps = new ArrayList<>();
        public String target = "";
        public long elapsedMs;

        public boolean allOk() {
            for (Step s : steps) {
                if (!s.ok()) return false;
            }
            return true;
        }

        /** 第一条失败原因的提示（给状态条/Toast 用）。 */
        public String firstFailureHint() {
            for (Step s : steps) {
                if (!s.ok()) return s.name + "：" + s.detail + (s.hint.isEmpty() ? "" : "\n" + s.hint);
            }
            return "";
        }

        /** 弹窗正文。 */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Step s : steps) {
                sb.append(s.line()).append('\n');
            }
            sb.append('\n');
            if (allOk()) {
                sb.append("结论：全链路通过，可以点「连接」了（").append(elapsedMs).append("ms）");
            } else {
                sb.append("结论：先修上面标 ❌ 的那一步（").append(elapsedMs).append("ms）");
            }
            return sb.toString();
        }

        /** 可复制的诊断文本（带原始细节）。 */
        public String diagnostic() {
            StringBuilder sb = new StringBuilder();
            sb.append("DSH 远程快捷自测\n目标：").append(target).append('\n').append('\n');
            for (Step s : steps) {
                sb.append('[').append(s.level).append("] ").append(s.name);
                if (!s.detail.isEmpty()) sb.append(" | ").append(s.detail);
                if (!s.hint.isEmpty()) sb.append(" | 建议: ").append(s.hint);
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    private RemoteSelfTest() {
    }

    /**
     * 跑一遍自测。
     *
     * @param baseUrl 当前配置的地址（可为空）
     * @param token   当前令牌（可为空）
     */
    public static Report run(Context c, String baseUrl, String token) {
        long t0 = System.currentTimeMillis();
        Report r = new Report();
        String url = baseUrl == null ? "" : baseUrl.trim();
        String tok = token == null ? "" : token.trim();
        r.target = url.isEmpty() ? "(未配置)" : url;

        // 1) 地址格式
        URL parsed = null;
        try {
            if (url.isEmpty()) {
                r.steps.add(new Step("地址格式", "FAIL", "地址为空",
                        "填 http://电脑IP:8218（例：http://192.168.1.2:8218）"));
            } else {
                parsed = new URL(url);
                String scheme = parsed.getProtocol() == null ? "" : parsed.getProtocol().toLowerCase(Locale.US);
                int port = parsed.getPort() > 0 ? parsed.getPort() : ("https".equals(scheme) ? 443 : 80);
                String host = parsed.getHost() == null ? "" : parsed.getHost();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    r.steps.add(new Step("地址格式", "FAIL", "协议不是 http/https：" + scheme,
                            "地址以 http:// 开头"));
                    parsed = null;
                } else if (host.isEmpty()) {
                    r.steps.add(new Step("地址格式", "FAIL", "没有主机名", "例：http://192.168.1.2:8218"));
                    parsed = null;
                } else if (port != 8218) {
                    r.steps.add(new Step("地址格式", "FAIL", "端口是 " + port + "，不是 8218",
                            "端口只填 8218；手机上看到的 45972 这类数字不用填"));
                    parsed = null;
                } else {
                    r.steps.add(new Step("地址格式", "PASS", scheme + "://" + host + ":" + port, ""));
                }
            }
        } catch (Exception e) {
            r.steps.add(new Step("地址格式", "FAIL", "解析失败：" + brief(e),
                    "正确格式 http://192.168.1.2:8218（有 http://、有端口、结尾不带 /）"));
            parsed = null;
        }

        // 2) 令牌
        if (tok.isEmpty()) {
            r.steps.add(new Step("令牌", "FAIL", "未填",
                    "在电脑上重新复制令牌；或直接用「扫码配对」自动填"));
        } else if (tok.length() != 32) {
            r.steps.add(new Step("令牌", "FAIL", "长度 " + tok.length() + "（应为 32）",
                    "重新复制整串，不要带空格或换行"));
        } else if (!tok.matches("[0-9a-fA-F]{32}")) {
            r.steps.add(new Step("令牌", "WARN", "长度对，但含非十六进制字符",
                    "确认复制的是电脑上显示的令牌整串，不是别的密码"));
        } else {
            r.steps.add(new Step("令牌", "PASS", "长度 32", ""));
        }

        // 3) 手机网络
        r.steps.add(networkStep(c));

        // 4) DNS
        String host = parsed == null ? "" : (parsed.getHost() == null ? "" : parsed.getHost());
        InetAddress addr = null;
        if (!host.isEmpty()) {
            try {
                long d0 = System.currentTimeMillis();
                addr = InetAddress.getByName(host);
                r.steps.add(new Step("域名解析", "PASS",
                        host + " → " + addr.getHostAddress() + "（" + (System.currentTimeMillis() - d0) + "ms）", ""));
            } catch (Exception e) {
                r.steps.add(new Step("域名解析", "FAIL", brief(e),
                        "域名可能过期/写错；同网段请直接填电脑 IP（如 192.168.1.2）"));
            }
        }

        // 5) TCP
        boolean tcpOk = false;
        if (addr != null) {
            try (Socket s = new Socket()) {
                long c0 = System.currentTimeMillis();
                s.connect(new InetSocketAddress(addr, 8218), 3000);
                tcpOk = true;
                r.steps.add(new Step("TCP 8218", "PASS", "已连通（" + (System.currentTimeMillis() - c0) + "ms）", ""));
            } catch (java.net.SocketTimeoutException e) {
                r.steps.add(new Step("TCP 8218", "FAIL", "连接超时",
                        "电脑的安全设置把手机挡住了：双击电脑上的 tools\\fix-remote-lan.cmd，弹窗点「是」，跑完再重试"));
            } catch (java.net.ConnectException e) {
                r.steps.add(new Step("TCP 8218", "FAIL", "连接被拒（端口没开）",
                        "电脑上的 DSH 没在运行：打开 DSH 等 10 秒；还不行就双击电脑上的 tools\\fix-remote-lan.cmd"));
            } catch (java.net.NoRouteToHostException e) {
                r.steps.add(new Step("TCP 8218", "FAIL", "没有路由到这台电脑",
                        "手机不在电脑的网络里：连同一个 WiFi；手机 IP 是 192.168.101.x 的话，无线路由器要改桥接"));
            } catch (Exception e) {
                r.steps.add(new Step("TCP 8218", "FAIL", brief(e),
                        "先点「快捷自测」重试；仍不行就在电脑上双击 tools\\fix-remote-lan.cmd"));
            }
        }

        // 6) /health（不需要令牌）
        if (tcpOk && parsed != null) {
            try {
                String body = httpGet(url + "/health", null, 4000);
                boolean ok = body != null && body.contains("\"ok\"") && body.contains("true");
                r.steps.add(new Step("/health", ok ? "PASS" : "WARN",
                        ok ? "返回 ok:true" : "返回内容不像插件：" + cut(body, 60),
                        ok ? "" : "电脑上 8218 端口被别的程序占了：重启 DSH 或重启电脑"));
            } catch (Exception e) {
                r.steps.add(new Step("/health", "FAIL", brief(e),
                        "能连端口但 HTTP 不通：确认电脑端是 DSH 远程插件，而不是别的服务"));
            }

            // 7) /status（要令牌）
            if (!tok.isEmpty()) {
                try {
                    String body = httpGet(url + "/status", tok, 4000);
                    boolean ok = body != null && body.contains("\"ok\"") && body.contains("true");
                    r.steps.add(new Step("/status（鉴权）", ok ? "PASS" : "WARN",
                            ok ? "令牌有效" : "返回： " + cut(body, 60),
                            ok ? "" : "配对信息过期：在电脑上打开配对页，用手机重新扫码"));
                } catch (Exception e) {
                    String msg = brief(e);
                    if (msg.contains("401") || msg.contains("403")) {
                        r.steps.add(new Step("/status（鉴权）", "FAIL", "HTTP 401（令牌无效）",
                                "在电脑上打开配对页，用手机重新扫码（最快）"));
                    } else {
                        r.steps.add(new Step("/status（鉴权）", "WARN", msg, "不影响连接，可忽略"));
                    }
                }
            }
        }

        r.elapsedMs = System.currentTimeMillis() - t0;
        return r;
    }

    private static Step networkStep(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                return new Step("手机网络", "FAIL", "当前没有网络", "先连上 WiFi 或移动数据");
            }
            int type = ni.getType();
            if (type == ConnectivityManager.TYPE_WIFI) {
                String ssid = "";
                try {
                    WifiManager wm = (WifiManager) c.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    ssid = wm == null ? "" : String.valueOf(wm.getConnectionInfo().getSSID());
                    if ("null".equals(ssid)) ssid = "";
                    ssid = ssid.replace("\"", "");
                } catch (Throwable ignored) {
                }
                return new Step("手机网络", "PASS",
                        "WiFi" + (ssid.isEmpty() ? "" : " · " + ssid), "");
            }
            if (type == ConnectivityManager.TYPE_MOBILE) {
                return new Step("手机网络", "WARN", "移动数据",
                        "电脑在局域网里，移动数据通常连不到；请连同一 WiFi");
            }
            return new Step("手机网络", "PASS", ni.getTypeName(), "");
        } catch (Throwable t) {
            return new Step("手机网络", "WARN", "读不到网络状态", "");
        }
    }

    private static String httpGet(String url, String token, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("GET");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = is == null ? "" : readAll(is);
            if (code >= 400) {
                throw new java.io.IOException("HTTP " + code + " " + cut(body, 80));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String brief(Throwable e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        if (m.length() > 90) m = m.substring(0, 90) + "…";
        return m.isEmpty() ? (e == null ? "未知错误" : e.getClass().getSimpleName()) : m;
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
