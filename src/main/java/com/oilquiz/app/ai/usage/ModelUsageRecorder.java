package com.oilquiz.app.ai.usage;

import android.content.Context;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.tool.TaskStateTracker;
import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.entity.ApiUsageLog;
import com.oilquiz.app.util.AILogger;

/**
 * 在线调用量的**唯一**落库入口。
 *
 * <p>历史背景：此前只有 4 个非流式/Responses 解析点各自 new 一个
 * {@code UsageInterceptingWrapper} 手写一行，且传的是伪造值
 * （sessionId 空串、traceId="local_"+now、startTime=now-5000 —— 于是每行 duration 恒为 5000ms、
 * callTime 一律早 5 秒），而两条主聊天流式路径（{@code readStreamResponse} /
 * {@code readStreamResponseWithTools}）根本不写这张表。结果是用量表只覆盖一小部分调用，
 * 且覆盖到的行时间字段不可信。本类把「一行怎么写、写成什么」收敛到一处，各调用路径只负责
 * 提供自己那次的真实上下文。</p>
 *
 * <p>口径约定：</p>
 * <ul>
 *   <li>{@code modelId} / {@code providerId} 由注册表配置派生，与
 *       {@link ModelUsageBridge} 写 {@code models} 表时用**同一对静态方法**，
 *       保证用量行能和注册模型 join，不出现两套命名。</li>
 *   <li>{@code sessionId} 取 {@link TaskStateTracker#getActiveSession()}：这是全应用唯一的
 *       活跃会话持有者，聊天/Agent 链路在发请求前已写入。非会话类调用（知识库、OCR、
 *       题目修复等共享本单例的调用方）会读到上一次会话残留值，因此
 *       <b>不</b>按 sessionId 做任何统计聚合，只作留痕。</li>
 *   <li>金额列（{@code *Cost}）有意恒为 0：本应用不使用价格配置表，用量界面只统计 token，
 *       不显示钱。看到 0 不是缺陷，是设计选择。</li>
 *   <li>{@code duration} 有意写 0（未计时）：真实的请求耗时需要在
 *       {@code OnlineInferenceService} 的 16 处建连点各插一个起始时间戳才能成立，
 *       为凑一个次要指标去改动主推理链不划算。<b>此前这里写的是 {@code now-5000} 伪造值，
 *       于是每一行的 duration 恒等于 5000ms</b> —— 现在宁可留空，不编数。</li>
 * </ul>
 */
public final class ModelUsageRecorder {

    private static final String TAG = "ModelUsageRecorder";

    /** 落库时的 userId：与 UsageDao 现有按 :userId 过滤的查询保持一致，不引入第二套归属口径 */
    private static final String LOCAL_USER = "local";

    private ModelUsageRecorder() {
    }

    /**
     * 记一次在线调用。任何异常都吞掉：用量留痕不得影响推理主链路。
     *
     * @param active       发起该次调用的注册配置；为 null 时跳过（无法归属就不写脏行）
     * @param inputTokens  prompt / input token 数
     * @param outputTokens completion / output token 数
     * @param cachedTokens 命中缓存的输入 token 数，无则传 0
     */
    public static void record(Context context,
                              OnlineModelManager.OnlineModelConfig active,
                              int inputTokens,
                              int outputTokens,
                              int cachedTokens) {
        try {
            if (context == null || active == null) return;
            if (inputTokens <= 0 && outputTokens <= 0) return; // 服务商没回 usage：不记空行

            long now = System.currentTimeMillis();

            ApiUsageLog log = new ApiUsageLog();
            log.setTraceId(java.util.UUID.randomUUID().toString());
            log.setSessionId(TaskStateTracker.getInstance(context).getActiveSession());
            log.setUserId(LOCAL_USER);
            log.setModelId(usageModelId(active));
            log.setProviderId(usageProviderId(active));
            log.setInputTokens(inputTokens);
            log.setOutputTokens(outputTokens);
            log.setTotalTokens(inputTokens + outputTokens);
            log.setCacheHitTokens(Math.max(0, cachedTokens));
            log.setStatus("success");
            log.setDuration(0); // 未计时，见类注释
            log.setCallTime(now);
            // 金额列保持默认 0：见类注释「不使用价格表，只统计 token」

            DatabaseManager.getInstance(context).usageDao().insertUsageLog(log);

            // 顺手保证 models 表里有该模型行，使按模型统计能被注册名标注（单行 upsert，代价可忽略）
            ModelUsageBridge.upsert(context, active);
        } catch (Throwable t) {
            // WARN 而不是 DEBUG：用量一行没落进去，用量页就少一条统计，而界面上一切正常 ——
            // 同一条静默口径此前把"投射从未成功过"藏了好几轮（见 ModelUsageBridge#IO 的说明）
            AILogger.w(TAG, "用量落库失败: " + t.getMessage());
        }
    }

    /** 无缓存 token 信息的调用路径用这个重载 */
    public static void record(Context context,
                              OnlineModelManager.OnlineModelConfig active,
                              int inputTokens,
                              int outputTokens) {
        record(context, active, inputTokens, outputTokens, 0);
    }

    /**
     * 用量行的模型标识口径：与注册表「当前选中/使用的具体模型名」一致。
     * 取 modelName，缺省回退 name —— 沿用改造前 {@code OnlineInferenceService} 里的既有优先级，
     * 避免换口径导致历史行与新行按模型聚合时裂成两堆。
     */
    public static String usageModelId(OnlineModelManager.OnlineModelConfig config) {
        if (config == null) return "unknown";
        if (config.modelName != null && !config.modelName.isEmpty()) return config.modelName;
        if (config.selectedModel != null && !config.selectedModel.isEmpty()) return config.selectedModel;
        return config.name != null && !config.name.isEmpty() ? config.name : "unknown";
    }

    /**
     * 用量行的服务商标识口径：取 apiUrl 的 host。
     * 与改造前一致（原实现就地写在推理服务里），抽到此处是为了让 {@code models} 表的
     * providerId 能用同一个函数算出来。
     */
    public static String usageProviderId(OnlineModelManager.OnlineModelConfig config) {
        if (config == null || config.apiUrl == null) return "";
        try {
            return config.apiUrl.replaceAll("^https?://", "").split("/")[0];
        } catch (Exception e) {
            return "";
        }
    }
}
