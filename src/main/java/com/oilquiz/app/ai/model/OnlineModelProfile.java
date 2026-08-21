package com.oilquiz.app.ai.model;

/**
 * 在线模型配置表 —— 以「模型名」为唯一匹配键的模型能力配置。
 *
 * 设计原则：上下文窗口/能力是**模型本身的属性**，与调用端点无关
 * （同一模型走官方还是中转站，窗口相同）。API 地址仅用于标注服务商
 * （展示用），不参与窗口判断。
 *
 * 匹配优先级：
 *   1. 精确模型名（完全一致）
 *   2. 前缀匹配（模型名以配置键开头，如 "deepseek-chat" 命中 "deepseek-chat"）
 *   3. 关键词匹配（如 "gpt-4o" 命中 "gpt-4" 系列规则）
 *   4. 未知 → 保守 32K
 *
 * 新增模型：往 {@link #MODELS} 表加一行即可（数据驱动，无散落 if-else）。
 */
public final class OnlineModelProfile {

    private OnlineModelProfile() {
    }

    /** 匹配结果：上下文窗口 + 服务商显示名 */
    public static final class ModelProfile {
        public final int contextWindow;
        public final String provider;

        ModelProfile(int contextWindow, String provider) {
            this.contextWindow = contextWindow;
            this.provider = provider;
        }
    }

    /** 模型配置条目 */
    private static final class Entry {
        final String match;        // 匹配键（模型名关键词，小写）
        final int contextWindow;   // 上下文窗口（tokens）
        final String provider;     // 服务商显示名

        Entry(String match, int contextWindow, String provider) {
            this.match = match;
            this.contextWindow = contextWindow;
            this.provider = provider;
        }
    }

    /**
     * 模型配置表（数据驱动，单一数据源）。
     * 按「匹配键出现先后」匹配：越靠前的条目优先级越高。
     * 注意：具体型号条目必须放在系列通配条目之前（如 "gpt-4o" 在 "gpt-4" 前）。
     */
    private static final Entry[] MODELS = {
        // ===== OpenAI =====
        new Entry("gpt-5", 128000, "OpenAI"),
        new Entry("gpt-4o", 128000, "OpenAI"),
        new Entry("gpt-4.1", 128000, "OpenAI"),
        new Entry("gpt-4-turbo", 128000, "OpenAI"),
        new Entry("gpt-4", 8192, "OpenAI"),
        new Entry("o1", 200000, "OpenAI"),
        new Entry("o3", 200000, "OpenAI"),
        new Entry("o4", 200000, "OpenAI"),
        new Entry("gpt-3.5", 16384, "OpenAI"),
        // ===== Anthropic =====
        new Entry("claude-3.5", 200000, "Anthropic"),
        new Entry("claude-3", 200000, "Anthropic"),
        new Entry("claude-sonnet", 200000, "Anthropic"),
        new Entry("claude-opus", 200000, "Anthropic"),
        new Entry("claude-haiku", 200000, "Anthropic"),
        new Entry("claude", 200000, "Anthropic"),
        // ===== DeepSeek =====
        new Entry("deepseek-v3", 128000, "DeepSeek"),
        new Entry("deepseek-r1", 65536, "DeepSeek"),
        new Entry("deepseek-reasoner", 65536, "DeepSeek"),
        new Entry("deepseek-chat", 65536, "DeepSeek"),
        new Entry("deepseek-coder", 65536, "DeepSeek"),
        new Entry("deepseek", 65536, "DeepSeek"),
        // ===== Qwen / DashScope =====
        new Entry("qwen3-max", 131072, "阿里云Qwen"),
        new Entry("qwen3-coder", 131072, "阿里云Qwen"),
        new Entry("qwen2.5-max", 131072, "阿里云Qwen"),
        new Entry("qwen2.5-coder", 131072, "阿里云Qwen"),
        new Entry("qwen3", 32768, "阿里云Qwen"),
        new Entry("qwen2.5", 32768, "阿里云Qwen"),
        new Entry("qwen-plus", 32768, "阿里云Qwen"),
        new Entry("qwen-max", 32768, "阿里云Qwen"),
        new Entry("qwen-turbo", 32768, "阿里云Qwen"),
        new Entry("qwen", 32768, "阿里云Qwen"),
        // ===== GLM / 智谱 =====
        new Entry("glm-5", 128000, "智谱GLM"),
        new Entry("glm-4.5", 128000, "智谱GLM"),
        new Entry("glm-4.6", 128000, "智谱GLM"),
        new Entry("glm-4", 128000, "智谱GLM"),
        new Entry("glm4", 128000, "智谱GLM"),
        new Entry("glm", 128000, "智谱GLM"),
        // ===== Moonshot / Kimi =====
        new Entry("kimi-k2", 128000, "Moonshot"),
        new Entry("kimi-latest", 128000, "Moonshot"),
        new Entry("kimi-thinking", 128000, "Moonshot"),
        new Entry("moonshot-v1", 128000, "Moonshot"),
        new Entry("kimi", 128000, "Moonshot"),
        new Entry("moonshot", 128000, "Moonshot"),
        // ===== 豆包 / 火山 =====
        new Entry("doubao-1.5", 131072, "火山豆包"),
        new Entry("doubao-pro", 131072, "火山豆包"),
        new Entry("doubao", 131072, "火山豆包"),
        // ===== Gemini =====
        new Entry("gemini-2", 1048576, "Google"),
        new Entry("gemini-1.5", 1048576, "Google"),
        new Entry("gemini", 1048576, "Google"),
        // ===== Groq / Together（通用开源模型托管，窗口取决于具体模型，保守 32K） =====
        new Entry("llama-3", 131072, "开源托管"),
        new Entry("llama-2", 4096, "开源托管"),
        new Entry("mixtral", 32768, "开源托管"),
        new Entry("command-r", 131072, "开源托管"),
        new Entry("abab", 32768, "MiniMax"),
        new Entry("yi-large", 32768, "零一万物"),
        new Entry("yi-medium", 32768, "零一万物"),
    };

    /**
     * 按模型名匹配配置。
     * @param apiUrl  API 地址（仅用于服务商标注，不参与窗口判断）
     * @param modelName 模型名
     * @return 匹配结果；未知模型返回保守默认（32K）
     */
    public static ModelProfile match(String apiUrl, String modelName) {
        String m = modelName != null ? modelName.toLowerCase() : "";
        if (m.isEmpty()) {
            return new ModelProfile(32768, providerName(apiUrl));
        }
        // 数据表匹配（顺序 = 优先级）
        for (Entry e : MODELS) {
            if (m.contains(e.match)) {
                return new ModelProfile(e.contextWindow, e.provider);
            }
        }
        // 未知模型：保守 32K，服务商按端点标注
        return new ModelProfile(32768, providerName(apiUrl));
    }

    /** 从 API 地址推断服务商显示名（仅展示用） */
    private static String providerName(String apiUrl) {
        if (apiUrl == null) return "Custom";
        String url = apiUrl.toLowerCase();
        if (url.contains("anthropic")) return "Anthropic";
        if (url.contains("generativelanguage") || url.contains("gemini.google")) return "Google";
        if (url.contains("azure") && url.contains("openai")) return "Azure OpenAI";
        if (url.contains("openai")) return "OpenAI";
        if (url.contains("deepseek")) return "DeepSeek";
        if (url.contains("dashscope") || url.contains("aliyun")) return "阿里云";
        if (url.contains("zhipu") || url.contains("bigmodel")) return "智谱";
        if (url.contains("moonshot") || url.contains("kimi")) return "Moonshot";
        if (url.contains("volces") || url.contains("doubao") || url.contains("ark")) return "火山";
        if (url.contains("groq")) return "Groq";
        if (url.contains("together")) return "Together";
        if (url.contains("ollama")) return "Ollama";
        if (url.contains("127.0.0.1") || url.contains("localhost")) return "本地";
        return "Custom";
    }
}
