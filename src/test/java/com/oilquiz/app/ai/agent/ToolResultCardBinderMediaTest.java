package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * 媒体工具产物必须落到原生媒体卡（2026-10-21）：
 * 图片→image_grid、视频→video、音频→audio，而不是所有多文件都塞 image_grid。
 */
public class ToolResultCardBinderMediaTest {

    @Test
    public void videoAndAudioFilesUseNativeMediaCards() throws Exception {
        JSONArray files = new JSONArray();
        files.put(new JSONObject().put("file", "/tmp/a.mp4"));
        files.put(new JSONObject().put("file", "/tmp/b.mp3"));
        JSONObject root = new JSONObject().put("files", files);

        List<ComponentData> cards = ToolResultCardBinder.mediaToolkitCards(
                AIToolResult.success(root.toString()));

        assertEquals(2, cards.size());
        assertEquals("video", cards.get(0).type);
        assertEquals("/tmp/a.mp4", cards.get(0).props.optString("url"));
        assertEquals("audio", cards.get(1).type);
        assertEquals("/tmp/b.mp3", cards.get(1).props.optString("url"));
    }

    @Test
    public void mixedImagesVideosKeepImageGridThenVideo() throws Exception {
        JSONArray files = new JSONArray();
        files.put(new JSONObject().put("file", "/tmp/frame1.jpg"));
        files.put(new JSONObject().put("file", "/tmp/frame2.png"));
        files.put(new JSONObject().put("file", "/tmp/movie.mp4"));
        JSONObject root = new JSONObject().put("files", files);

        List<ComponentData> cards = ToolResultCardBinder.mediaToolkitCards(
                AIToolResult.success(root.toString()));

        assertEquals(2, cards.size());
        assertEquals("image_grid", cards.get(0).type);
        assertEquals(2, cards.get(0).props.optJSONArray("images").length());
        assertEquals("video", cards.get(1).type);
    }

    @Test
    public void singleNonImageStillUsesMediaCard() throws Exception {
        JSONObject root = new JSONObject().put("file", "/tmp/voice.m4a");
        List<ComponentData> cards = ToolResultCardBinder.mediaToolkitCards(
                AIToolResult.success(root.toString()));
        assertEquals(1, cards.size());
        assertEquals("audio", cards.get(0).type);
        assertTrue(cards.get(0).props.optString("url").endsWith("voice.m4a"));
    }
}
