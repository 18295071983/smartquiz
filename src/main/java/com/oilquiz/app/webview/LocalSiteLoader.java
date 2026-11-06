package com.oilquiz.app.webview;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 HTML / 多文件站点加载器（WebViewAssetLoader，2026-11-06）。
 *
 * <p>背景：关闭 {@code setAllowFileAccessFromFileURLs} 后，file:// 页面里的
 * {@code fetch('data.json')}/XHR 会被内核拦掉。这里把"本地 HTML 所在目录"注册成一个
 * 虚拟站点，用 {@code https://appassets.androidplatform.net/local/<token>/...} 承载：
 * 页面与子资源同源，相对 CSS/JS/图片和 fetch 都正常，同时不需要放开 file-URL 任意读。</p>
 *
 * <p>安全边界：只服务显式注册过的目录；解析后 canonical path 必须仍在目录内（防 ../）；
 * token 进程内随机生成，不暴露真实绝对路径。</p>
 */
public final class LocalSiteLoader {

    public static final String DOMAIN = "appassets.androidplatform.net";
    private static final String LOCAL_PREFIX = "/local/";

    private static volatile LocalSiteLoader instance;

    private final Context context;
    private final Map<String, File> dirs = new ConcurrentHashMap<>();
    private volatile WebViewAssetLoader loader;

    private LocalSiteLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    public static LocalSiteLoader getInstance(Context context) {
        if (instance == null) {
            synchronized (LocalSiteLoader.class) {
                if (instance == null) instance = new LocalSiteLoader(context);
            }
        }
        return instance;
    }

    /** 把目录注册为虚拟站点，返回以 '/' 结尾的 base URL（供 loadDataWithBaseURL 解析相对路径）。 */
    public String baseUrlForDir(File dir) {
        if (dir == null) return null;
        try {
            File canonical = dir.getCanonicalFile();
            if (!canonical.isDirectory()) return null;
            String token = null;
            for (Map.Entry<String, File> e : dirs.entrySet()) {
                if (canonical.equals(e.getValue())) {
                    token = e.getKey();
                    break;
                }
            }
            if (token == null) {
                token = UUID.randomUUID().toString().replace("-", "");
                dirs.put(token, canonical);
            }
            ensureLoader();
            return "https://" + DOMAIN + LOCAL_PREFIX + token + "/";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 文件所在目录的 loader base URL；文件不是本地文件/目录不可用时返回 null。 */
    public String baseUrlForFile(File file) {
        if (file == null) return null;
        return baseUrlForDir(file.getParentFile());
    }

    /** 把请求交给 WebViewAssetLoader；非本域名/未注册路径返回 null，调用方继续原有逻辑。 */
    public WebResourceResponse shouldInterceptRequest(Uri uri) {
        if (uri == null || !DOMAIN.equalsIgnoreCase(uri.getHost())) return null;
        ensureLoader();
        try {
            return loader.shouldInterceptRequest(uri);
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureLoader() {
        if (loader != null) return;
        synchronized (this) {
            if (loader != null) return;
            loader = new WebViewAssetLoader.Builder()
                    .setDomain(DOMAIN)
                    .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context))
                    .addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(context))
                    .addPathHandler(LOCAL_PREFIX, this::handleLocal)
                    .build();
        }
    }

    /** PathHandler：path = "/<token>/<relative>"。 */
    private WebResourceResponse handleLocal(String path) {
        try {
            // WebViewAssetLoader 可能传 "/<token>/..." 或 "<token>/..."（不同版本/前缀裁剪差异）
            String rest = path == null ? "" : path;
            while (rest.startsWith("/")) rest = rest.substring(1);
            if (rest.startsWith("local/")) rest = rest.substring("local/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) return notFound();
            String token = rest.substring(0, slash);
            String relative = Uri.decode(rest.substring(slash + 1));
            File root = dirs.get(token);
            if (root == null) return notFound();
            File target = new File(root, relative).getCanonicalFile();
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                return notFound();
            }
            if (!target.isFile()) return notFound();
            String mime = mimeOf(target);
            String encoding = isTextMime(mime) ? "UTF-8" : null;
            InputStream in = new FileInputStream(target);
            return new WebResourceResponse(mime, encoding, in);
        } catch (Throwable t) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream("Not found".getBytes(StandardCharsets.UTF_8)));
    }

    private static String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            switch (ext) {
                case "html": case "htm": return "text/html";
                case "css": return "text/css";
                case "js": case "mjs": return "application/javascript";
                case "json": return "application/json";
                case "xml": return "application/xml";
                case "svg": return "image/svg+xml";
                case "txt": case "md": return "text/plain";
                case "png": return "image/png";
                case "jpg": case "jpeg": return "image/jpeg";
                case "gif": return "image/gif";
                case "webp": return "image/webp";
                case "pdf": return "application/pdf";
                default: break;
            }
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static boolean isTextMime(String mime) {
        return mime != null && (mime.startsWith("text/")
                || mime.contains("json") || mime.contains("javascript")
                || mime.contains("xml") || mime.contains("svg"));
    }
}
