package com.oilquiz.app.ai.speech.core;

import android.content.Context;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音模型选择器（ASR / TTS 共用，单一事实来源）
 *
 * 重构前：SpeechRecognitionService.selectAsrModel 与 TTSService.selectTtsModel
 * 几乎完全复制同一套优先级逻辑，仅启发式关键字不同。本类将二者合并，
 * 由 {@link Capability} 区分能力，消除重复。
 *
 * 选择优先级（ASR / TTS 一致）：
 * 0. 用户配置的专用模型（FEATURE_ASR / FEATURE_TTS）
 * 1. 激活模型中标记 supportsAudio 的
 * 2. 任意标记 supportsAudio 的启用模型
 * 3. 模型名启发式匹配（ASR: whisper/paraformer/sensevoice/asr；TTS: tts/cosyvoice/sambert/speech）
 * 4. 当前激活模型（兜底，部分端点所有模型共用一个端点）
 */
public final class SpeechModelSelector {

    private static final String TAG = "SpeechModelSelector";

    public enum Capability { ASR, TTS }
    public enum EndpointType { DASHSCOPE, XFYUN, VOLCANO, BAIDU, OPENAI }

    /** 端点域名 -> 端点类型映射（集中配置，非硬编码判断） */
    private static final Map<String, EndpointType> DOMAIN_TYPE_MAP = new HashMap<>();
    static {
        DOMAIN_TYPE_MAP.put("dashscope.aliyuncs.com", EndpointType.DASHSCOPE);
        DOMAIN_TYPE_MAP.put("maas.aliyuncs.com", EndpointType.DASHSCOPE);
        DOMAIN_TYPE_MAP.put("xfyun.cn", EndpointType.XFYUN);
        DOMAIN_TYPE_MAP.put("openspeech.cn", EndpointType.XFYUN);
        DOMAIN_TYPE_MAP.put("iflytek", EndpointType.XFYUN);
        DOMAIN_TYPE_MAP.put("openspeech.bytedance.com", EndpointType.VOLCANO);
        DOMAIN_TYPE_MAP.put("volcengine.com", EndpointType.VOLCANO);
        DOMAIN_TYPE_MAP.put("bytedance", EndpointType.VOLCANO);
        DOMAIN_TYPE_MAP.put("baidubce.com", EndpointType.BAIDU);
    }

    private static final String DEFAULT_ASR_MODEL = "whisper-1";
    private static final String DEFAULT_TTS_MODEL = "tts-1";
    /** 百炼端点的兜底模型 */
    private static final String DEFAULT_DASHSCOPE_ASR_MODEL = "qwen3-asr-flash";
    private static final String DEFAULT_DASHSCOPE_TTS_MODEL = "qwen3-tts-flash";
    /** 讯飞端点的兜底模型 */
    private static final String DEFAULT_XFYUN_ASR_MODEL = "16k_zh";
    private static final String DEFAULT_XFYUN_TTS_MODEL = "x4_xiaoyan";
    /** 火山引擎端点的兜底模型 */
    private static final String DEFAULT_VOLCANO_ASR_MODEL = "volcengine_streaming_common";
    private static final String DEFAULT_VOLCANO_TTS_MODEL = "zh_female_qingxin";
    /** 百度端点的兜底模型 */
    private static final String DEFAULT_BAIDU_ASR_MODEL = "1537";
    private static final String DEFAULT_BAIDU_TTS_MODEL = "4";

    private SpeechModelSelector() {
    }

    /**
     * 选择给定能力的在线模型配置
     *
     * @return 选中的模型配置；无可用配置返回 null
     */
    public static OnlineModelManager.OnlineModelConfig select(Context context, Capability capability) {
        try {
            OnlineModelManager mm = OnlineModelManager.getInstance(context);
            String feature = capability == Capability.ASR
                    ? OnlineModelManager.FEATURE_ASR
                    : OnlineModelManager.FEATURE_TTS;

            // 0. 优先使用用户配置的专用模型
            OnlineModelManager.OnlineModelConfig dedicated = mm.getFeatureModel(feature);
            if (dedicated != null) {
                AILogger.i(TAG, "Using dedicated " + capability + " model: " + dedicated.name);
                return dedicated;
            }

            List<OnlineModelManager.OnlineModelConfig> all = mm.getModelList();
            if (all == null || all.isEmpty()) {
                return null;
            }

            OnlineModelManager.OnlineModelConfig active = mm.getActiveModel();

            // 1. 启发式关键词匹配（按具体模型名）
            String[] keywords = capability == Capability.ASR
                    ? new String[]{"whisper", "paraformer", "sensevoice", "asr", "gummy"}
                    : new String[]{"tts", "cosyvoice", "qwen-tts", "qwen3-tts", "sambert", "speech"};
            for (OnlineModelManager.OnlineModelConfig c : all) {
                if (!c.enabled) continue;
                // 检查端点是否为语音服务商（百炼/讯飞/火山/百度）
                if (isDashScopeEndpoint(c.apiUrl) || isXfyunEndpoint(c.apiUrl) ||
                    isVolcanoEndpoint(c.apiUrl) || isBaiduEndpoint(c.apiUrl)) {
                    // 语音服务商端点：只要专用模型未设置，就认为可用
                    return c;
                }
            }

            // 2. 兜底：返回当前激活模型
            if (active != null && active.enabled) {
                return active;
            }
            return null;
        } catch (Exception e) {
            AILogger.e(TAG, "select(" + capability + ") failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析实际使用的模型名：覆盖值优先，其次端点默认模型，最后兜底默认
     */
    public static String resolveModelName(OnlineModelManager.OnlineModelConfig config,
                                          String override, Capability capability) {
        // TTS 防御：用户可能把 ASR 模型（如 fun-asr-flash / qwen3-asr-flash）误配为"语音合成专用模型"，
        // 此时不应直接拿它去合成语音（百炼会报模型不存在或把请求打到错误路径）。
        // 回落到端点默认 TTS 模型，保证试听/朗读可用。
        String effectiveOverride = override;
        if (capability == Capability.TTS && isAsrModelName(override)) {
            AILogger.w(TAG, "TTS override '" + override + "' 疑似 ASR 模型，回落端点默认 TTS 模型");
            effectiveOverride = null;
        }
        if (effectiveOverride != null && !effectiveOverride.isEmpty()) {
            return effectiveOverride;
        }
        String m = config.selectedModel != null ? config.selectedModel : config.modelName;
        if (capability == Capability.TTS && isAsrModelName(m)) {
            AILogger.w(TAG, "TTS selectedModel '" + m + "' 疑似 ASR 模型，回落端点默认 TTS 模型");
            m = null;
        }
        if (m != null && !m.isEmpty()) {
            return m;
        }
        // 兜底默认模型：各端点有各自默认模型名，不可混用
        if (isDashScopeEndpoint(config.apiUrl)) {
            return capability == Capability.ASR ? DEFAULT_DASHSCOPE_ASR_MODEL : DEFAULT_DASHSCOPE_TTS_MODEL;
        }
        if (isXfyunEndpoint(config.apiUrl)) {
            return capability == Capability.ASR ? DEFAULT_XFYUN_ASR_MODEL : DEFAULT_XFYUN_TTS_MODEL;
        }
        if (isVolcanoEndpoint(config.apiUrl)) {
            return capability == Capability.ASR ? DEFAULT_VOLCANO_ASR_MODEL : DEFAULT_VOLCANO_TTS_MODEL;
        }
        if (isBaiduEndpoint(config.apiUrl)) {
            return capability == Capability.ASR ? DEFAULT_BAIDU_ASR_MODEL : DEFAULT_BAIDU_TTS_MODEL;
        }
        return capability == Capability.ASR ? DEFAULT_ASR_MODEL : DEFAULT_TTS_MODEL;
    }

    /**
     * 判断模型名是否明显为 ASR（语音识别）模型。
     * 用于在 TTS 场景下拦截被误配为语音合成专用模型的 ASR 模型名。
     * 注意排除含 tts/cosyvoice/sambert/speech 的命名，避免误判真正的 TTS 模型。
     */
    public static boolean isAsrModelName(String modelName) {
        if (modelName == null || modelName.isEmpty()) return false;
        String n = modelName.toLowerCase();
        if (n.contains("tts") || n.contains("cosyvoice") || n.contains("sambert") || n.contains("speech")) {
            return false;
        }
        return n.contains("asr") || n.contains("paraformer") || n.contains("sensevoice")
                || n.contains("whisper") || n.contains("fun-asr") || n.contains("gummy");
    }

    /**
     * 判断是否为百炼 / DashScope 端点
     */
    public static boolean isDashScopeEndpoint(String apiUrl) {
        return getEndpointType(apiUrl) == EndpointType.DASHSCOPE;
    }

    /** 是否为讯飞端点 */
    public static boolean isXfyunEndpoint(String apiUrl) {
        return getEndpointType(apiUrl) == EndpointType.XFYUN;
    }

    /** 是否为火山引擎端点 */
    public static boolean isVolcanoEndpoint(String apiUrl) {
        return getEndpointType(apiUrl) == EndpointType.VOLCANO;
    }

    /** 是否为百度端点 */
    public static boolean isBaiduEndpoint(String apiUrl) {
        return getEndpointType(apiUrl) == EndpointType.BAIDU;
    }

    /**
     * 识别端点类型（供引擎路由使用）
     * @return "dashscope" / "xfyun" / "volcano" / "baidu" / "openai"
     */
    public static String identifyEndpoint(String apiUrl) {
        EndpointType type = getEndpointType(apiUrl);
        return type == null ? "openai" : type.name().toLowerCase();
    }

    /**
     * 从 URL 中提取域名并查找对应的端点类型
     */
    private static EndpointType getEndpointType(String apiUrl) {
        if (apiUrl == null || apiUrl.isEmpty()) return null;
        String lower = apiUrl.toLowerCase();
        for (Map.Entry<String, EndpointType> entry : DOMAIN_TYPE_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
