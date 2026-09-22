package com.oilquiz.app.ai.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件注册器生命周期测试：挂载 / 幂等卸载 / 重复 ID 拒绝 / 卸载异常回滚。
 */
public class PluginRegistryTest {

    /** 最小插件上下文（全部服务为 null；测试插件不触碰这些服务） */
    private static PluginContext emptyCtx() {
        return new PluginContext(null, null, null);
    }

    private static Plugin simplePlugin(String id, AtomicBoolean mounted, AtomicBoolean unmounted) {
        return new Plugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return "plugin-" + id;
            }

            @Override
            public void onMount(PluginContext ctx) {
                if (mounted != null) {
                    mounted.set(true);
                }
            }

            @Override
            public void onUnmount() {
                if (unmounted != null) {
                    unmounted.set(true);
                }
            }
        };
    }

    @Test
    public void mount_then_close_unmounts() throws Exception {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        AtomicBoolean mounted = new AtomicBoolean(false);
        AtomicBoolean unmounted = new AtomicBoolean(false);
        AutoCloseable handle = registry.mount(simplePlugin("p1", mounted, unmounted));
        assertTrue(mounted.get());
        assertEquals(1, registry.size());
        handle.close();
        assertTrue(unmounted.get());
        assertEquals(0, registry.size());
        assertFalse(registry.isMounted("p1"));
    }

    @Test
    public void disposer_is_idempotent() throws Exception {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        AtomicBoolean unmounted = new AtomicBoolean(false);
        AutoCloseable handle = registry.mount(simplePlugin("p1", null, unmounted));
        handle.close();
        handle.close(); // 二次 close 无副作用
        assertTrue(unmounted.get());
        assertEquals(0, registry.size());
    }

    @Test
    public void duplicate_id_is_rejected() throws Exception {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        registry.mount(simplePlugin("p1", null, null));
        try {
            registry.mount(simplePlugin("p1", null, null));
            fail("duplicate id should throw");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertEquals(1, registry.size());
    }

    @Test
    public void mount_failure_rolls_back_registration() {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        Plugin bad = new Plugin() {
            @Override
            public String id() {
                return "bad";
            }

            @Override
            public String name() {
                return "bad";
            }

            @Override
            public void onMount(PluginContext ctx) throws Exception {
                throw new IllegalStateException("mount exploded");
            }
        };
        try {
            registry.mount(bad);
            fail("mount failure should throw");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertEquals(0, registry.size());
        assertFalse(registry.isMounted("bad"));
    }

    @Test
    public void unmount_all_reverses_mount_order() throws Exception {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        StringBuilder order = new StringBuilder();
        registry.mount(new Plugin() {
            @Override
            public String id() {
                return "a";
            }

            @Override
            public String name() {
                return "a";
            }

            @Override
            public void onUnmount() {
                order.append('a');
            }
        });
        registry.mount(new Plugin() {
            @Override
            public String id() {
                return "b";
            }

            @Override
            public String name() {
                return "b";
            }

            @Override
            public void onUnmount() {
                order.append('b');
            }
        });
        registry.unmountAll();
        assertEquals("ba", order.toString()); // 后挂载的先卸载
        assertEquals(0, registry.size());
    }

    @Test
    public void empty_id_is_rejected() {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        try {
            registry.mount(simplePlugin("", null, null));
            fail("empty id should throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void mounted_list_reflects_state() throws Exception {
        PluginRegistry registry = new PluginRegistry(emptyCtx());
        registry.mount(simplePlugin("a", null, null));
        registry.mount(simplePlugin("b", null, null));
        assertEquals(2, registry.mounted().size());
        assertEquals("a", registry.mounted().get(0).id());
    }
}
