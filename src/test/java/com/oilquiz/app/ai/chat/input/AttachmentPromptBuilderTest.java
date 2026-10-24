package com.oilquiz.app.ai.chat.input;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.attachment.AttachmentPromptBuilder;
import com.oilquiz.app.ai.spi.AppServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AttachmentPromptBuilder 的 Agent 增强消息对齐用例（2026-10-25）。
 *
 * <p>锁三件事：<br>
 * 1. 提示词里点名的工具必须是真实注册名（file_reader / ocr_recognize），不能再出现
 *    file_parse_text / file_read_lines 这类「写了但没注册」的名字；<br>
 * 2. 不再注入 Qwen 原生 &lt;tool_call_begin&gt; 字面量（在线 Agent 走 OpenAI tools 协议，
 *    调用格式由引擎负责）；<br>
 * 3. 本地 Agent（常驻工具只有 4 个）传 fileToolsAvailable=false 时，整条提示词不出现
 *    文件/OCR 工具指引，避免模型去调 schema 里不存在的工具。</p>
 *
 * <p>放在 input 包内是为了构造包级可见的 {@link AttachmentPreParser.ParseResult}；
 * 断言只用 ASCII 标记（工具名/action/错误码/文件名），不依赖默认语言资源。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AttachmentPromptBuilderTest {

    private static final String ATT_NAME = "report.pdf";
    private static final String LOCAL_PATH = "/data/user/0/com.oilquiz.app/files/attachments/report.pdf";

    private Context context;
    private AttachmentPromptBuilder builder;

    @Before
    public void setUp() {
        AppServices.reset();
        context = ApplicationProvider.getApplicationContext();
        builder = new AttachmentPromptBuilder(context, () -> 32768);
    }

    @After
    public void tearDown() {
        AppServices.reset();
    }

    // ==================== 工具 ====================

    @Test
    public void onlinePrompt_usesRegisteredToolNames_andNoLiteralCallFormat() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = success(repeat('B', 12001));
        String prompt = builder.buildAgentAugmentedMessage(
                "帮我总结这份资料", Collections.singletonList(att),
                localFileMap(att), new ArrayList<>(), resultMap(att.id, parse), true);

        assertTrue("点名的文件工具必须是真实注册名 file_reader", prompt.contains("file_reader"));
        assertTrue("点名的 OCR 工具必须是真实注册名 ocr_recognize", prompt.contains("ocr_recognize"));
        assertTrue("截断后按行读要用 file_reader 的 read_lines action", prompt.contains("action=read_lines"));
        assertFalse("不能出现未注册的 file_parse_text", prompt.contains("file_parse_text"));
        assertFalse("不能出现未注册的 file_read_lines", prompt.contains("file_read_lines"));
        assertFalse("不能注入 Qwen 原生 tool call 字面量", prompt.contains("<|tool_call_begin|>"));
        assertFalse("不能拿 ai_weather 当调用格式示例", prompt.contains("ai_weather"));
        assertTrue("附件名与本地路径要带给模型", prompt.contains(ATT_NAME) && prompt.contains(LOCAL_PATH));
        assertTrue("原用户消息要保留", prompt.contains("帮我总结这份资料"));
        // 提示词 → 本轮 tools 的识别必须对得上：在线引擎靠这里把工具补进 tools
        java.util.Set<String> hinted = com.oilquiz.app.ai.chat.attachment.AttachmentToolHints
                .mentionedTools(prompt);
        assertTrue("在线提示词必须能被 AttachmentToolHints 识别出 file_reader",
                hinted.contains(com.oilquiz.app.ai.chat.attachment.AttachmentToolHints.TOOL_FILE_READER));
        assertTrue("在线提示词必须能被 AttachmentToolHints 识别出 ocr_recognize",
                hinted.contains(com.oilquiz.app.ai.chat.attachment.AttachmentToolHints.TOOL_OCR_RECOGNIZE));
    }

    @Test
    public void localPrompt_hasNoFileToolGuidance() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = success("解析出的正文");
        String prompt = builder.buildAgentAugmentedMessage(
                "帮我总结这份资料", Collections.singletonList(att),
                localFileMap(att), new ArrayList<>(), resultMap(att.id, parse), false);

        assertFalse("本地 Agent 没有文件工具，不能在提示词里点名", prompt.contains("file_reader"));
        assertFalse("本地 Agent 没有 OCR 工具，不能在提示词里点名", prompt.contains("ocr_recognize"));
        assertFalse("不能出现未注册的 file_parse_text", prompt.contains("file_parse_text"));
        assertFalse("不能出现未注册的 file_read_lines", prompt.contains("file_read_lines"));
        assertFalse("不能注入 Qwen 原生 tool call 字面量", prompt.contains("<|tool_call_begin|>"));
        assertTrue("附件名与本地路径仍要带给模型", prompt.contains(ATT_NAME) && prompt.contains(LOCAL_PATH));
        assertTrue("解析正文仍要带给模型", prompt.contains("解析出的正文"));
        assertTrue("本地提示词不应触发任何工具注入",
                com.oilquiz.app.ai.chat.attachment.AttachmentToolHints.mentionedTools(prompt).isEmpty());
    }

    // ==================== 截断 ====================

    @Test
    public void onlinePrompt_truncationPointsToFileReaderReadLines() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = success(repeat('A', 13000));
        String prompt = builder.buildAgentAugmentedMessage(
                "分析", Collections.singletonList(att),
                localFileMap(att), new ArrayList<>(), resultMap(att.id, parse), true);

        assertTrue("应说明完整内容长度", prompt.contains("13000"));
        assertTrue("截断提示要指向 file_reader 的 read_lines", prompt.contains("action=read_lines"));
        assertFalse("旧工具名不能残留", prompt.contains("file_read_lines"));
    }

    @Test
    public void localPrompt_truncationHasNoToolPointer() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = success(repeat('A', 13000));
        String prompt = builder.buildAgentAugmentedMessage(
                "分析", Collections.singletonList(att),
                localFileMap(att), new ArrayList<>(), resultMap(att.id, parse), false);

        assertTrue("截断提示仍要说明完整内容长度", prompt.contains("13000"));
        assertFalse("本地模式不能指向没有的工具", prompt.contains("file_reader"));
        assertFalse("旧工具名不能残留", prompt.contains("file_read_lines"));
    }

    // ==================== 解析失败 ====================

    @Test
    public void onlinePrompt_failedParseSuggestsRealTools() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = failed();
        String prompt = builder.buildAgentAugmentedMessage(
                "分析", Collections.singletonList(att),
                localFileMap(att), new ArrayList<>(), resultMap(att.id, parse), true);

        assertTrue("失败原因/错误码要透出", prompt.contains("OCR_FAILED"));
        assertTrue("在线模式可建议用 ocr_recognize 重试", prompt.contains("ocr_recognize"));
        assertFalse("不能建议未注册的工具", prompt.contains("file_parse_text"));
    }

    @Test
    public void localPrompt_failedParseOmitsRetryTools() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = failed();
        String prompt = builder.buildAgentAugmentedMessage(
                "分析", Collections.singletonList(att),
                localFileMap(att), new ArrayList<>(), resultMap(att.id, parse), false);

        assertTrue("失败原因/错误码要透出", prompt.contains("OCR_FAILED"));
        assertFalse("本地没有工具可重试，不能给工具指引", prompt.contains("ocr_recognize"));
        assertFalse("不能建议未注册的工具", prompt.contains("file_parse_text"));
    }

    // ==================== 跳过文件 ====================

    @Test
    public void skippedFiles_areListed() {
        ChatMessage.Attachment att = pdfAttachment();
        AttachmentPreParser.ParseResult parse = success("正文");
        List<String> skipped = Collections.singletonList("huge.zip(超出数量限制)");
        String prompt = builder.buildAgentAugmentedMessage(
                "分析", Collections.singletonList(att), localFileMap(att), skipped,
                resultMap(att.id, parse), true);

        assertTrue("被跳过的文件要告诉模型", prompt.contains("huge.zip"));
    }

    // ==================== fixtures ====================

    private static ChatMessage.Attachment pdfAttachment() {
        ChatMessage.Attachment att = new ChatMessage.Attachment(
                "pdf", "file://" + LOCAL_PATH, ATT_NAME, 2048L);
        att.localFilePath = LOCAL_PATH;
        return att;
    }

    private static Map<Uri, String> localFileMap(ChatMessage.Attachment att) {
        Map<Uri, String> map = new HashMap<>();
        map.put(Uri.parse(att.url), LOCAL_PATH);
        return map;
    }

    private static Map<String, AttachmentPreParser.ParseResult> resultMap(
            String id, AttachmentPreParser.ParseResult parse) {
        Map<String, AttachmentPreParser.ParseResult> map = new HashMap<>();
        map.put(id, parse);
        return map;
    }

    private static AttachmentPreParser.ParseResult success(String content) {
        AttachmentPreParser.ParseResult r = new AttachmentPreParser.ParseResult("att-1");
        r.status = AttachmentPreParser.ParseStatus.SUCCESS;
        r.content = content;
        r.method = "text_extract";
        return r;
    }

    private static AttachmentPreParser.ParseResult failed() {
        AttachmentPreParser.ParseResult r = new AttachmentPreParser.ParseResult("att-1");
        r.status = AttachmentPreParser.ParseStatus.FAILED;
        r.errorCode = AttachmentPreParser.ERR_OCR_FAILED;
        r.errorMessage = "图片 OCR 未识别到文字";
        return r;
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}
