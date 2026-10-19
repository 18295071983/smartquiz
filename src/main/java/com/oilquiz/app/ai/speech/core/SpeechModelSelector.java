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
 * 1. 勾选了对应能力的启用模型（ASR 认 supportsAsr，TTS 认 supportsTts），当前激活者优先；
 *    同档内再按模型名形态排序（见 {@link #isAsrModelName} / {@link #isTtsModelName}）
 * 2. 语音服务商端点（百炼/讯飞/火山/百度/MiMo）的启用模型 —— 兜底档，
 *    让**没勾过能力**的老配置不至于因为第 1 档收紧而突然选不到
 * 3. 标记 supportsAudio 的启用模型（supportsAudio 由端点地址推断，非用户勾选）
 *
 * <p>历史坑：本类曾声明"按具体模型名启发式匹配"并算出 keywords 数组，但循环体从未使用它，
 * 实际第一档就是端点域名匹配 —— 于是配置表单里的"语音合成/语音识别"勾选格
 * 对运行时毫无影响（只被 providers.json 刷新读写）。现在第 1 档真正读这两个勾选。</p>
 */
public final class SpeechModelSelector {

    private static final String TAG = "SpeechModelSelector";

    public enum Capability { ASR, TTS }
    public enum EndpointType { DASHSCOPE, XFYUN, VOLCANO, BAIDU, MIMO, OPENAI }

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
        DOMAIN_TYPE_MAP.put("xiaomimimo.com", EndpointType.MIMO);
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
    /** 小米 MiMo 端点的兜底模型 */
    private static final String DEFAULT_MIMO_ASR_MODEL = "mimo-v2.5-asr";
    private static final String DEFAULT_MIMO_TTS_MODEL = "mimo-v2.5-tts";

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

            // 1. 用户勾选了对应能力的模型：当前激活者优先，同档内模型名形态匹配者优先。
            //    这是配置表单里「语音合成 / 语音识别」勾选唯一真正生效的地方。
            OnlineModelManager.OnlineModelConfig byCapability =
                    pickByCapability(all, active, capability, true);
            if (byCapability == null) {
                byCapability = pickByCapability(all, active, capability, false);
            }
            if (byCapability != null) {
                AILogger.i(TAG, "Using capability-matched " + capability + " model: " + byCapability.name);
                return byCapability;
            }

            // 2. 语音服务商端点兜底（不要求勾选，保住"老配置没勾过能力"的用户不至于突然选不到）
            for (OnlineModelManager.OnlineModelConfig c : ordered(all, active)) {
                if (!c.enabled) continue;
                if (isSpeechProviderEndpoint(c.apiUrl)) {
                    AILogger.i(TAG, "Using speech-provider " + capability
                            + " endpoint (未勾选该能力): " + c.name);
                    return c;
                }
            }

            // 3. 端点地址推断出的音频能力（supportsAudio 不是用户勾选，是 isAudioModel 的结果）
            for (OnlineModelManager.OnlineModelConfig c : ordered(all, active)) {
                if (c.enabled && c.supportsAudio) {
                    return c;
                }
            }
            return null;
        } catch (Exception e) {
            AILogger.e(TAG, "select(" + capability + ") failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 音色持久化的作用域键 = 实际用于合成的那条 TTS 配置 id。
     *
     * <p>写入（{@code SpeechManager.saveTtsVoice}）与读取（{@code TTSService.resolveVoice}）
     * 必须走同一个解析口，否则会出现"按 A 端点存的音色，合成时按 B 端点读"而静默回落默认音色。</p>
     *
     * @return 配置 id；没有可用 TTS 配置时返回 null（调用方按无作用域处理）
     */
    public static String voiceScopeId(Context context) {
        OnlineModelManager.OnlineModelConfig config = select(context, Capability.TTS);
        return config != null ? config.id : null;
    }

    /** 该配置是否被用户勾选为具备这一路语音能力 */
    private static boolean hasCapability(OnlineModelManager.OnlineModelConfig config,
                                         Capability capability) {
        return capability == Capability.ASR ? config.supportsAsr : config.supportsTts;
    }

    /** 模型名形态是否与该能力相符（用于同档内排序，不做硬过滤） */
    private static boolean nameMatchesCapability(String modelName, Capability capability) {
        return capability == Capability.ASR ? isAsrModelName(modelName) : isTtsModelName(modelName);
    }

    private static String probedModelName(OnlineModelManager.OnlineModelConfig config) {
        String name = config.selectedModel != null && !config.selectedModel.isEmpty()
                ? config.selectedModel : config.modelName;
        return name;
    }

    /**
     * 按能力勾选挑一个模型。
     *
     * @param preferNameMatch true 时只认「勾选了能力且模型名形态也相符」的配置；
     *                        false 时只看勾选。分两趟是为了让名形相符的排前面，
     *                        而不是把勾选档直接砍掉。
     */
    private static OnlineModelManager.OnlineModelConfig pickByCapability(
            List<OnlineModelManager.OnlineModelConfig> all,
            OnlineModelManager.OnlineModelConfig active,
            Capability capability,
            boolean preferNameMatch) {
        for (OnlineModelManager.OnlineModelConfig c : ordered(all, active)) {
            if (!c.enabled || !hasCapability(c, capability)) continue;
            if (preferNameMatch && !nameMatchesCapability(probedModelName(c), capability)) continue;
            return c;
        }
        return null;
    }

    /** 当前激活模型排在最前：同能力档内优先沿用手上正在用的那个端点 */
    private static List<OnlineModelManager.OnlineModelConfig> ordered(
            List<OnlineModelManager.OnlineModelConfig> all,
            OnlineModelManager.OnlineModelConfig active) {
        List<OnlineModelManager.OnlineModelConfig> out =
                new java.util.ArrayList<>(all.size());
        if (active != null) out.add(active);
        for (OnlineModelManager.OnlineModelConfig c : all) {
            // 按 id 去重而非对象引用：调用方拿到的是副本时引用比较会失效，导致激活模型出现两次
            if (active != null && active.id != null && active.id.equals(c.id)) continue;
            out.add(c);
        }
        return out;
    }

    /** 是否为已知语音服务商端点（百炼/讯飞/火山/百度/MiMo） */
    public static boolean isSpeechProviderEndpoint(String apiUrl) {
        return isDashScopeEndpoint(apiUrl) || isXfyunEndpoint(apiUrl)
                || isVolcanoEndpoint(apiUrl) || isBaiduEndpoint(apiUrl)
                || isMimoEndpoint(apiUrl);
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
        // ASR 防御：用户可能把 TTS 模型（如 qwen3-tts-flash / cosyvoice / sambert）误配为"语音识别专用模型"，
        // 不应直接拿去识别，回落端点默认 ASR 模型。
        if (capability == Capability.ASR && isTtsModelName(override)) {
            AILogger.w(TAG, "ASR override '" + override + "' 疑似 TTS 模型，回落端点默认 ASR 模型");
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
        if (capability == Capability.ASR && isTtsModelName(m)) {
            AILogger.w(TAG, "ASR selectedModel '" + m + "' 疑似 TTS 模型，回落端点默认 ASR 模型");
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
        if (isMimoEndpoint(config.apiUrl)) {
            return capability == Capability.ASR ? DEFAULT_MIMO_ASR_MODEL : DEFAULT_MIMO_TTS_MODEL;
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
     * 判断模型名是否明显为 TTS（语音合成）模型。
     * 用于在 ASR 场景下拦截被误配为语音识别专用模型的 TTS 模型名。
     * 注意排除含 asr/paraformer/sensevoice/whisper 的命名，避免误判真正的 ASR 模型。
     */
    public static boolean isTtsModelName(String modelName) {
        if (modelName == null || modelName.isEmpty()) return false;
        String n = modelName.toLowerCase();
        if (n.contains("asr") || n.contains("paraformer") || n.contains("sensevoice")
                || n.contains("whisper") || n.contains("fun-asr") || n.contains("gummy")) {
            return false;
        }
        return n.contains("tts") || n.contains("cosyvoice") || n.contains("sambert")
                || n.contains("speech") || n.contains("qwen3-tts") || n.contains("qwen-tts");
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

    /** 是否为小米 MiMo 端点 */
    public static boolean isMimoEndpoint(String apiUrl) {
        return getEndpointType(apiUrl) == EndpointType.MIMO;
    }

    /**
     * 识别端点类型（供引擎路由使用）
     * @return "dashscope" / "xfyun" / "volcano" / "baidu" / "mimo" / "openai"
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
