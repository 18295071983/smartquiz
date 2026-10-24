package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.media.PlaybackHeaders;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

/** 播放器请求头（P1）：props 解析、防盗链三件套、CRLF 注入拦截。 */
public class PlaybackHeaderParserTest {

    @Test
    public void parsesHeadersObjectAndAliases() throws Exception {
        JSONObject p = new JSONObject();
        JSONObject h = new JSONObject()
                .put("Accept", "*/*")
                .put("X-Token", "abc");
        p.put("headers", h);
        p.put("referer", "https://example.com/");
        p.put("cookie", "sid=1");
        p.put("ua", "MyUA/1.0");

        Map<String, String> out = PlaybackHeaderParser.parse(p);
        assertEquals("*/*", out.get("Accept"));
        assertEquals("abc", out.get("X-Token"));
        assertEquals("https://example.com/", out.get("Referer"));
        assertEquals("sid=1", out.get("Cookie"));
        assertEquals("MyUA/1.0", out.get("User-Agent"));
    }

    @Test
    public void dropsCrlfAndInvalidHeaderNames() throws Exception {
        JSONObject p = new JSONObject();
        JSONObject h = new JSONObject()
                .put("X-Bad", "a\r\nInjected: 1")
                .put("Bad Name", "x")
                .put("Bad:Name", "x")
                .put("X-Good", "ok");
        p.put("headers", h);
        p.put("cookie", "a=b\r\nX: y");

        Map<String, String> out = PlaybackHeaderParser.parse(p);
        assertFalse(out.containsKey("X-Bad"));
        assertFalse(out.containsKey("Bad Name"));
        assertFalse(out.containsKey("Bad:Name"));
        assertFalse("CRLF cookie 必须被拒", out.containsKey("Cookie"));
        assertEquals("ok", out.get("X-Good"));
    }

    @Test
    public void emptyPropsYieldEmptyMap() {
        assertTrue(PlaybackHeaderParser.parse(new JSONObject()).isEmpty());
    }

    @Test
    public void playbackHeadersRoundTripAndClear() {
        PlaybackHeaders.set(java.util.Collections.singletonMap("User-Agent", "UA"));
        assertEquals("UA", PlaybackHeaders.get().get("User-Agent"));
        PlaybackHeaders.clear();
        assertTrue(PlaybackHeaders.get().isEmpty());
    }
}
