package com.oilquiz.app.ai.chat.component;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.oilquiz.app.ai.chat.media.PlaybackDrm;

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

/**
 * 卡片 props.drm（P2）：Widevine/ClearKey/PlayReady license 配置入口。
 *
 * <pre>
 * "drm": {
 *   "scheme": "widevine",              // widevine / clearkey / playready
 *   "licenseUrl": "https://...",
 *   "headers": {"Authorization": "..."},
 *   "multiSession": false
 * }
 * </pre>
 *
 * <p>没有 licenseUrl 时清空，避免把上一首的 DRM 配置带给普通媒体。</p>
 */
final class DrmResolver {

    private DrmResolver() {
    }

    static void apply(JSONObject p) {
        JSONObject drm = p == null ? null : p.optJSONObject("drm");
        if (drm == null) {
            PlaybackDrm.clear();
            return;
        }
        String license = firstNonEmpty(drm, "licenseUrl", "license_url", "license");
        if (license.isEmpty()) {
            PlaybackDrm.clear();
            return;
        }
        UUID scheme = schemeOf(drm.optString("scheme", "widevine"));
        Map<String, String> headers =
                PlaybackHeaderParser.parseObject(drm.optJSONObject("headers"));
        try {
            MediaItem.DrmConfiguration.Builder b =
                    new MediaItem.DrmConfiguration.Builder(scheme)
                            .setLicenseUri(Uri.parse(license))
                            .setMultiSession(drm.optBoolean("multiSession",
                                    drm.optBoolean("multi_session", false)));
            if (!headers.isEmpty()) b.setLicenseRequestHeaders(headers);
            PlaybackDrm.set(b.build());
        } catch (Throwable ignored) {
            PlaybackDrm.clear();
        }
    }

    static UUID schemeOf(String scheme) {
        String s = scheme == null ? "" : scheme.toLowerCase(java.util.Locale.US);
        if (s.contains("clearkey")) return C.CLEARKEY_UUID;
        if (s.contains("playready")) return C.PLAYREADY_UUID;
        return C.WIDEVINE_UUID;
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String key : keys) {
            String v = o.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
