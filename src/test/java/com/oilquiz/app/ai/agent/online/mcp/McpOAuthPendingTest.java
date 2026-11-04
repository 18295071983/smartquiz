package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;

/**
 * 授权回传的等待与投递（{@link McpOAuthPending}）＋环回地址解析（{@link McpLoopbackReceiver}）。
 *
 * <p>这是 OAuth 里唯一"跨线程递凭证"的一段：state 认错了会把别人的授权码当成自己的，
 * 认不出又会把用户已经点完的授权挂到超时。两种都要测到。</p>
 */
public class McpOAuthPendingTest {

    private static McpOAuthEndpoints.AuthorizationServer as() {
        return new McpOAuthEndpoints.AuthorizationServer("https://as.test",
                "https://as.test/authorize", "https://as.test/token", "", "",
                Arrays.asList("S256"), Arrays.asList("authorization_code"),
                Arrays.asList("code"), Arrays.asList("none"));
    }

    private static McpOAuthPending.Waiter waiter(String state) {
        return new McpOAuthPending.Waiter(state, "srv", "verifier", "cid",
                McpOAuthClient.CUSTOM_SCHEME_REDIRECT, "https://mcp.test/mcp", as());
    }

    @After
    public void tearDown() {
        McpOAuthPending.clearForTest();
    }

    @Test
    public void deliverWakesTheWaitingThread() throws Exception {
        McpOAuthPending.begin(waiter("state-1"));
        final McpOAuthPending.Result[] got = new McpOAuthPending.Result[1];
        Thread waiterThread = new Thread(() -> got[0] = McpOAuthPending.awaitResult("state-1", 3_000));
        waiterThread.start();
        Thread.sleep(60);
        assertTrue(McpOAuthPending.hasPending());
        assertTrue("state 相同应当命中", McpOAuthPending.deliver("state-1",
                new McpOAuthPending.Result("code-abc", "", "")));
        waiterThread.join(3_000);
        assertEquals("code-abc", got[0].code);
        assertTrue(got[0].hasCode());
    }

    @Test
    public void unknownStateIsRefusedRatherThanFedToThePendingRequest() {
        McpOAuthPending.begin(waiter("state-1"));
        assertFalse("别的 App/旧的一次回调递进来的 state 必须丢弃",
                McpOAuthPending.deliver("state-2",
                        new McpOAuthPending.Result("stolen", "", "")));
        assertFalse(McpOAuthPending.deliver(null, new McpOAuthPending.Result("x", "", "")));
        assertFalse(McpOAuthPending.deliver("", new McpOAuthPending.Result("x", "", "")));
    }

    @Test
    public void onlyOneAuthorizationMayWaitAtATime() {
        assertTrue(McpOAuthPending.begin(waiter("state-1")));
        assertFalse("并发发起两条会有一条必然超时，不如当场拒绝",
                McpOAuthPending.begin(waiter("state-2")));
        McpOAuthPending.finish("state-1");
        assertFalse(McpOAuthPending.hasPending());
        assertTrue("收尾后允许下一次授权", McpOAuthPending.begin(waiter("state-3")));
    }

    @Test
    public void awaitReturnsNullOnTimeoutInsteadOfHangingForever() {
        McpOAuthPending.begin(waiter("state-1"));
        assertNull(McpOAuthPending.awaitResult("state-1", 120));
    }

    @Test
    public void errorFromBrowserIsDeliveredLikeACode() {
        McpOAuthPending.begin(waiter("state-1"));
        assertTrue(McpOAuthPending.deliver("state-1", new McpOAuthPending.Result(
                "", "access_denied", "用户取消")));
        McpOAuthPending.Result r = McpOAuthPending.awaitResult("state-1", 500);
        assertTrue(r.hasError());
        assertFalse(r.hasCode());
        assertEquals("用户取消", r.errorDescription);
    }

    @Test
    public void finishMakesLaterCallbacksDead() {
        McpOAuthPending.begin(waiter("state-1"));
        McpOAuthPending.finish("state-1");
        assertFalse(McpOAuthPending.deliver("state-1",
                new McpOAuthPending.Result("late", "", "")));
    }

    @Test
    public void loopbackQueryParsingHandlesRealCallbackShapes() {
        assertEquals("code-1", McpLoopbackReceiver.parseQuery(
                "/callback?code=code-1&state=abc").get("code"));
        assertEquals("a b", McpLoopbackReceiver.parseQuery(
                "/callback?error=access_denied&error_description=a%20b").get("error_description"));
        assertTrue(McpLoopbackReceiver.parseQuery("/favicon.ico").isEmpty());
        assertTrue(McpLoopbackReceiver.parseQuery(null).isEmpty());
    }

    @Test
    public void loopbackReceiverBindsLoopbackOnlyAndReleasesThePort() throws Exception {
        McpLoopbackReceiver r = McpLoopbackReceiver.open();
        assertNotNull("绑不上端口时授权不能继续（不能悄悄退化成自定义 scheme）", r);
        assertTrue(r.redirectUri().startsWith("http://127.0.0.1:"));
        assertTrue(r.port() > 0);
        r.close();
    }
}
