package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TranslationTool implements AITool {
    private static final String TAG = "TranslationTool";
    private final Context context;

    public TranslationTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() { return "translation"; }

    @Override
    public String getDescription() { return "翻译工具，支持多语言文本翻译"; }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.getOrDefault("action", "translate");
            switch (action) {
                case "translate":
                    return translate(parameters);
                case "detect_language":
                    return detectLanguage(parameters);
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
        String targetLang = (String) parameters.get("target_language");
        if (text == null || text.isEmpty()) return new AIToolResult("缺少参数: text", parameters);
        if (targetLang == null || targetLang.isEmpty()) targetLang = "zh";

        String sourceLang = detectLanguageInternal(text);

        Map<String, Object> result = new HashMap<>();
        result.put("original_text", text);
        result.put("source_language", sourceLang);
        result.put("target_language", targetLang);

        if (sourceLang.equals(targetLang)) {
            result.put("translated_text", text);
            result.put("note", "源语言和目标语言相同");
        } else {
            result.put("translated_text", "[翻译结果将通过AI模型生成]");
            result.put("needs_ai", true);
        }

        return new AIToolResult(result, parameters);
    }

    private AIToolResult detectLanguage(Map<String, Object> parameters) {
        String text = (String) parameters.get("text");
        if (text == null || text.isEmpty()) return new AIToolResult("缺少参数: text", parameters);

        String detected = detectLanguageInternal(text);
        Map<String, Object> result = new HashMap<>();
        result.put("detected_language", detected);
        result.put("confidence", "high");
        return new AIToolResult(result, parameters);
    }

    private String detectLanguageInternal(String text) {
        boolean hasChinese = false;
        boolean hasLatin = false;
        boolean hasJapanese = false;
        boolean hasKorean = false;

        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') hasChinese = true;
            else if (c >= '\u3040' && c <= '\u309f') hasJapanese = true;
            else if ((c >= '\uac00' && c <= '\ud7af')) hasKorean = true;
            else if (Character.isLetter(c)) hasLatin = true;
        }

        if (hasKorean) return "ko";
        if (hasJapanese) return "ja";
        if (hasChinese) return "zh";
        if (hasLatin) return "en";
        return "unknown";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: translate(翻译), detect_language(检测语言)");
        descriptions.put("text", "要翻译的文本");
        descriptions.put("source_language", "源语言(可选，自动检测)");
        descriptions.put("target_language", "目标语言: zh, en, ja, ko");
        return descriptions;
    }
}
