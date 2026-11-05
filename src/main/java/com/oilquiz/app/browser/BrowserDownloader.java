package com.oilquiz.app.browser;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import com.oilquiz.app.webview.WebViewDefaults;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置浏览器的下载入口：统一走系统 {@link DownloadManager}。
 *
 * <p>相比旧的 {@code ACTION_VIEW} 跳系统浏览器：下载在应用内排队、有通知栏进度、
 * 带上 WebView 登录态 Cookie 与当前 UA，断网/重试由系统负责。</p>
 */
public final class BrowserDownloader {

    public static final long INVALID_ID = -1L;

    private static final Pattern CD_EXT =
            Pattern.compile("filename\\*\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_PLAIN =
            Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", Pattern.CASE_INSENSITIVE);

    private BrowserDownloader() {
    }

    /** 下载结果：id<0 表示未入队；fileName 供提示使用 */
    public static final class Result {
        public final long id;
        public final String fileName;

        Result(long id, String fileName) {
            this.id = id;
            this.fileName = fileName == null ? "" : fileName;
        }

        public boolean started() {
            return id != INVALID_ID;
        }
    }

    public static Result enqueue(Context context, String url, String userAgent,
                                 String contentDisposition, String mimeType) {
        if (context == null || url == null) return new Result(INVALID_ID, null);
        String lower = url.toLowerCase(Locale.ROOT);
        // blob:/data: 需要页面内 JS 取出字节再回传，当前链路拿不到数据本身
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return new Result(INVALID_ID, null);
        }

        String fileName = uniqueName(guessFileName(url, contentDisposition, mimeType));
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String ua = (userAgent == null || userAgent.isEmpty())
                    ? WebViewDefaults.CHROME_USER_AGENT : userAgent;
            request.addRequestHeader("User-Agent", ua);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (mimeType != null && !mimeType.isEmpty()) {
                String mime = mimeType;
                int semi = mime.indexOf(';');
                if (semi >= 0) mime = mime.substring(0, semi).trim();
                if (!mime.isEmpty()) request.setMimeType(mime);
            }
            request.setTitle(fileName);
            request.setDescription("正在下载");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            boolean placed = false;
            try {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                placed = true;
            } catch (Throwable ignored) {
                // 部分 ROM/权限状态下公共目录不可写，退回应用专属下载目录
            }
            if (!placed) {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return new Result(INVALID_ID, fileName);
            long id = dm.enqueue(request);
            return new Result(id, fileName);
        } catch (Throwable t) {
            com.oilquiz.app.infra.AppLogger.e("BrowserDownloader",
                    "下载入队失败: " + url + " - " + t.getMessage(), t);
            return new Result(INVALID_ID, fileName);
        }
    }

    // ==================== 文件名 ====================

    /**
     * blob:/data: 下载没有可用的 URL 路径，文件名只能来自 Content-Disposition 或 MIME。
     */
    public static String fileNameFromDispositionOrMime(String contentDisposition, String mimeType) {
        String name = fromContentDisposition(contentDisposition);
        if (name == null || name.isEmpty()) name = "download";
        name = sanitize(name);
        if (name.indexOf('.') < 0) {
            String ext = extensionForMime(mimeType == null ? "application/octet-stream" : mimeType);
            if (!ext.isEmpty()) name = name + "." + ext;
        }
        return name;
    }

    /**
     * DownloadManager 遇到同名文件会直接失败；公共 Downloads 可读时优先避让，
     * 读不到（未授予存储权限等）就保持原名，交给系统处理。
     */
    static String uniqueName(String fileName) {
        try {
            java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return fileName;
            java.io.File target = new java.io.File(dir, fileName);
            if (!target.exists()) return fileName;

            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            for (int i = 1; i < 100; i++) {
                String candidate = base + " (" + i + ")" + ext;
                if (!new java.io.File(dir, candidate).exists()) return candidate;
            }
        } catch (Throwable ignored) {
        }
        return fileName;
    }

    static String guessFileName(String url, String contentDisposition, String mimeType) {
        String name = fromContentDisposition(contentDisposition);
        if (name == null || name.isEmpty()) {
            name = fromUrl(url);
        }
        if (name == null || name.isEmpty()) {
            name = "download";
        }
        name = sanitize(name);

        if (name.indexOf('.') < 0) {
            String ext = extensionForMime(mimeType);
            if (!ext.isEmpty()) name = name + "." + ext;
        }
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 10) {
                name = name.substring(0, 120 - (name.length() - dot)) + name.substring(dot);
            } else {
                name = name.substring(0, 120);
            }
        }
        return name;
    }

    private static String fromContentDisposition(String cd) {
        if (cd == null || cd.isEmpty()) return null;
        try {
            Matcher m = CD_EXT.matcher(cd);
            if (m.find()) {
                String value = m.group(1).trim().replace("\"", "");
                int sep = value.indexOf("''");
                if (sep >= 0) value = value.substring(sep + 2);
                String decoded = Uri.decode(value);
                if (decoded != null && !decoded.isEmpty()) return decoded;
            }
            m = CD_PLAIN.matcher(cd);
            if (m.find()) {
                String value = m.group(2) != null ? m.group(2) : m.group(1);
                if (value != null) {
                    value = value.trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                    String decoded = Uri.decode(value);
                    if (decoded != null && !decoded.isEmpty()) return decoded;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String fromUrl(String url) {
        try {
            String last = Uri.parse(url).getLastPathSegment();
            if (last != null && !last.isEmpty()) {
                String decoded = Uri.decode(last);
                if (decoded != null && !decoded.isEmpty()) return decoded;
            }
            return URLUtil.guessFileName(url, null, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extensionForMime(String mimeType) {
        if (mimeType == null) return "";
        String mime = mimeType;
        int semi = mime.indexOf(';');
        if (semi >= 0) mime = mime.substring(0, semi).trim();
        try {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null && !ext.isEmpty()) return ext;
        } catch (Throwable ignored) {
        }
        // 部分 ROM 的 MimeTypeMap 缺项（自动化测试环境也可能为空），常见类型兜底
        switch (mime) {
            case "application/pdf": return "pdf";
            case "text/plain": return "txt";
            case "text/html": return "html";
            case "application/json": return "json";
            case "application/zip": return "zip";
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "video/mp4": return "mp4";
            case "audio/mpeg": return "mp3";
            case "application/octet-stream": return "bin";
            default: return "";
        }
    }

    /** 去掉路径分隔符、控制字符与文件名保留字符；保留中文 */
    static String sanitize(String name) {
        if (name == null) return "download";
        String s = name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        s = s.replaceAll("[\\p{Cntrl}]", "");
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith(" "))) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.equals("..") || s.equals(".")) return "download";
        return s;
    }
}
