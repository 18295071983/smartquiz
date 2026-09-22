package com.oilquiz.app.ai.plugin;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件注册器 —— 插件生命周期管理（挂载 / 卸载 / 撤销）。
 *
 * <p>对应 deepseek-harness 的插件树语义：
 * <ul>
 *   <li>mount = 注册 effect（onMount 中 ctx 注册的一切，卸载时随 onUnmount 撤销）；</li>
 *   <li>同一插件 ID 不可重复挂载；</li>
 *   <li>返回的 disposer 幂等（二次 close 无副作用）。</li>
 * </ul>
 * 线程安全。
 */
public class PluginRegistry {

    private static final String TAG = "PluginRegistry";

    private final PluginContext context;
    private final Map<String, Plugin> mounted = new LinkedHashMap<>();

    public PluginRegistry(PluginContext context) {
        this.context = context;
    }

    /**
     * 挂载插件；ID 冲突抛 IllegalStateException；onMount 抛异常则挂载失败且不登记。
     *
     * @return disposer：close() 即卸载该插件（幂等）
     */
    public synchronized AutoCloseable mount(Plugin plugin) {
        if (plugin == null || plugin.id() == null || plugin.id().isEmpty()) {
            throw new IllegalArgumentException("Plugin id must be non-empty");
        }
        if (mounted.containsKey(plugin.id())) {
            throw new IllegalStateException("Plugin already mounted: " + plugin.id());
        }
        try {
            plugin.onMount(context);
        } catch (Exception e) {
            Log.e(TAG, "Plugin onMount failed: " + plugin.id(), e);
            throw new IllegalStateException("Plugin mount failed: " + plugin.id()
                    + " (" + e.getMessage() + ")", e);
        }
        mounted.put(plugin.id(), plugin);
        Log.i(TAG, "Plugin mounted: " + plugin.id() + " (" + plugin.name() + ")");
        return new AutoCloseable() {
            private boolean closed = false;

            @Override
            public synchronized void close() {
                if (closed) {
                    return;
                }
                closed = true;
                unmount(plugin);
            }
        };
    }

    /** 卸载指定插件（幂等；未挂载则忽略）。 */
    public synchronized void unmount(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        Plugin current = mounted.get(plugin.id());
        if (current != plugin) {
            return; // 未挂载，或已被同 ID 新插件替换
        }
        mounted.remove(plugin.id());
        try {
            plugin.onUnmount();
        } catch (Exception e) {
            Log.e(TAG, "Plugin onUnmount failed: " + plugin.id(), e);
        }
        Log.i(TAG, "Plugin unmounted: " + plugin.id());
    }

    /** 卸载全部插件（逆序，先挂载的后卸载）。 */
    public synchronized void unmountAll() {
        List<Plugin> list = new ArrayList<>(mounted.values());
        Collections.reverse(list);
        for (Plugin p : list) {
            unmount(p);
        }
    }

    /** 当前已挂载插件（按挂载顺序）。 */
    public synchronized List<Plugin> mounted() {
        return new ArrayList<>(mounted.values());
    }

    public synchronized boolean isMounted(String pluginId) {
        return pluginId != null && mounted.containsKey(pluginId);
    }

    public synchronized int size() {
        return mounted.size();
    }
}
