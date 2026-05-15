package com.oilquiz.app.ai.feature;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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
    private static final String TAG = "Translator";
    private final AIService aiService;
    
    public Translator(Context context) {
        this.aiService = AIService.getInstance(context);
    }
    
    public String translate(String text, String targetLanguage) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
        String prompt = String.format(
            "Translate the following text to %s.\n" +
            "Text: %s\n" +
            "Please provide only the translation without any additional explanation.",
            targetLanguage,
            text
        );
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 600);
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Error translating text: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String translateQuestion(String questionText, String options, String answer, String explanation, String targetLanguage) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
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
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 800);
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Error translating question: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String detectLanguage(String text) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
        String prompt = String.format(
            "Detect the language of the following text.\n" +
            "Text: %s\n" +
            "Please provide only the language name without any additional explanation.",
            text
        );
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 200);
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Error detecting language: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
}