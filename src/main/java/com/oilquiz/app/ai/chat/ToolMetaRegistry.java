package com.oilquiz.app.ai.chat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具展示元数据（C0-T4）：工具名 → 中文名。
 *
 * <p>此前只有 {@code ChatMessage.formatToolDisplayName} 里 9 个 case，其余工具直接把
 * {@code python_execute} 这类内部名甩给用户看。这里收成一张表 + 一个兜底：
 * 表里没有的工具做<b>轻度润色</b>（去技术后缀、缩写大写、下划线转空格），
 * 例如 {@code custom_search_tool → Custom Search}、{@code foo_bar → Foo Bar}。
 * 不强行翻译成"搜索工具/自定义工具"这类生硬中文（2026-10-25 用户："也不是必须中文名，
 * 英文名也行，别设计太死板"）。
 *
 * <p>名单以 {@code AITool#getName()} 的实际注册名为准（外加少量历史别名，
 * 如 {@code get_weather} 与 {@code ai_weather} 并存、{@code python_*} 由
 * PythonToolManager 侧注册）。新增工具时在这里补一行即可，别在渲染层再加 case。
 *
 * <p>图标（{@code toolIcon}）仍是 emoji，改矢量图标要动布局，留给 B1/A1。
 */
public final class ToolMetaRegistry {

    private static final Map<String, String> NAMES;
    /** 常见技术缩写：兜底润色时保持大写（ocr→OCR、docx→DOCX…） */
    private static final Map<String, String> ACRONYMS = new HashMap<>();
    /** 尾部技术后缀：润色时只削掉最外层（custom_search_tool → Custom Search） */
    private static final java.util.Set<String> TECH_SUFFIXES = new java.util.HashSet<>();

    static {
        String[][] acronyms = {
                {"ai", "AI"}, {"ocr", "OCR"}, {"ui", "UI"}, {"api", "API"}, {"pdf", "PDF"},
                {"apk", "APK"}, {"js", "JS"}, {"db", "DB"}, {"url", "URL"}, {"html", "HTML"},
                {"json", "JSON"}, {"csv", "CSV"}, {"docx", "DOCX"}, {"xlsx", "XLSX"},
                {"pptx", "PPTX"}, {"sql", "SQL"}, {"mcp", "MCP"}, {"npu", "NPU"},
                {"llm", "LLM"}, {"tts", "TTS"}, {"asr", "ASR"}, {"gpu", "GPU"},
                {"cpu", "CPU"}, {"http", "HTTP"}, {"https", "HTTPS"}, {"ip", "IP"}
        };
        for (String[] pair : acronyms) ACRONYMS.put(pair[0], pair[1]);
        java.util.Collections.addAll(TECH_SUFFIXES, "tool", "execute", "executor", "manager",
                "handler", "service", "runner", "helper", "util", "impl", "action");
    }

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

    /** 展示名最大长度：超长内部名截断，避免把工具卡/状态行撑爆 */
    private static final int MAX_DISPLAY_LENGTH = 48;

    /**
     * 工具展示名：表内中文名优先；表外做轻度英文润色（不做生硬中文翻译）。
     *
     * <p><b>兜底约定（2026-10-25 "不要造成新 bug"）</b>：本方法只服务于<b>显示</b>，
     * 任何工具名（含表外、超长、全分隔符、中文、非 ASCII）都必须返回一个非 null 的字符串、
     * 不允许抛异常；执行链路一律用原始 {@code toolName}，不经这里改名。</p>
     */
    public static String displayName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return "";
        try {
            String name = NAMES.get(toolName);
            if (name != null) return name;
            // 表外工具保留润色后的英文名（去技术后缀/缩写大写），不强翻成"搜索工具"这类怪名字。
            return polish(toolName);
        } catch (Throwable t) {
            // 展示兜底：润色失败也绝不让 UI 崩，退回一个安全短名
            return safeFallback(toolName);
        }
    }

    /** 是否已在名单里（单测与排查用） */
    public static boolean isKnown(String toolName) {
        return toolName != null && NAMES.containsKey(toolName);
    }

    /**
     * 表外工具名的轻度润色：{@code custom_search_tool → Custom Search}、
     * {@code internal_docx_writer → Internal DOCX Writer}、{@code foo_bar → Foo Bar}。
     * 只削一层尾部技术后缀；常见缩写保持大写；全空时退回 "Tool"；超长截断。
     */
    static String polish(String toolName) {
        if (toolName == null) return "Tool";
        String[] parts = toolName.split("[_\\-\\s]+");
        int last = parts.length - 1;
        while (last > 0 && TECH_SUFFIXES.contains(parts[last].toLowerCase(java.util.Locale.ROOT))) {
            last--;
        }
        StringBuilder sb = new StringBuilder(toolName.length());
        for (int i = 0; i <= last; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            String acronym = ACRONYMS.get(p.toLowerCase(java.util.Locale.ROOT));
            if (sb.length() > 0) sb.append(' ');
            if (acronym != null) {
                sb.append(acronym);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return cap(sb.toString().trim());
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "Tool";
        return s.length() > MAX_DISPLAY_LENGTH
                ? s.substring(0, MAX_DISPLAY_LENGTH) + "…" : s;
    }

    /** 兜底安全名：只保留可显示字符，超长截断；仍为空则 "Tool" */
    private static String safeFallback(String toolName) {
        try {
            StringBuilder sb = new StringBuilder(toolName.length());
            for (int i = 0; i < toolName.length() && sb.length() < MAX_DISPLAY_LENGTH; i++) {
                char c = toolName.charAt(i);
                if (!Character.isISOControl(c)) sb.append(c);
            }
            String out = sb.toString().trim().replace('_', ' ');
            return out.isEmpty() ? "Tool" : cap(out);
        } catch (Throwable ignored) {
            return "Tool";
        }
    }
}
