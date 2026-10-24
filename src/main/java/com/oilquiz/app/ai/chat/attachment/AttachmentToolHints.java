package com.oilquiz.app.ai.chat.attachment;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 附件增强消息里点名的 Agent 工具（2026-10-25）。
 *
 * <p>提示词（{@link AttachmentPromptBuilder#buildAgentAugmentedMessage}）在"引擎有文件/OCR 工具"
 * 时会点名 {@code file_reader} / {@code ocr_recognize}；在线 Agent 必须在**同一轮**的 tools 里
 * 真的带上它们，否则模型照着提示词点名只会拿到「工具不存在」。</p>
 *
 * <p>这里把「提示词 → 工具名」的识别收成一处：<br>
 * 1. 工具名是稳定 ASCII，英文/繁中 locale 的提示词里也一模一样，所以用工具名本身做触发词，
 *    不依赖中文文案；<br>
 * 2. 只识别**真实注册**的工具名——{@code file_parse_text} / {@code file_read_lines} 这类历史上
 *    写错的名字不再被识别，避免幽灵工具重新混进 tools；<br>
 * 3. 子串命中是有意的：提示词里写的是 action 名（如 {@code ocr_recognize_pdf}）时，
 *    对应工具 {@code ocr_recognize} 同样要被带进本轮 tools。</p>
 */
public final class AttachmentToolHints {

    /** 文件读取工具（AIToolManager 注册名；按行读 = action=read_lines） */
    public static final String TOOL_FILE_READER = "file_reader";

    /** 图片/PDF 识别工具（AIToolManager 注册名；action 见 AttachmentPromptBuilder 里的提示词） */
    public static final String TOOL_OCR_RECOGNIZE = "ocr_recognize";

    private AttachmentToolHints() {}

    /**
     * 从一段（附件增强消息）文本里识别出需要本轮 tools 必备的附件工具。
     *
     * @param prompt 附件增强消息（可为 null）
     * @return 有序、去重的工具名集合；没有命中返回空集合
     */
    public static Set<String> mentionedTools(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Collections.emptySet();
        }
        String text = prompt.toLowerCase();
        Set<String> tools = new LinkedHashSet<>();
        if (text.contains(TOOL_FILE_READER)) {
            tools.add(TOOL_FILE_READER);
        }
        if (text.contains(TOOL_OCR_RECOGNIZE)) {
            tools.add(TOOL_OCR_RECOGNIZE);
        }
        return tools;
    }
}
