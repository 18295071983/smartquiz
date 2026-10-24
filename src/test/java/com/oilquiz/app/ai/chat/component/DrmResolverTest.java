package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.media3.common.C;

import com.oilquiz.app.ai.chat.media.PlaybackDrm;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.UUID;

/** DRM props（P2）：scheme 映射、license 配置、无配置清空。 */
@RunWith(RobolectricTestRunner.class)
public class DrmResolverTest {

    @Test
    public void mapsSchemes() {
        UUID widevine = DrmResolver.schemeOf("widevine");
        UUID clearkey = DrmResolver.schemeOf("clearkey");
        UUID playready = DrmResolver.schemeOf("playready");
        assertEquals(C.WIDEVINE_UUID, widevine);
        assertEquals(C.CLEARKEY_UUID, clearkey);
        assertEquals(C.PLAYREADY_UUID, playready);
        assertEquals(C.WIDEVINE_UUID, DrmResolver.schemeOf("unknown"));
    }

    @Test
    public void appliesAndClearsDrm() throws Exception {
        JSONObject drm = new JSONObject()
                .put("scheme", "widevine")
                .put("licenseUrl", "https://license.example/wv")
                .put("headers", new JSONObject().put("Authorization", "Bearer x"));
        DrmResolver.apply(new JSONObject().put("drm", drm));
        assertNotNull(PlaybackDrm.get());
        assertEquals(C.WIDEVINE_UUID, PlaybackDrm.get().scheme);
        assertEquals("https://license.example/wv", PlaybackDrm.get().licenseUri.toString());

        DrmResolver.apply(new JSONObject());
        assertNull("普通媒体必须清掉上一首的 DRM", PlaybackDrm.get());
    }
}
