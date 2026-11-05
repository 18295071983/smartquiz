package com.oilquiz.app.browser.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class WebPushMessageParserTest {

    private static WebPushSubscription subscription() {
        WebPushSubscription s = new WebPushSubscription();
        s.id = "sub";
        s.endpoint = "https://example.com/inbox";
        return s;
    }

    @Test
    public void parsesTopLevelArray() {
        List<WebPushMessageParser.Message> messages = WebPushMessageParser.parse(
                "[{\"id\":\"1\",\"title\":\"压力高\",\"body\":\"3号泵 1.2MPa\","
                        + "\"url\":\"https://example.com/a\"}]",
                subscription());
        assertEquals(1, messages.size());
        assertEquals("1", messages.get(0).id);
        assertEquals("压力高", messages.get(0).title);
        assertEquals("3号泵 1.2MPa", messages.get(0).body);
    }

    @Test
    public void parsesMessagesWrapper() {
        List<WebPushMessageParser.Message> messages = WebPushMessageParser.parse(
                "{\"messages\":[{\"id\":\"a\",\"title\":\"A\",\"body\":\"B\"},"
                        + "{\"id\":\"b\",\"title\":\"C\",\"body\":\"D\"}]}",
                subscription());
        assertEquals(2, messages.size());
        assertEquals("b", messages.get(1).id);
    }

    @Test
    public void parsesSingleObjectAndDataAliases() {
        List<WebPushMessageParser.Message> messages = WebPushMessageParser.parse(
                "{\"title\":\"T\",\"message\":\"M\",\"link\":\"https://example.com/x\"}",
                subscription());
        assertEquals(1, messages.size());
        assertEquals("T", messages.get(0).title);
        assertEquals("M", messages.get(0).body);
        assertEquals("https://example.com/x", messages.get(0).url);
    }

    @Test
    public void honorsCustomFieldNames() {
        WebPushSubscription s = subscription();
        s.titleField = "subject";
        s.bodyField = "detail";
        s.idField = "key";
        List<WebPushMessageParser.Message> messages = WebPushMessageParser.parse(
                "{\"subject\":\"标题\",\"detail\":\"正文\",\"key\":\"k1\"}", s);
        assertEquals(1, messages.size());
        assertEquals("标题", messages.get(0).title);
        assertEquals("正文", messages.get(0).body);
        assertEquals("k1", messages.get(0).id);
    }

    @Test
    public void missingIdFallsBackToStableHash() {
        String json = "[{\"title\":\"T\",\"body\":\"B\"}]";
        List<WebPushMessageParser.Message> first = WebPushMessageParser.parse(json, subscription());
        List<WebPushMessageParser.Message> second = WebPushMessageParser.parse(json, subscription());
        assertEquals(first.get(0).id, second.get(0).id);
        assertNotEquals(first.get(0).id,
                WebPushMessageParser.parse("[{\"title\":\"T\",\"body\":\"B2\"}]", subscription())
                        .get(0).id);
    }

    @Test
    public void skipsItemsWithoutContentAndHandlesInvalidJson() {
        assertTrue(WebPushMessageParser.parse(
                "[{\"id\":\"empty\"}]", subscription()).isEmpty());
        assertTrue(WebPushMessageParser.parse("not-json", subscription()).isEmpty());
        assertTrue(WebPushMessageParser.parse("", subscription()).isEmpty());
    }
}
