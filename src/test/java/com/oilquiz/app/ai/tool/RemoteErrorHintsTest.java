package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 远程连接失败提示的映射（2026-11-14 第二版：只给用户能做的动作）。
 *
 * <p>只验证关键分支，防止以后改动又把命令行/英文异常甩给用户。</p>
 */
public class RemoteErrorHintsTest {

    private static String headline(RemoteErrorHints.Hint h) {
        return h.headline;
    }

    @Test
    public void refusedPointsToOpeningDsh() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(
                new java.net.ConnectException("failed to connect to /192.168.1.2 (port 8218): Connection refused"));
        assertTrue(headline(h).contains("DSH"));
        assertTrue(h.action.contains("打开 DSH"));
        assertTrue(h.raw.contains("Connection refused"));
    }

    @Test
    public void timeoutPointsToOneClickFix() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(new java.net.SocketTimeoutException("timeout"));
        assertTrue(headline(h).contains("挡住"));
        assertTrue(h.action.contains("fix-remote-lan.cmd"));
        assertTrue(h.action.contains("「是」"));
    }

    @Test
    public void unknownHostAsksForComputerIp() {
        RemoteErrorHints.Hint h = RemoteErrorHints.of(new java.net.UnknownHostException("ir158286vw95.vicp.fun"));
        assertTrue(headline(h).contains("找不到"));
        assertTrue(h.action.contains("电脑IP"));
    }

    @Test
    public void unauthorizedAsksForRepairingPairing() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(401, "unauthorized");
        assertTrue(headline(h).contains("过期"));
        assertTrue(h.action.contains("扫码"));
    }

    @Test
    public void notFoundMentionsPort8218() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(404, "not found");
        assertTrue(h.action.contains("8218"));
    }

    @Test
    public void serverErrorSuggestsRestart() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(500, "boom");
        assertTrue(h.action.contains("重启 DSH"));
    }

    @Test
    public void displayHasTwoParts() {
        RemoteErrorHints.Hint h = RemoteErrorHints.ofHttp(401, "unauthorized");
        String d = h.display();
        assertTrue(d.contains("\n"));
        assertEquals(h.headline, d.substring(0, d.indexOf('\n')));
    }

    /** 界面文案里不应该再出现命令行/脚本调试入口。 */
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
            assertTrue("不应出现 PowerShell: " + copy, !copy.toLowerCase().contains("powershell"));
            assertTrue("不应出现 Set-NetConnectionProfile: " + copy,
                    !copy.toLowerCase().contains("set-netconnectionprofile"));
            assertTrue("不应出现 check-remote-lan: " + copy, !copy.contains("check-remote-lan.ps1"));
            assertTrue("不应出现 127.0.0.1: " + copy, !copy.contains("127.0.0.1"));
        }
    }
}
