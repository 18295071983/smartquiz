package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * remote_dsh 扫码配对的结果通道与解析。
 *
 * 配对协议（电脑端配对页生成的二维码内容）：
 *   dshpair://<电脑IP>:<端口>?token=<主令牌>
 *   dshpair://<host>?scheme=https&port=443&token=<主令牌>      ← 隧道/公网形态
 * 手机扫码 → 解析 → 写入 remote_dsh_config（base_url + token，并重置 session_id）。
 *
 * 【设备令牌（2026-10-06 新增）】二维码里带的是**主令牌**（=主人凭证，能改配置、能跑 /exec）。
 * 以前手机就一直揣着它用，后果是电脑端 /status 的 devices 恒为 0：面板上看不出任何设备连着，
 * 想踢掉某台设备只能换主令牌（所有设备一起掉线）。
 * 现在配对时多做一步：拿二维码里的主令牌去 POST /pair/claim 换一张**设备令牌**
 * （权限更小、可单独吊销、last_seen 可追踪），并优先使用它。
 * 换不到（电脑端还没重载到新版插件 / 网络不通）不算配对失败，退回主令牌继续可用，
 * 由 claimDeviceToken 的返回值说明情况，调用方把结果告诉用户。
 */
public final class RemoteDshPairBridge {

    private static final String TAG = "RemoteDshPair";

    /** 工具线程与扫码 Activity 之间的结果通道（volatile，单次配对） */
    public static volatile String lastResult;

    /** 配对成功且已换成设备令牌 */
    public static final String RESULT_OK = "OK";
    /** 配对成功，但设备令牌没换成，仍在使用主令牌（电脑端版本旧或网络失败） */
    public static final String RESULT_OK_MAIN_TOKEN = "OK_MAIN_TOKEN";

    private RemoteDshPairBridge() {
    }

    /** 解析配对文本并保存配置。成功返回 null，失败返回错误信息（用户可见）。 */
    public static String parseAndSave(Context context, String text) {
        if (text == null || text.trim().isEmpty()) {
            return "扫码内容为空，请对准电脑屏幕上的二维码";
        }
        String s = text.trim();
        Uri uri = Uri.parse(s);
        if (!"dshpair".equalsIgnoreCase(uri.getScheme())) {
            return "不是有效的配对码（应为 dshpair:// 开头，来自电脑配对页二维码）:\n" + s;
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String token = uri.getQueryParameter("token");
        // 公网/隧道（花生壳、Cloudflare 等）形态：dshpair://<host>?scheme=https&port=443&token=...
        // —— 隧道地址常常是 https 默认端口（URL 里没有端口），旧解析因 port<=0 直接判失败
        String scheme = uri.getQueryParameter("scheme");
        String portParam = uri.getQueryParameter("port");
        if (port <= 0 && portParam != null && !portParam.trim().isEmpty()) {
            try {
                port = Integer.parseInt(portParam.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (scheme == null || scheme.trim().isEmpty()) {
            scheme = port > 0 ? "http" : "https";
        } else {
            scheme = scheme.trim().toLowerCase();
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "配对码里的 scheme 不支持（只支持 http/https）:\n" + s;
        }
        if (host == null || host.isEmpty() || token == null || token.trim().isEmpty()) {
            return "配对码缺少电脑地址或令牌（host/token）:\n" + s;
        }
        boolean defaultPort = port <= 0
                || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        String baseUrl = defaultPort ? (scheme + "://" + host) : (scheme + "://" + host + ":" + port);
        SharedPreferences.Editor ed = context
                .getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE).edit();
        ed.putString("base_url", baseUrl);
        ed.putString("token", token.trim());
        // 主令牌单独留一份：设备令牌被吊销或换不到时要退回它，也是"重新登记设备"的凭据
        ed.putString(RemoteDshTool.KEY_MAIN_TOKEN, token.trim());
        // 新配对先清掉旧设备令牌（可能来自另一台电脑，或已被吊销）
        ed.remove(RemoteDshTool.KEY_DEVICE_TOKEN);
        // 配对 = 连接（用户主动断开过的话，扫码后应恢复可用）
        ed.putBoolean("connected", true);
        // 配对后重置会话（旧会话可能来自另一台电脑/已过期）
        ed.remove("session_id");
        ed.apply();
        return null;
    }

    /**
     * 用二维码里的主令牌换取设备令牌并落盘（供扫码流程在**子线程**调用，内部会阻塞做一次 HTTP）。
     *
     * @return 成功返回 null；失败返回原因（用户可见，调用方据此提示"仍可用但没登记设备"）
     */
    public static String claimDeviceToken(Context context, String baseUrl, String mainToken) {
        if (baseUrl == null || baseUrl.trim().isEmpty() || mainToken == null || mainToken.trim().isEmpty()) {
            return "缺少电脑地址或令牌";
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl.trim() + "/pair/claim").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + mainToken.trim());
            conn.setDoOutput(true);
            String body = "{\"device_name\":\"" + escapeJson(deviceName()) + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in != null ? readAll(in) : "";
            if (code != 200) {
                Log.w(TAG, "claim 失败 HTTP " + code + ": " + text);
                return "电脑端拒绝登记设备（HTTP " + code + "）"
                        + (text != null && text.contains("need_code_or_token")
                           ? "：电脑端插件是旧版，请更新电脑端插件后重新扫码" : "");
            }
            org.json.JSONObject jo = new org.json.JSONObject(text);
            String dt = jo.optString("device_token", "");
            String did = jo.optString("device_id", "");
            if (dt.isEmpty()) {
                return "电脑端未返回设备令牌";
            }
            context.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE).edit()
                    .putString(RemoteDshTool.KEY_DEVICE_TOKEN, dt)
                    .putString(RemoteDshTool.KEY_DEVICE_ID, did)
                    .apply();
            Log.i(TAG, "已登记设备令牌 id=" + did + " via=" + jo.optString("via", "?"));
            return null;
        } catch (Exception e) {
            Log.w(TAG, "claim 异常: " + e.getMessage(), e);
            return "登记设备失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 设备名（电脑端设备表显示用）。有些 ROM 的 MODEL 带非法字符，这里清掉。 */
    private static String deviceName() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        String brand = Build.BRAND == null ? "" : Build.BRAND.trim();
        if (m.isEmpty()) m = "Android 手机";
        String name = (brand.isEmpty() || m.toLowerCase().startsWith(brand.toLowerCase())) ? m : brand + " " + m;
        // 去掉代理对之外的控制字符/非法码元，避免电脑端存出 "??" 这种乱名
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c)) continue;
            if (Character.isHighSurrogate(c) && (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1)))) continue;
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(name.charAt(i - 1)))) continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "Android 手机" : out;
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /** 从解析出的 URI 提取 base_url（未校验，供工具端做最终一致性检查） */
    public static String baseUrlFrom(Uri uri) {
        return "http://" + uri.getHost() + ":" + uri.getPort();
    }
}
