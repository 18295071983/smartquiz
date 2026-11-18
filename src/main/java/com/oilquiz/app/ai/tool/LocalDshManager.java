package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 手机本机 DSH（Termux 里跑的真 DSH + smartquiz-remote 插件）——2026-11-18。
 *
 * <p>为什么这条是正路：登录由 Termux 里真正的 DSH 完成，插件通过官方
 * {@code deepseekAccount} 服务解析 token；App 只是 localhost 客户端，不冒充任何客户端。
 * 和 dsh-go 的"本机引擎"同构（见 README 与 {@code dsh-go-bridge}）。</p>
 *
 * <p>App 侧只做三件事：启动（RUN_COMMAND 下发）、探测（GET 插件 /status）、打开登录页
 * （本机 DSH 的 Web GUI）。插件地址默认 {@code http://127.0.0.1:8218}，可在页内改。</p>
 */
public final class LocalDshManager {

    private static final String TAG = "LocalDsh";
    private static final String PREF = "ai_prefs";
    private static final String K_PLUGIN_URL = "local_dsh_plugin_url";
    private static final String K_WEB_PORT = "local_dsh_web_port";
    /** 插件（smartquiz-remote）在本机 DSH 里的 HTTP 端口。 */
    public static final String DEFAULT_PLUGIN_URL = "http://127.0.0.1:8218";
    /** 本机 DSH Web GUI 端口（登录/管理用）。 */
    public static final int DEFAULT_WEB_PORT = 8788;

    private LocalDshManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String pluginUrl(Context c) {
        String v = prefs(c).getString(K_PLUGIN_URL, DEFAULT_PLUGIN_URL);
        if (v == null || v.trim().isEmpty()) v = DEFAULT_PLUGIN_URL;
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public static void setPluginUrl(Context c, String url) {
        prefs(c).edit().putString(K_PLUGIN_URL,
                url == null || url.trim().isEmpty() ? DEFAULT_PLUGIN_URL : url.trim()).apply();
    }

    public static int webPort(Context c) {
        return prefs(c).getInt(K_WEB_PORT, DEFAULT_WEB_PORT);
    }

    public static void setWebPort(Context c, int port) {
        prefs(c).edit().putInt(K_WEB_PORT, port <= 0 ? DEFAULT_WEB_PORT : port).apply();
    }

    /** 本机插件是否活着（2 秒超时；localhost 请求插件侧 isLocal 直接放行，不需要令牌）。 */
    public static boolean probe(Context c) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(pluginUrl(c) + "/status").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return false;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[512];
                int n = in.read(buf);
                String text = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                return text.contains("\"ok\":true");
            }
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 在 Termux 里拉起本机 DSH（后台 nohup）。阻塞到 RUN_COMMAND 回执（默认 20s 超时）。
     * 需要：Termux 已装、已授 RUN_COMMAND、且已装好 dsh（用户已验证）。
     */
    public static String start(Context c) {
        int port = webPort(c);
        String cmd = "pkill -f 'dsh web' 2>/dev/null; "
                + "nohup dsh web --host 127.0.0.1 --port " + port
                + " >$HOME/.dsh-web.log 2>&1 & echo started:" + port;
        try {
            TermuxEnvInstaller.ChannelResult r =
                    TermuxEnvInstaller.runInTermuxAndWait(c, cmd, 20);
            if (r == null) return "启动失败：没有回执";
            if (!r.ok) return "启动失败：" + (r.error == null ? "未知错误" : r.error);
            return "已在 Termux 后台启动 dsh web（端口 " + port + "）";
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t.getMessage());
            return "启动失败：" + t.getMessage();
        }
    }

    /** 停掉本机 DSH（pkill）。 */
    public static String stop(Context c) {
        try {
            TermuxEnvInstaller.ChannelResult r = TermuxEnvInstaller.runInTermuxAndWait(
                    c, "pkill -f 'dsh web' 2>/dev/null; echo stopped", 15);
            return r != null && r.ok ? "已停止本机 DSH" : "停止失败：" + (r == null ? "无回执" : r.error);
        } catch (Throwable t) {
            return "停止失败：" + t.getMessage();
        }
    }

    /** 打开本机 DSH 的 Web GUI（登录 DeepSeek 账号就在这里）。 */
    public static void openLogin(Context c) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://127.0.0.1:" + webPort(c) + "/"));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "openLogin failed: " + t.getMessage());
        }
    }
}
