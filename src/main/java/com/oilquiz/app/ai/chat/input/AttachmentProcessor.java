package com.oilquiz.app.ai.chat.input;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.util.fileparser.FileContentExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public interface Callback {
        void onContentExtracted(String attachmentId, String content);
        void onExtractionFailed(String attachmentId, String error);
    }

    private final Activity activity;
    private final FileContentExtractor fileContentExtractor;
    private final ExecutorService executorService;
    private final Map<String, String> extractedContents = new ConcurrentHashMap<>();

    public AttachmentProcessor(Activity activity) {
        this.activity = activity;
        this.fileContentExtractor = new FileContentExtractor(activity);
        this.executorService = Executors.newFixedThreadPool(3);
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
}
