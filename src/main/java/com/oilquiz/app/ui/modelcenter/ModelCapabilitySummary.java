package com.oilquiz.app.ui.modelcenter;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.model.ProviderConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 注册模型的「能力/口径」展示摘要 —— 纯派生，不持状态、不读数据库。
 *
 * <p>为什么单独一个类：模型的能力、上下文窗口、走哪条协议这三件事原本散落在
 * 注册表（{@code OnlineModelConfig} 的扁平布尔）、{@code providers.json}
 * （{@code ProviderConfigManager} 按 URL 反推的 protocol/services）和
 * {@code models_profile} 三处，界面上一个都没露出来。用户在模型列表里看到的是
 * 名字 + URL，点不开自己注册的模型到底走 chat 还是 /responses、有没有视觉能力、
 * 上下文按什么算。</p>
 *
 * <p>本类把那三处合成一行可读摘要，供模型列表行、配置对话框、模型中心详情共用，
 * 保证三处口径一致（而不是各写一套格式化逻辑）。</p>
 *
 * <p>只读约定：协议（protocol）来自 {@code providers.json}，由 URL 匹配得出，
 * <b>不</b>提供编辑入口 —— 若开放手工覆盖，就会出现「注册表里存的协议」和
 * 「引擎实际按 URL 反推的协议」两套事实，而推理链只认后者。</p>
 */
public final class ModelCapabilitySummary {

    private ModelCapabilitySummary() {
    }

    /** 一行紧凑摘要，用于列表行副标题：能力徽章 · 上下文 · 协议 */
    public static String oneLine(OnlineModelManager.OnlineModelConfig config) {
        if (config == null) return "";
        List<String> parts = new ArrayList<>();
        String badges = badges(config);
        if (!badges.isEmpty()) parts.add(badges);
        parts.add(contextLabel(config));
        parts.add(protocolLabel(config.apiUrl, config.modelName));
        String cost = costLabel(config);
        if (cost != null) parts.add(cost);
        return join(parts);
    }

    /** 能力徽章串；无能力时返回空串（调用方据此隐藏该行） */
    public static String badges(OnlineModelManager.OnlineModelConfig config) {
        if (config == null) return "";
        List<String> caps = new ArrayList<>();
        if (config.hasCapability("vision")) caps.add("视觉");
        if (config.hasCapability("audio")) caps.add("音频");
        if (config.hasCapability("agent")) caps.add("Agent");
        if (config.hasCapability("function_calling")) caps.add("工具调用");
        if (config.hasCapability("web_search")) caps.add("联网");
        if (config.hasCapability("embedding")) caps.add("向量");
        if (config.hasCapability("rerank")) caps.add("重排");
        if (config.hasCapability("image_gen")) caps.add("文生图");
        if (config.hasCapability("tts")) caps.add("合成");
        if (config.hasCapability("asr")) caps.add("识别");
        return join(caps);
    }

    /**
     * 上下文窗口标注，并说明数值来源 —— 这决定了它可不可信：
     * API 真实检测 / 配置表推断 / 未取到。
     */
    public static String contextLabel(OnlineModelManager.OnlineModelConfig config) {
        if (config == null) return "上下文 未知";
        int window = config.contextWindow;
        if (window <= 0) return "上下文 未知";
        String size = formatTokens(window);
        if (config.contextWindowFromApi) {
            return "上下文 " + size + "（API 检测）";
        }
        return "上下文 " + size + "（按配置表推断）";
    }

    /**
     * 该端点实际走的协议。与推理链同源：{@link ProviderConfigManager#getChatProtocol}
     * 与 {@link ProviderConfigManager#agentUsesResponses}，故 UI 说的就是引擎做的。
     *
     * <p>配置表里的取值是 {@code openai}/{@code anthropic}/{@code responses}/{@code ollama}
     * 这类内部常量名，直接显示会被读成厂商名（真机上就有人把"协议 openai"看成服务商），
     * 这里翻成端点形态。</p>
     */
    public static String protocolLabel(String apiUrl) {
        return protocolLabel(apiUrl, null);
    }

    /**
     * 同上，但按「服务商 + 模型」判 Agent 实际打哪条端点。
     *
     * <p>徽章必须和引擎同一判据：配置表一旦支持模型级 services 覆盖，只看服务商级就会出现
     * "界面写着 Agent 走 /responses、实际打 chat"（或反过来）的错误承诺。</p>
     */
    public static String protocolLabel(String apiUrl, String modelName) {
        try {
            ProviderConfigManager pcm = ProviderConfigManager.get();
            String protocol = pcm.getChatProtocol(apiUrl);
            if (protocol == null || protocol.isEmpty()) protocol = ProviderConfigManager.PROTOCOL_OPENAI;
            String label;
            switch (protocol) {
                case ProviderConfigManager.PROTOCOL_ANTHROPIC:
                    label = "Anthropic Messages";
                    break;
                case ProviderConfigManager.PROTOCOL_RESPONSES:
                    label = "OpenAI Responses";
                    break;
                case ProviderConfigManager.PROTOCOL_OLLAMA:
                    label = "Ollama 原生";
                    break;
                case ProviderConfigManager.PROTOCOL_OPENAI:
                default:
                    label = "OpenAI 兼容";
                    break;
            }
            // 声明走 Responses 的服务商：Agent 链路实际打 /responses，展示上要区分开
            if (pcm.agentUsesResponses(apiUrl, modelName)
                    && !ProviderConfigManager.PROTOCOL_RESPONSES.equals(protocol)) {
                return "协议 " + label + "，Agent 走 /responses";
            }
            return "协议 " + label;
        } catch (Exception e) {
            return "协议 未知";
        }
    }

    /**
     * 单价标注。未设时返回 null（调用方直接不显示），因为
     * 「未设」和「免费」在两回事 —— 留空比显示 $0 诚实。
     */
    public static String costLabel(OnlineModelManager.OnlineModelConfig config) {
        if (config == null || config.costPerMillionTokens <= 0) return null;
        return String.format(Locale.getDefault(), "$%.2f/M", config.costPerMillionTokens);
    }

    /** token 数的紧凑写法：128000 → 128K，1048576 → 1.0M */
    public static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) {
            return String.format(Locale.getDefault(), "%.1fM", tokens / 1_000_000.0);
        }
        if (tokens >= 1024) {
            int k = Math.round(tokens / 1024f);
            return k + "K";
        }
        return String.valueOf(tokens);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }
}
