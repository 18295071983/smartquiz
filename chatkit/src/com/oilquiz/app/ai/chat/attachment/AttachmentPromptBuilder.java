package com.oilquiz.app.ai.chat.attachment;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.file.FileUriUtils;
import com.oilquiz.app.ai.chat.input.AttachmentPreParser;
import com.oilquiz.app.ai.spi.AppServices;

import java.util.List;
import java.util.Map;

/**
 * 附件分析提示词构建器（全局可复用）。
 *
 * 从 AIChatActivity 提示词构建方法抽取：安全附件分析提示词（长度限制 + 异常保护）、
 * 摘要提示词、增强消息（本地解析 / Agent 落盘两套）、解析失败判定、方法中文名映射。
 * 上下文窗口大小通过 {@link Config} 注入，不依赖具体页面。
 */
public class AttachmentPromptBuilder {

    /** 直发附件（无文字输入）时的默认占位文案 */
    public static final String DEFAULT_ATTACHMENT_MESSAGE = "请分析这些附件的内容";

    private static final int MAX_SINGLE_ATTACHMENT_CHARS = 2000;
    private static final int MAX_TOTAL_ATTACHMENT_CHARS = 6000;

    /** 上下文窗口大小提供者（由宿主注入） */
    public interface Config {
        int getContextSize();
    }

    private final Config config;
    private final FileUriUtils fileUriUtils;

    public AttachmentPromptBuilder(Context context, Config config) {
        AppServices.ensure(context);
        this.config = config;
        this.fileUriUtils = new FileUriUtils(context);
    }

    /** 构建安全的附件分析提示词（带长度限制和异常保护） */
    public String buildSafeAttachmentAnalysisPrompt(String originalMessage, String attachmentContent, int fileCount) {
        try {
            int contextSize = config.getContextSize();
            int contentLimit = Math.min((int) (contextSize * 0.4), 16000);
            if (attachmentContent != null && attachmentContent.length() > contentLimit) {
                android.util.Log.w("AttachmentPrompt", "附件内容过长(" + attachmentContent.length()
                        + " 字符, 上下文=" + contextSize + ")，截断到 " + contentLimit);
                attachmentContent = attachmentContent.substring(0, contentLimit)
                        + "\n...[附件内容过长已截断，请基于已提供内容分析]";
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append(AppServices.strings().get(R.string.h_8997e243)).append(fileCount).append(AppServices.strings().get(R.string.h_feae15b8));

            if (originalMessage != null && !originalMessage.isEmpty() && !originalMessage.equals(DEFAULT_ATTACHMENT_MESSAGE)) {
                prompt.append(AppServices.strings().get(R.string.h_14e8a21e)).append(originalMessage).append("\n\n");
            } else {
                prompt.append(AppServices.strings().get(R.string.h_e4b86c39));
            }

            prompt.append(AppServices.strings().get(R.string.h_4fe2794a));
            prompt.append(attachmentContent);
            prompt.append(AppServices.strings().get(R.string.h_4b4e3db7));
            prompt.append(AppServices.strings().get(R.string.h_48e86d79));

            String finalPrompt = prompt.toString();

            int hardLimit = (int) (contextSize * 0.6);
            if (finalPrompt.length() > hardLimit) {
                android.util.Log.w("AttachmentPrompt", "提示词仍超限(" + finalPrompt.length() + "/" + hardLimit + ")，强制截断");
                finalPrompt = finalPrompt.substring(0, hardLimit)
                        + "\n...[内容过长已截断]\n请基于以上内容回答。";
            }
            return finalPrompt;
        } catch (Exception e) {
            android.util.Log.e("AttachmentPrompt", "构建提示词异常: " + e.getMessage(), e);
            return null;
        }
    }

    /** 构建附件分析提示词（安全版别名） */
    public String buildAttachmentAnalysisPrompt(String originalMessage, String attachmentContent, int fileCount) {
        return buildSafeAttachmentAnalysisPrompt(originalMessage, attachmentContent, fileCount);
    }

    /** 是否值得生成摘要（内容达到一定规模） */
    public boolean canSummarize(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        int minCharsForSummary = 100;
        int wordCount = content.trim().split("\\s+").length;
        int charCount = content.length();
        return charCount >= minCharsForSummary || wordCount >= 30;
    }

    /** 构建摘要提示词 */
    public String buildSummaryPrompt(String userMessage, String parsedContent) {
        int contextSize = config.getContextSize();
        int safeLimit = (int) (contextSize * 0.35);
        int contentLimit = Math.min(safeLimit, 5000);

        if (parsedContent.length() > contentLimit) {
            parsedContent = parsedContent.substring(0, contentLimit) + "\n...[内容已截断]";
        }

        StringBuilder prompt = new StringBuilder();

        if (userMessage != null && !userMessage.trim().isEmpty()) {
            prompt.append(AppServices.strings().get(R.string.h_36623a6c)).append(userMessage).append("\n\n");
        } else {
            prompt.append(AppServices.strings().get(R.string.h_a485628e));
        }

        prompt.append(AppServices.strings().get(R.string.h_a4368a9d));
        prompt.append(parsedContent).append("\n\n");
        prompt.append(AppServices.strings().get(R.string.h_b575ddeb));
        prompt.append(AppServices.strings().get(R.string.h_bd78f6c8));
        prompt.append(AppServices.strings().get(R.string.h_5529af52));
        prompt.append(AppServices.strings().get(R.string.h_d6c806dd));
        prompt.append(AppServices.strings().get(R.string.h_29d6d222));
        prompt.append(AppServices.strings().get(R.string.h_dc9307dd));

        return prompt.toString();
    }

    /** 解析内容是否为失败态（失败文本前缀匹配） */
    public boolean isExtractFailed(String content) {
        if (content == null) return true;
        return content.startsWith("解析失败:")
                || content.startsWith("文件解析失败:")
                || content.startsWith("无法加载图片")
                || content.startsWith("OCR识别失败:")
                || content.startsWith("不支持的文件类型:")
                || content.startsWith("PDF文件需要")
                || content.startsWith("Word文件需要")
                || content.startsWith("Excel文件需要")
                || content.startsWith("无法确定文件类型");
    }

    /** 构建增强消息（本地解析结果 → 上下文） */
    public String buildAugmentedMessage(String originalMessage, Map<Uri, String> extractedMap) {
        if (extractedMap.isEmpty()) return originalMessage;

        int contextSize = config.getContextSize();
        int safeLimit = (int) (contextSize * 0.4);
        int totalCharLimit = Math.min(safeLimit, MAX_TOTAL_ATTACHMENT_CHARS);
        int singleCharLimit = Math.min(totalCharLimit / Math.max(1, extractedMap.size()), MAX_SINGLE_ATTACHMENT_CHARS);

        StringBuilder sb = new StringBuilder();
        sb.append(AppServices.strings().get(R.string.h_793176ee)).append(originalMessage).append("\n\n");
        sb.append(AppServices.strings().get(R.string.h_4fe2794a));

        int totalUsed = 0;
        int idx = 1;
        for (Map.Entry<Uri, String> entry : extractedMap.entrySet()) {
            String fileName = fileUriUtils.getFileNameFromUri(entry.getKey());
            String content = entry.getValue();

            if (content == null || isExtractFailed(content)) {
                sb.append(AppServices.strings().get(R.string.h_94e069c2)).append(idx++).append("】");
                if (fileName != null) sb.append(" ").append(fileName);
                sb.append(AppServices.strings().get(R.string.h_68e672a0));
                continue;
            }

            int remaining = totalCharLimit - totalUsed;
            if (remaining <= 0) {
                sb.append(AppServices.strings().get(R.string.h_86b05ff3));
                break;
            }

            int thisLimit = Math.min(singleCharLimit, remaining);
            if (content.length() > thisLimit) {
                content = content.substring(0, thisLimit) + "\n...[内容已截断]";
            }

            sb.append(AppServices.strings().get(R.string.h_94e069c2)).append(idx++).append("】");
            if (fileName != null) sb.append(" ").append(fileName);
            sb.append("\n");
            sb.append(content).append("\n\n");
            totalUsed += content.length();
        }

        sb.append(AppServices.strings().get(R.string.h_c65217ea));
        sb.append(AppServices.strings().get(R.string.h_1575bdd9));

        return sb.toString();
    }

    /** 构建 Agent 增强消息（落盘路径 + 预解析结果 → 上下文） */
    public String buildAgentAugmentedMessage(String originalMessage, List<ChatMessage.Attachment> attachments,
                                             Map<Uri, String> localFileMap, List<String> skippedFiles,
                                             Map<String, AttachmentPreParser.ParseResult> parseResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(AppServices.strings().get(R.string.h_793176ee)).append(originalMessage).append("\n\n");
        sb.append(AppServices.strings().get(R.string.h_11214c64));

        int successCount = 0;
        int idx = 1;
        for (ChatMessage.Attachment att : attachments) {
            Uri uri = Uri.parse(att.url);
            String localPath = localFileMap != null ? localFileMap.get(uri) : null;
            AttachmentPreParser.ParseResult r = parseResults != null ? parseResults.get(att.id) : null;

            sb.append(AppServices.strings().get(R.string.h_94e069c2)).append(idx).append("】").append(att.name).append("\n");
            sb.append(AppServices.strings().get(R.string.h_1ee53933)).append(att.type).append(AppServices.strings().get(R.string.h_176d6e45))
              .append(FileUriUtils.formatFileSize(att.size)).append("\n");
            if (localPath != null) {
                sb.append(AppServices.strings().get(R.string.h_28c797b8)).append(localPath).append("\n");
            }

            if (r != null && r.isUsable()) {
                successCount++;
                sb.append(AppServices.strings().get(R.string.h_764fab2b))
                  .append(r.status == AttachmentPreParser.ParseStatus.PARTIAL_SUCCESS ? "PARTIAL_SUCCESS" : "SUCCESS")
                  .append("\n");
                sb.append(AppServices.strings().get(R.string.h_5f27c9db)).append(r.method)
                  .append(r.fromCache ? AppServices.strings().get(R.string.h_2d8ed504) : "").append("\n");
                if (r.errorMessage != null) {
                    sb.append(AppServices.strings().get(R.string.h_46bc48a9)).append(r.errorMessage).append("\n");
                }
                sb.append(AppServices.strings().get(R.string.h_1e7d25f8));
                sb.append(AppServices.strings().get(R.string.h_24984e0c));
                String content = r.content;
                int maxLen = 12000;
                if (content.length() > maxLen) {
                    sb.append(content, 0, maxLen);
                    sb.append(AppServices.strings().get(R.string.h_55a2a38e)).append(content.length())
                      .append(AppServices.strings().get(R.string.h_6b35290e));
                } else {
                    sb.append(content).append("\n");
                }
                sb.append(AppServices.strings().get(R.string.h_dae5a2ac));
            } else {
                sb.append(AppServices.strings().get(R.string.h_bf63260f));
                if (r != null) {
                    sb.append(AppServices.strings().get(R.string.h_5d89be82)).append(r.errorCode).append("\n");
                    sb.append(AppServices.strings().get(R.string.h_41d16b3d)).append(r.errorMessage).append("\n");
                    if (localPath != null) {
                        sb.append(AppServices.strings().get(R.string.h_cad33dfa));
                    }
                } else {
                    sb.append(AppServices.strings().get(R.string.h_03670db5));
                }
            }
            sb.append("\n");
            idx++;
        }

        if (skippedFiles != null && !skippedFiles.isEmpty()) {
            sb.append(AppServices.strings().get(R.string.h_d7824330)).append(String.join(", ", skippedFiles)).append("\n\n");
        }

        sb.append(AppServices.strings().get(R.string.h_9e0ee86a));
        if (successCount > 0) {
            sb.append(AppServices.strings().get(R.string.h_ee31e1e3)).append(successCount).append(AppServices.strings().get(R.string.h_fe85860b));
        }
        sb.append(AppServices.strings().get(R.string.h_f6a98d86));
        sb.append(AppServices.strings().get(R.string.h_447064b9));
        sb.append(AppServices.strings().get(R.string.h_30890ceb));

        sb.append(AppServices.strings().get(R.string.h_b07b6bc9));
        sb.append(AppServices.strings().get(R.string.h_db5abb31));
        sb.append("- file_parse_text: file_path | file_read_lines: file_path, start_line, line_count\n");
        sb.append("- ocr_recognize: image_path | ocr_recognize_pdf: pdf_path\n\n");

        if (!DEFAULT_ATTACHMENT_MESSAGE.equals(originalMessage)) {
            sb.append(AppServices.strings().get(R.string.h_cf88fe4b));
        } else {
            sb.append(AppServices.strings().get(R.string.h_926e80d4));
        }
        return sb.toString();
    }

    /** 按附件 id 找展示名 */
    public String getAttachmentNameById(List<ChatMessage.Attachment> attachments, String id) {
        for (ChatMessage.Attachment att : attachments) {
            if (att.id != null && att.id.equals(id)) return att.name;
        }
        return "未知文件";
    }

    /** 解析方法中文名 */
    public String methodLabel(String method) {
        if (method == null) return "未知";
        switch (method) {
            case "text_extract": return "文本提取";
            case "online_ocr": return "在线视觉模型OCR";
            case "local_ocr": return "本地OCR";
            case "pdf_pages_ocr": return "PDF逐页OCR";
            default: return method;
        }
    }
}
