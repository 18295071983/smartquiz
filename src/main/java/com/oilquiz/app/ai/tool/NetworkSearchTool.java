// RECOVERED(2026-11-02)：本文件曾被一次错误的编码回写损坏，现由 jadx 从最后一次成功编译的 class
// 反编译恢复，并逐处修正反编译失真（注解短名、Document/Element 类型、try/catch 结构、常量字面量）。
// 逻辑与损坏前一致；**原有中文注释已丢失**。改动此文件请勿再用 PowerShell 的 Get-Content/Set-Content 往返。
package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;

import okhttp3.Request;

import com.oilquiz.app.ai.util.NetworkUtil;

@Tool(value = "network_search", description = "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取。默认用 ask 拿结论（一般提问它就够）；只有要一批来源/链接/原文时才用 search 等其它 action。秘塔按次计费（一次调用扣一次额度，与返回条数无关），所以一次问清楚，别反复调。ask 出答案要几十秒是正常的，慢不等于失败。只有 ask/search/read_url（以及 search_and_read、smart_search 的搜索那一步）扣额度，其余抓网页类 action 是本地解析、不花钱。", category = "search", aliases = {"search", "web_search", "bing_search"}, actions = {@Action(name = "search", description = "【扣额度】关键词搜索：返回标题/链接/摘要列表（只在要一批来源/链接时用；要结论默认用 ask。一次把 limit 给够，别为多看几条反复搜）"), @Action(name = "ask", description = "【扣额度】秘塔智能问答：直接返回结论+引用来源。默认用 ask 就够（一般提问都走它）；出答案要几十秒是正常的，慢不等于失败，别重试（重试=再扣一次额度）"), @Action(name = "read_url", description = "【扣额度】秘塔服务端读网页：返回 markdown 正文（字段名 markdown，原样不清洗），能处理 JS 渲染页；读不了的页会回服务错误码，这时换来源或改用 get_webpage（本地、免费）"), @Action(name = "get_webpage", description = "【本地，不扣额度】用 Jsoup 直接抓网页原始内容"), @Action(name = "extract_info", description = "【本地，不扣额度】抽取网页的结构化元数据：标题/meta描述/meta关键词/各级标题/正文切片/内链/分类/关键词；带 query 时额外回 queryMatches（按问题命中的正文段落）。注意：这不是语义问答，想从页面得到某个具体答案请用 read_url 拿正文或 ask 提问"), @Action(name = "summarize", description = "【本地，不扣额度】把上一步 search 返回的 results 汇成要点+来源；也可以只给 url，我抓该页再摘要。只填 query 不够"), @Action(name = "search_and_read", description = "【search 那一步扣额度】搜索并默认读前 min(limit,3) 条正文（limit 也决定读几条，最多 3 条；正文抓取是本地 Jsoup，不再额外扣）；要只读某一条就带 detailIndex=N"), @Action(name = "get_dynamic_content", description = "【本地，不扣额度】抓 JS 渲染页并挖出内嵌数据（window.DATA 之类）"), @Action(name = "smart_search", description = "【search 那一步扣额度】智能搜索：搜完自动按相关度读若干条正文并汇总"), @Action(name = "smart_read", description = "【本地，不扣额度】对已有搜索结果逐条读正文并生成摘要：必须传 results（上一步 search 返回的数组，或它的 JSON 字符串）")}, params = {@Param(name = "action", type = "string", description = "操作类型: ask(默认,扣额度)/search(扣额度)/read_url(扣额度)｜get_webpage·extract_info·summarize·get_dynamic_content·smart_read(本地抓取,不扣额度)｜search_and_read·smart_search(只搜索那一步扣)", required = false), @Param(name = "query", type = "string", description = "搜索查询（用于search、search_and_read、smart_search、extract_info操作；extract_info 用它挑命中段落）", required = false), @Param(name = "keyword", type = "string", description = "搜索关键词（query的别名）", required = false), @Param(name = "question", type = "string", description = "问题（用于ask操作，秘塔智能问答）", required = false), @Param(name = "model", type = "string", description = "问答模型: concise(简洁)/detail(深入)/research(研究)，默认concise（用于ask操作）", required = false), @Param(name = "limit", type = "int", description = "返回条数（1..50，默认 5）。接口不支持翻页，要更多结果就调大这个值；search_and_read 也用它决定读几条正文（最多 3 条）", required = false), @Param(name = "num_results", type = "int", description = "返回结果数量（limit的别名）", required = false), @Param(name = "url", type = "string", description = "网页URL（read_url/get_webpage/extract_info/get_dynamic_content 用；summarize 也可以只给 url 走单页摘要）", required = false), @Param(name = "maxResults", type = "int", description = "最大结果数(默认5)", required = false), @Param(name = "scope", type = "string", description = "搜索范围：webpage(默认)/document(文库)/scholar(学术文献)/image/video/podcast；写 paper 会按 scholar 处理", required = false), @Param(name = "include_summary", type = "boolean", description = "是否返回内容摘要（默认 true；关掉只省流量但更容易答错）", required = false), @Param(name = "start_char", type = "int", description = "read_url 正文起始字符偏移（默认 0）", required = false), @Param(name = "max_chars", type = "int", description = "read_url 本次最大字符数（默认 0=整篇；建议 4000）", required = false), @Param(name = "with_local", type = "boolean", description = "read_url 是否附带本地抓取的正文做对照（默认 false；开了会多一次网络往返，回 local_text/local_chars，用于核对秘塔是否漏句）", required = false), @Param(name = "format", type = "string", description = "read_url 返回格式：markdown(默认，秘塔原文)/text(转成纯文本，省 token 丢结构)/both(两个都给)", required = false), @Param(name = "autoRead", type = "boolean", description = "是否自动读取详情(默认true)", required = false), @Param(name = "results", type = "array", description = "搜索结果数组（summarize/smart_read 用）：把上一步 search 返回的 results 原样传进来；数组、它的 JSON 字符串、或整段搜索返回都收", required = false)})
/* loaded from: NetworkSearchTool.class */
public class NetworkSearchTool implements com.oilquiz.app.ai.tool.AITool {
    private static final java.lang.String TAG = "NetworkSearchTool";
    private static final java.lang.String METASO_SEARCH_API_URL = "https://metaso.cn/api/v1/search";
    private static final java.lang.String METASO_READER_API_URL = "https://metaso.cn/api/v1/reader";
    private static final java.lang.String METASO_CHAT_API_URL = "https://metaso.cn/api/open/search/v2";
    private static final java.lang.String DEFAULT_METASO_API_KEY = "mk-3B07FA8984EE0A485E5BB237C2B7D517";
    private final android.content.Context context;
    private static final long ASK_TIMEOUT_MS = 180_000L;
    private static final long NET_TIMEOUT_MS = 60_000L;
    private static volatile okhttp3.OkHttpClient askClient;
    private static volatile okhttp3.OkHttpClient netClient;
    private static final java.lang.String GLUE_CHARS = "至到第号年月日期起止前后";
    private static final java.util.regex.Pattern DATE_PATTERN = java.util.regex.Pattern.compile("(?<!\\d)(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?(?!\\d)|(?<!\\d)(\\d{1,2})\\s*[-/]\\s*(\\d{1,2})\\s*[-/]\\s*(\\d{4})(?!\\d)");
    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern.compile("(1[3-9]\\d{9}|0\\d{2,3}-\\d{7,8})");
    private static final java.util.regex.Pattern EMAIL_PATTERN = java.util.regex.Pattern.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile("(https?://[^\\s\"'<>]+)");
    private static final java.util.regex.Pattern JSON_LD_PATTERN = java.util.regex.Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([^<]*)</script>", 34);
    private static final java.util.regex.Pattern NUMERIC_ENTITY_PATTERN = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final java.util.Set<java.lang.String> DYNAMIC_CONTENT_KEYWORDS = new java.util.HashSet(java.util.Arrays.asList("window.__INITIAL_STATE__", "window.dataLayer", "window.__REDUX_STATE__", "window.APP_DATA", "window.__NEXT_DATA__", "window.__APOLLO_STATE__", "hydration", "ReactDOM.hydrate", "SSR_DATA"));
    private static final java.util.Set<java.lang.String> KEYWORD_GLUE_WORDS = new java.util.HashSet(java.util.Arrays.asList("期间", "以上", "以下", "之内", "之前", "之后", "其中", "以及", "或者", "由于", "根据", "依据", "对于", "关于", "是否", "可以", "相关"));
    private static final java.lang.String[] NAV_MARKERS = {"无障碍", "长者专区", "繁体", "简体", "客户端", "网站地图", "登录", "注册", "抖音", "微信"};

    public static boolean isAvailable() {
        return true;
    }

    public static java.lang.String preSearch(android.content.Context ctx, java.lang.String query, int limit) {
        if (ctx == null || query == null || query.trim().isEmpty()) {
            return null;
        }
        try {
            com.oilquiz.app.ai.tool.NetworkSearchTool tool = new com.oilquiz.app.ai.tool.NetworkSearchTool(ctx);
            return tool.buildSearchPrompt(query.trim(), limit > 0 ? limit : 5);
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.w(TAG, "preSearch failed: " + e.getMessage());
            return null;
        }
    }

    private java.lang.String buildSearchPrompt(java.lang.String query, int limit) {
        try {
            com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(this.context);
            java.lang.String apiKey = resolveApiKey(apiKeyManager);
            java.util.List<java.util.Map<java.lang.String, java.lang.String>> results = metasoSearch(query, limit, "webpage", true, apiKey);
            if (results == null || results.isEmpty()) {
                return null;
            }
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            int i = 1;
            for (java.util.Map<java.lang.String, java.lang.String> r : results) {
                int i2 = i;
                i++;
                sb.append(i2).append(". ").append(r.getOrDefault("title", "")).append('\n');
                java.lang.String url = r.getOrDefault("url", r.getOrDefault("link", ""));
                if (url != null && !url.isEmpty()) {
                    sb.append("   链接: ").append(url).append('\n');
                }
                java.lang.String snippet = r.getOrDefault("snippet", "");
                if (snippet != null && !snippet.isEmpty()) {
                    sb.append("   摘要: ").append(snippet).append('\n');
                }
            }
            return sb.toString();
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.w(TAG, "buildSearchPrompt failed: " + e.getMessage());
            return null;
        }
    }

    public NetworkSearchTool(android.content.Context context) {
        this.context = context;
    }

    @Override // com.oilquiz.app.ai.tool.AITool
    public java.lang.String getName() {
        return "network_search";
    }

    @Override // com.oilquiz.app.ai.tool.AITool
    public java.lang.String getDescription() {
        return "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取。默认用 ask 拿结论（一般提问它就够）；要一批来源/链接/原文才用 search 等其它 action。秘塔按次计费（一次调用扣一次额度，与返回条数无关），一次问清楚，别反复调。ask 出答案要几十秒是正常的，慢不等于失败。只有 ask/search/read_url（含 search_and_read、smart_search 的搜索那一步）扣额度，抓网页类的 action 是本地解析、不花钱。";
    }

    @Override // com.oilquiz.app.ai.tool.AITool
    public long executionTimeoutMs(java.util.Map<java.lang.String, java.lang.Object> args) {
        java.lang.String action = args == null ? null : java.lang.String.valueOf(args.get("action"));
        return "ask".equals(action) ? ASK_TIMEOUT_MS : NET_TIMEOUT_MS;
    }

    private okhttp3.OkHttpClient clientFor(long timeoutMs) {
        okhttp3.OkHttpClient cached = timeoutMs >= ASK_TIMEOUT_MS ? askClient : netClient;
        if (cached != null) {
            return cached;
        }
        okhttp3.OkHttpClient derived = com.oilquiz.app.ai.util.NetworkUtil.getClient().newBuilder().readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).callTimeout(timeoutMs + 5000, java.util.concurrent.TimeUnit.MILLISECONDS).build();
        if (timeoutMs >= ASK_TIMEOUT_MS) {
            askClient = derived;
        } else {
            netClient = derived;
        }
        return derived;
    }

    @Override // com.oilquiz.app.ai.tool.AITool
    public com.oilquiz.app.ai.tool.AIToolResult execute(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String action;
        try {
            action = getStringParam(parameters, "action", "search");
            if (action == null) {
                action = "search";
            }
            normalizeParameters(parameters);
            switch (action) {
                case "search":
                    return search(parameters);
                case "ask":
                    return askMetaso(parameters);
                case "read_url":
                    return readUrlMetaso(parameters);
                case "get_webpage":
                    return getWebpage(parameters);
                case "extract_info":
                    return extractInfo(parameters);
                case "summarize":
                    return summarizeResults(parameters);
                case "search_and_read":
                    return searchAndRead(parameters);
                case "get_dynamic_content":
                    return getDynamicContent(parameters);
                case "smart_search":
                    return smartSearch(parameters);
                case "smart_read":
                    return smartRead(parameters);
                default:
                    return new com.oilquiz.app.ai.tool.AIToolResult("Unknown action: " + action, parameters);
            }
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Error executing network search tool: " + e.getMessage(), e);
            return new com.oilquiz.app.ai.tool.AIToolResult("Error: " + e.getMessage(), parameters);
        }
    }

    private void normalizeParameters(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        if (parameters == null) {
            return;
        }
        if (parameters.containsKey("keyword") && !parameters.containsKey("query")) {
            parameters.put("query", parameters.get("keyword"));
        }
        if (parameters.containsKey("num_results") && !parameters.containsKey("limit")) {
            parameters.put("limit", parameters.get("num_results"));
        }
    }

    private java.lang.String resolveApiKey(com.oilquiz.app.ai.util.APIKeyManager mgr) {
        com.oilquiz.app.ai.model.APIConfig c = null;
        if (mgr == null) {
            return DEFAULT_METASO_API_KEY;
        }
        try {
            java.lang.String flat = mgr.getAPIKey("metaso_search");
            if (flat != null && !flat.trim().isEmpty()) {
                com.oilquiz.app.util.AILogger.i(TAG, "Metaso API key 来源: 扁平键(Service.METASO_SEARCH), key=" + maskKey(flat));
                return flat.trim();
            }
        } catch (java.lang.Throwable th) {
        }
        try {
            c = mgr.getAPIConfigByServiceType("metaso_search");
        } catch (java.lang.Throwable th2) {
        }
        if (c != null && c.getApiKey() != null && !c.getApiKey().trim().isEmpty()) {
            com.oilquiz.app.util.AILogger.i(TAG, "Metaso API key 来源: API配置[秘塔搜索] name=" + c.getName() + ", key=" + maskKey(c.getApiKey()));
            return c.getApiKey().trim();
        }
        com.oilquiz.app.ai.model.APIConfig c2 = mgr.getAPIConfigByServiceType("bing_search");
        if (c2 != null && c2.getApiKey() != null && !c2.getApiKey().trim().isEmpty()) {
            com.oilquiz.app.util.AILogger.i(TAG, "Metaso API key 来源: API配置[Bing Search] name=" + c2.getName() + ", key=" + maskKey(c2.getApiKey()));
            return c2.getApiKey().trim();
        }
        try {
            java.util.List<com.oilquiz.app.ai.model.APIConfig> searchConfigs = mgr.getAPIConfigsByCategory("search");
            if (searchConfigs != null) {
                for (com.oilquiz.app.ai.model.APIConfig c3 : searchConfigs) {
                    java.lang.String host = c3.getApiHost();
                    if (host != null && host.toLowerCase().contains("metaso") && c3.getApiKey() != null && !c3.getApiKey().trim().isEmpty()) {
                        com.oilquiz.app.util.AILogger.i(TAG, "Metaso API key 来源: 搜索类配置 apiHost 含 metaso, name=" + c3.getName() + ", key=" + maskKey(c3.getApiKey()));
                        return c3.getApiKey().trim();
                    }
                }
            }
        } catch (java.lang.Throwable th3) {
        }
        // 宽匹配兜底(2026-11-02)：serviceType/分类选错但 key 明显是秘塔的，也直接用上——
        // 兼容 API 配置页保存时服务下拉停在"OpenAI"等默认项的存量数据，避免退回内置共享 key。
        try {
            java.util.List<com.oilquiz.app.ai.model.APIConfig> all = mgr.getAllAPIConfigs();
            if (all != null) {
                for (com.oilquiz.app.ai.model.APIConfig c4 : all) {
                    java.lang.String key4 = c4.getApiKey();
                    if (key4 == null || key4.trim().isEmpty() || !c4.isActive()) {
                        continue;
                    }
                    java.lang.String sig = ((c4.getName() == null ? "" : c4.getName()) + " "
                            + (c4.getApiHost() == null ? "" : c4.getApiHost())).toLowerCase();
                    if (sig.contains("metaso") || sig.contains("秘塔") || key4.trim().startsWith("mk-")) {
                        com.oilquiz.app.util.AILogger.i(TAG, "Metaso API key 来源: 宽匹配(名称/地址含秘塔或mk-前缀), name="
                                + c4.getName() + ", key=" + maskKey(key4));
                        return key4.trim();
                    }
                }
            }
        } catch (java.lang.Throwable th4) {
        }
        com.oilquiz.app.util.AILogger.i(TAG, "Metaso API key 来源: 内置共享 key" + maskKey(DEFAULT_METASO_API_KEY) + "（未找到用户配置；该 key 可能过期/受限，建议在「API配置管理」添加【秘塔搜索】并填写 Key）");
        return DEFAULT_METASO_API_KEY;
    }

    private static java.lang.String maskKey(java.lang.String k) {
        return (k == null || k.length() < 10) ? "***" : k.substring(0, 6) + "…" + k.substring(k.length() - 4) + "(" + k.length() + ")";
    }

    private java.lang.String getStringParam(java.util.Map<java.lang.String, java.lang.Object> params, java.lang.String key, java.lang.String def) {
        java.lang.Object v;
        if (params != null && (v = params.get(key)) != null) {
            return java.lang.String.valueOf(v);
        }
        return def;
    }

    private static java.lang.String normalizeScope(java.lang.String scope) {
        if (scope == null) {
            return "webpage";
        }
        java.lang.String v = scope.trim().toLowerCase();
        if ("paper".equals(v)) {
            return "scholar";
        }
        switch (v) {
            case "webpage":
            case "document":
            case "scholar":
            case "image":
            case "video":
            case "podcast":
                return v;
            default:
                return "webpage";
        }
    }

    private static void copyIfPresent(java.util.Map<java.lang.String, java.lang.Object> from, java.util.Map<java.lang.String, java.lang.Object> to, java.lang.String key) {
        java.lang.Object v;
        if (from == null || to == null || key == null || (v = from.get(key)) == null) {
            return;
        }
        to.put(key, v);
    }

    private int getIntParam(java.util.Map<java.lang.String, java.lang.Object> params, java.lang.String key, int def) {
        java.lang.Object v;
        if (params != null && (v = params.get(key)) != null) {
            if (v instanceof java.lang.Number) {
                return ((java.lang.Number) v).intValue();
            }
            try {
                return java.lang.Integer.parseInt(java.lang.String.valueOf(v).trim());
            } catch (java.lang.NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private boolean getBoolParam(java.util.Map<java.lang.String, java.lang.Object> params, java.lang.String key, boolean def) {
        java.lang.Object v;
        if (params != null && (v = params.get(key)) != null) {
            if (v instanceof java.lang.Boolean) {
                return ((java.lang.Boolean) v).booleanValue();
            }
            java.lang.String s = java.lang.String.valueOf(v).trim().toLowerCase();
            if (s.equals("true") || s.equals("1") || s.equals("yes")) {
                return true;
            }
            if (s.equals("false") || s.equals("0") || s.equals("no")) {
                return false;
            }
            return def;
        }
        return def;
    }

    private static com.oilquiz.app.ai.tool.AIToolResult missingParam(java.lang.String action, java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String a = action == null ? "" : action;
        if ("read_url".equals(a) || "get_webpage".equals(a) || "get_dynamic_content".equals(a) || "extract_info".equals(a)) {
            return com.oilquiz.app.ai.tool.AIToolResult.fail(a + " 需要 url：填完整网页地址，例如 https://example.com/a；别把地址写进 query（query 只给 search 用）（这次没有发出请求，不扣额度）", parameters);
        }
        if ("ask".equals(a)) {
            return com.oilquiz.app.ai.tool.AIToolResult.fail("ask 需要 question：填要回答的完整问题（不是关键词，也不是网址）（这次没有发出请求，不扣额度）", parameters);
        }
        if ("smart_read".equals(a)) {
            return com.oilquiz.app.ai.tool.AIToolResult.fail("smart_read 需要 results：把上一步 search 返回的 results 数组原样传进来（数组，每项含 title/url/snippet；整段 JSON 字符串也可以）（这次没有发出请求，不扣额度）", parameters);
        }
        if ("summarize".equals(a)) {
            return com.oilquiz.app.ai.tool.AIToolResult.fail("summarize 需要 results（上一步 search 返回的 results 数组）或直接给 url（我抓网页再摘要）；query 只是主题标签，光填 query 不够（这次没有发出请求，不扣额度）", parameters);
        }
        return com.oilquiz.app.ai.tool.AIToolResult.fail(a + " 需要 query：填搜索关键词；要读某个网页请改用 action=read_url 并填 url（这次没有发出请求，不扣额度）", parameters);
    }

    static java.util.List<java.util.Map<java.lang.String, java.lang.Object>> parseResultsArg(java.lang.Object raw) {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        java.lang.Object source = raw;
        if (raw instanceof java.lang.String) {
            java.lang.String s = ((java.lang.String) raw).trim();
            if (s.isEmpty()) {
                return out;
            }
            try {
                source = new org.json.JSONTokener(s).nextValue();
            } catch (java.lang.Throwable th) {
                return out;
            }
        }
        if (source instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) source;
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject item = a.optJSONObject(i);
                if (item != null) {
                    out.add(jsonToFlatMap(item));
                }
            }
            return out;
        }
        if (source instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) source;
            org.json.JSONArray inner = o.optJSONArray("results");
            if (inner == null) {
                inner = o.optJSONArray("data");
            }
            if (inner != null) {
                return parseResultsArg(inner);
            }
            out.add(jsonToFlatMap(o));
            return out;
        }
        if (source instanceof java.util.List) {
            for (java.lang.Object e : (java.util.List) source) {
                if (e instanceof java.util.Map) {
                    out.add(new java.util.LinkedHashMap<>((java.util.Map) e));
                } else if (e instanceof org.json.JSONObject) {
                    out.add(jsonToFlatMap((org.json.JSONObject) e));
                }
            }
        }
        return out;
    }

    private static java.util.Map<java.lang.String, java.lang.Object> jsonToFlatMap(org.json.JSONObject o) {
        java.util.Map<java.lang.String, java.lang.Object> m = new java.util.LinkedHashMap<>();
        java.util.Iterator<java.lang.String> it = o.keys();
        while (it.hasNext()) {
            java.lang.String k = it.next();
            m.put(k, o.opt(k));
        }
        return m;
    }

    private com.oilquiz.app.ai.tool.AIToolResult search(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String query = getStringParam(parameters, "query", null);
        int limit = getIntParam(parameters, "limit", 5);
        if (query == null || query.trim().isEmpty()) {
            return missingParam("search", parameters);
        }
        if (limit <= 0) {
            limit = 5;
        }
        try {
            com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(this.context);
            java.lang.String apiKey = resolveApiKey(apiKeyManager);
            int searchPage = getIntParam(parameters, "page", 0);
            if (searchPage > 0) {
                com.oilquiz.app.util.AILogger.w(TAG, "Metaso search 不支持 page，已忽略 page=" + searchPage + "（要更多结果请调大 limit，上限 50）");
            }
            java.lang.String searchScope = getStringParam(parameters, "scope", "webpage");
            boolean includeSummary = !parameters.containsKey("include_summary") || getBoolParam(parameters, "include_summary", true);
            java.util.List<java.util.Map<java.lang.String, java.lang.String>> searchResults = metasoSearch(query, limit, searchScope, includeSummary, apiKey);
            if (searchResults == null || searchResults.isEmpty()) {
                return com.oilquiz.app.ai.tool.AIToolResult.fail("搜索无结果（服务端返回成功但没有条目，通常是关键词过窄或该 scope 下确无内容）: " + query + "。这次调用已按次扣额度：换词最多再试一次，不要连续重搜", parameters);
            }
            int demoted = demoteListPages(searchResults);
            java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
            result.put("query", query);
            result.put("count", java.lang.Integer.valueOf(searchResults.size()));
            result.put("scope", normalizeScope(searchScope));
            result.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
            result.put("results", searchResults);
            result.put("engine", "Metaso");
            if (demoted > 0) {
                result.put("ranking_note", "已把 " + demoted + " 条栏目索引页排到具体内容页之后（本地重排，接口顺序见 original_index 字段）");
            }
            com.oilquiz.app.ai.tool.AIToolResult toolResult = new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
            try {
                org.json.JSONArray items = new org.json.JSONArray();
                for (java.util.Map<java.lang.String, java.lang.String> sr : searchResults) {
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("icon", "��");
                    item.put("title", sr.getOrDefault("title", ""));
                    java.lang.String snippet = sr.getOrDefault("snippet", "");
                    item.put("description", snippet.length() > 80 ? snippet.substring(0, 80) + "..." : snippet);
                    item.put("value", sr.getOrDefault("source", ""));
                    item.put("url", sr.getOrDefault("url", ""));
                    items.put(item);
                }
                org.json.JSONObject props = new org.json.JSONObject();
                props.put("title", "搜索结果 · " + searchResults.size() + " 条");
                props.put("items", items);
                toolResult.withComponent(com.oilquiz.app.ai.chat.component.ComponentData.of("list_card", props));
            } catch (java.lang.Exception e) {
            }
            return toolResult;
        } catch (java.lang.Exception e2) {
            com.oilquiz.app.util.AILogger.e(TAG, "Search failed: " + e2.getMessage(), e2);
            return new com.oilquiz.app.ai.tool.AIToolResult("搜索失败: " + e2.getMessage(), parameters);
        }
    }

    public java.lang.String testApiKey(java.lang.String apiKey) {
        long start = java.lang.System.currentTimeMillis();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "FAIL|API Key 为空";
        }
        try {
            org.json.JSONObject bodyJson = new org.json.JSONObject();
            bodyJson.put("q", "秘塔AI搜索");
            bodyJson.put("scope", "webpage");
            bodyJson.put("includeSummary", false);
            bodyJson.put("includeRawContent", false);
            bodyJson.put("size", 1);
            okhttp3.RequestBody body = okhttp3.RequestBody.create(bodyJson.toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
            Request request = NetworkUtil.createApiRequestBuilder(METASO_SEARCH_API_URL).addHeader("Authorization", "Bearer " + apiKey.trim()).post(body).build();
            okhttp3.Response response = clientFor(NET_TIMEOUT_MS).newCall(request).execute();
            try {
                long latency = java.lang.System.currentTimeMillis() - start;
                int code = response.code();
                java.lang.String responseBody = response.body() != null ? response.body().string() : "";
                if (code == 401 || code == 403) {
                    java.lang.String str = "FAIL|API Key 无效或无权访问（HTTP " + code + "）";
                    if (response != null) {
                        response.close();
                    }
                    return str;
                }
                if (code == 429) {
                    if (response != null) {
                        response.close();
                    }
                    return "FAIL|触发限流（HTTP 429），请稍后再试";
                }
                if (!response.isSuccessful()) {
                    java.lang.String err = parseMetasoError(responseBody);
                    java.lang.String str2 = "FAIL|" + (err != null ? err : "HTTP " + code);
                    if (response != null) {
                        response.close();
                    }
                    return str2;
                }
                org.json.JSONObject json = new org.json.JSONObject(responseBody);
                int bizCode = json.optInt("code", 0);
                if (bizCode == 0) {
                    java.lang.String str3 = "OK|" + latency;
                    if (response != null) {
                        response.close();
                    }
                    return str3;
                }
                java.lang.String msg = json.optString("message", "未知错误");
                java.lang.String str4 = "FAIL|服务返回错误[" + bizCode + "]: " + msg;
                if (response != null) {
                    response.close();
                }
                return str4;
            } catch (java.lang.Throwable th) {
                if (response != null) {
                    try {
                        response.close();
                    } catch (java.lang.Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "testApiKey failed: " + e.getMessage(), e);
            return "FAIL|" + e.getMessage();
        }
    }

    private List<Map<String, String>> metasoSearch(String query, int count, String scope, boolean includeSummary, String apiKey) throws java.lang.Exception {
        new java.util.ArrayList();
        try {
            org.json.JSONObject bodyJson = new org.json.JSONObject();
            bodyJson.put("q", query);
            bodyJson.put("scope", normalizeScope(scope));
            bodyJson.put("includeSummary", includeSummary);
            bodyJson.put("includeRawContent", false);
            int size = java.lang.Math.max(1, java.lang.Math.min(50, count));
            bodyJson.put("size", String.valueOf(size));
            okhttp3.RequestBody body = okhttp3.RequestBody.create(bodyJson.toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
            Request request = NetworkUtil.createApiRequestBuilder(METASO_SEARCH_API_URL).addHeader("Authorization", "Bearer " + apiKey).post(body).build();
            com.oilquiz.app.util.AILogger.i(TAG, "Metaso Search: " + query);
            okhttp3.Response response = clientFor(NET_TIMEOUT_MS).newCall(request).execute();
            try {
                int responseCode = response.code();
                com.oilquiz.app.util.AILogger.i(TAG, "Metaso Search Response Code: " + responseCode);
                java.lang.String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    java.util.List<java.util.Map<java.lang.String, java.lang.String>> results = parseMetasoSearchResponse(responseBody);
                    com.oilquiz.app.util.AILogger.i(TAG, "Parsed " + results.size() + " search results");
                    if (results.isEmpty()) {
                        com.oilquiz.app.util.AILogger.w(TAG, "Metaso empty-results body(" + responseBody.length() + "): " + responseBody.substring(0, java.lang.Math.min(800, responseBody.length())));
                    }
                    if (response != null) {
                        response.close();
                    }
                    return results;
                }
                if (responseCode == 401) {
                    throw new java.lang.Exception("秘塔搜索API认证失败，请检查API密钥是否正确");
                }
                if (responseCode == 429) {
                    throw new java.lang.Exception("秘塔搜索API请求频率超限，请稍后再试");
                }
                java.lang.String errMsg = parseMetasoError(responseBody);
                throw new java.lang.Exception(errMsg != null ? errMsg : "秘塔搜索API返回错误码: " + responseCode);
            } finally {
            }
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Metaso Search API 调用失败: " + e.getMessage(), e);
            throw e;
        }
    }

    private static java.lang.String metasoErrorText(int code, java.lang.String msg) {
        java.lang.String m = (msg == null || msg.trim().isEmpty()) ? "服务端未给出说明" : msg.trim();
        switch (code) {
            case 1000:
                return "秘塔请求参数错误(1000)：" + m + "。常见原因是传了接口不支持的翻页参数；scope 只能是 webpage/document/scholar/image/video/podcast，size 取 1..50。改正参数再发一次，不要原样重试";
            case 4000:
                return "秘塔未找到相关数据(4000)：" + m + "。这个目标服务端解析不了，换一个来源或换关键词；对同一目标重复请求没有意义";
            case 5000:
                return "秘塔服务端错误(5000)：" + m + "。内置共享 Key 可能已失效或额度用尽——可在「设置 → API 配置 → 秘塔搜索」填自己的 Key。先别重试";
            default:
                return "秘塔服务错误(" + code + ")：" + m + "。重试会再发一次请求，而秘塔按次扣额度，所以先按上面说明判断是否值得重试";
        }
    }

    private static int extractMetasoErrorCode(org.json.JSONObject json) {
        if (json == null) {
            return 0;
        }
        if (json.has("errCode")) {
            return json.optInt("errCode", 0);
        }
        if (json.has("code")) {
            return json.optInt("code", 0);
        }
        return 0;
    }

    private static java.lang.String extractMetasoErrorMsg(org.json.JSONObject json) {
        if (json == null) {
            return null;
        }
        java.lang.String m = json.optString("errMsg", "");
        if (m.isEmpty()) {
            m = json.optString("message", "");
        }
        return m;
    }

    private static java.lang.Integer peekMetasoErrorCode(java.lang.String body) {
        try {
            org.json.JSONObject j = new org.json.JSONObject(body);
            if (j.has("errCode")) {
                return java.lang.Integer.valueOf(j.optInt("errCode", 0));
            }
            if (j.has("code")) {
                return java.lang.Integer.valueOf(j.optInt("code", 0));
            }
            return null;
        } catch (java.lang.Throwable th) {
            return null;
        }
    }

    private static java.lang.String peekMetasoErrorMsg(java.lang.String body) {
        try {
            return extractMetasoErrorMsg(new org.json.JSONObject(body));
        } catch (java.lang.Throwable th) {
            return null;
        }
    }

    private static java.lang.String clipForLog(java.lang.String s) {
        return (s == null || s.isEmpty()) ? "(空响应体)" : s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private java.util.List<java.util.Map<java.lang.String, java.lang.String>> parseMetasoSearchResponse(java.lang.String jsonResponse) {
        org.json.JSONObject responseJson = null;
        int bizCode = 0;
        java.util.List<java.util.Map<java.lang.String, java.lang.String>> results = new java.util.ArrayList<>();
        try {
            responseJson = new org.json.JSONObject(jsonResponse);
            bizCode = extractMetasoErrorCode(responseJson);
        } catch (java.lang.IllegalStateException biz) {
            throw biz;
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "解析秘塔搜索响应失败: " + e.getMessage(), e);
        }
        if (responseJson == null) {
            return results;
        }
        if (bizCode != 0) {
            java.lang.String emsg = extractMetasoErrorMsg(responseJson);
            com.oilquiz.app.util.AILogger.w(TAG, "Metaso search 业务错误 code=" + bizCode + " msg=" + emsg);
            throw new java.lang.IllegalStateException(metasoErrorText(bizCode, emsg));
        }
        org.json.JSONArray values = responseJson.optJSONArray("webpages");
        if (values == null) {
            values = findResultsArray(responseJson);
        }
        if (values == null) {
            com.oilquiz.app.util.AILogger.w(TAG, "未在秘塔响应中找到搜索结果");
            return results;
        }
        for (int i = 0; i < values.length(); i++) {
            org.json.JSONObject item = values.optJSONObject(i);
            if (item != null) {
                java.util.Map<java.lang.String, java.lang.String> resultItem = new java.util.HashMap<>();
                resultItem.put("title", firstNonEmpty(item, "title", "name", "subject"));
                resultItem.put("url", firstNonEmpty(item, "link", "url", "linkUrl", "href", "imageUrl", "image_url", "videoUrl", "video_url"));
                resultItem.put("snippet", firstNonEmpty(item, "snippet", "summary", "description", "content", "abstract"));
                resultItem.put("source", firstNonEmpty(item, "source", "siteName", "domain"));
                resultItem.put("date", firstNonEmpty(item, "date", "publishTime", "publishDate", "time"));
                if (!resultItem.get("title").isEmpty() && !resultItem.get("url").isEmpty()) {
                    results.add(resultItem);
                }
            }
        }
        return results;
    }

    private org.json.JSONArray findResultsArray(org.json.JSONObject json) {
        java.lang.String[] directKeys = {"webpages", "documents", "scholars", "images", "videos", "podcasts", "results", "data", "searchResults", "items", "list", "value"};
        for (java.lang.String key : directKeys) {
            org.json.JSONArray arr = json.optJSONArray(key);
            if (arr != null && arr.length() > 0) {
                return arr;
            }
        }
        org.json.JSONObject data = json.optJSONObject("data");
        if (data != null) {
            for (java.lang.String key2 : directKeys) {
                org.json.JSONArray arr2 = data.optJSONArray(key2);
                if (arr2 != null && arr2.length() > 0) {
                    return arr2;
                }
            }
            return null;
        }
        return null;
    }

    private java.lang.String firstNonEmpty(org.json.JSONObject obj, java.lang.String... keys) {
        for (java.lang.String key : keys) {
            java.lang.String val = obj.optString(key, "");
            if (!val.isEmpty()) {
                return val;
            }
        }
        return "";
    }

    private java.lang.String parseMetasoError(java.lang.String responseBody) {
        java.lang.Integer code = peekMetasoErrorCode(responseBody);
        if (code == null || code.intValue() == 0) {
            return null;
        }
        return metasoErrorText(code.intValue(), peekMetasoErrorMsg(responseBody));
    }

    private com.oilquiz.app.ai.tool.AIToolResult askMetaso(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String question = getStringParam(parameters, "question", null);
        if (question == null || question.trim().isEmpty()) {
            return missingParam("ask", parameters);
        }
        java.lang.String model = getStringParam(parameters, "model", "concise");
        if (model.trim().isEmpty()) {
            model = "concise";
        }
        try {
            com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(this.context);
            java.lang.String apiKey = resolveApiKey(apiKeyManager);
            org.json.JSONObject bodyJson = new org.json.JSONObject();
            bodyJson.put("question", question);
            bodyJson.put("model", model);
            bodyJson.put("stream", false);
            okhttp3.RequestBody body = okhttp3.RequestBody.create(bodyJson.toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
            okhttp3.Request request = com.oilquiz.app.ai.util.NetworkUtil.createApiRequestBuilder(METASO_CHAT_API_URL).addHeader("Authorization", "Bearer " + apiKey).post(body).build();
            com.oilquiz.app.util.AILogger.i(TAG, "Metaso Chat: " + question);
            okhttp3.Response response = clientFor(ASK_TIMEOUT_MS).newCall(request).execute();
            try {
                java.lang.String responseBody = response.body() != null ? response.body().string() : "";
                int http = response.code();
                com.oilquiz.app.util.AILogger.i(TAG, "Metaso Chat Response Code: " + http);
                java.lang.Integer biz = peekMetasoErrorCode(responseBody);
                if (biz != null && biz.intValue() != 0) {
                    java.lang.String meaning = metasoErrorText(biz.intValue(), peekMetasoErrorMsg(responseBody));
                    com.oilquiz.app.util.AILogger.w(TAG, "Metaso Chat 业务错误 code=" + biz + " http=" + http);
                    com.oilquiz.app.ai.tool.AIToolResult fail = com.oilquiz.app.ai.tool.AIToolResult.fail(meaning, parameters);
                    if (response != null) {
                        response.close();
                    }
                    return fail;
                }
                if (http == 401 || http == 403) {
                    com.oilquiz.app.ai.tool.AIToolResult fail2 = com.oilquiz.app.ai.tool.AIToolResult.fail("秘塔问答鉴权失败(HTTP " + http + ")：API Key 无效或无该接口权限，重试没有意义，请检查「设置 → API 配置 → 秘塔搜索」", parameters);
                    if (response != null) {
                        response.close();
                    }
                    return fail2;
                }
                if (http == 429) {
                    com.oilquiz.app.ai.tool.AIToolResult fail3 = com.oilquiz.app.ai.tool.AIToolResult.fail("秘塔问答被限流(HTTP 429)：请等一会儿再问，连续重发只会继续被限并扣额度", parameters);
                    if (response != null) {
                        response.close();
                    }
                    return fail3;
                }
                if (http >= 200 && http < 300) {
                    java.util.Map<java.lang.String, java.lang.Object> result = parseMetasoChatResponse(responseBody);
                    if (result == null) {
                        com.oilquiz.app.ai.tool.AIToolResult fail4 = com.oilquiz.app.ai.tool.AIToolResult.fail("秘塔问答响应缺少 data 字段（HTTP " + http + "，业务码 0），响应前 200 字: " + clipForLog(responseBody) + "。别原样重试；需要来源可改用 action=search（另一次调用、另扣一次额度）", parameters);
                        if (response != null) {
                            response.close();
                        }
                        return fail4;
                    }
                    result.put("question", question);
                    result.put("model", model);
                    result.put("engine", "Metaso");
                    com.oilquiz.app.ai.tool.AIToolResult aIToolResult = new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
                    if (response != null) {
                        response.close();
                    }
                    return aIToolResult;
                }
                com.oilquiz.app.ai.tool.AIToolResult cc = (http == 400 || http == 404 || http == 405) ? metasoChatCompletions(question, model, apiKey) : null;
                if (cc != null) {
                    if (response != null) {
                        response.close();
                    }
                    return cc;
                }
                if (http == 404 || http == 405) {
                    AIToolResult fb = askFallbackToSearch(question, parameters);
                    if (fb != null) {
                        if (response != null) {
                            response.close();
                        }
                        return fb;
                    }
                }
                com.oilquiz.app.util.AILogger.w(TAG, "Metaso Chat HTTP " + http + " body=" + clipForLog(responseBody));
                com.oilquiz.app.ai.tool.AIToolResult fail5 = com.oilquiz.app.ai.tool.AIToolResult.fail("秘塔问答请求失败（HTTP " + http + "，body 无业务码）。这不代表问法错了，先按状态码判断：4xx=请求形态/权限，5xx=服务端；重试会再扣一次额度，必要时就改用 action=search 自己汇总", parameters);
                if (response != null) {
                    response.close();
                }
                return fail5;
            } catch (java.lang.Throwable th) {
                if (response != null) {
                    try {
                        response.close();
                    } catch (java.lang.Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (java.lang.IllegalStateException biz2) {
            com.oilquiz.app.util.AILogger.w(TAG, "Metaso Chat 业务错误: " + biz2.getMessage());
            return com.oilquiz.app.ai.tool.AIToolResult.fail(biz2.getMessage(), parameters);
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Metaso Chat failed: " + e.getMessage(), e);
            return com.oilquiz.app.ai.tool.AIToolResult.fail("秘塔问答请求未完成（传输层异常，不是服务返回的业务码）: " + e.getMessage() + "。ask 出答案本来就要几十秒，别把慢当失败反复重试——每次请求都按次扣额度", parameters);
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult askFallbackToSearch(java.lang.String question, java.util.Map<java.lang.String, java.lang.Object> parameters) {
        try {
            java.lang.String apiKey = resolveApiKey(com.oilquiz.app.ai.util.APIKeyManager.getInstance(this.context));
            int limit = getIntParam(parameters, "limit", 5);
            java.lang.String scope = getStringParam(parameters, "scope", "webpage");
            java.util.List<java.util.Map<java.lang.String, java.lang.String>> results = metasoSearch(question, limit, scope, true, apiKey);
            if (results == null || results.isEmpty()) {
                return null;
            }
            java.util.Map<java.lang.String, java.lang.Object> out = new java.util.HashMap<>();
            out.put("question", question);
            out.put("scope", normalizeScope(scope));
            out.put("engine", "Metaso");
            out.put("mode", "search_fallback");
            out.put("note", "智能问答接口当前不可用，已回退为普通搜索结果（回退同样算一次调用、扣一次额度），请基于结果自行汇总");
            out.put("results", results);
            com.oilquiz.app.util.AILogger.i(TAG, "Metaso ask fallback to search: " + results.size() + " results");
            return new com.oilquiz.app.ai.tool.AIToolResult(out, parameters);
        } catch (java.lang.Throwable t) {
            com.oilquiz.app.util.AILogger.w(TAG, "askFallbackToSearch failed: " + t.getMessage());
            return null;
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult metasoChatCompletions(java.lang.String question, java.lang.String model, java.lang.String apiKey) {
        try {
            org.json.JSONObject jSONObject = new org.json.JSONObject();
            jSONObject.put("model", model);
            org.json.JSONArray msgs = new org.json.JSONArray();
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("role", "user");
            m.put("content", question);
            msgs.put(m);
            jSONObject.put("messages", msgs);
            jSONObject.put("stream", false);
            okhttp3.Request req = com.oilquiz.app.ai.util.NetworkUtil.createApiRequestBuilder("https://metaso.cn/api/v1/chat/completions").addHeader("Authorization", "Bearer " + apiKey).addHeader("Accept", "application/json").post(okhttp3.RequestBody.create(jSONObject.toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"))).build();
            okhttp3.Response resp = clientFor(ASK_TIMEOUT_MS).newCall(req).execute();
            try {
                java.lang.String rb = resp.body() != null ? resp.body().string() : "";
                com.oilquiz.app.util.AILogger.i(TAG, "Metaso chat/completions code=" + resp.code());
                if (resp.isSuccessful()) {
                    org.json.JSONObject j = new org.json.JSONObject(rb);
                    java.lang.String answer = null;
                    org.json.JSONArray choices = j.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        org.json.JSONObject c0 = choices.optJSONObject(0);
                        org.json.JSONObject msg = c0 != null ? c0.optJSONObject("message") : null;
                        if (msg != null) {
                            answer = msg.optString("content", null);
                        }
                    }
                    if (answer != null && !answer.trim().isEmpty()) {
                        java.util.Map<java.lang.String, java.lang.Object> out = new java.util.HashMap<>();
                        out.put("answer", answer);
                        out.put("question", question);
                        out.put("mode", "chat_completions");
                        out.put("engine", "Metaso");
                        com.oilquiz.app.ai.tool.AIToolResult aIToolResult = new com.oilquiz.app.ai.tool.AIToolResult(out, (java.util.Map<java.lang.String, java.lang.Object>) null);
                        if (resp != null) {
                            resp.close();
                        }
                        return aIToolResult;
                    }
                    if (resp != null) {
                        resp.close();
                    }
                    return null;
                }
                if (resp != null) {
                    resp.close();
                }
                return null;
            } catch (java.lang.Throwable th) {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (java.lang.Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (java.lang.Throwable t) {
            com.oilquiz.app.util.AILogger.w(TAG, "metasoChatCompletions failed: " + t.getMessage());
            return null;
        }
    }

    private java.util.Map<java.lang.String, java.lang.Object> parseMetasoChatResponse(java.lang.String jsonResponse) {
        java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonResponse);
            int code = extractMetasoErrorCode(json);
            if (code != 0) {
                throw new java.lang.IllegalStateException(metasoErrorText(code, extractMetasoErrorMsg(json)));
            }
            org.json.JSONObject data = json.optJSONObject("data");
            if (data == null) {
                com.oilquiz.app.util.AILogger.w(TAG, "秘塔问答响应缺少data字段");
                return null;
            }
            result.put("answer", data.optString("text", ""));
            result.put("balance", java.lang.Integer.valueOf(data.optInt("balance", 0)));
            org.json.JSONArray refs = data.optJSONArray("references");
            if (refs != null) {
                java.util.List<java.util.Map<java.lang.String, java.lang.String>> references = new java.util.ArrayList<>();
                for (int i = 0; i < refs.length(); i++) {
                    org.json.JSONObject ref = refs.optJSONObject(i);
                    if (ref != null) {
                        java.util.Map<java.lang.String, java.lang.String> refItem = new java.util.HashMap<>();
                        refItem.put("title", ref.optString("title", ""));
                        refItem.put("link", ref.optString("link", ""));
                        refItem.put("date", ref.optString("date", ""));
                        refItem.put("source", ref.optString("author", ""));
                        references.add(refItem);
                    }
                }
                result.put("references", references);
            }
            return result;
        } catch (java.lang.IllegalStateException biz) {
            throw biz;
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "解析秘塔问答响应失败: " + e.getMessage(), e);
            return null;
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult readUrlMetaso(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        org.json.JSONObject j = null;
        int readerErr = 0;
        java.lang.String mdPreview = "";
        java.lang.String url = getStringParam(parameters, "url", null);
        if (url == null || url.trim().isEmpty()) {
            return missingParam("read_url", parameters);
        }
        try {
            com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(this.context);
            java.lang.String apiKey = resolveApiKey(apiKeyManager);
            org.json.JSONObject bodyJson = new org.json.JSONObject();
            bodyJson.put("url", url);
            okhttp3.RequestBody body = okhttp3.RequestBody.create(bodyJson.toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
            okhttp3.Request request = com.oilquiz.app.ai.util.NetworkUtil.createApiRequestBuilder(METASO_READER_API_URL).addHeader("Authorization", "Bearer " + apiKey).addHeader("Accept", "text/plain").post(body).build();
            com.oilquiz.app.util.AILogger.i(TAG, "Metaso Reader: " + url);
            okhttp3.Response response = clientFor(NET_TIMEOUT_MS).newCall(request).execute();
            try {
                java.lang.String responseBody = response.body() != null ? response.body().string() : "";
                com.oilquiz.app.util.AILogger.i(TAG, "Metaso Reader Response Code: " + response.code());
                if (!response.isSuccessful()) {
                    if (response.code() == 401) {
                        com.oilquiz.app.ai.tool.AIToolResult aIToolResult = new com.oilquiz.app.ai.tool.AIToolResult("秘塔API认证失败，请检查API密钥", parameters);
                        if (response != null) {
                            response.close();
                        }
                        return aIToolResult;
                    }
                    com.oilquiz.app.ai.tool.AIToolResult aIToolResult2 = new com.oilquiz.app.ai.tool.AIToolResult("秘塔网页读取失败，HTTP码: " + response.code(), parameters);
                    if (response != null) {
                        response.close();
                    }
                    return aIToolResult2;
                }
                java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
                result.put("url", url);
                result.put("engine", "Metaso");
                java.lang.String contentText = responseBody;
                try {
                    j = new org.json.JSONObject(responseBody);
                    readerErr = extractMetasoErrorCode(j);
                    mdPreview = j.optString("markdown", "");
                } catch (java.lang.Throwable th) {
                }
                if (readerErr != 0 && mdPreview.isEmpty()) {
                    java.lang.String errMsg = extractMetasoErrorMsg(j);
                    com.oilquiz.app.util.AILogger.w(TAG, "Metaso Reader errCode=" + readerErr + " msg=" + errMsg + " url=" + url);
                    com.oilquiz.app.ai.tool.AIToolResult aIToolResult3 = new com.oilquiz.app.ai.tool.AIToolResult(metasoErrorText(readerErr, errMsg) + "；本工具读的是秘塔服务端解析结果，也可用工具注册表调用 webpage_reader 直抓", parameters);
                    if (response != null) {
                        response.close();
                    }
                    return aIToolResult3;
                }
                // READ-URL-GUARD(2026-11-02)：恢复时把 j 初始化成 null，但下面的 has()/optString()
                // 没有判空 —— 响应不是 JSON（纯文本/markdown）时直接 NPE，真机上表现为
                // "read_url 失败: JSONObject.has 空对象"。这里整块判空，拿不到 JSON 就用原文。
                if (j != null && j.has("title")) {
                    result.put("title", j.optString("title", ""));
                }
                if (j != null && j.has("author")) {
                    result.put("author", j.optString("author", ""));
                }
                if (j != null && j.has("date")) {
                    result.put("date", j.optString("date", ""));
                }
                java.lang.String md = j == null ? "" : j.optString("markdown", "");
                contentText = (md == null || md.isEmpty())
                        ? (j == null ? responseBody : j.optString("content", responseBody))
                        : md;
                int startChar = java.lang.Math.max(0, getIntParam(parameters, "start_char", 0));
                int maxChars = getIntParam(parameters, "max_chars", 0);
                // FORMAT-SELECT(2026-11-02)：返回格式由调用方选。
                //   markdown（默认）= 秘塔原文，保留标题/表格/链接/图片语法
                //   text          = 由 markdown 转出的纯文本（去语法，省 token，但丢结构）
                //   both          = 两个都给（同一篇会占两倍，慎用）
                // 分页与 with_local 与格式无关，各自独立生效。
                java.lang.String format = getStringParam(parameters, "format", "markdown");
                if (format == null || format.trim().isEmpty()) {
                    format = "markdown";
                }
                format = format.trim().toLowerCase();
                if (!format.equals("text") && !format.equals("both")) {
                    format = "markdown";
                }
                java.lang.String contentOut;
                if (startChar > 0 || maxChars > 0) {
                    int total = contentText.length();
                    if (startChar > total) {
                        startChar = total;
                    }
                    int end = maxChars > 0 ? java.lang.Math.min(total, startChar + maxChars) : total;
                    contentOut = contentText.substring(startChar, end);
                    java.util.Map<java.lang.String, java.lang.Object> page = new java.util.HashMap<>();
                    page.put("total_chars", java.lang.Integer.valueOf(total));
                    page.put("start_char", java.lang.Integer.valueOf(startChar));
                    page.put("end_char", java.lang.Integer.valueOf(end));
                    page.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.TRUNCATED, java.lang.Boolean.valueOf(end < total));
                    page.put("next_start", java.lang.Integer.valueOf(end));
                    result.put("page", page);
                } else {
                    contentOut = contentText;
                }
                if (!format.equals("text")) {
                    result.put("markdown", contentOut);
                }
                if (!format.equals("markdown")) {
                    result.put("text", markdownToPlain(contentOut));
                }
                // LOCAL-SUPPLEMENT(2026-11-02)：秘塔转 markdown 时会丢行内内容（真机：光明网那句
                // 带 <a> 的"11月2日15:00…CCTV2 直播"整句消失），而本地 Jsoup 抓同一页是完整的。
                // 但两路都回等于同一篇给两遍、白占上下文，所以做成**按需**：with_local=true 才附带。
                if (getBoolParam(parameters, "with_local", false)) {
                    try {
                        java.lang.String localHtml = fetchWebpage(url);
                        java.lang.String localText = HtmlContentExtractor.mainText(localHtml);
                        if (localText != null && !localText.trim().isEmpty()) {
                            java.lang.String trimmed = localText.trim();
                            result.put("local_chars", java.lang.Integer.valueOf(trimmed.length()));
                            result.put("local_text", trimmed.length() > 8000 ? trimmed.substring(0, 8000) : trimmed);
                            result.put("local_truncated", java.lang.Boolean.valueOf(trimmed.length() > 8000));
                        }
                    } catch (java.lang.Throwable ignored) {
                        // 本地抓不到就算了：read_url 的成功与否只看秘塔那一路
                    }
                }
                com.oilquiz.app.ai.tool.AIToolResult aIToolResult4 = new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
                if (response != null) {
                    response.close();
                }
                return aIToolResult4;
            } finally {
            }
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Metaso Reader failed: " + e.getMessage(), e);
            return new com.oilquiz.app.ai.tool.AIToolResult("秘塔网页读取失败: " + e.getMessage(), parameters);
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult getWebpage(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String url = getStringParam(parameters, "url", null);
        if (url == null) {
            return missingParam("get_webpage", parameters);
        }
        try {
            java.lang.String content = fetchWebpage(url);
            java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
            result.put("url", url);
            result.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
            result.put("content", content);
            result.put("length", java.lang.Integer.valueOf(content.length()));
            return new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
        } catch (java.lang.Exception e) {
            return new com.oilquiz.app.ai.tool.AIToolResult("获取网页失败: " + e.getMessage(), parameters);
        }
    }

    private java.lang.String fetchWebpage(java.lang.String urlString) throws java.lang.Exception {
        com.oilquiz.app.ai.tool.WebFetchPolicy.beforeFetch(urlString);
        try {
            return fetchWebpageUnchecked(urlString);
        } finally {
            com.oilquiz.app.ai.tool.WebFetchPolicy.afterFetch(urlString);
        }
    }

    private java.lang.String fetchWebpageUnchecked(java.lang.String urlString) throws java.lang.Exception {
        okhttp3.Request request = com.oilquiz.app.ai.util.NetworkUtil.createRequestBuilder(urlString).get().build();
        okhttp3.Response response = com.oilquiz.app.ai.util.NetworkUtil.getClient().newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new java.lang.Exception("HTTP Error: " + response.code());
            }
            if (response.body() != null) {
                java.lang.String decodeHtml = com.oilquiz.app.ai.tool.HtmlContentExtractor.decodeHtml(response.body().bytes(), response.header("Content-Type"));
                if (response != null) {
                    response.close();
                }
                return decodeHtml;
            }
            if (response != null) {
                response.close();
            }
            return "";
        } catch (java.lang.Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (java.lang.Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult searchAndRead(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String query = getStringParam(parameters, "query", null);
        int limit = getIntParam(parameters, "limit", 3);
        int detailIndex = getIntParam(parameters, "detailIndex", -1);
        if (query == null || query.trim().isEmpty()) {
            return missingParam("search_and_read", parameters);
        }
        if (limit <= 0) {
            limit = 3;
        }
        try {
            java.util.Map<java.lang.String, java.lang.Object> searchParams = new java.util.HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", java.lang.Integer.valueOf(limit));
            copyIfPresent(parameters, searchParams, "scope");
            copyIfPresent(parameters, searchParams, "include_summary");

            com.oilquiz.app.ai.tool.AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }
            java.util.Map<java.lang.String, java.lang.Object> searchData =
                    (java.util.Map<java.lang.String, java.lang.Object>) searchResult.getResult();
            java.util.List<java.util.Map<java.lang.String, java.lang.String>> results =
                    (java.util.List<java.util.Map<java.lang.String, java.lang.String>>) searchData.get("results");

            if (detailIndex >= 0 && detailIndex < results.size()) {
                java.util.Map<java.lang.String, java.lang.String> selectedResult = results.get(detailIndex);
                java.lang.String detailUrl = selectedResult.get("url");
                java.util.Map<java.lang.String, java.lang.Object> detailResult = new java.util.HashMap<>();
                detailResult.put("query", query);
                detailResult.put("selectedIndex", java.lang.Integer.valueOf(detailIndex));
                detailResult.put("selectedTitle", selectedResult.get("title"));
                detailResult.put("selectedUrl", detailUrl);
                try {
                    java.lang.String content = fetchWebpage(detailUrl);
                    detailResult.put("content", extractWebpageContent(content, detailUrl));
                    detailResult.put("status", "success_with_detail");
                } catch (java.lang.Exception e2) {
                    com.oilquiz.app.util.AILogger.w(TAG, "Failed to fetch detail page: " + e2.getMessage());
                    detailResult.put("content", "无法获取详情页内容: " + e2.getMessage());
                    detailResult.put("status", "success_without_detail");
                }
                return new com.oilquiz.app.ai.tool.AIToolResult(detailResult, parameters);
            }

            int autoReadCount = java.lang.Math.max(1, java.lang.Math.min(java.lang.Math.min(limit, 3), results.size()));
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> detailed = new java.util.ArrayList<>();
            for (int i = 0; i < autoReadCount; i++) {
                java.util.Map<java.lang.String, java.lang.String> r = results.get(i);
                java.lang.String u = r.get("url");
                java.util.Map<java.lang.String, java.lang.Object> one = new java.util.HashMap<>();
                one.put("index", java.lang.Integer.valueOf(i));
                one.put("title", r.get("title"));
                one.put("url", u);
                try {
                    java.lang.String html = fetchWebpage(u);
                    one.put("content", extractWebpageContent(html, u));
                    one.put("read_ok", java.lang.Boolean.TRUE);
                } catch (java.lang.Exception e3) {
                    com.oilquiz.app.util.AILogger.w(TAG, "search_and_read 默认读第 " + i + " 条失败: " + e3.getMessage());
                    one.put("error", "这一页抓不下来: " + e3.getMessage());
                    one.put("read_ok", java.lang.Boolean.FALSE);
                }
                detailed.add(one);
            }

            java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
            result.put("query", query);
            result.put("count", java.lang.Integer.valueOf(results.size()));
            result.put("status", "success_with_details");
            result.put("results", results);
            result.put("detailedContents", detailed);
            result.put("note", "已读取前 " + autoReadCount + " 条正文（读取条数 = limit，上限 3 条；本地抓取，不额外扣额度）；要只读某一条就带 detailIndex=N（从 0 开始）");
            return new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Search and read failed: " + e.getMessage(), e);
            return new com.oilquiz.app.ai.tool.AIToolResult("搜索并阅读失败: " + e.getMessage(), parameters);
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult getDynamicContent(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.Object obj;
        java.lang.String url = getStringParam(parameters, "url", null);
        java.lang.String content = getStringParam(parameters, "content", null);
        if (url == null && content == null) {
            return missingParam("get_dynamic_content", parameters);
        }
        if (content == null) {
            try {
                content = fetchWebpage(url);
            } catch (java.lang.Exception e) {
                com.oilquiz.app.util.AILogger.e(TAG, "Get dynamic content failed: " + e.getMessage(), e);
                return new com.oilquiz.app.ai.tool.AIToolResult("获取动态内容失败: " + e.getMessage(), parameters);
            }
        }
        java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
        result.put("url", url);
        java.util.Map<java.lang.String, java.lang.Object> dynamic = extractDynamicData(content);
        boolean hasWindowState = dynamic.containsKey("windowVariables");
        boolean hasJsonLd = dynamic.containsKey("jsonLd");
        result.put("isDynamic", java.lang.Boolean.valueOf(hasWindowState));
        result.put("hasStructuredData", java.lang.Boolean.valueOf(hasJsonLd));
        if (!dynamic.isEmpty()) {
            java.lang.String json = new org.json.JSONObject(dynamic).toString();
            result.put("dynamicDataTruncated", java.lang.Boolean.valueOf(json.length() > 20000));
            result.put("dynamicDataChars", java.lang.Integer.valueOf(json.length()));
            if (json.length() > 20000) {
                try {
                    obj = new org.json.JSONObject().put("truncated", true).put("preview", json.substring(0, 20000));
                } catch (org.json.JSONException je) {
                    obj = dynamic;
                }
            } else {
                obj = dynamic;
            }
            result.put("dynamicData", obj);
        }
        result.put("content", extractWebpageContent(content, url));
        result.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
        return new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
    }

    private com.oilquiz.app.ai.tool.AIToolResult smartSearch(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String query = getStringParam(parameters, "query", null);
        int maxResults = getIntParam(parameters, "maxResults", 5);
        boolean autoRead = getBoolParam(parameters, "autoRead", true);
        if (query == null || query.trim().isEmpty()) {
            return missingParam("smart_search", parameters);
        }
        if (maxResults <= 0) {
            maxResults = 5;
        }
        try {
            com.oilquiz.app.util.AILogger.i(TAG, "Starting smart search for: " + query);
            java.util.Map<java.lang.String, java.lang.Object> searchParams = new java.util.HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", java.lang.Integer.valueOf(maxResults));
            copyIfPresent(parameters, searchParams, "scope");
            copyIfPresent(parameters, searchParams, "include_summary");
            com.oilquiz.app.ai.tool.AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }
            java.util.Map<java.lang.String, java.lang.Object> searchData = (java.util.Map) searchResult.getResult();
            java.util.List<java.util.Map<java.lang.String, java.lang.String>> results = (java.util.List) searchData.get("results");
            if (results == null || results.isEmpty()) {
                return com.oilquiz.app.ai.tool.AIToolResult.fail("搜索无结果（服务端返回成功但没有条目，通常是关键词过窄或该 scope 下确无内容）: " + query + "。这次调用已按次扣额度：换词最多再试一次，不要连续重搜", parameters);
            }
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> analyzedResults = analyzeSearchResults(results, query);
            java.util.List<java.lang.Integer> selectedIndices = selectRelevantResults(analyzedResults);
            java.util.Map<java.lang.String, java.lang.Object> finalResult = new java.util.HashMap<>();
            finalResult.put("query", query);
            finalResult.put("totalResults", java.lang.Integer.valueOf(results.size()));
            finalResult.put("analyzedResults", analyzedResults);
            finalResult.put("selectedIndices", selectedIndices);
            if (autoRead && !selectedIndices.isEmpty()) {
                java.util.List<java.util.Map<java.lang.String, java.lang.Object>> detailedContents = new java.util.ArrayList<>();
                for (java.lang.Integer index : selectedIndices) {
                    try {
                        java.util.Map<java.lang.String, java.lang.String> result = results.get(index.intValue());
                        java.lang.String url = result.get("url");
                        java.util.Map<java.lang.String, java.lang.Object> detail = new java.util.HashMap<>();
                        detail.put("index", index);
                        detail.put("title", result.get("title"));
                        detail.put("url", url);
                        detail.put("originalSnippet", result.get("snippet"));
                        try {
                            java.lang.String content = fetchWebpage(url);
                            java.util.Map<java.lang.String, java.lang.Object> extracted = extractWebpageContent(content, url);
                            detail.put("content", extracted);
                            detail.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS, true);
                        } catch (java.lang.Exception e) {
                            com.oilquiz.app.util.AILogger.w(TAG, "Failed to fetch detail for URL: " + url);
                            detail.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.ERROR, "无法获取详情: " + e.getMessage());
                            detail.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS, false);
                        }
                        detailedContents.add(detail);
                    } catch (java.lang.Exception e2) {
                        com.oilquiz.app.util.AILogger.e(TAG, "Error processing result index " + index + ": " + e2.getMessage());
                    }
                }
                finalResult.put("detailedContents", detailedContents);
                finalResult.put("summary", generateSmartSummaryFromDetails(detailedContents, query));
                finalResult.put("status", "success_with_details");
            } else {
                finalResult.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
                finalResult.put("note", "使用 autoRead=true 可自动获取详情内容");
            }
            return new com.oilquiz.app.ai.tool.AIToolResult(finalResult, parameters);
        } catch (java.lang.Exception e3) {
            com.oilquiz.app.util.AILogger.e(TAG, "Smart search failed: " + e3.getMessage(), e3);
            return new com.oilquiz.app.ai.tool.AIToolResult("智能搜索失败: " + e3.getMessage(), parameters);
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult smartRead(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String query = getStringParam(parameters, "query", null);
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> results = parseResultsArg(parameters.get("results"));
        if (results.isEmpty()) {
            return missingParam("smart_read", parameters);
        }
        try {
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> analyzedResults = new java.util.ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                java.util.Map<java.lang.String, java.lang.Object> result = results.get(i);
                java.lang.String title = java.lang.String.valueOf(result.get("title"));
                java.lang.String snippet = java.lang.String.valueOf(result.get("snippet"));
                java.util.Map<java.lang.String, java.lang.Object> analysis = analyzeResult(title, snippet, java.lang.String.valueOf(result.get("url")), query, i);
                analyzedResults.add(analysis);
            }
            java.util.List<java.lang.Integer> selectedIndices = selectRelevantResults(analyzedResults);
            java.util.ArrayList arrayList = new java.util.ArrayList();
            for (java.lang.Integer index : selectedIndices) {
                try {
                    java.util.Map<java.lang.String, java.lang.Object> result2 = results.get(index.intValue());
                    java.lang.String url = (java.lang.String) result2.get("url");
                    java.util.HashMap hashMap = new java.util.HashMap();
                    hashMap.put("index", index);
                    hashMap.put("title", result2.get("title"));
                    hashMap.put("url", url);
                    try {
                        java.lang.String content = fetchWebpage(url);
                        java.util.Map<java.lang.String, java.lang.Object> extracted = extractWebpageContent(content, url);
                        hashMap.put("content", extracted);
                        hashMap.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS, true);
                    } catch (java.lang.Exception e) {
                        hashMap.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.ERROR, "无法获取详情: " + e.getMessage());
                        hashMap.put(com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS, false);
                    }
                    arrayList.add(hashMap);
                } catch (java.lang.Exception e2) {
                    com.oilquiz.app.util.AILogger.e(TAG, "Error reading result index " + index + ": " + e2.getMessage());
                }
            }
            java.util.Map<java.lang.String, java.lang.Object> finalResult = new java.util.HashMap<>();
            finalResult.put("query", query);
            finalResult.put("selectedCount", java.lang.Integer.valueOf(selectedIndices.size()));
            finalResult.put("selectedIndices", selectedIndices);
            finalResult.put("detailedContents", arrayList);
            finalResult.put("summary", generateSmartSummaryFromDetails(arrayList, query));
            finalResult.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
            return new com.oilquiz.app.ai.tool.AIToolResult(finalResult, parameters);
        } catch (java.lang.Exception e3) {
            com.oilquiz.app.util.AILogger.e(TAG, "Smart read failed: " + e3.getMessage(), e3);
            return new com.oilquiz.app.ai.tool.AIToolResult("智能阅读失败: " + e3.getMessage(), parameters);
        }
    }

    private java.util.List<java.util.Map<java.lang.String, java.lang.Object>> analyzeSearchResults(java.util.List<java.util.Map<java.lang.String, java.lang.String>> results, java.lang.String query) {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> analyzed = new java.util.ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            java.util.Map<java.lang.String, java.lang.String> result = results.get(i);
            java.lang.String title = result.get("title");
            java.lang.String snippet = result.get("snippet");
            java.lang.String url = result.get("url");
            java.util.Map<java.lang.String, java.lang.Object> analysis = analyzeResult(title, snippet, url, query, i);
            analyzed.add(analysis);
        }
        return analyzed;
    }

    private java.util.Map<java.lang.String, java.lang.Object> analyzeResult(java.lang.String title, java.lang.String snippet, java.lang.String url, java.lang.String query, int index) {
        java.util.Map<java.lang.String, java.lang.Object> analysis = new java.util.HashMap<>();
        analysis.put("index", java.lang.Integer.valueOf(index));
        analysis.put("title", title);
        analysis.put("snippet", snippet);
        analysis.put("url", url);
        double relevanceScore = calculateRelevance(title, snippet, query);
        analysis.put("relevanceScore", java.lang.Double.valueOf(relevanceScore));
        boolean isHighQuality = isHighQualityResult(title, snippet, url);
        analysis.put("isHighQuality", java.lang.Boolean.valueOf(isHighQuality));
        java.lang.String contentCategory = classifyUrl(url);
        analysis.put("contentCategory", contentCategory);
        analysis.put("trustworthiness", java.lang.Double.valueOf(calculateTrustworthiness(url)));
        return analysis;
    }

    private double calculateRelevance(java.lang.String title, java.lang.String snippet, java.lang.String query) {
        if (title == null || snippet == null || query == null) {
            return 0.0d;
        }
        double score = 0.0d;
        java.lang.String lowerTitle = title.toLowerCase();
        java.lang.String lowerSnippet = snippet.toLowerCase();
        java.lang.String lowerQuery = query.toLowerCase();
        java.lang.String[] queryWords = lowerQuery.split("\\s+");
        for (java.lang.String word : queryWords) {
            if (lowerTitle.contains(word)) {
                score += 2.0d;
            }
            if (lowerSnippet.contains(word)) {
                score += 1.0d;
            }
        }
        double score2 = score / (queryWords.length * 3.0d);
        if (lowerTitle.startsWith(lowerQuery)) {
            score2 += 0.2d;
        }
        return java.lang.Math.min(1.0d, score2);
    }

    private boolean isHighQualityResult(java.lang.String title, java.lang.String snippet, java.lang.String url) {
        if (title == null || title.length() < 5 || snippet == null || snippet.length() < 20 || url == null || !url.startsWith("https://")) {
            return false;
        }
        java.lang.String[] trustedDomains = {"gov.cn", "edu.cn", "org.cn", ".com.cn", ".net.cn"};
        for (java.lang.String domain : trustedDomains) {
            if (url.contains(domain)) {
                return true;
            }
        }
        return snippet.length() > 100;
    }

    private java.lang.String classifyUrl(java.lang.String url) {
        return url == null ? "unknown" : url.contains(".gov.") ? "government" : url.contains(".edu.") ? "education" : url.contains(".org") ? "organization" : (url.contains("news.") || url.contains(".news.")) ? "news" : (url.contains("blog.") || url.contains(".blog.")) ? "blog" : (url.contains("github.com") || url.contains("gitcode.com")) ? "code" : (url.contains("baike.") || url.contains("wiki")) ? "encyclopedia" : "general";
    }

    private double calculateTrustworthiness(java.lang.String url) {
        if (url == null) {
            return 0.0d;
        }
        double score = 0.5d;
        if (url.startsWith("https://")) {
            score = 0.5d + 0.1d;
        }
        if (url.contains(".gov.")) {
            score += 0.2d;
        }
        if (url.contains(".edu.")) {
            score += 0.2d;
        }
        if (url.contains(".org")) {
            score += 0.1d;
        }
        return java.lang.Math.min(1.0d, score);
    }

    private java.util.List<java.lang.Integer> selectRelevantResults(java.util.List<java.util.Map<java.lang.String, java.lang.Object>> analyzedResults) {
        java.util.List<java.lang.Integer> selected = new java.util.ArrayList<>();
        analyzedResults.sort((a, b) -> {
            double scoreA = ((java.lang.Double) a.get("relevanceScore")).doubleValue();
            double scoreB = ((java.lang.Double) b.get("relevanceScore")).doubleValue();
            return java.lang.Double.compare(scoreB, scoreA);
        });
        for (int i = 0; i < java.lang.Math.min(3, analyzedResults.size()); i++) {
            java.util.Map<java.lang.String, java.lang.Object> result = analyzedResults.get(i);
            double relevance = ((java.lang.Double) result.get("relevanceScore")).doubleValue();
            boolean highQuality = ((java.lang.Boolean) result.get("isHighQuality")).booleanValue();
            if (relevance > 0.3d || highQuality) {
                selected.add((java.lang.Integer) result.get("index"));
            }
        }
        if (selected.isEmpty() && !analyzedResults.isEmpty()) {
            selected.add((java.lang.Integer) analyzedResults.get(0).get("index"));
        }
        return selected;
    }

    private java.util.Map<java.lang.String, java.lang.Object> generateSmartSummaryFromDetails(java.util.List<java.util.Map<java.lang.String, java.lang.Object>> details, java.lang.String query) {
        java.util.Map<java.lang.String, java.lang.Object> summary = new java.util.HashMap<>();
        java.util.List<java.lang.String> keyPoints = new java.util.ArrayList<>();
        java.util.List<java.lang.String> sources = new java.util.ArrayList<>();
        java.lang.StringBuilder fullText = new java.lang.StringBuilder();
        for (java.util.Map<java.lang.String, java.lang.Object> detail : details) {
            boolean success = ((java.lang.Boolean) detail.get(com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS)).booleanValue();
            if (success) {
                java.lang.String title = (java.lang.String) detail.get("title");
                java.lang.String url = (java.lang.String) detail.get("url");
                java.util.Map<java.lang.String, java.lang.Object> content = (java.util.Map) detail.get("content");
                if (content != null) {
                    java.lang.String summaryText = (java.lang.String) content.get("summary");
                    if (title != null) {
                        keyPoints.add(title);
                    }
                    if (url != null) {
                        sources.add(url);
                    }
                    if (summaryText != null) {
                        fullText.append(summaryText).append("\n\n");
                    }
                }
            }
        }
        summary.put("keyPoints", keyPoints);
        summary.put("sources", sources);
        summary.put("detailedSummary", fullText.toString().trim());
        summary.put("conciseSummary", generateConciseSummary(keyPoints, fullText.toString(), query));
        return summary;
    }

    private java.lang.String generateConciseSummary(java.util.List<java.lang.String> keyPoints, java.lang.String fullText, java.lang.String query) {
        java.lang.StringBuilder summary = new java.lang.StringBuilder();
        java.lang.String text = fullText == null ? "" : removeHtmlTags(fullText);
        java.lang.String digest = generateSmartSummary(text, query);
        if (digest != null && !digest.isEmpty() && !"暂无摘要".equals(digest)) {
            summary.append(digest);
        }
        if (keyPoints != null && !keyPoints.isEmpty()) {
            if (summary.length() > 0) {
                summary.append("\n\n");
            }
            summary.append("来源：");
            for (int i = 0; i < java.lang.Math.min(5, keyPoints.size()); i++) {
                if (i > 0) {
                    summary.append("；");
                }
                summary.append(keyPoints.get(i));
            }
        }
        return summary.toString();
    }

    private java.util.Map<java.lang.String, java.lang.Object> extractDynamicData(java.lang.String content) {
        java.util.Map<java.lang.String, java.lang.Object> dynamicData = new java.util.HashMap<>();
        java.lang.String jsonLdData = extractJsonLd(content);
        if (jsonLdData != null && !jsonLdData.isEmpty()) {
            try {
                org.json.JSONObject jsonObject = new org.json.JSONObject(jsonLdData);
                dynamicData.put("jsonLd", jsonObject);
            } catch (java.lang.Exception e) {
                com.oilquiz.app.util.AILogger.w(TAG, "Failed to parse JSON-LD: " + e.getMessage());
            }
        }
        java.util.Map<java.lang.String, java.lang.String> windowVariables = extractWindowVariables(content);
        if (!windowVariables.isEmpty()) {
            dynamicData.put("windowVariables", windowVariables);
        }
        return dynamicData;
    }

    private java.lang.String extractJsonLd(java.lang.String content) {
        java.util.regex.Matcher matcher = JSON_LD_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private java.util.Map<java.lang.String, java.lang.String> extractWindowVariables(java.lang.String content) {
        java.util.Map<java.lang.String, java.lang.String> variables = new java.util.HashMap<>();
        for (java.lang.String keyword : DYNAMIC_CONTENT_KEYWORDS) {
            int index = content.indexOf(keyword);
            if (index != -1) {
                int endIndex = content.indexOf(";", index);
                if (endIndex == -1) {
                    endIndex = content.indexOf("</script>", index);
                }
                if (endIndex != -1) {
                    java.lang.String variableContent = content.substring(index, endIndex).trim();
                    if (variableContent.contains("=")) {
                        java.lang.String[] parts = variableContent.split("=", 2);
                        if (parts.length == 2) {
                            variables.put(parts[0].trim(), parts[1].trim());
                        }
                    }
                }
            }
        }
        return variables;
    }

    private java.util.Map<java.lang.String, java.lang.Object> extractWebpageContent(java.lang.String content, java.lang.String url) {
        com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent page = com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent.of(content, url);
        java.util.Map<java.lang.String, java.lang.Object> extracted = new java.util.HashMap<>();
        extracted.put("title", page.title);
        extracted.put("metaDescription", page.metaDescription);
        extracted.put("metaKeywords", page.metaKeywords);
        extracted.put("headings", page.headings);
        extracted.put("contentSlices", sliceText(page.text));
        extracted.put("category", classifyContent(page.text));
        extracted.put("keywords", extractKeywords(page.text));
        extracted.put("summary", generateSmartSummary(page.text, null));
        extracted.put("relatedLinks", page.links);
        return extracted;
    }

    /* loaded from: NetworkSearchTool$PageContent.class */
    private static final class PageContent {
        java.lang.String title = "";
        java.lang.String metaDescription = "";
        java.lang.String metaKeywords = "";
        java.lang.String text = "";
        java.util.List<java.lang.String> headings = new java.util.ArrayList();
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> links = new java.util.ArrayList();
        java.util.List<java.lang.String> urls = new java.util.ArrayList();
        java.util.Map<java.lang.String, java.lang.Object> quality = new java.util.HashMap();
        java.lang.String verdict = "pass";

        private PageContent() {
        }

        static com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent of(java.lang.String html, java.lang.String url) {
            com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent page = new com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent();
            org.jsoup.nodes.Document parse = com.oilquiz.app.ai.tool.HtmlContentExtractor.parse(html, url);
            if (parse == null) {
                page.text = html == null ? "" : html.replaceAll("<[^>]+>", " ").trim();
                return page;
            }
            page.title = parse.title() == null ? "" : parse.title().trim();
            page.metaDescription = metaContent(parse, "description");
            page.metaKeywords = metaContent(parse, "keywords");
            org.jsoup.nodes.Element main = com.oilquiz.app.ai.tool.HtmlContentExtractor.mainContent(parse);
            page.text = com.oilquiz.app.ai.tool.HtmlContentExtractor.toText(main);
            page.headings = com.oilquiz.app.ai.tool.HtmlContentExtractor.headings(main != null ? main : parse);
            com.oilquiz.app.ai.tool.HtmlContentExtractor.Quality q =
                    com.oilquiz.app.ai.tool.HtmlContentExtractor.assess(parse, main, page.text, false, 200);
            page.quality = new java.util.HashMap();
            page.quality.put("score", java.lang.Double.valueOf(q.score));
            page.quality.put("page_type", q.pageType);
            page.quality.put("flags", q.flags);
            page.verdict = q.verdict;
            java.util.Set<java.lang.String> seen = new java.util.HashSet<>();
            java.util.Iterator it = parse.select("a[href]").iterator();
            while (it.hasNext()) {
                org.jsoup.nodes.Element a = (org.jsoup.nodes.Element) it.next();
                java.lang.String abs = a.absUrl("href");
                java.lang.String href = abs.isEmpty() ? a.attr("href") : abs;
                if (href != null && href.startsWith("http") && href.length() <= 256 && seen.add(href)) {
                    java.util.Map<java.lang.String, java.lang.Object> link = new java.util.HashMap<>();
                    link.put("url", href);
                    java.lang.String baseDomain = domainOf(url);
                    java.lang.String linkDomain = domainOf(href);
                    link.put("isInternal", java.lang.Boolean.valueOf(baseDomain != null && baseDomain.equals(linkDomain)));
                    link.put("domain", linkDomain);
                    page.links.add(link);
                    if (page.links.size() >= 20) {
                        break;
                    }
                }
            }
            for (java.util.Map<java.lang.String, java.lang.Object> l : page.links) {
                page.urls.add((java.lang.String) l.get("url"));
            }
            return page;
        }

        private static java.lang.String metaContent(org.jsoup.nodes.Document doc, java.lang.String name) {
            java.util.Iterator it = doc.select("meta[name]").iterator();
            while (it.hasNext()) {
                org.jsoup.nodes.Element meta = (org.jsoup.nodes.Element) it.next();
                if (name.equalsIgnoreCase(meta.attr("name"))) {
                    return meta.attr("content").trim();
                }
            }
            return "";
        }

        private static java.lang.String domainOf(java.lang.String url) {
            try {
                return new java.net.URL(url).getHost();
            } catch (java.lang.Exception e) {
                return null;
            }
        }
    }

    static int demoteListPages(java.util.List<java.util.Map<java.lang.String, java.lang.String>> results) {
        if (results == null || results.size() < 2) {
            return 0;
        }
        java.util.List<java.util.Map<java.lang.String, java.lang.String>> keep = new java.util.ArrayList<>();
        java.util.List<java.util.Map<java.lang.String, java.lang.String>> demoted = new java.util.ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            java.util.Map<java.lang.String, java.lang.String> item = results.get(i);
            if (item != null) {
                item.put("original_index", java.lang.String.valueOf(i));
                if (isLikelyListPage(item.get("url"))) {
                    demoted.add(item);
                } else {
                    keep.add(item);
                }
            }
        }
        if (demoted.isEmpty() || keep.isEmpty()) {
            return 0;
        }
        results.clear();
        results.addAll(keep);
        results.addAll(demoted);
        return demoted.size();
    }

    static boolean isLikelyListPage(java.lang.String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            java.lang.String path = new java.net.URL(url).getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return true;
            }
            java.lang.String lower = path.toLowerCase(java.util.Locale.ROOT);
            boolean hasContentId = lower.matches(".*\\d{5,}.*") || lower.contains("/content/") || lower.contains("/article/") || lower.contains("/detail") || lower.contains("/doc-");
            if (hasContentId) {
                return false;
            }
            if (lower.endsWith("/") || lower.matches(".*/?(index|default|list|more|all)\\.(html?|shtml?|php|aspx?)$")) {
                return true;
            }
            return lower.matches(".*/(col|column|channel|category|node|special|topic|list)/.*");
        } catch (java.lang.Exception e) {
            return false;
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult extractInfo(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String url = getStringParam(parameters, "url", null);
        java.lang.String content = getStringParam(parameters, "content", null);
        if (url == null && content == null) {
            return missingParam("extract_info", parameters);
        }
        if (content == null) {
            try {
                content = fetchWebpage(url);
            } catch (java.lang.Exception e) {
                com.oilquiz.app.util.AILogger.e(TAG, "Extract info failed: " + e.getMessage(), e);
                return new com.oilquiz.app.ai.tool.AIToolResult("提取信息失败: " + e.getMessage(), parameters);
            }
        }
        com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent page = com.oilquiz.app.ai.tool.NetworkSearchTool.PageContent.of(content, url);
        java.util.HashMap hashMap = new java.util.HashMap();
        hashMap.put("url", url);
        hashMap.put("title", page.title);
        hashMap.put("metaDescription", page.metaDescription);
        hashMap.put("metaKeywords", page.metaKeywords);
        hashMap.put("headings", page.headings);
        hashMap.put("dates", extractDates(page.text));
        hashMap.put("phones", extractPhones(page.text));
        hashMap.put("emails", extractEmails(page.text));
        hashMap.put("urls", page.urls);
        hashMap.put("contentSlices", sliceText(page.text));
        hashMap.put("category", classifyContent(page.text));
        hashMap.put("keywords", extractKeywords(page.text));
        hashMap.put("quality", page.quality);
        hashMap.put("verdict", page.verdict);
        java.lang.String query = getStringParam(parameters, "query", null);
        if (query != null && !query.trim().isEmpty()) {
            hashMap.put("query", query.trim());
            hashMap.put("queryMatches", matchSlicesByQuery(page.text, query));
        }
        java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
        result.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
        result.put("extracted", hashMap);
        return new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
    }

    private com.oilquiz.app.ai.tool.AIToolResult summarizeResults(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> results = parseResultsArg(parameters.get("results"));
        java.lang.String query = getStringParam(parameters, "query", null);
        if (results == null || results.isEmpty()) {
            com.oilquiz.app.ai.tool.AIToolResult single = summarizeSingleUrl(parameters);
            return single != null ? single : missingParam("summarize", parameters);
        }
        try {
            java.util.HashMap hashMap = new java.util.HashMap();
            hashMap.put("query", query);
            hashMap.put("totalResults", java.lang.Integer.valueOf(results.size()));
            java.util.ArrayList arrayList = new java.util.ArrayList();
            java.util.ArrayList arrayList2 = new java.util.ArrayList();
            java.lang.StringBuilder fullSummary = new java.lang.StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                java.util.Map<java.lang.String, java.lang.Object> item = results.get(i);
                java.lang.String title = strOf(item.get("title"));
                java.lang.String snippet = strOf(item.get("snippet"));
                java.lang.String url = strOf(item.get("url"));
                if (title != null && !title.isEmpty()) {
                    arrayList.add((i + 1) + ". " + title);
                    arrayList2.add(url);
                }
                if (snippet != null && !snippet.isEmpty()) {
                    fullSummary.append(snippet).append("\n\n");
                }
            }
            hashMap.put("keyPoints", arrayList);
            hashMap.put("sources", arrayList2);
            hashMap.put("summary", generateSmartSummary(fullSummary.toString(), query));
            hashMap.put("detailedSummary", fullSummary.toString().trim());
            java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
            result.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
            result.put("summary", hashMap);
            result.put("source_note", "本摘要由你传入的 results 合成，未再调用秘塔，不扣额度");
            return new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Summarize failed: " + e.getMessage(), e);
            return new com.oilquiz.app.ai.tool.AIToolResult("生成摘要失败: " + e.getMessage(), parameters);
        }
    }

    private com.oilquiz.app.ai.tool.AIToolResult summarizeSingleUrl(java.util.Map<java.lang.String, java.lang.Object> parameters) {
        java.lang.String url = getStringParam(parameters, "url", null);
        try {
            java.lang.String html = getStringParam(parameters, "content", null);
            if ((html == null || html.trim().isEmpty()) && url != null && !url.trim().isEmpty()) {
                html = fetchWebpage(url);
            }
            if (html == null || html.trim().isEmpty()) {
                return null;
            }
            java.util.Map<java.lang.String, java.lang.Object> page = extractWebpageContent(html, url);
            java.lang.String body = removeHtmlTags(html);
            if (body == null) {
                body = "";
            }
            java.util.HashMap hashMap = new java.util.HashMap();
            hashMap.put("url", url);
            hashMap.put("title", page.get("title"));
            hashMap.put("summary", generateSmartSummary(body, getStringParam(parameters, "query", null)));
            hashMap.put("detailedSummary", body.trim());
            java.util.Map<java.lang.String, java.lang.Object> result = new java.util.HashMap<>();
            result.put("status", com.oilquiz.app.ai.tool.ToolStatusVocabulary.SUCCESS);
            result.put("summary", hashMap);
            result.put("source_note", "单页摘要：本地抓取解析，未调用秘塔，不扣额度");
            com.oilquiz.app.util.AILogger.i(TAG, "summarize 走单页形态 url=" + url);
            return new com.oilquiz.app.ai.tool.AIToolResult(result, parameters);
        } catch (java.lang.Exception e) {
            com.oilquiz.app.util.AILogger.w(TAG, "summarize 单页形态失败: " + e.getMessage());
            return null;
        }
    }

    private static java.lang.String strOf(java.lang.Object v) {
        if (v == null || v == org.json.JSONObject.NULL) {
            return null;
        }
        return v instanceof java.lang.String ? (java.lang.String) v : java.lang.String.valueOf(v);
    }

    private java.util.List<java.lang.String> extractDates(java.lang.String html) {
        java.util.List<java.lang.String> dates = new java.util.ArrayList<>();
        java.util.Set<java.lang.String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = DATE_PATTERN.matcher(html);
        while (matcher.find()) {
            java.lang.String iso = toIsoDate(matcher);
            if (iso != null && seen.add(iso)) {
                dates.add(iso);
            }
        }
        return dates;
    }

    private static java.lang.String toIsoDate(java.util.regex.Matcher m) {
        int day;
        int month;
        int year;
        try {
            if (m.group(1) != null) {
                year = java.lang.Integer.parseInt(m.group(1));
                month = java.lang.Integer.parseInt(m.group(2));
                day = java.lang.Integer.parseInt(m.group(3));
            } else {
                day = java.lang.Integer.parseInt(m.group(4));
                month = java.lang.Integer.parseInt(m.group(5));
                year = java.lang.Integer.parseInt(m.group(6));
            }
            if (isValidYmd(year, month, day)) {
                return java.lang.String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", java.lang.Integer.valueOf(year), java.lang.Integer.valueOf(month), java.lang.Integer.valueOf(day));
            }
            return null;
        } catch (java.lang.Exception e) {
            return null;
        }
    }

    private static boolean isValidYmd(int year, int month, int day) {
        if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1) {
            return false;
        }
        int[] days = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
        int maxDay = days[month - 1] + ((month == 2 && leap) ? 1 : 0);
        return day <= maxDay;
    }

    private java.util.List<java.lang.String> extractPhones(java.lang.String html) {
        java.util.List<java.lang.String> phones = new java.util.ArrayList<>();
        java.util.Set<java.lang.String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = PHONE_PATTERN.matcher(html);
        while (matcher.find()) {
            java.lang.String phone = matcher.group();
            int start = matcher.start();
            int end = matcher.end();
            boolean leftOk = start == 0 || !java.lang.Character.isDigit(html.charAt(start - 1));
            boolean rightOk = end >= html.length() || !java.lang.Character.isDigit(html.charAt(end));
            if (leftOk && rightOk && seen.add(phone)) {
                phones.add(phone);
            }
        }
        return phones;
    }

    private java.util.List<java.lang.String> extractEmails(java.lang.String html) {
        java.util.List<java.lang.String> emails = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = EMAIL_PATTERN.matcher(html);
        while (matcher.find()) {
            emails.add(matcher.group(1));
        }
        return emails;
    }

    private java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sliceText(java.lang.String text) {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> slices = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return slices;
        }
        java.lang.String[] paragraphs = text.split("\\n{2,}");
        java.util.Set<java.lang.String> seen = new java.util.HashSet<>();
        for (int i = 0; i < paragraphs.length; i++) {
            java.lang.String paragraph = paragraphs[i].trim();
            if (paragraph.length() >= 20 && seen.add(textFingerprint(paragraph))) {
                java.util.Map<java.lang.String, java.lang.Object> slice = new java.util.HashMap<>();
                slice.put("index", java.lang.Integer.valueOf(i + 1));
                slice.put("content", paragraph);
                slice.put("length", java.lang.Integer.valueOf(paragraph.length()));
                slice.put("isSummary", java.lang.Boolean.valueOf(isSummarySection(paragraph)));
                slices.add(slice);
            }
        }
        return slices;
    }

    private static java.lang.String textFingerprint(java.lang.String text) {
        if (text == null) {
            return "";
        }
        java.lang.String norm = text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        return norm.length() > 120 ? norm.substring(0, 120) : norm;
    }

    private java.util.List<java.util.Map<java.lang.String, java.lang.Object>> matchSlicesByQuery(java.lang.String plainText, java.lang.String query) {
        int hit;
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> matches = new java.util.ArrayList<>();
        if (plainText == null || plainText.isEmpty()) {
            return matches;
        }
        java.util.List<java.lang.String> terms = new java.util.ArrayList<>();
        for (java.lang.String t : query.split("[、,，;；\\s]+")) {
            java.lang.String term = t.trim();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) {
            return matches;
        }
        java.lang.String[] sentences = plainText.split("[。！？!?]|\\.(?=\\s)|\\n+");
        java.util.Set<java.lang.String> seen = new java.util.HashSet<>();
        for (java.lang.String raw : sentences) {
            java.lang.String sentence = raw.trim();
            if (sentence.length() >= 12 && (hit = queryHitCount(sentence, terms)) >= 2 && seen.add(textFingerprint(sentence))) {
                java.util.Map<java.lang.String, java.lang.Object> one = new java.util.HashMap<>();
                one.put("matchedTerms", java.lang.Integer.valueOf(hit));
                one.put("content", sentence.length() > 600 ? sentence.substring(0, 600) + "…" : sentence);
                matches.add(one);
                if (matches.size() >= 5) {
                    break;
                }
            }
        }
        matches.sort((a, b) -> {
            return java.lang.Integer.compare(((java.lang.Integer) b.get("matchedTerms")).intValue(), ((java.lang.Integer) a.get("matchedTerms")).intValue());
        });
        return matches;
    }

    private static int queryHitCount(java.lang.String sentence, java.util.List<java.lang.String> terms) {
        java.lang.String lower = sentence.toLowerCase(java.util.Locale.ROOT);
        int hit = 0;
        for (java.lang.String term : terms) {
            java.lang.String t = term.toLowerCase(java.util.Locale.ROOT);
            if (!containsCjk(t)) {
                if (containsWord(lower, t)) {
                    hit += 2;
                }
            } else if (lower.contains(t)) {
                hit += 2;
            } else {
                int grams = 0;
                for (int i = 0; i + 2 <= t.length() && grams < 2; i++) {
                    if (lower.contains(t.substring(i, i + 2))) {
                        grams++;
                    }
                }
                hit += grams;
            }
        }
        return hit;
    }

    private static boolean containsCjk(java.lang.String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 19968) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(java.lang.String lowerText, java.lang.String word) {
        int i = 0;
        while (true) {
            int from = i;
            int idx = lowerText.indexOf(word, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + word.length();
            boolean leftOk = idx == 0 || !java.lang.Character.isLetterOrDigit(lowerText.charAt(idx - 1));
            boolean rightOk = end >= lowerText.length() || !java.lang.Character.isLetterOrDigit(lowerText.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            i = idx + 1;
        }
    }

    /** markdown → 纯文本：去标题符号、图片、链接地址、强调符号，保留文字与段落。 */
    private static String markdownToPlain(String md) {
        if (md == null || md.isEmpty()) return "";
        String t = md;
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("(?m)^\\s{0,3}[-*+]\\s+", "");
        t = t.replaceAll("(?m)^\\s{0,3}>\\s?", "");
        t = t.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        t = t.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1");
        t = t.replaceAll("`([^`]*)`", "$1");
        t = t.replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    private java.lang.String removeHtmlTags(java.lang.String html) {
        if (html == null) {
            return "";
        }
        if (html.indexOf(60) >= 0) {
            java.lang.String extracted = com.oilquiz.app.ai.tool.HtmlContentExtractor.mainText(html);
            if (extracted != null && !extracted.isEmpty()) {
                return extracted;
            }
            html = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ").replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ").replaceAll("(?is)<!--.*?-->", " ").replaceAll("<[^>]+>", " ");
        }
        java.lang.String text = decodeHtmlEntities(html);
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n(?:[ \\t]*\\n)+", "\u0001").replaceAll("\\n", " ").replace("\u0001", "\n\n").trim();
    }

    private static java.lang.String decodeHtmlEntities(java.lang.String text) {
        java.lang.String replacement;
        if (text == null || text.indexOf(38) < 0) {
            return text;
        }
        java.lang.String out = text.replace("&nbsp;", " ").replace("&ensp;", " ").replace("&emsp;", " ").replace("&thinsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&copy;", "©").replace("&reg;", "®").replace("&trade;", "™").replace("&times;", "×").replace("&divide;", "÷").replace("&middot;", "·").replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…").replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&laquo;", "«").replace("&raquo;", "»").replace("&deg;", "°");
        java.util.regex.Matcher num = NUMERIC_ENTITY_PATTERN.matcher(out);
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        while (num.find()) {
            try {
                int codePoint = java.lang.Integer.parseInt(num.group(2), num.group(1).isEmpty() ? 10 : 16);
                replacement = codePoint > 0 ? new java.lang.String(java.lang.Character.toChars(codePoint)) : "";
            } catch (java.lang.Exception e) {
                replacement = num.group();
            }
            num.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        num.appendTail(sb);
        return sb.toString();
    }

    private java.lang.String cleanText(java.lang.String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private boolean isSummarySection(java.lang.String text) {
        java.lang.String lowerText = text.toLowerCase();
        return lowerText.contains("总结") || lowerText.contains("摘要") || lowerText.contains("简介") || lowerText.contains("概述") || lowerText.contains("summary") || lowerText.contains("abstract");
    }

    private java.lang.String classifyContent(java.lang.String text) {
        java.lang.String lowerText = text.toLowerCase();
        if (lowerText.contains("新闻") || lowerText.contains("报道") || lowerText.contains("最新")) {
            return "新闻资讯";
        }
        if (lowerText.contains("技术") || lowerText.contains("开发") || lowerText.contains("编程")) {
            return "技术文章";
        }
        if (lowerText.contains("研究") || lowerText.contains("论文") || lowerText.contains("学术")) {
            return "学术研究";
        }
        if (lowerText.contains("报告") || lowerText.contains("分析") || lowerText.contains("市场")) {
            return "行业报告";
        }
        if (lowerText.contains("科普") || lowerText.contains("知识") || lowerText.contains("百科")) {
            return "科普知识";
        }
        return "其他";
    }

    private java.util.List<java.lang.String> extractKeywords(java.lang.String text) {
        java.util.List<java.lang.String> keywords = new java.util.ArrayList<>();
        java.util.Set<java.lang.String> stopWords = new java.util.HashSet<>(java.util.Arrays.asList("的", "是", "在", "有", "和", "了", "我", "你", "他", "她", "它", "这", "那", "这些", "那些", "什么", "怎么", "为什么", "因为", "所以", "但是", "如果", "可以", "可能", "应该", "需要", "会", "不会", "能"));
        stopWords.addAll(java.util.Arrays.asList("the", "of", "and", "to", "in", "is", "are", "for", "with", "that", "this", "as", "on", "by", "an", "be", "it", "or", "from", "at", "you", "your", "we"));
        java.lang.String cleanText = removeHtmlTags(text).toLowerCase();
        java.lang.String[] words = cleanText.split("[^\\u4e00-\\u9fa5a-zA-Z]+");
        java.util.Map<java.lang.String, java.lang.Integer> wordCount = new java.util.HashMap<>();
        for (java.lang.String word : words) {
            java.lang.String token = trimGlueChars(word);
            if (token.length() >= 2 && !stopWords.contains(token) && !KEYWORD_GLUE_WORDS.contains(token)) {
                wordCount.put(token, java.lang.Integer.valueOf(wordCount.getOrDefault(token, 0).intValue() + 1));
            }
        }
        java.util.List<java.util.Map.Entry<java.lang.String, java.lang.Integer>> sortedEntries = new java.util.ArrayList<>(wordCount.entrySet());
        sortedEntries.sort((a, b) -> {
            return ((java.lang.Integer) b.getValue()).compareTo((java.lang.Integer) a.getValue());
        });
        for (int i = 0; i < java.lang.Math.min(10, sortedEntries.size()); i++) {
            keywords.add(sortedEntries.get(i).getKey());
        }
        return keywords;
    }

    private static java.lang.String trimGlueChars(java.lang.String token) {
        int start = 0;
        int end = token.length();
        while (start < end && GLUE_CHARS.indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && GLUE_CHARS.indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(start, end);
    }

    private java.lang.String generateSmartSummary(java.lang.String content, java.lang.String query) {
        if (content == null || content.trim().isEmpty()) {
            return "暂无摘要";
        }
        java.lang.String[] sentences = removeHtmlTags(content).split("[。！？!?]|\\.(?=\\s)|\\n+");
        java.util.List<java.lang.String> terms = new java.util.ArrayList<>();
        if (query != null && !query.trim().isEmpty()) {
            for (java.lang.String t : query.split("[、,，;；\\s]+")) {
                if (t.trim().length() >= 2) {
                    terms.add(t.trim());
                }
            }
        }
        java.util.List<java.lang.Integer> picked = pickSummarySentences(sentences, terms, true);
        if (picked.isEmpty()) {
            picked = pickSummarySentences(sentences, terms, false);
        }
        if (picked.isEmpty()) {
            return "暂无摘要";
        }
        java.lang.StringBuilder summary = new java.lang.StringBuilder();
        for (int i = 0; i < picked.size(); i++) {
            java.lang.String s = sentences[picked.get(i).intValue()].trim();
            if (s.length() > 400) {
                int cut = s.lastIndexOf(32, 400);
                s = s.substring(0, cut > 200 ? cut : 400);
            }
            if (i > 0) {
                summary.append((needsAsciiJoiner(summary) && startsAscii(s)) ? ". " : "。");
            }
            summary.append(s);
        }
        return summary.toString() + "。";
    }

    private static boolean startsAscii(java.lang.String s) {
        return !s.isEmpty() && s.charAt(0) < 128 && java.lang.Character.isLetterOrDigit(s.charAt(0));
    }

    private static boolean needsAsciiJoiner(java.lang.CharSequence previous) {
        char last;
        return previous.length() != 0 && (last = previous.charAt(previous.length() - 1)) < 128 && java.lang.Character.isLetterOrDigit(last);
    }

    private static boolean isBoilerplateSentence(java.lang.String sentence) {
        int hits = 0;
        for (java.lang.String marker : NAV_MARKERS) {
            if (sentence.contains(marker)) {
                hits++;
            }
            if (hits >= 3) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<java.lang.Integer> pickSummarySentences(java.lang.String[] sentences, java.util.List<java.lang.String> terms, boolean filterBoilerplate) {
        java.util.List<java.lang.Integer> valid = new java.util.ArrayList<>();
        java.util.Set<java.lang.String> seen = new java.util.HashSet<>();
        for (int i = 0; i < sentences.length; i++) {
            java.lang.String s = sentences[i].trim();
            if (s.length() >= 20 && ((!filterBoilerplate || !isBoilerplateSentence(s)) && seen.add(textFingerprint(s)))) {
                valid.add(java.lang.Integer.valueOf(i));
            }
        }
        if (valid.isEmpty()) {
            return valid;
        }
        int window = java.lang.Math.min(3, valid.size());
        int bestStart = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int start = 0; start + window <= valid.size(); start++) {
            int score = 0;
            for (int k = 0; k < window; k++) {
                score += sentenceScore(sentences[valid.get(start + k).intValue()].trim(), terms);
            }
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        java.util.List<java.lang.Integer> picked = new java.util.ArrayList<>();
        for (int k2 = 0; k2 < window; k2++) {
            picked.add(valid.get(bestStart + k2));
        }
        return picked;
    }

    private static int sentenceScore(java.lang.String sentence, java.util.List<java.lang.String> terms) {
        return java.lang.Math.min(sentence.length(), 300) + (queryHitCount(sentence, terms) * 100);
    }

    @Override // com.oilquiz.app.ai.tool.AITool
    public java.util.Map<java.lang.String, java.lang.String> getParameterDescriptions() {
        java.util.Map<java.lang.String, java.lang.String> descriptions = new java.util.HashMap<>();
        descriptions.put("action", "操作类型: ask(默认，直接给结论+来源，扣额度)/search(要一批来源/链接，扣额度)/read_url(服务端读网页正文，扣额度)｜get_webpage(本地Jsoup抓网页)/extract_info(页面元数据)/summarize(需 results 或 url)/get_dynamic_content/smart_read(需 results) 都是本地解析、不扣额度｜search_and_read/smart_search 只在搜索那一步扣额度");
        descriptions.put("question", "要回答的完整问题（ask 用；不是关键词，也不是网址）");
        descriptions.put("query", "搜索关键词（search/search_and_read/smart_search 用；网页地址请填 url，别塞进这里）");
        descriptions.put("keyword", "搜索关键词（query 的别名）");
        descriptions.put("model", "问答深度: concise(简洁)/detail(深入)/research(研究)，默认 concise（ask 用）");
        descriptions.put("limit", "返回条数 1..50，默认 5（按次计费，一次给够，别为多看几条反复搜；没有翻页）；search_and_read 的正文读取条数也取它，上限 3 条");
        descriptions.put("num_results", "返回结果数量（limit 的别名）");
        descriptions.put("scope", "搜索范围: webpage(默认)/document(文库)/scholar(学术文献)/image/video/podcast；写 paper 按 scholar 处理");
        descriptions.put("include_summary", "是否返回内容摘要，默认 true（关掉只剩标题+链接，更容易编造）");
        descriptions.put("url", "完整网页地址（read_url/get_webpage/extract_info/get_dynamic_content 用）");
        descriptions.put("content", "网页正文（extract_info/get_dynamic_content 用，与 url 二选一）");
        descriptions.put("start_char", "read_url 正文起始字符偏移，默认 0（长文续读用）");
        descriptions.put("max_chars", "read_url 本次最大字符数，默认 0=整篇，长文建议 4000");
        descriptions.put("with_local", "read_url 是否附带本地抓取的正文做对照（默认 false；开了多一次网络往返，回 local_text/local_chars）");
        descriptions.put("format", "read_url 返回格式：markdown(默认，秘塔原文)/text(转纯文本)/both(都给)");
        descriptions.put("maxResults", "最大结果数（smart_search 用，默认 5）");
        descriptions.put("autoRead", "是否自动读取详情（smart_search 用，默认 true）");
        descriptions.put("results", "搜索结果列表（summarize/smart_read 必填）：上一步 search 返回的 results 数组、它的 JSON 字符串、或整段搜索返回都收");
        descriptions.put("detailIndex", "搜索结果索引（search_and_read 用，从 0 开始，指定要读的那条）");
        return descriptions;
    }
}
