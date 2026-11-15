package com.oilquiz.app.ai.chat.component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卡片 props 契约表（2026-11-16 由 tools/card_contract_test.ps1 扫源码生成，勿手改）。
 *
 * <p>用途：卡片渲染失败/数据不匹配走兜底时，告诉用户和 Agent 「这张卡需要哪些字段」，
 * 而不是只显示一张「查看原始 JSON」的通用卡（真机踩过：table_card 传 rows 而不是
 * headers+rows，静默降级，谁也不知道字段名错了）。</p>
 */
public final class CardContracts {

    private CardContracts() {
    }

    private static final Map<String, String[]> CONTRACTS = new LinkedHashMap<>();

    static {
        CONTRACTS.put("agenda", new String[]{"date", "desc", "description", "items", "name", "status", "time", "title"});
        CONTRACTS.put("agent_status", new String[]{"spinning", "status", "text", "toolName"});
        CONTRACTS.put("alert_card", new String[]{"actions", "childId", "content", "jump", "level", "text", "title", "type"});
        CONTRACTS.put("app_card", new String[]{"message", "package", "params", "target", "title", "uri"});
        CONTRACTS.put("audio", new String[]{"artist", "audio_url", "loop", "media", "speed", "src", "title", "uri", "url", "value"});
        CONTRACTS.put("chart", new String[]{"categories", "chartType", "data", "label", "name", "points", "series", "seriesName", "title", "value", "y"});
        CONTRACTS.put("code_card", new String[]{"code", "command", "language", "status", "title"});
        CONTRACTS.put("compare", new String[]{"items", "title"});
        CONTRACTS.put("contact_card", new String[]{"action", "actions", "app", "copy", "description", "label", "link", "package", "title", "type", "value"});
        CONTRACTS.put("diff", new String[]{"after", "before", "hunks", "path", "text", "title", "type"});
        CONTRACTS.put("feedback_bar", new String[]{"actions", "dynamic", "feedbackState", "id", "label", "messageId", "speaking", "streaming", "visibleWhen"});
        CONTRACTS.put("file_card", new String[]{"name", "path", "size", "type", "uri"});
        CONTRACTS.put("file_list", new String[]{"files", "name", "path", "size", "title", "type"});
        CONTRACTS.put("grid_card", new String[]{"columns", "copy", "icon", "items", "label", "link", "title"});
        CONTRACTS.put("html", new String[]{"file", "maxHeight", "path", "style", "url"});
        CONTRACTS.put("image_grid", new String[]{"columns", "images", "url"});
        CONTRACTS.put("info_card", new String[]{"items", "label", "title", "value"});
        CONTRACTS.put("job", new String[]{"cmd", "job_id", "status", "title"});
        CONTRACTS.put("key_value", new String[]{"title"});
        CONTRACTS.put("link_card", new String[]{"description", "domain", "title", "url"});
        CONTRACTS.put("list_card", new String[]{"description", "icon", "items", "link", "title", "url", "value"});
        CONTRACTS.put("location", new String[]{"accuracy", "address", "city", "provider", "title"});
        CONTRACTS.put("markdown_card", new String[]{"actions", "content", "text", "title"});
        CONTRACTS.put("markdown_doc", new String[]{"maxHeight", "title"});
        CONTRACTS.put("metric_card", new String[]{"color", "label", "metrics", "title", "value"});
        CONTRACTS.put("note_card", new String[]{"author", "content", "type"});
        CONTRACTS.put("playlist", new String[]{"artist", "audios", "files", "items", "name", "path", "size", "src", "title", "uri", "url"});
        CONTRACTS.put("progress_card", new String[]{"description", "progress", "status", "title"});
        CONTRACTS.put("quiz_card", new String[]{"analysis", "answer", "items", "options", "placeholder", "question", "type"});
        CONTRACTS.put("steps_card", new String[]{"description", "items", "status", "steps", "title"});
        CONTRACTS.put("table_card", new String[]{"align", "headers", "rows", "title"});
        CONTRACTS.put("todo_card", new String[]{"done", "items", "text", "title"});
        CONTRACTS.put("tool_call", new String[]{"args", "result", "status", "statusText", "summary", "toolName"});
        CONTRACTS.put("video", new String[]{"autoPlay", "default", "is_default", "loop", "media", "speed", "src", "subtitle", "subtitle_url", "subtitles", "title", "uri", "url", "value", "video_url"});
        CONTRACTS.put("weather_card", new String[]{"city", "date", "forecast", "humidity", "icon", "temp", "tempMax", "tempMin", "text", "windDir", "windScale"});
    }

    /** 该卡片接受的字段名（无契约返回空数组） */
    public static String[] keysOf(String type) {
        // 先按原始名找，再按 canonical 找：table_card 这类名字 resolve 后可能是 "table"，
        // 直接查 resolve 结果会漏掉契约（真机踩到：提示变成"需要更多字段"）
        String[] k = null;
        if (type != null) {
            k = CONTRACTS.get(type);
            if (k == null) {
                k = CONTRACTS.get(ComponentTypes.resolve(type));
            }
        }
        return k == null ? new String[0] : k;
    }

    /** 一句话提示：	able_card 需要 headers/rows/align */
    public static String hint(String type) {
        String[] keys = keysOf(type);
        if (keys.length == 0) return type + " 需要更多字段";
        StringBuilder sb = new StringBuilder(type).append(" 需要 ");
        for (int i = 0; i < Math.min(keys.length, 6); i++) {
            if (i > 0) sb.append('/');
            sb.append(keys[i]);
        }
        return sb.toString();
    }
}
