package com.oilquiz.app.ai.capability;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 注册表 —— 能力缝的装配点。
 *
 * <p>注册即 effect：{@link #register(LlmProvider)} 返回 disposer（AutoCloseable），
 * 关闭即撤销注册，符合 deepseek-harness “registrations are effects” 约定。
 * 线程安全。
 */
public class LlmRegistry {

    private static final String TAG = "LlmRegistry";

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();

    /** 注册 Provider；ID 冲突抛 IllegalStateException。返回的 close() 撤销注册。 */
    public AutoCloseable register(LlmProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isEmpty()) {
            throw new IllegalArgumentException("Provider id must be non-empty");
        }
        LlmProvider prev = providers.putIfAbsent(provider.id(), provider);
        if (prev != null) {
            throw new IllegalStateException("Provider already registered: " + provider.id());
        }
        Log.i(TAG, "Registered LLM provider: " + provider.id() + " (" + provider.displayName() + ")");
        return () -> {
            // 只移除自己注册的实例（防止被同 ID 新 Provider 顶掉后误删）
            if (providers.remove(provider.id(), provider)) {
                Log.i(TAG, "Unregistered LLM provider: " + provider.id());
            }
        };
    }

    /** 按 ID 解析；不存在返回 null。 */
    public LlmProvider resolve(String providerId) {
        return providerId == null ? null : providers.get(providerId);
    }

    /** 第一个支持该请求的 Provider；无则 null（Consumer 兜底报错）。 */
    public LlmProvider resolveFirstSupported(LlmRequest request) {
        for (LlmProvider p : providers.values()) {
            try {
                if (p.supports(request)) {
                    return p;
                }
            } catch (Exception e) {
                Log.w(TAG, "supports() failed for " + p.id() + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** 全部 Provider（按 ID 排序，确定性输出）。 */
    public List<LlmProvider> providers() {
        List<LlmProvider> list = new ArrayList<>(providers.values());
        Collections.sort(list, (a, b) -> a.id().compareTo(b.id()));
        return list;
    }

    public int size() {
        return providers.size();
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }
}
