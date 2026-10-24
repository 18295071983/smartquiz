package com.oilquiz.app.ai.chat.status;

import java.util.Locale;

/**
 * 工具执行"活动分类"（引擎侧基础设施，2026-10-25）。
 *
 * <p><b>为什么放在引擎侧</b>：工具名 → 用户在等的到底是"搜资料 / 读文件 / 生成文件 / 生成图片 /
 * 跑代码"这件事，只有引擎/工具层最清楚。UI 只该消费结论，不该在每次 tick 里猜工具名。
 * 工具执行统一收口在 {@code AIToolManager#executeTool}，那里把分类 + 开始时刻发布到
 * {@link AgentActivityBus}，聊天页再按分类选文案（见 {@link #statusText(long)}）。</p>
 *
 * <p>文案设计沿用推理/生成状态那套：阶段 + 已耗时，不编假百分比；每句 ≤10 个汉字，
 * 保证底部状态行 / 工具卡标题一行放得下。</p>
 */
public enum AgentActivityKind {

    SEARCH("正在联网搜索…", "搜索较久，仍在检索…"),
    READ_FILE("正在读取文件…", "文件读取较久，仍在继续…"),
    GENERATE_FILE("正在生成文件…", "文件较大，仍在生成…"),
    GENERATE_IMAGE("正在生成图片…", "图片生成较久，仍在继续…"),
    GENERATE_CHART("正在生成图表…", "图表绘制较久，仍在继续…"),
    RUN_CODE("正在运行代码…", "代码执行较久，仍在运行…"),
    DOWNLOAD("正在下载文件…", "下载较慢，仍在继续…"),
    QUERY_DATA("正在查询数据…", "查询较久，仍在进行…"),
    GENERIC("正在调用工具…", "工具执行中…");

    private final String earlyText;
    private final String lateText;

    AgentActivityKind(String earlyText, String lateText) {
        this.earlyText = earlyText;
        this.lateText = lateText;
    }

    /** 运行中文案：<10s 用第一句，之后换成"较久"；GENERIC 保持更细的四段口径。 */
    public String statusText(long elapsedMs) {
        long seconds = Math.max(0L, elapsedMs) / 1000L;
        if (this == GENERIC) {
            if (seconds < 3) return "正在调用工具…";
            if (seconds < 10) return "工具执行中…";
            if (seconds < 30) return "工具耗时较长，仍在执行…";
            return "仍在等待工具结果…";
        }
        return seconds < 10 ? earlyText : lateText;
    }

    /** 从持久化的短名还原（找不到按 GENERIC）。 */
    public static AgentActivityKind fromName(String name) {
        if (name == null || name.isEmpty()) return GENERIC;
        try {
            return valueOf(name);
        } catch (Throwable t) {
            return GENERIC;
        }
    }

    /**
     * 工具名 → 活动分类（引擎/UI 共用的唯一口径）。
     *
     * <p>顺序有意义：先判"下载"再判"读文件"（{@code download_file} 含 {@code load_file}），
     * 先判"读文件"再判"生成文件"（{@code pdf_read} 应归读）。</p>
     */
    public static AgentActivityKind classify(String toolName) {
        if (toolName == null || toolName.isEmpty()) return GENERIC;
        String n = toolName.toLowerCase(Locale.ROOT);

        if (containsAny(n, "search", "web_search", "brave", "bing", "google", "tavily",
                "news", "retriev", "spider_search")) {
            return SEARCH;
        }
        // 先判下载再判读文件：download_file 含 "load_file" 子串，顺序反了会被误判成读文件
        if (containsAny(n, "download", "fetch", "http_get", "curl", "wget", "ytdlp", "yt_dlp",
                "url_fetch")) {
            return DOWNLOAD;
        }
        if (containsAny(n, "read_file", "file_read", "read_pdf", "pdf_read", "read_text",
                "read_document", "load_file", "open_file", "ocr", "read_image", "doc_read",
                "read_", "_read")) {
            return READ_FILE;
        }
        if (containsAny(n, "generate_image", "image_gen", "text2image", "txt2img", "draw_image",
                "paint", "wallpaper", "photo_gen")) {
            return GENERATE_IMAGE;
        }
        if (containsAny(n, "chart", "plot", "graph", "matplotlib", "echarts", "visualize")) {
            return GENERATE_CHART;
        }
        if (containsAny(n, "docx", "word", "xlsx", "excel", "pptx", "powerpoint", "pdf_create",
                "write_file", "file_write", "save_file", "create_file", "generate_file",
                "markdown", "report", "export_", "text_to_file")) {
            return GENERATE_FILE;
        }
        if (containsAny(n, "python", "shell", "bash", "run_code", "code_exec", "execute_code",
                "termux", "node_", "javascript")) {
            return RUN_CODE;
        }
        if (containsAny(n, "sql", "database", "db_query", "query_data", "crawl", "spider")) {
            return QUERY_DATA;
        }
        return GENERIC;
    }

    private static boolean containsAny(String s, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && s.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
