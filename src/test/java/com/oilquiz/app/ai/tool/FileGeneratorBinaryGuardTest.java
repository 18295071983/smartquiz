package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
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
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * file_generator 的二进制写护栏（2026-10-29，用户取向：宁可当场报错，也不能把文件写坏）。
 *
 * <p>动机就来自真机那条会话：{@code python_file_ops} 的路由歧义一度把模型推向"退回 write 整篇覆盖"，
 * 而 {@code .docx/.xlsx/.pdf} 是 zip/二进制容器——按文本写进去等于直接毁掉原件。这些格式其实
 * 都有专用工具（excel_tool / python_execute + python-docx·pptx·reportlab），所以拦下来同时必须指路。</p>
 *
 * <p>同时钉住两件不能误伤的：文本类后缀照常能写；{@code copy} 是逐字节复制，写 .docx 副本合法。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class FileGeneratorBinaryGuardTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "fgbinary-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------- 1. 静态判定：按后缀拦、指路对 ----------

    @Test
    public void officeAndMediaExtensionsAreRefusedWithPointers() {
        assertRefused("report.docx", "python-docx");
        assertRefused("deck.pptx", "python-pptx");
        assertRefused("ledger.xlsx", "excel_tool");
        assertRefused("ledger.xls", "excel_tool");
        assertRefused("spec.pdf", "reportlab");
        assertRefused("bundle.zip", "zipfile");
        assertRefused("photo.PNG", null);          // 大小写不敏感
        assertRefused("clip.mp4", null);
        assertRefused("app.apk", "export_apk");
    }

    @Test
    public void textExtensionsAreNeverBlocked() {
        for (String name : new String[]{"a.md", "a.txt", "a.csv", "a.json", "a.xml",
                "a.yaml", "a.properties", "a.srt", "a.html", "a.py", "a.log"}) {
            assertNull(name + " 不该被拦", FileGeneratorTool.binaryGuardMessage(new File(dir, name).getAbsolutePath()));
        }
    }

    /** 版本备份与取回路径不能被拦：xxx.md.ver1 的"扩展名"是 ver1，护栏要放行 */
    @Test
    public void versionBackupsStayWritableForRecovery() {
        assertNull(FileGeneratorTool.binaryGuardMessage(new File(dir, "report.md.ver1").getAbsolutePath()));
        assertNull(FileGeneratorTool.binaryGuardMessage(new File(dir, "notes.txt.ver5").getAbsolutePath()));
    }

    /** 后缀不像文本、但文件里开头就有 NUL —— 也要认出二进制（无扩展名或怪扩展名） */
    @Test
    public void nulBytesMakeAnUnknownExtensionLookBinary() throws Exception {
        File weird = new File(dir, "台账");               // 无扩展名
        try (FileOutputStream out = new FileOutputStream(weird)) {
            out.write(new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0});
        }
        String msg = FileGeneratorTool.binaryGuardMessage(weird.getAbsolutePath());
        assertNotNull("含 NUL 的无扩展名文件应被判成二进制", msg);
        assertTrue(msg.contains("二进制"));
    }

    // ---------- 2. 端到端：真被拦下时不落盘 ----------

    @Test
    public void writeRefusesToOverwriteAnOfficeDocument() {
        File target = new File(dir, "台账.docx");
        AIToolResult r = run("write", target, "正文内容");
        assertFalse("写 .docx 必须失败", r.isSuccess());
        assertTrue("报错要指路 python-docx，实际: " + r.getErrorMessage(),
                r.getErrorMessage().contains("python-docx"));
        assertFalse("更不许把空壳落盘（那才是真的写坏）", target.exists());
    }

    @Test
    public void createRefusesBinaryContainerToo() {
        File target = new File(dir, "sheet.xlsx");
        AIToolResult r = run("create", target, "a,b\n1,2\n");
        assertFalse(r.isSuccess());
        assertTrue("要提到 excel_tool: " + r.getErrorMessage(), r.getErrorMessage().contains("excel_tool"));
        assertFalse(target.exists());
    }

    @Test
    public void appendAndEditAlsoGuarded() {
        File target = new File(dir, "doc.pdf");
        assertFalse(run("append", target, "追加").isSuccess());
        assertFalse(run("edit", target, "x").isSuccess());
        assertFalse(target.exists());
    }

    @Test
    public void csvStillWrites() throws Exception {
        File target = new File(dir, "台账数据.csv");
        AIToolResult r = run("write", target, "位号,振动\nP-2101B,2.8\n");
        assertTrue("CSV 是文本，必须照常能写: " + r.getErrorMessage(), r.isSuccess());
        assertTrue(target.exists());
        String s = new String(Files.readAllBytes(target.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(s.contains("P-2101B"));
    }

    /** copy 是逐字节复制，写 .docx 副本合法——护栏不能误伤这条 */
    @Test
    public void copyOfBinaryContainerIsAllowed() throws Exception {
        File src = new File(dir, "origin.docx");
        Files.write(src.toPath(), new byte[]{'P', 'K', 3, 4, 0, 1});
        File dst = new File(dir, "backup.docx");
        Map<String, Object> p = new HashMap<>();
        p.put("action", "copy");
        p.put("source", src.getAbsolutePath());
        p.put("destination", dst.getAbsolutePath());
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertTrue("copy 不该被二进制护栏拦下: " + r.getErrorMessage(), r.isSuccess());
        assertTrue(dst.exists());
    }

    // ---------- helpers ----------

    private AIToolResult run(String action, File target, String content) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", action);
        p.put("file_path", target.getAbsolutePath());
        p.put("content", content);
        if ("edit".equals(action)) {
            p.put("old_text", "x");
            p.put("new_text", "y");
        }
        return new FileGeneratorTool(context).execute(p);
    }

    private static void assertRefused(String fileName, String mustMention) {
        String msg = FileGeneratorTool.binaryGuardMessage("/sdcard/Download/OilQuiz/files/" + fileName);
        assertNotNull(fileName + " 应被拦下", msg);
        if (mustMention != null) {
            assertTrue(fileName + " 的报错要提到 " + mustMention + "，实际: " + msg,
                    msg.contains(mustMention));
        }
    }
}
