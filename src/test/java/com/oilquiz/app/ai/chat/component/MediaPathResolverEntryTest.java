package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.SystemUIComponentTool;
import com.oilquiz.app.ai.tool.ToolSchemaExtractor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 卡片条目定位口径（2026-10-23 修真机 file_list"每条链接都打不开"）。
 *
 * <p>模型写卡片时把 {@code path} 给成<b>目录</b>、文件名只留在 {@code name} 里
 * （真机会话原样：{@code path="/storage/.../agent_workspace/files/"} +
 * {@code name="李凡_末日手艺人_7698807827401362726.mp4"}）。渲染端按 path 打开就是
 * "暂不支持打开文件夹"，卡片外观一切正常。本测试钉住补齐规则，特别是两条最容易做错的：
 * <ul>
 *   <li><b>目录行不能被"补齐"坏</b>：{@code navigate()} 生成的目录条目 name 就是目录自己的名字，
 *       无脑 {@code dir+name} 会拼出不存在的 {@code 目录/目录名}；</li>
 *   <li><b>省略号截断名只在唯一命中时才认</b>：模型爱写 {@code 前缀...后缀}，多义时必须放弃，
 *       否则点开的是别人的文件。</li>
 * </ul>
 *
 * <p>顺带钉住"教给模型的字段口径"与"渲染端真读的字段"不出第二份文案：
 * props 说明的三处抄本（注解 / getParameterDescriptions / AIToolManager）共用
 * {@link ComponentTypes#PROPS_DOC_FILE_CARDS}，前两处在运行时可校验，就校验这两处。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MediaPathResolverEntryTest {

    private File root;

    @Before
    public void makeTree() throws Exception {
        root = Files.createTempDirectory("mpr-entry").toFile();
        File files = new File(root, "files");
        assertTrue(files.mkdirs());
        touch(new File(files, "李凡_末日手艺人_7698807827401362726.mp4"));
        touch(new File(files,
                "抖音_李凡_末日手艺人斩诡强化万物 我叫顾满，穿来的。睁眼就是灰雾遮天_7698807827401362726.mp4"));
        touch(new File(files, "report.md"));
        File sub = new File(files, "images");
        assertTrue(sub.mkdirs());
        touch(new File(sub, "chart.png"));
        touch(new File(root, "top.txt"));
    }

    @After
    public void cleanUp() {
        deleteRecursively(root);
    }

    // ==================== 目录 + 名字补齐 ====================

    /** 真机形态：path 是目录、文件名只在 name 里 → 补齐成目录里的真实文件 */
    @Test
    public void directoryPathPlusNameResolvesToTheFile() {
        File files = new File(root, "files");
        File hit = MediaPathResolver.resolveEntryToFile(null,
                files.getAbsolutePath() + "/", "report.md");
        assertNotNull("目录串+文件名没补齐", hit);
        assertEquals(new File(files, "report.md").getAbsolutePath(), hit.getAbsolutePath());
    }

    /** 尾斜杠可有可无，name 里混进 file:// 或前导斜杠也要认 */
    @Test
    public void joinToleratesTrailingAndLeadingSlashes() {
        File files = new File(root, "files");
        String dir = files.getAbsolutePath();
        assertEquals(new File(files, "report.md").getAbsolutePath(),
                MediaPathResolver.resolveEntryToFile(null, dir, "report.md").getAbsolutePath());
        assertEquals(new File(files, "report.md").getAbsolutePath(),
                MediaPathResolver.resolveEntryToFile(null, dir + "///", "/report.md").getAbsolutePath());
        assertEquals(dir + "/report.md", MediaPathResolver.childPath(dir + "/", "/report.md"));
        assertEquals(dir + "/report.md", MediaPathResolver.childPath(dir, "files/report.md"));
    }

    /** 目录行必须还是目录：navigate() 生成的条目 name 就是目录自己的名字，不能拼成 目录/目录名 */
    @Test
    public void directoryEntryStaysADirectory() {
        File files = new File(root, "files");
        File hit = MediaPathResolver.resolveEntryToFile(null,
                files.getAbsolutePath(), "files");
        assertNotNull(hit);
        assertTrue("目录行被补齐成了不存在的 目录/目录名", hit.isDirectory());
        assertEquals(files.getAbsolutePath(), hit.getAbsolutePath());
    }

    /** 子目录条目（path=父目录，name=子目录名）→ 解析到子目录，卡片仍走行内导航 */
    @Test
    public void subdirectoryEntryResolvesToDirectory() {
        File files = new File(root, "files");
        File hit = MediaPathResolver.resolveEntryToFile(null,
                files.getAbsolutePath(), "images");
        assertNotNull(hit);
        assertTrue(hit.isDirectory());
        assertEquals(new File(files, "images").getAbsolutePath(), hit.getAbsolutePath());
    }

    /** path 本来就指到文件时用 path，name 不许把它带偏（模型的 name 常是展示用别名） */
    @Test
    public void existingFilePathWinsOverName() {
        File real = new File(root, "files/report.md");
        File hit = MediaPathResolver.resolveEntryToFile(null,
                real.getAbsolutePath(), "季度报告.md");
        assertEquals(real.getAbsolutePath(), hit.getAbsolutePath());
    }

    /** path 空、只有 name 且 name 是全路径时按 name 定位（file_card 只给名字的卡片） */
    @Test
    public void nameOnlyAbsolutePathResolves() {
        File real = new File(root, "top.txt");
        File hit = MediaPathResolver.resolveEntryToFile(null, "", real.getAbsolutePath());
        assertEquals(real.getAbsolutePath(), hit.getAbsolutePath());
    }

    // ==================== 省略号截断名 ====================

    /** 真机第二行就是截断名：前缀...后缀 唯一命中 → 还原成磁盘上的文件 */
    @Test
    public void ellipsisNameResolvedWhenUnique() {
        File files = new File(root, "files");
        String truncated = "抖音_李凡_末日手艺人斩诡强化万物..._7698807827401362726.mp4";
        File hit = MediaPathResolver.resolveEntryToFile(null,
                files.getAbsolutePath(), truncated);
        assertNotNull("省略号截断名没还原", hit);
        assertTrue(hit.isFile());
        assertTrue(hit.getName().startsWith("抖音_李凡_末日手艺人斩诡强化万物"));
        assertTrue(hit.getName().endsWith("_7698807827401362726.mp4"));
    }

    /** 两头都对上但命中两个 → 不猜（宁可只进目录，也别打开错文件） */
    @Test
    public void ellipsisNameWithTwoMatchesIsDropped() {
        List<String> cands = Arrays.asList("AB_cd_1.txt", "AB_xy_1.txt");
        assertNull(MediaPathResolver.matchEllipsisName("AB...1.txt", cands));
        assertEquals("AB_cd_1.txt", MediaPathResolver.matchEllipsisName("AB...cd_1.txt", cands));
    }

    /** 只有前缀（"很长的名字..."）等于没有约束；只有后缀同理 → 都不认 */
    @Test
    public void ellipsisNeedsBothPrefixAndSuffix() {
        List<String> cands = Arrays.asList("a.txt", "b.txt");
        assertNull(MediaPathResolver.matchEllipsisName("...txt", cands));
        assertNull(MediaPathResolver.matchEllipsisName("a.txt...", cands));
        assertNull(MediaPathResolver.matchEllipsisName("a.txt", cands));   // 没有省略号不走这条
    }

    /** 中文省略号与双省略号也要认（模型两种都写过） */
    @Test
    public void unicodeEllipsisAlsoMatches() {
        List<String> cands = Arrays.asList("银川天气汇报画板_0911.html");
        assertEquals("银川天气汇报画板_0911.html",
                MediaPathResolver.matchEllipsisName("银川天气汇报画板…0911.html", cands));
        assertEquals("银川天气汇报画板_0911.html",
                MediaPathResolver.matchEllipsisName("银川天气汇报画板‥0911.html", cands));
    }

    /** 目录里根本没有对应文件 → 交回目录本身，行仍可"进入目录" */
    @Test
    public void missingNameFallsBackToDirectory() {
        File files = new File(root, "files");
        File hit = MediaPathResolver.resolveEntryToFile(null,
                files.getAbsolutePath(), "并不存在的文件.mp4");
        assertNotNull(hit);
        assertTrue("补齐失败时应该退回目录", hit.isDirectory());
    }

    /** 解析不到实体的字符串原样回传，不能编一个路径出来 */
    @Test
    public void resolveEntryPathKeepsOriginalWhenNothingExists() {
        String ghost = new File(root, "ghost").getAbsolutePath();
        assertEquals(ghost, MediaPathResolver.resolveEntryPath(null, ghost, "x.mp4"));
        assertEquals("", MediaPathResolver.resolveEntryPath(null, "", ""));
    }

    // ==================== 纯字符串工具 ====================

    @Test
    public void bareNameStripsPrefixesAndTrailingSlash() {
        assertEquals("report.md", MediaPathResolver.bareName("/storage/a/b/report.md"));
        assertEquals("report.md", MediaPathResolver.bareName("/storage/a/b/report.md/"));
        assertEquals("x.png", MediaPathResolver.bareName("file:///sdcard/img/x.png"));
        assertEquals("", MediaPathResolver.bareName(null));
        assertEquals("", MediaPathResolver.bareName("   "));
    }

    @Test
    public void childPathHandlesEmptySides() {
        assertEquals("a/b.md", MediaPathResolver.childPath("a/", "/b.md"));
        assertEquals("a", MediaPathResolver.childPath("a/", ""));
        assertEquals("b.md", MediaPathResolver.childPath("", "b.md"));
    }

    // ==================== 教给模型的口径只有一份 ====================

    /**
     * props 说明的两条模型可见出口（注解路径与 getParameterDescriptions 路径）必须带上
     * 同一句文件类卡片口径——这两处此前各抄一份、改一漏一，本测试就是防止再长出第三份手抄本。
     */
    @Test
    public void propsDocTeachesOneSharedFileCardCaliber() {
        SystemUIComponentTool tool = new SystemUIComponentTool(
                ApplicationProvider.getApplicationContext());
        String viaMap = String.valueOf(tool.getParameterDescriptions().get("props"));
        String viaAnnotation = String.valueOf(
                ToolSchemaExtractor.extract(tool).paramDesc);
        assertTrue("getParameterDescriptions 未引用共享口径",
                viaMap.contains(ComponentTypes.PROPS_DOC_FILE_CARDS));
        assertTrue("注解路径未引用共享口径",
                viaAnnotation.contains(ComponentTypes.PROPS_DOC_FILE_CARDS));
        // 口径本身必须说到"完整路径/不能只给目录"，否则等于没教
        assertTrue(ComponentTypes.PROPS_DOC_FILE_CARDS.contains("完整"));
        assertTrue(ComponentTypes.PROPS_DOC_FILE_CARDS.contains("不能只给目录"));
        // 渲染端真读的字段都得教到，少一个就是下一批"卡片渲染不出"的种子
        for (String field : new String[]{"name", "path", "size", "type", "files", "title"}) {
            assertTrue("口径缺字段 " + field, ComponentTypes.PROPS_DOC_FILE_CARDS.contains(field));
        }
    }

    /** 目录（ComponentTypes.SCHEMA）里的 file_list 条目也得教到路径口径，且必填字段名不变 */
    @Test
    public void catalogTeachesPathCaliberForFileList() throws Exception {
        JSONArray catalog = ComponentTypes.catalogJson();
        JSONObject entry = null;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject o = catalog.optJSONObject(i);
            if (o != null && "file_list".equals(o.optString("type"))) {
                entry = o;
                break;
            }
        }
        assertNotNull("目录里没有 file_list", entry);
        assertEquals("文件 files[{name,path,size,type}]", entry.optString("required"));
        assertTrue("目录没教路径口径: " + entry.optString("note"),
                entry.optString("note").contains("完整路径"));
        // 卡片闸门仍只认 files（必填字段名一旦被改，模型照目录填就渲染不出）
        assertTrue(new FileListCardView().canRender(
                ComponentData.of("file_list", new JSONObject().put("files", new JSONArray()))));
    }

    // ---- helpers ----

    private static void touch(File f) throws Exception {
        assertTrue("创建测试文件失败: " + f, f.createNewFile());
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
