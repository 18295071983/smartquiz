package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * data:image 解码器的边界（纯 JVM：这里只测识别与"解码前就拒绝"的硬限制，
 * 真正 BitmapFactory 解码由真机/其它渲染测试覆盖）。
 */
public class DataImageDecoderTest {

    @Test
    public void recognizesOnlyBase64ImageDataUris() {
        assertTrue(DataImageDecoder.isDataImage("data:image/png;base64,iVBORw0KGgo="));
        assertTrue(DataImageDecoder.isDataImage("  DATA:IMAGE/JPEG;base64,AAAA  "));
        assertFalse(DataImageDecoder.isDataImage("data:image/png,notbase64"));
        assertFalse(DataImageDecoder.isDataImage("data:image/png;base64,"));
        assertFalse(DataImageDecoder.isDataImage("https://example.test/a.png"));
        assertFalse(DataImageDecoder.isDataImage("/tmp/a.png"));
        assertFalse(DataImageDecoder.isDataImage(null));
    }

    @Test
    public void rejectsOversizedPayloadBeforeDecoding() {
        String payload = new String(new char[DataImageDecoder.MAX_BASE64_CHARS + 1]).replace('\0', 'A');
        assertNull(DataImageDecoder.decode("data:image/png;base64," + payload));
        assertEquals(512 * 1024, DataImageDecoder.MAX_BASE64_CHARS);
        assertEquals(4096, DataImageDecoder.MAX_DIMENSION);
    }

    @Test
    public void rejectsMalformedOrNonImageInput() {
        assertNull(DataImageDecoder.decode("https://example.test/a.png"));
        assertNull(DataImageDecoder.decode("data:image/png,notbase64"));
        assertNull(DataImageDecoder.decode(null));
    }
}
