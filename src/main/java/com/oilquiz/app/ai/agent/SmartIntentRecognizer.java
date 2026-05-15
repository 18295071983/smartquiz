package com.oilquiz.app.ai.agent;

import android.content.Context;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class SmartIntentRecognizer {

    private static final String TAG = "SmartIntentRecognizer";
    private static final double CONFIDENCE_HIGH = 0.7;
    private static final double CONFIDENCE_LOW = 0.3;
    private static final int LLM_MAX_TOKENS = 40;
    private static final float LLM_TEMPERATURE = 0.1f;
    private static final int CACHE_MAX_SIZE = 200;

    private static SmartIntentRecognizer instance;
    private final Context context;
    private AgentService agentService;
    private final Map<String, Intent> toolNameToIntent = new HashMap<>();
    private final Map<String, Intent> toolDescToIntent = new HashMap<>();
    private final Map<String, IntentResult> resultCache = new ConcurrentHashMap<>();

    public enum Intent {
        WEATHER("weather", "天气查询", true),
        SEARCH("search", "搜索查询", true),
        DATABASE("database", "数据库操作", true),
        CALCULATOR("calculator", "数学计算", true),
        TRANSLATE("translate", "翻译", false),
        CREATIVE("creative", "创意写作", false),
        LEARNING("learning", "学习辅助", false),
        ANALYSIS("analysis", "深度分析", false),
        OCR("ocr", "图片识别", true),
        FILE("file", "文件处理", true),
        WEB("web", "网页浏览", true),
        QUIZ("quiz", "题目相关", true),
        CHAT("chat", "普通对话", false),
        UNKNOWN("unknown", "未知", false);

        public final String id;
        public final String displayName;
        public final boolean needsTool;

        Intent(String id, String displayName, boolean needsTool) {
            this.id = id;
            this.displayName = displayName;
            this.needsTool = needsTool;
        }
    }

    public static class IntentResult {
        public final Intent intent;
        public final double confidence;
        public final String extractedEntity;
        public final Map<String, Object> parameters;
        public final String source;

        public IntentResult(Intent intent, double confidence, String extractedEntity, Map<String, Object> parameters, String source) {
            this.intent = intent;
            this.confidence = confidence;
            this.extractedEntity = extractedEntity;
            this.parameters = parameters != null ? parameters : new HashMap<>();
            this.source = source;
        }

        public IntentResult(Intent intent, double confidence, String source) {
            this(intent, confidence, null, new HashMap<>(), source);
        }

        public boolean needsTool() {
            return intent.needsTool && confidence >= CONFIDENCE_LOW;
        }
    }

    private static final Map<Intent, String[]> KEYWORDS = new LinkedHashMap<>();
    private static final Map<Intent, Pattern[]> PATTERNS = new HashMap<>();
    private static final Map<Intent, String[]> EXCLUDE_KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put(Intent.WEATHER, new String[]{"天气", "气温", "温度多少", "天气预报", "下雨吗", "下雪吗", "穿什么", "带伞", "紫外线", "湿度", "风力", "空气质量", "PM2.5", "AQI"});
        KEYWORDS.put(Intent.SEARCH, new String[]{"搜索", "查找资料", "检索", "网上查", "帮我搜", "搜索一下", "百度", "谷歌", "查一下"});
        KEYWORDS.put(Intent.QUIZ, new String[]{"题目", "出题", "练习题", "考试题", "错题", "题库", "做题", "答题", "搜题", "找题", "解答题", "选择题", "填空题", "判断题"});
        KEYWORDS.put(Intent.CALCULATOR, new String[]{"计算", "算一下", "等于多少", "加起来", "乘以", "除以", "开方", "平方", "百分比", "求和"});
        KEYWORDS.put(Intent.DATABASE, new String[]{"数据库", "学习统计", "做题记录", "成绩", "正确率", "学习进度"});
        KEYWORDS.put(Intent.TRANSLATE, new String[]{"翻译", "translate", "翻译成", "译成", "用英语说", "用中文说", "英文怎么说"});
        KEYWORDS.put(Intent.OCR, new String[]{"识别", "OCR", "文字识别", "提取文字", "读取图片", "图片文字", "拍照识别"});
        KEYWORDS.put(Intent.FILE, new String[]{"文件", "读取文件", "解析文件", "CSV", "JSON", "打开文件", "导出", "导入"});
        KEYWORDS.put(Intent.WEB, new String[]{"网页", "网站", "链接", "爬取", "解析网页", "获取链接", "http", "https"});
        KEYWORDS.put(Intent.CREATIVE, new String[]{"写一篇", "创作", "写个", "写一首", "诗歌", "作文", "故事", "剧本", "邮件", "演讲稿", "文案", "小说"});
        KEYWORDS.put(Intent.LEARNING, new String[]{"学习", "作业", "课程", "知识点", "讲解", "解释一下", "什么是", "为什么", "怎么理解", "帮我学"});
        KEYWORDS.put(Intent.ANALYSIS, new String[]{"分析一下", "总结", "报告", "研究", "统计", "对比", "评估", "优缺点", "利弊"});
        KEYWORDS.put(Intent.CHAT, new String[]{"你好", "嗨", "hello", "hi", "聊天", "谢谢", "再见", "早上好", "晚上好"});

        EXCLUDE_KEYWORDS.put(Intent.WEATHER, new String[]{"冷吗", "热吗", "好冷", "好热", "太冷", "太热", "冷死", "热死", "冷不冷", "热不热"});
        EXCLUDE_KEYWORDS.put(Intent.SEARCH, new String[]{"想一下", "觉得"});
        EXCLUDE_KEYWORDS.put(Intent.CALCULATOR, new String[]{"算不算", "划算"});

        PATTERNS.put(Intent.CALCULATOR, new Pattern[]{
            Pattern.compile("[0-9]+\\s*[+\\-*/×÷]\\s*[0-9]+"),
            Pattern.compile("[0-9]+\\.?[0-9]*\\s*[+\\-*/×÷]\\s*[0-9]+\\.?[0-9]*"),
        });
        PATTERNS.put(Intent.WEB, new Pattern[]{
            Pattern.compile("https?://\\S+"),
            Pattern.compile("www\\.\\S+\\.\\w+"),
        });
        PATTERNS.put(Intent.TRANSLATE, new Pattern[]{
            Pattern.compile("(翻译|translate|译成|翻成).*(英文|中文|日文|韩文|法语|德语|英语|日语|韩语)"),
        });
        PATTERNS.put(Intent.WEATHER, new Pattern[]{
            Pattern.compile("([\u4e00-\u9fa5]{2,4})(的?天气|气温|温度|下雨|下雪|穿什么)"),
        });
    }

    private SmartIntentRecognizer(Context context) {
        this.context = context.getApplicationContext();
        buildToolMapping();
    }

    public static synchronized SmartIntentRecognizer getInstance(Context context) {
        if (instance == null) {
            instance = new SmartIntentRecognizer(context);
        }
        return instance;
    }

    public void setAgentService(AgentService agentService) {
        this.agentService = agentService;
        buildToolMapping();
    }

    private void buildToolMapping() {
        toolNameToIntent.clear();
        toolDescToIntent.clear();

        toolNameToIntent.put("weather", Intent.WEATHER);
        toolNameToIntent.put("get_weather", Intent.WEATHER);
        toolNameToIntent.put("weather_query", Intent.WEATHER);
        toolNameToIntent.put("network_search", Intent.SEARCH);
        toolNameToIntent.put("search", Intent.SEARCH);
        toolNameToIntent.put("web_search", Intent.SEARCH);
        toolNameToIntent.put("search_questions", Intent.QUIZ);
        toolNameToIntent.put("calculate", Intent.CALCULATOR);
        toolNameToIntent.put("calculator", Intent.CALCULATOR);
        toolNameToIntent.put("database", Intent.DATABASE);
        toolNameToIntent.put("database_query", Intent.DATABASE);
        toolNameToIntent.put("translation", Intent.TRANSLATE);
        toolNameToIntent.put("translate", Intent.TRANSLATE);
        toolNameToIntent.put("generate_questions", Intent.QUIZ);
        toolNameToIntent.put("file_analysis", Intent.FILE);
        toolNameToIntent.put("web_page_reader", Intent.WEB);
        toolNameToIntent.put("read_webpage", Intent.WEB);
        toolNameToIntent.put("read_url", Intent.WEB);
        toolNameToIntent.put("app_toolkit", Intent.OCR);
        toolNameToIntent.put("smart_research", Intent.SEARCH);
        toolNameToIntent.put("system_resource", Intent.SEARCH);
        toolNameToIntent.put("file_reader", Intent.FILE);
        toolNameToIntent.put("file_analyzer", Intent.FILE);
        toolNameToIntent.put("file_generator", Intent.FILE);
        toolNameToIntent.put("permission_manager", Intent.SEARCH);

        toolDescToIntent.put("天气", Intent.WEATHER);
        toolDescToIntent.put("搜索", Intent.SEARCH);
        toolDescToIntent.put("计算", Intent.CALCULATOR);
        toolDescToIntent.put("数据库", Intent.DATABASE);
        toolDescToIntent.put("翻译", Intent.TRANSLATE);
        toolDescToIntent.put("题目", Intent.QUIZ);
        toolDescToIntent.put("文件", Intent.FILE);
        toolDescToIntent.put("网页", Intent.WEB);
        toolDescToIntent.put("识别", Intent.OCR);

        if (agentService != null) {
            for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
                Intent mapped = mapToolNameToIntent(schema.name);
                if (mapped != null) {
                    toolNameToIntent.put(schema.name, mapped);
                }
                Intent descMapped = mapToolDescToIntent(schema.description);
                if (descMapped != null && !toolDescToIntent.containsValue(descMapped)) {
                    toolDescToIntent.put(schema.description, descMapped);
                }
            }
        }
    }

    private Intent mapToolNameToIntent(String toolName) {
        return mapToolNameToIntentPublic(toolName);
    }

    public Intent mapToolNameToIntentPublic(String toolName) {
        if (toolName.contains("weather") || toolName.contains("天气")) return Intent.WEATHER;
        if (toolName.contains("search") || toolName.contains("搜索") || toolName.contains("find")) return Intent.SEARCH;
        if (toolName.contains("calc") || toolName.contains("math") || toolName.contains("计算")) return Intent.CALCULATOR;
        if (toolName.contains("database") || toolName.contains("db") || toolName.contains("数据")) return Intent.DATABASE;
        if (toolName.contains("translat") || toolName.contains("翻译")) return Intent.TRANSLATE;
        if (toolName.contains("question") || toolName.contains("quiz") || toolName.contains("题")) return Intent.QUIZ;
        if (toolName.contains("file") || toolName.contains("文件")) return Intent.FILE;
        if (toolName.contains("web") || toolName.contains("url") || toolName.contains("page")) return Intent.WEB;
        if (toolName.contains("ocr") || toolName.contains("识别")) return Intent.OCR;
        return null;
    }

    private Intent mapToolDescToIntent(String desc) {
        if (desc == null) return null;
        if (desc.contains("天气")) return Intent.WEATHER;
        if (desc.contains("搜索")) return Intent.SEARCH;
        if (desc.contains("计算")) return Intent.CALCULATOR;
        if (desc.contains("数据库")) return Intent.DATABASE;
        if (desc.contains("翻译")) return Intent.TRANSLATE;
        if (desc.contains("题目") || desc.contains("练习")) return Intent.QUIZ;
        if (desc.contains("文件")) return Intent.FILE;
        if (desc.contains("网页")) return Intent.WEB;
        if (desc.contains("识别")) return Intent.OCR;
        return null;
    }

    public IntentResult recognize(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new IntentResult(Intent.CHAT, 0.0, "empty");
        }

        String trimmed = message.trim();
        if (resultCache.containsKey(trimmed)) {
            IntentResult cached = resultCache.get(trimmed);
            return new IntentResult(cached.intent, cached.confidence, cached.extractedEntity,
                new HashMap<>(cached.parameters), cached.source + "_cache");
        }

        IntentResult result = recognizeInternal(trimmed);

        if (resultCache.size() > CACHE_MAX_SIZE) {
            String oldestKey = resultCache.keySet().iterator().next();
            resultCache.remove(oldestKey);
        }
        resultCache.put(trimmed, result);

        return result;
    }

    public IntentResult recognizeWithContext(String message, String contextSummary) {
        if (message == null || message.trim().isEmpty()) {
            return new IntentResult(Intent.CHAT, 0.0, "empty");
        }

        String trimmed = message.trim();

        IntentResult baseResult = recognize(trimmed);
        if (baseResult.confidence >= CONFIDENCE_HIGH) {
            return baseResult;
        }

        if (contextSummary != null && !contextSummary.isEmpty()) {
            Intent contextIntent = extractIntentFromContext(contextSummary);
            if (contextIntent != null && contextIntent.needsTool) {
                if (baseResult.intent == contextIntent) {
                    return new IntentResult(contextIntent,
                        Math.min(1.0, baseResult.confidence + 0.15),
                        baseResult.extractedEntity, baseResult.parameters,
                        baseResult.source + "_context_boosted");
                }
            }

            IntentResult llmResult = recognizeByLLMWithContext(trimmed, contextSummary);
            if (llmResult != null && llmResult.confidence > baseResult.confidence) {
                return llmResult;
            }
        }

        if (baseResult.confidence < CONFIDENCE_LOW && contextSummary != null) {
            IntentResult llmResult = recognizeByLLMWithContext(trimmed, contextSummary);
            if (llmResult != null) {
                return llmResult;
            }
        }

        return baseResult;
    }

    private Intent extractIntentFromContext(String contextSummary) {
        if (contextSummary == null || contextSummary.isEmpty()) return null;
        if (contextSummary.contains("天气") || contextSummary.contains("WEATHER")) return Intent.WEATHER;
        if (contextSummary.contains("搜索") || contextSummary.contains("SEARCH")) return Intent.SEARCH;
        if (contextSummary.contains("计算") || contextSummary.contains("CALCULATOR")) return Intent.CALCULATOR;
        if (contextSummary.contains("数据库") || contextSummary.contains("DATABASE")) return Intent.DATABASE;
        if (contextSummary.contains("翻译") || contextSummary.contains("TRANSLATE")) return Intent.TRANSLATE;
        if (contextSummary.contains("题目") || contextSummary.contains("QUIZ")) return Intent.QUIZ;
        if (contextSummary.contains("文件") || contextSummary.contains("FILE")) return Intent.FILE;
        if (contextSummary.contains("网页") || contextSummary.contains("WEB")) return Intent.WEB;
        return null;
    }

    private IntentResult recognizeByLLMWithContext(String message, String contextSummary) {
        if (!LlamaHelper.isModelInitialized()) return null;
        try {
            String prompt = buildLLMClassificationPromptWithContext(message, contextSummary);
            String response = LlamaHelper.generate(prompt, LLM_MAX_TOKENS + 10, LLM_TEMPERATURE);
            if (response == null || response.trim().isEmpty()) return null;
            return parseLLMResponse(response.trim(), message);
        } catch (Exception e) {
            AILogger.e(TAG, "LLM context intent recognition failed: " + e.getMessage());
            return null;
        }
    }

    private String buildLLMClassificationPromptWithContext(String userMessage, String contextSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n");
        sb.append("你是意图分类器。只输出意图标签，不要输出其他内容。\n");
        sb.append("可选标签：WEATHER SEARCH QUIZ CALCULATOR DATABASE TRANSLATE OCR FILE WEB CREATIVE LEARNING ANALYSIS CHAT\n\n");
        sb.append("当前对话上下文：");
        sb.append(contextSummary != null ? contextSummary : "无");
        sb.append("\n考虑对话上下文的连贯性来判断意图。\n\n");
        sb.append("示例：\n");
        sb.append("用户：北京明天冷吗 → CHAT\n");
        sb.append("用户：北京明天天气怎么样 → WEATHER\n");
        sb.append("用户：帮我查一下量子力学 → SEARCH\n");
        sb.append("<|im_end|>\n");
        sb.append("<|im_start|>user\n");
        sb.append(userMessage);
        sb.append("\n<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    private IntentResult recognizeInternal(String message) {
        IntentResult keywordResult = recognizeByKeywords(message);
        if (keywordResult.confidence >= CONFIDENCE_HIGH) {
            AILogger.i(TAG, "Keyword match: " + keywordResult.intent.id + " conf=" + keywordResult.confidence);
            return keywordResult;
        }

        IntentResult patternResult = recognizeByPatterns(message);
        double bestConfidence = Math.max(keywordResult.confidence, patternResult.confidence);
        Intent bestIntent = bestConfidence == patternResult.confidence ? patternResult.intent : keywordResult.intent;
        String bestEntity = bestConfidence == patternResult.confidence ? patternResult.extractedEntity : keywordResult.extractedEntity;
        Map<String, Object> bestParams = bestConfidence == patternResult.confidence ? patternResult.parameters : keywordResult.parameters;

        if (bestConfidence >= CONFIDENCE_HIGH) {
            AILogger.i(TAG, "Pattern match: " + bestIntent.id + " conf=" + bestConfidence);
            return new IntentResult(bestIntent, bestConfidence, bestEntity, bestParams, "pattern");
        }

        if (bestConfidence >= CONFIDENCE_LOW && bestIntent != Intent.CHAT && bestIntent != Intent.UNKNOWN) {
            AILogger.i(TAG, "Low confidence keyword/pattern: " + bestIntent.id + " conf=" + bestConfidence + ", trying LLM");
            IntentResult llmResult = recognizeByLLM(message);
            if (llmResult != null && llmResult.confidence > bestConfidence) {
                return llmResult;
            }
            return new IntentResult(bestIntent, bestConfidence, bestEntity, bestParams, "keyword_pattern");
        }

        IntentResult llmResult = recognizeByLLM(message);
        if (llmResult != null) {
            return llmResult;
        }

        if (bestConfidence > 0) {
            return new IntentResult(bestIntent, bestConfidence, bestEntity, bestParams, "keyword_fallback");
        }

        return new IntentResult(Intent.CHAT, 0.5, "default");
    }

    private IntentResult recognizeByKeywords(String message) {
        String lower = message.toLowerCase();
        Intent bestIntent = Intent.CHAT;
        double bestConfidence = 0;
        String extractedEntity = null;
        Map<String, Object> params = new HashMap<>();

        for (Map.Entry<Intent, String[]> entry : KEYWORDS.entrySet()) {
            Intent intent = entry.getKey();
            String[] keywords = entry.getValue();

            if (EXCLUDE_KEYWORDS.containsKey(intent)) {
                boolean excluded = false;
                for (String ex : EXCLUDE_KEYWORDS.get(intent)) {
                    if (lower.contains(ex)) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) continue;
            }

            int matchCount = 0;
            String matchedKeyword = null;
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    matchCount++;
                    if (matchedKeyword == null) matchedKeyword = keyword;
                }
            }

            if (matchCount > 0) {
                double confidence = 0.4 + (0.6 * Math.min(1.0, (double) matchCount / 2.0));

                if (intent == Intent.WEATHER) {
                    String city = extractCityName(message);
                    if (city != null) {
                        extractedEntity = city;
                        params.put("city", city);
                        confidence = Math.min(1.0, confidence + 0.1);
                    }
                } else if (intent == Intent.SEARCH) {
                    String query = extractSearchQuery(message);
                    if (query != null) {
                        extractedEntity = query;
                        params.put("query", query);
                    }
                } else if (intent == Intent.QUIZ) {
                    String subject = extractSubject(message);
                    if (subject != null) {
                        extractedEntity = subject;
                        params.put("subject", subject);
                    }
                }

                if (confidence > bestConfidence) {
                    bestConfidence = confidence;
                    bestIntent = intent;
                }
            }
        }

        return new IntentResult(bestIntent, bestConfidence, extractedEntity, params, "keyword");
    }

    private IntentResult recognizeByPatterns(String message) {
        for (Map.Entry<Intent, Pattern[]> entry : PATTERNS.entrySet()) {
            Intent intent = entry.getKey();
            Pattern[] patterns = entry.getValue();

            for (Pattern pattern : patterns) {
                if (pattern.matcher(message).find()) {
                    double confidence = 0.8;
                    String entity = null;
                    Map<String, Object> params = new HashMap<>();

                    if (intent == Intent.WEATHER) {
                        String city = extractCityName(message);
                        if (city != null) {
                            entity = city;
                            params.put("city", city);
                        }
                    } else if (intent == Intent.CALCULATOR) {
                        String expr = extractMathExpression(message);
                        if (expr != null) {
                            entity = expr;
                            params.put("expression", expr);
                        }
                    } else if (intent == Intent.WEB) {
                        String url = extractUrl(message);
                        if (url != null) {
                            entity = url;
                            params.put("url", url);
                        }
                    } else if (intent == Intent.TRANSLATE) {
                        String lang = extractTargetLanguage(message);
                        if (lang != null) {
                            entity = lang;
                            params.put("target_language", lang);
                        }
                    }

                    return new IntentResult(intent, confidence, entity, params, "pattern");
                }
            }
        }
        return new IntentResult(Intent.UNKNOWN, 0, "pattern_none");
    }

    private IntentResult recognizeByLLM(String message) {
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.w(TAG, "LLM not available for intent recognition");
            return null;
        }

        try {
            String prompt = buildLLMClassificationPrompt(message);
            String response = LlamaHelper.generate(prompt, LLM_MAX_TOKENS, LLM_TEMPERATURE);

            if (response == null || response.trim().isEmpty()) {
                return null;
            }

            return parseLLMResponse(response.trim(), message);
        } catch (Exception e) {
            AILogger.e(TAG, "LLM intent recognition failed: " + e.getMessage());
            return null;
        }
    }

    private String buildLLMClassificationPrompt(String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n");
        sb.append("你是意图分类器。只输出意图标签，不要输出其他内容。\n");
        sb.append("可选标签：WEATHER SEARCH QUIZ CALCULATOR DATABASE TRANSLATE OCR FILE WEB CREATIVE LEARNING ANALYSIS CHAT\n\n");
        sb.append("示例：\n");
        sb.append("用户：北京明天冷吗 → CHAT\n");
        sb.append("用户：北京明天天气怎么样 → WEATHER\n");
        sb.append("用户：帮我查一下量子力学 → SEARCH\n");
        sb.append("用户：3.14乘以2.5等于多少 → CALCULATOR\n");
        sb.append("用户：把这段话翻译成英文 → TRANSLATE\n");
        sb.append("用户：写一首关于春天的诗 → CREATIVE\n");
        sb.append("用户：解释一下相对论 → LEARNING\n");
        sb.append("用户：分析一下人工智能的利弊 → ANALYSIS\n");
        sb.append("用户：帮我找一道物理题 → QUIZ\n");
        sb.append("用户：你好啊 → CHAT\n");
        sb.append("<|im_end|>\n");
        sb.append("<|im_start|>user\n");
        sb.append(userMessage);
        sb.append("\n<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    private IntentResult parseLLMResponse(String response, String originalMessage) {
        String cleaned = response.toUpperCase().trim();
        cleaned = cleaned.replaceAll("[^A-Z]", "");

        Map<String, Intent> labelMap = new HashMap<>();
        labelMap.put("WEATHER", Intent.WEATHER);
        labelMap.put("SEARCH", Intent.SEARCH);
        labelMap.put("QUIZ", Intent.QUIZ);
        labelMap.put("CALCULATOR", Intent.CALCULATOR);
        labelMap.put("DATABASE", Intent.DATABASE);
        labelMap.put("TRANSLATE", Intent.TRANSLATE);
        labelMap.put("OCR", Intent.OCR);
        labelMap.put("FILE", Intent.FILE);
        labelMap.put("WEB", Intent.WEB);
        labelMap.put("CREATIVE", Intent.CREATIVE);
        labelMap.put("LEARNING", Intent.LEARNING);
        labelMap.put("ANALYSIS", Intent.ANALYSIS);
        labelMap.put("CHAT", Intent.CHAT);

        for (Map.Entry<String, Intent> entry : labelMap.entrySet()) {
            if (cleaned.contains(entry.getKey())) {
                Intent intent = entry.getValue();
                String entity = null;
                Map<String, Object> params = new HashMap<>();

                if (intent == Intent.WEATHER) entity = extractCityName(originalMessage);
                else if (intent == Intent.SEARCH) entity = extractSearchQuery(originalMessage);
                else if (intent == Intent.QUIZ) entity = extractSubject(originalMessage);
                else if (intent == Intent.CALCULATOR) entity = extractMathExpression(originalMessage);
                else if (intent == Intent.TRANSLATE) entity = extractTargetLanguage(originalMessage);
                else if (intent == Intent.WEB) entity = extractUrl(originalMessage);

                if (entity != null) params.put(getEntityKey(intent), entity);

                AILogger.i(TAG, "LLM intent: " + intent.id + " raw=" + response);
                return new IntentResult(intent, 0.85, entity, params, "llm");
            }
        }

        return null;
    }

    private String getEntityKey(Intent intent) {
        switch (intent) {
            case WEATHER: return "city";
            case SEARCH: return "query";
            case QUIZ: return "subject";
            case CALCULATOR: return "expression";
            case TRANSLATE: return "target_language";
            case WEB: return "url";
            default: return "entity";
        }
    }

    public boolean shouldUseAgent(String message) {
        IntentResult result = recognize(message);
        return result.intent.needsTool && result.confidence >= CONFIDENCE_LOW;
    }

    public String getIntentType(String message) {
        return recognize(message).intent.id;
    }

    public String getRecommendedTool(Intent intent) {
        if (agentService != null) {
            for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
                Intent mapped = toolNameToIntent.get(schema.name);
                if (mapped == intent) {
                    return schema.name;
                }
            }
            for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
                Intent descMapped = mapToolDescToIntent(schema.description);
                if (descMapped == intent) {
                    return schema.name;
                }
            }
        }
        String fallback = getIntentFallbackTool(intent);
        return fallback;
    }

    private String getIntentFallbackTool(Intent intent) {
        switch (intent) {
            case WEATHER: return "weather";
            case SEARCH: return "network_search";
            case QUIZ: return "search_questions";
            case CALCULATOR: return "calculator";
            case DATABASE: return "database";
            case OCR: return "app_toolkit";
            case FILE: return "app_toolkit";
            case WEB: return "read_webpage";
            default: return null;
        }
    }

    public void clearCache() {
        resultCache.clear();
    }

    // ==================== Entity Extraction ====================

    private String extractCityName(String message) {
        String[] suffixes = {"市", "县", "区", "镇", "省"};
        for (String suffix : suffixes) {
            int idx = message.indexOf(suffix);
            if (idx > 0) {
                int start = Math.max(0, idx - 3);
                return message.substring(start, idx + 1);
            }
        }
        String[] knownCities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉",
            "南京", "西安", "长沙", "天津", "苏州", "郑州", "东莞", "青岛", "沈阳", "宁波", "昆明",
            "大连", "厦门", "福州", "无锡", "合肥", "济南", "佛山", "哈尔滨", "长春", "石家庄",
            "贵阳", "南宁", "太原", "兰州", "海口", "三亚", "拉萨", "呼和浩特", "银川", "西宁",
            "乌鲁木齐", "南昌", "温州", "珠海", "中山", "惠州", "常州", "徐州", "烟台", "洛阳"};
        for (String city : knownCities) {
            if (message.contains(city)) return city;
        }
        return null;
    }

    private String extractSearchQuery(String message) {
        String[] prefixes = {"搜索", "查找", "检索", "搜一下", "查一下", "帮我搜", "帮我查", "帮我找"};
        for (String prefix : prefixes) {
            int idx = message.indexOf(prefix);
            if (idx >= 0) {
                String query = message.substring(idx + prefix.length()).trim();
                if (!query.isEmpty()) {
                    return query.replaceAll("[的了吗？?！!。.，,]", "");
                }
            }
        }
        return null;
    }

    private String extractSubject(String message) {
        String[] subjects = {"数学", "语文", "英语", "物理", "化学", "生物", "历史", "地理", "政治",
            "计算机", "编程", "法律", "医学", "经济", "金融", "会计"};
        for (String subject : subjects) {
            if (message.contains(subject)) return subject;
        }
        return null;
    }

    private String extractMathExpression(String message) {
        StringBuilder expr = new StringBuilder();
        for (char c : message.toCharArray()) {
            if (Character.isDigit(c) || "+-*/.()×÷".indexOf(c) >= 0 || Character.isSpaceChar(c)) {
                char ec = c;
                if (c == '×') ec = '*';
                if (c == '÷') ec = '/';
                expr.append(ec);
            }
        }
        String result = expr.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private String extractTargetLanguage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("英文") || lower.contains("英语")) return "english";
        if (lower.contains("中文") || lower.contains("汉语")) return "chinese";
        if (lower.contains("日文") || lower.contains("日语")) return "japanese";
        if (lower.contains("韩文") || lower.contains("韩语")) return "korean";
        if (lower.contains("法语")) return "french";
        if (lower.contains("德语")) return "german";
        return null;
    }

    private String extractUrl(String message) {
        int httpStart = message.toLowerCase().indexOf("http");
        if (httpStart >= 0) {
            int end = message.indexOf(' ', httpStart);
            if (end < 0) end = message.length();
            return message.substring(httpStart, end);
        }
        int wwwStart = message.toLowerCase().indexOf("www.");
        if (wwwStart >= 0) {
            int end = message.indexOf(' ', wwwStart);
            if (end < 0) end = message.length();
            return message.substring(wwwStart, end);
        }
        return null;
    }
}
