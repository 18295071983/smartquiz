package com.oilquiz.app.ai.chat.attachment;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.file.FileUriUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 附件工厂（全局可复用）。
 *
 * 从 AIChatActivity handleAttachedFiles 抽取：Uri 列表 → 附件列表。
 * 包含 MIME 类型推断（image/video/audio/pdf/file）、content:// 复制到本地
 * （避免权限过期后无法访问，图片附带 thumbnailPath 快速显示）。
 * 宿主决定把产出的附件加入输入区还是直接发送。
 */
public final class AttachmentFactory {

    private AttachmentFactory() {}

    /**
     * 把选择器返回的 Uri 列表转换为聊天附件（含本地落盘）。
     * 返回的附件已含 thumbnailPath / localFilePath（图片且已复制到本地时），
     * 并按真实字节头嗅探 mimeType —— 扩展名和 content resolver 给的 MIME 都可能与实际编码不符，
     * 而视觉接口要求 media_type/data URI 前缀与字节一致。
     */
    public static List<ChatMessage.Attachment> createFromUris(Context context, List<Uri> uris) {
        List<ChatMessage.Attachment> result = new ArrayList<>();
        FileUriUtils fileUriUtils = new FileUriUtils(context);
        for (Uri uri : uris) {
            String fileName = fileUriUtils.getFileNameFromUri(uri);
            String mimeType;
            try {
                mimeType = context.getContentResolver().getType(uri);
            } catch (Exception e) {
                mimeType = null;
            }
            String type = "file";
            if (mimeType != null) {
                if (mimeType.startsWith("image/")) type = "image";
                else if (mimeType.startsWith("video/")) type = "video";
                else if (mimeType.startsWith("audio/")) type = "audio";
                else if (mimeType.contains("pdf")) type = "pdf";
            }

            String attachmentUrl = uri.toString();
            String localFilePath = null;

            if ("content".equals(uri.getScheme())) {
                String localPath = fileUriUtils.copyUriToCacheFile(uri);
                if (localPath != null) {
                    localFilePath = localPath;
                    attachmentUrl = Uri.fromFile(new File(localPath)).toString();
                }
            }
            if (localFilePath == null && "file".equals(uri.getScheme())) {
                // file:// 直接选入（例如拍照落盘后重新进入选择器）：路径本身可用
                localFilePath = uri.getPath();
            }

            ChatMessage.Attachment attachment = new ChatMessage.Attachment(
                    type, attachmentUrl, fileName, fileUriUtils.getFileSizeFromUri(uri));
            if (localFilePath != null) {
                attachment.localFilePath = localFilePath;
            }
            if ("image".equals(type) && localFilePath != null) {
                attachment.thumbnailPath = localFilePath;
                String sniffed = com.oilquiz.app.ai.util.ImagePreprocessUtil
                        .sniffMimeType(new File(localFilePath));
                if (sniffed != null && !"application/octet-stream".equals(sniffed)) {
                    attachment.mimeType = sniffed;
                }
            }
            result.add(attachment);
        }
        return result;
    }
}
