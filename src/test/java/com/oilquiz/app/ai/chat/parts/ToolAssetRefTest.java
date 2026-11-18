package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 文件引用解析（2026-11-18，文件回传协议第一批）。 */
public class ToolAssetRefTest {

    @Test
    public void parsesRefAttributes() {
        ToolAssetRef ref = ToolAssetRef.parse(
                "<ref kind=\"image\" id=\"f_8f3a\" mime=\"image/webp\" w=\"1220\" h=\"2656\""
                        + " bytes=\"135132\"/>\n<path>D:\\x\\a.png</path>");
        assertTrue(ref != null);
        assertEquals("f_8f3a", ref.id);
        assertEquals("image/webp", ref.mime);
        assertEquals(1220, ref.width);
        assertEquals(2656, ref.height);
        assertEquals(135132L, ref.bytes);
        assertTrue(ref.isImage());
    }

    @Test
    public void pathOnlyResultSynthesizesStableRef() {
        ToolAssetRef ref = ToolAssetRef.fromPath("D:\\\\x\\\\A.PNG");
        assertNotNull(ref);
        assertTrue(ref.isImage());
        assertEquals("image/png", ref.mime);
        assertEquals("同一路径 id 稳定", ref.id, ToolAssetRef.fromPath("D:\\\\x\\\\A.PNG").id);
        assertNull(ToolAssetRef.fromPath(""));
    }

    @Test
    public void mimeDrivesRendererKind() {
        assertEquals(ToolAssetRef.RenderKind.IMAGE, ref("image/webp").renderKind());
        assertEquals(ToolAssetRef.RenderKind.CODE, ref("text/html").renderKind());
        assertEquals(ToolAssetRef.RenderKind.CODE, ref("application/json").renderKind());
        assertEquals(ToolAssetRef.RenderKind.PDF, ref("application/pdf").renderKind());
        assertEquals(ToolAssetRef.RenderKind.AUDIO, ref("audio/mpeg").renderKind());
        assertEquals(ToolAssetRef.RenderKind.DIFF, ref("text/x-diff").renderKind());
        assertEquals(ToolAssetRef.RenderKind.FILE, ref("application/zip").renderKind());
    }

    private static ToolAssetRef ref(String mime) {
        return ToolAssetRef.parse("<ref kind=\"file\" id=\"f1\" mime=\"" + mime + "\"/>");
    }

    @Test
    public void malformedOrMissingIdFallsBackToNull() {
        assertNull("没有 ref → 文字卡兜底", ToolAssetRef.parse("<path>a.png</path>"));
        assertNull("缺 id → 不可加载", ToolAssetRef.parse("<ref kind=\"image\"/>"));
        assertNull(ToolAssetRef.parse(null));
    }
}
