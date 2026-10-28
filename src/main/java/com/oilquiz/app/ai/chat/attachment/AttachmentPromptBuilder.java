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

    /**
     * 构建 Agent 增强消息（落盘路径 + 预解析结果 → 上下文）。
     *
     * <p>提示词里点名的工具必须是 {@code AIToolManager} 真实注册的工具名（{@code file_reader}
     * 的 read_lines / parse_structured / read，{@code ocr_recognize} 的 ocr_recognize /
     * ocr_recognize_pdf / image_understand），否则模型照提示词点名只会拿到「工具不存在」。
     * 这里也不再手写任何「调用格式」字面量：在线 Agent 走 OpenAI tools 协议、本地 Agent 走
     * Qwen 原生 &lt;tool_call&gt; 协议，调用格式由各自引擎决定，提示词只描述工具能力。</p>
     *
     * @param fileToolsAvailable 本轮 Agent 引擎是否真的带文件/OCR 工具。在线 Agent 传 true
     *        （附件消息里点名的工具会由 {@code OnlineAgentEngine#execute} 补进本轮核心 tools，
     *        见 {@link AttachmentToolHints}）；本地 Agent 的常驻工具只有
     *        time_date/location/ai_weather/network_search，传 false —— 否则提示词会让
     *        模型去调它 schema 里根本没有的工具（本地 native PEG 按 tools 数组逐字枚举，
     *        未注入的工具名根本生成不出来）。
     */
    public String buildAgentAugmentedMessage(String originalMessage, List<ChatMessage.Attachment> attachments,
                                             Map<Uri, String> localFileMap, List<String> skippedFiles,
                                             Map<String, AttachmentPreParser.ParseResult> parseResults,
                                             boolean fileToolsAvailable) {
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
                      .append(AppServices.strings().get(fileToolsAvailable
                              ? R.string.h_6b35290e : R.string.h_d6d43061));
                } else {
                    sb.append(content).append("\n");
                }
                sb.append(AppServices.strings().get(R.string.h_dae5a2ac));
            } else {
                sb.append(AppServices.strings().get(R.string.h_bf63260f));
                if (r != null) {
                    sb.append(AppServices.strings().get(R.string.h_5d89be82)).append(r.errorCode).append("\n");
                    sb.append(AppServices.strings().get(R.string.h_41d16b3d)).append(r.errorMessage).append("\n");
                    // 重试指引只在引擎真有工具时给；本地 Agent 没有文件/OCR 工具，给了只会让模型空转
                    if (localPath != null && fileToolsAvailable) {
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
            sb.append(AppServices.strings().get(R.string.h_ee31e1e3)).append(successCount)
              .append(AppServices.strings().get(fileToolsAvailable
                      ? R.string.h_fe85860b : R.string.h_98c42f39));
        }
        sb.append(AppServices.strings().get(R.string.h_f6a98d86));
        sb.append(AppServices.strings().get(R.string.h_447064b9));

        // 工具指引只在引擎真有这些工具时给（在线 Agent）。工具名与 action 必须按真实 schema 写：
        // file_reader 的 action=read_lines/parse_structured/read，ocr_recognize 的
        // action=ocr_recognize/ocr_recognize_pdf/image_understand。不写任何调用格式字面量 ——
        // 在线引擎是 OpenAI tools、本地引擎是 Qwen 原生协议，格式由各自引擎负责。
        if (fileToolsAvailable) {
            sb.append(AppServices.strings().get(R.string.h_30890ceb));
            sb.append(AppServices.strings().get(R.string.h_b07b6bc9));
            sb.append("- file_reader：action=read_lines（file_path=上面的本地路径，start_line/end_line 指定行区间）按行读取；action=parse_structured 解析表格/CSV/JSON/XML；action=read 读取全文\n");
            sb.append("- ocr_recognize：action=ocr_recognize（image_path=图片路径）识别图片文字；action=ocr_recognize_pdf（pdf_path=PDF 路径）识别 PDF 文字；action=image_understand（image_path+question）看图理解\n\n");
        }

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
