package com.oilquiz.app.util.export;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出管理器
 * 负责管理导出任务和格式
 */
public class ExportManager {
    private static final String TAG = "ExportManager";
    private static final String LOG_PREFIX = "ExportManager";
    private static ExportManager instance;
    private Context context;
    private Map<String, ExportTask> exportTasks = new HashMap<>();

    private ExportManager() {
        Log.i(LOG_PREFIX, "ExportManager initialized");
    }

    public static synchronized ExportManager getInstance() {
        if (instance == null) {
            instance = new ExportManager();
        }
        return instance;
    }

    public void init(Context context) {
        if (this.context != null) {
            Log.w(LOG_PREFIX, "Re-initializing ExportManager with new context");
        }
        this.context = context;
        Log.i(LOG_PREFIX, "ExportManager context initialized: " + (context != null ? context.getClass().getSimpleName() : "null"));
    }

    /**
     * 导出格式枚举
     */
    public enum ExportFormat {
        CSV, EXCEL, PDF, WORD, HTML, ENHANCED_HTML, MARKDOWN, JSON, LONG_IMAGE
    }

    /**
     * 导出任务状态枚举
     */
    public enum ExportTaskStatus {
        RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    /**
     * 导出配置类
     */
    public static class ExportConfig implements java.io.Serializable {
        public ExportFormat format;
        public String fileName;
        public boolean includeAnswers;
        public boolean includeExplanations;
        public boolean groupByCategory;
        public boolean includeCategories;
        public boolean includeDifficulty;
        public boolean includeExplanation;
        public boolean autoSizeColumns;
        public boolean splitByCategory;
        public String documentTitle;
        public String documentAuthor;
        public String documentSubject;
        public java.util.List<String> selectedFields;
        public String templateId; // 模板ID
        public long contentTemplateId; // 内容模板ID
        public String contentTemplateName; // 内容模板名称
        public String contentTemplateFilePath; // 内容模板文件路径
        public boolean isContentTemplateMode; // 是否使用内容模板

        public ExportFormat getFormat() {
            return format;
        }

        public void setFormat(ExportFormat format) {
            this.format = format;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public boolean isIncludeAnswers() {
            return includeAnswers;
        }

        public void setIncludeAnswers(boolean includeAnswers) {
            this.includeAnswers = includeAnswers;
        }

        public boolean isIncludeExplanations() {
            return includeExplanations;
        }

        public void setIncludeExplanations(boolean includeExplanations) {
            this.includeExplanations = includeExplanations;
        }

        public boolean isGroupByCategory() {
            return groupByCategory;
        }

        public void setGroupByCategory(boolean groupByCategory) {
            this.groupByCategory = groupByCategory;
        }

        public boolean isIncludeCategories() {
            return includeCategories;
        }

        public void setIncludeCategories(boolean includeCategories) {
            this.includeCategories = includeCategories;
        }

        public boolean isIncludeDifficulty() {
            return includeDifficulty;
        }

        public void setIncludeDifficulty(boolean includeDifficulty) {
            this.includeDifficulty = includeDifficulty;
        }

        public boolean isIncludeExplanation() {
            return includeExplanation;
        }

        public void setIncludeExplanation(boolean includeExplanation) {
            this.includeExplanation = includeExplanation;
        }

        public boolean isAutoSizeColumns() {
            return autoSizeColumns;
        }

        public void setAutoSizeColumns(boolean autoSizeColumns) {
            this.autoSizeColumns = autoSizeColumns;
        }

        public boolean isSplitByCategory() {
            return splitByCategory;
        }

        public void setSplitByCategory(boolean splitByCategory) {
            this.splitByCategory = splitByCategory;
        }

        public String getDocumentTitle() {
            return documentTitle;
        }

        public void setDocumentTitle(String documentTitle) {
            this.documentTitle = documentTitle;
        }

        public String getDocumentAuthor() {
            return documentAuthor;
        }

        public void setDocumentAuthor(String documentAuthor) {
            this.documentAuthor = documentAuthor;
        }

        public String getDocumentSubject() {
            return documentSubject;
        }

        public void setDocumentSubject(String documentSubject) {
            this.documentSubject = documentSubject;
        }

        public java.util.List<String> getSelectedFields() {
            return selectedFields;
        }

        public void setSelectedFields(java.util.List<String> selectedFields) {
            this.selectedFields = selectedFields;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String templateId) {
            this.templateId = templateId;
        }

        public long getContentTemplateId() {
            return contentTemplateId;
        }

        public void setContentTemplateId(long contentTemplateId) {
            this.contentTemplateId = contentTemplateId;
        }

        public String getContentTemplateName() {
            return contentTemplateName;
        }

        public void setContentTemplateName(String contentTemplateName) {
            this.contentTemplateName = contentTemplateName;
        }

        public String getContentTemplateFilePath() {
            return contentTemplateFilePath;
        }

        public void setContentTemplateFilePath(String contentTemplateFilePath) {
            this.contentTemplateFilePath = contentTemplateFilePath;
        }

        public boolean isContentTemplateMode() {
            return isContentTemplateMode;
        }

        public void setContentTemplateMode(boolean contentTemplateMode) {
            isContentTemplateMode = contentTemplateMode;
        }
    }

    /**
     * 导出任务类
     */
    public static class ExportTask {
        private ExportConfig config;
        private List<Question> questions;
        private ExportCallback callback;
        private ExportTaskStatus status = ExportTaskStatus.RUNNING;
        private Context context;

        public ExportConfig getConfig() {
            return config;
        }

        public void setConfig(ExportConfig config) {
            this.config = config;
        }

        public List<Question> getQuestions() {
            return questions;
        }

        public void setQuestions(List<Question> questions) {
            this.questions = questions;
        }

        public ExportCallback getCallback() {
            return callback;
        }

        public void setCallback(ExportCallback callback) {
            this.callback = callback;
        }

        public ExportTaskStatus getStatus() {
            return status;
        }

        public void setStatus(ExportTaskStatus status) {
            this.status = status;
        }

        public Context getContext() {
            return context;
        }

        public void setContext(Context context) {
            this.context = context;
        }
    }

    /**
     * 导出回调接口
     */
    public interface ExportCallback {
        void onExportStart();
        void onExportProgress(int progress);
        void onExportComplete(File file);
        void onExportError(String error);
        default void onExportLog(String message) {
            // 默认实现，空方法
        }
    }

    /**
     * 根据导出格式获取对应的导出器
     * 使用 Python (Chaquopy) 处理导出，避免 Apache POI/iText 等不兼容 Android 的库
     */
    private Exporter getExporter(Context context, String format) {
        Log.i(LOG_PREFIX, "Getting exporter for format: " + format);
        switch (format.toUpperCase()) {
            case "EXCEL":
            case "XLSX":
                Log.i(LOG_PREFIX, "Using PythonExporter for Excel");
                return new PythonExporter("excel");
            case "CSV":
                Log.i(LOG_PREFIX, "Using CSVExporter");
                return new CSVExporter();
            case "MARKDOWN":
            case "MD":
                Log.i(LOG_PREFIX, "Using MarkdownExporter");
                return new MarkdownExporter();
            case "JSON":
                Log.i(LOG_PREFIX, "Using JSONExporter");
                return new JSONExporter();
            case "WORD":
            case "DOCX":
                Log.i(LOG_PREFIX, "Using WordExporter");
                return new WordExporter();
            case "PDF":
                Log.i(LOG_PREFIX, "Using PDFExporter");
                return new PDFExporter();
            case "HTML":
                Log.i(LOG_PREFIX, "Using HTMLExporter");
                return new HTMLExporter();
            case "LONGIMAGE":
            case "LONGIMG":
                Log.i(LOG_PREFIX, "Using LongImageExporter");
                return new LongImageExporter();
            default:
                Log.e(LOG_PREFIX, "Unsupported format: " + format);
                throw new IllegalArgumentException("不支持的导出格式: " + format);
        }
    }

    /**
     * 开始导出任务
     */
    public void startExport(ExportTask task) {
        Log.i(LOG_PREFIX, "=== Starting export task ===");
        
        if (task == null || task.getQuestions() == null || task.getQuestions().isEmpty()) {
            Log.e(LOG_PREFIX, "Invalid export task: task=" + (task == null ? "null" : "not null") + 
                    ", questions=" + (task != null && task.getQuestions() != null ? task.getQuestions().size() : "null"));
            if (task != null && task.getCallback() != null) {
                task.getCallback().onExportError("没有问题可导出");
            }
            return;
        }

        // 设置context
        if (task.getContext() == null) {
            task.setContext(context);
            Log.i(LOG_PREFIX, "Set context from manager to task");
        }

        String taskId = "task_" + System.currentTimeMillis();
        exportTasks.put(taskId, task);
        Log.i(LOG_PREFIX, "Task created: " + taskId + ", questions count: " + task.getQuestions().size());
        Log.i(LOG_PREFIX, "Export config: format=" + (task.getConfig() != null ? task.getConfig().getFormat().name() : "null") +
                ", includeAnswers=" + (task.getConfig() != null && task.getConfig().isIncludeAnswers()) +
                ", includeExplanations=" + (task.getConfig() != null && task.getConfig().isIncludeExplanations()) +
                ", selectedFields=" + (task.getConfig() != null && task.getConfig().getSelectedFields() != null ? task.getConfig().getSelectedFields().size() : 0));
        Log.i(LOG_PREFIX, "Template info: contentTemplateMode=" + (task.getConfig() != null && task.getConfig().isContentTemplateMode()) +
                ", templateId=" + (task.getConfig() != null ? task.getConfig().getTemplateId() : "null"));

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(LOG_PREFIX, "Export thread started for task: " + taskId);
                    if (task.getCallback() != null) {
                        task.getCallback().onExportStart();
                    }

                    // 初始化模板管理器
                    com.oilquiz.app.util.export.template.TemplateManager templateManager = com.oilquiz.app.util.export.template.TemplateManager.getInstance();
                    templateManager.init(task.getContext());
                    Log.i(LOG_PREFIX, "TemplateManager initialized");

                    // 根据格式选择导出器
                    Exporter selectedExporter = null;
                    // 检查是否使用内容模板
                    if (task.getConfig().isContentTemplateMode()) {
                        Log.i(LOG_PREFIX, "Using ContentTemplateExporter for content template mode");
                        // 内容模板模式下，根据选择的格式使用对应的导出器
                        // 但保持内容模板的处理逻辑
                        selectedExporter = new ContentTemplateExporter();
                    } else {
                        // 根据格式选择导出器
                        Log.i(LOG_PREFIX, "Determining exporter for format: " + task.getConfig().getFormat().name());
                        switch (task.getConfig().getFormat()) {
                            case CSV:
                                Log.i(LOG_PREFIX, "Selected: CSVExporter");
                                selectedExporter = new CSVExporter();
                                break;
                            case EXCEL:
                                Log.i(LOG_PREFIX, "Selected: PythonExporter for Excel (Android-compatible)");
                                selectedExporter = new PythonExporter("excel");
                                break;
                            case WORD:
                                Log.i(LOG_PREFIX, "Selected: WordExporter");
                                selectedExporter = new WordExporter();
                                break;
                            case HTML:
                                // 学习查看版HTML导出，使用Python实现折叠功能
                                Log.i(LOG_PREFIX, "Selected: PythonExporter for HTML (learning view)");
                                selectedExporter = new PythonExporter("html");
                                break;
                            case ENHANCED_HTML:
                                Log.i(LOG_PREFIX, "Selected: EnhancedHTMLExporter");
                                selectedExporter = new EnhancedHTMLExporter();
                                break;
                            case MARKDOWN:
                                Log.i(LOG_PREFIX, "Selected: MarkdownExporter");
                                selectedExporter = new MarkdownExporter();
                                break;
                            case JSON:
                                Log.i(LOG_PREFIX, "Selected: JSONExporter");
                                selectedExporter = new JSONExporter();
                                break;
                            case PDF:
                                Log.i(LOG_PREFIX, "Selected: PythonExporter for PDF (Chinese support)");
                                selectedExporter = new PythonExporter("pdf");
                                break;
                            case LONG_IMAGE:
                                Log.i(LOG_PREFIX, "Selected: PythonExporter for Long Image (Pillow)");
                                selectedExporter = new PythonExporter("long_image");
                                break;

                            default:
                                Log.e(LOG_PREFIX, "Unsupported format: " + task.getConfig().getFormat());
                                selectedExporter = null;
                                break;
                        }
                    }

                    if (selectedExporter != null) {
                        Log.i(LOG_PREFIX, "Exporter ready, starting export...");
                        long startTime = System.currentTimeMillis();
                        // 执行导出
                        File file = selectedExporter.export(task);
                        long endTime = System.currentTimeMillis();
                        Log.i(LOG_PREFIX, "Export completed successfully: " + file.getAbsolutePath() + 
                                ", file size: " + file.length() + " bytes, duration: " + (endTime - startTime) + "ms");
                        task.setStatus(ExportTaskStatus.COMPLETED);
                        if (task.getCallback() != null) {
                            task.getCallback().onExportComplete(file);
                        }
                    } else {
                        Log.e(LOG_PREFIX, "No exporter found, export failed");
                        task.setStatus(ExportTaskStatus.FAILED);
                        if (task.getCallback() != null) {
                            task.getCallback().onExportError("不支持的导出格式");
                        }
                    }
                } catch (Exception e) {
                    Log.e(LOG_PREFIX, "Export error: " + e.getMessage(), e);
                    task.setStatus(ExportTaskStatus.FAILED);
                    if (task.getCallback() != null) {
                        task.getCallback().onExportError("导出失败: " + e.getMessage());
                    }
                } finally {
                    // 任务完成后从地图中移除
                    exportTasks.remove(taskId);
                    Log.i(LOG_PREFIX, "Export task completed and removed: " + taskId);
                }
            }
        }).start();
    }

    /**
     * 开始导出任务（静态方法）
     */
    public static String startExport(Context context, ExportFormat format, File file, List<Question> questions, ExportConfig config, ExportCallback callback) {
        ExportManager manager = getInstance();
        manager.init(context);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setCallback(callback);
        task.setContext(context);
        
        manager.startExport(task);
        return "task_" + System.currentTimeMillis();
    }

    /**
     * 获取导出任务状态
     */
    public static ExportTaskStatus getExportTaskStatus(String taskId) {
        ExportManager manager = getInstance();
        ExportTask task = manager.exportTasks.get(taskId);
        return task != null ? task.getStatus() : ExportTaskStatus.FAILED;
    }

    /**
     * 暂停导出任务
     */
    public static void pauseExport(String taskId) {
        ExportManager manager = getInstance();
        ExportTask task = manager.exportTasks.get(taskId);
        if (task != null) {
            task.setStatus(ExportTaskStatus.PAUSED);
        }
    }

    /**
     * 恢复导出任务
     */
    public static void resumeExport(String taskId) {
        ExportManager manager = getInstance();
        ExportTask task = manager.exportTasks.get(taskId);
        if (task != null) {
            task.setStatus(ExportTaskStatus.RUNNING);
        }
    }

    /**
     * 取消导出任务
     */
    public static void cancelExport(String taskId) {
        ExportManager manager = getInstance();
        ExportTask task = manager.exportTasks.get(taskId);
        if (task != null) {
            task.setStatus(ExportTaskStatus.CANCELLED);
            manager.exportTasks.remove(taskId);
        }
    }

    /**
     * 初始化导出模块
     */
    public static void initExportTemplates(Context context) {
        // 简化处理，实际应该初始化导出模板
        Log.d(TAG, "Export templates initialized");
    }

    /**
     * 获取导出文件路径
     */
    public static File getExportDirectory(Context context) {
        if (context == null) {
            return null;
        }

        // 导出到应用临时目录
        File exportDir = new File(context.getCacheDir(), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        return exportDir;
    }

    /**
     * 获取导出文件路径（实例方法）
     */
    public File getExportDirectory() {
        return getExportDirectory(context);
    }

    /**
     * 清理过期的导出文件
     */
    public void cleanupOldExports() {
        File exportDir = getExportDirectory();
        if (exportDir == null || !exportDir.exists()) {
            return;
        }

        long cutoffTime = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000; // 7天前
        File[] files = exportDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() < cutoffTime) {
                    file.delete();
                }
            }
        }
    }

    /**
     * 导出到Word文件
     */
    public File exportToWord(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.WORD);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new WordExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to Word error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成Excel模板
     */
    public File generateExcelTemplate(Context context) {
        // 生成一个空的Excel模板文件
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.EXCEL);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(new java.util.ArrayList<>());
        task.setContext(context);
        
        try {
            Exporter exporter = new ExcelExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Generate Excel template error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到Excel文件
     */
    public File exportToExcel(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.EXCEL);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new ExcelExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to Excel error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到PDF文件
     */
    public File exportToPDF(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.PDF);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new PDFExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to PDF error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到HTML文件
     */
    public File exportToHTML(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.HTML);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new HTMLExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to HTML error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到Markdown文件
     */
    public File exportToMarkdown(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.MARKDOWN);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new MarkdownExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to Markdown error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到JSON文件
     */
    public File exportToJSON(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.JSON);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new JSONExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to JSON error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到CSV文件
     */
    public File exportToCSV(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.CSV);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new CSVExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to CSV error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 导出到长图片
     */
    public File exportToLongImage(Context context, List<Question> questions) {
        ExportConfig config = new ExportConfig();
        config.setFormat(ExportFormat.LONG_IMAGE);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        
        ExportTask task = new ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(context);
        
        try {
            Exporter exporter = new LongImageExporter();
            return exporter.export(task);
        } catch (Exception e) {
            Log.e(TAG, "Export to Long Image error: " + e.getMessage());
            return null;
        }
    }


}