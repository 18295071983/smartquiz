package com.oilquiz.app.ai.model;

/**
 * 在线模型配置表 —— 按「API 端点 + 模型名」匹配模型能力。
 *
 * 相比纯模型名推断更准：同一模型名在不同服务商端点（官方/代理/中转）上下文可能不同。
 * 匹配优先级：
 *   1. 精确端点 + 模型名
 *   2. 端点类型（如官方 DeepSeek/OpenAI/Qwen）+ 模型名
 *   3. 纯模型名（跨端点兜底）
 *   4. 未知 → 保守默认 32K
 *
 * 用法：OnlineModelProfile.match(apiUrl, modelName) → ModelProfile（含 contextWindow 等）
 */
public final class OnlineModelProfile {

    private OnlineModelProfile() {
    }

    /** 匹配结果：上下文窗口 + 端点类型名 */
    public static final class ModelProfile {
        public final int contextWindow;
        public final String provider;

        ModelProfile(int contextWindow, String provider) {
            this.contextWindow = contextWindow;
            this.provider = provider;
        }
    }

    /**
     * 按 API 地址 + 模型名匹配配置。
     * @param apiUrl  API 调用地址（可为 null）
     * @param modelName 模型名（可为 null）
     * @return 匹配的配置；无匹配时返回保守默认（32K, "Custom"）
     */
    public static ModelProfile match(String apiUrl, String modelName) {
        String url = apiUrl != null ? apiUrl.toLowerCase() : "";
        String m = modelName != null ? modelName.toLowerCase() : "";

        // ===== 一、按端点识别服务商 =====
        Provider provider = detectProvider(url);

        // ===== 二、端点 + 模型名精确匹配 =====
        ModelProfile exact = matchByProviderAndModel(provider, m);
        if (exact != null) return exact;

        // ===== 三、纯模型名兜底（跨端点） =====
        ModelProfile byName = matchByModelNameOnly(m);
        if (byName != null) return byName;

        // ===== 四、端点级默认 =====
        if (provider != Provider.UNKNOWN) {
            return new ModelProfile(provider.defaultContextWindow, provider.displayName);
        }

        return new ModelProfile(32768, "Custom");
    }

    /** 服务商枚举：默认上下文窗口 + 显示名 */
    private enum Provider {
        OPENAI("OpenAI", 128000),
        AZURE_OPENAI("Azure OpenAI", 128000),
        ANTHROPIC("Anthropic Claude", 200000),
        GOOGLE_GEMINI("Google Gemini", 1048576),
        DEEPSEEK("DeepSeek", 65536),
        QWEN_DASHSCOPE("阿里云DashScope/Qwen", 131072),
        ZHIPU_GLM("智谱GLM", 128000),
        MOONSHOT_KIMI("Moonshot Kimi", 128000),
        DOUBAO("火山引擎豆包", 131072),
        GROQ("Groq", 131072),
        TOGETHER("Together AI", 131072),
        OLLAMA("Ollama", 32768),
        LMSTUDIO("LM Studio", 32768),
        VLLM("vLLM", 32768),
        CUSTOM("Custom", 32768),
        UNKNOWN("Custom", 32768);

        final String displayName;
        final int defaultContextWindow;

        Provider(String displayName, int defaultContextWindow) {
            this.displayName = displayName;
            this.defaultContextWindow = defaultContextWindow;
        }
    }

    /** 从 API 地址识别服务商 */
    private static Provider detectProvider(String url) {
        if (url == null || url.isEmpty()) return Provider.UNKNOWN;
        if (url.contains("anthropic")) return Provider.ANTHROPIC;
        if (url.contains("generativelanguage") || url.contains("gemini.google")) return Provider.GOOGLE_GEMINI;
        if (url.contains("azure") && url.contains("openai")) return Provider.AZURE_OPENAI;
        if (url.contains("openai")) return Provider.OPENAI;
        if (url.contains("deepseek")) return Provider.DEEPSEEK;
        if (url.contains("dashscope") || url.contains("aliyun")) return Provider.QWEN_DASHSCOPE;
        if (url.contains("zhipu") || url.contains("bigmodel")) return Provider.ZHIPU_GLM;
        if (url.contains("moonshot") || url.contains("kimi")) return Provider.MOONSHOT_KIMI;
        if (url.contains("volces") || url.contains("doubao") || url.contains("ark")) return Provider.DOUBAO;
        if (url.contains("groq")) return Provider.GROQ;
        if (url.contains("together")) return Provider.TOGETHER;
        if (url.contains("ollama")) return Provider.OLLAMA;
        if (url.contains("lmstudio") || url.contains("127.0.0.1:1234") || url.contains("localhost:1234")) return Provider.LMSTUDIO;
        if (url.contains("vllm") || url.contains(":8000")) return Provider.VLLM;
        return Provider.CUSTOM;
    }

    /** 端点 + 模型名匹配（最高优先级，覆盖端点默认窗口） */
    private static ModelProfile matchByProviderAndModel(Provider p, String m) {
        if (p == null || m.isEmpty()) return null;
        switch (p) {
            case DEEPSEEK:
                if (m.contains("v3") || m.contains("r1")) return new ModelProfile(128000, "DeepSeek");
                if (m.contains("chat") || m.contains("reasoner")) return new ModelProfile(65536, "DeepSeek");
                return null;
            case OPENAI:
            case AZURE_OPENAI:
                if (m.contains("gpt-5") || m.contains("gpt-4o") || m.contains("gpt-4.1")) return new ModelProfile(128000, p.displayName);
                if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) return new ModelProfile(128000, p.displayName);
                if (m.contains("gpt-4-turbo")) return new ModelProfile(128000, p.displayName);
                if (m.contains("gpt-3.5")) return new ModelProfile(16384, p.displayName);
                return null;
            case ANTHROPIC:
                if (m.contains("claude")) return new ModelProfile(200000, "Anthropic Claude");
                return null;
            case QWEN_DASHSCOPE:
                if (m.contains("max") || m.contains("coder") || m.contains("long")) return new ModelProfile(131072, "Qwen");
                if (m.contains("qwen3") || m.contains("qwen2.5")) return new ModelProfile(32768, "Qwen");
                return null;
            case ZHIPU_GLM:
                if (m.contains("glm")) return new ModelProfile(128000, "GLM");
                return null;
            case MOONSHOT_KIMI:
                if (m.contains("kimi") || m.contains("moonshot")) return new ModelProfile(128000, "Kimi");
                return null;
            case DOUBAO:
                if (m.contains("doubao") || m.contains("1.5") || m.contains("pro")) return new ModelProfile(131072, "豆包");
                return null;
            case GOOGLE_GEMINI:
                if (m.contains("gemini")) return new ModelProfile(1048576, "Gemini");
                return null;
            default:
                return null;
        }
    }

    /** 纯模型名匹配（跨端点兜底；端点已匹配且未命中时不覆盖端点默认） */
    private static ModelProfile matchByModelNameOnly(String m) {
        if (m.isEmpty()) return null;
        if (m.contains("gpt-5") || m.contains("gpt-4o") || m.contains("gpt-4.1")) return new ModelProfile(128000, "OpenAI");
        if (m.contains("gpt-4-turbo")) return new ModelProfile(128000, "OpenAI");
        if (m.contains("gpt-3.5")) return new ModelProfile(16384, "OpenAI");
        if (m.contains("claude")) return new ModelProfile(200000, "Anthropic");
        if (m.contains("deepseek-v3")) return new ModelProfile(128000, "DeepSeek");
        if (m.contains("deepseek")) return new ModelProfile(65536, "DeepSeek");
        if (m.contains("qwen3-max") || m.contains("qwen2.5-max") || m.contains("qwen3-coder")) return new ModelProfile(131072, "Qwen");
        if (m.contains("qwen")) return new ModelProfile(32768, "Qwen");
        if (m.contains("glm")) return new ModelProfile(128000, "GLM");
        if (m.contains("kimi") || m.contains("moonshot")) return new ModelProfile(128000, "Kimi");
        if (m.contains("doubao")) return new ModelProfile(131072, "豆包");
        if (m.contains("gemini")) return new ModelProfile(1048576, "Gemini");
        return null;
    }
}
