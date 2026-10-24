package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.media.PlaybackArtwork;

import org.json.JSONObject;

import java.io.File;

/** 卡片 props 里的封面字段（P1）：artwork/cover/image/poster → PlaybackArtwork。 */
final class ArtworkResolver {

    private ArtworkResolver() {
    }

    static void apply(Context context, JSONObject p) {
        String raw = firstNonEmpty(p, "artwork", "cover", "image", "poster");
        Uri uri = resolve(context, raw);
        if (uri == null) {
            PlaybackArtwork.clear();
        } else {
            PlaybackArtwork.set(uri);
        }
    }

    static Uri resolve(Context context, String raw) {
        if (context == null || raw == null || raw.isEmpty()) return null;
        String resolved = MediaPathResolver.resolve(context, raw);
        if (resolved == null || resolved.isEmpty()) return null;
        String lower = resolved.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("content://") || lower.startsWith("file://")) {
            return Uri.parse(resolved);
        }
        return Uri.fromFile(new File(resolved));
    }

    private static String firstNonEmpty(JSONObject p, String... keys) {
        if (p == null) return "";
        for (String key : keys) {
            String v = p.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
