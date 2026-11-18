package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.util.PublicStorageWriter;

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
    /** 本机 DSH Web GUI 端口（登录/管理用；dsh-phone 默认 7788，桥接 8218）。 */
    public static final int DEFAULT_WEB_PORT = 7788;

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

    /**
     * 2026-11-19：本机 DSH 插件的访问令牌。
     *
     * <p>插件首次启动会生成 32 位 hex 令牌并存进容器里的 {@code /root/plugin/.token}；
     * 容器脚本会把它同步到 {@code Download/OilQuiz/termux_env/dsh_token.txt}，
     * App 读这个文件，避免用户手抄。读不到返回空串（退回旧的 "local"）。</p>
     */
    public static String pluginToken(Context c) {
        SharedPreferences p = prefs(c);
        String cached = p.getString("local_dsh_token", "");
        if (cached != null && !cached.isEmpty()) return cached;
        String t = readTokenFile(c);
        if (!t.isEmpty()) p.edit().putString("local_dsh_token", t).apply();
        return t;
    }

    private static String readTokenFile(Context c) {
        try {
            String t = PublicStorageWriter.readTail(c, "termux_env", "dsh_token.txt", 128);
            if (t == null) return "";
            return t.trim().replace("\n", "").replace("\r", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 2026-11-19：拿不到 token 文件时（Termux 直接写的文件 App 的 MediaStore 可能看不到），
     * 走一次命令通道 `cat` 取回来并缓存。会阻塞几秒，只在后台线程调用。
     */
    public static String pluginTokenFresh(Context c) {
        String t = pluginToken(c);
        if (!t.isEmpty()) return t;
        try {
            TermuxEnvInstaller.ChannelResult r = TermuxEnvInstaller.runInTermuxAndWait(
                    c, "cat /sdcard/Download/OilQuiz/termux_env/dsh_token.txt", 10);
            if (r != null && r.ok && r.stdout != null) {
                String v = r.stdout.trim().replace("\n", "").replace("\r", "");
                if (!v.isEmpty()) {
                    prefs(c).edit().putString("local_dsh_token", v).apply();
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
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
            // 2026-11-19：桥接现在强制 Bearer（8218 不算 isLocal），探测也要带令牌
            String tok = pluginTokenFresh(c);
            if (!tok.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + tok);
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
        // 先体检 Termux 通道：不通就别发命令白等 60 秒
        TermuxChannel.Status st = TermuxChannel.check(c);
        if (!st.ok) {
            if (st.needsAllowExternalApps()) {
                // 一次性设置：自动复制修复命令并打开 Termux，用户粘一次即可
                String msg = TermuxChannel.copyFixAndOpen(c);
                return "Termux 通道还差一次性设置（allow-external-apps）。\n" + msg
                        + "\n粘完回 App 再点「启动」。";
            }
            return "Termux 通道不可用：" + st.summary()
                    + "\n先在「远程连接」页点「通道检查 / 修复（Termux）」。";
        }
        // 2026-11-18：Termux 读不到 /sdcard 时 dsh-phone 的 URL 落盘会失败，先拦下来
        Boolean canRead = TermuxEnvInstaller.termuxPublicReadable(c);
        if (Boolean.FALSE.equals(canRead)) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "termux-setup-storage", TermuxEnvInstaller.TERMUX_STORAGE_FIX_COMMAND));
                }
            } catch (Throwable ignored) {
            }
            TermuxEnvInstaller.openTermux(c);
            return "Termux 读不到公共目录：已复制 termux-setup-storage 并打开 Termux，"
                    + "粘贴执行、点允许后回来再点「启动」。";
        }
        int port = webPort(c);
        // 用手机工作区实测过的 dsh-phone（网页 7788 + 桥接 8218 + token URL 落盘）
        String cmd = "export PATH=\"$PREFIX/bin:$PATH\"; "
                + "DSH_PORT=" + port + " dsh-phone start; "
                + "mkdir -p /sdcard/Download/OilQuiz/termux_env 2>/dev/null; "
                + "dsh-phone url > /sdcard/Download/OilQuiz/termux_env/dsh_url.txt 2>/dev/null; "
                + "dsh-phone status";
        try {
            TermuxEnvInstaller.ChannelResult r =
                    TermuxEnvInstaller.runInTermuxAndWait(c, cmd, 60);
            if (r == null) return "启动失败：没有回执";
            if (!r.ok) return "启动失败：" + (r.error == null ? "未知错误" : r.error);
            return "已启动本机 DSH（网页 " + port + " / 桥接 8218）\n" + trim(r.stdout);
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t.getMessage());
            return "启动失败：" + t.getMessage();
        }
    }

    /** 停掉本机 DSH（pkill）。 */
    public static String stop(Context c) {
        try {
            TermuxEnvInstaller.ChannelResult r = TermuxEnvInstaller.runInTermuxAndWait(
                    c, "export PATH=\"$PREFIX/bin:$PATH\"; dsh-phone stop; echo stopped", 20);
            return r != null && r.ok ? "已停止本机 DSH" : "停止失败：" + (r == null ? "无回执" : r.error);
        } catch (Throwable t) {
            return "停止失败：" + t.getMessage();
        }
    }

    /**
     * 打开本机 DSH 的 Web GUI（登录 DeepSeek 账号就在这里）。
     * 优先用 dsh-phone 落盘的带 token URL（每次启动 token 都会变）。
     */
    public static void openLogin(Context c) {
        String url = "http://127.0.0.1:" + webPort(c) + "/";
        try {
            String saved = PublicStorageWriter.readTail(c, "termux_env", "dsh_url.txt", 2048);
            if (saved != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+")
                        .matcher(saved);
                if (m.find()) url = m.group();
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "openLogin failed: " + t.getMessage());
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
