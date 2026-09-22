package com.oilquiz.app.ai.capability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/**
 * LLM Provider 注册表测试：注册 / 解析 / 自动选择 / disposer 撤销。
 */
public class LlmRegistryTest {

    private static LlmProvider fake(String id, boolean supports) {
        return new LlmProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return "fake-" + id;
            }

            @Override
            public boolean supports(LlmRequest request) {
                return supports;
            }

            @Override
            public LlmService createService() {
                return new LlmService() {
                    @Override
                    public String providerId() {
                        return id;
                    }

                    @Override
                    public void stream(LlmRequest request, LlmStreamCallback callback) {
                    }

                    @Override
                    public void cancel() {
                    }

                    @Override
                    public boolean isBusy() {
                        return false;
                    }
                };
            }
        };
    }

    private static LlmRequest anyRequest() {
        return LlmRequest.builder().addMessage(LlmMessage.user("hi")).build();
    }

    @Test
    public void register_resolve_unregister() throws Exception {
        LlmRegistry registry = new LlmRegistry();
        AutoCloseable handle = registry.register(fake("a", true));
        assertEquals("a", registry.resolve("a").id());
        assertEquals(1, registry.size());
        handle.close();
        assertNull(registry.resolve("a"));
        assertEquals(0, registry.size());
    }

    @Test
    public void duplicate_id_is_rejected() throws Exception {
        LlmRegistry registry = new LlmRegistry();
        registry.register(fake("a", true));
        try {
            registry.register(fake("a", false));
            fail("duplicate should throw");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertEquals(1, registry.size());
    }

    @Test
    public void resolve_first_supported_skips_unsupported() throws Exception {
        LlmRegistry registry = new LlmRegistry();
        registry.register(fake("no", false));
        registry.register(fake("yes", true));
        LlmProvider p = registry.resolveFirstSupported(anyRequest());
        assertEquals("yes", p.id());
    }

    @Test
    public void resolve_first_supported_returns_null_when_none() throws Exception {
        LlmRegistry registry = new LlmRegistry();
        registry.register(fake("no", false));
        assertNull(registry.resolveFirstSupported(anyRequest()));
    }

    @Test
    public void providers_are_sorted_by_id() throws Exception {
        LlmRegistry registry = new LlmRegistry();
        registry.register(fake("b", true));
        registry.register(fake("a", true));
        List<LlmProvider> providers = registry.providers();
        assertEquals("a", providers.get(0).id());
        assertEquals("b", providers.get(1).id());
    }

    @Test
    public void empty_id_is_rejected() {
        LlmRegistry registry = new LlmRegistry();
        try {
            registry.register(fake("", true));
            fail("empty id should throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
