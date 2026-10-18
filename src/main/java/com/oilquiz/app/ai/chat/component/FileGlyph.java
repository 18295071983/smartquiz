package com.oilquiz.app.ai.chat.component;

import android.content.Context;

import com.oilquiz.app.R;

import java.util.Locale;

/**
 * 文件类型图标口径（B1-4）：扩展名/类型 → 矢量图标 + 语义着色。
 *
 * <p>原来 {@code FileCardView} 与 {@code FileListCardView} 各写了一份 emoji 映射
 * （📕📊🗜🎬…），同一文件在两张卡里长得不一样，且 emoji 不受主题与字号控制。
 * 这里收成一处，两张卡共用；颜色只用 {@link ComponentTheme} 语义色。
 */
public final class FileGlyph {

    private FileGlyph() {
    }

    /** 按类型或扩展名取矢量图标资源 */
    public static int iconRes(String type, String name) {
        String lower = (type != null ? type : "").toLowerCase(Locale.US);
        String n = (name != null ? name : "").toLowerCase(Locale.US);
        if (lower.contains("dir") || lower.contains("folder")) return R.drawable.ic_folder;
        if (lower.contains("pdf") || n.endsWith(".pdf")) return R.drawable.ic_file_pdf;
        if (lower.contains("image") || lower.contains("png") || lower.contains("jpg")
                || lower.contains("jpeg") || lower.contains("webp") || lower.contains("gif")
                || lower.contains("bmp")
                || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp")) {
            return R.drawable.ic_ai_image;
        }
        if (lower.contains("excel") || lower.contains("xls") || lower.contains("csv")
                || n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".csv")) {
            return R.drawable.ic_excel;
        }
        if (lower.contains("zip") || lower.contains("rar") || lower.contains("7z")
                || lower.contains("tar") || lower.contains("gz")
                || n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
                || n.endsWith(".tar") || n.endsWith(".gz")) {
            return R.drawable.ic_file_archive;
        }
        if (lower.contains("audio") || lower.contains("video") || lower.contains("mp3")
                || lower.contains("mp4") || n.endsWith(".mp3") || n.endsWith(".mp4")
                || n.endsWith(".wav") || n.endsWith(".m4a") || n.endsWith(".avi")
                || n.endsWith(".mov")) {
            return R.drawable.ic_file_media;
        }
        if (n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".py") || n.endsWith(".js")
                || n.endsWith(".ts") || n.endsWith(".go") || n.endsWith(".rs")
                || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".json")
                || n.endsWith(".html") || n.endsWith(".css") || n.endsWith(".sh")) {
            return R.drawable.ic_code;
        }
        if (lower.contains("text") || lower.contains("markdown") || n.endsWith(".txt")
                || n.endsWith(".md") || n.endsWith(".doc") || n.endsWith(".docx")
                || n.endsWith(".ppt") || n.endsWith(".pptx")) {
            return R.drawable.ic_note;
        }
        return R.drawable.ic_file;
    }

    /** 图标着色：只用主题语义色，深浅色自动跟随 */
    public static int tint(Context context, int iconRes) {
        if (iconRes == R.drawable.ic_file_pdf) return ComponentTheme.danger(context);
        if (iconRes == R.drawable.ic_excel) return ComponentTheme.success(context);
        if (iconRes == R.drawable.ic_file_media) return ComponentTheme.warning(context);
        if (iconRes == R.drawable.ic_ai_image) return ComponentTheme.brand(context);
        return ComponentTheme.textSecondary(context);
    }
}
