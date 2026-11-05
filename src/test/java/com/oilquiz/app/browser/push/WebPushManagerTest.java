package com.oilquiz.app.browser.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WebPushManagerTest {

    @Test
    public void parse_validConfig_clampsAndDefaults() {
        WebPushManager.Parsed parsed = WebPushManager.parseConfig(
                "{\"endpoint\":\"https://example.com/inbox\",\"intervalMinutes\":5,"
                        + "\"headers\":{\"Authorization\":\"Bearer x\"},"
                        + "\"body\":{\"device\":\"d1\"},\"titleField\":\"subject\"}",
                "file:///android_asset/webapp.html");
        assertNull(parsed.error);
        assertNotNull(parsed.subscription);
        WebPushSubscription s = parsed.subscription;
        assertEquals("https://example.com/inbox", s.endpoint);
        assertEquals(WebPushManager.MIN_INTERVAL_MINUTES, s.intervalMinutes);
        assertEquals("GET", s.method);
        assertTrue(s.headersJson.contains("Authorization"));
        assertTrue(s.body.contains("\"device\":\"d1\""));
        assertEquals("subject", s.titleField);
        assertTrue(s.id.startsWith("push_"));
    }

    @Test
    public void parse_intervalIsClampedAtBothEnds() {
        assertEquals(WebPushManager.MIN_INTERVAL_MINUTES,
                WebPushManager.parseConfig("{\"endpoint\":\"https://a.test/i\",\"intervalMinutes\":1}", null)
                        .subscription.intervalMinutes);
        assertEquals(WebPushManager.MAX_INTERVAL_MINUTES,
                WebPushManager.parseConfig("{\"endpoint\":\"https://a.test/i\",\"intervalMinutes\":99999}", null)
                        .subscription.intervalMinutes);
    }

    @Test
    public void parse_explicitIdIsKept() {
        WebPushManager.Parsed parsed = WebPushManager.parseConfig(
                "{\"id\":\"alarm-1\",\"endpoint\":\"https://a.test/i\"}", null);
        assertNull(parsed.error);
        assertEquals("alarm-1", parsed.subscription.id);
    }

    @Test
    public void parse_rejectsBadValues() {
        assertNotNull(WebPushManager.parseConfig(
                "{\"endpoint\":\"javascript:alert(1)\"}", null).error);
        assertNotNull(WebPushManager.parseConfig("{}", null).error);
        assertNotNull(WebPushManager.parseConfig(
                "{\"endpoint\":\"https://a.test/i\",\"method\":\"PUT\"}", null).error);
        assertNotNull(WebPushManager.parseConfig(
                "{\"endpoint\":\"https://a.test/i\",\"headers\":{\"X\":\"a\\nb\"}}", null).error);
        assertNotNull(WebPushManager.parseConfig(
                "{\"endpoint\":\"https://a.test/i\",\"openUrl\":\"javascript:alert(1)\"}", null).error);
        assertNotNull(WebPushManager.parseConfig("not-json", null).error);
    }

    @Test
    public void parse_openUrlTemplateIsAccepted() {
        WebPushManager.Parsed parsed = WebPushManager.parseConfig(
                "{\"endpoint\":\"https://a.test/i\","
                        + "\"openUrl\":\"https://a.test/alarm?id={id}&title={title}\"}", null);
        assertNull(parsed.error);
        assertEquals("https://a.test/alarm?id={id}&title={title}", parsed.subscription.openUrl);
    }

    @Test
    public void parse_derivedIdIsScopedByPageOrigin() {
        String config = "{\"endpoint\":\"https://api.example.com/inbox\"}";
        String a = WebPushManager.parseConfig(config, "https://app.example.com/a").subscription.id;
        String b = WebPushManager.parseConfig(config, "https://other.example.com/b").subscription.id;
        assertNotEquals(a, b);
        assertEquals(a, WebPushManager.parseConfig(config, "https://app.example.com/c").subscription.id);
    }

    @Test
    public void originOf_normalizesSchemeHostPort() {
        assertEquals("https://example.com:8443",
                WebPushManager.originOf("https://Example.com:8443/a/b"));
        assertEquals("https://example.com", WebPushManager.originOf("https://example.com"));
        assertEquals("file://", WebPushManager.originOf("file:///android_asset/x.html"));
        assertEquals("", WebPushManager.originOf(null));
    }

    @Test
    public void resultJson_carriesIdAndError() {
        WebPushSubscription s = new WebPushSubscription();
        s.id = "x";
        s.intervalMinutes = 15;
        String ok = WebPushManager.resultJson(newResult(true, null, s));
        assertTrue(ok.contains("\"ok\":true"));
        assertTrue(ok.contains("\"id\":\"x\""));
        String fail = WebPushManager.resultJson(newResult(false, "坏了", null));
        assertTrue(fail.contains("\"ok\":false"));
        assertTrue(fail.contains("坏了"));
    }

    private static WebPushManager.Result newResult(boolean ok, String error, WebPushSubscription s) {
        try {
            java.lang.reflect.Constructor<WebPushManager.Result> c =
                    WebPushManager.Result.class.getDeclaredConstructor(
                            boolean.class, String.class, WebPushSubscription.class);
            c.setAccessible(true);
            return c.newInstance(ok, error, s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
