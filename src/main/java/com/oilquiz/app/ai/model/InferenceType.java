package com.oilquiz.app.ai.model;

/**
 * 推理类型枚举
 * 用于区分本地模型推理、在线模型推理、以及 NPU（Qualcomm GenieX）端侧推理
 */
public enum InferenceType {
    LOCAL("本地模型"),
    ONLINE("在线模型"),
    /** Qualcomm GenieX 运行时：GGUF 直接跑在 Hexagon NPU（SM8750 / SM8850） */
    NPU("NPU（GenieX）");

    /** NPU 引擎在"选择模型"列表里的虚拟模型 ID */
    public static final String NPU_MODEL_ID = "npu-geniex-hexagon";

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
