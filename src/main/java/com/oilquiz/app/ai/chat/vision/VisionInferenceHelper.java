package com.oilquiz.app.ai.chat.vision;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.util.AttachmentManager;
import com.oilquiz.app.ai.util.ImagePreprocessUtil;

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

    /**
     * 解析附件为真实本地文件（优先落盘路径，其次走统一 Uri 解析）。
     *
     * 口径与 {@code AIChatActivity}/{@code UriPathResolver} 保持一致：不再手工
     * {@code url.replace("file://", "")} —— 那会让 content://、纯路径、带空格的 file URI
     * 三种写法各自解读，同一附件在不同链路里解析出不同结果。
     */
    public File resolveAttachmentFile(ChatMessage.Attachment att) {
        if (att == null) return null;
        try {
            if (att.localFilePath != null) {
                File f = new File(att.localFilePath);
                if (f.exists()) return f;
            }
            if (att.url != null && !att.url.isEmpty()) {
                File f = com.oilquiz.app.util.UriPathResolver.resolveToFile(context, att.url);
                if (f != null && f.exists()) return f;
            }
        } catch (Exception e) {
            android.util.Log.w("VisionHelper", "resolveAttachmentFile failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 把附件解析并编码为一次在线视觉请求的图片集。
     *
     * @return 与 files 等长的结果列表，元素可能为 null（该图不可用），由宿主决定如何回报
     */
    public java.util.List<ImagePreprocessUtil.PreparedVisionImage> encodeForVision(List<File> files) {
        List<ImagePreprocessUtil.PreparedVisionImage> out = new ArrayList<>();
        if (files == null) return out;
        for (File file : files) {
            out.add(ImagePreprocessUtil.prepareForOnlineVision(file));
        }
        return out;
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
