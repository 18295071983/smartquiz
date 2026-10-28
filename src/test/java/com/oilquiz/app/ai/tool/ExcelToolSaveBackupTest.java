package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.annotation.Tool;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code excel_tool} 原地保存前的版本备份（2026-10-29）。
 *
 * <p>这是本轮最实在的一条数据丢失口子：真机上用户让模型改台账 xlsx，{@code write_cell}
 * 不传 {@code output_path} 就<b>写回原文件</b>，改错一格等于覆盖掉整本台账，而原先
 * 只有 {@code file_generator} 会留 {@code .verN}——xlsx 恰恰是 file_generator 现在
 * <b>拒绝</b>写的那类文件（按文本写等于写坏），所以真正的写者反而没有兜底。</p>
 *
 * <p>这里直接调私有的 {@code saveWorkbook}：备份这件事属于"落盘"这一层，测 execute
 * 要绕一整套路径解析，绕不过的恰恰是这次改的那三行。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ExcelToolSaveBackupTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "excelver-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
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

    /** 建一个 A1=值 的 xlsx 落盘 */
    private File writeWorkbook(File target, String cellValue) throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("台账");
        sheet.createRow(0).createCell(0).setCellValue(cellValue);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            wb.write(fos);
        }
        wb.close();
        return target;
    }

    private String readA1(File source) throws Exception {
        Workbook wb = new XSSFWorkbook(new FileInputStream(source));
        try {
            return wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
        } finally {
            wb.close();
        }
    }

    /** 反射调用私有 saveWorkbook(workbook, parameters, source) */
    private File saveWorkbook(File source, Map<String, Object> parameters) throws Exception {
        ExcelTool tool = new ExcelTool(context);
        Method m = ExcelTool.class.getDeclaredMethod("saveWorkbook", Workbook.class, Map.class, File.class);
        m.setAccessible(true);
        Workbook wb = new XSSFWorkbook(new FileInputStream(source));
        try {
            wb.getSheetAt(0).getRow(0).getCell(0).setCellValue("NEW");
            return (File) m.invoke(tool, wb, parameters, source);
        } finally {
            wb.close();
        }
    }

    // ---------- 1. 原地保存必须留底 ----------

    @Test
    public void inPlaceSaveBacksUpThePreviousVersion() throws Exception {
        File ledger = writeWorkbook(new File(dir, "台账.xlsx"), "OLD");

        File saved = saveWorkbook(ledger, new HashMap<String, Object>());

        assertEquals("不传 output_path 就写回原文件", ledger, saved);
        assertEquals("NEW", readA1(ledger));

        File ver1 = FileVersionBackup.versionFile(ledger, 1);
        assertTrue("原地覆盖前必须留 原名.ver1（改坏台账要能退回去）", ver1.exists());
        assertEquals("OLD", readA1(ver1));
    }

    @Test
    public void secondInPlaceSaveRotatesVersions() throws Exception {
        File ledger = writeWorkbook(new File(dir, "台账.xlsx"), "V1");
        saveWorkbook(ledger, new HashMap<String, Object>());   // ver1=V1
        // 再改一次（此时 ver1 里是 V1，当前是 NEW）
        FileVersionBackup.backup(ledger);                       // ver1=NEW, ver2=V1

        assertEquals("NEW", readA1(FileVersionBackup.versionFile(ledger, 1)));
        assertEquals("V1", readA1(FileVersionBackup.versionFile(ledger, 2)));
    }

    // ---------- 2. 另存为：备份的是"被覆盖的那个目标"，不是源 ----------

    @Test
    public void saveAsBacksUpAnExistingTargetFile() throws Exception {
        File source = writeWorkbook(new File(dir, "台账.xlsx"), "SRC");
        File other = writeWorkbook(new File(dir, "月度汇总.xlsx"), "OTHER");

        Map<String, Object> params = new HashMap<>();
        params.put("output_path", other.getAbsolutePath());
        File saved = saveWorkbook(source, params);

        assertEquals(other, saved);
        assertTrue("目标已存在→覆盖前留底", FileVersionBackup.versionFile(other, 1).exists());
        assertEquals("OTHER", readA1(FileVersionBackup.versionFile(other, 1)));
        assertFalse("源文件不该被写，也不该留底", FileVersionBackup.versionFile(source, 1).exists());
        assertEquals("SRC", readA1(source));
    }

    @Test
    public void saveToBrandNewPathLeavesNoVersion() throws Exception {
        File source = writeWorkbook(new File(dir, "台账.xlsx"), "SRC");
        File fresh = new File(dir, "导出.xlsx");

        Map<String, Object> params = new HashMap<>();
        params.put("output_path", fresh.getAbsolutePath());
        File saved = saveWorkbook(source, params);

        assertEquals(fresh, saved);
        assertFalse("新建文件没有'上一版'可留", FileVersionBackup.versionFile(fresh, 1).exists());
    }

    // ---------- 3. 备份要能被 workspace(action=versions/restore) 找到 ----------

    @Test
    public void excelVersionsAreDiscoverableByTheSharedApi() throws Exception {
        File ledger = writeWorkbook(new File(dir, "台账.xlsx"), "OLD");
        saveWorkbook(ledger, new HashMap<String, Object>());

        assertEquals(1, FileVersionBackup.versions(ledger).size());
        assertNull(FileVersionBackup.restore(ledger, 1));
        assertEquals("OLD", readA1(ledger));
    }

    // ---------- 4. 模型可见性：光做不说等于没做 ----------

    @Test
    public void backupIsAdvertisedOnEverySchemaChannel() {
        ExcelTool tool = new ExcelTool(context);
        String runtime = tool.getDescription();
        String annotated = ExcelTool.class.getAnnotation(Tool.class) != null
                ? ExcelTool.class.getAnnotation(Tool.class).description() : "";
        Map<String, String> params = tool.getParameterDescriptions();

        for (String channel : new String[]{runtime, annotated}) {
            assertTrue("描述要说清覆盖前留 .verN: " + channel, channel.contains(".ver"));
            assertTrue("描述要指路到 restore: " + channel, channel.contains("restore"));
        }
        assertNotNull(params.get("output_path"));
        assertTrue("output_path 要说明会被备份的是哪个文件",
                params.get("output_path").contains("备份"));
    }
}
