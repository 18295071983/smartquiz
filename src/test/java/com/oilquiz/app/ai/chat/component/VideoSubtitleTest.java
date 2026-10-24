package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/** 视频外挂字幕解析（P1）：地址别名、MIME 兜底、默认轨标记。 */
@RunWith(RobolectricTestRunner.class)
public class VideoSubtitleTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void mimeByExtension() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP,
                VideoCardView.subtitleMimeOf("https://x/a.srt?token=1"));
        assertEquals(MimeTypes.TEXT_VTT,
                VideoCardView.subtitleMimeOf("https://x/a.vtt"));
        assertEquals(MimeTypes.TEXT_SSA,
                VideoCardView.subtitleMimeOf("/tmp/a.ass"));
        assertEquals(MimeTypes.TEXT_SSA,
                VideoCardView.subtitleMimeOf("/tmp/a.ssa"));
        assertEquals(MimeTypes.APPLICATION_TTML,
                VideoCardView.subtitleMimeOf("/tmp/a.ttml"));
        assertEquals(MimeTypes.APPLICATION_SUBRIP,
                VideoCardView.subtitleMimeOf("/tmp/a.unknown"));
    }

    @Test
    public void parsesSingleSubtitleAsDefault() throws Exception {
        JSONObject p = new JSONObject();
        p.put("subtitle", "https://x/a.vtt");
        List<MediaItem.SubtitleConfiguration> subs = VideoCardView.parseSubtitles(ctx(), p);
        assertEquals(1, subs.size());
        MediaItem.SubtitleConfiguration s = subs.get(0);
        assertEquals(MimeTypes.TEXT_VTT, s.mimeType);
        assertEquals("und", s.language);
        assertNotNull(s.uri);
        assertTrue((s.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0);
    }

    @Test
    public void parsesArrayWithLanguageLabelAndDefault() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject()
                .put("url", "https://x/zh.srt")
                .put("lang", "zh")
                .put("label", "中文"));
        arr.put(new JSONObject()
                .put("src", "https://x/en.vtt")
                .put("language", "en")
                .put("name", "English")
                .put("default", true));
        JSONObject p = new JSONObject().put("subtitles", arr);

        List<MediaItem.SubtitleConfiguration> subs = VideoCardView.parseSubtitles(ctx(), p);
        assertEquals(2, subs.size());
        assertEquals("zh", subs.get(0).language);
        assertEquals("中文", subs.get(0).label.toString());
        assertEquals("en", subs.get(1).language);
        assertEquals("English", subs.get(1).label.toString());
        assertTrue((subs.get(1).selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0);
        assertTrue("有显式默认轨时第一条不能被重复标默认",
                (subs.get(0).selectionFlags & C.SELECTION_FLAG_DEFAULT) == 0);
    }
}
