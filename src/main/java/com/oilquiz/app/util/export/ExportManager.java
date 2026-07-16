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
    private static ExportManager instance;
    private Context context;
    private Map<String, ExportTask> exportTasks = new HashMap<>();

    private ExportManager() {
    }

    public static synchronized ExportManager getInstance() {
        if (instance == null) {
            instance = new ExportManager();
        }
        return instance;
    }

    public void init(Context context) {
        this.context = context;
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
     * 开始导出任务
     */
    public void startExport(ExportTask task) {
        if (task == null || task.getQuestions() == null || task.getQuestions().isEmpty()) {
            if (task != null && task.getCallback() != null) {
                task.getCallback().onExportError("没有问题可导出");
            }
            return;
        }

        // 设置context
        if (task.getContext() == null) {
            task.setContext(context);
        }

        String taskId = "task_" + System.currentTimeMillis();
        exportTasks.put(taskId, task);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (task.getCallback() != null) {
                        task.getCallback().onExportStart();
                    }

                    // 初始化模板管理器
                    com.oilquiz.app.util.export.template.TemplateManager templateManager = com.oilquiz.app.util.export.template.TemplateManager.getInstance();
                    templateManager.init(task.getContext());

                    // 根据格式选择导出器
                    Exporter selectedExporter = null;
                    // 检查是否使用内容模板
                    if (task.getConfig().isContentTemplateMode()) {
                        // 内容模板模式下，根据选择的格式使用对应的导出器
                        // 但保持内容模板的处理逻辑
                        selectedExporter = new ContentTemplateExporter();
                    } else {
                        // 根据格式选择导出器
                        switch (task.getConfig().getFormat()) {
                            case CSV:
                                selectedExporter = new CSVExporter();
                                break;
                            case EXCEL:
                                selectedExporter = new ExcelExporter();
                                break;
                            case PDF:
                                selectedExporter = new PDFExporter();
                                break;
                            case WORD:
                                selectedExporter = new WordExporter();
                                break;
                            case HTML:
                                // 检查是否指定了模板
                                if (task.getConfig().getTemplateId() != null) {
                                    com.oilquiz.app.util.export.template.Template template = templateManager.getTemplateById(task.getConfig().getTemplateId());
                                    if (template != null) {
                                        selectedExporter = new TemplateHTMLExporter(template);
                                    } else {
                                        selectedExporter = new HTMLExporter();
                                    }
                                } else {
                                    selectedExporter = new HTMLExporter();
                                }
                                break;
                            case ENHANCED_HTML:
                                selectedExporter = new EnhancedHTMLExporter();
                                break;
                            case MARKDOWN:
                                selectedExporter = new MarkdownExporter();
                                break;
                            case JSON:
                                selectedExporter = new JSONExporter();
                                break;
                            case LONG_IMAGE:
                                selectedExporter = new LongImageExporter();
                                break;

                            default:
                                selectedExporter = null;
                                break;
                        }
                    }

                    if (selectedExporter != null) {
                        // 执行导出
                        File file = selectedExporter.export(task);
                        task.setStatus(ExportTaskStatus.COMPLETED);
                        if (task.getCallback() != null) {
                            task.getCallback().onExportComplete(file);
                        }
                    } else {
                        task.setStatus(ExportTaskStatus.FAILED);
                        if (task.getCallback() != null) {
                            task.getCallback().onExportError("不支持的导出格式");
                        }
                    }
                } catch (Exception e) {
                    task.setStatus(ExportTaskStatus.FAILED);
                    Log.e(TAG, "Export error: " + e.getMessage());
                    if (task.getCallback() != null) {
                        task.getCallback().onExportError("导出失败: " + e.getMessage());
                    }
                } finally {
                    // 任务完成后从地图中移除
                    exportTasks.remove(taskId);
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