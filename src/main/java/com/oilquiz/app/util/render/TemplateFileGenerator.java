package com.oilquiz.app.util.render;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 题库标准导入模板生成器。
 * <p>
 * 生成与 {@code FieldMappingRegistry} 标准字段一致的导入模板（含示例行），
 * 供用户下载后按格式填写题目，再通过"直接导入"入库。
 * <p>
 * 支持三种格式：Excel(.xlsx)、CSV(.csv)、JSON(.json)。
 * 表头使用中文标准显示名（题干/选项A/正确答案/题型 等），导入时可被字段映射自动识别。
 * <p>
 * 保存位置：系统「下载/OilQuiz」目录（通过 MediaStore 写入），
 * 用户在系统文件管理器中可直接查看与编辑；不再写入应用私有目录。
 */
public final class TemplateFileGenerator {

    private static final String TAG = "TemplateFileGenerator";

    /** MediaStore 相对子目录：Download/OilQuiz */
    private static final String RELATIVE_SUB_DIR = "OilQuiz";

    /** 标准模板表头（与 FieldMappingRegistry 的 displayName 对应） */
    public static final String[] STANDARD_HEADERS = {
            "题干", "选项A", "选项B", "选项C", "选项D",
            "正确答案", "题型", "分类", "难度", "解析"
    };

    /**
     * 示例数据行，覆盖常见题型，帮助用户理解每列填法。
     * 列顺序与 {@link #STANDARD_HEADERS} 一致。
     */
    public static final String[][] SAMPLE_ROWS = {
            {
                    "中国石油的企业精神是什么？",
                    "爱国", "创业", "求实", "奉献",
                    "A", "单选题", "企业文化", "1", "企业精神是爱国、创业、求实、奉献。"
            },
            {
                    "以下哪些属于安全生产的基本原则？",
                    "安全第一", "预防为主", "综合治理", "盲目蛮干",
                    "ABC", "多选题", "安全生产", "2", "安全生产基本原则包括安全第一、预防为主、综合治理。"
            },
            {
                    "进入生产区域必须佩戴安全帽。",
                    "", "", "", "",
                    "对", "判断题", "安全规范", "1", "进入生产区域必须正确佩戴劳动防护用品。"
            },
            {
                    "原油按密度分类，轻质原油的密度一般小于____克/立方厘米。",
                    "", "", "", "",
                    "0.87", "填空题", "石油知识", "2", "轻质原油密度一般小于0.87克/立方厘米。"
            }
    };

    private TemplateFileGenerator() {
    }

    /** 模板文件名（不含路径） */
    public static String templateFileName(String format) {
        switch (format) {
            case "EXCEL":
                return "题库导入模板.xlsx";
            case "CSV":
                return "题库导入模板.csv";
            case "JSON":
                return "题库导入模板.json";
            default:
                return "题库导入模板.txt";
        }
    }

    /** MIME 类型 */
    private static String mimeType(String format) {
        switch (format) {
            case "EXCEL":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "CSV":
                return "text/csv";
            case "JSON":
                return "application/json";
            default:
                return "text/plain";
        }
    }

    /**
     * 生成指定格式的模板文件并保存到系统「下载/OilQuiz」目录。
     *
     * @param context 上下文
     * @param format  EXCEL / CSV / JSON
     * @return 用户可见的保存路径描述；失败返回 null
     */
    public static String generateToDownloads(Context context, String format) {
        if (context == null) return null;
        try {
            byte[] bytes = buildContent(format);
            if (bytes == null) return null;
            String fileName = templateFileName(format);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return writeToMediaStore(context, format, fileName, bytes);
            } else {
                return writeToLegacyDownloads(format, fileName, bytes);
            }
        } catch (Throwable e) {
            // 捕获 Throwable：POI 在 Android 上可能抛 NoClassDefFoundError（Error 而非 Exception）
            Log.e(TAG, "生成模板失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** API 29+：通过 MediaStore 写入 Download/OilQuiz，文件管理器可见且可编辑 */
    private static String writeToMediaStore(Context context, String format,
                                            String fileName, byte[] bytes) throws Exception {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + RELATIVE_SUB_DIR;

        // 先删除同名旧文件，避免重复生成 "(1)" 后缀副本
        try {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            String[] args = {fileName, relativePath + File.separator};
            context.getContentResolver().delete(collection, selection, args);
            // 兼容部分设备 RELATIVE_PATH 末尾无分隔符的存储方式
            context.getContentResolver().delete(collection, selection, new String[]{fileName, relativePath});
        } catch (Exception ignore) {
            // 删除失败不影响后续插入
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType(format));
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.w(TAG, "MediaStore 插入失败: " + fileName);
            return null;
        }
        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                Log.w(TAG, "打开输出流失败: " + uri);
                return null;
            }
            os.write(bytes);
            os.flush();
        }
        Log.i(TAG, "模板生成成功(MediaStore): " + relativePath + "/" + fileName);
        return "内部存储/Download/" + RELATIVE_SUB_DIR + "/" + fileName;
    }

    /** API 28 及以下：直接写入公共 Download/OilQuiz 目录 */
    private static String writeToLegacyDownloads(String format, String fileName,
                                                 byte[] bytes) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), RELATIVE_SUB_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "创建下载目录失败: " + dir.getAbsolutePath());
            return null;
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        Log.i(TAG, "模板生成成功(File): " + out.getAbsolutePath());
        return out.getAbsolutePath();
    }

    /** 按格式构建模板内容字节 */
    private static byte[] buildContent(String format) throws Exception {
        switch (format) {
            case "EXCEL":
                return buildExcelBytes();
            case "CSV":
                return buildCsvBytes();
            case "JSON":
                return buildJsonBytes();
            default:
                return null;
        }
    }

    /** Excel 模板：内存中构建带表头样式的 xlsx（固定列宽，不调用 autoSizeColumn） */
    private static byte[] buildExcelBytes() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("题库");

            // 表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < STANDARD_HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(STANDARD_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (String[] row : SAMPLE_ROWS) {
                Row r = sheet.createRow(rowIndex++);
                for (int i = 0; i < row.length; i++) {
                    r.createCell(i).setCellValue(row[i]);
                }
            }

            // 固定列宽（禁用 autoSizeColumn：依赖 java.awt，Android 上会崩溃）
            for (int i = 0; i < STANDARD_HEADERS.length; i++) {
                sheet.setColumnWidth(i, 25 * 256);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    /** CSV 模板：UTF-8 带 BOM，便于 Excel 直接打开不乱码 */
    private static byte[] buildCsvBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(String.join(",", STANDARD_HEADERS));
        sb.append("\r\n");
        for (String[] row : SAMPLE_ROWS) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(escapeCsv(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** JSON 模板：对象数组，key 使用中文标准字段名 */
    private static byte[] buildJsonBytes() throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] row : SAMPLE_ROWS) {
            JSONObject jo = new JSONObject();
            for (int i = 0; i < STANDARD_HEADERS.length && i < row.length; i++) {
                jo.put(STANDARD_HEADERS[i], row[i]);
            }
            arr.put(jo);
        }
        return arr.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    /** CSV 字段转义：含逗号/引号/换行的字段用双引号包裹，内部引号翻倍 */
    private static String escapeCsv(String value) {
        if (value == null) return "";
        boolean needQuote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }
}
