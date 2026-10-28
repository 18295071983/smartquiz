package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.agent.ToolResultCardBinder;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code file_generator} 的 edit / diff 两个动作（2026-10-28 从 python_file_ops 迁来）。
 *
 * <p>这里测的是"落到盘上的事实"而不是返回值：edit 必须只动匹配到的那一段、0 匹配时必须一个字节都不改；
 * diff 是只读预览，跑完文件必须原样。返回值再漂亮，文件被改错也没用。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class FileGeneratorEditActionTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "edit-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    @Test
    public void editRewritesOnlyTheMatchedSpanAndAttachesDiff() throws Exception {
        File file = new File(dir, "rules.md");
        writeRaw(file, "规则一：先声明式\n规则二：后代码\n");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "edit");
        params.put("file_path", file.getAbsolutePath());
        params.put("old_text", "先声明式");
        params.put("new_text", "先想专用工具");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        assertEquals("规则一：先想专用工具\n规则二：后代码\n", read(file));

        Map<String, Object> out = asMap(result.getResult());
        assertNotNull(out);
        assertEquals("edit", out.get("action"));
        assertEquals(1, out.get("replaced"));
        assertEquals(1, out.get("matched"));
        String unified = String.valueOf(out.get("unifiedDiff"));
        assertTrue("要给模型看 diff 头: " + unified, unified.contains("@@"));
        assertTrue("要能看到新行: " + unified, unified.contains("+规则一：先想专用工具"));

        Map<String, Object> diff = asMap(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
        assertNotNull("改完要挂 diff 卡", diff);
        assertEquals("规则一：先声明式\n规则二：后代码\n", diff.get("before"));
    }

    @Test
    public void editWithNoMatchFailsAndLeavesFileUntouched() throws Exception {
        File file = new File(dir, "rules.md");
        String original = "规则一：先声明式\n";
        writeRaw(file, original);

        Map<String, Object> params = new HashMap<>();
        params.put("action", "edit");
        params.put("file_path", file.getAbsolutePath());
        params.put("old_text", "这句话文件里没有");
        params.put("new_text", "x");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertFalse("匹配 0 处必须失败", result.isSuccess());
        assertTrue(String.valueOf(result.getErrorMessage()), String.valueOf(result.getErrorMessage())
                .contains("未找到精确匹配"));
        assertEquals("失败时一个字节都不能动", original, read(file));
    }

    @Test
    public void diffPreviewDoesNotWriteButShowsPreviewCard() throws Exception {
        File file = new File(dir, "notes.txt");
        writeRaw(file, "a\nb\n");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "diff");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "a\nc\n");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        assertEquals("预览不许落盘", "a\nb\n", read(file));

        Map<String, Object> out = asMap(result.getResult());
        assertNotNull(out);
        assertEquals("diff", out.get("action"));
        assertEquals(Boolean.TRUE, out.get("changed"));
        assertTrue(String.valueOf(out.get("unifiedDiff")).contains("+c"));

        Map<String, Object> diff = asMap(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
        assertNotNull("预览要出对比卡", diff);
        assertTrue("标题要标预览: " + diff.get("title"), String.valueOf(diff.get("title")).contains("变更预览"));
        assertEquals(Boolean.TRUE, diff.get("preview"));
        assertEquals("a\nc\n", diff.get("after"));
    }

    @Test
    public void diffOfIdenticalContentShowsNothingToCompare() throws Exception {
        File file = new File(dir, "same.txt");
        writeRaw(file, "same\n");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "diff");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "same\n");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        assertEquals(Boolean.FALSE, asMap(result.getResult()).get("changed"));
        assertNull("无差异不该挂空卡",
                result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
    }

    /**
     * 编码要真的生效（2026-10-28 从 python_file_ops 迁过来时补的洞）：
     * write 以前无论传 encoding 什么都写 UTF-8，append 也不跟原文件编码走——
     * 迁过来的 GB18030 场景会静默变乱码，这属于"功能没损失"必须先证明的地方。
     */
    @Test
    public void writeHonoursRequestedEncoding() throws Exception {
        File file = new File(dir, "gbk.txt");
        Map<String, Object> params = new HashMap<>();
        params.put("action", "write");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "中文内容");
        params.put("encoding", "GB18030");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        assertEquals("写文件要用请求的编码",
                "中文内容", new String(Files.readAllBytes(file.toPath()),
                        java.nio.charset.Charset.forName("GB18030")));
    }

    @Test
    public void appendFollowsExistingFileEncoding() throws Exception {
        File file = new File(dir, "gbk-append.txt");
        Files.write(file.toPath(), "第一段".getBytes(java.nio.charset.Charset.forName("GB18030")));

        Map<String, Object> params = new HashMap<>();
        params.put("action", "append");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "第二段");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        String all = new String(Files.readAllBytes(file.toPath()),
                java.nio.charset.Charset.forName("GB18030"));
        assertEquals("追加要沿用原文件编码", "第一段\n第二段", all);
    }

    /**
     * 卡片的反馈链（2026-10-28 用户问"编辑动作是不是反馈不到 UI"）：
     * 工具结果 → ToolResultCardBinder → 聊天流组件。这里直接断言"编辑会出 file_card + diff 卡，
     * 且 diff 卡标题写明是修改、替换了几处"——没有这张卡，用户在界面上就看不出文件被改过。
     */
    @Test
    public void editReachesUiAsFileCardPlusDiffCard() throws Exception {
        File file = new File(dir, "ui.md");
        writeRaw(file, "A\\nB\\nC\\n");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "edit");
        params.put("file_path", file.getAbsolutePath());
        params.put("old_text", "B");
        params.put("new_text", "B2");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);

        List<ComponentData> cards = ToolResultCardBinder.build(context, "file_generator", result, params);
        ComponentData fileCard = null;
        ComponentData diffCard = null;
        for (ComponentData c : cards) {
            if ("file_card".equals(c.type)) fileCard = c;
            if ("diff".equals(c.type)) diffCard = c;
        }
        assertNotNull("编辑后应有文件卡: " + types(cards), fileCard);
        assertNotNull("编辑后应有 diff 卡（否则界面看不出改了哪）: " + types(cards), diffCard);
        String title = diffCard.props.optString("title");
        assertTrue("diff 卡标题要说明是「修改」: " + title, title.contains("已修改"));
        assertTrue("diff 卡标题要带替换处数: " + title, title.contains("替换 1 处"));
        assertEquals("A\\nB2\\nC\\n", diffCard.props.optString("after"));
    }

    @Test
    public void overwriteWriteReachesUiWithOverwriteTitle() throws Exception {
        File file = new File(dir, "ui-write.md");
        writeRaw(file, "old\\n");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "write");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "new\\n");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);

        List<ComponentData> cards = ToolResultCardBinder.build(context, "file_generator", result, params);
        ComponentData diffCard = null;
        for (ComponentData c : cards) {
            if ("diff".equals(c.type)) diffCard = c;
        }
        assertNotNull("覆盖写应有 diff 卡: " + types(cards), diffCard);
        assertTrue("覆盖写的标题要说明是「覆盖写入」: " + diffCard.props.optString("title"),
                diffCard.props.optString("title").contains("已覆盖写入"));
    }

    private static String types(List<ComponentData> cards) {
        StringBuilder sb = new StringBuilder();
        for (ComponentData c : cards) {
            if (sb.length() > 0) sb.append(",");
            sb.append(c.type);
        }
        return sb.toString();
    }

    private static void assertOk(AIToolResult result) {
        String message = result.getErrorMessage() == null
                ? String.valueOf(result.getResult()) : result.getErrorMessage();
        assertTrue(message, result.isSuccess());
        assertNotNull(message, result.getAdditionalInfo());
    }

    private static void writeRaw(File file, String content) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
