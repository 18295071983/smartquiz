package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.util.Log;
import android.view.View;

import com.oilquiz.app.ai.chat.render.MarkdownDocPage;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Markdown 文档卡（2026-10-25）：把一整篇 markdown（文件或正文）渲染成可交互文档。
 *
 * <p>与 {@link MarkdownCardView} 的分工：{@code markdown_card} 是"正文里的一小段富文本"，
 * 走 Markwon → TextView，便宜但只认基础语法；{@code markdown_doc} 是"一篇文档/一个 .md 文件"，
 * 走 {@link MarkdownDocPage}（markdown-it + 脚注/定义列表/任务列表 + KaTeX + highlight.js），
 * 生成单文件 HTML 后落盘，再交给 {@link HtmlCardView} 加载——WebView 的沙箱、桥、量高、
 * 放大浮标全部复用，不另造一套。</p>
 *
 * <p>props：</p>
 * <pre>
 * {
 *   "path": "files/报告.md",     // 工作区路径（可用 url/file 别名），与 content 二选一
 *   "content": "# 标题\n正文",    // 或直接给 markdown 原文
 *   "title": "报告",             // 可选，缺省用文件名
 *   "maxHeight": 560             // 可选，卡片内容区最大高度(dp)
 * }
 * </pre>
 */
public class MarkdownDocCardView implements ChatComponent {

    private static final String TAG = "MarkdownDocCardView";

    /** 读取上限：超过 4MB 的 markdown 不做整篇渲染（避免一次性内联卡死） */
    private static final long MAX_MD_BYTES = 4L * 1024 * 1024;

    @Override
    public String getType() {
        return "markdown_doc";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        JSONObject p = data.props;
        return p.has("path") || p.has("file") || p.has("url")
                || p.has("content") || p.has("text") || p.has("markdown") || p.has("md");
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String title = p.optString("title", "");
        String rawPath = firstNonEmpty(p, "path", "file", "url");
        String md = firstNonEmpty(p, "content", "text", "markdown", "md");

        if (!rawPath.isEmpty()) {
            File f = resolve(context, rawPath);
            if (f == null || !f.isFile()) {
                return errorCard(context, "找不到 markdown 文件", rawPath);
            }
            if (f.length() > MAX_MD_BYTES) {
                return errorCard(context, "文件过大，未按文档渲染", f.getName() + " · " + (f.length() / 1024) + " KB");
            }
            try {
                md = readText(f);
            } catch (Throwable t) {
                Log.w(TAG, "read markdown failed: " + t.getMessage());
                return errorCard(context, "读取失败", f.getName());
            }
            if (title.isEmpty()) title = f.getName();
        }

        if (md == null || md.trim().isEmpty()) {
            return errorCard(context, "没有可渲染的 markdown 内容", title);
        }

        boolean night = isNightMode(context);
        String page;
        try {
            page = MarkdownDocPage.build(context, md, night, title);
        } catch (Throwable t) {
            Log.w(TAG, "build page failed: " + t.getMessage());
            return errorCard(context, "渲染失败", String.valueOf(t.getMessage()));
        }

        File pageFile = writeCache(context, page, md, night);
        if (pageFile == null) {
            return errorCard(context, "写入临时文件失败", title);
        }

        JSONObject props = new JSONObject();
        try {
            props.put("url", "file://" + pageFile.getAbsolutePath());
            if (!title.isEmpty()) props.put("title", title);
            props.put("maxHeight", p.optInt("maxHeight", 560));
            // 文档卡是正文型产物：默认摊开，不跟随 html 卡的"可收起"默认档
            props.put("display", "inline");
            if (!night) props.put("light", true);
        } catch (Throwable ignored) {
        }
        // 交给 html 卡：LOCAL_FILE 档（JS + 桥 + 量高 + 放大浮标全部复用）
        return new HtmlCardView().createView(context, ComponentData.of("html", props));
    }

    private static File resolve(Context context, String raw) {
        try {
            return MediaPathResolver.resolveToFile(context, raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(File f) throws Exception {
        byte[] buf = new byte[(int) Math.min(f.length(), MAX_MD_BYTES)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        }
        String s = new String(buf, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s;
    }

    private static File writeCache(Context context, String page, String md, boolean night) {
        try {
            File dir = new File(context.getCacheDir(), "md_doc");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String key = sha1((night ? "d:" : "l:") + md) + ".html";
            File out = new File(dir, key);
            if (out.isFile() && out.length() > 0) return out;
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(page.getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "write cache failed: " + t.getMessage());
            return null;
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String firstNonEmpty(JSONObject p, String... keys) {
        for (String k : keys) {
            String v = p.optString(k, "");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static boolean isNightMode(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static View errorCard(Context context, String state, String detail) {
        CardShell shell = CardShell.create(context).setTitle("Markdown 文档");
        shell.setState(CardShell.State.ERROR, state + (detail == null || detail.isEmpty() ? "" : "：" + detail));
        return shell;
    }
}
