package com.oilquiz.app.ai.usage;

import android.content.Context;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.entity.ModelEntity;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注册表 → {@code models} 表的同步桥。
 *
 * <p>{@code models} 表此前是个空壳：Room 里声明了 {@code ModelDao}，全项目却没有任何写入方，
 * 于是 {@code api_usage_log.modelId} 只是一串自由文本，无法与用户注册的模型对上，
 * 用量界面只能显示一串裸模型 id，也无法区分「已删除配置」的历史调用。</p>
 *
 * <p>本桥只做一件派生性的事：把注册表（SharedPreferences 里的 JSON blob）投射成 {@code models} 行，
 * 供用量侧 join 展示。注册表仍是唯一事实来源，本表可随重建，因此不做反向同步、
 * 也不参与推理链路——推理读的还是 {@link OnlineModelManager}。</p>
 *
 * <p>{@code modelId} / {@code providerId} 一律走
 * {@link ModelUsageRecorder#usageModelId} / {@link ModelUsageRecorder#usageProviderId}，
 * 与落日志时同一对函数，杜绝两套命名。</p>
 */
public final class ModelUsageBridge {

    private static final String TAG = "ModelUsageBridge";

    private ModelUsageBridge() {
    }

    /** 单行 upsert（用量落库时顺带调用，保证即便用户从不打开页面也能 join 到名字） */
    public static void upsert(Context context, OnlineModelManager.OnlineModelConfig config) {
        try {
            if (context == null || config == null) return;
            DatabaseManager.getInstance(context).modelDao().insert(toEntity(context, config));
        } catch (Throwable t) {
            AILogger.d(TAG, "模型行同步失败: " + t.getMessage());
        }
    }

    /**
     * 全量重建：注册表现存的模型逐个 upsert，并删掉注册表里已不存在的行。
     * 打开用量页时调用一次，使「已删除配置」的历史调用能被正确标注。
     */
    public static void syncFromRegistry(Context context) {
        try {
            if (context == null) return;
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            List<OnlineModelManager.OnlineModelConfig> configs = manager.getModelList();
            if (configs == null) return;

            com.oilquiz.app.ai.usage.db.dao.ModelDao dao =
                    DatabaseManager.getInstance(context).modelDao();

            Set<String> alive = new HashSet<>();
            for (OnlineModelManager.OnlineModelConfig config : configs) {
                String modelId = ModelUsageRecorder.usageModelId(config);
                alive.add(modelId);
                dao.insert(toEntity(context, config));
            }

            // 清理：注册表里已不存在的模型行。历史用量行不受影响（不删日志，只删名字映射），
            // 用量页据此把 join 不到的行显示成「已移除的模型」。
            List<ModelEntity> existing = dao.getAll();
            if (existing != null) {
                for (ModelEntity entity : existing) {
                    if (entity != null && entity.getModelId() != null && !alive.contains(entity.getModelId())) {
                        dao.delete(entity);
                    }
                }
            }
        } catch (Throwable t) {
            AILogger.d(TAG, "全量模型行同步失败: " + t.getMessage());
        }
    }

    /** 按 modelId 索引现有模型行，供用量页把裸 id 换成用户起的配置名 */
    public static Map<String, ModelEntity> indexByModelId(Context context) {
        Map<String, ModelEntity> index = new HashMap<>();
        try {
            List<ModelEntity> all = DatabaseManager.getInstance(context).modelDao().getAll();
            if (all != null) {
                for (ModelEntity entity : all) {
                    if (entity != null && entity.getModelId() != null) {
                        index.put(entity.getModelId(), entity);
                    }
                }
            }
        } catch (Throwable t) {
            AILogger.d(TAG, "模型行索引失败: " + t.getMessage());
        }
        return index;
    }

    /** 派生一行模型记录；modelId 上有唯一索引，故先查回已有主键再 REPLACE，避免主键漂移 */
    private static ModelEntity toEntity(Context context, OnlineModelManager.OnlineModelConfig config) {
        String modelId = ModelUsageRecorder.usageModelId(config);
        ModelEntity entity = new ModelEntity(
                ModelUsageRecorder.usageProviderId(config),
                modelId,
                config.name != null ? config.name : modelId,
                config.name != null ? config.name : modelId);

        ModelEntity existing = DatabaseManager.getInstance(context).modelDao().getByModelId(modelId);
        if (existing != null) {
            entity.setId(existing.getId());
        }

        entity.setFamily(familyOf(config.apiUrl));
        entity.setCapabilities(capabilitySummary(config));
        entity.setMaxInput(config.contextWindow);
        // 输出上限走既有口径：从 cachedModelsJson 按模型名取服务商声明值，不自造一套
        entity.setMaxOutput(OnlineModelManager.parseMaxOutputTokens(config.cachedModelsJson, modelId));
        return entity;
    }

    /** 能力徽章的紧凑串，仅作展示用；判定仍以注册表扁平布尔为唯一来源 */
    private static String capabilitySummary(OnlineModelManager.OnlineModelConfig config) {
        List<String> caps = new ArrayList<>();
        if (config.hasCapability("vision")) caps.add("vision");
        if (config.hasCapability("audio")) caps.add("audio");
        if (config.hasCapability("function_calling")) caps.add("fc");
        if (config.hasCapability("agent")) caps.add("agent");
        if (config.hasCapability("web_search")) caps.add("web");
        if (config.hasCapability("embedding")) caps.add("embed");
        if (config.hasCapability("rerank")) caps.add("rerank");
        if (config.hasCapability("image_gen")) caps.add("imagegen");
        if (config.hasCapability("tts")) caps.add("tts");
        if (config.hasCapability("asr")) caps.add("asr");
        if (config.hasCapability("long_context")) caps.add("longctx");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < caps.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(caps.get(i));
        }
        return sb.toString();
    }

    /** 从 apiUrl 取 host 首段当家族名（dashscope / deepseek / xfyun …），仅用于分组展示 */
    private static String familyOf(String apiUrl) {
        String host = apiUrl == null ? "" : apiUrl.replaceAll("^https?://", "").split("/")[0];
        if (host.isEmpty()) return "custom";
        String[] parts = host.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : host;
    }
}
