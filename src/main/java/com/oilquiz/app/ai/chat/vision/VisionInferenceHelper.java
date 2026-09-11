package com.oilquiz.app.ai.chat.vision;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.util.AttachmentManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视觉推理辅助器（全局可复用）。
 *
 * 从 AIChatActivity 视觉链路抽取的可复用侧：视觉历史构建（最近 12 条）、
 * 附件解析为真实本地文件、附件批量落盘（AttachmentManager 回调 → CompletableFuture）。
 * 编排与渲染（多模态推理、OCR 流程、消息流更新）仍由宿主负责。
 */
public class VisionInferenceHelper {

    /** 视觉历史最多携带条数 */
    public static final int VISION_HISTORY_MAX = 12;

    private final Context context;

    public VisionInferenceHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 构建视觉推理历史（仅 USER/AI 且有正文，最多 12 条，排除尾部 N 条） */
    public List<ChatMessage> buildVisionHistory(List<ChatMessage> chatHistory, int excludeLast) {
        List<ChatMessage> hist = new ArrayList<>();
        int end = Math.max(0, chatHistory.size() - excludeLast);
        for (int i = 0; i < end; i++) {
            ChatMessage m = chatHistory.get(i);
            if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                    && m.content != null && !m.content.isEmpty()) {
                hist.add(m);
            }
        }
        if (hist.size() > VISION_HISTORY_MAX) {
            hist = new ArrayList<>(hist.subList(hist.size() - VISION_HISTORY_MAX, hist.size()));
        }
        return hist;
    }

    /** 解析附件为真实本地文件（优先落盘路径，否则 content:// 复制到缓存） */
    public File resolveAttachmentFile(ChatMessage.Attachment att) {
        try {
            if (att.localFilePath != null) {
                File f = new File(att.localFilePath);
                if (f.exists()) return f;
            }
            if (att.url != null) {
                File f = new File(att.url.replace("file://", ""));
                if (f.exists()) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 批量把附件 Uri 保存到本地（AttachmentManager 回调 → future）。
     * 返回 uri → 落盘绝对路径 映射（失败路径可能缺项）。
     */
    public CompletableFuture<Map<Uri, String>> saveAttachmentsToLocal(
            AttachmentManager attachmentManager, List<Uri> uris) {
        CompletableFuture<Map<Uri, String>> future = new CompletableFuture<>();
        Map<Uri, String> localPaths = new ConcurrentHashMap<>();

        if (uris.isEmpty()) {
            future.complete(localPaths);
            return future;
        }

        attachmentManager.saveAttachments(context, new ArrayList<>(uris),
                new AttachmentManager.AttachmentCallback() {
                    @Override
                    public void onSuccess(List<AttachmentManager.AttachmentFile> savedFiles) {
                        for (AttachmentManager.AttachmentFile file : savedFiles) {
                            localPaths.put(file.uri, file.path);
                        }
                        future.complete(localPaths);
                    }

                    @Override
                    public void onError(String error) {
                        android.util.Log.e("VisionHelper", "Save attachments failed: " + error);
                        future.complete(localPaths);
                    }
                });
        return future;
    }
}
