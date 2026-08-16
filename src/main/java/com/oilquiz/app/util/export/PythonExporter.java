package com.oilquiz.app.util.export;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.export.PythonExportBridge;
import com.oilquiz.app.model.Question;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Python 导出器
 * 使用 Python (Chaquopy) 处理导出，避免 Apache POI/iText 等不兼容 Android 的库
 */
public class PythonExporter implements Exporter {
    private static final String TAG = "PythonExporter";
    private static final int PROGRESS_UPDATE_INTERVAL = 10;
    
    private final PythonExportBridge pythonBridge;
    private final String format;
    
    public PythonExporter(String format) {
        this.format = format;
        this.pythonBridge = PythonExportBridge.getInstance();
        Log.i(TAG, "PythonExporter created for format: " + format);
    }
    
    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        Log.i(TAG, "=== Python Export started, format: " + format + " ===");
        validateParameters(task);
        
        Context context = task.getContext();
        List<Question> questions = task.getQuestions();
        ExportManager.ExportConfig config = task.getConfig();
        
        // 初始化 Python 环境
        if (!pythonBridge.isInitialized() && !pythonBridge.initialize(context)) {
            Log.e(TAG, "Failed to initialize Python environment");
            throw new RuntimeException("初始化 Python 环境失败");
        }
        
        Log.i(TAG, "Questions count: " + questions.size() +
                ", includeAnswers: " + config.isIncludeAnswers() +
                ", includeExplanations: " + config.isIncludeExplanations() +
                ", fields: " + (config.getSelectedFields() != null ? config.getSelectedFields().size() : 0));
        
        // 创建导出目录
        File exportDir = ExportManager.getExportDirectory(context);
        if (exportDir == null) {
            throw new RuntimeException("无法创建导出目录");
        }
        
        // 生成文件名
        String fileName = config.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        Log.i(TAG, "Output filename: " + fileName);
        
        String extension = getFileExtension();
        File outputFile = new File(exportDir, fileName + "." + extension);
        Log.i(TAG, "Output file path: " + outputFile.getAbsolutePath());
        
        // 准备配置
        Map<String, Object> exportConfig = new java.util.HashMap<>();
        
        // 添加字段配置
        List<String> selectedFields = config.getSelectedFields();
        if (selectedFields != null && !selectedFields.isEmpty()) {
            exportConfig.put("fields", selectedFields);
        } else {
            // 默认字段
            exportConfig.put("fields", ExportUtils.getQuestionFields());
        }
        
        exportConfig.put("include_answers", config.isIncludeAnswers());
        exportConfig.put("include_explanations", config.isIncludeExplanations());
        
        // 按 ID 排序题目
        questions.sort((q1, q2) -> Long.compare(q1.getId(), q2.getId()));
        Log.i(TAG, "Questions sorted by ID");
        
        // 更新进度
        if (task.getCallback() != null) {
            task.getCallback().onExportProgress(10);
        }
        
        // 调用 Python 导出
        File resultFile = null;
        try {
            Log.i(TAG, "Calling Python bridge for " + format + " export...");
            switch (format.toLowerCase()) {
                case "excel":
                    resultFile = pythonBridge.exportToExcel(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToExcel completed");
                    break;
                case "csv":
                    resultFile = pythonBridge.exportToCsv(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToCsv completed");
                    break;
                case "markdown":
                    resultFile = pythonBridge.exportToMarkdown(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToMarkdown completed");
                    break;
                case "json":
                    resultFile = pythonBridge.exportToJson(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToJson completed");
                    break;
                case "pdf":
                    resultFile = pythonBridge.exportToPdf(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToPdf completed");
                    break;
                case "long_image":
                    resultFile = pythonBridge.exportToLongImage(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToLongImage completed");
                    break;
                case "html":
                    resultFile = pythonBridge.exportToHtml(outputFile, questions, exportConfig, context);
                    Log.i(TAG, "Python exportToHtml completed");
                    break;
                default:
                    Log.e(TAG, "Unsupported format: " + format);
                    throw new IllegalArgumentException("不支持的导出格式: " + format);
            }
        } catch (Exception e) {
            Log.e(TAG, format + " export failed: " + e.getMessage(), e);
            throw new RuntimeException(format + " 导出失败: " + e.getMessage(), e);
        }
        
        // 更新进度到 100%
        if (task.getCallback() != null) {
            task.getCallback().onExportProgress(100);
        }
        
        if (resultFile == null || !resultFile.exists()) {
            throw new RuntimeException(format + " 导出文件生成失败");
        }
        
        Log.i(TAG, format + " export completed: " + resultFile.getAbsolutePath());
        return resultFile;
    }
    
    @Override
    public void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException {
        if (task == null) {
            throw new IllegalArgumentException("导出任务不能为空");
        }
        if (task.getConfig() == null) {
            throw new IllegalArgumentException("导出配置不能为空");
        }
        if (task.getQuestions() == null || task.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("没有问题可导出");
        }
    }
    
    @Override
    public String getFileExtension() {
        switch (format.toLowerCase()) {
            case "excel":
                return "xlsx";
            case "csv":
                return "csv";
            case "markdown":
                return "md";
            case "json":
                return "json";
            case "pdf":
                return "pdf";
            case "long_image":
                return "png";
            case "html":
                return "html";
            default:
                return "txt";
        }
    }
    
    @Override
    public String getFormatName() {
        switch (format.toLowerCase()) {
            case "excel":
                return "Excel";
            case "csv":
                return "CSV";
            case "markdown":
                return "Markdown";
            case "json":
                return "JSON";
            case "pdf":
                return "PDF";
            case "long_image":
                return "长图片";
            case "html":
                return "HTML学习版";
            default:
                return format;
        }
    }
}
