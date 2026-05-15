package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.ai.feature.Translator;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.Map;

public class TranslationTool implements AITool {
    private static final String TAG = "TranslationTool";
    private final Context context;
    private final Translator translator;
    
    private static final Map<String, String> LANGUAGE_CODE_MAP = new HashMap<>();
    static {
        LANGUAGE_CODE_MAP.put("zh", "中文");
        LANGUAGE_CODE_MAP.put("中文", "中文");
        LANGUAGE_CODE_MAP.put("chinese", "中文");
        LANGUAGE_CODE_MAP.put("en", "英文");
        LANGUAGE_CODE_MAP.put("英文", "英文");
        LANGUAGE_CODE_MAP.put("english", "英文");
        LANGUAGE_CODE_MAP.put("ja", "日文");
        LANGUAGE_CODE_MAP.put("日文", "日文");
        LANGUAGE_CODE_MAP.put("japanese", "日文");
        LANGUAGE_CODE_MAP.put("ko", "韩文");
        LANGUAGE_CODE_MAP.put("韩文", "韩文");
        LANGUAGE_CODE_MAP.put("korean", "韩文");
        LANGUAGE_CODE_MAP.put("fr", "法文");
        LANGUAGE_CODE_MAP.put("法文", "法文");
        LANGUAGE_CODE_MAP.put("french", "法文");
        LANGUAGE_CODE_MAP.put("de", "德文");
        LANGUAGE_CODE_MAP.put("德文", "德文");
        LANGUAGE_CODE_MAP.put("german", "德文");
        LANGUAGE_CODE_MAP.put("es", "西班牙文");
        LANGUAGE_CODE_MAP.put("西班牙文", "西班牙文");
        LANGUAGE_CODE_MAP.put("spanish", "西班牙文");
        LANGUAGE_CODE_MAP.put("ru", "俄文");
        LANGUAGE_CODE_MAP.put("俄文", "俄文");
        LANGUAGE_CODE_MAP.put("russian", "俄文");
    }

    public TranslationTool(Context context) {
        this.context = context;
        this.translator = new Translator(context);
    }

    @Override
    public String getName() { return "translation"; }

    @Override
    public String getDescription() { return "翻译工具，支持多语言文本翻译，由AI模型驱动"; }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.getOrDefault("action", "translate");
            switch (action) {
                case "translate":
                    return translate(parameters);
                case "detect_language":
                    return detectLanguage(parameters);
                case "translate_question":
                    return translateQuestion(parameters);
                default:
                    return new AIToolResult("Unknown action: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error: " + e.getMessage(), e);
            return new AIToolResult("Error: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult translate(Map<String, Object> parameters) {
        String text = (String) parameters.get("text");
        String targetLangCode = (String) parameters.get("target_language");
        if (text == null || text.isEmpty()) return new AIToolResult("缺少参数: text", parameters);
        if (targetLangCode == null || targetLangCode.isEmpty()) targetLangCode = "中文";

        String targetLanguage = getLanguageName(targetLangCode);

        Map<String, Object> result = new HashMap<>();
        result.put("original_text", text);
        result.put("target_language", targetLanguage);

        String translatedText = translator.translate(text, targetLanguage);
        
        if (translatedText != null && !translatedText.startsWith("Error:")) {
            result.put("translated_text", translatedText);
            result.put("status", "success");
            result.put("engine", "AI Model");
        } else {
            result.put("status", "error");
            result.put("error", translatedText);
            result.put("message", "AI翻译服务初始化失败，请检查AI模型配置");
        }

        return new AIToolResult(result, parameters);
    }

    private AIToolResult detectLanguage(Map<String, Object> parameters) {
        String text = (String) parameters.get("text");
        if (text == null || text.isEmpty()) return new AIToolResult("缺少参数: text", parameters);

        String detected = translator.detectLanguage(text);
        Map<String, Object> result = new HashMap<>();
        result.put("detected_language", detected);
        result.put("engine", "AI Model");
        result.put("status", detected.startsWith("Error:") ? "error" : "success");
        return new AIToolResult(result, parameters);
    }

    private AIToolResult translateQuestion(Map<String, Object> parameters) {
        String question = (String) parameters.get("question");
        String options = (String) parameters.get("options");
        String answer = (String) parameters.get("answer");
        String explanation = (String) parameters.get("explanation");
        String targetLangCode = (String) parameters.get("target_language");
        
        if (question == null || question.isEmpty()) return new AIToolResult("缺少参数: question", parameters);
        if (targetLangCode == null || targetLangCode.isEmpty()) targetLangCode = "中文";

        String targetLanguage = getLanguageName(targetLangCode);

        String translated = translator.translateQuestion(
            question, 
            options != null ? options : "", 
            answer != null ? answer : "", 
            explanation != null ? explanation : "", 
            targetLanguage
        );

        Map<String, Object> result = new HashMap<>();
        result.put("original_question", question);
        result.put("target_language", targetLanguage);
        
        if (translated != null && !translated.startsWith("Error:")) {
            result.put("translated_question", translated);
            result.put("status", "success");
            result.put("engine", "AI Model");
        } else {
            result.put("status", "error");
            result.put("error", translated);
            result.put("message", "AI翻译服务初始化失败，请检查AI模型配置");
        }

        return new AIToolResult(result, parameters);
    }

    private String getLanguageName(String code) {
        String name = LANGUAGE_CODE_MAP.get(code.toLowerCase());
        return name != null ? name : code;
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: translate(翻译文本), detect_language(检测语言), translate_question(翻译题目)");
        descriptions.put("text", "要翻译的文本");
        descriptions.put("question", "要翻译的题目文本（用于 translate_question）");
        descriptions.put("options", "题目选项（用于 translate_question，可选）");
        descriptions.put("answer", "题目答案（用于 translate_question，可选）");
        descriptions.put("explanation", "题目解析（用于 translate_question，可选）");
        descriptions.put("target_language", "目标语言: zh/中文, en/英文, ja/日文, ko/韩文, fr/法文, de/德文, es/西班牙文, ru/俄文");
        return descriptions;
    }
}
