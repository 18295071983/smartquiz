package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.component.ComponentColors;
import com.oilquiz.app.theme.Tokens;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件引用渲染器（2026-11-18，文件回传第二批）。
 *
 * <p>把 {@link ToolAssetRef} 按 {@link ToolAssetRef.RenderKind} 渲染成对应视图：图片、
 * 代码/文本、diff、普通文件（pdf/音视频现阶段先用文件卡，后续接播放器）。</p>
 *
 * <p>加载走 {@link AssetLoader}（磁盘 + 内存缓存、单飞、可取消）。视图上挂 {@link Handle}，
 * 弹层关闭/行回收时 {@link #cancel(View)} 摘回调，避免回调打到已销毁的视图上。</p>
 */
final class AssetRenderer {

    /** 单实例：缓存目录 cacheDir/tool_refs，跨详情页复用已下载文件。 */
    private static AssetLoader loader;

    /** 视图 ↔ 正在加载的 id（取消用）。 */
    static final class Handle {
        final String id;

        Handle(String id) {
            this.id = id;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_IMAGE_PX = 1080;
    private static final int MAX_TEXT_CHARS = 200_000;

    private AssetRenderer() {
    }

    static synchronized AssetLoader loader(Context ctx) {
        if (loader == null) {
            File dir = new File(ctx.getApplicationContext().getCacheDir(), "tool_refs");
            loader = new AssetLoader(dir, new ReceivedFileFetcher(ctx));
        }
        return loader;
    }

    /** 创建容器并开始加载；返回的 View 可随时 {@link #cancel(View)}。 */
    static View build(Context ctx, ToolAssetRef ref, String fallbackLabel) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        if (ref == null) {
            return box;
        }
        box.setTag(new Handle(ref.id));
        // 没有专用卡的文件（pdf/office/zip/未知二进制）：不预下载、不内联，先出文件卡，
        // 点"打开"再取完整文件并交给内置 FileRenderActivity —— 避免内置渲染器拿到代码串/半截内容。
        if (ref.renderKind() == ToolAssetRef.RenderKind.FILE
                || ref.renderKind() == ToolAssetRef.RenderKind.PDF) {
            return openCard(ctx, ref);
        }
        box.addView(statusText(ctx, "加载中…", ComponentColors.textTertiary(ctx)));

        final Context app = ctx.getApplicationContext();
        loader(app).request(ref.id, ref.path, new AssetLoader.Callback() {
            @Override
            public void onReady(File file) {
                MAIN.post(() -> {
                    if (!(box.getTag() instanceof Handle)) {
                        return; // 已取消/已换内容
                    }
                    box.removeAllViews();
                    View content = renderFile(ctx, ref, file);
                    if (content == null) {
                        box.addView(statusText(ctx, "文件类型暂不支持预览", ComponentColors.textTertiary(ctx)));
                    } else {
                        box.addView(content);
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                MAIN.post(() -> {
                    if (!(box.getTag() instanceof Handle)) {
                        return;
                    }
                    box.removeAllViews();
                    TextView retry = statusText(ctx, "加载失败 · 点重试", ComponentColors.error(ctx));
                    retry.setOnClickListener(v -> {
                        box.removeAllViews();
                        box.addView(statusText(ctx, "加载中…", ComponentColors.textTertiary(ctx)));
                        loader(app).request(ref.id, ref.path, new AssetLoader.Callback() {
                            @Override
                            public void onReady(File file) {
                                MAIN.post(() -> {
                                    box.removeAllViews();
                                    box.addView(renderFile(ctx, ref, file));
                                });
                            }

                            @Override
                            public void onError(Throwable e) {
                                MAIN.post(() -> {
                                    box.removeAllViews();
                                    box.addView(statusText(ctx, "加载失败", ComponentColors.error(ctx)));
                                });
                            }
                        });
                    });
                    box.addView(retry);
                });
            }
        });
        return box;
    }

    /** 无专用卡的文件：只出卡；点击才 `file_get` 取完整文件并交给内置渲染器。 */
    private static View openCard(Context ctx, ToolAssetRef ref) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Tokens.spaceSm(ctx);
        row.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ComponentColors.fieldBg(ctx));
        bg.setCornerRadius(Tokens.spaceSm(ctx));
        row.setBackground(bg);

        TextView glyph = new TextView(ctx);
        glyph.setText("▣");
        glyph.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_SUBTITLE));
        glyph.setTextColor(ComponentColors.accent(ctx));
        row.addView(glyph);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(Tokens.spaceSm(ctx));
        TextView name = new TextView(ctx);
        name.setText(fallbackName(ref));
        name.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        name.setTextColor(ComponentColors.textPrimary(ctx));
        col.addView(name);
        TextView hint = new TextView(ctx);
        hint.setText(ref.mime == null || ref.mime.isEmpty() ? "文件" : ref.mime);
        hint.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        hint.setTextColor(ComponentColors.textTertiary(ctx));
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(hint);
        row.addView(col, colLp);

        TextView open = new TextView(ctx);
        open.setText("打开");
        open.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        open.setTextColor(ComponentColors.accent(ctx));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        openLp.setMarginStart(Tokens.spaceSm(ctx));
        row.addView(open, openLp);

        row.setClickable(true);
        row.setTag(new Handle(ref.id));
        row.setOnClickListener(v -> {
            row.removeAllViews();
            row.addView(statusText(ctx, "正在获取完整文件…", ComponentColors.textTertiary(ctx)));
            final Context app = ctx.getApplicationContext();
            loader(app).request(ref.id, ref.path, new AssetLoader.Callback() {
                @Override
                public void onReady(File file) {
                    MAIN.post(() -> {
                        if (!row.isAttachedToWindow()) {
                            return; // 弹层已关
                        }
                        if (ref.renderKind() == ToolAssetRef.RenderKind.PDF) {
                            // PDF 用专用查看器；起不来再退内置渲染器
                            try {
                                com.oilquiz.app.ui.activity.PdfiumPreviewActivity.start(
                                        ctx, file.getAbsolutePath());
                                return;
                            } catch (Throwable ignored) {
                                // fallthrough
                            }
                        }
                        openInFileRenderer(ctx, file);
                    });
                }

                @Override
                public void onError(Throwable error) {
                    MAIN.post(() -> {
                        if (!row.isAttachedToWindow()) {
                            return;
                        }
                        row.removeAllViews();
                        String msg = error == null || error.getMessage() == null ? "" : error.getMessage();
                        row.addView(statusText(ctx, "获取失败 · 点重试" + (msg.isEmpty() ? "" : "（" + msg + "）"), ComponentColors.error(ctx)));
                    });
                }
            });
        });
        return row;
    }

    private static String fallbackName(ToolAssetRef ref) {
        String p = ref.path == null ? "" : ref.path;
        int cut = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return cut >= 0 && cut + 1 < p.length() ? p.substring(cut + 1) : "文件";
    }

    private static View buildBodyFallback(Context ctx) {
        return statusText(ctx, "文件", ComponentColors.textTertiary(ctx));
    }

    /** 弹层关闭/视图回收：取消回调。 */
    static void cancel(View view) {
        if (view != null && view.getTag() instanceof Handle) {
            loader(view.getContext()).cancel(((Handle) view.getTag()).id);
            view.setTag(null);
        }
    }

    // ---------------- 按类型出视图 ----------------

    private static View renderFile(Context ctx, ToolAssetRef ref, File file) {
        String name = file.getName().toLowerCase(java.util.Locale.US);
        String mime = ref.mime == null ? "" : ref.mime.toLowerCase(java.util.Locale.US);
        if (mime.contains("markdown") || name.endsWith(".md") || name.endsWith(".markdown")) {
            return markdownView(ctx, file);
        }
        switch (ref.renderKind()) {
            case IMAGE:
                return imageView(ctx, file);
            case CODE:
                View code = codeView(ctx, readText(file), langOf(ref));
                code.setOnClickListener(v -> {
                    try {
                        com.oilquiz.app.ui.activity.CodeReaderActivity.start(
                                ctx, file.getAbsolutePath());
                    } catch (Throwable ignored) {
                        // 点击失败不影响内联预览
                    }
                });
                return code;
            case DIFF:
                return codeView(ctx, readText(file), "diff");
            case PDF:
                return fileCard(ctx, ref, file, "PDF");
            case AUDIO:
                return mediaCard(ctx, "audio", file, "音频");
            case VIDEO:
                return mediaCard(ctx, "video", file, "视频");
            default:
                return fileCard(ctx, ref, file, "文件");
        }
    }

    /** Markdown 文件：交给内置 MarkdownDocCardView（整篇渲染，含标题/目录排版）。 */
    private static View markdownView(Context ctx, File file) {
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("path", file.getAbsolutePath());
            props.put("title", file.getName());
            View v = new com.oilquiz.app.ai.chat.component.MarkdownDocCardView()
                    .createView(ctx, com.oilquiz.app.ai.chat.component.ComponentData.of(
                            "markdown_doc", props));
            if (v != null) {
                return v;
            }
        } catch (Throwable ignored) {
            // 落到代码视图
        }
        return codeView(ctx, readText(file), "markdown");
    }

    /** 网络图片：直接交给既有图片卡（ImageGridCardView 内部走 Glide + 点击预览）。 */    static View buildUrl(Context ctx, String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            org.json.JSONArray images = new org.json.JSONArray();
            images.put(url.trim());
            props.put("images", images);
            return new com.oilquiz.app.ai.chat.component.ImageGridCardView()
                    .createView(ctx, com.oilquiz.app.ai.chat.component.ComponentData.of("image_grid", props));
        } catch (Throwable t) {
            return null;
        }
    }

    private static View imageView(Context ctx, File file) {
        // 复用既有图片卡（ImageGridCardView：单图/多图、file:// 支持、点击预览），
        // 不再自己 decode 一个裸 ImageView——项目里这套已经打磨过。
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            org.json.JSONArray images = new org.json.JSONArray();
            images.put("file://" + file.getAbsolutePath());
            props.put("images", images);
            View card = new com.oilquiz.app.ai.chat.component.ImageGridCardView()
                    .createView(ctx, com.oilquiz.app.ai.chat.component.ComponentData.of("image_grid", props));
            if (card != null) {
                return card;
            }
        } catch (Throwable ignored) {
            // 图片卡不可用就退回下面的采样解码
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > MAX_IMAGE_PX) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bmp == null) {
            return statusText(ctx, "图片解码失败", ComponentColors.error(ctx));
        }
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMaxHeight(Tokens.space3xl(ctx) * 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 音视频：复用既有 AudioCardView / VideoCardView（file:// 由它们内部处理）。 */
    private static View mediaCard(Context ctx, String type, File file, String fallback) {
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("url", "file://" + file.getAbsolutePath());
            props.put("title", file.getName());
            com.oilquiz.app.ai.chat.component.ChatComponent card = "audio".equals(type)
                    ? new com.oilquiz.app.ai.chat.component.AudioCardView()
                    : new com.oilquiz.app.ai.chat.component.VideoCardView();
            View v = card.createView(ctx, com.oilquiz.app.ai.chat.component.ComponentData.of(type, props));
            if (v != null) {
                return v;
            }
        } catch (Throwable ignored) {
            // 播放器建不出来就退回文件卡
        }
        return fileCard(ctx, ToolAssetRef.fromPath(file.getAbsolutePath()), file, fallback);
    }

    private static View codeView(Context ctx, String text, String lang) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION));
        tv.setTextColor(ComponentColors.textSecondary(ctx));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setText(CodeHighlighter.highlight(ctx, text, lang));
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0f, 1.35f);
        int pad = Tokens.spaceSm(ctx);
        tv.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ComponentColors.fieldBg(ctx));
        bg.setCornerRadius(Tokens.spaceSm(ctx));
        tv.setBackground(bg);
        return tv;
    }

    private static View fileCard(Context ctx, ToolAssetRef ref, File file, String typeLabel) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Tokens.spaceSm(ctx);
        row.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ComponentColors.fieldBg(ctx));
        bg.setCornerRadius(Tokens.spaceSm(ctx));
        row.setBackground(bg);

        TextView glyph = new TextView(ctx);
        glyph.setText("▣");
        glyph.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_SUBTITLE));
        glyph.setTextColor(ComponentColors.accent(ctx));
        row.addView(glyph);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(Tokens.spaceSm(ctx));
        TextView name = new TextView(ctx);
        name.setText(typeLabel + " · " + formatSize(file.length()));
        name.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        name.setTextColor(ComponentColors.textPrimary(ctx));
        col.addView(name);
        TextView hint = new TextView(ctx);
        hint.setText(ref.mime == null || ref.mime.isEmpty() ? ref.id : ref.mime);
        hint.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        hint.setTextColor(ComponentColors.textTertiary(ctx));
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(hint);
        row.addView(col, colLp);

        // 没有专用卡的文件类型：点一下交给 App 内置文件渲染器（PDF/Office/压缩包/文本…）
        TextView open = new TextView(ctx);
        open.setText("打开");
        open.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        open.setTextColor(ComponentColors.accent(ctx));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        openLp.setMarginStart(Tokens.spaceSm(ctx));
        row.addView(open, openLp);
        row.setClickable(true);
        row.setOnClickListener(v -> openInFileRenderer(ctx, file));
        return row;
    }

    /** 交给内置渲染器；内置引擎不支持时弹系统"选择打开方式"。 */
    private static void openInFileRenderer(Context ctx, File file) {
        // 代码/语言文件优先用代码阅读器（行号 + 着色），不丢给通用文件卡/系统
        if (com.oilquiz.app.ui.activity.CodeReaderActivity.isCodeFile(file.getName())) {
            try {
                com.oilquiz.app.ui.activity.CodeReaderActivity.start(ctx, file.getAbsolutePath());
                return;
            } catch (Throwable ignored) {
                // 退内置渲染器
            }
        }
        if (hasBuiltInRenderer(file)) {
            try {
                android.content.Intent i = new android.content.Intent(ctx,
                        com.oilquiz.app.ui.activity.FileRenderActivity.class);
                i.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH,
                        file.getAbsolutePath());
                if (!(ctx instanceof android.app.Activity)) {
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {
                // 内置渲染器起不来就退系统选择器
            }
        }
        openWithSystem(ctx, file);
    }

    /** 内置引擎能不能渲染：工厂命中，或 FileRenderActivity 的集成查看器支持（Office/ODF 等）。 */
    private static boolean hasBuiltInRenderer(File file) {
        try {
            if (com.oilquiz.app.util.PreviewRenderBridge.RenderEngineFactory.getInstance()
                    .getEngineForFile(file) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // 工厂异常按不支持处理，继续看扩展名
        }
        String n = file.getName().toLowerCase(java.util.Locale.US);
        // 与 FileRenderActivity 的集成查看器对齐（LibreOffice 系）
        String[] office = {".doc", ".docx", ".dot", ".dotx", ".xls", ".xlsx", ".xlsm", ".xlt",
                ".xltx", ".ppt", ".pptx", ".ppsx", ".pot", ".potx", ".rtf", ".odt", ".ods",
                ".odp", ".odg", ".ott", ".ots", ".otp", ".otg", ".fodt", ".fods", ".fodp",
                ".fodg", ".vsd", ".vsdx", ".vdx", ".pub", ".wps", ".key", ".abw", ".pmd",
                ".emf", ".svm", ".wmf", ".svg"};
        for (String ext : office) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 系统"选择打开方式"（FileProvider + ACTION_VIEW）。 */
    private static void openWithSystem(Context ctx, File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "com.oilquiz.app.fileprovider", file);
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            view.setDataAndType(uri, mimeOf(file));
            view.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.Intent chooser = android.content.Intent.createChooser(view, "选择打开方式");
            if (!(ctx instanceof android.app.Activity)) {
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(chooser);
        } catch (Throwable ignored) {
            // 没有能打开的应用：保持文件卡，不抛
        }
    }

    /** 仅用于选择器的 MIME：按扩展名给一个尽量准确的类型。 */
    private static String mimeOf(File file) {
        String n = file.getName().toLowerCase(java.util.Locale.US);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static TextView statusText(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        tv.setTextColor(color);
        tv.setPadding(Tokens.spaceSm(ctx), Tokens.spaceSm(ctx),
                Tokens.spaceSm(ctx), Tokens.spaceSm(ctx));
        return tv;
    }

    private static String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[Math.min(MAX_TEXT_CHARS, (int) Math.max(1, file.length()))];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            String text = new String(buf, 0, off, StandardCharsets.UTF_8);
            return file.length() > off ? text + "\n…（已截断）" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String langOf(ToolAssetRef ref) {
        String m = ref.mime == null ? "" : ref.mime.toLowerCase(java.util.Locale.US);
        if (m.contains("html")) return "html";
        if (m.contains("json")) return "json";
        if (m.contains("xml")) return "xml";
        if (m.contains("javascript") || m.contains("typescript")) return "js";
        return "";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
