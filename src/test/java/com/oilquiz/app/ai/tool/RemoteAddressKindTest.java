package com.oilquiz.app.ai.tool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 远程地址分类单测（2026-11-16）。
 *
 * 背景：用户反馈"我用的是局域网，不是公网隧道，显示错乱" ——
 * 手机连接页把 Tailscale 组网地址（100.64/10）显示成「隧道域名…公网二维码」。
 */
public class RemoteAddressKindTest {

    @Test
    public void lanRanges() {
        assertTrue(RemoteAddressKind.isLan("192.168.1.2"));
        assertTrue(RemoteAddressKind.isLan("http://192.168.1.2:8218"));
        assertTrue(RemoteAddressKind.isLan("10.0.0.9"));
        assertTrue(RemoteAddressKind.isLan("172.20.1.1"));
        assertTrue(RemoteAddressKind.isLan("127.0.0.1:8218"));
        assertFalse("172.32 不是内网", RemoteAddressKind.isLan("172.32.1.1"));
    }

    @Test
    public void meshRange() {
        assertTrue(RemoteAddressKind.isMesh("100.113.188.52"));
        assertTrue(RemoteAddressKind.isMesh("http://100.113.188.52:8218"));
        assertTrue(RemoteAddressKind.isMesh("100.64.0.1"));
        assertFalse(RemoteAddressKind.isMesh("100.200.0.1"));
        assertFalse(RemoteAddressKind.isMesh("8.8.8.8"));
    }

    @Test
    public void classifyAndLabel() {
        assertEquals(RemoteAddressKind.Kind.LAN, RemoteAddressKind.classify("192.168.1.2:8218"));
        assertEquals(RemoteAddressKind.Kind.MESH, RemoteAddressKind.classify("http://100.113.188.52:8218"));
        assertEquals(RemoteAddressKind.Kind.PUBLIC, RemoteAddressKind.classify("https://abc.vicp.fun"));

        assertEquals("局域网", RemoteAddressKind.label("192.168.1.2"));
        assertEquals("组网（VPN）", RemoteAddressKind.label("http://100.113.188.52:8218"));
        assertEquals("公网", RemoteAddressKind.label("https://abc.vicp.fun"));
    }

    @Test
    public void usageHintsExplainWhy() {
        assertTrue(RemoteAddressKind.usageHint("192.168.1.2").contains("局域网"));
        assertTrue("组网提示必须点明不需要公网",
                RemoteAddressKind.usageHint("http://100.113.188.52:8218").contains("不需要公网"));
        assertTrue(RemoteAddressKind.usageHint("https://abc.vicp.fun").contains("公网二维码"));
    }

    @Test
    public void hostParsingToleratesForms() {
        assertEquals("192.168.1.2", RemoteAddressKind.hostOf("http://192.168.1.2:8218/foo"));
        assertEquals("abc.vicp.fun", RemoteAddressKind.hostOf("https://abc.vicp.fun"));
        assertEquals("", RemoteAddressKind.hostOf(""));
        assertEquals("", RemoteAddressKind.hostOf(null));
    }
}
