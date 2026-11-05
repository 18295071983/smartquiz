package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 鉴权里可离线判定的两件事：凭据 key 的归一口径、证书事实的文案。
 *
 * <p>弹框本身（输用户名密码、勾"记住"、证书放行）只能在真机上点，这里不假装覆盖。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class BrowserAuthControllerTest {

    @Test
    public void credentialKeyLowercasesHostButKeepsRealm() {
        // realm 是服务器给的不透明字符串、按 RFC 2617 区分大小写；小写化会把两个保护域并成一个，
        // 于是拿 A 域存过的密码去试 B 域
        assertEquals("nas.local|Router Realm",
                BrowserAuthController.credentialKey("NAS.local", "Router Realm"));
        assertEquals("nas.local|router realm",
                BrowserAuthController.credentialKey(" nas.local ", " router realm "));
    }

    @Test
    public void credentialKeyWithoutRealmIsHostOnly() {
        assertEquals("192.168.1.1", BrowserAuthController.credentialKey("192.168.1.1", null));
        assertEquals("192.168.1.1", BrowserAuthController.credentialKey("192.168.1.1", "   "));
        assertEquals("", BrowserAuthController.credentialKey(null, null));
    }

    @Test
    public void realmAndNoRealmAreDifferentKeys() {
        assertTrue(!BrowserAuthController.credentialKey("a.com", "Admin")
                .equals(BrowserAuthController.credentialKey("a.com", "")));
    }

    /**
     * 一天 = 86400 秒，用固定时刻造"过期 30 天"这类可核对的输入，别用 now 让断言随日历漂。
     */
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void describesCertificateFacts() {
        String text = BrowserAuthController.describeCertificate(
                "CN=nas.local", "CN=self-signed", T0 - 30 * DAY, T0 + 335 * DAY, T0);
        assertTrue(text.contains("CN=nas.local"));
        assertTrue(text.contains("CN=self-signed"));
        assertTrue(text.contains("有效期："));
        assertTrue(!text.contains("已过期"));
    }

    @Test
    public void flagsExpiredAndNotYetValid() {
        assertTrue(BrowserAuthController.describeCertificate("CN=a", "CN=b",
                T0 - 400 * DAY, T0 - 30 * DAY, T0).contains("已过期 30 天"));
        assertTrue(BrowserAuthController.describeCertificate("CN=a", "CN=b",
                T0 + 5 * DAY, T0 + 400 * DAY, T0).contains("还没生效"));
    }

    @Test
    public void missingFieldsAreOmittedNotFabricated() {
        // 取不到的项别拿 1970-01-01 当日期显示，也别瞎下"已过期"结论
        String text = BrowserAuthController.describeCertificate("", "", 0L, 0L, T0);
        assertEquals("", text);
        assertTrue(!BrowserAuthController.describeCertificate("CN=a", null, 0L, 0L, T0).contains("1970"));
    }
}
