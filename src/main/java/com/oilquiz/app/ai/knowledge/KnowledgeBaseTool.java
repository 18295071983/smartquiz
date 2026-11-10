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
 *   <li>update —— 修改已入库知识（id 或唯一标题定位；只改传入的字段，id 不变）</li>
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
        description = "知识库：先索引后精确——list 看轻量索引(id/标题/分类/关键词，不含正文)、get 按id取单条全文、search 模糊检索、search+id/exact_title 精确取；添加(add/add_batch)、修改(update，按id或唯一标题，只改传入字段)、导入(import_json/import_file/import_document 支持Word/Excel/TXT/MD/CSV/PDF/HTML/图片OCR/音频ASR)、删除(delete)、清空(clear)、统计(stats)",
        category = "knowledge",
        aliases = {"kb", "knowledge", "wiki_search"},
        actions = {
                @Action(name = "list", description = "列出知识库轻量索引（id/标题/分类/关键词/来源/更新时间/文档序号，不含正文）；回答“知识库里有什么”或先定位再取详情时用；超过一页用 offset 翻页", params = {
                        @Param(name = "category", type = "string", description = "分类过滤（可选）", required = false),
                        @Param(name = "tag", type = "string", description = "按标签/别名精确过滤（匹配 keywords 里的整词，可选）", required = false),
                        @Param(name = "limit", type = "int", description = "条数上限，默认20，最大100", required = false),
                        @Param(name = "offset", type = "int", description = "跳过前 N 条（翻页用，默认0；返回里有 next_offset 就传它）", required = false)
                }),
                @Action(name = "get", description = "按 id 取单条知识的完整正文（先 list/search 拿 id）；不会一次返回多条", params = {
                        @Param(name = "id", type = "int", description = "知识ID", required = true)
                }),
                @Action(name = "search", description = "检索知识库：默认模糊匹配（query 分词+打分，返回命中片段）；传 id 或 exact_title 时改为精确取，不做模糊召回", params = {
                        @Param(name = "query", type = "string", description = "检索关键词（模糊检索用；精确取时可省略）", required = false),
                        @Param(name = "id", type = "int", description = "精确取：按知识ID（与 exact_title 二选一）", required = false),
                        @Param(name = "exact_title", type = "string", description = "精确取：标题全等（与 id 二选一）", required = false),
                        @Param(name = "category", type = "string", description = "分类过滤，如 general/guide/faq；tool_defs=系统工具定义库（查工具用法/参数用）；不传=检索用户知识（自动排除 tool_defs）", required = false),
                        @Param(name = "tag", type = "string", description = "按标签/别名精确过滤（匹配 keywords 整词；别名/同义词建议存 keywords）", required = false),
                        @Param(name = "top_k", type = "int", description = "返回条数上限，默认5，最大20", required = false)
                }),
                @Action(name = "add", description = "添加单条知识", params = {
                        @Param(name = "title", type = "string", description = "知识标题", required = true),
                        @Param(name = "content", type = "string", description = "知识正文内容", required = true),
                        @Param(name = "category", type = "string", description = "分类，默认 general", required = false),
                        @Param(name = "keywords", type = "string", description = "关键词（逗号分隔），增强检索命中", required = false),
                        @Param(name = "source", type = "string", description = "来源标识，如文件名/链接", required = false)
                }),
                @Action(name = "update", description = "修改已入库的一条知识：按 id（search 结果里带 id）或标题唯一命中定位，只改传入的字段，条目 id 不变", params = {
                        @Param(name = "id", type = "int", description = "知识ID（与 find_title 二选一，优先用 id）", required = false),
                        @Param(name = "find_title", type = "string", description = "按标题精确定位（仅标题唯一时可用；命中多条会报错，请改用 id）", required = false),
                        @Param(name = "title", type = "string", description = "新标题（可选）", required = false),
                        @Param(name = "content", type = "string", description = "新正文（可选）", required = false),
                        @Param(name = "category", type = "string", description = "新分类（可选）", required = false),
                        @Param(name = "keywords", type = "string", description = "新关键词，逗号分隔（可选）", required = false),
                        @Param(name = "source", type = "string", description = "新来源（可选）", required = false)
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
                @Action(name = "delete", description = "按id、标题或来源删除知识；文档导入的整篇内容用 source（通常是文件绝对路径）一次删干净", params = {
                        @Param(name = "id", type = "int", description = "知识ID（与 title/source 三选一）", required = false),
                        @Param(name = "title", type = "string", description = "知识标题精确匹配（与 id/source 三选一；文档导入的多块同标题会一起删）", required = false),
                        @Param(name = "source", type = "string", description = "来源（导入时的文件绝对路径）：删除该来源下的全部块，用于整篇替换", required = false)
                }),
                @Action(name = "clear", description = "清空知识库内容（含系统工具定义 tool_defs；清掉后下次 Agent 初始化会自动重灌）"),
                @Action(name = "export", description = "导出知识库为JSON文件（结构与 import_json 一致，可直接 import_file 导回），用于备份", params = {
                        @Param(name = "file_path", type = "string", description = "导出文件绝对路径（可选；不传则写到 app 私有目录 knowledge_export_<时间戳>.json）", required = false),
                        @Param(name = "category", type = "string", description = "只导出该分类（可选，不传导出全部）", required = false)
                }),
                @Action(name = "stats", description = "知识库统计：总量、分类分布、最近更新时间、已向量化块数（embedded）"),
                @Action(name = "embed", description = "为现有知识补全本地向量（写入时自动算，只是给老库/换模型后补一次）：会按块调用在线 embedding，消耗额度；已有向量的块跳过；没配 embedding 模型时返回 0")
        },
        params = {
                @Param(name = "action", type = "string", description = "操作类型", required = true),
                @Param(name = "query", type = "string", description = "检索关键词", required = false),
                @Param(name = "category", type = "string", description = "分类过滤", required = false),
                @Param(name = "tag", type = "string", description = "标签/别名（list/search 精确过滤用）", required = false),
                @Param(name = "top_k", type = "int", description = "返回条数上限", required = false),
                @Param(name = "title", type = "string", description = "标题（add 必填 / import_document 可选）", required = false),
                @Param(name = "content", type = "string", description = "内容", required = false),
                @Param(name = "keywords", type = "string", description = "关键词", required = false),
                @Param(name = "source", type = "string", description = "来源", required = false),
                @Param(name = "id", type = "int", description = "知识ID", required = false),
                @Param(name = "find_title", type = "string", description = "按标题精确定位（update 用，仅唯一命中时可用）", required = false),
                @Param(name = "exact_title", type = "string", description = "精确取：标题全等（search 用，与 id 二选一）", required = false),
                @Param(name = "limit", type = "int", description = "条数上限（list 用，默认20，最大100）", required = false),
                @Param(name = "offset", type = "int", description = "翻页偏移（list 用，跳过前 N 条）", required = false),
                @Param(name = "items", type = "array", description = "批量知识数组", required = false),
                @Param(name = "json", type = "string", description = "知识JSON字符串", required = false),
                @Param(name = "file_path", type = "string", description = "文件绝对路径（import_file 读JSON / import_document 读文档·图片·音频 / export 写导出文件）", required = false)
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
        return "知识库：list 轻量索引(id/标题/分类/关键词，不含正文)、get 按id取单条全文、search 模糊检索或(id/exact_title)精确取、"
                + "add/add_batch 添加、update 修改(按id或唯一标题，只改传入字段)、"
                + "import_json/import_file 导入JSON、"
                + "import_document 直接导入文件(Word/Excel/TXT/MD/CSV/PDF/HTML/图片OCR/音频ASR转写自动切块)、"
                + "delete 删除(按id/标题/来源)、export 导出备份、clear 清空(含工具定义，会自动重灌)、stats 统计、"
                + "embed 补全本地向量(老库/换模型后用，消耗 embedding 额度)。"
                + "检索结果带 doc_id/chunk_seq，可说明这是某篇文档的第几块；search 传 semantic=true 且库已有向量时"
                + "会做语义扩召回（关键词 + 向量两路融合，能捞回关键词没命中的同义问法）。"
                + "知识库内容由用户维护，用于回答应用专属问题";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型：search(检索)/add(加一条)/add_batch(批量加)/update(改一条)/import_json(导入JSON字符串)/import_file(导入JSON文件)/import_document(直接导入文档·图片·音频)/delete(按id·标题·来源删)/export(导出备份)/clear(清空)/stats(统计)");
        params.put("query", "检索关键词（search 模糊检索用；可用用户原话，系统按虚词切分，不必手工压成两字词；精确取可省略）");
        params.put("category", "分类（search/list 过滤 / add 写入 / update 修改，可选）");
        params.put("tag", "标签/别名精确过滤（list/search 用）：对 keywords 做整词匹配，不做分词；别名/同义词/标签建议直接写进 keywords，逗号分隔");
        params.put("top_k", "返回条数上限（search 可选，默认5）");
        params.put("limit", "条数上限（list 可选，默认20，最大100）");
        params.put("offset", "翻页偏移（list 可选）：跳过前 N 条；返回里带 next_offset 时直接传它取下一页");
        params.put("semantic", "语义扩召回开关（search 可选，默认 true）：本地已有向量时关键词召回 + 向量余弦召回两路融合，能捞回关键词没命中的同义/口语化问法；还没回填向量或没配模型时自动退回关键词，不额外消费。传 false 可强制只走关键词");
        params.put("title", "标题（add 必填；update 时为新标题 / import_document 可选）");
        params.put("content", "内容（add 必填；update 时为新正文）");
        params.put("keywords", "关键词（add/update 可选）");
        params.put("source", "来源（add/update 可选；delete 传 source 会删掉该来源下的整篇）");
        params.put("id", "知识ID（get/delete/update 使用；search 传 id 表示精确取）");
        params.put("find_title", "按标题精确定位（update 用；仅当标题唯一时可用，命中多条会报错要求改用 id）");
        params.put("exact_title", "精确取：标题全等（search 用，与 id 二选一，不做模糊召回）");
        params.put("items", "批量知识数组（add_batch 必填）");
        params.put("json", "知识JSON字符串（import_json 必填）");
        params.put("file_path", "文件绝对路径（import_file 为JSON文件 / import_document 为文档、图片、音频 / export 为导出目标）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            if (actionObj == null) {
                return new AIToolResult("缺少参数: action（list/get/search/add/add_batch/update/import_json/import_file/import_document/delete/clear/stats）",
                        parameters);
            }
            String action = actionObj.toString();
            switch (action) {
                case "list":
                    return doList(parameters);
                case "get":
                    return doGet(parameters);
                case "search":
                    return doSearch(parameters);
                case "add":
                    return doAdd(parameters);
                case "add_batch":
                    return doAddBatch(parameters);
                case "update":
                    return doUpdate(parameters);
                case "import_json":
                    return doImportJson(parameters);
                case "import_file":
                    return doImportFile(parameters);
                case "import_document":
                    return doImportDocument(parameters);
                case "delete":
                    return doDelete(parameters);
                case "export":
                    return doExport(parameters);
                case "clear":
                    return doClear(parameters);
                case "stats":
                    return doStats(parameters);
                case "embed":
                    return doEmbed(parameters);
                default:
                    return new AIToolResult("未知操作: " + action
                            + "（支持 list/get/search/add/add_batch/update/import_json/import_file/import_document/delete/export/clear/stats/embed）", parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "execute 异常: " + e.getMessage(), e);
            return new AIToolResult("知识库操作失败: " + e.getMessage(), parameters);
        }
    }

    // ==================== 各动作实现 ====================

    /** 轻量索引：只回 id/标题/分类/关键词等，不含正文（先看有什么，再 get 详情） */
    private AIToolResult doList(Map<String, Object> parameters) {
        String category = stringParam(parameters, "category");
        String tag = stringParam(parameters, "tag");
        int limit = intParam(parameters, "limit", 20);
        int offset = intParam(parameters, "offset", 0);
        JSONArray rows = manager.listMeta(category, tag, limit, offset);
        String lastError = manager.getLastError();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "list");
        info.put("count", rows.length());
        info.put("offset", offset);
        info.put("tag", tag == null ? "" : tag);
        info.put("category", category == null ? "" : category);
        if (lastError != null && !lastError.isEmpty()) {
            info.put("error", lastError);
            return new AIToolResult("列出知识索引失败: " + lastError, info);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("count", rows.length());
            payload.put("offset", offset);
            // 拿满一页说明后面可能还有：直接告诉模型下一页从哪开始，省一轮试错
            if (rows.length() >= Math.min(limit <= 0 ? 20 : limit, 100)) {
                payload.put("next_offset", offset + rows.length());
            }
            payload.put("note", "这是索引（不含正文）；要正文用 action=get(id=…)，模糊检索用 action=search(query=…)");
            payload.put("items", rows);
        } catch (Exception ignored) {
        }
        return new AIToolResult(payload.toString(), info, true);
    }

    /** 按 id 取单条全文：精确、只回一条，不会把整库倒进上下文 */
    private AIToolResult doGet(Map<String, Object> parameters) {
        Map<String, Object> info = new HashMap<>();
        info.put("action", "get");
        long id = toLong(parameters.get("id"), -1);
        if (id <= 0) {
            return new AIToolResult("缺少参数: id（先用 list 或 search 拿到 id）", parameters);
        }
        JSONObject row = manager.getById(id);
        if (row == null) {
            String err = manager.getLastError();
            info.put("id", id);
            info.put("error", err == null ? "" : err);
            return new AIToolResult(err != null && !err.isEmpty() ? err : ("未找到 id=" + id), info);
        }
        info.put("id", id);
        return new AIToolResult(row.toString(), info, true);
    }

    private AIToolResult doSearch(Map<String, Object> parameters) {
        // 精确取（2026-10-28）：给了 id / exact_title 就不做模糊召回，命中即命中
        long exactId = toLong(parameters.get("id"), -1);
        String exactTitle = stringParam(parameters, "exact_title");
        if (exactId > 0 || (exactTitle != null && !exactTitle.trim().isEmpty())) {
            int topK = intParam(parameters, "top_k", 5);
            JSONArray rows = manager.searchExact(exactId > 0 ? exactId : null, exactTitle, topK);
            String err = manager.getLastError();
            Map<String, Object> info = new HashMap<>();
            info.put("action", "search");
            info.put("mode", "exact");
            info.put("hits", rows.length());
            if (err != null && !err.isEmpty()) {
                info.put("error", err);
                return new AIToolResult(err, info);
            }
            return new AIToolResult(rows.toString(), info, true);
        }
        String query = stringParam(parameters, "query");
        if (query == null || query.trim().isEmpty()) {
            return new AIToolResult("缺少参数: query（模糊检索）或 id / exact_title（精确取）", parameters);
        }
        String category = stringParam(parameters, "category");
        int topK = intParam(parameters, "top_k", 5);
        // v6：默认开启语义扩召回——库里有向量时查询侧只多 1 次 embedding，能捞回关键词没命中的同义问法；
        // 没回填向量 / 没配模型时自动退回关键词（不报错、不额外消费）。显式 semantic=false 可关掉。
        Object semanticObj = parameters.get("semantic");
        boolean semantic = semanticObj == null
                || Boolean.TRUE.equals(semanticObj)
                || "true".equalsIgnoreCase(String.valueOf(semanticObj).trim());
        String tag = stringParam(parameters, "tag");
        // 分类隔离已在 v5 下推到 SQL（manager.search 内：不传 category 时 WHERE category <> 'tool_defs'）。
        // 这里不再做 Java 侧二次剔除——那层过滤发生在 top_k 之后，会把召回名额白让给工具定义。
        JSONArray results = manager.search(query, category, tag, topK, semantic);
        String lastError = manager.getLastError();

        Map<String, Object> info = new HashMap<>();
        info.put("action", "search");
        info.put("query", query);
        info.put("category", category == null ? "" : category);
        info.put("hits", results.length());

        if (results.length() == 0) {
            if (lastError != null && !lastError.isEmpty()) {
                // 检索失败与"确实没有内容"必须区分：否则模型会把数据库错误当成"知识库为空"
                info.put("error", lastError);
                return new AIToolResult("{\"hits\":0,\"error\":\"" + escape(lastError)
                        + "\",\"message\":\"知识库检索失败：" + escape(lastError) + "\"}", info);
            }
            // 0 命中是正常结果（不是失败）：用 3 参构造显式标成功，
            // 否则 AIToolResult(String, Map) 会被当成"工具执行失败"，在线 Agent 看到的就是"工具执行失败"而非 hits:0
            return new AIToolResult("{\"hits\":0,\"message\":\"知识库中未找到与 \"" + query + "\" 相关的内容\"}",
                    info, true);
        }
        // 结果本身是核心交付：直接作为 result 返回 JSON 字符串（必须显式标成功，String 走的是失败重载）
        return new AIToolResult(results.toString(), info, true);
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
                    info, true);
        }
        return new AIToolResult("知识添加失败"
                + (manager.getLastError() != null ? "：" + manager.getLastError() : ""), parameters);
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
        String lastError = manager.getLastError();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "add_batch");
        info.put("added", added);
        if (lastError != null && !lastError.isEmpty()) {
            info.put("error", lastError);
            return new AIToolResult("{\"success\":false,\"added\":" + added
                    + ",\"error\":\"" + escape(lastError) + "\"}", info);
        }
        return new AIToolResult("{\"success\":true,\"added\":" + added + "}", info, true);
    }

    /**
     * 修改一条已入库知识：id 优先；没给 id 时按 {@code find_title} 精确匹配，
     * 必须唯一命中（文档导入会按标题切块，同名多条时要模型改用 id）。
     * 只改传入的非空字段，条目 id / created_at 不变。
     */
    private AIToolResult doUpdate(Map<String, Object> parameters) {
        Map<String, Object> info = new HashMap<>();
        info.put("action", "update");

        long id = toLong(parameters.get("id"), -1);
        String findTitle = stringParam(parameters, "find_title");
        if (id <= 0) {
            if (isBlank(findTitle)) {
                return new AIToolResult("缺少参数: id（search 结果里带 id）或 find_title（标题唯一时）", parameters);
            }
            long resolved = manager.findUniqueIdByTitle(findTitle.trim());
            if (resolved <= 0) {
                String err = manager.getLastError();
                return new AIToolResult(err != null && !err.isEmpty() ? err
                        : "未找到标题为「" + findTitle.trim() + "」的知识", parameters);
            }
            id = resolved;
        }

        String title = stringParam(parameters, "title");
        String content = stringParam(parameters, "content");
        String category = stringParam(parameters, "category");
        String keywords = stringParam(parameters, "keywords");
        String source = stringParam(parameters, "source");
        if (isBlank(title) && isBlank(content) && isBlank(category)
                && isBlank(keywords) && isBlank(source)) {
            return new AIToolResult("没有要修改的字段：title/content/category/keywords/source 至少传一个", parameters);
        }

        boolean ok = manager.updateChunk(id, title, category, keywords, content, source);
        info.put("id", id);
        if (!ok) {
            String err = manager.getLastError();
            info.put("error", err == null ? "" : err);
            return new AIToolResult("更新失败" + (err != null && !err.isEmpty() ? "：" + err : ""), info);
        }

        // 回给模型"改了哪几项"，便于它向用户复述；字段值本身不回声（可能很长）
        JSONObject payload = new JSONObject();
        int changed = 0;
        try {
            payload.put("success", true);
            payload.put("id", id);
            JSONArray fields = new JSONArray();
            if (!isBlank(title)) { fields.put("title"); changed++; }
            if (!isBlank(content)) { fields.put("content"); changed++; }
            if (!isBlank(category)) { fields.put("category"); changed++; }
            if (!isBlank(keywords)) { fields.put("keywords"); changed++; }
            if (!isBlank(source)) { fields.put("source"); changed++; }
            payload.put("updated", fields);
            payload.put("updated_count", changed);
        } catch (Exception stepFailed) {
            com.oilquiz.app.util.AILogger.w("KnowledgeBaseTool", "这一步失败，结果可能被当成正常 · doUpdate: " + stepFailed);
        }
        info.put("updated", changed);
        return new AIToolResult(payload.toString(), info, true);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private AIToolResult doImportJson(Map<String, Object> parameters) {
        String json = stringParam(parameters, "json");
        if (json == null || json.trim().isEmpty()) {
            return new AIToolResult("缺少参数: json（知识JSON字符串）", parameters);
        }
        int added = manager.importJson(json);
        String lastError = manager.getLastError();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "import_json");
        info.put("added", added);
        if (lastError != null && !lastError.isEmpty()) {
            info.put("error", lastError);
            return new AIToolResult("导入失败: " + lastError, info);
        }
        return new AIToolResult("{\"success\":true,\"added\":" + added + "}", info, true);
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
            String lastError = manager.getLastError();
            Map<String, Object> info = new HashMap<>();
            info.put("action", "import_file");
            info.put("file_path", filePath);
            info.put("added", added);
            if (lastError != null && !lastError.isEmpty()) {
                info.put("error", lastError);
                return new AIToolResult("导入文件失败: " + lastError, info);
            }
            return new AIToolResult("{\"success\":true,\"added\":" + added + "}", info, true);
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
                    + ",\"message\":\"" + escape(msg) + "\"}", info, true);
        }
        return new AIToolResult("文档导入失败: " + result.optString("message", "未知错误"), info);
    }

    private AIToolResult doDelete(Map<String, Object> parameters) {
        Object idObj = parameters.get("id");
        String title = stringParam(parameters, "title");
        String source = stringParam(parameters, "source");
        Map<String, Object> info = new HashMap<>();
        info.put("action", "delete");

        if (idObj != null) {
            long id = toLong(idObj, -1);
            if (id <= 0) {
                return new AIToolResult("id 格式错误: " + idObj, parameters);
            }
            boolean ok = manager.deleteById(id);
            info.put("deleted", ok ? 1 : 0);
            return new AIToolResult("{\"success\":" + ok + ",\"deleted\":" + (ok ? 1 : 0) + "}", info, true);
        }
        if (title != null && !title.trim().isEmpty()) {
            int deleted = manager.deleteByTitle(title.trim());
            info.put("deleted", deleted);
            // 文档导入的多块同标题会一起删掉：明确说清楚，别让模型以为只删了一条
            String body = deleted > 1
                    ? "{\"success\":true,\"deleted\":" + deleted + ",\"note\":\"同标题 " + deleted + " 块一并删除\"}"
                    : "{\"success\":true,\"deleted\":" + deleted + "}";
            return new AIToolResult(body, info, true);
        }
        if (source != null && !source.trim().isEmpty()) {
            int deleted = manager.deleteBySource(source.trim());
            info.put("deleted", deleted);
            return new AIToolResult("{\"success\":true,\"deleted\":" + deleted + "}", info, true);
        }
        return new AIToolResult("缺少参数: id、title 或 source", parameters);
    }

    /** 导出备份：结构与 import_json 一致，可直接 import_file 导回 */
    private AIToolResult doExport(Map<String, Object> parameters) {
        String filePath = stringParam(parameters, "file_path");
        String category = stringParam(parameters, "category");
        JSONObject result = manager.exportJson(filePath, category);
        Map<String, Object> info = new HashMap<>();
        info.put("action", "export");
        info.put("file", result.optString("file"));
        info.put("count", result.optInt("count", 0));
        if (!result.optBoolean("success", false)) {
            return new AIToolResult(result.optString("message", "导出失败"), info);
        }
        return new AIToolResult(result.toString(), info, true);
    }

    private AIToolResult doClear(Map<String, Object> parameters) {
        int cleared = manager.clear();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "clear");
        info.put("cleared", cleared);
        return new AIToolResult("{\"success\":true,\"cleared\":" + cleared
                + ",\"message\":\"已清空（含系统工具定义，下次初始化会自动重灌）\"}", info, true);
    }

    private AIToolResult doStats(Map<String, Object> parameters) {
        JSONObject stats = manager.stats();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "stats");
        return new AIToolResult(stats.toString(), info, true);
    }

    /**
     * 补齐向量：给老库/换模型后用。已有向量的块跳过。
     *
     * <p>没有任何向量来源时（既没下本地模型、也没配在线 embedding），触发一次后台下载（约 98MB），
     * 立即返回不阻塞——下载与推理都在后台，绝不把"模型没下"变成工具调用卡住。</p>
     */
    private AIToolResult doEmbed(Map<String, Object> parameters) {
        // local=true：明确要"离线自足"，即使在线端点可用也先把本地模型拉下来
        Object localObj = parameters.get("local");
        boolean forceLocal = localObj != null
                && (Boolean.TRUE.equals(localObj) || "true".equalsIgnoreCase(String.valueOf(localObj).trim()));
        if (forceLocal && !manager.localEmbedding().isAvailable()) {
            boolean started = manager.localEmbedding().downloadInBackground(null);
            JSONObject payload = new JSONObject();
            Map<String, Object> info = new HashMap<>();
            info.put("action", "embed");
            info.put("embedding_source", "downloading");
            info.put("local_embedding_dir", manager.localEmbedding().dir().getAbsolutePath());
            try {
                payload.put("download", started ? "started" : "failed");
                payload.put("embedding_source", "downloading");
                payload.put("local_embedding_dir", manager.localEmbedding().dir().getAbsolutePath());
                payload.put("message", started
                        ? "已开始后台下载本地向量模型（bge-base-zh-v1.5，约 98MB，走 hf-mirror）；"
                        + "下载完再调一次 embed，就会改用本地模型重算全部向量。"
                        : "本地向量模型下载未能启动（可能已在下载中）。");
            } catch (Exception ignored) {
            }
            return new AIToolResult(payload.toString(), info, true);
        }

        JSONObject pre = manager.stats();
        String source = pre.optString("embedding_source", "none");
        if ("none".equals(source) || "downloading".equals(source)) {
            boolean started = "downloading".equals(source)
                    || manager.localEmbedding().downloadInBackground(null);
            JSONObject payload = new JSONObject();
            Map<String, Object> info = new HashMap<>();
            info.put("action", "embed");
            info.put("embedding_source", started ? "downloading" : "none");
            info.put("local_embedding_dir", pre.optString("local_embedding_dir"));
            try {
                payload.put("embedding_source", started ? "downloading" : "none");
                payload.put("download", started ? "started" : "failed");
                payload.put("local_embedding_dir", pre.optString("local_embedding_dir"));
                payload.put("message", started
                        ? "正在后台下载本地向量模型（bge-base-zh-v1.5，约 98MB，走 hf-mirror）；下完再调一次 embed 即可补向量。"
                        : "既没有本地向量模型、也没有可用的在线 embedding 端点，无法补全向量。");
            } catch (Exception ignored) {
            }
            return new AIToolResult(payload.toString(), info, true);
        }

        int added = manager.backfillAllEmbeddings();
        JSONObject stats = manager.stats();
        Map<String, Object> info = new HashMap<>();
        info.put("action", "embed");
        info.put("embedded_now", added);
        info.put("embedded_total", stats.optInt("embedded"));
        info.put("embedding_source", stats.optString("embedding_source"));
        String err = manager.getLastError();
        if (err != null && !err.isEmpty()) {
            info.put("error", err);
            return new AIToolResult("补全向量失败: " + err, info);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("embedded_now", added);
            payload.put("embedded_total", stats.optInt("embedded"));
            payload.put("total", stats.optInt("total"));
            payload.put("embedding_source", stats.optString("embedding_source"));
            if (added == 0) {
                payload.put("message", "没有新增向量：要么全都已向量化，要么当前来源不可用"
                        + "（本地模型未就绪 / 在线端点没声明 embedding 模型）");
            } else if ("online".equals(stats.optString("embedding_source"))) {
                payload.put("hint", "本批用的是在线接口；想离线可用（不烧额度）再调一次 embed 并传 local=true，"
                        + "会下载约 98MB 本地模型并自动改用它重算。");
            }
        } catch (Exception ignored) {
            // 普通字段不会触发
        }
        return new AIToolResult(payload.toString(), info, true);
    }

    // ==================== 参数取值工具 ====================

    private static String stringParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : value.toString();
    }

    private static int intParam(Map<String, Object> parameters, String key, int def) {
        return (int) toLong(parameters.get(key), def);
    }

    /**
     * 宽松解析整型参数：兼容模型/引导卡片传来的 Integer、Long、Double(5.0)、String("5"/"5.0")，
     * 解析不了返回 def（不抛异常）。在线 function calling 传的 JSON 整数是 Integer，
     * 但模型偶尔会写成小数或字符串，这里统一兜住。
     */
    private static long toLong(Object value, long def) {
        if (value == null) {
            return def;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return (long) Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
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
