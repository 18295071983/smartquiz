package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code workspace(action=versions/restore)}（2026-10-29）。
 *
 * <p>动机：{@code file_generator} 覆盖写前会留 {@code 原名.ver1..ver5}，但模型完全不知道有这套底，
 * 也没有取回入口——真机上唯一可行的说法是"读回 .verN 再 write 回去"，等于让用户自己当版本管理器。
 * 现在 {@code excel_tool} 的原地保存也接上同一份备份，两个写者共用命名，所以列版本/回退
 * 必须有单一入口，且必须能按同一套规则把备份找回来。</p>
 *
 * <p>这一层不碰 {@code AgentWorkspace} 实例：它的构造在 Robolectric 下会抛
 * {@code ArrayIndexOutOfBoundsException}（{@code Environment.isExternalStorageManager()} 未被 shadow，
 * 真机不会）。所以这里测"找版本"的纯内核 + 参数表漂移，端到端留给
 * {@code WorkspaceVersionsRestoreDeviceTest}。</p>
 */
public class WorkspaceVersionsRestoreTest {

    private static final String SOURCE =
            "src/main/java/com/oilquiz/app/ai/tool/WorkspaceTool.java";

    private File dir;
    private File filesDir;
    private File tmpDir;
    private File rootDir;
    private File[] dirs;

    @Before
    public void setUp() throws Exception {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "wsver-" + System.nanoTime());
        filesDir = new File(dir, "files");
        tmpDir = new File(dir, "tmp");
        rootDir = new File(dir, "ws");
        for (File f : new File[] {filesDir, tmpDir, rootDir}) {
            //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        }
        dirs = new File[] {filesDir, tmpDir, rootDir};
    }

    @After
    public void tearDown() {
        deleteRecursive(dir);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void write(File f, String content) throws IOException {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 1. 找版本：备份在主文件同目录 ----------

    @Test
    public void locatesTheFileThatActuallyHasVersions() throws Exception {
        // 主文件已被删（用户手删或清理），但长期区里留着备份——这正是最需要回退的时刻
        write(new File(filesDir, "台账.xlsx.ver1"), "OLD");
        File loose = new File(rootDir, "台账.xlsx");

        File located = WorkspaceTool.locateVersionedFile(loose, "台账.xlsx", dirs);

        assertEquals("应定位到带备份的那一侧", filesDir, located.getParentFile());
        assertEquals(1, FileVersionBackup.versions(located).size());
    }

    @Test
    public void existingFileWinsOverVersionHunting() throws Exception {
        File live = new File(filesDir, "report.md");
        write(live, "now");

        assertEquals(live.getCanonicalPath(),
                WorkspaceTool.locateVersionedFile(live, "report.md", dirs).getCanonicalPath());
    }

    @Test
    public void nothingFoundStillReturnsAnInWorkspacePath() throws Exception {
        File missing = new File(rootDir, "ghost.md");

        File located = WorkspaceTool.locateVersionedFile(missing, "ghost.md", dirs);

        assertNotNull(located);
        assertTrue(WorkspaceTool.isWithin(located, dirs));
        assertTrue("没备份时调用方拿它去报「没有历史备份」",
                FileVersionBackup.versions(located).isEmpty());
    }

    // ---------- 2. 越界路径：restore 是写操作，只能在区内 ----------

    @Test
    public void absolutePathOutsideWorkspaceIsReRootedOrRejected() throws Exception {
        // 模型常把工作区路径记错成公共目录的另一种写法（/storage/emulated/0/OilQuiz/xxx）
        File outside = new File("/storage/emulated/0/OilQuiz/notes.md");
        assertFalse(WorkspaceTool.isWithin(outside, dirs));

        File redirected = WorkspaceTool.locateVersionedFile(outside, outside.getAbsolutePath(), dirs);
        assertNotNull(redirected);
        assertTrue("越界名只能按 basename 落回工作区，绝不能写到区外",
                WorkspaceTool.isWithin(redirected, dirs));
        assertEquals("notes.md", redirected.getName());

        // 目录不可用时（初始化失败这类），宁可拒绝也不猜路径
        assertNull(WorkspaceTool.locateVersionedFile(outside, outside.getAbsolutePath(), new File[0]));
    }

    @Test
    public void dotDotTraversalStaysInside() throws Exception {
        File tricky = new File(filesDir, "../../escape.md");
        String canonical = tricky.getCanonicalPath();
        if (WorkspaceTool.isWithin(tricky, dirs)) {
            assertTrue("被判为区内就必须真在区内: " + canonical,
                    canonical.startsWith(dir.getCanonicalPath() + File.separator)
                            || canonical.equals(dir.getCanonicalPath()));
        }
        // 越界的穿越名会被按 basename 收回区内
        File redirected = WorkspaceTool.locateVersionedFile(tricky, "../../escape.md", dirs);
        if (redirected != null) {
            assertTrue(WorkspaceTool.isWithin(redirected, dirs));
        }
    }

    // ---------- 3. 模型传来的值：类型与容错 ----------

    @Test
    public void versionParamToleratesTheShapesModelsActuallySend() throws Exception {
        Method m = WorkspaceTool.class.getDeclaredMethod("intParam", Map.class, String.class, int.class);
        m.setAccessible(true);

        assertEquals(Integer.valueOf(3), m.invoke(null, params("version", 3), "version", 1));
        assertEquals(Integer.valueOf(3), m.invoke(null, params("version", "3"), "version", 1));
        assertEquals(Integer.valueOf(2), m.invoke(null, params("version", "2.0"), "version", 1));
        assertEquals("缺省=最近一版", Integer.valueOf(1),
                m.invoke(null, new java.util.HashMap<String, Object>(), "version", 1));
        assertEquals("垃圾值回落默认，而不是报错后什么都不做",
                Integer.valueOf(1), m.invoke(null, params("version", "最新"), "version", 1));

        Method s = WorkspaceTool.class.getDeclaredMethod("strParam", Map.class, String.class);
        s.setAccessible(true);
        assertEquals("a.md", s.invoke(null, params("fileName", "  a.md  "), "fileName"));
        assertEquals("", s.invoke(null, new java.util.HashMap<String, Object>(), "fileName"));
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(key, value);
        return map;
    }

    // ---------- 4. 四条可见通道 + execute 的 case 不许漂 ----------

    /**
     * 新增 action 时最容易漏的就是"代码里有 case，参数表里没有"——模型看不见就不会调。
     * 这里按源码把四处对齐：execute 的 case ⊆ 线上 enum ⊆ 注解 @Action ⊆ getParameterDescriptions。
     */
    @Test
    public void everyExecuteActionIsAdvertisedToTheModel() throws Exception {
        Set<String> inSwitch = actionsInSource();
        Set<String> inEnum = new LinkedHashSet<>(
                Arrays.asList(actionEnumFromDefinitions()));
        Set<String> inAnnotation = new LinkedHashSet<>();
        Tool tool = WorkspaceTool.class.getAnnotation(Tool.class);
        assertNotNull("workspace 类上少了 @Tool", tool);
        for (Action a : tool.actions()) inAnnotation.add(a.name());

        // switch 里每个动作都要在模型看得见的两处枚举里
        assertTrue("execute 有 case 但线上 enum 没教: " + diff(inSwitch, inEnum),
                inEnum.containsAll(inSwitch));
        assertTrue("execute 有 case 但 @Action 没教: " + diff(inSwitch, inAnnotation),
                inAnnotation.containsAll(inSwitch));
        // 反向：枚举不能吹没实现的动作
        assertTrue("enum 里有 execute 没实现的动作: " + diff(inEnum, inSwitch),
                inSwitch.containsAll(inEnum));

        assertTrue(inSwitch.contains("versions"));
        assertTrue(inSwitch.contains("restore"));

        // 描述层也要说到，否则模型不会主动想到能回退
        WorkspaceTool instance = new WorkspaceTool();
        assertTrue("getDescription 没提 versions/restore", instance.getDescription().contains("versions"));
        assertTrue("getDescription 没提 versions/restore", instance.getDescription().contains("restore"));
        Map<String, String> descriptions = instance.getParameterDescriptions();
        assertTrue(descriptions.get("action").contains("versions"));
        for (Param p : tool.params()) {
            if ("action".equals(p.name())) {
                assertTrue("@Param(action) 没列 versions", p.description().contains("versions"));
                assertTrue("@Param(action) 没列 restore", p.description().contains("restore"));
            }
        }
    }

    /** 参数名四处对齐（漏一处就是"看得见用不了"或"用得上看不见"） */
    @Test
    public void parameterNamesMatchAcrossChannels() {
        Set<String> fromAnnotation = new LinkedHashSet<>();
        for (Param p : WorkspaceTool.class.getAnnotation(Tool.class).params()) {
            fromAnnotation.add(p.name());
        }
        Set<String> fromDefinitions = new LinkedHashSet<>();
        for (ParamDefinition d : new WorkspaceTool().getParameterDefinitions()) {
            fromDefinitions.add(d.getName());
        }
        Set<String> fromDescriptions = new LinkedHashSet<>(
                new WorkspaceTool().getParameterDescriptions().keySet());

        assertEquals(fromAnnotation, fromDefinitions);
        assertEquals(fromAnnotation, fromDescriptions);
        assertTrue(fromDefinitions.contains("version"));
    }

    private static Set<String> actionsInSource() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        String src = new String(Files.readAllBytes(new File(SOURCE).toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("case\\s+\"([A-Za-z_]+)\"").matcher(src);
        while (m.find()) out.add(m.group(1));
        assertFalse("没从源码解析到任何 case（测试得从模块根目录跑）", out.isEmpty());
        return out;
    }

    private static String[] actionEnumFromDefinitions() {
        for (ParamDefinition d : new WorkspaceTool().getParameterDefinitions()) {
            if ("action".equals(d.getName())) {
                List<String> values = d.getEnumValues();
                assertNotNull("workspace 的 action 没有 enum", values);
                return values.toArray(new String[0]);
            }
        }
        throw new AssertionError("workspace 参数表里没有 action");
    }

    private static List<String> diff(Set<String> a, Set<String> b) {
        List<String> missing = new ArrayList<>();
        for (String s : a) if (!b.contains(s)) missing.add(s);
        return missing;
    }
}
