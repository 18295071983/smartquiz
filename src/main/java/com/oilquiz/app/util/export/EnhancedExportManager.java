package com.oilquiz.app.util.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.View;

import com.google.gson.Gson;
import com.oilquiz.app.model.Question;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 增强型导出管理器
 * - 进度反馈
 * - 深色模式支持
 * - 多种导出格式
 * - 导出预览
 */
public class EnhancedExportManager {
    private static final String TAG = "EnhancedExportManager";
    private static volatile EnhancedExportManager instance;
    
    private final Context context;
    private final ExecutorService executor;
    private final Gson gson;
    private ExportCallback callback;
    private boolean isDarkMode = false;

    private EnhancedExportManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.gson = new Gson();
    }

    public static synchronized EnhancedExportManager getInstance(Context context) {
        if (instance == null) {
            instance = new EnhancedExportManager(context.getApplicationContext());
        }
        return instance;
    }

    public void setCallback(ExportCallback callback) {
        this.callback = callback;
    }

    public void setDarkMode(boolean darkMode) {
        this.isDarkMode = darkMode;
    }

    // ==================== 导出格式枚举 ====================
    
    public enum ExportFormat {
        JSON("JSON", "json", "application/json"),
        CSV("CSV", "csv", "text/csv"),
        HTML("HTML", "html", "text/html"),
        MARKDOWN("Markdown", "md", "text/markdown"),
        PDF("PDF", "pdf", "application/pdf"),
        WORD("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        EXCEL("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        IMAGE("长图", "png", "image/png"),
        TXT("文本", "txt", "text/plain");

        private final String displayName;
        private final String extension;
        private final String mimeType;

        ExportFormat(String displayName, String extension, String mimeType) {
            this.displayName = displayName;
            this.extension = extension;
            this.mimeType = mimeType;
        }

        public String getDisplayName() { return displayName; }
        public String getExtension() { return extension; }
        public String getMimeType() { return mimeType; }
    }

    // ==================== 主导出方法 ====================

    /**
     * 导出题目列表
     */
    public Future<File> exportQuestions(List<Question> questions, ExportFormat format) {
        return exportQuestions(questions, format, null);
    }

    public Future<File> exportQuestions(List<Question> questions, ExportFormat format, String customFileName) {
        return executor.submit(() -> {
            try {
                // 验证数据
                if (questions == null || questions.isEmpty()) {
                    throw new IllegalArgumentException("题目列表为空");
                }

                // 生成文件名
                String fileName = generateFileName(customFileName, format);
                updateProgress(10, "准备导出...");

                // 根据格式导出
                File result = switch (format) {
                    case JSON -> exportToJSON(questions, fileName);
                    case CSV -> exportToCSV(questions, fileName);
                    case HTML -> exportToHTML(questions, fileName);
                    case MARKDOWN -> exportToMarkdown(questions, fileName);
                    case PDF -> exportToPDF(questions, fileName);
                    case WORD -> exportToWord(questions, fileName);
                    case EXCEL -> exportToExcel(questions, fileName);
                    case IMAGE -> exportToImage(null, fileName);
                    case TXT -> exportToText(questions, fileName);
                };

                updateProgress(100, "导出完成");
                updateSuccess(result);
                
                Log.i(TAG, "导出成功: " + result.getPath());
                return result;

            } catch (Exception e) {
                Log.e(TAG, "导出失败", e);
                updateError("导出失败: " + e.getMessage());
                return null;
            }
        });
    }

    // ==================== 各格式导出实现 ====================

    /**
     * 导出为 JSON
     */
    private File exportToJSON(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在转换为 JSON...");

        Map<String, Object> data = new HashMap<>();
        data.put("exportTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        data.put("totalCount", questions.size());
        data.put("questions", questions);

        String json = gson.toJson(data);

        updateProgress(60, "正在写入文件...");

        File file = createExportFile(fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(json);
        }

        return file;
    }

    /**
     * 导出为 CSV
     */
    private File exportToCSV(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在转换为 CSV...");

        StringBuilder csv = new StringBuilder();
        
        // CSV 表头
        csv.append("ID,题目,选项,答案,解析,分类,难度,题型\n");

        // CSV 数据行
        int processed = 0;
        for (Question q : questions) {
            csv.append(q.getId()).append(",");
            csv.append(escapeCSV(q.getQuestionText())).append(",");
            csv.append(escapeCSV(optionsToString(q.getOptions()))).append(",");
            csv.append(escapeCSV(q.getCorrectAnswer())).append(",");
            csv.append(escapeCSV(q.getExplanation())).append(",");
            csv.append(escapeCSV(q.getCategory())).append(",");
            csv.append(q.getDifficulty()).append(",");
            csv.append(escapeCSV(q.getQuestionType())).append("\n");

            processed++;
            int progress = 20 + (int) (processed * 40.0 / questions.size());
            updateProgress(progress, "正在处理第 " + processed + " 题...");
        }

        updateProgress(60, "正在写入文件...");

        File file = createExportFile(fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(csv.toString());
        }

        return file;
    }

    /**
     * 导出为 HTML
     */
    private File exportToHTML(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在生成 HTML...");

        StringBuilder html = new StringBuilder();
        
        // HTML 头部
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>题库导出 - ").append(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date())).append("</title>\n");
        
        // 内联样式
        html.append(getExportStyles());
        
        html.append("</head>\n<body>\n");
        html.append("<div class=\"container\">\n");
        
        // 标题
        html.append("<h1>题库导出</h1>\n");
        html.append("<p class=\"info\">共 ").append(questions.size()).append(" 道题目 | 导出时间: ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
            .append("</p>\n");

        // 题目列表
        int processed = 0;
        for (Question q : questions) {
            html.append("<div class=\"question\">\n");
            
            // 题号和难度
            html.append("<div class=\"question-header\">");
            html.append("<span class=\"question-number\">第 ").append(processed + 1).append(" 题</span>");
            html.append("<span class=\"difficulty difficulty-").append(q.getDifficulty()).append("\">");
            html.append(getDifficultyName(q.getDifficulty())).append("</span>");
            html.append("</div>\n");
            
            // 题目内容
            html.append("<div class=\"question-content\">").append(escapeHTML(q.getQuestionText())).append("</div>\n");
            
            // 选项
            if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                html.append("<div class=\"options\">").append(escapeHTML(optionsToString(q.getOptions()))).append("</div>\n");
            }
            
            // 答案
            html.append("<div class=\"answer\"><strong>答案:</strong> ").append(escapeHTML(q.getCorrectAnswer())).append("</div>\n");
            
            // 解析
            if (q.getExplanation() != null && !q.getExplanation().isEmpty()) {
                html.append("<div class=\"analysis\"><strong>解析:</strong> ").append(escapeHTML(q.getExplanation())).append("</div>\n");
            }
            
            // 分类
            html.append("<div class=\"meta\">");
            if (q.getCategory() != null && !q.getCategory().isEmpty()) {
                html.append("<span class=\"category\">分类: ").append(escapeHTML(q.getCategory())).append("</span>");
            }
            html.append("<span class=\"type\">题型: ").append(escapeHTML(q.getQuestionType())).append("</span>");
            html.append("</div>\n");
            
            html.append("</div>\n");

            processed++;
            int progress = 20 + (int) (processed * 40.0 / questions.size());
            updateProgress(progress, "正在处理第 " + processed + " 题...");
        }

        // 尾部
        html.append("</div>\n</body>\n</html>");

        updateProgress(60, "正在写入文件...");

        File file = createExportFile(fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(html.toString());
        }

        return file;
    }

    /**
     * 导出为 Markdown
     */
    private File exportToMarkdown(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在生成 Markdown...");

        StringBuilder md = new StringBuilder();
        
        // 标题
        md.append("# 题库导出\n\n");
        md.append("> 共 ").append(questions.size()).append(" 道题目 | 导出时间: ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
            .append("\n\n");
        md.append("---\n\n");

        int processed = 0;
        for (Question q : questions) {
            md.append("## 第 ").append(processed + 1).append(" 题\n\n");
            md.append("**难度:** ").append(getDifficultyName(q.getDifficulty())).append("\n\n");
            md.append("**题目:** ").append(q.getQuestionText()).append("\n\n");
            
            if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                md.append("**选项:**\n").append(optionsToString(q.getOptions())).append("\n\n");
            }
            
            md.append("**答案:** ").append(q.getCorrectAnswer()).append("\n\n");
            
            if (q.getExplanation() != null && !q.getExplanation().isEmpty()) {
                md.append("**解析:** ").append(q.getExplanation()).append("\n\n");
            }
            
            if (q.getCategory() != null && !q.getCategory().isEmpty()) {
                md.append("**分类:** ").append(q.getCategory()).append("\n");
            }
            
            md.append("\n---\n\n");

            processed++;
            int progress = 20 + (int) (processed * 40.0 / questions.size());
            updateProgress(progress, "正在处理第 " + processed + " 题...");
        }

        updateProgress(60, "正在写入文件...");

        File file = createExportFile(fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(md.toString());
        }

        return file;
    }

    /**
     * 导出为文本
     */
    private File exportToText(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在生成文本...");

        StringBuilder txt = new StringBuilder();
        txt.append("题库导出\n");
        txt.append("=".repeat(40)).append("\n");
        txt.append("共 ").append(questions.size()).append(" 道题目\n");
        txt.append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        txt.append("=".repeat(40)).append("\n\n");

        int processed = 0;
        for (Question q : questions) {
            txt.append("【第 ").append(processed + 1).append(" 题】\n");
            txt.append("难度: ").append(getDifficultyName(q.getDifficulty())).append("\n");
            txt.append("题目: ").append(q.getQuestionText()).append("\n");
            
            if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                txt.append("选项: ").append(optionsToString(q.getOptions())).append("\n");
            }
            
            txt.append("答案: ").append(q.getCorrectAnswer()).append("\n");
            
            if (q.getExplanation() != null && !q.getExplanation().isEmpty()) {
                txt.append("解析: ").append(q.getExplanation()).append("\n");
            }
            
            txt.append("\n");

            processed++;
            int progress = 20 + (int) (processed * 40.0 / questions.size());
            updateProgress(progress, "正在处理第 " + processed + " 题...");
        }

        updateProgress(60, "正在写入文件...");

        File file = createExportFile(fileName);
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(txt.toString());
        }

        return file;
    }

    /**
     * 导出为 PDF（占位，需要 PDF 库）
     */
    private File exportToPDF(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在生成 PDF...");
        
        // 提示：PDF 导出需要引入 iText 或 Android PDF API
        // 这里先生成 HTML，然后可以调用系统转换为 PDF
        String htmlFileName = fileName.replace(".pdf", ".html");
        File htmlFile = exportToHTML(questions, htmlFileName);
        
        // 尝试使用系统功能转换为 PDF
        File pdfFile = createExportFile(fileName);
        
        updateProgress(50, "请使用浏览器打开 HTML 文件后另存为 PDF");
        
        // 返回 HTML 文件作为替代
        return htmlFile;
    }

    /**
     * 导出为 Word（使用 POI 库）
     */
    private File exportToWord(List<Question> questions, String fileName) throws Exception {
        updateProgress(20, "正在生成 Word 文档...");
        
        // 检查 POI 是否可用
        boolean poiAvailable = false;
        try {
            Class.forName("org.apache.poi.xwpf.usermodel.XWPFDocument");
            poiAvailable = true;
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "POI 库不可用，降级为 HTML");
        }
        
        if (!poiAvailable) {
            return exportToHTML(questions, fileName.replace(".docx", ".html"));
        }
        
        try {
            // 使用反射避免编译时依赖
            Class<?> xwpfDocumentClass = Class.forName("org.apache.poi.xwpf.usermodel.XWPFDocument");
            Object document = xwpfDocumentClass.getDeclaredConstructor().newInstance();
            
            // 标题
            java.lang.reflect.Method createParagraphMethod = xwpfDocumentClass.getMethod("createParagraph");
            Object titlePara = createParagraphMethod.invoke(document);
            
            Class<?> runClass = Class.forName("org.apache.poi.xwpf.usermodel.XWPFRun");
            Object titleRun = runClass.getDeclaredConstructor().newInstance();
            
            java.lang.reflect.Method setTextMethod = runClass.getMethod("setText", String.class);
            setTextMethod.invoke(titleRun, "题库导出");
            
            java.lang.reflect.Method createRunMethod = xwpfDocumentClass.getMethod("createRun");
            
            int processed = 0;
            for (Question q : questions) {
                Object para = createParagraphMethod.invoke(document);
                Object run = createRunMethod.invoke(document);
                setTextMethod.invoke(run, "第 " + (processed + 1) + " 题: " + q.getQuestionText());
                
                processed++;
                int progress = 20 + (int) (processed * 40.0 / questions.size());
                updateProgress(progress, "正在处理第 " + processed + " 题...");
            }
            
            updateProgress(60, "正在写入文件...");
            
            File file = createExportFile(fileName);
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            java.lang.reflect.Method writeMethod = xwpfDocumentClass.getMethod("write", java.io.OutputStream.class);
            writeMethod.invoke(document, out);
            out.close();
            
            java.lang.reflect.Method closeMethod = xwpfDocumentClass.getMethod("close");
            closeMethod.invoke(document);
            
            return file;
            
        } catch (Exception e) {
            Log.w(TAG, "Word 导出失败，降级为 HTML: " + e.getMessage());
            return exportToHTML(questions, fileName.replace(".docx", ".html"));
        }
    }

    /**
     * 导出为 Excel（占位）
     */
    private File exportToExcel(List<Question> questions, String fileName) throws Exception {
        // Excel 导出需要 Apache POI
        // 这里使用 CSV 作为替代
        updateProgress(30, "Excel 导出需要 POI 库，降级为 CSV...");
        return exportToCSV(questions, fileName.replace(".xlsx", ".csv"));
    }

    /**
     * 导出为长图
     */
    private File exportToImage(View view, String fileName) throws Exception {
        if (view == null) {
            throw new IllegalArgumentException("需要提供要截图的视图");
        }
        
        updateProgress(30, "正在截图...");

        // 创建 Bitmap（确保视图已经测量）
        int width = view.getWidth();
        int height = view.getHeight();
        
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("视图尺寸无效，请确保视图已测量");
        }
        
        Bitmap bitmap = Bitmap.createBitmap(
            width, 
            height, 
            Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);

        updateProgress(60, "正在保存图片...");

        File file = createExportFile(fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        
        bitmap.recycle(); // 释放 Bitmap 内存

        return file;
    }

    // ==================== 辅助方法 ====================

    private String generateFileName(String customName, ExportFormat format) {
        if (customName != null && !customName.isEmpty()) {
            if (!customName.endsWith("." + format.extension)) {
                return customName + "." + format.extension;
            }
            return customName;
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "SmartQuiz_Export_" + timestamp + "." + format.extension;
    }

    private File createExportFile(String fileName) throws Exception {
        File exportDir;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用应用私有目录
            exportDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports");
        } else {
            // 旧版本使用公共下载目录
            exportDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "SmartQuiz");
        }
        
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        
        File file = new File(exportDir, fileName);
        
        // 如果文件已存在，添加序号
        int counter = 1;
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        String extension = fileName.substring(fileName.lastIndexOf('.'));
        while (file.exists()) {
            fileName = baseName + "_" + counter + extension;
            file = new File(exportDir, fileName);
            counter++;
        }
        
        return file;
    }

    private String escapeCSV(String text) {
        if (text == null) return "";
        // CSV 转义：双引号替换为两个双引号
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String escapeHTML(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("\n", "<br>");
    }

    private String getDifficultyName(int difficulty) {
        switch (difficulty) {
            case 1: return "简单";
            case 2: return "中等";
            case 3: return "困难";
            default: return "未知";
        }
    }
    
    /**
     * 将选项 Map 转换为字符串
     */
    private String optionsToString(java.util.Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return "";
        }
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (java.util.Map.Entry<String, String> entry : options.entrySet()) {
            sb.append(entry.getKey()).append(". ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    private String getExportStyles() {
        StringBuilder styles = new StringBuilder();
        styles.append("<style>\n");
        styles.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; ");
        styles.append("max-width: 800px; margin: 0 auto; padding: 20px; line-height: 1.6; }\n");
        styles.append("h1 { color: #333; border-bottom: 2px solid #8B5CF6; padding-bottom: 10px; }\n");
        styles.append(".info { color: #666; font-size: 14px; }\n");
        styles.append(".question { background: #fff; border: 1px solid #e0e0e0; ");
        styles.append("border-radius: 8px; padding: 20px; margin-bottom: 20px; }\n");
        styles.append(".question-header { display: flex; justify-content: space-between; ");
        styles.append("margin-bottom: 15px; }\n");
        styles.append(".question-number { font-weight: bold; color: #333; }\n");
        styles.append(".difficulty { padding: 4px 12px; border-radius: 4px; font-size: 12px; }\n");
        styles.append(".difficulty-1 { background: #E8F5E9; color: #4CAF50; }\n");
        styles.append(".difficulty-2 { background: #FFF3E0; color: #FF9800; }\n");
        styles.append(".difficulty-3 { background: #FFEBEE; color: #F44336; }\n");
        styles.append(".question-content { font-size: 16px; margin-bottom: 15px; }\n");
        styles.append(".options { background: #f5f5f5; padding: 10px; border-radius: 4px; ");
        styles.append("margin-bottom: 15px; white-space: pre-wrap; }\n");
        styles.append(".answer { color: #4CAF50; margin-bottom: 10px; }\n");
        styles.append(".analysis { color: #666; font-size: 14px; }\n");
        styles.append(".meta { font-size: 12px; color: #999; margin-top: 10px; }\n");
        styles.append(".meta span { margin-right: 15px; }\n");
        
        // 深色模式
        if (isDarkMode) {
            styles.append("@media (prefers-color-scheme: dark) { ");
            styles.append("body { background: #121212; color: #E1E1E1; } ");
            styles.append("h1 { color: #fff; } ");
            styles.append(".question { background: #1E1E1E; border-color: #333; } ");
            styles.append(".question-number { color: #fff; } ");
            styles.append(".options { background: #2D2D2D; } ");
            styles.append("}\n");
        }
        
        styles.append("</style>\n");
        return styles.toString();
    }

    // ==================== 进度回调 ====================

    private void updateProgress(int percent, String message) {
        if (callback != null) {
            callback.onProgress(percent, message);
        }
    }

    private void updateSuccess(File file) {
        if (callback != null) {
            callback.onSuccess(file);
        }
    }

    private void updateError(String error) {
        if (callback != null) {
            callback.onError(error);
        }
    }

    // ==================== 回调接口 ====================

    public interface ExportCallback {
        void onProgress(int percent, String message);
        void onSuccess(File exportedFile);
        void onError(String error);
    }
}
