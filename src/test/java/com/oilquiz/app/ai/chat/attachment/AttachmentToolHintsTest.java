package com.oilquiz.app.ai.chat.attachment;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AttachmentToolHints 用例（2026-10-25）：提示词 → 本轮 tools 的识别口径。
 *
 * <p>锁两件事：真实注册名（file_reader / ocr_recognize）能被识别；历史上写错的幽灵名
 * （file_parse_text / file_read_lines）不会再被识别，避免它们重新混进本轮 tools。</p>
 */
public class AttachmentToolHintsTest {

    @Test
    public void recognizesRealAttachmentTools() {
        String prompt = "工具：file_reader（action=read_lines）按行读取；"
                + "ocr_recognize（action=ocr_recognize_pdf）识别 PDF";
        Set<String> tools = AttachmentToolHints.mentionedTools(prompt);

        assertEquals(2, tools.size());
        assertTrue(tools.contains(AttachmentToolHints.TOOL_FILE_READER));
        assertTrue(tools.contains(AttachmentToolHints.TOOL_OCR_RECOGNIZE));
    }

    @Test
    public void ignoresPhantomToolNames() {
        String prompt = "可用工具：file_parse_text / file_read_lines";
        assertTrue("幽灵工具名（从未注册）不能触发任何工具注入",
                AttachmentToolHints.mentionedTools(prompt).isEmpty());
    }

    @Test
    public void actionNameStillMapsToItsTool() {
        // ocr_recognize_pdf 是 ocr_recognize 的 action；提示词提到它 → 该工具本轮要有
        assertEquals(new LinkedHashSet<>(Arrays.asList(AttachmentToolHints.TOOL_OCR_RECOGNIZE)),
                AttachmentToolHints.mentionedTools("action=ocr_recognize_pdf（pdf_path=...）"));
    }

    @Test
    public void plainMessageHasNoAttachmentTools() {
        assertTrue(AttachmentToolHints.mentionedTools("帮我总结一下这份资料").isEmpty());
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertTrue(AttachmentToolHints.mentionedTools(null).isEmpty());
        assertTrue(AttachmentToolHints.mentionedTools("").isEmpty());
    }

    @Test
    public void caseInsensitive() {
        assertEquals(new LinkedHashSet<>(Arrays.asList("file_reader")),
                AttachmentToolHints.mentionedTools("FILE_READER"));
    }
}
