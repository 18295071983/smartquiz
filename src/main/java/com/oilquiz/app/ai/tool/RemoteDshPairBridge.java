package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * remote_dsh 扫码配对的结果通道与解析。
 *
 * 配对协议（电脑端桥接 /pair 页面生成的二维码内容）：
 *   dshpair://<电脑IP>:<端口>?token=<访问令牌>
 * 手机扫码 → 解析 → 自动写入 remote_dsh_config（base_url + token，并重置 session_id）。
 */
public final class RemoteDshPairBridge {

    /** 工具线程与扫码 Activity 之间的结果通道（volatile，单次配对） */
    public static volatile String lastResult;

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
        // 配对后重置会话（旧会话可能来自另一台电脑/已过期）
        ed.remove("session_id");
        ed.apply();
        return null;
    }

    /** 从解析出的 URI 提取 base_url（未校验，供工具端做最终一致性检查） */
    public static String baseUrlFrom(Uri uri) {
        return "http://" + uri.getHost() + ":" + uri.getPort();
    }
}
