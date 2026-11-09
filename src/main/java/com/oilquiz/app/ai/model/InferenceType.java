package com.oilquiz.app.ai.model;

/**
 * 推理类型枚举
 * 用于区分本地模型推理与在线模型推理
 *
 * <p><b>GENIEX-REMOVED(2026-11-09)</b>：这里原有第三个取值 {@code NPU("NPU（HTP）")}，
 * 代表 Qualcomm GenieX 运行时。GenieX 与我们的 ggml-hexagon 后端共用同名 skel、只能留一份，
 * 已于 2026-11-01 停用、11-09 删除（原因见 {@code InferenceRouter} 的类注释）。
 *
 * <p>注意<b>不要</b>把"本地模型跑在 HTP0 上"重新加成一个推理类型：那是同一个引擎换了加速
 * 后端，权重落点由 {@code LlamaHelper.getActualBackend()} 单独报；把它并进"引擎类型"会让
 * 下游（抽屉指标、状态页）按另一个引擎处理，丢掉本已存在的真账。
 */
public enum InferenceType {
    LOCAL("本地模型"),
    ONLINE("在线模型");

    private final String displayName;

    InferenceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 根据模型ID判断推理类型
     * @param modelId 模型ID或名称
     * @return 推理类型
     */
    public static InferenceType fromModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return LOCAL;
        }
        // 在线模型通常有特定前缀或包含特定关键词
        String lowerId = modelId.toLowerCase();
        if (lowerId.contains("gpt") || lowerId.contains("claude") ||
            lowerId.contains("gemini") || lowerId.contains("online") ||
            lowerId.startsWith("http")) {
            return ONLINE;
        }
        return LOCAL;
    }
}
