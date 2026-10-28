package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ai.agent.online.AgentWorkspace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机(instrumented)验证 {@code workspace(action=versions/restore)} 与 {@code excel_tool} 的覆盖前备份。
 *
 * <p>为什么必须上真机：这套逻辑的唯一入口是 {@link AgentWorkspace}，而它的构造读
 * {@code Environment.isExternalStorageManager()} —— Robolectric 4.10.3 下会抛
 * {@code ArrayIndexOutOfBoundsException}（真机不会）。于是"版本定位、区内边界检查、restore 落盘"
 * 三件事在本地单测里都跑不到，只能在这里补。</p>
 *
 * <p>运行（本机 MIUI 会拦 Gradle 自动安装，报 INSTALL_FAILED_USER_RESTRICTED，所以用 am instrument）：</p>
 * <pre>
 *   adb install -r build\outputs\apk\debug\*.apk
 *   adb install -r build\outputs\apk\androidTest\debug\*.apk
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.WorkspaceVersionsRestoreDeviceTest \
 *     com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 * 单个用例：{@code -e class ...WorkspaceVersionsRestoreDeviceTest#方法名}
 */
@RunWith(AndroidJUnit4.class)
public class WorkspaceVersionsRestoreDeviceTest {

    private Context context;
    private AgentWorkspace ws;
    private File target;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ws = AgentWorkspace.getInstance(context);
        // 唯一名，绝不碰用户真实台账；落在长期区 files/（与 file_generator 默认写盘同侧）
        target = new File(ws.getFilesDir(), "_vercheck_" + System.nanoTime() + ".md");
    }

    @After
    public void tearDown() {
        for (int n = 1; n <= FileVersionBackup.MAX_VERSIONS; n++) {
            File v = FileVersionBackup.versionFile(target, n);
            //noinspection ResultOfMethodCallIgnored
            v.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    private void write(String content) throws Exception {
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
    }

    private AIToolResult workspace(String action, String fileName, Integer version, Boolean confirm) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", action);
        p.put("fileName", fileName);
        if (version != null) p.put("version", version);
        if (confirm != null) p.put("confirm", confirm);
        return new WorkspaceTool(context).execute(p);
    }

    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : null;
    }

    // ---------- 1. versions 看得见 .verN ----------

    @Test
    public void versionsListsTheBackupsThatFileGeneratorLeft() throws Exception {
        write("第一版内容");
        FileVersionBackup.backup(target);      // ver1 = 第一版内容
        write("第二版内容");
        FileVersionBackup.backup(target);      // ver1 = 第二版，ver2 = 第一版
        write("第三版内容");

        AIToolResult r = workspace("versions", target.getName(), null, null);
        assertTrue("versions 调用失败: " + r.getErrorMessage(), r.isSuccess());
        Map<?, ?> out = asMap(r.getResult());
        assertNotNull(out);
        assertEquals(2, ((Number) out.get("count")).intValue());
        String message = String.valueOf(out.get("message"));
        assertTrue("要把 restore 的调用方式告诉模型: " + message, message.contains("restore"));
        assertTrue(message.contains(target.getName()));
    }

    @Test
    public void versionsOnUnknownFileSaysNoBackupsInsteadOfError() throws Exception {
        AIToolResult r = workspace("versions", "根本没这个文件.md", null, null);
        assertTrue("没备份不该报错，要给出可行动说明: " + r.getErrorMessage(), r.isSuccess());
        Map<?, ?> out = asMap(r.getResult());
        assertEquals(0, ((Number) out.get("count")).intValue());
        assertTrue(String.valueOf(out.get("message")).contains("没有"));
    }

    // ---------- 2. restore：先确认，再覆盖，覆盖前还留底 ----------

    @Test
    public void restoreRequiresConfirmBeforeTouchingAnything() throws Exception {
        write("第一版");
        FileVersionBackup.backup(target);
        write("第二版");

        AIToolResult r = workspace("restore", target.getName(), 1, null);
        assertFalse("没带 confirm 必须拒绝（防 prompt 注入把用户文件回退掉）", r.isSuccess());
        assertTrue(String.valueOf(r.getErrorMessage()).contains("confirm"));
        assertEquals("被拒绝之后内容不许变", "第二版", read());
    }

    @Test
    public void restoreRollsBackAndKeepsCurrentContentRecoverable() throws Exception {
        write("第一版");
        FileVersionBackup.backup(target);      // ver1 = 第一版
        write("第二版");

        AIToolResult r = workspace("restore", target.getName(), 1, true);
        assertTrue("restore 失败: " + r.getErrorMessage(), r.isSuccess());
        assertEquals("第一版", read());

        // 恢复前的"第二版"也必须还在，退错了还能再退回来
        File ver1 = FileVersionBackup.versionFile(target, 1);
        assertTrue(ver1.exists());
        assertEquals("第二版", new String(Files.readAllBytes(ver1.toPath()), StandardCharsets.UTF_8));

        AIToolResult back = workspace("restore", target.getName(), 1, true);
        assertTrue(back.isSuccess());
        assertEquals("第二版", read());
    }

    @Test
    public void restoreOfMissingVersionFailsWithoutChangingContent() throws Exception {
        write("只有当前版");

        AIToolResult r = workspace("restore", target.getName(), 4, true);
        assertFalse("没有第 4 版要报错而不是写出空文件", r.isSuccess());
        assertTrue(String.valueOf(r.getErrorMessage()).contains("versions"));
        assertEquals("只有当前版", read());
    }

    // ---------- 3. 越界名字不许动 ----------

    @Test
    public void absolutePathOutsideWorkspaceCannotBeRestored() throws Exception {
        File outside = new File(context.getFilesDir().getParentFile(), "outside_probe.md");
        AIToolResult r = workspace("restore", outside.getAbsolutePath(), 1, true);
        assertFalse(outside.exists());
        if (r.isSuccess()) {
            // 允许"按文件名收回工作区内"，但落点必须在区内
            Map<?, ?> out = asMap(r.getResult());
            String path = String.valueOf(out.get("path"));
            String wsRoot = ws.getWorkspaceDir().getCanonicalPath();
            assertTrue("restore 落点越界: " + path,
                    new File(path).getCanonicalPath().startsWith(wsRoot));
        }
    }

    // ---------- 4. 备份确实进了工作区面板能看到的目录 ----------

    @Test
    public void listShowsTheVersionFilesSoTheyAreNotInvisible() throws Exception {
        write("第一版");
        FileVersionBackup.backup(target);
        write("第二版");

        AIToolResult r = new WorkspaceTool(context).execute(mapOf("action", "list"));
        assertTrue(r.isSuccess());
        StringBuilder names = new StringBuilder();
        Map<?, ?> out = asMap(r.getResult());
        Object files = out != null ? out.get("files") : null;
        if (files instanceof java.util.List) {
            for (Object o : (java.util.List<?>) files) {
                Map<?, ?> item = asMap(o);
                if (item != null) names.append(item.get("name")).append('\n');
            }
        }
        assertTrue("list 里要能看到 xxx.ver1（否则用户以为备份丢了）: " + names,
                names.indexOf(target.getName() + ".ver1") >= 0);
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
