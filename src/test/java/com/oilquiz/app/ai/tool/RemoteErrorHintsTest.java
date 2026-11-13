package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 远程连接失败提示（2026-11-14 第三版）：只保留两个主要原因，最多两行。
 */
public class RemoteErrorHintsTest {

    private static String headline(RemoteErrorHints.Hint h) {
        return h.headline;
    }

    @Test
    public void refusedSaysDshNotRunning() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(
                new java.net.ConnectException("failed to connect to /192.168.1.2 (port 8218): Connection refused"));
        assertTrue(headline(h).contains("DSH"));
        assertTrue(h.action.contains("打开 DSH"));
        assertTrue(h.raw.contains("Connection refused"));
    }

    @Test
    public void timeoutBlamesPublicNetwork() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(new java.net.SocketTimeoutException("timeout"));
        assertTrue(headline(h).contains("公用"));
        assertTrue(h.action.contains("fix-remote-lan.cmd"));
        assertTrue(h.action.contains("「是」"));
    }

    @Test
    public void unknownHostBlamesDifferentNetwork() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(new java.net.UnknownHostException("ir158286vw95.vicp.fun"));
        assertTrue(headline(h).contains("网络不通"));
        assertTrue(h.action.contains("同一个 WiFi"));
        assertTrue(h.action.contains("192.168.101.x"));
    }

    @Test
    public void unauthorizedSaysPairingExpired() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(401, "unauthorized");
        assertTrue(headline(h).contains("过期"));
        assertTrue(h.action.contains("扫码"));
    }

    @Test
    public void notFoundSaysAddressWrong() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(404, "not found");
        assertTrue(headline(h).contains("地址"));
        assertTrue(h.action.contains("8218"));
    }

    @Test
    public void serverErrorSuggestsRestart() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(500, "boom");
        assertTrue(h.action.contains("重启"));
    }

    @Test
    public void displayIsAtMostTwoLines() {
        RemoteErrorHints.Hint[] hints = {
                RemoteErrorHints.of(new java.net.SocketTimeoutException("t")),
                RemoteErrorHints.of(new java.net.ConnectException("Connection refused")),
                RemoteErrorHints.of(new java.net.UnknownHostException("x")),
                RemoteErrorHints.ofHttp(401, ""),
                RemoteErrorHints.ofHttp(404, ""),
                RemoteErrorHints.ofHttp(503, ""),
        };
        for (RemoteErrorHints.Hint h : hints) {
            String d = h.display();
            assertEquals("最多两行: " + d, 1, d.split("\n", -1).length - 1);
            assertTrue(d.startsWith(h.headline));
        }
    }

    /** 用户可见文案里不应再出现开发者命令（fix-remote-lan.cmd 是给用户双击的，允许）。 */
    @Test
    public void noDeveloperCommandsInUserCopy() {
        String[] userCopy = {
                RemoteErrorHints.of(new java.net.SocketTimeoutException("t")).display(),
                RemoteErrorHints.of(new java.net.ConnectException("Connection refused")).display(),
                RemoteErrorHints.of(new java.net.UnknownHostException("x")).display(),
                RemoteErrorHints.ofHttp(401, "").display(),
                RemoteErrorHints.ofHttp(404, "").display(),
                RemoteErrorHints.ofHttp(503, "").display(),
        };
        for (String copy : userCopy) {
            String low = copy.toLowerCase(java.util.Locale.US);
            assertTrue("不应出现 PowerShell: " + copy, !low.contains("powershell"));
            assertTrue("不应出现 Set-NetConnectionProfile: " + copy, !low.contains("set-netconnectionprofile"));
            assertTrue("不应出现 check-remote-lan: " + copy, !copy.contains("check-remote-lan.ps1"));
            assertTrue("不应出现 127.0.0.1: " + copy, !copy.contains("127.0.0.1"));
        }
    }
}
