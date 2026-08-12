package com.oilquiz.app.ai.chat.input;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.cache.SummaryCacheManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.util.fileparser.FileContentExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 处理附件：文件内容提取、prompt 构建、并发处理。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class AttachmentProcessor {

    private static final String TAG = "AttachmentProcessor";
    private static final int MAX_ATTACHMENT_CONTENT_LENGTH = 3000;
    private static final long SUMMARY_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000; // 摘要缓存过期时间（24小时）
    private static final int MAX_RETRY_ATTEMPTS = 2; // 最大重试次数

    public interface Callback {
        void onContentExtracted(String attachmentId, String content);
        void onExtractionFailed(String attachmentId, String error);
        void onSummaryGenerated(String attachmentId, String summary); // AI摘要生成回调
    }

    private final Activity activity;
    private final FileContentExtractor fileContentExtractor;
    private final ExecutorService executorService;
    private final Map<String, String> extractedContents = new ConcurrentHashMap<>();
    
    // 摘要缓存管理器（持久化）
    private final SummaryCacheManager summaryCacheManager;
    
    // 摘要缓存: key=attachmentId, value=CachedSummary
    private final java.util.concurrent.ConcurrentHashMap<String, CachedSummary> summaryCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 重试计数: key=attachmentId, value=retryCount
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> retryCounts = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 缓存的摘要数据
     */
    private static class CachedSummary {
        String summary;
        long timestamp;
        
        CachedSummary(String summary, long timestamp) {
            this.summary = summary;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > SUMMARY_CACHE_EXPIRY_MS;
        }
    }

    public AttachmentProcessor(Activity activity) {
        this.activity = activity;
        this.fileContentExtractor = new FileContentExtractor(activity);
        this.executorService = Executors.newFixedThreadPool(3);
        this.summaryCacheManager = SummaryCacheManager.getInstance(activity); // 初始化持久化缓存管理器
    }

    /**
     * 并行提取多个附件的内容
     */
    public void extractContents(List<ChatMessage.Attachment> attachments, Callback callback) {
        if (attachments == null || attachments.isEmpty()) return;

        List<Future<?>> futures = new ArrayList<>();
        for (ChatMessage.Attachment attachment : attachments) {
            futures.add(executorService.submit(() -> {
                try {
                    String content = extractContent(attachment);
                    extractedContents.put(attachment.id, content);
                    if (callback != null) {
                        callback.onContentExtracted(attachment.id, content);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to extract content for " + attachment.name, e);
                    String error = "内容提取失败: " + e.getMessage();
                    extractedContents.put(attachment.id, error);
                    if (callback != null) {
                        callback.onExtractionFailed(attachment.id, error);
                    }
                }
            }));
        }

        // Wait for all to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                Log.e(TAG, "Future wait error", e);
            }
        }
    }

    /**
     * 构建带附件内容增强的 prompt（本地模型）
     */
    public String buildAugmentedMessage(String userMessage, List<ChatMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return userMessage;

        StringBuilder augmented = new StringBuilder();
        augmented.append(userMessage).append("\n\n--- 附件内容 ---\n");

        for (ChatMessage.Attachment attachment : attachments) {
            String content = extractedContents.get(attachment.id);
            if (content != null && !content.isEmpty()) {
                augmented.append("\n📄 文件: ").append(attachment.name).append("\n");
                if (content.length() > MAX_ATTACHMENT_CONTENT_LENGTH) {
                    augmented.append(content, 0, MAX_ATTACHMENT_CONTENT_LENGTH).append("\n...(内容已截断)");
                } else {
                    augmented.append(content).append("\n");
                }
            }
        }

        return augmented.toString();
    }

    /**
     * 构建带附件路径的 agent 增强消息
     */
    public String buildAgentAugmentedMessage(String userMessage, List<String> savedPaths) {
        if (savedPaths == null || savedPaths.isEmpty()) return userMessage;

        StringBuilder augmented = new StringBuilder();
        augmented.append(userMessage).append("\n\n--- 附件路径 ---\n");
        for (String path : savedPaths) {
            augmented.append("- ").append(path).append("\n");
        }
        return augmented.toString();
    }

    /**
     * 保存附件到本地存储
     */
    public List<String> saveAttachmentsToLocal(List<ChatMessage.Attachment> attachments) {
        List<String> savedPaths = new ArrayList<>();
        if (attachments == null) return savedPaths;

        for (ChatMessage.Attachment attachment : attachments) {
            try {
                String savedPath = saveToLocal(attachment);
                if (savedPath != null) {
                    savedPaths.add(savedPath);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to save attachment: " + attachment.name, e);
            }
        }
        return savedPaths;
    }

    public String getExtractedContent(String attachmentId) {
        return extractedContents.get(attachmentId);
    }

    public void clearExtractedContents() {
        extractedContents.clear();
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    private String extractContent(ChatMessage.Attachment attachment) throws Exception {
        if (attachment.url == null) return "";

        Uri uri = Uri.parse(attachment.url);
        try {
            String result = fileContentExtractor.extractContent(uri).get();
            return result != null ? result : "";
        } catch (Exception e) {
            Log.e(TAG, "Extract failed", e);
            return "";
        }
    }

    private String saveToLocal(ChatMessage.Attachment attachment) throws Exception {
        if (attachment.url == null) return null;

        Uri uri = Uri.parse(attachment.url);
        File cacheDir = new File(activity.getCacheDir(), "attachments");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        String fileName = attachment.name != null ? attachment.name : UUID.randomUUID().toString();
        File localFile = new File(cacheDir, fileName);

        try (InputStream inputStream = activity.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(localFile)) {
            if (inputStream == null) return null;
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        return localFile.getAbsolutePath();
    }

    /**
     * 为附件生成AI智能摘要（使用真实AI服务）
     * @param attachment 附件对象
     */
    public void generateAISummary(ChatMessage.Attachment attachment) {
        if (attachment == null || attachment.extractedContent == null || attachment.extractedContent.isEmpty()) {
            return;
        }
        
        // 检查持久化缓存
        String cachedSummary = summaryCacheManager.getSummary(attachment.id);
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
                    cacheSummary(attachment.id, tempSummary);
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
                    cacheSummary(attachment.id, tempSummary);
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
                
                // 等待结果（最多15秒超时）
                String summary = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                
                // 清理摘要格式
                String cleanSummary = cleanSummaryFormat(summary);
                
                // 缓存摘要
                cacheSummary(attachment.id, cleanSummary);
                
                Log.i(TAG, "AI summary generated successfully for: " + attachment.name);
                
                // 回调更新UI
                if (callback != null) {
                    callback.onSummaryGenerated(attachment.id, cleanSummary);
                }
                
            } catch (java.util.concurrent.TimeoutException e) {
                Log.e(TAG, "AI summary generation timeout for " + attachment.name, e);
                handleRetryOrFallback(attachment, "⏱️ 摘要生成超时，请稍后重试");
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
        int retryCount = retryCounts.getOrDefault(attachment.id, 0);
        
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            // 重试
            retryCounts.put(attachment.id, retryCount + 1);
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
            cacheSummary(attachment.id, fallbackSummary);
            if (callback != null) {
                callback.onSummaryGenerated(attachment.id, fallbackSummary);
            }
            // 重置重试计数
            retryCounts.remove(attachment.id);
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
                // OCR文本：需要较强的理解能力
                return selectModelByCapability("vision", defaultConfig);
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
        
        // 检测图片OCR
        if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")) {
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
        if (content.length() > 3000) {
            content = content.substring(0, 3000);
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
