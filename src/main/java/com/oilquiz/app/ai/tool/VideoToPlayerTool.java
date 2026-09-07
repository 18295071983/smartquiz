package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频转播放工具（video_to_player）：把视频页面链接/直链解析并下载为本地 mp4，
 * 返回工作区绝对路径供 video_player 插件（file_card）渲染并唤起原生预览页播放。
 *
 * 能力：
 * - url 为直链（.mp4/.webm/.m3u8 等媒体扩展名或 Content-Type 为视频）→ 直接下载
 * - url 为视频页面 → 抓取 HTML 提取 og:video / og:video:url / 页面内 <video src> 直链
 * - 下载到工作区 files/videos/ 目录，返回 {local_path, title, size}
 */
@Tool(
    value = "video_to_player",
    description = "视频下载转播放工具：输入视频页面链接或直链URL，解析视频源并下载到本地工作区，"
            + "返回 local_path（mp4绝对路径）供 video_player 组件渲染原生播放。"
            + "直链(mp4/webm等扩展名或视频Content-Type)直接下载；网页链接抓取HTML提取og:video或<video>标签src后下载。"
            + "下载完成后返回文件卡片可直接点击全屏播放。用户说\"播放视频\"时用本工具下载后创建 video_player 组件。",
    category = "media",
    actions = {
        @Action(name = "video_to_player", description = "下载视频到本地并返回可播放路径",
            params = {
                @Param(name = "url", type = "string", description = "视频页面链接或直链URL（必填）", required = true),
                @Param(name = "title", type = "string", description = "视频标题（可选，默认取文件名）", required = false),
                @Param(name = "timeout", type = "integer", description = "下载超时秒数（可选，默认120）", required = false)
            })
    }
)
public class VideoToPlayerTool implements AITool {

    private static final String TAG = "VideoToPlayerTool";

    private final Context context;

    public VideoToPlayerTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "video_to_player";
    }

    @Override
    public String getDescription() {
        return "视频下载转播放工具：输入视频页面链接或直链URL，解析视频源并下载到本地工作区，"
                + "返回 local_path（mp4绝对路径）供 video_player 组件渲染原生播放。"
                + "url=视频页面链接或直链(必填)；title=可选标题；timeout=下载超时秒数(默认120)。"
                + "直链(mp4/webm等扩展名或视频Content-Type)直接下载；网页链接抓取HTML提取og:video或<video>标签src后下载。"
                + "下载完成后返回文件卡片可直接点击全屏播放。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> m = new HashMap<>();
        m.put("url", "视频页面链接或直链URL（必填）");
        m.put("title", "视频标题（可选，默认取文件名）");
        m.put("timeout", "下载超时秒数（可选，默认120）");
        return m;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object u = parameters.get("url");
            if (u == null || String.valueOf(u).trim().isEmpty()) {
                return new AIToolResult("缺少 url 参数（视频页面链接或直链）", parameters);
            }
            String url = String.valueOf(u).trim();
            String title = parameters.get("title") != null ? String.valueOf(parameters.get("title")) : "";
            int timeoutSec = 120;
            try {
                if (parameters.get("timeout") != null) {
                    timeoutSec = Math.max(10, Integer.parseInt(String.valueOf(parameters.get("timeout"))));
                }
            } catch (NumberFormatException ignored) {
            }

            // 1. 若为网页链接（非媒体直链），抓取页面提取视频直链
            String mediaUrl = url;
            if (!isDirectMediaUrl(url)) {
                mediaUrl = extractVideoUrl(url, timeoutSec);
                if (mediaUrl == null || mediaUrl.isEmpty()) {
                    return new AIToolResult("无法从页面提取视频链接: " + url
                            + "（页面未包含 og:video 或 <video> 标签；可尝试提供直链）", parameters);
                }
            }

            // 2. 下载到工作区 files/videos/
            File videosDir = new File(com.oilquiz.app.ai.agent.online.AgentWorkspace
                    .getInstance(context).getWorkspaceDir(), "files/videos");
            if (!videosDir.exists()) videosDir.mkdirs();
            String fileName = title != null && !title.trim().isEmpty()
                    ? sanitizeFileName(title) : fileNameFromUrl(mediaUrl);
            if (!fileName.toLowerCase().endsWith(".mp4")) fileName += ".mp4";
            File target = new File(videosDir, fileName);
            if (target.exists()) target.delete();

            long size = downloadFile(mediaUrl, target, timeoutSec);
            if (size <= 0) {
                return new AIToolResult("视频下载失败（0 字节）: " + mediaUrl, parameters);
            }

            // 3. 返回结果：路径 + 文件卡片组件（可直接点击播放）
            JSONObject props = new JSONObject();
            props.put("name", title != null && !title.trim().isEmpty() ? title : fileName);
            props.put("path", target.getAbsolutePath());
            props.put("type", "video/mp4");
            props.put("size", formatSize(size));
            props.put("local_path", target.getAbsolutePath());
            props.put("title", title != null && !title.trim().isEmpty() ? title : fileName);

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("local_path", target.getAbsolutePath());
            result.put("title", title);
            result.put("size", size);
            result.put("message", "视频已下载: " + fileName + "（" + formatSize(size) + "），"
                    + "可创建 video_player 组件（local_path=" + target.getAbsolutePath() + "）播放");

            AIToolResult r = new AIToolResult(result.toString(), parameters);
            r.withComponent(com.oilquiz.app.ai.chat.component.ComponentData.of("file_card", props));
            return r;
        } catch (Exception e) {
            android.util.Log.w(TAG, "视频下载失败: " + e.getMessage());
            return new AIToolResult("视频下载失败: " + e.getMessage(), parameters);
        }
    }

    /** 判断是否为媒体直链（扩展名或含 video 关键词的 URL） */
    private boolean isDirectMediaUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.matches(".*\\.(mp4|webm|mkv|mov|avi|m4v|3gp|flv)(\\?.*)?$")) return true;
        return false;
    }

    /** 抓取网页提取视频直链（og:video / og:video:url / <video src> / <source src>） */
    private String extractVideoUrl(String pageUrl, int timeoutSec) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(pageUrl).openConnection();
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.w(TAG, "页面抓取失败 HTTP " + code + ": " + pageUrl);
                return null;
            }
            // 内容类型是视频 → 直接返回该 URL
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("video")) {
                return pageUrl;
            }
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int max = 2 * 1024 * 1024;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < max) {
                baos.write(buf, 0, n);
                total += n;
            }
            in.close();
            String html = new String(baos.toByteArray(), "UTF-8");

            // 优先 og:video / og:video:url / og:video:secure_url
            String[] patterns = {
                    "<meta[^>]+property=[\"']og:video:secure_url[\"'][^>]+content=[\"']([^\"']+)[\"']",
                    "<meta[^>]+property=[\"']og:video:url[\"'][^>]+content=[\"']([^\"']+)[\"']",
                    "<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+)[\"']",
                    "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video[\"']",
                    "<video[^>]+src=[\"']([^\"']+)[\"']",
                    "<source[^>]+src=[\"']([^\"']+)[\"']"
            };
            for (String p : patterns) {
                Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty()) {
                        // 相对路径 → 绝对
                        if (v.startsWith("//")) v = "https:" + v;
                        else if (v.startsWith("/")) {
                            URL base = new URL(pageUrl);
                            v = base.getProtocol() + "://" + base.getHost() + v;
                        }
                        return v;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            android.util.Log.w(TAG, "页面视频提取失败: " + e.getMessage());
            return null;
        }
    }

    /** 下载文件到 target，返回字节数；失败返回 -1 */
    private long downloadFile(String url, File target, int timeoutSec) {
        InputStream in = null;
        OutputStream out = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.w(TAG, "下载失败 HTTP " + code + ": " + url);
                return -1;
            }
            in = conn.getInputStream();
            out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total;
        } catch (Exception e) {
            android.util.Log.w(TAG, "下载异常: " + e.getMessage());
            if (target.exists()) target.delete();
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }

    private String fileNameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "video_" + System.currentTimeMillis();
            return URLDecoder.decode(name, "UTF-8");
        } catch (Exception e) {
            return "video_" + System.currentTimeMillis();
        }
    }

    private String sanitizeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "video_" + System.currentTimeMillis() : s;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
