package com.oilquiz.app.ai.chat.parts;



/**
 * 工具结果里的文件引用（2026-11-18，文件回传协议第一批）。
 *
 * <p>约定：工具结果不再把 PC 私有绝对路径当可读地址，而是带一个引用：</p>
 * <pre>
 * &lt;ref kind="image" id="f_8f3a" mime="image/webp" w="1220" h="2656" bytes="135132"/&gt;
 * &lt;path&gt;D:\qzq\...\card0.png&lt;/path&gt;   &lt;!-- 仅展示 --&gt;
 * </pre>
 *
 * <p>渲染器只认 {@code id}，由 {@code ImageRefResolver} 通过插件 {@code GET /file/{id}}
 * 取字节并缓存；{@code path} 只用于文字展示。这样换工作区/换机器不会因为私有路径读不到而渲染失败。</p>
 */
final class ToolAssetRef {

    final String kind;
    final String id;
    final String mime;
    final int width;
    final int height;
    final long bytes;
    /** PC 侧路径（file_get 用；没有就是空串）。 */
    final String path;

    private ToolAssetRef(String kind, String id, String mime, int width, int height, long bytes,
                         String path) {
        this.kind = kind;
        this.id = id;
        this.mime = mime;
        this.width = width;
        this.height = height;
        this.bytes = bytes;
        this.path = path == null ? "" : path;
    }

    boolean isImage() {
        return "image".equals(kind) || (mime != null && mime.startsWith("image/"));
    }

    /** 渲染器分发目标：同一条 ref 按 mime 决定用哪种视图，不为每种文件单写一条链路。 */
    enum RenderKind { IMAGE, CODE, PDF, AUDIO, VIDEO, DIFF, FILE }

    RenderKind renderKind() {
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.US);
        String k = kind == null ? "" : kind.toLowerCase(java.util.Locale.US);
        if (m.startsWith("image/") || "image".equals(k)) return RenderKind.IMAGE;
        if (m.startsWith("audio/")) return RenderKind.AUDIO;
        if (m.startsWith("video/")) return RenderKind.VIDEO;
        if (m.contains("pdf")) return RenderKind.PDF;
        if ("diff".equals(k) || m.contains("diff")) return RenderKind.DIFF;
        if (m.startsWith("text/") || m.contains("json") || m.contains("xml")
                || m.contains("javascript") || m.contains("typescript")) {
            return RenderKind.CODE;
        }
        return RenderKind.FILE;
    }

    /** 从工具结果里解析第一个 ref；没有/缺 id 返回 null（调用方回落到文字卡）。 */
    static ToolAssetRef parse(String result) {
        if (result == null || result.length() == 0) {
            return null;
        }
        int start = result.indexOf("<ref");
        if (start < 0) {
            return null;
        }
        int end = result.indexOf('>', start);
        if (end < 0) {
            return null;
        }
        String tag = result.substring(start + 4, end);
        String id = attr(tag, "id");
        if (id == null || id.length() == 0) {
            return null;
        }
        return new ToolAssetRef(attr(tag, "kind"), id, attr(tag, "mime"),
                intAttr(tag, "w"), intAttr(tag, "h"), longAttr(tag, "bytes"),
                com.oilquiz.app.ai.chat.util.ToolResultText.pathOf(result));
    }


    /**
     * 没有 {@code <ref>} 时，从结果里的 {@code <path>} 合成一个引用（2026-11-18）。
     *
     * <p>这是过渡口径：工具现在只回路径，但路径本身足够发 {@code file_get}。id 用路径哈希，
     * 同一路径多次打开命中同一份缓存；mime 按扩展名猜。路径不存在/不允许由插件返回错误，
     * 渲染器出重试卡，不会崩。</p>
     */
    static ToolAssetRef fromPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String p = path.trim();
        String lower = p.toLowerCase(java.util.Locale.US);
        String mime = "application/octet-stream";
        String kind = "file";
        if (lower.endsWith(".png")) { mime = "image/png"; kind = "image"; }
        else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) { mime = "image/jpeg"; kind = "image"; }
        else if (lower.endsWith(".webp")) { mime = "image/webp"; kind = "image"; }
        else if (lower.endsWith(".gif")) { mime = "image/gif"; kind = "image"; }
        else if (lower.endsWith(".pdf")) { mime = "application/pdf"; kind = "file"; }
        else if (lower.endsWith(".html") || lower.endsWith(".htm")) { mime = "text/html"; kind = "file"; }
        else if (lower.endsWith(".json")) { mime = "application/json"; kind = "file"; }
        else if (lower.endsWith(".xml")) { mime = "text/xml"; kind = "file"; }
        else if (lower.endsWith(".js")) { mime = "text/javascript"; kind = "file"; }
        else if (lower.endsWith(".ts")) { mime = "text/typescript"; kind = "file"; }
        else if (lower.endsWith(".md")) { mime = "text/markdown"; kind = "file"; }
        else if (lower.endsWith(".txt") || lower.endsWith(".log")) { mime = "text/plain"; kind = "file"; }
        return new ToolAssetRef(kind, "p_" + hash(p), mime, 0, 0, 0L, p);
    }

    private static String hash(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(java.util.Locale.US, "%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
    private static String attr(String tag, String name) {
        String needle = name + "=\"";
        int from = tag.indexOf(needle);
        if (from < 0) {
            return "";
        }
        from += needle.length();
        int to = tag.indexOf('"', from);
        return to < 0 ? "" : tag.substring(from, to).trim();
    }

    private static int intAttr(String tag, String name) {
        try {
            return Integer.parseInt(attr(tag, name));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long longAttr(String tag, String name) {
        try {
            return Long.parseLong(attr(tag, name));
        } catch (Throwable t) {
            return 0L;
        }
    }
}
