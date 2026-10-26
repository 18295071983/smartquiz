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

    /**
     * 本桥的所有写库动作都在这一条串行线程上跑。
     *
     * <p><b>为什么必须在桥内部解决而不是每个调用方各自套线程</b>：Room 未开
     * {@code allowMainThreadQueries}（本项目刻意没开），而"保存配置后投射"的调用点
     * <b>全部</b>在 UI 线程回调里（对话框的 onConfigSaved、删除确认、Tab 刷新）。
     * 真机 2026-10-27 02:09:57 的 ai_logs 里是这两行：
     * <pre>
     *   [DEBUG] ModelUsageBridge 模型行同步失败: Cannot access database on the main thread…
     *   [DEBUG] ModelUsageBridge 全量模型行同步失败: Cannot access database on the main thread…
     * </pre>
     * 即这条投射**从来没成功过**，却被 {@code catch(Throwable) + AILogger.d} 压成一条 DEBUG，
     * 表现就是"保存明明成功、用量侧却一直是旧值"。改在产出侧投递，调用方不必再关心线程；
     * 失败级别也从 DEBUG 升到 WARN，别再静默。</p>
     */
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "model-usage-bridge"));

    private ModelUsageBridge() {
    }

    /**
     * 复制 {@link #toEntity} 与各口径函数会读到的字段。
     *
     * <p>注册表里那个 {@code OnlineModelConfig} 是<b>共享可变对象</b>，投递到后台线程后再读它，
     * 可能读到写一半的状态（例如名称已改、cachedModelsJson 还没写）→ 投射出一条撕裂的行。
     * 所以在调用线程先取一份快照。<b>新增注册字段时，若它参与投射，要同步加进这里。</b></p>
     */
    private static OnlineModelManager.OnlineModelConfig snapshot(
            OnlineModelManager.OnlineModelConfig c) {
        OnlineModelManager.OnlineModelConfig s = new OnlineModelManager.OnlineModelConfig();
        s.id = c.id;
        s.name = c.name;
        s.apiUrl = c.apiUrl;
        s.modelName = c.modelName;
        s.selectedModel = c.selectedModel;
        s.enabled = c.enabled;
        s.cachedModelsJson = c.cachedModelsJson;
        s.contextWindow = c.contextWindow;
        s.contextWindowFromApi = c.contextWindowFromApi;
        s.costPerMillionTokens = c.costPerMillionTokens;
        s.supportsVision = c.supportsVision;
        s.supportsAudio = c.supportsAudio;
        s.supportsCode = c.supportsCode;
        s.supportsFunctionCalling = c.supportsFunctionCalling;
        s.supportsAgent = c.supportsAgent;
        s.supportsWebSearch = c.supportsWebSearch;
        s.supportsEmbedding = c.supportsEmbedding;
        s.supportsRerank = c.supportsRerank;
        s.supportsImageGen = c.supportsImageGen;
        s.supportsTts = c.supportsTts;
        s.supportsAsr = c.supportsAsr;
        return s;
    }

    /** 单行 upsert（用量落库与配置保存后都会调；线程安全，可在主线程直接调） */
    public static void upsert(Context context, OnlineModelManager.OnlineModelConfig config) {
        if (context == null || config == null) return;
        final Context app = context.getApplicationContext();
        final OnlineModelManager.OnlineModelConfig snap = snapshot(config);
        IO.execute(() -> {
            try {
                DatabaseManager.getInstance(app).modelDao().insert(toEntity(app, snap));
            } catch (Throwable t) {
                AILogger.w(TAG, "模型行同步失败: " + t.getMessage());
            }
        });
    }

    /**
     * 一次「保存/新增配置」之后的统一投射 —— 三个宿主页面（模型中心、模型选择、AI 中心）
     * 都该调这一个方法，而不是各自决定要不要补 upsert。
     *
     * <p><b>为什么不能只 upsert 当前这一条</b>：{@code models.modelId} 取的是**实际模型名**
     * （{@link ModelUsageRecorder#usageModelId}），编辑时把 {@code deepseek-chat} 改成
     * {@code deepseek-reasoner} 等于换了主键 —— 只 upsert 会新建一行、把按旧名建的那行留下，
     * 用量页于是同时显示改前与改后的名字，用户看到的现象就是"改完没生效"。</p>
     *
     * <p>所以在同一条后台任务里：先投射刚保存的那条，再走一次全量对齐（现存配置逐条 upsert、
     * 删掉注册表里已不存在的行）。历史用量行不受影响（只删名字映射，不删日志）；
     * 注册表仍是唯一事实来源。调用方可在 UI 线程直接调。</p>
     */
    public static void afterConfigSaved(Context context, OnlineModelManager.OnlineModelConfig config) {
        if (context == null || config == null) return;
        final Context app = context.getApplicationContext();
        final OnlineModelManager.OnlineModelConfig snap = snapshot(config);
        IO.execute(() -> {
            try {
                DatabaseManager.getInstance(app).modelDao().insert(toEntity(app, snap));
            } catch (Throwable t) {
                AILogger.w(TAG, "模型行同步失败: " + t.getMessage());
            }
            doSyncFromRegistry(app);
        });
    }

    /**
     * 全量对齐的**异步**入口：注册表现存模型逐条投射，并删掉注册表里已不存在的行。
     * 供 UI 线程调用（如删除配置后）。
     *
     * <p>注意：本方法只是投递，返回时库还没写完。<b>需要"先对齐再查询"的调用方必须改用
     * {@link #syncFromRegistryBlocking(Context)}</b>，否则查询会读到对齐前的数据。</p>
     */
    public static void syncFromRegistry(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        IO.execute(() -> doSyncFromRegistry(app));
    }

    /**
     * 全量对齐的**同步**版本：仅限已经在后台线程上的调用方（用量页 {@code load()} 就是
     * "先对齐、再聚合"，两者必须保持先后顺序）。在 UI 线程调用会撞上 Room 的主线程检查。
     */
    public static void syncFromRegistryBlocking(Context context) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 先把误用说破，而不是等 Room 抛一句通用的 "Cannot access database on the main thread"
            // —— 那句话不会告诉调用者该换成哪个方法，上一版就是这么静默失败了好几轮
            AILogger.w(TAG, "syncFromRegistryBlocking 在主线程被调用：本方法保留同步语义是给"
                    + "『先对齐再查询』的后台任务用的；UI 线程请改调异步的 syncFromRegistry()");
        }
        doSyncFromRegistry(context);
    }

    private static void doSyncFromRegistry(Context context) {
        try {
            if (context == null) return;
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            List<OnlineModelManager.OnlineModelConfig> configs = manager.getModelList();
            if (configs == null) return;

            com.oilquiz.app.ai.usage.db.dao.ModelDao dao =
                    DatabaseManager.getInstance(context).modelDao();

            Set<String> alive = new HashSet<>();
            for (OnlineModelManager.OnlineModelConfig config : configs) {
                if (config == null) continue;
                OnlineModelManager.OnlineModelConfig snap = snapshot(config);
                alive.add(ModelUsageRecorder.usageModelId(snap));
                dao.insert(toEntity(context, snap));
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
            // WARN 而非 DEBUG：这里失败意味着用量页会继续显示旧名字，是用户能看到的问题
            AILogger.w(TAG, "全量模型行同步失败: " + t.getMessage());
        }
    }

    /** 按 modelId 索引现有模型行，供用量页把裸 id 换成用户起的配置名。**须在后台线程调用** */
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
