package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（类 MCP 协议）：Agent 可自行查找可用工具与参数 schema，按需获取，避免全量注入浪费 token。
 *
 * 用法（类比 MCP tools/list + tools/call）：
 *  - tool_registry(action="list")            → 全部工具名+简短描述（轻量）
 *  - tool_registry(action="search", keyword="ui") → 按关键词找工具
 *  - tool_registry(action="get", tool="ai_weather") → 引擎自动加载该工具定义，返回参数键名概览（轻量，不返回完整 schema）
 *
 * 与系统提示词配合：模型先 list 知道有哪些工具，需要细节时再 get 单个 schema，
 * 主流程工具定义按消息意图裁剪注入，进一步省 token。
 */
@Tool(
    value = "tool_registry",
    description = "工具注册表(MCP式工具发现)：列出可用工具(list)、按关键词搜索工具(search)、获取单个工具完整参数schema(get)。模型不确定有哪些工具或需要某工具详细参数时调用，避免猜测工具名/参数。",
    category = "meta",
    actions = {
        @Action(name = "list", description = "列出全部可用工具名（默认只回名字，detail=true 才带简短描述；按关键词找工具请用 search）"),
        @Action(name = "search", description = "按关键词搜索工具"),
        @Action(name = "get", description = "获取单个工具的完整参数schema")
    },
    params = {
        @Param(name = "action", type = "string", description = "list/search/get", required = true),
        @Param(name = "keyword", type = "string", description = "关键词：search 搜索；list 按名/描述过滤（可选）", required = false),
        @Param(name = "tool", type = "string", description = "工具名(仅get用)", required = false),
        @Param(name = "detail", type = "boolean", description = "list 是否带描述（默认 false，只回工具名）", required = false)
    }
)
public class ToolRegistryTool implements AITool {
    private static final String TAG = "ToolRegistryTool";
    private final Context context;

    public ToolRegistryTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "tool_registry";
    }

    @Override
    public String getDescription() {
        return "工具注册表(MCP式工具发现)：列出可用工具(list)、按关键词搜索工具(search)、获取单个工具完整参数schema(get)。模型不确定有哪些工具或需要某工具详细参数时调用，避免猜测工具名/参数。";
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            String action = actionObj != null ? actionObj.toString() : "list";

            switch (action) {
                case "list":
                    return listTools(parameters);
                case "search": {
                    Object kw = parameters.get("keyword");
                    String keyword = kw != null ? kw.toString().toLowerCase() : "";
                    return searchTools(keyword);
                }
                case "get": {
                    Object toolObj = parameters.get("tool");
                    if (toolObj == null) {
                        return new AIToolResult("缺少参数: tool（要查的工具名）", parameters);
                    }
                    return getToolSchema(toolObj.toString());
                }
                default:
                    return new AIToolResult("未知操作: " + action + "（支持 list/search/get）", parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "工具注册表执行失败: " + e.getMessage(), e);
            return new AIToolResult("工具注册表执行失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult listTools(Map<String, Object> parameters) {
        try {
            com.oilquiz.app.ai.tool.AIToolManager mgr =
                    com.oilquiz.app.ai.tool.AIToolManager.getInstance(context);
            List<Map<String, Object>> descriptions = mgr.getToolDescriptions();
            // keyword 过滤（可选，2026-09-23 v3.1：list 与 search 共用 keyword，
            // 防"schema 只有 search 用 keyword、list 传了被忽略"的误用）
            String filter = null;
            Object kwObj = parameters.get("keyword");
            if (kwObj != null && !kwObj.toString().trim().isEmpty()) {
                filter = kwObj.toString().trim().toLowerCase();
            }
            // 分页（可选）：limit=每页条数(-1 全量)、offset=跳过条数
            int limit = -1, offset = 0;
            Object limObj = parameters.get("limit");
            if (limObj != null) {
                try { limit = Integer.parseInt(limObj.toString()); } catch (Exception ignored) {}
            }
            Object offObj = parameters.get("offset");
            if (offObj != null) {
                try { offset = Math.max(0, Integer.parseInt(offObj.toString())); } catch (Exception ignored) {}
            }
            List<Map<String, Object>> tools = new ArrayList<>();
            int total = descriptions.size();
            // 只回工具名（2026-10-28）：list 的用途是"知道有哪些"，描述会让一页目录膨胀到几千 token。
            // 需要描述用 search(按关键词) 或 detail=true；需要参数 schema 用 get。
            boolean detail = false;
            Object detailObj = parameters.get("detail");
            if (detailObj != null) {
                String v = detailObj.toString().trim();
                detail = "true".equalsIgnoreCase(v) || "1".equals(v);
            }
            for (int i = 0; i < total; i++) {
                Map<String, Object> d = descriptions.get(i);
                String toolName = String.valueOf(d.get("name"));
                // 别名参与筛选（2026-10-29 评审）：只按现名筛，搜旧名会"列不到"，
                // 而 get(旧名) 又能命中——两边矛盾，模型就没机会怀疑自己叫错了工具。
                java.util.List<String> aliasList = mgr.aliasesOf(toolName);
                if (filter != null) {
                    String dn = toolName.toLowerCase();
                    String dd = d.get("description") != null
                            ? String.valueOf(d.get("description")).toLowerCase() : "";
                    boolean aliasHit = false;
                    for (String a : aliasList) {
                        if (a != null && a.toLowerCase().contains(filter)) { aliasHit = true; break; }
                    }
                    if (!dn.contains(filter) && !dd.contains(filter) && !aliasHit) continue;
                }
                if (offset > 0) { offset--; continue; }
                if (limit >= 0 && tools.size() >= limit) break;
                Map<String, Object> t = new HashMap<>();
                t.put("name", toolName);
                if (detail) {
                    Object desc = d.get("description");
                    // 目录用途：描述截断到 40 字，防工具结果 16KB 截断变非法 JSON
                    String s = desc != null ? String.valueOf(desc) : "";
                    if (s.length() > 40) s = s.substring(0, 40) + "…";
                    t.put("description", s);
                    if (!aliasList.isEmpty()) t.put("aliases", aliasList);
                }
                tools.add(t);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("count", tools.size());
            result.put("total", total);
            // 2026-09-23 v3：原生 List/Map（Gson 序列化成干净 JSON 数组）——
            // org.json.JSONArray 会被 Gson 当普通对象序列化成 {"values":[{"nameValuePairs":..}]} 嵌套结构
            result.put("tools", tools);
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("列出工具失败: " + e.getMessage(), null);
        }
    }

    private AIToolResult searchTools(String keyword) {
        try {
            com.oilquiz.app.ai.tool.AIToolManager mgr =
                    com.oilquiz.app.ai.tool.AIToolManager.getInstance(context);
            List<Map<String, Object>> descriptions = mgr.getToolDescriptions();
            String kw = keyword.toLowerCase().trim();
            List<Map<String, Object>> nameHits = new ArrayList<>();
            List<Map<String, Object>> aliasHits = new ArrayList<>();
            List<Map<String, Object>> descHits = new ArrayList<>();
            for (Map<String, Object> d : descriptions) {
                String name = String.valueOf(d.get("name"));
                String desc = d.get("description") != null ? String.valueOf(d.get("description")) : "";
                String lowerName = name.toLowerCase();
                String lowerDesc = desc.toLowerCase();
                // 命中原因透明化（2026-09-23 v3，模型第二轮测试反馈）：返回 matched 字段
                // 说明命中在 name 还是 description——截断后描述看不出"为什么命中"
                boolean hitName = kw.isEmpty() || lowerName.contains(kw);
                // 别名命中（2026-10-29 评审）：旧名/别名要能被 search 检出，否则"搜不到却 get 得到"
                String hitAlias = null;
                if (!hitName && !kw.isEmpty()) {
                    for (String a : mgr.aliasesOf(name)) {
                        if (a != null && a.toLowerCase().contains(kw)) { hitAlias = a; break; }
                    }
                }
                boolean hitDesc = !hitName && hitAlias == null && lowerDesc.contains(kw);
                if (hitName || hitAlias != null || hitDesc) {
                    Map<String, Object> t = new HashMap<>();
                    t.put("name", name);
                    if (desc.length() > 60) desc = desc.substring(0, 60) + "…";
                    t.put("description", desc);
                    if (!kw.isEmpty()) {
                        t.put("matched", hitName ? "name" : (hitAlias != null ? "alias" : "description"));
                        if (hitAlias != null) t.put("matched_alias", hitAlias);
                    }
                    (hitName ? nameHits : (hitAlias != null ? aliasHits : descHits)).add(t);
                }
            }
            // 2026-09-23 v3.1（模型第三轮反馈）：相关度排序——精确名命中优先于描述命中，
            // 避免搜 uicomponent 时精确名排第 5
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.addAll(nameHits);
            tools.addAll(aliasHits);
            tools.addAll(descHits);
            Map<String, Object> result = new HashMap<>();
            result.put("count", tools.size());
            result.put("tools", tools);
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("搜索工具失败: " + e.getMessage(), null);
        }
    }

    private AIToolResult getToolSchema(String toolName) {
        try {
            com.oilquiz.app.ai.tool.AIToolManager mgr = com.oilquiz.app.ai.tool.AIToolManager
                    .getInstance(context);
            // 模糊解析：模型可能用猜测名（"weather"→"ai_weather"），命中则返回真实工具 schema
            com.oilquiz.app.ai.tool.AIToolManager.NameResolution res = mgr.resolveTool(toolName);
            String resolved = res.found() ? res.tool : null;
            String effective = resolved != null ? resolved : toolName;
            com.oilquiz.app.ai.tool.openai.ToolDefinition def = mgr.getToolDefinition(effective);
            if (def == null) {
                String hint = "";
                if (resolved == null) {
                    // 给出候选，帮助模型纠正工具名
                    java.util.List<String> candidates = mgr.searchToolNamesByKeyword(toolName, 5);
                    if (!candidates.isEmpty()) {
                        hint = "，相近工具: " + String.join(", ", candidates);
                    }
                }
                return new AIToolResult("工具不存在: " + toolName + hint + "（用 tool_registry(list) 查看全部）", null);
            }
            // 2026-09-23 v2（模型自测反馈修复）：get 返回完整参数说明（name/type/required/description），
            // 模型当轮即可看到类型/枚举/约束（如互斥、字节限制），不再"只能靠猜"；
            // description 截断 80 字防超长。同时引擎层仍会把该工具定义注入会话工具缓存
            // （发现制闭环），下一轮 tools 数组即携带完整 schema 可直接调用。
            // v3：全部用原生集合（Gson 输出干净 JSON），不再 org.json + toString 转义
            // 2026-10-29（评审 REVIEW-2026-1029-01）：
            //  ① 输出自曝命中方式 matched_by，措辞按档分——只有精确命中才准说"已加载完整定义"，
            //     别名/回退必须明说"这是替换的结果"，否则模型把猜的当事实；
            //  ② 带上工具级 description：旧名（python_file_ops）被别名带到只读的 document_parser 时，
            //     正是这句"不写文件——写/改/差异请用 file_generator"能拦住乱调；
            //  ③ 参数描述 80→160 字并带 description_truncated 标记，有枚举就单列 enum，
            //     不再把 file_generator.action 的枚举切尾。
            List<Map<String, Object>> keys = new ArrayList<>();
            if (def.getParameters() != null) {
                for (com.oilquiz.app.ai.tool.openai.ParamDefinition p : def.getParameters()) {
                    Map<String, Object> k = new HashMap<>();
                    k.put("name", p.getName());
                    k.put("type", p.getType() != null ? p.getType() : "");
                    k.put("required", p.isRequired());
                    String d = p.getDescription() != null ? p.getDescription() : "";
                    if (d.length() > 160) {
                        k.put("description", d.substring(0, 160) + "…");
                        k.put("description_truncated", true);
                    } else {
                        k.put("description", d);
                    }
                    java.util.List<String> enums = p.getEnumValues();
                    if (enums != null && !enums.isEmpty()) {
                        k.put("enum", new ArrayList<>(enums));
                    }
                    keys.add(k);
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("tool", def.getName());
            result.put("requested_tool", toolName);
            result.put("matched_by", matchedByLabel(res.match));
            result.put("hint", hintFor(res.match, toolName, def.getName()));
            String tdesc = def.getDescription() != null ? def.getDescription() : "";
            if (tdesc.length() > 400) {
                result.put("description", tdesc.substring(0, 400) + "…");
                result.put("description_truncated", true);
            } else {
                result.put("description", tdesc);
            }
            java.util.List<String> aliases = mgr.aliasesOf(def.getName());
            if (!aliases.isEmpty()) result.put("aliases", aliases);
            result.put("param_keys", keys);
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("获取工具schema失败: " + e.getMessage(), null);
        }
    }

    /** 命中方式 → 对外词汇（评审口径：exact / alias / normalized / fuzzy / not_found）。 */
    static String matchedByLabel(com.oilquiz.app.ai.tool.AIToolManager.NameMatch m) {
        if (m == null) return "not_found";
        switch (m) {
            case EXACT: return "exact";
            case ALIAS: return "alias";
            case NORMALIZED: return "normalized";
            case FUZZY: return "fuzzy";
            default: return "not_found";
        }
    }

    /**
     * 措辞按命中方式分档。<b>只有 exact 允许出现"已加载完整定义"</b>——这是本次评审的核心：
     * 路由/别名可以猜，但不能把猜说成确定。
     */
    static String hintFor(com.oilquiz.app.ai.tool.AIToolManager.NameMatch m, String requested, String resolved) {
        if (m == com.oilquiz.app.ai.tool.AIToolManager.NameMatch.EXACT) {
            return "已加载完整定义（下一轮可直接调用）；以下是参数说明，按说明填参。";
        }
        if (m == com.oilquiz.app.ai.tool.AIToolManager.NameMatch.ALIAS) {
            return "「" + requested + "」不是注册名，它是「" + resolved + "」的历史名/别名（同一个工具，不是两个）。"
                    + "下面是 " + resolved + " 的<b>当前</b>定义：能力以这份为准，旧名里已去掉的动作别再照旧填参，"
                    + "替代工具看 description。";
        }
        if (m == com.oilquiz.app.ai.tool.AIToolManager.NameMatch.NORMALIZED) {
            return "「" + requested + "」按名字写法归一到「" + resolved + "」（大小写/分隔符差异），"
                    + "下面是 " + resolved + " 的定义。";
        }
        return "未精确匹配「" + requested + "」，已回退到最相近的「" + resolved + "」；"
                + "参数可能与预期不同，请按下面这份 schema 调用，不要照 " + requested + " 的旧参数填。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> desc = new HashMap<>();
        desc.put("action", "list(列出)/search(搜索)/get(取schema)");
        desc.put("keyword", "搜索关键词(search用)");
        desc.put("tool", "工具名(get用)");
        desc.put("detail", "list 是否带描述（默认 false）");
        return desc;
    }
}
