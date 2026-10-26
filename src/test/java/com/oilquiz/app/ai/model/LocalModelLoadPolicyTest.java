package com.oilquiz.app.ai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 「要不要主动加载本地模型」判定单测（2026-10-25）。
 *
 * <p>背景：预加载与回前台热启动两处各写了一半条件，热启动那条不看在线模式，
 * 结果停在首页没进聊天页就把 Qwen3.5-2B-Q4 的权重捞进内存（native Heap Alloc≈984MB）。
 * 现在两处共用 {@link LocalModelLoadPolicy}，这里把真值表和现场读取都钉住。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LocalModelLoadPolicyTest {

    // ==================== 真值表（纯函数） ====================

    @Test
    public void activeOnlineModelNeverAutoLoadsLocal() {
        assertFalse(LocalModelLoadPolicy.shouldAutoLoadLocalModel(true, true));
        assertFalse(LocalModelLoadPolicy.shouldAutoLoadLocalModel(true, false));
    }

    @Test
    public void localAgentDisabledNeverAutoLoadsLocal() {
        assertFalse(LocalModelLoadPolicy.shouldAutoLoadLocalModel(false, false));
    }

    @Test
    public void offlineAndLocalAgentOnAllowsAutoLoad() {
        assertTrue(LocalModelLoadPolicy.shouldAutoLoadLocalModel(false, true));
    }

    /** 已配置但"未激活"的在线模型不算数——用户随时可能切回本地模型。 */
    @Test
    public void onlyActivatedOnlineModelCounts() {
        assertTrue("配置的在线模型未激活时仍允许主动加载",
                LocalModelLoadPolicy.shouldAutoLoadLocalModel(false, true));
        assertFalse("一旦激活就交给在线模型，不占本地权重",
                LocalModelLoadPolicy.shouldAutoLoadLocalModel(true, true));
    }

    // ==================== 现场读取（Robolectric） ====================

    /**
     * 出厂/真机现场：本地 Agent 关着（AIConfig 默认 false）、没有激活的在线模型
     * → 仍不该主动加载，原因里要点名"本地 Agent"。
     */
    @Test
    public void defaultStateSkipsAutoLoadWithReason() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("ai_config", Context.MODE_PRIVATE).edit().clear().commit();
        String reason = LocalModelLoadPolicy.skipReason(ctx);
        assertNotNull("本地 Agent 默认关闭时不能主动加载本地模型", reason);
        assertTrue("原因要说清是本地 Agent 关闭：" + reason, reason.contains("本地 Agent"));
        assertFalse(LocalModelLoadPolicy.shouldAutoLoadLocalModel(ctx));
    }

    /** 本地 Agent 开、且没有"激活"的在线模型 → 允许主动加载（离线/本地场景不受影响）。 */
    @Test
    public void localAgentOnWithoutActiveOnlineModelAllowsAutoLoad() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("ai_config", Context.MODE_PRIVATE).edit()
                .putBoolean("local_agent_enabled", true).commit();
        try {
            assertNull(LocalModelLoadPolicy.skipReason(ctx));
            assertTrue(LocalModelLoadPolicy.shouldAutoLoadLocalModel(ctx));
        } finally {
            ctx.getSharedPreferences("ai_config", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void nullContextIsTreatedAsNotAllowed() {
        assertEquals("无 Context，不主动加载本地模型", LocalModelLoadPolicy.skipReason(null));
        assertFalse(LocalModelLoadPolicy.shouldAutoLoadLocalModel(null));
    }
}
