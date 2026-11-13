package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 远程连接失败提示的映射（2026-11-14）。
 *
 * <p>只验证"什么错 → 给什么提示"的关键分支，避免以后改文案时把用户又丢回"报一堆码"。</p>
 */
public class RemoteErrorHintsTest {

    private static String headline(RemoteErrorHints.Hint h) {
        return h.headline;
    }

    @Test
    public void refusedMentionsPortAndSelfCheck() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(
                new java.net.ConnectException("failed to connect to /192.168.1.2 (port 8218): Connection refused"));
        assertTrue(headline(h).contains("监听"));
        assertTrue(h.action.contains("check-remote-lan.ps1"));
        assertTrue(h.raw.contains("Connection refused"));
    }

    @Test
    public void timeoutMentionsFirewall() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(new java.net.SocketTimeoutException("timeout"));
        assertTrue(headline(h).contains("挡住"));
        assertTrue(h.action.contains("Set-NetConnectionProfile"));
    }

    @Test
    public void unknownHostMentionsLanIp() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(new java.net.UnknownHostException("ir158286vw95.vicp.fun"));
        assertTrue(headline(h).contains("找不到"));
        assertTrue(h.action.contains("192.168.1.2"));
    }

    @Test
    public void unauthorizedMentionsToken() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(401, "unauthorized");
        assertTrue(headline(h).contains("令牌"));
        assertTrue(h.action.contains(".token"));
    }

    @Test
    public void notFoundMentionsPort8218() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(404, "not found");
        assertTrue(h.action.contains("8218"));
    }

    @Test
    public void serverErrorMentionsRestart() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(500, "boom");
        assertTrue(h.action.contains("DSH"));
    }

    @Test
    public void displayHasTwoParts() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(401, "unauthorized");
        String d = h.display();
        assertTrue(d.contains("\n"));
        assertEquals(h.headline, d.substring(0, d.indexOf('\n')));
        // 不再出现原始 code 堆砌
        assertTrue(!d.contains("HTTP 401"));
    }
}
