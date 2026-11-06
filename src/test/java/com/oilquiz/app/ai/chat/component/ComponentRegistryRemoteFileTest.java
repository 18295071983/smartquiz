package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 历史数据纠偏：file_card 的 path 是网址时按链接卡渲染（2026-11-06 真机"文件不存在"）。
 */
public class ComponentRegistryRemoteFileTest {

    @Test
    public void remoteFileCardBecomesLinkCard() throws Exception {
        ComponentData file = ComponentData.of("file_card", new JSONObject()
                .put("name", "article_1.html")
                .put("path", "https://k.sina.com.cn/article_1.html"));
        ComponentData out = ComponentRegistry.normalizeRemoteFileCard(file);
        assertEquals("link_card", out.type);
        assertEquals("https://k.sina.com.cn/article_1.html", out.props.optString("url"));
        assertEquals("article_1.html", out.props.optString("title"));
    }

    @Test
    public void localFileCardStaysFileCard() throws Exception {
        ComponentData file = ComponentData.of("file_card", new JSONObject()
                .put("name", "2020Chinese.skinz")
                .put("path", "/storage/emulated/0/Download/2020Chinese.skinz"));
        ComponentData out = ComponentRegistry.normalizeRemoteFileCard(file);
        assertEquals("file_card", out.type);
    }

    @Test
    public void windowsDriveLetterIsNotMistakenForScheme() throws Exception {
        ComponentData file = ComponentData.of("file_card", new JSONObject()
                .put("name", "a.md").put("path", "C:/tmp/a.md"));
        ComponentData out = ComponentRegistry.normalizeRemoteFileCard(file);
        assertEquals("file_card", out.type);
    }
}
