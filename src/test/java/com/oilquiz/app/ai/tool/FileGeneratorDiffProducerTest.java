package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * file_generator 的 diff 生产者（2026-10-24）：覆盖写/追加在 additionalInfo 里带 before/after，
 * 新建文件不带（避免"全是新增"的噪音卡）。
 *
 * <p>这里传的是 {@code File#getAbsolutePath()}，依赖
 * {@code FileGeneratorTool.normalizeParameters} 用 {@code new File(fp).isAbsolute()}
 * 判绝对路径（Android 的 "/storage/…" 与桌面单测的 "C:\…"、"/tmp/…" 都算绝对）。
 * 若哪天判定退回 {@code startsWith("/")}，Windows 上的本地单测会把这里的路径当相对名塞进
 * Agent 工作区，而 {@code AgentWorkspace} 构造读 {@code Environment.isExternalStorageManager()}
 * 在 Robolectric 4.10.3 下会抛 {@code ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0}
 * （真机不会），三条用例会一起变红 —— 那时该修的是路径判定口径，不是这套用例。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class FileGeneratorDiffProducerTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "gen-" + System.nanoTime());
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
    public void overwriteExistingFileAttachesDiff() {
        File file = new File(dir, "report.md");
        writeRaw(file, "old line\nsecond");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "create");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "new line\nsecond");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        Map<String, Object> diff = asMap(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
        assertNotNull("覆盖写应带 diff 载荷", diff);
        assertEquals("old line\nsecond", diff.get("before"));
        assertEquals("new line\nsecond", diff.get("after"));
    }

    @Test
    public void appendExistingFileAttachesDiff() {
        File file = new File(dir, "notes.txt");
        writeRaw(file, "first");

        Map<String, Object> params = new HashMap<>();
        params.put("action", "append");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "second");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        Map<String, Object> diff = asMap(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
        assertNotNull("追加应带 diff 载荷", diff);
        assertEquals("first", diff.get("before"));
        assertEquals("first\nsecond", diff.get("after"));
    }

    @Test
    public void newFileHasNoDiffPayload() {
        File file = new File(dir, "new.txt");
        Map<String, Object> params = new HashMap<>();
        params.put("action", "create");
        params.put("file_path", file.getAbsolutePath());
        params.put("content", "hello");

        AIToolResult result = new FileGeneratorTool(context).execute(params);
        assertOk(result);
        assertTrue("新建文件应真的落盘: " + file.getAbsolutePath(), file.isFile());
        assertNull("新建文件不该挂 diff 卡",
                result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
    }

    /** 失败时把工具自己的错误/结果文本带进断言消息，避免"只看到 false" */
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
