package com.oilquiz.app.ai.chat.parser;

/**
 * PromptTemplate - 提示词模板
 * 
 * 功能：
 * 1. 定义系统提示词（包含输出格式要求）
 * 2. 为不同场景提供专用提示词
 * 3. 支持动态提示词注入
 */
public class PromptTemplate {

    // ========== 系统提示词 ==========

    private static final String BASE_SYSTEM_PROMPT = 
        "你是一个智能AI助手。请按照以下格式输出内容：\n\n" +
        "1. **思考过程**：用 <思考开始> 和 </思考开始> 标签包裹你的思考过程\n" +
        "   例如：<思考开始>让我分析一下这个问题...</思考开始>\n\n" +
        "2. **工具调用**：当需要调用工具时，用 <工具调用> 和 </工具调用> 标签包裹JSON格式的调用参数\n" +
        "   格式：<工具调用>{\"tool_calls\": [{\"name\": \"工具名称\", \"arguments\": {\"参数名\": \"参数值\"}}]}</工具调用>\n\n" +
        "3. **结构化数据**：返回结构化数据时，用 <数据类型=\"xxx\"> 和 </数据> 标签包裹JSON数据\n" +
        "   例如：<数据类型=\"天气\">{\"city\": \"北京\", \"temp\": 25, \"weather\": \"晴\"}</数据>\n\n" +
        "4. **普通回复**：直接输出文本，无需特殊标记\n\n" +
        "5. **错误信息**：出错时用 <错误> 和 </错误> 标签包裹错误描述\n" +
        "   例如：<错误>抱歉，我无法处理这个请求</错误>\n\n" +
        "请始终遵循以上格式输出，这样我可以更好地理解和展示你的回复。";
    
    private static final String OPENAI_TOOL_CALL_PROMPT = 
        "你是一个智能助手，可以使用工具来完成任务。\n\n" +
        "【可用工具】\n{tools}\n\n" +
        "【输出格式】\n" +
        "如果你需要使用工具，请严格按照以下 JSON 格式输出：\n" +
        "```json\n" +
        "{\"tool_calls\": [{\"name\": \"工具名称\", \"arguments\": {\"参数名\": \"参数值\"}}]}\n" +
        "```\n" +
        "如果不需要工具，直接回答用户问题。\n\n" +
        "【用户消息】\n{message}\n\n" +
        "【助手回复】\n";

    // ========== 场景提示词 ==========

    private static final String WEATHER_PROMPT_TEMPLATE = 
        "查询{city}的天气信息。\n\n" +
        "请按照以下JSON格式返回天气数据：\n" +
        "<数据类型=\"天气\">\n" +
        "{\n" +
        "  \"city\": \"城市名\",\n" +
        "  \"temp\": 温度数值,\n" +
        "  \"weather\": \"天气状况\",\n" +
        "  \"humidity\": 湿度百分比,\n" +
        "  \"wind\": \"风力风向\",\n" +
        "  \"icon\": \"天气图标代码\"\n" +
        "}\n" +
        "</数据>";

    private static final String TOOL_CALL_PROMPT_TEMPLATE = 
        "请调用工具：{toolName}\n\n" +
        "工具参数：{parameters}\n\n" +
        "请按照以下格式返回工具调用：\n" +
        "<工具调用>\n" +
        "{{\n" +
        "  \"name\": \"{toolName}\",\n" +
        "  \"parameters\": {parameters}\n" +
        "}}\n" +
        "</工具调用>";

    private static final String CODE_EXPLAIN_PROMPT_TEMPLATE = 
        "请解释以下代码的功能和逻辑：\n\n" +
        "```\n{code}\n```\n\n" +
        "请先用 <思考开始> 标签包裹你的分析过程，然后用结构化格式返回解释。";

    private static final String TRANSLATE_PROMPT_TEMPLATE = 
        "请将以下文本翻译成{targetLanguage}：\n\n" +
        "{text}\n\n" +
        "请先用 <思考开始> 标签包裹翻译思路，然后返回翻译结果。";

    private static final String SUMMARIZE_PROMPT_TEMPLATE = 
        "请总结以下内容的要点：\n\n" +
        "{content}\n\n" +
        "请先用 <思考开始> 标签包裹分析过程，然后返回结构化的总结。";

    // ========== 获取提示词 ==========

    /**
     * 获取基础系统提示词
     */
    public static String getSystemPrompt() {
        return BASE_SYSTEM_PROMPT;
    }

    /**
     * 获取天气查询提示词
     */
    public static String getWeatherPrompt(String city) {
        return WEATHER_PROMPT_TEMPLATE.replace("{city}", city);
    }

    /**
     * 获取工具调用提示词
     */
    public static String getToolCallPrompt(String toolName, String parameters) {
        return TOOL_CALL_PROMPT_TEMPLATE
            .replace("{toolName}", toolName)
            .replace("{parameters}", parameters);
    }

    /**
     * 获取代码解释提示词
     */
    public static String getCodeExplainPrompt(String code) {
        return CODE_EXPLAIN_PROMPT_TEMPLATE.replace("{code}", code);
    }

    /**
     * 获取翻译提示词
     */
    public static String getTranslatePrompt(String text, String targetLanguage) {
        return TRANSLATE_PROMPT_TEMPLATE
            .replace("{text}", text)
            .replace("{targetLanguage}", targetLanguage);
    }

    /**
     * 获取总结提示词
     */
    public static String getSummarizePrompt(String content) {
        return SUMMARIZE_PROMPT_TEMPLATE.replace("{content}", content);
    }

    /**
     * 构建带系统提示词的完整提示
     */
    public static String buildWithSystemPrompt(String userMessage) {
        return getSystemPrompt() + "\n\n" + userMessage;
    }

    /**
     * 构建带上下文的提示
     */
    public static String buildWithContext(String userMessage, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append(getSystemPrompt());
        sb.append("\n\n");
        if (context != null && !context.isEmpty()) {
            sb.append("上下文信息：\n");
            sb.append(context);
            sb.append("\n\n");
        }
        sb.append("用户问题：\n");
        sb.append(userMessage);
        return sb.toString();
    }

    /**
     * 构建带历史记录的提示
     */
    public static String buildWithHistory(String userMessage, java.util.List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append(getSystemPrompt());
        sb.append("\n\n");
        if (history != null && !history.isEmpty()) {
            sb.append("对话历史：\n");
            for (String msg : history) {
                sb.append("- ").append(msg).append("\n");
            }
            sb.append("\n");
        }
        sb.append("当前问题：\n");
        sb.append(userMessage);
        return sb.toString();
    }
    
    /**
     * 获取 OpenAI 标准格式的工具调用提示词
     */
    public static String getOpenAIToolCallPrompt(String tools, String message) {
        return OPENAI_TOOL_CALL_PROMPT
            .replace("{tools}", tools != null ? tools : "")
            .replace("{message}", message != null ? message : "");
    }
    
    /**
     * 构建带工具列表的完整提示（OpenAI 标准格式）
     */
    public static String buildWithTools(String userMessage, String tools) {
        return getOpenAIToolCallPrompt(tools, userMessage);
    }
}
