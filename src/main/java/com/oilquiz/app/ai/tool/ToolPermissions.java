package com.oilquiz.app.ai.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具权限分级（2026-11-16）。
 *
 * <p><b>为什么需要</b>：Agent 现在有 57 个工具，能力跨度极大 —— 有的只读时间（time_date），
 * 有的能执行任意命令（linux_shell）、能推代码（git push）、能把数据发到公网（network_search）。
 * 现在是一刀切审批：要么全放行、要么全拦住。分级之后可以做到：</p>
 *
 * <pre>
 * READ_ONLY   → 一律放行（无副作用）
 * WRITE_LOCAL → 首次询问，之后按会话记住（写文件、建目录、git commit）
 * EXEC        → 每次询问（执行命令、跑脚本）
 * NETWORK     → 每次询问（外发数据到网络）
 * DANGEROUS   → 强制确认 + 记录审计（push、改权限、装包）
 * </pre>
 *
 * <p><b>设计取舍</b>：分级表放在代码里（而非配置），因为这是安全默认值 —— 不能被模型或
 * 普通设置轻易改掉；需要放宽时改这里、走一次发版审核。未知工具按 {@link Level#EXEC} 处理
 * （保守：宁可多问一次）。</p>
 */
public final class ToolPermissions {

    private ToolPermissions() {
    }

    /** 权限级别（越往下越危险） */
    public enum Level {
        /** 只读：查询时间/配置/文件内容，无副作用 */
        READ_ONLY,
        /** 本地可逆写：写文件、建目录、commit、记忆写入 */
        WRITE_LOCAL,
        /** 执行代码/命令 */
        EXEC,
        /** 网络外发 */
        NETWORK,
        /** 危险操作：推代码、改系统、装包、改权限 */
        DANGEROUS
    }

    /** 工具名 → 级别（未列出的工具按 EXEC 处理） */
    private static final Map<String, Level> LEVELS;

    static {
        Map<String, Level> m = new LinkedHashMap<>();

        // ---- 只读 ----
        for (String t : new String[]{
                "time_date", "calculator", "unit_converter", "text_tools", "control_lookup",
                "get_models_profile", "update_models_profile", "chat_history", "memory",
                "file_reader", "file_analyzer", "webpage_reader", "network_search",
                "smart_research", "ocr_recognize", "location", "system_resource",
                "tool_registry", "mcp_toolbox"}) {
            m.put(t, Level.READ_ONLY);
        }

        // ---- 本地可逆写 ----
        for (String t : new String[]{
                "file_generator", "workspace", "excel", "database", "reminder", "task",
                "layout_editor", "ui_component_plugin", "dynamic_tool",
                "dynamic_tool_manager", "import_start", "import_cancel", "import_decide",
                "rule_engine", "image_gen", "speech_synthesis"}) {
            m.put(t, Level.WRITE_LOCAL);
        }

        // ---- 执行 ----
        for (String t : new String[]{
                "linux_shell", "js_execute", "pip_install", "media_toolkit",
                "screen_capture", "screen_watch", "app_operation", "system_ui_component",
                "voice_input", "system_connect"}) {
            m.put(t, Level.EXEC);
        }

        // ---- 网络外发 ----
        for (String t : new String[]{
                "dashscope_media", "douyin_downloader", "web_render",
                "video_to_player", "remote_dsh"}) {
            m.put(t, Level.NETWORK);
        }

        // ---- 危险 ----
        for (String t : new String[]{
                "git",                 // 含 push/pull，会把代码发到远端
                "export_apk",
                "permission_manager"}) {
            m.put(t, Level.DANGEROUS);
        }

        LEVELS = Collections.unmodifiableMap(m);
    }

    /** 取工具级别；未知工具按 EXEC（保守默认）。 */
    public static Level levelOf(String toolName) {
        if (toolName == null) {
            return Level.EXEC;
        }
        Level l = LEVELS.get(toolName.toLowerCase(Locale.US));
        return l == null ? Level.EXEC : l;
    }

    /** 是否需要用户确认。 */
    public static boolean requiresApproval(String toolName) {
        return levelOf(toolName) != Level.READ_ONLY;
    }

    /** 是否属于"每次都必须问"的高危操作。 */
    public static boolean requiresEveryTime(String toolName) {
        Level l = levelOf(toolName);
        return l == Level.EXEC || l == Level.NETWORK || l == Level.DANGEROUS;
    }

    /** 是否必须写审计日志。 */
    public static boolean requiresAudit(String toolName) {
        return levelOf(toolName) == Level.DANGEROUS;
    }

    /** 全部已分级工具（供设置页展示）。 */
    public static Map<String, Level> allLevels() {
        return LEVELS;
    }

    /** 某一级别下的所有工具名。 */
    public static Set<String> toolsOf(Level level) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Level> e : LEVELS.entrySet()) {
            if (e.getValue() == level) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
