package com.oilquiz.app.ai.knowledge;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识库 AI 工具：供本地 Agent 与在线 Agent 调用的知识库检索与管理入口。
 *
 * <p>支持动作：
 * <ul>
 *   <li>search —— 全文检索（query 关键词 + category 过滤 + top_k 条数）</li>
 *   <li>add —— 添加单条知识（title/content/category/keywords/source）</li>
 *   <li>add_batch —— 批量添加（items 为对象数组）</li>
 *   <li>import_json —— 导入知识 JSON（兼容 Python 预处理脚本输出的 {"chunks":[...]} 结构）</li>
 *   <li>delete —— 按 id 或 title 删除</li>
 *   <li>clear —— 清空全部</li>
 *   <li>stats —— 统计信息（总量/分类分布/最近更新）</li>
 * </ul>
 *
 * <p>由 AIToolManager 懒加载创建；@Tool 注解由 ToolSchemaExtractor 自动提取，
 * 并同步暴露给在线 Agent（OpenAI function calling 格式）。
 */
@Tool(
        value = "knowledge_base",
        description = "知识库：全文检索知识(search)、添加知识(add/add_batch)、导入JSON(import_json/import_file)、直接导入文件(import_document，支持Word/Excel/TXT/MD/CSV/PDF/HTML/图片OCR/音频ASR转写自动切块)、删除(delete)、清空(clear)、统计(stats)。知识库内容由用户维护，用于回答应用专属问题",
        category = "knowledge",
        aliases = {"kb", "knowledge", "wiki_search"},
        actions = {
                @Action(name = "search", description = "全文检索知识库，返回匹配的知识片段列表", params = {
                        @Param(name = "query", type = "string", description = "检索关键词，中文/英文/数字均可", required = true),
                        @Param(name = "category", type = "string", description = "分类过滤，如 general/guide/faq，不传则检索全部", required = false),
                        @Param(name = "top_k", type = "int", description = "返回条数上限，默认5，最大20", required = false)
                }),
                @Action(name = "add", description = "添加单条知识", params = {
                        @Param(name = "title", type = "string", description = "知识标题", required = true),
                        @Param(name = "content", type = "string", description = "知识正文内容", required = true),
                        @Param(name = "category", type = "string", description = "分类，默认 general", required = false),
                        @Param(name = "keywords", type = "string", description = "关键词（逗号分隔），增强检索命中", required = false),
                        @Param(name = "source", type = "string", description = "来源标识，如文件名/链接", required = false)
                }),
                @Action(name = "add_batch", description = "批量添加知识，items 为对象数组（每项含 title/content/category/keywords/source）", params = {
                        @Param(name = "items", type = "array", description = "知识对象数组", required = true)
                }),
                @Action(name = "import_json", description = "导入知识JSON（Python预处理脚本输出格式，含chunks数组）", params = {
                        @Param(name = "json", type = "string", description = "知识JSON字符串", required = true)
                }),
                @Action(name = "import_file", description = "从手机存储导入知识JSON文件（UTF-8，最大5MB）", params = {
                        @Param(name = "file_path", type = "string", description = "JSON文件绝对路径", required = true)
                }),
                @Action(name = "import_document", description = "直接导入文件到知识库：Word(doc/docx)/Excel(xls/xlsx)/TXT/MD/CSV/PDF/HTML/图片(截图拍照走OCR)/音频(mp3/wav/m4a等走语音识别ASR转写)，自动解析并按标题切块入库，无需手动转换JSON", params = {
                        @Param(name = "file_path", type = "string", description = "文件绝对路径", required = true),
                        @Param(name = "category", type = "string", description = "知识分类，默认 general", required = false),
                        @Param(name = "title", type = "string", description = "指定标题（优先于文件名，图片/音频建议传入，如 化学笔记截图/课堂录音）", required = false)
                }),
                @Action(name = "delete", description = "按id或标题删除知识", params = {
                        @Param(name = "id", type = "int", description = "知识ID（与title二选一）", required = false),
                        @Param(name = "title", type = "string", description = "知识标题精确匹配（与id二选一）", required = false)
                }),
                @Action(name = "clear", description = "清空全部知识库内容"),
                @Action(name = "stats", description = "知识库统计：总量、分类分布、最近更新时间")
        },
        params = {
                @Param(name = "action", type = "string", description = "操作类型", required = true),
                @Param(name = "query", type = "string", description = "检索关键词", required = false),
                @Param(name = "category", type = "string", description = "分类过滤", required = false),
                @Param(name = "top_k", type = "int", description = "返回条数上限", required = false),
                @Param(name = "title", type = "string", description = "标题（add 必填 / import_document 可选）", required = false),
                @Param(name = "content", type = "string", description = "内容", required = false),
                @Param(name = "keywords", type = "string", description = "关键词", required = false),
                @Param(name = "source", type = "string", description = "来源", required = false),
                @Param(name = "id", type = "int", description = "知识ID", required = false),
                @Param(name = "items", type = "array", description = "批量知识数组", required = false),
                @Param(name = "json", type = "string", description = "知识JSON字符串", required = false),
                @Param(name = "file_path", type = "string", description = "知识JSON文件路径（import_file使用）", required = false)
        }
)
public class KnowledgeBaseTool implements AITool {
    private static final String TAG = "KnowledgeBaseTool";

    private final Context context;
    private final KnowledgeBaseManager manager;

    public KnowledgeBaseTool(Context context) {
        this.context = context.getApplicationContext();
        this.manager = KnowledgeBaseManager.getInstance(this.context);
    }

    @Override
    public String getName() {
        return "knowledge_base";
    }

    @Override
    public String getDescription() {
        return "知识库：全文检索知识(search)、添加知识(add/add_batch)、导入JSON(import_json/import_file)、"
                + "直接导入文件(import_document，支持Word/Excel/TXT/MD/CSV/PDF/HTML/图片OCR/音频ASR转写自动切块)、"
                + "删除(delete)、清空(clear)、统计(stats)。知识库内容由用户维护，用于回答应用专属问题";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型：search/add/add_batch/import_json/delete/clear/stats");
        params.put("query", "检索关键词（search 必填）");
        params.put("category", "分类过滤（search/add 可选）");
        params.put("top_k", "返回条数上限（search 可选，默认5）");
        params.put("title", "标题（add 必填 / import_document 可选）");
        params.put("content", "内容（add 必填）");
        params.put("keywords", "关键词（add 可选）");
        params.put("source", "来源（add 可选）");
        params.put("id", "知识ID（delete 使用）");
        params.put("items", "批量知识数组（add_batch 必填）");
        params.put("json", "知识JSON字符串（import_json 必填）");
        params.put("file_path", "文件绝对路径（import_file 为JSON文件 / import_document 为文档）");
        params.put("category", "导入分类（import_document 可选）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            if (actionObj == null) {
                return new AIToolResult("缺少参数: action（search/add/add_batch/import_json/import_file/import_document/delete/clear/stats）",
                        parameters);
            }
            String action = actionObj.toString();
            switch (action) {
                case "search":
                    return doSearch(parameters);
                case "add":
                    return doAdd(parameters);
                case "add_batch":
                    return doAddBatch(parameters);
                case "import_json":
                    return doImportJson(parameters);
                case "import_file":
                    return doImportFile(parameters);
                case "import_document":
                    return doImportDocument(parameters);
                case "delete":
                    return doDelete(parameters);
                case "clear":
                    return doClear(parameters);
                case "stats":
                    return doStats(parameters);
                default:
                    return new AIToolResult("未知操作: " + action
                            + "（支持 search/add/add_batch/import_json/import_file/import_document/delete/clear/stats）", parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "execute 异常: " + e.getMessage(), e);
            return new AIToolResult("知识库操作失败: " + e.getMessage(), parameters);
        }
    }

    // ==================== 各动作实现 ====================

    private AIToolResult doSearch(Map<String, Object> parameters) {
        String query = stringParam(parameters, "query");
        if (query == null || query.trim().isEmpty()) {
            return new AIToolResult("缺少参数: query（检索关键词）", parameters);
        }
        String category = stringParam(parameters, "category");
        int topK = intParam(parameters, "top_k", 5);
        JSONArray results = manager.search(query, category, topK);

        Map<String, Object> info = new HashMap<>();
        info.put("action", "search");
        info.put("query", query);
        info.put("category", category == null ? "" : category);
        info.put("hits", results.length());

        if (results.length() == 0) {
            return new AIToolResult("{\"hits\":0,\"message\":\"知识库中未找到与 \"" + query + "\" 相关的内容\"}", info);
        }
        // 结果本身是核心交付：直接作为 result 返回 JSON 字符串
        return new AIToolResult(results.toString(), info);
    }

    private AIToolResult doAdd(Map<String, Object> parameters) {
        String title = stringParam(parameters, "title");
        String content = stringParam(parameters, "content");
        if (content == null || content.trim().isEmpty()) {
            return new AIToolResult("缺少参数: content（知识正文）", parameters);
        }
        if (title == null || title.trim().isEmpty()) {
            title = content.length() > 20 ? content.substring(0, 20) : content;
        }
        String category = stringParam(parameters, "category");
        String keywords = stringParam(parameters, "keywords");
        String source = stringParam(parameters, "source");

        long id = manager.addChunk(title, category, keywords, content, source);
        Map<String, Object> info = new HashMap<>();
        info.put("action", "add");
        if (id > 0) {
            info.put("id", id);
            return new AIToolResult("{\"success\":true,\"id\":" + id + ",\"title\":\"" + escape(title) + "\"}",
                    info);
        }
        return new AIToolResult("知识添加失败", parameters);
    }

    private AIToolResult doAddBatch(Map<String, Object> parameters) {
        Object itemsObj = parameters.get("items");
        if (itemsObj == null) {
            return new AIToolResult("缺少参数: items（知识对象数组）", parameters);
        }
        JSONArray items;
        try {
            if (itemsObj instanceof JSONArray) {
                items = (JSONArray) itemsObj;
            } else if (itemsObj instanceof String) {
                items = new JSONArray(itemsObj.toString());
            } else {
                items = new JSONArray(itemsObj.toString());
            }
        } catch (Exception e) {
            return new AIToolResult("items 解析失败: " + e.getMessage(), parameters);
        }
        int added = manager.addBatch(items);
        Map<String, Object> info = new HashMap<>();
        info.put("action", "add_batch");
        info.put("added", added);
        return new AIToolResult("{\"success\":true,\"added\":" + added + "}", info);
    }

    private AIToolResult doImportJson(Map<String, Object> parameters) {
        String json = stringParam(parameters, "json");
        if (json == null || json.trim().isEmpty()) {
            return new AIToolResult("缺少参数: json（知识JSON字符串）", parameters);
        }
        int added = manager.importJson(json);
        Map<String, Object> info = new HashMap<>();
        info.put("action", "import_json");
        info.put("added", added);
        return new AIToolResult("{\"success\":true,\"added\":" + added + "}", info);
    }

    private AIToolResult doImportFile(Map<String, Object> parameters) {
        String filePath = stringParam(parameters, "file_path");
        if (filePath == null || filePath.trim().isEmpty()) {
            return new AIToolResult("缺少参数: file_path（知识JSON文件绝对路径）", parameters);
        }
        java.io.File file = new java.io.File(filePath.trim());
        if (!file.exists() || !file.isFile()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        if (file.length() > 5 * 1024 * 1024) {
            return new AIToolResult("文件过大（超过5MB）: " + filePath, parameters);
        }
        try {
            String json = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            int added = manager.importJson(json);
            Map<String, Object> info = new HashMap<>();
            info.put("action", "import_file");
            info.put("file_path", filePath);
            info.put("added", added);
            return new AIToolResult("{\"success\":true,\"added\":" + added + "}", info);
        } catch (Exception e) {
            Log.e(TAG, "import_file 失败: " + e.getMessage(), e);
            return new AIToolResult("导入文件失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult doImportDocument(Map<String, Object> parameters) {
        String filePath = stringParam(parameters, "file_path");
        if (filePath == null || filePath.trim().isEmpty()) {
            return new AIToolResult("缺少参数: file_path（文件绝对路径）", parameters);
        }
        String category = stringParam(parameters, "category");
        String title = stringParam(parameters, "title");
        JSONObject result = manager.importDocument(filePath.trim(), category, title);
        Map<String, Object> info = new HashMap<>();
        info.put("action", "import_document");
        info.put("file_path", filePath);
        info.put("category", category == null ? "" : category);
        info.put("title", title == null ? "" : title);
        boolean success = result.optBoolean("success", false);
        info.put("added", result.optInt("added", 0));
        if (success) {
            String msg = "文档导入成功，共切分为 " + result.optInt("added", 0) + " 条知识"
                    + (result.has("message") ? "；" + result.optString("message") : "");
            return new AIToolResult("{\"success\":true,\"added\":" + result.optInt("added", 0)
                    + ",\"message\":\"" + escape(msg) + "\"}", info);
        }
        return new AIToolResult("文档导入失败: " + result.optString("message", "未知错误"), info);
    }

    private AIToolResult doDelete(Map<String, Object> parameters) {
        Object idObj = parameters.get("id");
        String title = stringParam(parameters, "title");
        Map<String, Object> info = new HashMap<>();
        info.put("action", "delete");

        if (idObj != null) {
            long id;
            try {
                id = Long.parseLong(idObj.toString().trim());
            } catch (NumberFormatException e) {
                return new AIToolResult("id 格式错误: " + idObj, parameters);
            }
            boolean ok = manager.deleteById(id);
            info.put("deleted", ok ? 1 : 0);
            return new AIToolResult("{\"success\":" + ok + ",\"deleted\":" + (ok ? 1 : 0) + "}", info);
        }
        if (title != null && !title.trim().isEmpty()) {
            int deleted = manager.deleteByTitle(title.trim());
            info.put("deleted", deleted);
            return new AIToolResult("{\"success\":true,\"deleted\":" + deleted + "}", info);
        }
        return new AIToolResult("缺少参数: id 或 title", parameters);
    }

    private AIToolResult doClear(Map<String, Object> parameters) {
        int cleared = manager.clear();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "clear");
        info.put("cleared", cleared);
        return new AIToolResult("{\"success\":true,\"cleared\":" + cleared + "}", info);
    }

    private AIToolResult doStats(Map<String, Object> parameters) {
        JSONObject stats = manager.stats();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "stats");
        return new AIToolResult(stats.toString(), info);
    }

    // ==================== 参数取值工具 ====================

    private static String stringParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : value.toString();
    }

    private static int intParam(Map<String, Object> parameters, String key, int def) {
        Object value = parameters.get(key);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
