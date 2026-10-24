package com.html2apk.wrapper.tv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 直播清单的抓取 + 解析 + 磁盘缓存。
 *
 * <p>抓取策略：</p>
 * <ul>
 *   <li>手动跟随 301/302/303/307/308（HttpURLConnection 不保证跨协议跟随）；</li>
 *   <li>8MB 上限、15s/20s 超时、支持 gzip；</li>
 *   <li>成功解析后把<b>原始清单</b>缓存到 filesDir/live_tv/cache/，网络失败时回退缓存，
 *       并在结果里标记 {@code cached=true} + 原始错误，避免"没网就整个列表空白"。</li>
 * </ul>
 */
public final class LivePlaylistRepository {

    /** 部分 IPTV 源会按 UA 拦截；默认用一个通用播放器/浏览器 UA，频道可用 EXTVLCOPT 覆盖 */
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern CHARSET = Pattern.compile(
            "charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE);

    public static final class LoadResult {
        public final List<LiveChannel> channels;
        public final boolean cached;
        public final boolean singleStream;
        /** 失败原因；成功时为 null；回退缓存时为抓取错误（列表仍可用） */
        public final String error;
        public final long cacheAgeMs;

        LoadResult(List<LiveChannel> channels, boolean cached, boolean singleStream,
                   String error, long cacheAgeMs) {
            this.channels = Collections.unmodifiableList(channels);
            this.cached = cached;
            this.singleStream = singleStream;
            this.error = error;
            this.cacheAgeMs = Math.max(0, cacheAgeMs);
        }

        public boolean ok() {
            return !channels.isEmpty();
        }
    }

    private final File cacheDir;

    public LivePlaylistRepository(Context context) {
        this.cacheDir = new File(context.getApplicationContext().getFilesDir(), "live_tv/cache");
    }

    public static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** 网络抓取 + 解析；失败时回退磁盘缓存 */
    public LoadResult load(String url, String fallbackName) {
        String u = url == null ? "" : url.trim();
        if (!isHttpUrl(u)) {
            return new LoadResult(Collections.emptyList(), false, false,
                    "频道源地址必须是 http:// 或 https://", 0);
        }

        String text = null;
        String baseUrl = u;
        String fetchError = null;
        boolean cached = false;
        try {
            Fetched fetched = fetch(u);
            text = fetched.text;
            // 相对流地址要按"跳转后的最终清单地址"解析，而不是最初的 URL
            baseUrl = fetched.finalUrl;
        } catch (Exception e) {
            fetchError = humanError(e);
        }

        if (text == null) {
            text = readCache(u);
            cached = text != null;
        }
        if (text == null) {
            return new LoadResult(Collections.emptyList(), false, false,
                    fetchError == null ? "无法读取频道源" : fetchError, 0);
        }

        M3uParser.Result parsed = M3uParser.parse(text, baseUrl, fallbackName);
        if (parsed.channels.isEmpty()) {
            return new LoadResult(Collections.emptyList(), cached, false,
                    fetchError == null ? "未解析到频道（需要 M3U/M3U8 频道列表）" : fetchError, cacheAge(u));
        }

        if (!cached) saveCache(u, text);
        return new LoadResult(parsed.channels, cached, parsed.singleStream,
                fetchError, cacheAge(u));
    }

    /** 解析页面已经拿到的文本（本地清单、带鉴权的自定义请求等） */
    public LoadResult parse(String text, String baseUrl, String fallbackName) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        M3uParser.Result parsed = M3uParser.parse(text, base, fallbackName);
        if (parsed.channels.isEmpty()) {
            return new LoadResult(Collections.emptyList(), false, false,
                    "未解析到频道（需要 M3U/M3U8 频道列表）", 0);
        }
        return new LoadResult(parsed.channels, false, parsed.singleStream, null, 0);
    }

    // ==================== 网络 ====================

    private static final class Fetched {
        final String text;
        final String finalUrl;

        Fetched(String text, String finalUrl) {
            this.text = text;
            this.finalUrl = finalUrl;
        }
    }

    private Fetched fetch(String rawUrl) throws Exception {
        String current = rawUrl;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", DEFAULT_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("HTTP " + code + "（缺少跳转地址）");
                    }
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }

                String encoding = conn.getContentEncoding();
                InputStream in = conn.getInputStream();
                if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
                    in = new GZIPInputStream(in);
                }
                byte[] data = readLimited(in, MAX_BYTES);
                return new Fetched(decode(data, conn.getContentType()), current);
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("跳转次数过多");
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = input.read(buf)) != -1) {
                total += n;
                if (total > max) throw new IllegalStateException("清单超过 8MB");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String decode(byte[] data, String contentType) {
        if (data == null || data.length == 0) return "";
        int offset = 0;
        Charset cs = StandardCharsets.UTF_8;
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF
                && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF) {
            offset = 3;
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xFE) {
            cs = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xFE && (data[1] & 0xff) == 0xFF) {
            cs = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    cs = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        String text = new String(data, offset, data.length - offset, cs);
        // 国内 IPTV 清单常见 GBK 却没在 Content-Type 里声明 charset：
        // UTF-8 解出替换符时再按 GBK 试一次，避免频道名整片乱码。
        if (text.indexOf('\uFFFD') >= 0 && !"GBK".equalsIgnoreCase(cs.name())) {
            try {
                String gbk = new String(data, offset, data.length - offset, Charset.forName("GBK"));
                if (gbk.indexOf('\uFFFD') < 0) return gbk;
            } catch (Exception ignored) {
            }
        }
        return text;
    }

    private static String humanError(Exception e) {
        String msg = e == null ? "" : e.getMessage();
        if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
        return "频道源加载失败：" + msg;
    }

    // ==================== 缓存 ====================

    private File cacheFile(String url) {
        return new File(cacheDir, LiveChannel.sha1Short(url) + ".m3u");
    }

    private void saveCache(String url, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File target = cacheFile(url);
            File tmp = new File(cacheDir, target.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（个别文件系统）就直接覆盖
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private String readCache(String url) {
        try {
            File f = cacheFile(url);
            if (!f.isFile() || f.length() <= 0 || f.length() > MAX_BYTES) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                return decode(readLimited(in, MAX_BYTES), null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private long cacheAge(String url) {
        try {
            File f = cacheFile(url);
            if (!f.isFile()) return 0;
            return Math.max(0, System.currentTimeMillis() - f.lastModified());
        } catch (Exception e) {
            return 0;
        }
    }
}
