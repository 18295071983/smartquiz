package com.oilquiz.app.ai.chat.vision;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.attachment.AttachmentPromptBuilder;
import com.oilquiz.app.ai.model.OnlineModelManager;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 附件视觉编排器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity 附件+视觉链路抽取的可复用侧：
 * 附件过滤（数量/大小）、最近图片附件定位、视觉历史构建、本地/在线视觉可用性决策、
 * 在线视觉模型判定、图片 base64 读取。完整执行流程（落盘→生成→渲染）由宿主
 * 组合 {@link VisionInferenceHelper} 与本类决策完成。
 */
public class AttachmentVisionPipeline {

    public static final int MAX_ATTACHMENTS = 10;
    public static final long MAX_ATTACHMENT_FILE_SIZE = 5L * 1024 * 1024;
    /** 在线视觉图片大小上限（防请求体过大） */
    public static final long ONLINE_VISION_MAX_FILE_SIZE = 4L * 1024 * 1024;
    /** 在线视觉失败冷却时间 */
    public static final long VISION_FAIL_COOLDOWN_MS = 30_000L;

    private final Context context;
    private final AttachmentPromptBuilder promptBuilder;

    /** 环境事实提供者（宿主注入） */
    public interface Env {
        boolean isOnlineModel();
        OnlineModelManager.OnlineModelConfig getActiveModel();
        String getCurrentModelName();
        boolean isLocalVisionLoaded();   // LlamaHelper.isMultimodalLoaded && isModelInitialized
        int getModelContextSize();
    }

    public AttachmentVisionPipeline(Context context, Env env) {
        this.context = context.getApplicationContext();
        this.env = env;
        this.promptBuilder = new AttachmentPromptBuilder(this.context, env::getModelContextSize);
    }

    private final Env env;

    public AttachmentPromptBuilder getPromptBuilder() { return promptBuilder; }

    // ==================== 附件过滤 ====================

    /** 过滤有效附件（数量/大小限制），返回过滤后列表与跳过原因列表 */
    public static class FilterResult {
        public final List<ChatMessage.Attachment> filtered;
        public final List<String> skipped;
        public FilterResult(List<ChatMessage.Attachment> filtered, List<String> skipped) {
            this.filtered = filtered;
            this.skipped = skipped;
        }
    }

    public FilterResult filterAttachments(List<ChatMessage.Attachment> attachments) {
        List<ChatMessage.Attachment> filtered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (attachments == null) return new FilterResult(filtered, skipped);
        for (ChatMessage.Attachment att : attachments) {
            if (filtered.size() >= MAX_ATTACHMENTS) {
                skipped.add(att.name + "(超出数量限制)");
            } else if (att.size > MAX_ATTACHMENT_FILE_SIZE) {
                skipped.add(att.name + "(" + com.oilquiz.app.ai.chat.file.FileUriUtils.formatFileSize(att.size) + ")");
            } else {
                filtered.add(att);
            }
        }
        return new FilterResult(filtered, skipped);
    }

    /** 是否全部为图片附件 */
    public static boolean allImages(List<ChatMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return false;
        for (ChatMessage.Attachment att : attachments) {
            if (att == null || !"image".equals(att.type)) return false;
        }
        return true;
    }

    // ==================== 视觉追问定位 ====================

    /** 找最近一条带图片的用户消息（不包含末尾当前消息）；返回索引或 -1 */
    public int findLastImageAttachment(List<ChatMessage> history) {
        if (history == null) return -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.type == ChatMessage.MessageType.USER && m.attachments != null) {
                for (ChatMessage.Attachment att : m.attachments) {
                    if (att != null && "image".equals(att.type)) return i;
                }
            }
        }
        return -1;
    }

    /** 该消息是否本身是"末尾带图消息"（不应走追问） */
    public boolean isCurrentImageMessage(int lastImageIndex, List<ChatMessage> history) {
        return lastImageIndex >= 0 && lastImageIndex == history.size() - 1;
    }

    /** 构建图片消息之前的视觉历史（USER/AI 文本，最多 max 条） */
    public List<ChatMessage> buildVisionHistoryBefore(List<ChatMessage> history, int lastImageIndex, int max) {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < lastImageIndex; i++) {
            ChatMessage m = history.get(i);
            if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                    && m.content != null && !m.content.isEmpty()) {
                hist.add(m);
            }
        }
        if (hist.size() > max) {
            return new ArrayList<>(hist.subList(hist.size() - max, hist.size()));
        }
        return hist;
    }

    // ==================== 可用性决策 ====================

    /** 本地视觉追问可用 */
    public boolean canFollowUpLocalVision() {
        return env.isLocalVisionLoaded();
    }

    /** 在线视觉可用（模型支持 vision + 失败冷却未触发） */
    public boolean canFollowUpOnlineVision(long lastOnlineVisionFailAt) {
        if (!env.isOnlineModel()) return false;
        if (System.currentTimeMillis() - lastOnlineVisionFailAt < VISION_FAIL_COOLDOWN_MS) return false;
        return isOnlineVisionModel();
    }

    /** 在线模型是否支持视觉（配置能力字段优先，模型名关键词兜底） */
    public boolean isOnlineVisionModel() {
        try {
            OnlineModelManager.OnlineModelConfig active = env.getActiveModel();
            if (active != null && active.hasCapability("vision")) return true;
            String modelName = env.getCurrentModelName();
            if ((modelName == null || modelName.isEmpty()) && active != null) modelName = active.modelName;
            if (modelName == null) return false;
            return OnlineModelManager.isVisionModelName(modelName);
        } catch (Exception e) {
            return false;
        }
    }

    /** 在线视觉图片是否可走 base64（落盘存在 + 未超 4MB） */
    public static boolean canOnlineVisionUseFile(File localFile) {
        return localFile != null && localFile.exists()
                && localFile.length() <= ONLINE_VISION_MAX_FILE_SIZE;
    }

    /** 读图转 base64（NO_WRAP） */
    public static String readImageAsBase64(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 在线视觉用户文本兜底 */
    public static String visionUserText(String originalMessage) {
        return originalMessage == null || originalMessage.trim().isEmpty()
                ? "请描述这张图片的内容" : originalMessage;
    }
}
