package com.oilquiz.app.ai.chat.input;

import android.app.Activity;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.cache.SummaryCacheManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 附件 AI 摘要生成（真实在线模型 + 重试 + 持久化缓存）。
 *
 * 从 AIChatActivity 中提取的独立模块。
 *
 * 说明：本类原先还带着一套"内容提取 + 增强消息 + 落盘"的实现，与 AttachmentPreParser /
 * AttachmentPromptBuilder / VisionInferenceHelper 的现役链路并行且阈值不同（3000 字 vs 12000 字），
 * 已确认无任何调用方，删除以免后续改动时两处不一致。附件解析的唯一入口是
 * {@link AttachmentPreParser#parseAllAsync}。
 */
public class AttachmentProcessor {

    private static final String TAG = "AttachmentProcessor";
    private static final long SUMMARY_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000; // 摘要缓存过期时间（24小时）
    private static final int MAX_RETRY_ATTEMPTS = 2; // 最大重试次数
    /** 摘要提示词里最多带入的原文字符数 */
    private static final int SUMMARY_INPUT_CHARS = 3000;

    public interface Callback {
        void onContentExtracted(String attachmentId, String content);
        void onExtractionFailed(String attachmentId, String error);
        void onSummaryGenerated(String attachmentId, String summary); // AI摘要生成回调
    }

    private final Activity activity;
    private final ExecutorService executorService;

    // 摘要缓存管理器（持久化）
    private final SummaryCacheManager summaryCacheManager;

    // 重试计数: key=摘要缓存键, value=retryCount
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> retryCounts = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 摘要缓存键：按文件内容指纹（路径:大小:文件名），而不是 attachment.id。
     * attachment.id 是每次构造附件时随机生成的 UUID，用它做键等于持久化缓存永远不命中，
     * 同一个文件重复提问会反复走在线模型重新生成摘要。
     */
    static String summaryCacheKey(ChatMessage.Attachment attachment) {
        if (attachment == null) return "null";
        String source = attachment.localFilePath != null ? attachment.localFilePath : attachment.url;
        String raw = (source != null ? source : "") + ":" + attachment.size
                + ":" + (attachment.name != null ? attachment.name : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public AttachmentProcessor(Activity activity) {
        this.activity = activity;
        this.executorService = Executors.newFixedThreadPool(3);
        this.summaryCacheManager = SummaryCacheManager.getInstance(activity); // 初始化持久化缓存管理器
    }


    /**
     * 为附件生成AI智能摘要（使用真实AI服务）
     * @param attachment 附件对象
     */
    public void generateAISummary(ChatMessage.Attachment attachment) {
        if (attachment == null || attachment.extractedContent == null || attachment.extractedContent.isEmpty()) {
            return;
        }
        
        // 检查持久化缓存（按内容指纹，同一个文件重复提问不再重复走模型）
        String cachedSummary = summaryCacheManager.getSummary(summaryCacheKey(attachment));
        if (cachedSummary != null) {
            Log.i(TAG, "Using persistent cache for: " + attachment.name);
            if (callback != null) {
                callback.onSummaryGenerated(attachment.id, cachedSummary);
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                // 检查在线模型是否可用
                OnlineInferenceService onlineService = OnlineInferenceService.getInstance(activity);
                if (!onlineService.isOnlineModelAvailable()) {
                    Log.w(TAG, "No online model available, using simple summary");
                    // 降级：使用简单摘要
                    String tempSummary = generateSimpleSummary(attachment.extractedContent);
                    cacheSummary(summaryCacheKey(attachment), tempSummary);
                    if (callback != null) {
                        callback.onSummaryGenerated(attachment.id, tempSummary);
                    }
                    return;
                }
                
                // 获取当前激活的在线模型配置
                OnlineModelManager.OnlineModelConfig config = onlineService.getActiveConfig();
                if (config == null) {
                    Log.w(TAG, "No active online model config");
                    String tempSummary = generateSimpleSummary(attachment.extractedContent);
                    cacheSummary(summaryCacheKey(attachment), tempSummary);
                    if (callback != null) {
                        callback.onSummaryGenerated(attachment.id, tempSummary);
                    }
                    return;
                }
                
                // 智能选择模型（根据内容类型）
                OnlineModelManager.OnlineModelConfig selectedConfig = selectOptimalModel(attachment, config);
                
                // 构建摘要生成提示词
                String prompt = buildSummaryPrompt(attachment);
                
                Log.i(TAG, "Generating AI summary for: " + attachment.name + 
                      " using model: " + selectedConfig.modelName + 
                      " (type: " + detectContentType(attachment) + ")");
                
                // 异步调用AI服务生成摘要
                CompletableFuture<String> future = onlineService.generateAsync(
                    prompt, 
                    selectedConfig, 
                    null,  // 无历史对话
                    300    // 限制摘要长度为300 tokens
                );
                
                try {
                // 等待结果（最多15秒超时）
                String summary = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                
                // 清理摘要格式
                String cleanSummary = cleanSummaryFormat(summary);
                
                // 缓存摘要
                cacheSummary(summaryCacheKey(attachment), cleanSummary);
                
                Log.i(TAG, "AI summary generated successfully for: " + attachment.name);
                
                // 回调更新UI
                if (callback != null) {
                    callback.onSummaryGenerated(attachment.id, cleanSummary);
                }
                } catch (java.util.concurrent.TimeoutException e) {
                    Log.e(TAG, "AI summary generation timeout for " + attachment.name, e);
                    future.cancel(true); // 不再等待底层结果，避免线程滞留
                    handleRetryOrFallback(attachment, "⏱️ 摘要生成超时，请稍后重试");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate AI summary for " + attachment.name, e);
                handleRetryOrFallback(attachment, "❌ AI摘要生成失败: " + e.getMessage());
            }
        });
    }

    /**
     * 缓存摘要（使用持久化缓存管理器）
     */
    private void cacheSummary(String attachmentId, String summary) {
        if (attachmentId != null && summary != null) {
            // 保存到持久化缓存（同时保存内存和磁盘）
            summaryCacheManager.saveSummary(attachmentId, summary, "AI-Service");
        }
    }

    /**
     * 处理重试或降级
     */
    private void handleRetryOrFallback(ChatMessage.Attachment attachment, String errorMessage) {
        String cacheKey = summaryCacheKey(attachment);
        int retryCount = retryCounts.getOrDefault(cacheKey, 0);
        
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            // 重试
            retryCounts.put(cacheKey, retryCount + 1);
            Log.w(TAG, "Retrying summary generation for " + attachment.name + 
                  " (attempt " + (retryCount + 1) + "/" + MAX_RETRY_ATTEMPTS + ")");
            
            // 延迟2秒后重试
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            
            // 重新生成
            generateAISummary(attachment);
        } else {
            // 达到最大重试次数，使用降级方案
            Log.e(TAG, "Max retries reached for " + attachment.name + ", using fallback");
            String fallbackSummary = errorMessage + "\n\n" + generateSimpleSummary(attachment.extractedContent);
            cacheSummary(cacheKey, fallbackSummary);
            if (callback != null) {
                callback.onSummaryGenerated(attachment.id, fallbackSummary);
            }
            // 重置重试计数
            retryCounts.remove(cacheKey);
        }
    }

    /**
     * 智能选择最优模型（根据内容类型和模型能力）
     */
    private OnlineModelManager.OnlineModelConfig selectOptimalModel(
            ChatMessage.Attachment attachment, 
            OnlineModelManager.OnlineModelConfig defaultConfig) {
        
        String contentType = detectContentType(attachment);
        
        // 根据内容类型选择模型策略
        switch (contentType) {
            case "image_ocr":
                // 图片 OCR 后进入这里的已经是**纯文本**，摘要任务不再需要视觉能力；
                // 之前按 "vision" 选模型会把最贵的视觉模型用在文本摘要上，且没有视觉模型的
                // 配置集会直接落回默认档 —— 语义与成本都不对。
                return defaultConfig;
            case "document_long":
                // 长文档：需要大上下文窗口
                return selectModelByCapability("long_context", defaultConfig);
            case "audio_transcript":
                // 音频转录：需要语音理解
                return selectModelByCapability("audio", defaultConfig);
            case "code":
                // 代码文件：需要代码理解能力
                return selectModelByCapability("coding", defaultConfig);
            default:
                // 普通文本：使用默认模型
                return defaultConfig;
        }
    }

    /**
     * 检测附件内容类型
     */
    private String detectContentType(ChatMessage.Attachment attachment) {
        if (attachment == null || attachment.extractedContent == null) {
            return "unknown";
        }
        
        String name = attachment.name != null ? attachment.name.toLowerCase() : "";
        String content = attachment.extractedContent;
        
        // 检测图片OCR（后缀集合与 AttachmentPreParser 的图片判定保持一致）
        if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")
                || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".heic")) {
            return "image_ocr";
        }
        
        // 检测音频转录
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a")) {
            return "audio_transcript";
        }
        
        // 检测代码文件
        if (name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js") || 
            name.endsWith(".cpp") || name.endsWith(".c")) {
            return "code";
        }
        
        // 检测长文档（超过1000字）
        if (content.length() > 1000) {
            return "document_long";
        }
        
        return "text_short";
    }

    /**
     * 根据能力需求选择最优模型
     */
    private OnlineModelManager.OnlineModelConfig selectModelByCapability(
            String capability, 
            OnlineModelManager.OnlineModelConfig defaultConfig) {
        
        try {
            OnlineModelManager modelManager = OnlineModelManager.getInstance(activity);
            java.util.List<OnlineModelManager.OnlineModelConfig> allModels = modelManager.getModelList();
            
            if (allModels == null || allModels.isEmpty()) {
                Log.w(TAG, "No models available, using default");
                return defaultConfig;
            }
            
            // 过滤启用的模型且具备所需能力
            java.util.List<OnlineModelManager.OnlineModelConfig> enabledModels = new java.util.ArrayList<>();
            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (config.enabled && config.hasCapability(capability)) {
                    enabledModels.add(config);
                }
            }
            
            if (enabledModels.isEmpty()) {
                Log.w(TAG, "No model with capability '" + capability + "', using default");
                return defaultConfig;
            }
            
            // 按性价比排序（优先选择成本低且能力匹配的模型）
            enabledModels.sort((a, b) -> {
                double costA = a.costPerMillionTokens > 0 ? a.costPerMillionTokens : Double.MAX_VALUE;
                double costB = b.costPerMillionTokens > 0 ? b.costPerMillionTokens : Double.MAX_VALUE;
                return Double.compare(costA, costB);
            });
            
            OnlineModelManager.OnlineModelConfig selected = enabledModels.get(0);
            Log.i(TAG, "Selected model for '" + capability + "': " + selected.name + 
                  " (cost: $" + selected.costPerMillionTokens + "/M tokens)");
            
            return selected;
        } catch (Exception e) {
            Log.e(TAG, "Failed to select model by capability: " + e.getMessage(), e);
            return defaultConfig;
        }
    }
    
    private Callback callback; // 保存回调引用
    
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 构建摘要生成提示词
     */
    private String buildSummaryPrompt(ChatMessage.Attachment attachment) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下文件内容生成一个简洁的智能摘要（100-200字）：\n\n");
        prompt.append("文件名：").append(attachment.name).append("\n");
        prompt.append("文件类型：").append(attachment.type).append("\n\n");
        prompt.append("内容：\n");
        
        // 限制输入长度
        String content = attachment.extractedContent;
        if (content.length() > SUMMARY_INPUT_CHARS) {
            content = content.substring(0, SUMMARY_INPUT_CHARS);
        }
        prompt.append(content);
        
        return prompt.toString();
    }

    /**
     * 简单的摘要生成（临时方案）
     */
    private String generateSimpleSummary(String content) {
        if (content == null || content.isEmpty()) {
            return "无内容";
        }
        
        // 提取前100个字符作为简单摘要
        int length = Math.min(100, content.length());
        String summary = content.substring(0, length);
        
        // 如果内容被截断，添加省略号
        if (content.length() > 100) {
            summary += "...";
        }
        
        return "📌 内容预览：\n" + summary;
    }

    /**
     * 清理AI生成的摘要格式
     */
    private String cleanSummaryFormat(String rawSummary) {
        if (rawSummary == null || rawSummary.isEmpty()) {
            return "无摘要";
        }
        
        // 去除首尾空白
        String cleaned = rawSummary.trim();
        
        // 去除可能的Markdown标记（使用简单替换避免正则转义问题）
        cleaned = cleaned.replaceAll("^#+\\s*", ""); // 去除标题标记
        cleaned = cleaned.replace("**", ""); // 去除粗体标记
        cleaned = cleaned.replace("*", ""); // 去除斜体标记
        
        // 限制长度
        if (cleaned.length() > 500) {
            cleaned = cleaned.substring(0, 500) + "...";
        }
        
        // 添加前缀标识
        return "🤖 AI智能摘要:\n" + cleaned;
    }
}
