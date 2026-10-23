package com.oilquiz.app.ai.chat.component;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 位置卡（2026-10-23）：地址 + 经纬度 + 可直接打开地图。
 *
 * <p>此前 {@code location} 工具的结果只渲染成 info_card 的纯文本（城市/经纬度/精度），
 * 用户拿到坐标没有下一步动作。本卡把这些字段结构化，并提供两个真实动作：
 * 用 {@code geo:} 调系统地图、复制坐标。</p>
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "当前位置（可选）",
 *   "address": "详细地址（可选）",
 *   "city": "城市（可选）",
 *   "latitude": 31.2304, "longitude": 121.4737,   // 数字或字符串都行
 *   "accuracy": "20（可选，米）",
 *   "provider": "gps（可选）"
 * }
 * </pre>
 * 没有经纬度时只展示地址文本，不给"在地图中打开"。
 */
public class LocationCardView implements ChatComponent {

    @Override
    public String getType() {
        return "location";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        JSONObject p = data.props;
        // 这里刻意不用 TextUtils：canRender 是纯判定，JVM 单测直接跑（形状矩阵测试依赖）
        return !p.optString("address", "").isEmpty()
                || !p.optString("city", "").isEmpty()
                || coords(p) != null;
    }

    @Override
    public android.view.View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String title = p.optString("title", "");
        String address = p.optString("address", "");
        String city = p.optString("city", "");
        String accuracy = p.optString("accuracy", "");
        String provider = p.optString("provider", "");
        final double[] latLng = coords(p);

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_location)
                .setHeader(TextUtils.isEmpty(title)
                        ? context.getString(R.string.card_location_title) : title,
                        TextUtils.isEmpty(city) ? null : city);

        if (!TextUtils.isEmpty(address)) {
            shell.addBodyText(address, ComponentTheme.bodyTextSize(),
                    ComponentTheme.textPrimary(context), false);
        }
        if (latLng != null) {
            String coordsText = String.format(Locale.US, "%.5f, %.5f", latLng[0], latLng[1]);
            StringBuilder meta = new StringBuilder(coordsText);
            if (!TextUtils.isEmpty(accuracy)) {
                meta.append("  ±").append(accuracy).append(" m");
            }
            if (!TextUtils.isEmpty(provider)) {
                meta.append("  · ").append(provider);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ComponentTheme.dp(context, 4);
            TextView coordsTv = new TextView(context);
            coordsTv.setText(meta.toString());
            coordsTv.setTextSize(ComponentTheme.metaTextSize());
            coordsTv.setTextColor(ComponentTheme.textTertiary(context));
            shell.addBody(coordsTv, lp);

            final String uri = geoUri(latLng[0], latLng[1],
                    TextUtils.isEmpty(address) ? city : address);
            if (uri != null) {
                shell.addAction(context.getString(R.string.card_location_open_map), true,
                        () -> openMap(context, uri));
            }
            final String clipboardText = coordsText;
            shell.addAction(context.getString(R.string.card_location_copy), false,
                    () -> copy(context, clipboardText));
        }
        return shell;
    }

    // ---------------------------------------------------------------- 动作

    private static void openMap(Context context, String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "没有可打开地图的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private static void copy(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("坐标", text));
                Toast.makeText(context, R.string.card_location_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 数据（纯函数，可单测）

    /** 取经纬度；缺一个或不是数字返回 null（不要让 0,0 冒充有效坐标） */
    static double[] coords(JSONObject p) {
        if (p == null) return null;
        Double lat = num(p, "latitude", "lat");
        Double lng = num(p, "longitude", "lng", "lon");
        if (lat == null || lng == null) return null;
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
        if (lat == 0d && lng == 0d) return null;   // 常见"没定位到"的占位值
        return new double[]{lat, lng};
    }

    private static Double num(JSONObject p, String... keys) {
        for (String k : keys) {
            double d = p.optDouble(k, Double.NaN);
            if (!Double.isNaN(d)) return d;
            String s = p.optString(k, "").trim();
            if (!s.isEmpty()) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /** 系统地图深链：geo:lat,lng?q=lat,lng(名称)；无有效输入返回 null */
    static String geoUri(double lat, double lng, String label) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
        String pos = String.format(Locale.US, "%.6f,%.6f", lat, lng);
        if (label == null || label.trim().isEmpty()) return "geo:" + pos;
        return "geo:" + pos + "?q=" + pos + "(" + encodeLabel(label.trim()) + ")";
    }

    /**
     * 标签百分号编码（UTF-8）。不用 {@code android.net.Uri.encode} 是为了让 geoUri 保持纯函数、
     * JVM 单测能直接验；编码集合与 Uri.encode 一致（保留字母数字与 -_.~）。
     */
    static String encodeLabel(String s) {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder(s.length() + 16);
        try {
            for (byte b : s.getBytes("UTF-8")) {
                int c = b & 0xFF;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '~') {
                    sb.append((char) c);
                } else {
                    sb.append('%').append(hex[c >> 4]).append(hex[c & 0xF]);
                }
            }
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
        return sb.toString();
    }
}
