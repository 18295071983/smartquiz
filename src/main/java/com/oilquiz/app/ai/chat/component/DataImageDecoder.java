package com.oilquiz.app.ai.chat.component;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.util.Locale;

/**
 * {@code data:image/...;base64,...} 图片解码（2026-11-15）。
 *
 * <p>为什么需要单独一层：layout 的 image/avatar/avatar_group/carousel 与内置 image_grid
 * 都走 Glide/文件路径加载，而 data URI 既不是网络地址也不是本地文件，Glide 在部分设备上
 * 会长时间不回调。这里统一做三件事：</p>
 * <ol>
 *   <li><b>硬限制</b>：单张 base64 payload ≤ {@value #MAX_BASE64_CHARS} 字符（512 KiB）、
 *       单边 ≤ {@value #MAX_DIMENSION} 像素；超限直接返回 null，由调用方画浅底占位；</li>
 *   <li><b>先探边界再解码</b>：{@code inJustDecodeBounds} 拿宽高，按
 *       {@value #DECODE_MAX_DIMENSION} 采样，避免 4096×4096 全尺寸解码造成 OOM；</li>
 *   <li><b>不抛异常</b>：格式错、Base64 错、OOM 全部吞掉返回 null。</li>
 * </ol>
 *
 * <p>只服务于"卡内展示"；不做文件缓存、不写磁盘、不做网络请求。</p>
 */
public final class DataImageDecoder {

    /** 单张 data URI 的 base64 文本上限：512 KiB。 */
    public static final int MAX_BASE64_CHARS = 512 * 1024;

    /** 原图单边上限：4096 px。 */
    public static final int MAX_DIMENSION = 4096;

    /** 实际解码后的单边上限：2048 px（显示足够，内存安全）。 */
    private static final int DECODE_MAX_DIMENSION = 2048;

    private static final String PREFIX = "data:image/";
    private static final String BASE64_MARKER = ";base64,";

    private DataImageDecoder() {
    }

    /** 是否是 {@code data:image/...;base64,...}（大小写不敏感、允许前导空白）。 */
    public static boolean isDataImage(String src) {
        if (src == null) return false;
        String s = src.trim().toLowerCase(Locale.US);
        if (!s.startsWith(PREFIX)) return false;
        int marker = s.indexOf(BASE64_MARKER);
        return marker > PREFIX.length() && marker + BASE64_MARKER.length() < s.length();
    }

    /**
     * 解码 data URI。
     *
     * @return 解码后的 Bitmap；非 data URI、超限、格式坏、OOM 等情况一律 null
     */
    public static Bitmap decode(String src) {
        if (src == null) return null;
        String raw = src.trim();
        int marker = raw.toLowerCase(Locale.US).indexOf(BASE64_MARKER);
        if (marker < 0) return null;
        int start = marker + BASE64_MARKER.length();
        if (start >= raw.length()) return null;
        String payload = stripWhitespace(raw.substring(start));
        if (payload.isEmpty() || payload.length() > MAX_BASE64_CHARS) return null;
        byte[] bytes;
        try {
            bytes = Base64.decode(payload, Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
        if (bytes == null || bytes.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0) return null; // 不是可解码的图片
            if (width > MAX_DIMENSION || height > MAX_DIMENSION) return null;
            int sample = 1;
            while (width / sample > DECODE_MAX_DIMENSION || height / sample > DECODE_MAX_DIMENSION) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable t) {
            // OOM/解码器异常：降级成占位，不把异常抛给渲染链路
            return null;
        }
    }

    private static String stripWhitespace(String value) {
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                if (sb == null) {
                    sb = new StringBuilder(value.length());
                    sb.append(value, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? value : sb.toString();
    }
}
