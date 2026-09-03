package com.oilquiz.app.util.export;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.WebViewActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * WebView APK 导出器
 * 功能：
 * 1. 将题目数据打包成 HTML 文件，可直接在 WebView 中打开
 * 2. 可选择通过 WebViewActivity 直接预览
 * 3. 可选择导出到文件管理器供长期使用
 */
public class WebViewAPKExporter implements Exporter {
    private static final String TAG = "WebViewAPKExporter";
    private ExportManager.ExportTask exportTask;

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        Log.i(TAG, "=== WebView Export started ===");
        validateParameters(task);

        this.exportTask = task;
        List<Question> questions = task.getQuestions();
        Context context = task.getContext();
        ExportManager.ExportConfig config = task.getConfig();
        
        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("没有可导出的题目");
        }
        if (context == null) {
            throw new IllegalStateException("上下文不能为空");
        }
        if (config == null) {
            throw new IllegalStateException("导出配置不能为空");
        }

        String fileName = config.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }

        Log.i(TAG, "Questions count: " + questions.size());
        // 安全读取配置字段，避免 NPE
        boolean includeAnswers = false;
        boolean includeExplanations = false;
        try {
            if (config != null) {
                includeAnswers = config.isIncludeAnswers();
                includeExplanations = config.isIncludeExplanations();
                Log.i(TAG, "Include answers: " + includeAnswers);
                Log.i(TAG, "Include explanations: " + includeExplanations);
            } else {
                Log.w(TAG, "Config is null, using default values");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read config, using default values", e);
        }

        // 创建导出目录
        File exportDir = new File(context.getFilesDir(), "webview_export_" + System.currentTimeMillis());
        exportDir.mkdirs();
        Log.i(TAG, "Export directory: " + exportDir.getAbsolutePath());

        try {
            // 导出 HTML 文件
            File indexFile = exportHtml(exportDir, fileName, questions, context);

            // 创建 README
            createReadmeFile(exportDir, fileName, questions.size());

            Log.i(TAG, "HTML exported to: " + indexFile.getAbsolutePath() + 
                ", size: " + indexFile.length() + " bytes");

            // 用"背题"壳机制打包成可安装 APK（HTML → dt.jet → 壳模板 → apksig 签名）
            String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File apkFile = new File(exportDir, safeName + ".apk");
            try {
                ApkPacker.buildApk(context, indexFile, apkFile);
                Log.i(TAG, "APK built: " + apkFile.getAbsolutePath() + ", size: " + apkFile.length());
            } catch (Exception apkEx) {
                Log.e(TAG, "APK build failed, falling back to HTML only", apkEx);
                // APK 打包失败时回退：仍返回 HTML，不中断导出
                apkFile = indexFile;
            }

            // 发送广播通知
            try {
                android.content.Intent intent = new android.content.Intent("com.oilquiz.app.EXPORT_COMPLETE");
                intent.putExtra("file_path", apkFile.getAbsolutePath());
                intent.putExtra("export_type", "webview");
                context.sendBroadcast(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send broadcast", e);
            }

            return apkFile;
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            // 出错时清理临时目录
            deleteDirectory(exportDir);
            throw e;
        }
    }

    /**
     * 导出 HTML 文件
     */
    private File exportHtml(File destDir, String fileName, List<Question> questions, Context context) throws Exception {
        // 使用现有的 HTMLExporter 导出 HTML
        ExportManager.ExportConfig originalConfig = exportTask.getConfig();
        
        // 安全地创建 HTML 导出配置
        ExportManager.ExportConfig htmlConfig = new ExportManager.ExportConfig();
        htmlConfig.setFormat(ExportManager.ExportFormat.HTML);
        
        if (originalConfig != null) {
            try {
                htmlConfig.setIncludeAnswers(originalConfig.isIncludeAnswers());
                htmlConfig.setIncludeExplanations(originalConfig.isIncludeExplanations());
                htmlConfig.setIncludeDifficulty(originalConfig.isIncludeDifficulty());
                htmlConfig.setIncludeExplanation(originalConfig.isIncludeExplanation());
                if (originalConfig.getSelectedFields() != null) {
                    htmlConfig.setSelectedFields(originalConfig.getSelectedFields());
                }
                htmlConfig.setTemplateId(originalConfig.getTemplateId());
                htmlConfig.setContentTemplateId(originalConfig.getContentTemplateId());
                htmlConfig.setContentTemplateName(originalConfig.getContentTemplateName());
                htmlConfig.setContentTemplateFilePath(originalConfig.getContentTemplateFilePath());
                htmlConfig.setContentTemplateMode(originalConfig.isContentTemplateMode());
            } catch (Exception e) {
                Log.w(TAG, "Failed to copy config fields, using defaults", e);
            }
        }
        
        htmlConfig.setFileName(fileName);

        ExportManager.ExportTask htmlTask = new ExportManager.ExportTask();
        htmlTask.setConfig(htmlConfig);
        htmlTask.setQuestions(questions);
        htmlTask.setContext(context);

        HTMLExporter htmlExporter = new HTMLExporter();
        File htmlFile = htmlExporter.export(htmlTask);

        // 复制 HTML 文件到导出目录并重命名为 index.html
        File indexFile = new File(destDir, "index.html");
        java.nio.file.Files.copy(htmlFile.toPath(), indexFile.toPath(), 
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // 删除临时 HTML 文件
        htmlFile.delete();

        return indexFile;
    }

    /**
     * 创建 README 说明文件
     */
    private void createReadmeFile(File destDir, String fileName, int questionCount) throws IOException {
        File readmeFile = new File(destDir, "README.txt");
        try (FileWriter writer = new FileWriter(readmeFile)) {
            writer.write("====================================\n");
            writer.write("  题目离线包说明\n");
            writer.write("====================================\n\n");
            writer.write("文件名称: " + fileName + "\n");
            writer.write("题目数量: " + String.valueOf(questionCount) + " 题\n");
            writer.write("生成时间: " +
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) +
                "\n\n");
            writer.write("查看方法:\n");
            writer.write("1. 在 SmartQuiz 应用中点击「预览」可直接查看\n");
            writer.write("2. 导出到文件管理器后，可用任何浏览器打开\n\n");
            writer.write("功能说明:\n");
            writer.write("- 支持题目搜索和筛选\n");
            writer.write("- 支持显示/隐藏答案和解析\n");
            writer.write("- 支持题目折叠/展开\n");
            writer.write("- 支持打印功能\n");
            writer.write("- 响应式设计，适配各种屏幕\n\n");
            writer.write("Powered by SmartQuiz\n");
        }
        Log.d(TAG, "README file created");
    }

    /**
     * 删除目录及其所有内容
     */
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 通过 WebViewActivity 预览
     */
    public void previewInWebView(Context context) {
        if (this.exportTask == null) {
            Log.e(TAG, "Export task not set, cannot preview");
            return;
        }

        try {
            // 先导出到临时目录
            File exportDir = new File(context.getFilesDir(), "webview_export_temp");
            exportDir.mkdirs();

            List<Question> questions = exportTask.getQuestions();
            exportHtml(exportDir, "index", questions, context);

            // 通过 WebViewActivity 打开
            Intent intent = new Intent(context, WebViewActivity.class);
            intent.putExtra("url", "file://" + new File(exportDir, "index.html").getAbsolutePath());
            intent.putExtra("title", "题目预览");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            Log.i(TAG, "WebView preview started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebView preview", e);
        }
    }

    @Override
    public String getFormatName() {
        return "WebView APK 应用";
    }

    @Override
    public String getFileExtension() {
        return "apk";
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
}
