package com.oilquiz.app.ai.feature;

import android.content.Context;

/**
 * 翻译器 - AI驱动的多语言翻译
 * 
 * 功能：
 * - 文本翻译（支持多语言）
 * - 题目翻译（题目、选项、答案、解析）
 * - 仅返回翻译结果，不添加额外解释
 * 
 * 支持语言：
 * - 中文 (Chinese)
 * - 英文 (English)
 * - 日文 (Japanese)
 * - 韩文 (Korean)
 * 等主流语言
 * 
 * 使用方式：
 * Translator translator = new Translator(context);
 * String translated = translator.translate("Hello", "中文");
 * 
 * @author AI Team
 * @since 2024
 */
public class Translator {
    /**
     * 统一出词入口（2026-10-25）：以前每个方法开头都 {@code initializeSafe()} 直接把本地 GGUF
     * 拉进内存；现在"有激活在线模型就先走在线，不为跑功能偷偷加载本地权重"。
     * 见 {@link FeatureTextCompleter} 的口径说明。
     */
    private final FeatureTextCompleter completer;

    /** 调用方按 {@code startsWith("Error:")} 判失败（见 ToolResultInterpreter 等），前缀必须保留。 */
    private static final String AI_UNAVAILABLE = FeatureTextCompleter.AI_UNAVAILABLE;
    
    public Translator(Context context) {
        this.completer = new FeatureTextCompleter(context);
    }
    
    public String translate(String text, String targetLanguage) {
        String prompt = String.format(
            "Translate the following text to %s.\n" +
            "Text: %s\n" +
            "Please provide only the translation without any additional explanation.",
            targetLanguage,
            text
        );
        
        String out = completer.complete(prompt, 600);
        return out != null ? out : AI_UNAVAILABLE;
    }
    
    public String translateQuestion(String questionText, String options, String answer, String explanation, String targetLanguage) {
        String prompt = String.format(
            "Translate the following question to %s.\n" +
            "Question: %s\n" +
            "Options: %s\n" +
            "Answer: %s\n" +
            "Explanation: %s\n" +
            "Please preserve the original format and structure.\n" +
            "Provide only the translation without any additional explanation.",
            targetLanguage,
            questionText,
            options,
            answer,
            explanation
        );
        
        String out = completer.complete(prompt, 800);
        return out != null ? out : AI_UNAVAILABLE;
    }
    
    public String detectLanguage(String text) {
        String prompt = String.format(
            "Detect the language of the following text.\n" +
            "Text: %s\n" +
            "Please provide only the language name without any additional explanation.",
            text
        );
        
        String out = completer.complete(prompt, 200);
        return out != null ? out : AI_UNAVAILABLE;
    }
}