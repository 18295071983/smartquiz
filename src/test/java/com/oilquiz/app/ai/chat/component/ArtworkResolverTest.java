package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.media.PlaybackArtwork;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** 封面解析（P1）：网络/本地路径与"新曲目无封面要清掉上一首"。 */
@RunWith(RobolectricTestRunner.class)
public class ArtworkResolverTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void resolvesNetworkArtwork() {
        Uri uri = ArtworkResolver.resolve(ctx(), "https://x/cover.jpg");
        assertNotNull(uri);
        assertEquals("https", uri.getScheme());
        assertEquals("x", uri.getHost());
    }

    @Test
    public void applySetsAndClearsPlaybackArtwork() throws Exception {
        ArtworkResolver.apply(ctx(), new JSONObject().put("artwork", "https://x/cover.jpg"));
        assertNotNull(PlaybackArtwork.get());

        ArtworkResolver.apply(ctx(), new JSONObject());
        assertNull("新曲目没有封面时必须清掉上一首", PlaybackArtwork.get());
    }
}
