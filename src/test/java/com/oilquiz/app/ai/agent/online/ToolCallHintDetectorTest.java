package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * {@link ToolCallHintDetector} 回归测试。
 *
 * <p>误判的代价不对称：漏判只是少发一条格式提示；误判会把一条已经说完的回答按回循环里，
 * 让模型把同一段结论换个说法再说一遍（2026-10-28 真机会话 f135ecaa 就重说了 8 遍）。
 * 所以这里的第一组用例是"解释工具时不能触发"，取自那条回答的原文。
 */
public class ToolCallHintDetectorTest {

    private static final List<String> TOOLS = Arrays.asList(
            "file_generator", "python_file_ops", "python_execute", "js_execute",
            "workspace", "file_reader", "weather", "search", "task", "memory");

    /** 真机原文（节选）：整段都在解释工具怎么用，一个工具调用都不该触发 */
    private static final String REAL_ANSWER_EXCERPT =
            "不完全是。准确地说，没有一个独立命名为 `write` 的工具，但写文件这项能力是齐全的：\n"
          + "1. **file_generator**：写文件的主力。它没有 `write` 这个 action，但 `create`（新建并写入）承担同样的职责。\n"
          + "2. **python_file_ops**：action 列表是 `read/parse/write/append/edit/replace/diff`，其中明确包含 `write`。\n\n"
          + "| 工具 | 写入入口 |\n|---|---|\n| `file_generator` | `create`、`append`、`markdown` |\n\n"
          + "记住一条：新建文件用 `file_generator(action=create)`，改某一段用 `python_file_ops(action=edit)`。";

    @Test
    public void explainingToolsInInlineCodeIsNotACallIntent() {
        assertNull("解释工具用法被当成『要调用工具』——真机会话重答 8 次就是这个误判",
                ToolCallHintDetector.detectReason(REAL_ANSWER_EXCERPT, TOOLS));
    }

    @Test
    public void plainToolMentionWithoutCallSyntaxIsNotACallIntent() {
        String answer = "写文件用 file_generator，改文件用 python_file_ops，读文件用 file_reader。";
        assertNull(ToolCallHintDetector.detectReason(answer, TOOLS));
    }

    @Test
    public void englishCasualPhraseIsNotACallIntent() {
        assertNull(ToolCallHintDetector.detectReason(
                "Let me check the file first, then I will summarize it.", TOOLS));
    }

    @Test
    public void pseudoCallInProseIsDetected() {
        assertNotNull("正文里写 weather(...) 却不进 tool_calls，正是要纠正的形态",
                ToolCallHintDetector.detectReason("我先 weather(北京) 一下再回答。", TOOLS));
    }

    @Test
    public void pseudoCallWithFullWidthParensIsDetected() {
        assertNotNull(ToolCallHintDetector.detectReason("接下来调用 search（关键词）看看。", TOOLS));
    }

    @Test
    public void fencedJsonToolCallIsDetected() {
        String text = "我这样调用：\n```json\n{\"name\": \"weather\", \"arguments\": {\"city\": \"北京\"}}\n```";
        assertNotNull("围栏代码块里的伪 tool_calls 必须仍然命中",
                ToolCallHintDetector.detectReason(text, TOOLS));
    }

    @Test
    public void chineseVerbNextToToolIsDetected() {
        assertNotNull(ToolCallHintDetector.detectReason("我将使用 file_generator 生成报告。", TOOLS));
    }

    @Test
    public void englishExplicitCallIntentIsDetected() {
        assertNotNull(ToolCallHintDetector.detectReason("I will call the weather tool now.", TOOLS));
    }

    @Test
    public void toolNameInsideLargerIdentifierIsNotACall() {
        assertNull("my_file_generator(...) 不是注册工具名",
                ToolCallHintDetector.detectReason("my_file_generator(x) 只是示意。", TOOLS));
    }

    @Test
    public void emptyInputIsNotACallIntent() {
        assertNull(ToolCallHintDetector.detectReason("", TOOLS));
        assertNull(ToolCallHintDetector.detectReason(null, TOOLS));
        assertNull(ToolCallHintDetector.detectReason("随便聊聊。", Arrays.asList()));
    }

    @Test
    public void inlineCodeIsStrippedButFencedBlocksAreKept() {
        String stripped = ToolCallHintDetector.stripInlineCode("用 `file_generator(action=create)` 写文件");
        assertFalse("行内代码应被抹掉，只留正文", stripped.contains("file_generator"));
        assertTrue("正文其余部分要保留", stripped.contains("写文件"));
        assertNotNull("围栏块不剥，伪调用才能被 JSON 规则抓住",
                ToolCallHintDetector.detectReason(
                        "```json\n{\"name\": \"memory\"}\n```", TOOLS));
    }
}
