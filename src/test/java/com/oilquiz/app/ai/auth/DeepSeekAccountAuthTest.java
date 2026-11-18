package com.oilquiz.app.ai.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.model.ProviderConfigManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;

/**
 * DeepSeek 账号登录的鉴权映射（2026-11-18，B 方案）。
 *
 * <p>钉住：`dsh_acct:` 前缀的 apiKey → `x-dsh-auth-token` 头（不是 Bearer）；
 * 普通 API Key 仍走原来的 Bearer，不回归。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class DeepSeekAccountAuthTest {

    @Test
    public void accountTokenUsesDshAuthHeader() {
        Map<String, String> h = ProviderConfigManager.get().getAuthHeaders(
                DeepSeekAccountClient.INFERENCE_BASE,
                DeepSeekAccountClient.KEY_PREFIX + "tok_123", null, null);
        assertEquals("tok_123", h.get("x-dsh-auth-token"));
        assertFalse("账号 token 不能再发 Bearer", h.containsKey("Authorization"));
        assertTrue(h.size() >= 1);
    }

    @Test
    public void apiKeyStillUsesBearer() {
        Map<String, String> h = ProviderConfigManager.get().getAuthHeaders(
                DeepSeekAccountClient.INFERENCE_BASE, "sk-test-key", null, null);
        assertEquals("Bearer sk-test-key", h.get("Authorization"));
        assertFalse(h.containsKey("x-dsh-auth-token"));
    }

    @Test
    public void emptyAccountTokenEmitsNoHeader() {
        Map<String, String> h = ProviderConfigManager.get().getAuthHeaders(
                DeepSeekAccountClient.INFERENCE_BASE, DeepSeekAccountClient.KEY_PREFIX, null, null);
        assertFalse(h.containsKey("x-dsh-auth-token"));
        assertFalse(h.containsKey("Authorization"));
    }
}
