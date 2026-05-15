package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.Map;

public class AppOperationTool implements AITool {
    private static final String TAG = "AppOperationTool";
    private final Context context;
    
    private static final Map<String, Class<?>> PAGE_MAP = new HashMap<>();
    static {
        try {
            PAGE_MAP.put("user", Class.forName("com.oilquiz.app.ui.activity.UserActivity"));
            PAGE_MAP.put("用户", Class.forName("com.oilquiz.app.ui.activity.UserActivity"));
            PAGE_MAP.put("question", Class.forName("com.oilquiz.app.ui.activity.QuestionActivity"));
            PAGE_MAP.put("题库", Class.forName("com.oilquiz.app.ui.activity.QuestionActivity"));
            PAGE_MAP.put("quiz", Class.forName("com.oilquiz.app.ui.activity.QuizActivity"));
            PAGE_MAP.put("答题", Class.forName("com.oilquiz.app.ui.activity.QuizActivity"));
            PAGE_MAP.put("start_quiz", Class.forName("com.oilquiz.app.ui.activity.StartQuizActivity"));
            PAGE_MAP.put("开始答题", Class.forName("com.oilquiz.app.ui.activity.StartQuizActivity"));
            PAGE_MAP.put("study_plan", Class.forName("com.oilquiz.app.ui.activity.StudyPlanActivity"));
            PAGE_MAP.put("学习计划", Class.forName("com.oilquiz.app.ui.activity.StudyPlanActivity"));
            PAGE_MAP.put("wrong_question", Class.forName("com.oilquiz.app.ui.activity.WrongQuestionActivity"));
            PAGE_MAP.put("错题本", Class.forName("com.oilquiz.app.ui.activity.WrongQuestionActivity"));
            PAGE_MAP.put("note", Class.forName("com.oilquiz.app.ui.activity.NoteActivity"));
            PAGE_MAP.put("笔记", Class.forName("com.oilquiz.app.ui.activity.NoteActivity"));
            PAGE_MAP.put("ocr", Class.forName("com.oilquiz.app.ui.activity.OCRActivity"));
            PAGE_MAP.put("OCR", Class.forName("com.oilquiz.app.ui.activity.OCRActivity"));
            PAGE_MAP.put("import", Class.forName("com.oilquiz.app.ui.activity.ImportActivity"));
            PAGE_MAP.put("导入", Class.forName("com.oilquiz.app.ui.activity.ImportActivity"));
            PAGE_MAP.put("import_guide", Class.forName("com.oilquiz.app.ui.activity.ImportGuideActivity"));
            PAGE_MAP.put("导入指南", Class.forName("com.oilquiz.app.ui.activity.ImportGuideActivity"));
            PAGE_MAP.put("question_generate", Class.forName("com.oilquiz.app.ui.activity.QuestionGenerateActivity"));
            PAGE_MAP.put("生成题目", Class.forName("com.oilquiz.app.ui.activity.QuestionGenerateActivity"));
            PAGE_MAP.put("environment_check", Class.forName("com.oilquiz.app.ui.activity.EnvironmentCheckActivity"));
            PAGE_MAP.put("环境检查", Class.forName("com.oilquiz.app.ui.activity.EnvironmentCheckActivity"));
            PAGE_MAP.put("export", Class.forName("com.oilquiz.app.ui.activity.ExportActivity"));
            PAGE_MAP.put("导出", Class.forName("com.oilquiz.app.ui.activity.ExportActivity"));
            PAGE_MAP.put("backup", Class.forName("com.oilquiz.app.ui.activity.BackupActivity"));
            PAGE_MAP.put("备份", Class.forName("com.oilquiz.app.ui.activity.BackupActivity"));
            PAGE_MAP.put("theme", Class.forName("com.oilquiz.app.ui.activity.ThemeActivity"));
            PAGE_MAP.put("主题", Class.forName("com.oilquiz.app.ui.activity.ThemeActivity"));
            PAGE_MAP.put("language", Class.forName("com.oilquiz.app.ui.activity.LanguageActivity"));
            PAGE_MAP.put("语言", Class.forName("com.oilquiz.app.ui.activity.LanguageActivity"));
            PAGE_MAP.put("file_preview", Class.forName("com.oilquiz.app.ui.activity.SimpleFilePreviewActivity"));
            PAGE_MAP.put("文件预览", Class.forName("com.oilquiz.app.ui.activity.SimpleFilePreviewActivity"));
            PAGE_MAP.put("test", Class.forName("com.oilquiz.app.ui.activity.TestActivity"));
            PAGE_MAP.put("测试", Class.forName("com.oilquiz.app.ui.activity.TestActivity"));
            PAGE_MAP.put("logs", Class.forName("com.oilquiz.app.ui.activity.LogsActivity"));
            PAGE_MAP.put("日志", Class.forName("com.oilquiz.app.ui.activity.LogsActivity"));
            PAGE_MAP.put("about", Class.forName("com.oilquiz.app.ui.activity.AboutActivity"));
            PAGE_MAP.put("关于", Class.forName("com.oilquiz.app.ui.activity.AboutActivity"));
            PAGE_MAP.put("history", Class.forName("com.oilquiz.app.ui.activity.HistoryActivity"));
            PAGE_MAP.put("历史", Class.forName("com.oilquiz.app.ui.activity.HistoryActivity"));
            PAGE_MAP.put("statistics", Class.forName("com.oilquiz.app.ui.activity.StatisticsActivity"));
            PAGE_MAP.put("统计", Class.forName("com.oilquiz.app.ui.activity.StatisticsActivity"));
            PAGE_MAP.put("database", Class.forName("com.oilquiz.app.ui.activity.DatabaseManagementActivity"));
            PAGE_MAP.put("数据库管理", Class.forName("com.oilquiz.app.ui.activity.DatabaseManagementActivity"));
            PAGE_MAP.put("ai_center", Class.forName("com.oilquiz.app.ui.activity.AICenterActivity"));
            PAGE_MAP.put("AI中心", Class.forName("com.oilquiz.app.ui.activity.AICenterActivity"));
            PAGE_MAP.put("ai", Class.forName("com.oilquiz.app.ui.activity.AICenterActivity"));
            PAGE_MAP.put("AI", Class.forName("com.oilquiz.app.ui.activity.AICenterActivity"));
            PAGE_MAP.put("model_import", Class.forName("com.oilquiz.app.ui.activity.ModelImportActivity"));
            PAGE_MAP.put("模型导入", Class.forName("com.oilquiz.app.ui.activity.ModelImportActivity"));
            PAGE_MAP.put("model_selector", Class.forName("com.oilquiz.app.ui.activity.ModelSelectorActivity"));
            PAGE_MAP.put("模型选择", Class.forName("com.oilquiz.app.ui.activity.ModelSelectorActivity"));
            PAGE_MAP.put("ai_service_status", Class.forName("com.oilquiz.app.ui.activity.AIServiceStatusActivity"));
            PAGE_MAP.put("AI服务状态", Class.forName("com.oilquiz.app.ui.activity.AIServiceStatusActivity"));
            PAGE_MAP.put("toolbox", Class.forName("com.oilquiz.app.ui.activity.ToolboxActivity"));
            PAGE_MAP.put("工具箱", Class.forName("com.oilquiz.app.ui.activity.ToolboxActivity"));
            PAGE_MAP.put("home", Class.forName("com.oilquiz.app.MainActivity"));
            PAGE_MAP.put("主页", Class.forName("com.oilquiz.app.MainActivity"));
            PAGE_MAP.put("首页", Class.forName("com.oilquiz.app.MainActivity"));
        } catch (ClassNotFoundException e) {
            AILogger.w(TAG, "Some Activity classes not found: " + e.getMessage());
        }
    }

    public AppOperationTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() { return "app_operation"; }

    @Override
    public String getDescription() { return "应用操作工具，执行页面跳转、应用信息查询等操作"; }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) return new AIToolResult("缺少参数: action", parameters);

            switch (action) {
                case "navigate":
                    return handleNavigate(parameters);
                case "list_pages":
                    return handleListPages();
                case "get_info":
                    return handleGetInfo();
                case "open_settings":
                    return handleOpenSettings(parameters);
                case "share":
                    return handleShare(parameters);
                default:
                    return new AIToolResult("未知操作: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "执行出错: " + e.getMessage(), e);
            return new AIToolResult("错误: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult handleNavigate(Map<String, Object> parameters) {
        String page = (String) parameters.get("page");
        if (page == null) return new AIToolResult("缺少参数: page", parameters);

        Class<?> targetClass = PAGE_MAP.get(page.toLowerCase());
        if (targetClass == null) {
            for (Map.Entry<String, Class<?>> entry : PAGE_MAP.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(page)) {
                    targetClass = entry.getValue();
                    break;
                }
            }
        }

        if (targetClass == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "未知页面: " + page);
            result.put("available_pages", getAvailablePages());
            return new AIToolResult(result, parameters);
        }

        try {
            Intent intent = new Intent(context, targetClass);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已导航到: " + page);
            result.put("target", targetClass.getName());
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "导航失败: " + e.getMessage(), e);
            return new AIToolResult("导航失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult handleListPages() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("count", PAGE_MAP.size());
        result.put("pages", getAvailablePages());
        result.put("message", "以下是可导航的页面列表");
        return new AIToolResult(result, new HashMap<>());
    }

    private AIToolResult handleOpenSettings(Map<String, Object> parameters) {
        String setting = (String) parameters.get("setting");
        
        Intent intent;
        if (setting != null && !setting.isEmpty()) {
            switch (setting.toLowerCase()) {
                case "wifi":
                    intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                    break;
                case "bluetooth":
                    intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                    break;
                case "location":
                    intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    break;
                case "display":
                    intent = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                    break;
                case "sound":
                    intent = new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS);
                    break;
                case "storage":
                    intent = new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                    break;
                case "app":
                    intent = new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                    break;
                default:
                    intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    break;
            }
        } else {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开设置: " + (setting != null ? setting : "系统设置"));
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("打开设置失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult handleShare(Map<String, Object> parameters) {
        String text = (String) parameters.get("text");
        String title = (String) parameters.get("title");
        
        if (text == null || text.isEmpty()) {
            return new AIToolResult("缺少参数: text", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null) {
                intent.putExtra(Intent.EXTRA_TITLE, title);
            }
            Intent chooser = Intent.createChooser(intent, title != null ? title : "分享");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开分享界面");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("分享失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult handleGetInfo() {
        Map<String, Object> result = new HashMap<>();
        result.put("app_name", "答题宝");
        result.put("version", "2.0");
        result.put("description", "智能学习辅助应用");
        result.put("package_name", context.getPackageName());
        result.put("available_pages", getAvailablePages());
        return new AIToolResult(result, new HashMap<>());
    }

    private String getAvailablePages() {
        StringBuilder pages = new StringBuilder();
        for (String key : PAGE_MAP.keySet()) {
            if (pages.length() > 0) pages.append(", ");
            pages.append(key);
        }
        return pages.toString();
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: navigate(导航), list_pages(列出页面), get_info(获取信息), open_settings(打开设置), share(分享)");
        descriptions.put("page", "目标页面名称，例如: home, ai, quiz, user, theme 等");
        descriptions.put("setting", "设置项: wifi, bluetooth, location, display, sound, storage, app");
        descriptions.put("text", "分享的文本内容");
        descriptions.put("title", "分享标题");
        return descriptions;
    }
}
