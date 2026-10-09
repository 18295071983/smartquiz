package com.oilquiz.app.ai.model;

import java.io.Serializable;

/**
 * API 模型信息
 */
public class ApiModel implements Serializable {
    
    /**
     * 模型标识
     */
    public String id;
    
    /**
     * 模型显示名称
     */
    public String displayName;
    
    /**
     * 所属组织
     */
    public String ownedBy;
    
    /**
     * 上下文长度
     */
    public int contextLength;
    
    /**
     * contextLength 是否来自服务商 API 返回的真实字段（context_length/max_model_len 等），
     * 而非模型名启发式推断。配置保存时只有真实值才直接采用，推断值需走 API 补查。
     */
    public boolean contextLengthFromApi;
    
    /**
     * 是否已弃用
     */
    public boolean deprecated;
    
    /**
     * 创建时间
     */
    public long createdAt;
    
    /**
     * 模型来源：openai, anthropic, custom
     */
    public String source;

    /**
     * 模型能力列表（TTS, ASR, Realtime-Text-to-Speech 等）
     */
    public java.util.List<String> capabilities;

    /**
     * 思考模式支持的**强度档位** —— 来自服务商 {@code GET /models} 的
     * {@code effort.supported_levels}（DeepSeek 官方 schema 字段）。
     *
     * <p>官方说明：该数组是"模型在思考模式下支持的强度档位，按推荐显示顺序，
     * 即 {@code reasoning_effort} 参数接受的取值"，且**不含** {@code none}
     * （{@code none} 表示关闭思考模式）。</p>
     *
     * <p><b>为什么必须取 API、不能硬编码</b>：档位是"每家甚至每个模型"各不相同的能力声明
     * —— GLM-5.3 只接受 {@code max/high/low}，GLM-5.2 另有 {@code xhigh/medium/minimal/none}，
     * 而不少服务商只有开关没有档位。硬编码一张全局档位表，就会出现
     * "用户随便点一个档位 → 给某模型传入它不支持的取值 → 直接报错"。
     * 本字段为空即表示**服务商未声明档位能力**：此时 UI 不展示档位选择、
     * 请求也不下发强度参数（只保留开关）。</p>
     */
    public java.util.List<String> thinkingEffortLevels = new java.util.ArrayList<>();

    /** 服务端默认强度档位（{@code effort.default_level}，未声明为 null） */
    public String thinkingEffortDefault;

    /** 服务端允许的最大输出 token 数（{@code max_output_tokens}，0 表示未声明） */
    public int maxOutputTokens;

    /**
     * 服务商声明的**输入模态**（{@code GET /models} 的 {@code input_modalities}）。
     *
     * <p>官方定义："模型接受的输入类型"，取值 {@code text} / {@code image}。
     * 这是判断**多模态（图片输入）**的权威依据 —— 例如 DeepSeek 的
     * {@code deepseek-flash} 为 {@code ["text","image"]}（支持图像理解），
     * 而 {@code deepseek-v4-pro} 仅有 {@code ["text"]}。</p>
     *
     * <p><b>为什么不能用模型名猜</b>：此前靠 {@code contains("vl"/"vision"/"4o"/"omni")}
     * 之类的关键词推断，{@code deepseek-flash} 一个都不含 → 被判为"不支持图片"，
     * 于是官方明确支持图像理解的模型在应用里用不了图。</p>
     */
    public java.util.List<String> inputModalities = new java.util.ArrayList<>();

    /** 是否支持图片输入（依据服务商声明的 input_modalities，未声明则 false） */
    public boolean supportsImageInput() {
        if (inputModalities == null) return false;
        for (String m : inputModalities) {
            if ("image".equalsIgnoreCase(m)) return true;
        }
        return false;
    }

    /** 该模型是否声明了思考强度档位（UI 与请求注入据此决定是否处理"强度"） */
    public boolean hasThinkingEffortLevels() {
        return thinkingEffortLevels != null && !thinkingEffortLevels.isEmpty();
    }
    
    public ApiModel() {}
    
    public ApiModel(String id) {
        this.id = id;
        this.displayName = id;
        this.contextLength = 0;
        this.deprecated = false;
    }
    
    public ApiModel(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.contextLength = 0;
        this.deprecated = false;
    }
    
    /**
     * 创建 OpenAI 格式的模型
     */
    public static ApiModel fromOpenAI(String id, String ownedBy, long createdAt) {
        return fromOpenAI(id, null, ownedBy, createdAt);
    }

    /**
     * 创建 OpenAI 格式的模型（带服务商提供的**显示名**）。
     *
     * <p>官方 {@code GET /models} 的 {@code name} 是"用于模型选择器的显示名"，
     * 且**与 id 不同**：例如 {@code id=deepseek-flash} 而
     * {@code name=DeepSeek-V4.1-Flash}。若忽略该字段，UI 就只会显示 id，
     * 用户看不到 v4.1 这类版本信息。</p>
     *
     * @param displayName 服务商返回的 name；为空时回落到 id
     */
    public static ApiModel fromOpenAI(String id, String displayName, String ownedBy, long createdAt) {
        ApiModel model = new ApiModel();
        model.id = id;
        model.displayName = (displayName != null && !displayName.isEmpty()) ? displayName : id;
        model.ownedBy = ownedBy;
        model.createdAt = createdAt;
        model.source = "openai";
        model.deprecated = false;
        model.contextLength = detectContextLength(id);
        return model;
    }
    
    /**
     * 创建 Anthropic 格式的模型
     */
    public static ApiModel fromAnthropic(String name) {
        ApiModel model = new ApiModel();
        model.id = name;
        model.displayName = name;
        model.source = "anthropic";
        model.deprecated = false;
        model.contextLength = detectAnthropicContextLength(name);
        return model;
    }
    
    /**
     * 从模型 ID 推断上下文长度。
     * 复用数据驱动配置表 {@link OnlineModelProfile}（deepseek→64K、qwen3→32K、gemini→1M 等），
     * 替代旧的名称启发式（只认 32k/16k 关键词、其余一律 4096 死值）。
     * 服务商 API 返回真实字段时由调用方覆盖此推断值。
     */
    private static int detectContextLength(String modelId) {
        return OnlineModelProfile.match(null, modelId).contextWindow;
    }
    
    /**
     * 从模型名称推断 Anthropic 模型的上下文长度（复用配置表，claude 系列统一 200K）。
     */
    private static int detectAnthropicContextLength(String modelName) {
        return OnlineModelProfile.match(null, modelName).contextWindow;
    }
    
    /**
     * 获取格式化的上下文长度
     */
    public String getFormattedContextLength() {
        if (contextLength >= 1000) {
            return (contextLength / 1000) + "K";
        }
        return String.valueOf(contextLength);
    }
    
    /**
     * 获取模型名称（用于兼容性）
     */
    public String getName() {
        return displayName != null ? displayName : id;
    }
    
    /**
     * 是否是可用的模型（非弃用）
     */
    public boolean isAvailable() {
        return !deprecated;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ApiModel apiModel = (ApiModel) obj;
        return id != null && id.equals(apiModel.id);
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
    
    @Override
    public String toString() {
        return displayName + " (" + getFormattedContextLength() + " context)";
    }
}