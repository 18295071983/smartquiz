package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 文件卡片组件：图标 + 文件名 + 大小 + 打开按钮。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "name": "报告.pdf",
 *   "size": "2.3 MB",           // 可选
 *   "type": "pdf",              // 可选：用于图标
 *   "uri": "content://...",     // 可选：可打开的 URI
 *   "path": "/storage/..."      // 可选：本地路径
 * }
 * </pre>
 */
public class FileCardView implements ChatComponent {

    @Override
    public String getType() {
        return "file_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("name", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String name = p.optString("name", "");
        String size = p.optString("size", "");
        String type = p.optString("type", "");
        final String uri = p.optString("uri", "");
        final String path = p.optString("path", "");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 8), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 图标（按类型）
        TextView iconTv = new TextView(context);
        iconTv.setTextSize(28);
        iconTv.setText(fileIcon(type, name));
        iconTv.setGravity(Gravity.CENTER);
        card.addView(iconTv, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)));

        // 名称 + 大小
        LinearLayout infoCol = new LinearLayout(context);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(dp(context, 10), 0, 0, 0);

        TextView nameTv = new TextView(context);
        nameTv.setText(name);
        nameTv.setTextSize(14);
        nameTv.setTextColor(ComponentColors.textPrimary(context));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoCol.addView(nameTv);

        if (!TextUtils.isEmpty(size)) {
            TextView sizeTv = new TextView(context);
            sizeTv.setText(size);
            sizeTv.setTextSize(11);
            sizeTv.setTextColor(ComponentColors.textTertiary(context));
            infoCol.addView(sizeTv);
        }
        card.addView(infoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 操作按钮区（打开 / 分享）
        if (!TextUtils.isEmpty(uri) || !TextUtils.isEmpty(path)) {
            LinearLayout actionCol = new LinearLayout(context);
            actionCol.setOrientation(LinearLayout.VERTICAL);

            TextView openBtn = new TextView(context);
            openBtn.setText("打开");
            openBtn.setTextSize(13);
            openBtn.setTextColor(ComponentColors.accent(context));
            openBtn.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
            openBtn.setBackground(buttonBackground(context));
            openBtn.setGravity(Gravity.CENTER);
            openBtn.setOnClickListener(v -> openFile(context, uri, path));
            actionCol.addView(openBtn);

            TextView shareBtn = new TextView(context);
            shareBtn.setText("分享");
            shareBtn.setTextSize(13);
            shareBtn.setTextColor(ComponentColors.textPrimary(context));
            shareBtn.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
            shareBtn.setBackground(buttonBackground(context));
            shareBtn.setGravity(Gravity.CENTER);
            shareBtn.setOnClickListener(v -> shareFile(context, uri, path));
            actionCol.addView(shareBtn);

            card.addView(actionCol);
        }

        return card;
    }

    private static void openFile(Context context, String uri, String path) {
        try {
            // 规范化 path：剥离 file:// 前缀（模型可能传 file:///data/...），解析相对路径
            path = normalizePath(context, path);
            // 无路径/URI：明确提示（模型创建 file_card 时可能只传了 name 没传真实路径）
            if (TextUtils.isEmpty(uri) && TextUtils.isEmpty(path)) {
                Toast.makeText(context, "该文件卡片没有可打开的路径（文件可能未生成或路径缺失）",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // 图片文件 → 应用内预览（不依赖系统图片查看器）
            if (isImageFile(context, uri, path)) {
                showImagePreview(context, !TextUtils.isEmpty(uri) ? uri : path);
                return;
            }
            // 非图片文件 → 应用内预览（FileRenderActivity 按类型渲染 md/文本/表格/pdf 等）。
            // 不能直接 ACTION_VIEW 交给系统：无 App 能渲染时系统转给浏览器，
            // 中文文件名被 punycode 编码成域名 DNS 解析失败（实测 Bug5）
            try {
                Intent preview = new Intent(context,
                        com.oilquiz.app.ui.activity.FileRenderActivity.class);
                if (!TextUtils.isEmpty(uri)) {
                    preview.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_URI,
                            Uri.parse(uri));
                } else if (!TextUtils.isEmpty(path)) {
                    preview.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH,
                            new java.io.File(path).getAbsolutePath());
                } else {
                    return;
                }
                if (!(context instanceof android.app.Activity)) {
                    preview.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(preview);
                return;
            } catch (Exception previewErr) {
                android.util.Log.w("FileCardView", "应用内预览失败，退回系统打开: " + previewErr.getMessage());
            }
            // 兜底：应用内预览失败才交给系统
            Intent intent = null;
            if (!TextUtils.isEmpty(uri)) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            } else if (!TextUtils.isEmpty(path)) {
                // Android 7.0+ 必须用 FileProvider，直接 file:// 会抛 FileUriExposedException
                java.io.File file = new java.io.File(path);
                Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.oilquiz.app.fileprovider", file);
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(fileUri, "*/*");
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(context, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 判断 uri/path 是否为图片（扩展名 + content:// MIME 双重判断） */
    private static boolean isImageFile(Context context, String uri, String path) {
        String target = !TextUtils.isEmpty(uri) ? uri : path;
        if (TextUtils.isEmpty(target)) return false;
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            return true;
        }
        if (target.startsWith("content://")) {
            try {
                String mime = context.getContentResolver().getType(Uri.parse(target));
                return mime != null && mime.startsWith("image/");
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 应用内图片预览（PhotoView 双指缩放）——本地文件优先 BitmapFactory 解码，Glide 兜底 */
    private static void showImagePreview(Context context, String target) {
        try {
            if (!(context instanceof android.app.Activity)) return;
            android.app.Dialog dialog = new android.app.Dialog(context);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            com.github.chrisbanes.photoview.PhotoView photoView = new com.github.chrisbanes.photoview.PhotoView(context);
            photoView.setBackgroundColor(android.graphics.Color.BLACK);

            boolean decoded = false;
            if (target != null && (target.startsWith("file://") || target.startsWith("/"))) {
                try {
                    java.io.File localFile = target.startsWith("file://")
                            ? new java.io.File(android.net.Uri.parse(target).getPath())
                            : new java.io.File(target);
                    if (localFile.exists()) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
                        int sample = 1;
                        while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                            sample *= 2;
                        }
                        opts.inJustDecodeBounds = false;
                        opts.inSampleSize = sample;
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
                        if (bmp != null) {
                            photoView.setImageBitmap(bmp);
                            decoded = true;
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("FileCardView", "bitmap decode failed: " + e.getMessage());
                }
            }
            if (!decoded) {
                com.bumptech.glide.Glide.with(context).load(target)
                        .error(new android.graphics.drawable.ColorDrawable(0xFF1E293B))
                        .into(photoView);
            }

            dialog.setContentView(photoView, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            photoView.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            }
        } catch (Exception e) {
            Toast.makeText(context, "无法预览图片", Toast.LENGTH_SHORT).show();
        }
    }

    /** 分享文件（通过系统分享面板）。
     *  私有目录文件先复制到公共 Download 目录再分享——直接 FileProvider 分享私有目录文件时，
     *  部分目标 App 无法读取（表现为"需要 root"/"文件不存在"）。 */
    private static void shareFile(Context context, String uri, String path) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("*/*");
            if (!TextUtils.isEmpty(uri)) {
                share.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (!TextUtils.isEmpty(path)) {
                // 规范化 path（剥离 file:// 前缀/解析相对路径）
                path = normalizePath(context, path);
                java.io.File file = new java.io.File(path);
                if (!file.exists() || !file.isFile()) {
                    Toast.makeText(context, "文件不存在: " + file.getName(), Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri shareUri = null;
                try {
                    // 工作区在公共目录时文件可直接被目标App读取（FileProvider 授权即可）；
                    // 私有目录文件需复制到公共 Download（否则"需要root"）
                    com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                            com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
                    boolean inWorkspace = file.getCanonicalPath()
                            .startsWith(ws.getWorkspaceDir().getCanonicalPath());
                    if (inWorkspace && ws.isPublicWorkspace()) {
                        shareUri = androidx.core.content.FileProvider.getUriForFile(
                                context, "com.oilquiz.app.fileprovider", file);
                    } else {
                        shareUri = copyToPublicDownloads(context, file);
                    }
                } catch (Throwable t) {
                    shareUri = copyToPublicDownloads(context, file);
                }
                if (shareUri == null) {
                    Toast.makeText(context, "文件复制失败，无法分享", Toast.LENGTH_SHORT).show();
                    return;
                }
                share.putExtra(Intent.EXTRA_STREAM, shareUri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // 无路径/URI：明确提示（模型创建 file_card 时可能只传了 name 没传真实路径）
                Toast.makeText(context, "该文件卡片没有可分享的路径（文件可能未生成或路径缺失）",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(share, "分享文件"));
        } catch (Exception e) {
            Toast.makeText(context, "无法分享文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 规范化文件路径：剥离 file:// 前缀；相对路径（工作区文件）解析为绝对路径；
     *  路径不存在时按文件名在工作区 files/、tmp/、根目录兜底搜索（模型常传相对名/缩写/幻觉路径）。 */
    private static String normalizePath(Context context, String path) {
        if (TextUtils.isEmpty(path)) return path;
        String p = path.trim();
        if (p.startsWith("file://")) {
            p = android.net.Uri.parse(p).getPath();
        }
        if (p == null || p.isEmpty()) return path;
        try {
            com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
            java.io.File resolved = ws.resolveExistingFile(p);
            if (resolved != null) return resolved.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.w("FileCardView", "workspace resolve failed: " + t.getMessage());
        }
        return p;
    }

    /** 复制文件到公共 Download 目录（MediaStore，Android 10+ 免权限），返回可分享的 URI */
    private static Uri copyToPublicDownloads(Context context, java.io.File source) {
        try {
            String fileName = source.getName();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
            String mime = null;
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase());
            }
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime != null ? mime : "application/octet-stream");
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/OilQuiz");
            android.net.Uri collection;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                collection = android.provider.MediaStore.Downloads
                        .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            }
            android.net.Uri item = context.getContentResolver().insert(collection, values);
            if (item == null) return null;
            try (java.io.OutputStream os = context.getContentResolver().openOutputStream(item)) {
                if (os == null) return null;
                try (java.io.InputStream is = new java.io.FileInputStream(source)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                }
            }
            return item;
        } catch (Exception e) {
            android.util.Log.w("FileCardView", "copy to downloads failed: " + e.getMessage());
            return null;
        }
    }

    private static String fileIcon(String type, String name) {
        String lower = (type != null ? type : "").toLowerCase();
        String n = (name != null ? name : "").toLowerCase();
        if (lower.contains("pdf") || n.endsWith(".pdf")) return "📕";
        if (lower.contains("image") || lower.contains("png") || lower.contains("jpg")
                || lower.contains("jpeg") || lower.contains("webp") || lower.contains("gif")
                || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) return "🖼";
        if (lower.contains("excel") || lower.contains("xls") || n.endsWith(".xlsx") || n.endsWith(".xls")) return "📊";
        if (lower.contains("word") || lower.contains("doc") || n.endsWith(".docx") || n.endsWith(".doc")) return "📄";
        if (lower.contains("ppt") || lower.contains("powerpoint") || n.endsWith(".pptx") || n.endsWith(".ppt")) return "📽";
        if (lower.contains("zip") || lower.contains("rar") || lower.contains("7z") || n.endsWith(".zip")
                || n.endsWith(".rar") || n.endsWith(".7z")) return "🗜";
        if (lower.contains("text") || lower.contains("txt") || lower.contains("md")
                || n.endsWith(".txt") || n.endsWith(".md")) return "📝";
        if (lower.contains("audio") || lower.contains("mp3") || lower.contains("wav") || n.endsWith(".mp3")) return "🎵";
        if (lower.contains("video") || lower.contains("mp4") || n.endsWith(".mp4")) return "🎬";
        return "📁";
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static android.graphics.drawable.Drawable buttonBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.accentOverlay(context));
        gd.setCornerRadius(dp(context, 6));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
