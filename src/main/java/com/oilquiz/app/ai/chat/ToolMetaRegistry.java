package com.oilquiz.app.ai.chat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具展示元数据（C0-T4）：工具名 → 中文名。
 *
 * <p>此前只有 {@code ChatMessage.formatToolDisplayName} 里 9 个 case，其余工具直接把
 * {@code python_execute} 这类内部名甩给用户看。这里收成一张表 + 一个兜底：
 * 表里没有的工具按 {@code foo_bar → Foo Bar} 人性化，仍然不暴露原始下划线名。
 *
 * <p>名单以 {@code AITool#getName()} 的实际注册名为准（外加少量历史别名，
 * 如 {@code get_weather} 与 {@code ai_weather} 并存、{@code python_*} 由
 * PythonToolManager 侧注册）。新增工具时在这里补一行即可，别在渲染层再加 case。
 *
 * <p>图标（{@code toolIcon}）仍是 emoji，改矢量图标要动布局，留给 B1/A1。
 */
public final class ToolMetaRegistry {

    private static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new HashMap<>();
        // 检索与信息
        m.put("ai_weather", "天气查询");
        m.put("get_weather", "天气查询");
        m.put("network_search", "网络搜索");
        m.put("webpage_reader", "网页阅读");
        m.put("web_render", "网页渲染");
        m.put("smart_research", "智能研究");
        m.put("location", "定位");
        m.put("time_date", "时间日期");
        m.put("control_lookup", "控件查询");
        // 题库与学习
        m.put("generate_questions", "生成题目");
        m.put("analyze_question", "分析题目");
        m.put("search_questions", "搜索题目");
        m.put("create_study_plan", "创建学习计划");
        m.put("get_statistics", "学习统计");
        m.put("database", "数据库查询");
        m.put("excel_tool", "Excel 处理");
        m.put("knowledge_base", "知识库");
        m.put("chat_history", "聊天记录");
        m.put("memory", "记忆");
        m.put("task", "任务清单");
        m.put("reminder", "提醒");
        // 文件与工作区
        m.put("file_reader", "文件读取");
        m.put("file_generator", "文件生成");
        m.put("file_analyzer", "文件分析");
        m.put("text_tools", "文本处理");
        m.put("workspace", "工作区文件");
        m.put("export_apk", "导出 APK");
        m.put("douyin_downloader", "视频下载");
        // 执行与脚本
        m.put("python_execute", "Python 执行");
        m.put("python_chart", "Python 图表");
        m.put("python_analyze_data", "Python 数据分析");
        m.put("js_execute", "JavaScript 执行");
        m.put("linux_shell", "Linux 命令");
        m.put("pip_install", "安装 Python 库");
        m.put("remote_dsh", "远程 Shell");
        // 多媒体与设备
        m.put("ocr_recognize", "OCR 识别");
        m.put("image_gen", "图片生成");
        m.put("dashscope_media", "媒体生成");
        m.put("media_toolkit", "媒体处理");
        m.put("video_to_player", "视频播放");
        m.put("speech_synthesis", "语音合成");
        m.put("voice_input", "语音输入");
        m.put("screen_capture", "屏幕截图");
        m.put("screen_watch", "屏幕观察");
        m.put("camera", "相机");
        // 系统与应用
        m.put("app_operation", "应用操作");
        m.put("permission_manager", "权限管理");
        m.put("system_resource", "系统资源");
        m.put("system_connect", "系统连接");
        m.put("storage", "存储管理");
        m.put("calculator", "计算器");
        m.put("unit_converter", "单位换算");
        // UI 与工具自身
        m.put("ui_component", "UI 组件");
        m.put("ui_component_plugin", "UI 组件插件");
        m.put("layout_editor", "布局编辑器");
        m.put("tool_registry", "工具列表");
        m.put("get_models_profile", "模型配置");
        m.put("update_models_profile", "更新模型配置");
        // 导入流程
        m.put("import_start", "开始导入");
        m.put("import_status", "导入进度");
        m.put("import_list_files", "列出导入文件");
        m.put("import_decide", "导入决策");
        m.put("import_cancel", "取消导入");
        NAMES = Collections.unmodifiableMap(m);
    }

    private ToolMetaRegistry() {
    }

    /** 工具中文名；表里没有时人性化兜底，绝不回吐原始下划线名 */
    public static String displayName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return "";
        String name = NAMES.get(toolName);
        if (name != null) return name;
        String humanized = humanize(toolName);
        return humanized.isEmpty() ? toolName : humanized;
    }

    /** 是否已在名单里（单测与排查用） */
    public static boolean isKnown(String toolName) {
        return toolName != null && NAMES.containsKey(toolName);
    }

    /** {@code foo_bar_baz → Foo Bar Baz} */
    private static String humanize(String toolName) {
        String[] parts = toolName.split("_");
        StringBuilder sb = new StringBuilder(toolName.length());
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
