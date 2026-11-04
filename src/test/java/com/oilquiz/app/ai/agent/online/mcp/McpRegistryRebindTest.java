package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oilquiz.app.ai.agent.online.OnlineToolRegistry;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 2026-11-04 真机事故的回归：在线引擎每新建一次 OnlineToolManager 就换一个
 * OnlineToolRegistry，而 {@code McpManager.registeredTools} 是进程级记账。
 *
 * <p>旧实现只查记账：新 registry 里根本没有这些工具，{@code ensureRegistered} 却提前返回，
 * 于是装配定义时 15 个 {@code mcp__amap-maps__*} 全被判「未注册」（当天 48 次告警），
 * 模型只好退回 {@code mcp_toolbox} 的 get+call 绕路。
 */
public class McpRegistryRebindTest {

    private static final String LOCAL = "mcp__amap-maps__maps_geo";

    @Test
    public void staleBookkeepingMustNotCountAsRegistered() {
        OnlineToolRegistry rebuilt = mock(OnlineToolRegistry.class);
        when(rebuilt.hasTool(LOCAL)).thenReturn(false);
        Set<String> bookkeeping = new HashSet<>(Arrays.asList(LOCAL));

        assertFalse("新 registry 里没有这个工具，就不能算已注册",
                McpManager.stillRegistered(rebuilt, bookkeeping, LOCAL));
        assertFalse("陈旧名字必须清掉，否则下一轮还走同一条错路",
                bookkeeping.contains(LOCAL));
    }

    @Test
    public void liveRegistrationStillShortCircuits() {
        OnlineToolRegistry registry = mock(OnlineToolRegistry.class);
        when(registry.hasTool(LOCAL)).thenReturn(true);
        Set<String> bookkeeping = new HashSet<>(Arrays.asList(LOCAL));

        assertTrue("真的在 registry 里就该直接返回，别重复注册",
                McpManager.stillRegistered(registry, bookkeeping, LOCAL));
        assertTrue(bookkeeping.contains(LOCAL));
    }

    @Test
    public void unknownNameIsNotRegistered() {
        OnlineToolRegistry registry = mock(OnlineToolRegistry.class);
        assertFalse(McpManager.stillRegistered(registry, new HashSet<>(), LOCAL));
    }
}
