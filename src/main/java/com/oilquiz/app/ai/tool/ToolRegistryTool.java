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
        @Action(name = "get", description = "获取单个工具的完整参数schema"),
        @Action(name = "call", description = "代执行一个已注册工具：tool=工具名，args=参数JSON对象字符串。用于当前工具集里没有该工具、但又必须调用的场景")
    },
    params = {
        @Param(name = "action", type = "string", description = "list/search/get/call", required = true),
        @Param(name = "keyword", type = "string", description = "关键词：search 搜索；list 按名/描述过滤（可选）", required = false),
        @Param(name = "tool", type = "string", description = "工具名（get/call 用）", required = false),
        @Param(name = "args", type = "string", description = "call 的参数 JSON 对象字符串，如 {\"path\":\"/sdcard/a.txt\"}", required = false),
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

    /**
     * CALL-TIMEOUT(2026-11-02)：{@code call} 是**代执行别的工具**，等待时长必须跟着被代执行的那个工具走。
     *
     * <p>否则发现通道会把慢工具判成失败：{@code network_search(action=ask)} 自己申报 180 秒，
     * 但经 {@code tool_registry(call)} 执行时只享受调用方基线（本地 15 秒），
     * 结果就是"秘塔明明答得上来，却因等 15 秒被判失败"，而它按次计费、这一次已经扣掉了。</p>
     */
    @Override
    public long executionTimeoutMs(java.util.Map<String, Object> args) {
        try {
            if (args == null || !"call".equals(String.valueOf(args.get("action")))) return 0L;
            Object t = args.get("tool");
            if (t == null) return 0L;
            String target = String.valueOf(t).trim();
            if (target.isEmpty() || "tool_registry".equals(target) || context == null) return 0L;
            com.oilquiz.app.ai.tool.AITool inner =
                    com.oilquiz.app.ai.tool.AIToolManager.getInstance(context).getTool(target);
            if (inner == null) return 0L;
            java.util.Map<String, Object> innerArgs = parseCallArgs(args.get("args"));
            return inner.executionTimeoutMs(innerArgs);
        } catch (Throwable ignored) {
            // 申报只是"允许多等一会儿"，读不到就退回调用方基线，绝不能把一次正常调用判成失败
            return 0L;
        }
    }

    /** call 的 args 可能是 Map 也可能是 JSON 字符串（模型两种都会写），两种都要能读出来算超时。 */
    private static java.util.Map<String, Object> parseCallArgs(Object rawArgs) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (rawArgs == null) return out;
        if (rawArgs instanceof java.util.Map) {
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) rawArgs).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(String.valueOf(rawArgs).trim());
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.get(k));
            }
        } catch (Exception ignored) {
            // 解析不出就按基线等，callTool 自己会给出"args 不是合法 JSON 对象"的错误
        }
        return out;
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
                case "call":
                    return callTool(parameters);
                default:
                    return new AIToolResult("未知操作: " + action + "（支持 list/search/get/call）", parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "工具注册表执行失败: " + e.getMessage(), e);
            return new AIToolResult("工具注册表执行失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * CALL(2026-11-01)：代执行一个「发现到的」工具。
     *
     * <p>为什么必须有这一档：本地 Agent 的工具名由 native PEG <b>逐字枚举</b>注入的 schema，
     * 会话中途把新工具塞进工具集会毁掉 tools 前缀 → 全量 re-prefill（实测 ~20s 首字）。
     * 所以发现必须能直接落地为执行，否则 list/get 只是给模型看一眼它根本调不到的东西。</p>
     *
     * <p>约束：args 接受 JSON 对象字符串或对象本身；只允许一层，禁止调用自身——
     * 否则 create_dynamic_tool / 自调用可以无限套娃。</p>
     */
    private AIToolResult callTool(Map<String, Object> parameters) {
        Object toolObj = parameters.get("tool");
        if (toolObj == null || String.valueOf(toolObj).trim().isEmpty()) {
            return new AIToolResult("缺少参数: tool（要调用的工具名）", parameters);
        }
        String target = String.valueOf(toolObj).trim();
        if ("tool_registry".equals(target)) {
            return new AIToolResult("不能通过 call 调用 tool_registry 自身；list/search/get 直接指定 action 即可", parameters);
        }
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        Object rawArgs = parameters.get("args");
        if (rawArgs != null) {
            if (rawArgs instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) rawArgs).entrySet()) {
                    args.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                String s = String.valueOf(rawArgs).trim();
                if (!s.isEmpty()) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(s);
                        java.util.Iterator<String> it = o.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            args.put(k, o.get(k));
                        }
                    } catch (Exception ex) {
                        return new AIToolResult("args 不是合法 JSON 对象: " + ex.getMessage(), parameters);
                    }
                }
            }
        }
        if (context == null) {
            return new AIToolResult("缺少上下文，无法代执行工具: " + target, parameters);
        }
        AIToolResult r = com.oilquiz.app.ai.tool.AIToolManager
                .getInstance(context).executeTool(target, args);
        return r != null ? r : new AIToolResult("工具执行无返回: " + target, parameters);
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
                    // 多关键词（"打开应用 启动"）按**任一词**命中即可：整串当子串会让自然语言查询
                    // 恒为 0 条（2026-11-06 真机：搜"打开应用 启动 intent"搜不到 system_resource，
                    // 模型于是断言"没有打开应用的能力"，改去挂按钮）。单关键词行为不变。
                    String dn = toolName.toLowerCase();
                    String dd = d.get("description") != null
                            ? String.valueOf(d.get("description")).toLowerCase() : "";
                    boolean keywordHit = false;
                    for (String token : splitSearchTokens(filter)) {
                        if (dn.contains(token) || dd.contains(token)) { keywordHit = true; break; }
                        for (String a : aliasList) {
                            if (a != null && a.toLowerCase().contains(token)) { keywordHit = true; break; }
                        }
                        if (keywordHit) break;
                    }
                    if (!keywordHit) continue;
                }
                if (offset > 0) { offset--; continue; }
                if (limit >= 0 && tools.size() >= limit) break;
                Map<String, Object> t = new HashMap<>();
                t.put("name", toolName);
                if (detail) {
                    Object desc = d.get("description");
                    // NO-CLIP(2026-11-02，用户口径"api 返回的数据不要截断")：目录描述**全文返回**。
                    // 以前切 40/120 字，正好把"默认用 ask""按次计费"这类关键口径切成半句，
                    // 模型在发现通道里读到残句只能靠猜；描述是给它看的，给全才起作用。
                    String s = desc != null ? String.valueOf(desc) : "";
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
            String kw = keyword == null ? "" : keyword.toLowerCase().trim();
            // 分词而不是整串子串匹配（2026-11-06 真机）：
            // 模型很自然地写 "打开应用 启动 intent 应用操作"，而没有任何一条描述包含这一整串，
            // 于是 count=0。它据此判定"没有打开应用的能力"，最后去造一个点不动的卡片按钮。
            java.util.List<String> tokens = splitSearchTokens(kw);
            List<SearchHit> hits = new ArrayList<>();
            for (Map<String, Object> d : descriptions) {
                String name = String.valueOf(d.get("name"));
                String desc = d.get("description") != null ? String.valueOf(d.get("description")) : "";
                // 命中原因透明化（2026-09-23 v3）：matched 说明命中在 name / alias / description；
                // 多词查询再补 matched_tokens，让模型知道是哪个词把这条捞出来的。
                SearchScore score = scoreTool(name, desc, mgr.aliasesOf(name), tokens);
                if (score.score <= 0) continue;
                Map<String, Object> t = new HashMap<>();
                t.put("name", name);
                // NO-CLIP(2026-11-02)：搜索结果同样给全文描述，不裁字数。
                t.put("description", desc);
                if (!kw.isEmpty()) {
                    t.put("matched", score.tier == 3 ? "name" : (score.tier == 2 ? "alias" : "description"));
                    if (score.alias != null) t.put("matched_alias", score.alias);
                    if (tokens.size() > 1 && !score.hitTokens.isEmpty()) {
                        t.put("matched_tokens", new ArrayList<>(score.hitTokens));
                    }
                }
                hits.add(new SearchHit(score, name, t));
            }
            // 相关度排序：命中词多/命中在名字上优先（避免搜 uicomponent 时精确名排第 5）
            hits.sort((a, b) -> {
                if (b.score.score != a.score.score) return b.score.score - a.score.score;
                if (b.score.tier != a.score.tier) return b.score.tier - a.score.tier;
                return a.name.compareTo(b.name);
            });
            List<Map<String, Object>> tools = new ArrayList<>(hits.size());
            for (SearchHit h : hits) tools.add(h.card);
            Map<String, Object> result = new HashMap<>();
            result.put("count", tools.size());
            result.put("tools", tools);
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("搜索工具失败: " + e.getMessage(), null);
        }
    }

    private static final class SearchHit {
        final SearchScore score;
        final String name;
        final Map<String, Object> card;

        SearchHit(SearchScore score, String name, Map<String, Object> card) {
            this.score = score;
            this.name = name;
            this.card = card;
        }
    }

    /** 单个工具对一次查询的命中结果：分数、最好命中层级（3=name/2=alias/1=description）、命中词。 */
    static final class SearchScore {
        int score;
        int tier;
        String alias;
        final java.util.List<String> hitTokens = new ArrayList<>();
    }

    /**
     * 查询分词：空白与中英文逗号/顿号/分号/斜杠/竖线。去重保序。
     *
     * <p>这里只做"词"级切分，不做中文分词——工具名和描述本身就是中英混排的短文本，
     * 按分隔符切开已经能把"打开应用 启动 intent"（模型最常写的样子）还原成可命中的词。</p>
     */
    static java.util.List<String> splitSearchTokens(String keyword) {
        java.util.List<String> out = new ArrayList<>();
        if (keyword == null) return out;
        String[] parts = keyword.toLowerCase(java.util.Locale.ROOT).trim()
                .split("[\\s,，、;；/|]+");
        for (String p : parts) {
            String token = p.trim();
            if (!token.isEmpty() && !out.contains(token)) out.add(token);
        }
        return out;
    }

    /**
     * 给一个工具打分：名字命中 4 分、别名 3 分、描述 1 分；取每个词的最高档。
     * 空查询返回"全部命中"（保持 list/search 不带 keyword 时的旧语义）。
     */
    static SearchScore scoreTool(String name, String desc, java.util.List<String> aliases,
                                 java.util.List<String> tokens) {
        SearchScore r = new SearchScore();
        if (tokens == null || tokens.isEmpty()) {
            r.score = 4;
            r.tier = 3;
            return r;
        }
        String lowerName = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        String lowerDesc = desc == null ? "" : desc.toLowerCase(java.util.Locale.ROOT);
        for (String token : tokens) {
            if (token == null || token.isEmpty()) continue;
            if (lowerName.contains(token)) {
                r.score += 4;
                r.tier = Math.max(r.tier, 3);
                r.hitTokens.add(token);
                continue;
            }
            String aliasHit = null;
            if (aliases != null) {
                for (String a : aliases) {
                    if (a != null && a.toLowerCase(java.util.Locale.ROOT).contains(token)) {
                        aliasHit = a;
                        break;
                    }
                }
            }
            if (aliasHit != null) {
                r.score += 3;
                r.tier = Math.max(r.tier, 2);
                if (r.alias == null) r.alias = aliasHit;
                r.hitTokens.add(token);
            } else if (lowerDesc.contains(token)) {
                r.score += 1;
                r.tier = Math.max(r.tier, 1);
                r.hitTokens.add(token);
            }
        }
        return r;
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
            // description 全文返回（2026-11-02 起不再截断）。同时引擎层仍会把该工具定义注入会话工具缓存
            // （发现制闭环），下一轮 tools 数组即携带完整 schema 可直接调用。
            // v3：全部用原生集合（Gson 输出干净 JSON），不再 org.json + toString 转义
            // 2026-10-29（评审 REVIEW-2026-1029-01）：
            //  ① 输出自曝命中方式 matched_by，措辞按档分——只有精确命中才准说"已加载完整定义"，
            //     别名/回退必须明说"这是替换的结果"，否则模型把猜的当事实；
            //  ② 带上工具级 description：旧名（python_file_ops）被别名带到只读的 document_parser 时，
            //     正是这句"不写文件——写/改/差异请用 file_generator"能拦住乱调；
            //  ③ 参数描述有枚举就单列 enum；2026-11-02 起描述一律给全文，
            //     不再切 file_generator.action 的枚举尾巴，也不再标 description_truncated。
            List<Map<String, Object>> keys = new ArrayList<>();
            if (def.getParameters() != null) {
                for (com.oilquiz.app.ai.tool.openai.ParamDefinition p : def.getParameters()) {
                    Map<String, Object> k = new HashMap<>();
                    k.put("name", p.getName());
                    k.put("type", p.getType() != null ? p.getType() : "");
                    k.put("required", p.isRequired());
                    String d = p.getDescription() != null ? p.getDescription() : "";
                    // NO-CLIP(2026-11-02)：参数说明给全文（原来 160 字 + truncated 标记）。
                    k.put("description", d);
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
            // NO-CLIP(2026-11-02)：工具级描述同样给全文（原来 400 字 + truncated 标记）。
            result.put("description", tdesc);
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
