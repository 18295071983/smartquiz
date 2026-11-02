package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.util.AILogger;

import java.net.URLEncoder;
import java.net.URL;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.oilquiz.app.ai.util.NetworkUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

@Tool(
    value = "network_search",
    description = "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取。"
            + "默认用 ask 拿结论（一般提问它就够）；只有要一批来源/链接/原文时才用 search 等其它 action。"
            + "秘塔按次计费（一次调用扣一次额度，与返回条数无关），所以一次问清楚，别反复调。"
            + "ask 出答案要几十秒是正常的，慢不等于失败。"
            + "只有 ask/search/read_url（以及 search_and_read、smart_search 的搜索那一步）扣额度，"
            + "其余抓网页类 action 是本地解析、不花钱。",
    category = "search",
    aliases = {"search", "web_search", "bing_search"},
    actions = {
        @Action(name = "search", description = "【扣额度】关键词搜索：返回标题/链接/摘要列表（只在要一批来源/链接时用；要结论默认用 ask。一次把 limit 给够，别为多看几条反复搜）"),
        @Action(name = "ask", description = "【扣额度】秘塔智能问答：直接返回结论+引用来源。默认用 ask 就够（一般提问都走它）；出答案要几十秒是正常的，慢不等于失败，别重试（重试=再扣一次额度）"),
        @Action(name = "read_url", description = "【扣额度】秘塔服务端读网页：返回 markdown 正文，能处理 JS 渲染页；读不了的页会回服务错误码，这时换来源或改用 get_webpage（本地、免费）"),
        @Action(name = "get_webpage", description = "【本地，不扣额度】用 Jsoup 直接抓网页原始内容"),
        @Action(name = "extract_info", description = "【本地，不扣额度】抽取网页的结构化元数据：标题/meta描述/meta关键词/各级标题/正文切片/内链/分类/关键词。注意：这不是语义问答，想从页面得到某个具体答案请用 read_url 拿正文或 ask 提问"),
        @Action(name = "summarize", description = "【本地，不扣额度】把上一步 search 返回的 results 汇成要点+来源；也可以只给 url，我抓该页再摘要。只填 query 不够"),
        @Action(name = "search_and_read", description = "【search 那一步扣额度】搜索并默认读前 3 条正文（正文抓取是本地 Jsoup，不再额外扣）；要指定某一条就带 detailIndex=N"),
        @Action(name = "get_dynamic_content", description = "【本地，不扣额度】抓 JS 渲染页并挖出内嵌数据（window.DATA 之类）"),
        @Action(name = "smart_search", description = "【search 那一步扣额度】智能搜索：搜完自动按相关度读若干条正文并汇总"),
        @Action(name = "smart_read", description = "【本地，不扣额度】对已有搜索结果逐条读正文并生成摘要：必须传 results（上一步 search 返回的数组，或它的 JSON 字符串）")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型: ask(默认,扣额度)/search(扣额度)/read_url(扣额度)｜get_webpage·extract_info·summarize·get_dynamic_content·smart_read(本地抓取,不扣额度)｜search_and_read·smart_search(只搜索那一步扣)", required = false),
        @Param(name = "query", type = "string", description = "搜索查询（用于search、search_and_read、smart_search操作）", required = false),
        @Param(name = "keyword", type = "string", description = "搜索关键词（query的别名）", required = false),
        @Param(name = "question", type = "string", description = "问题（用于ask操作，秘塔智能问答）", required = false),
        @Param(name = "model", type = "string", description = "问答模型: concise(简洁)/detail(深入)/research(研究)，默认concise（用于ask操作）", required = false),
        @Param(name = "limit", type = "int", description = "返回条数（1..50，默认 5）。接口不支持翻页，要更多结果就调大这个值", required = false),
        @Param(name = "num_results", type = "int", description = "返回结果数量（limit的别名）", required = false),
        @Param(name = "url", type = "string", description = "网页URL（read_url/get_webpage/extract_info/get_dynamic_content 用；summarize 也可以只给 url 走单页摘要）", required = false),
        @Param(name = "maxResults", type = "int", description = "最大结果数(默认5)", required = false),
        // SEARCH-PAGE(2026-11-02)：page 已从 schema 摘掉。真机实测带 page（连 page=0 也一样）
        // 会被服务端判为参数错误（errCode 1000），官方示例与社区实现的 body 里都没有 page，
        // 也就是说"翻页"这条路根本不存在，只能调大 size（上限 50）。
        // 留一个必被服务端拒的参数，等于给模型埋雷。
        // API-AUDIT(2026-11-02)：scope 是六选一（webpage/document/scholar/image/video/podcast），
        // 原实现写死 webpage，等于把"找论文/文库/图片/播客"这些能力锁死了。
        @Param(name = "scope", type = "string", description = "搜索范围：webpage(默认)/document(文库)/scholar(学术文献)/image/video/podcast；写 paper 会按 scholar 处理", required = false),
        // API-AUDIT：includeSummary=false 时结果常常没有 snippet，只剩标题+链接，模型只能编。
        @Param(name = "include_summary", type = "boolean", description = "是否返回内容摘要（默认 true；关掉只省流量但更容易答错）", required = false),
        // API-AUDIT(2026-11-02)：reader 的返回体实测是 JSON（title/author/date/markdown），
        // 长正文分页同样按字符偏移做，避免一次灌爆上下文。
        @Param(name = "start_char", type = "int", description = "read_url 正文起始字符偏移（默认 0）", required = false),
        @Param(name = "max_chars", type = "int", description = "read_url 本次最大字符数（默认 0=整篇；建议 4000）", required = false),
        @Param(name = "autoRead", type = "boolean", description = "是否自动读取详情(默认true)", required = false),
        @Param(name = "results", type = "array", description = "搜索结果数组（summarize/smart_read 用）：把上一步 search 返回的 results 原样传进来；数组、它的 JSON 字符串、或整段搜索返回都收", required = false)
    }
)
public class NetworkSearchTool implements AITool {
    private static final String TAG = "NetworkSearchTool";
    // 秘塔搜索API（Bing搜索API已于2025年8月下线，改用秘塔作为主要搜索引擎）
    private static final String METASO_SEARCH_API_URL = "https://metaso.cn/api/v1/search";
    private static final String METASO_READER_API_URL = "https://metaso.cn/api/v1/reader";
    private static final String METASO_CHAT_API_URL = "https://metaso.cn/api/open/search/v2";
    private static final String DEFAULT_METASO_API_KEY = "mk-3B07FA8984EE0A485E5BB237C2B7D517";
    private final Context context;

    /**
     * 自有搜索工具是否可用：内置默认秘塔 Key 恒在，视为可用。
     * 网络断开/Key 失效在请求层自然失败（调用方回退官方 webSearch 注入）。
     */
    public static boolean isAvailable() {
        return true;
    }

    /**
     * 预搜索：在模型请求前用自有搜索工具（秘塔）搜索，返回适合拼入 prompt 的文本摘要。
     * 供"联网搜索优先走自有工具"使用；失败/无结果返回 null（调用方回退官方 webSearch）。
     */
    public static String preSearch(Context ctx, String query, int limit) {
        if (ctx == null || query == null || query.trim().isEmpty()) return null;
        try {
            NetworkSearchTool tool = new NetworkSearchTool(ctx);
            return tool.buildSearchPrompt(query.trim(), limit > 0 ? limit : 5);
        } catch (Exception e) {
            AILogger.w(TAG, "preSearch failed: " + e.getMessage());
            return null;
        }
    }

    /** 执行搜索并格式化为 prompt 可读摘要（1. 标题 / 链接 / 摘要） */
    private String buildSearchPrompt(String query, int limit) {
        try {
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = resolveApiKey(apiKeyManager);
            List<Map<String, String>> results = metasoSearch(query, limit, "webpage", true, apiKey);
            if (results == null || results.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (Map<String, String> r : results) {
                sb.append(i++).append(". ").append(r.getOrDefault("title", "")).append('\n');
                String url = r.getOrDefault("url", r.getOrDefault("link", ""));
                if (url != null && !url.isEmpty()) {
                    sb.append("   链接: ").append(url).append('\n');
                }
                String snippet = r.getOrDefault("snippet", "");
                if (snippet != null && !snippet.isEmpty()) {
                    sb.append("   摘要: ").append(snippet).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            AILogger.w(TAG, "buildSearchPrompt failed: " + e.getMessage());
            return null;
        }
    }
    
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile("<meta\\s+name\\s*=\\s*[\"']description[\"']\\s+content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_KEYWORDS_PATTERN = Pattern.compile("<meta\\s+name\\s*=\\s*[\"']keywords[\"']\\s+content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern H1_PATTERN = Pattern.compile("<h1[^>]*>([^<]*)</h1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H2_PATTERN = Pattern.compile("<h2[^>]*>([^<]*)</h2>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})|(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})|(\\d{4}年\\d{1,2}月\\d{1,2}日)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1[3-9]\\d{9}|0\\d{2,3}-\\d{7,8})");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s\"'<>]+)");
    private static final Pattern JSON_LD_PATTERN = Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([^<]*)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_CONTENT_PATTERN = Pattern.compile("<script[^>]*>([^<]*)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    
    private static final Set<String> DYNAMIC_CONTENT_KEYWORDS = new HashSet<>(Arrays.asList(
        "window.__INITIAL_STATE__", "window.dataLayer", "window.__REDUX_STATE__",
        "window.APP_DATA", "window.__NEXT_DATA__", "window.__APOLLO_STATE__",
        "hydration", "ReactDOM.hydrate", "SSR_DATA"
    ));
    
    public NetworkSearchTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "network_search";
    }
    
    @Override
    public String getDescription() {
        // 与 @Tool 注解保持一致：注册表 list/search 与 tool_registry 都读这里，
        // 两处口径不同模型就会拿到两个版本的说法。
        return "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取。"
                + "默认用 ask 拿结论（一般提问它就够）；要一批来源/链接/原文才用 search 等其它 action。"
                + "秘塔按次计费（一次调用扣一次额度，与返回条数无关），一次问清楚，别反复调。"
                + "ask 出答案要几十秒是正常的，慢不等于失败。"
                + "只有 ask/search/read_url（含 search_and_read、smart_search 的搜索那一步）扣额度，"
                + "抓网页类的 action 是本地解析、不花钱。";
    }

    /**
     * ASK-SLOW(2026-11-02)：本工具自己申报等待时长。
     *
     * <p>用户口径：秘塔按**次**计费（每次 3 积分，与返回条数无关），而 ask 出答案普遍要几十秒。
     * 以前本地引擎对所有工具套 15 秒、OkHttp 读超时 30 秒，ask 一定会被判"工具超时"——
     * 慢被当成失败，模型接着重试，**每一轮重试都是真金白银**。所以这里把 ask 放宽到 180 秒，
     * search/read_url 60 秒；HTTP 层用同样口径的读超时，别让底层先掐断。</p>
     *
     * <p>调用方（在线 OnlineToolManager、对话页 AgentService、本地 AgentLoopEngine）
     * 都走 {@code ToolTimeouts.effectiveMs}，申报值取"基线与申报的较大者"，一处声明三处生效。</p>
     */
    @Override
    public long executionTimeoutMs(Map<String, Object> args) {
        String action = args == null ? null : String.valueOf(args.get("action"));
        return "ask".equals(action) ? ASK_TIMEOUT_MS : NET_TIMEOUT_MS;
    }

    /** ask：服务端要边搜边生成，实测数十秒；给到 180 秒避免"慢=失败"的误判。 */
    private static final long ASK_TIMEOUT_MS = 180_000L;
    /** search / reader：一次请求一次答案，60 秒足够（含 50 条的大 size）。 */
    private static final long NET_TIMEOUT_MS = 60_000L;

    /**
     * 按申报值取 HTTP 客户端：共享客户端的 readTimeout 是 30 秒，
     * 不在这里放宽的话，工具层的 180 秒永远等不到——底层先抛 SocketTimeout。
     * 用 newBuilder 派生（连接池/线程池仍共享），两个档位各缓存一份，别每次调用都新建。
     */
    private okhttp3.OkHttpClient clientFor(long timeoutMs) {
        okhttp3.OkHttpClient cached = timeoutMs >= ASK_TIMEOUT_MS ? askClient : netClient;
        if (cached != null) return cached;
        okhttp3.OkHttpClient derived = NetworkUtil.getClient().newBuilder()
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
        if (timeoutMs >= ASK_TIMEOUT_MS) askClient = derived; else netClient = derived;
        return derived;
    }

    private static volatile okhttp3.OkHttpClient askClient;
    private static volatile okhttp3.OkHttpClient netClient;
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = getStringParam(parameters, "action", "search");
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
                // get_weather 分支已移除（CORE-TOOLS-4, 2026-10-11）：它只返回一句
                // "天气查询功能请使用 get_weather 工具"，而 get_weather 这个名字**并不在工具注册表里**
                // （天气工具名是 ai_weather），属于会误导模型的陈旧文案；且 action 枚举不含它、
                // 模型无法选中，保留只会让人误以为 network_search 管天气。
                // 天气一律走 ai_weather，逐字对应 system prompt 的选用规则。
                default:
                    return new AIToolResult("Unknown action: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing network search tool: " + e.getMessage(), e);
            return new AIToolResult("Error: " + e.getMessage(), parameters);
        }
    }
    
    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;

        if (parameters.containsKey("keyword") && !parameters.containsKey("query")) {
            parameters.put("query", parameters.get("keyword"));
        }

        if (parameters.containsKey("num_results") && !parameters.containsKey("limit")) {
            parameters.put("limit", parameters.get("num_results"));
        }
    }

    /**
     * 解析秘塔搜索 API Key（METASO-SEARCH，2026-10-11）。
     *
     * <p>背景：此前四处调用点各自写 `getAPIKey(Service.METASO_SEARCH)` 再兜底内置 key，
     * 而 `APIKeyManager.Service.METASO_SEARCH`（扁平键）**全仓没有写入点** —— UI 走的是
     * `APIConfig` 结构化配置（`getAPIConfigByServiceType` / `getAPIConfigsByCategory`），
     * 两套存储互不相通，所以"用户在 UI 里配了搜索 key 也读不到"，永远用内置共享 key。
     *
     * <p>取值顺序（命中即返回）：
     * <ol>
     *   <li>扁平键 {@code Service.METASO_SEARCH}（代码/旧版本写入，优先级最高）</li>
     *   <li>{@code APIConfig} 的 {@code serviceType=metaso_search}（UI "秘塔搜索"）</li>
     *   <li>{@code APIConfig} 的 {@code serviceType=bing_search}（兼容历史配置）</li>
     *   <li>{@code Category.SEARCH} 下 apiHost 指向秘塔的配置（最宽容的兜底）</li>
     *   <li>内置共享 key {@link #DEFAULT_METASO_API_KEY}</li>
     * </ol>
     * 任一来源取到非空值即返回；内置 key 是打进 APK 的共享凭据，可能过期/被限流，
     * 故用户可配置是主要出路（配好后不再走第 5 条）。
     */
    private String resolveApiKey(APIKeyManager mgr) {
        if (mgr == null) return DEFAULT_METASO_API_KEY;
        // 1. 扁平键
        try {
            String flat = mgr.getAPIKey(APIKeyManager.Service.METASO_SEARCH);
            if (flat != null && !flat.trim().isEmpty()) {
                AILogger.i(TAG, "Metaso API key 来源: 扁平键(Service.METASO_SEARCH), key=" + maskKey(flat));
                return flat.trim();
            }
        } catch (Throwable ignored) {
        }
        // 2/3. 结构化配置：先"秘塔搜索"，再兼容"Bing Search"
        try {
            com.oilquiz.app.ai.model.APIConfig c =
                    mgr.getAPIConfigByServiceType(com.oilquiz.app.ai.model.APIConfig.ServiceType.METASO_SEARCH);
            if (c != null && c.getApiKey() != null && !c.getApiKey().trim().isEmpty()) {
                AILogger.i(TAG, "Metaso API key 来源: API配置[秘塔搜索] name=" + c.getName()
                        + ", key=" + maskKey(c.getApiKey()));
                return c.getApiKey().trim();
            }
            c = mgr.getAPIConfigByServiceType(com.oilquiz.app.ai.model.APIConfig.ServiceType.BING_SEARCH);
            if (c != null && c.getApiKey() != null && !c.getApiKey().trim().isEmpty()) {
                AILogger.i(TAG, "Metaso API key 来源: API配置[Bing Search] name=" + c.getName()
                        + ", key=" + maskKey(c.getApiKey()));
                return c.getApiKey().trim();
            }
        } catch (Throwable ignored) {
        }
        // 4. 搜索类配置里 apiHost 指向秘塔的（用户可能选"自定义"）
        try {
            java.util.List<com.oilquiz.app.ai.model.APIConfig> searchConfigs =
                    mgr.getAPIConfigsByCategory(com.oilquiz.app.ai.model.APIConfig.Category.SEARCH);
            if (searchConfigs != null) {
                for (com.oilquiz.app.ai.model.APIConfig c : searchConfigs) {
                    String host = c.getApiHost();
                    if (host != null && host.toLowerCase().contains("metaso")
                            && c.getApiKey() != null && !c.getApiKey().trim().isEmpty()) {
                        AILogger.i(TAG, "Metaso API key 来源: 搜索类配置 apiHost 含 metaso, name=" + c.getName()
                                + ", key=" + maskKey(c.getApiKey()));
                        return c.getApiKey().trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 注意：此处只记来源不记 key 值（避免凭据进 logcat）。
        // 排障时先看这行：若一直落在"内置共享 key"，说明用户配置没被读到 ——
        // 常见原因是配置被 OnlineModelManager 当在线模型停用过（isActive=false），见
        // ApiConfigActivity 的 CATEGORY-AUTOSET 注释。
        AILogger.i(TAG, "Metaso API key 来源: 内置共享 key" + maskKey(DEFAULT_METASO_API_KEY)
                + "（未找到用户配置；该 key 可能过期/受限，建议在「API配置管理」添加【秘塔搜索】并填写 Key）");
        return DEFAULT_METASO_API_KEY;
    }

    /** KEY-LOG(2026-11-02)：只记前后几位与长度，够判断"用的是哪把 Key"，又不把凭据写进日志。 */
    private static String maskKey(String k) {
        if (k == null || k.length() < 10) return "***";
        return k.substring(0, 6) + "…" + k.substring(k.length() - 4) + "(" + k.length() + ")";
    }

    /** 类型安全的字符串参数提取（LLM可能传String/Number/Boolean） */
    private String getStringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        return String.valueOf(v);
    }

    /** API-AUDIT(2026-11-02)：scope 白名单（官方支持六种），非法值退回 webpage。 */
    private static String normalizeScope(String scope) {
        if (scope == null) return "webpage";
        String v = scope.trim().toLowerCase();
        // COMMUNITY-REF(2026-11-02)：参考实现（HundunOnline/mcp-metaso）的 scope 键是
        // webpage/document/**scholar**/image/video/podcast；"paper" 不是合法值，映射过去。
        if ("paper".equals(v)) return "scholar";
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

    /**
     * SCOPE-THRU(2026-11-02)：组合动作（search_and_read / smart_search）内部会重新构造一次
     * search 的参数表。旧实现只搬 query+limit，把用户显式传的 scope/include_summary 丢了，
     * 于是"搜论文"这类要求在组合路径上被悄悄降级成普通网页搜索。这里按需拷贝，缺就不写。
     */
    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from == null || to == null || key == null) return;
        Object v = from.get(key);
        if (v != null) to.put(key, v);
    }

    /** 类型安全的整数参数提取（LLM可能传"5"字符串或5.0浮点） */
    private int getIntParam(Map<String, Object> params, String key, int def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** 类型安全的布尔参数提取（LLM可能传"true"字符串） */
    private boolean getBoolParam(Map<String, Object> params, String key, boolean def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        return def;
    }
    
    /**
     * PARAM-CLARITY(2026-11-02)：缺参数必须说清"这个 action 要填什么、别填成什么"。
     *
     * <p>真机 2B 会把网页地址塞进 query 再调 read_url，拿到一句 "Missing required parameter: url" 就转去
     * search 那条链接 — 一次完全没必要的联网调用（秘塔按次扣额度）。
     * 失败文案是给模型看的下一步指令，不是日志。</p>
     *
     * <p>PHONE-AUDIT(2026-11-02)：用户在手机上把 11 个 action 逐个试了一遍，抓到两处**指错路**：
     * {@code summarize} 真正要的是 {@code results}（或直接给 {@code url}），而默认分支让它补 {@code query}
     * —— 它照做就永远失败（实测四连败）；而 {@code results} 只认 Java List，模型把上一步的数组
     * 原样（JSON 字符串）贴进来也被判"缺 results"。现在按真实必填项说，并且每句都标
     * "这次没发请求、不扣额度" —— 不然模型按"按次计费"的说法会以为改正参数重试还要再扣一次而不敢试。</p>
     */
    private static AIToolResult missingParam(String action, Map<String, Object> parameters) {
        String a = action == null ? "" : action;
        final String free = "（这次没有发出请求，不扣额度）";
        if ("read_url".equals(a) || "get_webpage".equals(a)
                || "get_dynamic_content".equals(a) || "extract_info".equals(a)) {
            return AIToolResult.fail(a + " 需要 url：填完整网页地址，例如 https://example.com/a；"
                    + "别把地址写进 query（query 只给 search 用）" + free, parameters);
        }
        if ("ask".equals(a)) {
            return AIToolResult.fail("ask 需要 question：填要回答的完整问题（不是关键词，也不是网址）" + free, parameters);
        }
        if ("smart_read".equals(a)) {
            return AIToolResult.fail("smart_read 需要 results：把上一步 search 返回的 results 数组原样传进来"
                    + "（数组，每项含 title/url/snippet；整段 JSON 字符串也可以）" + free, parameters);
        }
        if ("summarize".equals(a)) {
            return AIToolResult.fail("summarize 需要 results（上一步 search 返回的 results 数组）或直接给 url"
                    + "（我抓网页再摘要）；query 只是主题标签，光填 query 不够" + free, parameters);
        }
        return AIToolResult.fail(a + " 需要 query：填搜索关键词；要读某个网页请改用 action=read_url 并填 url" + free, parameters);
    }

    /**
     * RESULTS-INPUT(2026-11-02)：{@code results} 容忍三种写法（List / JSON 字符串 / 整段搜索返回）。
     *
     * <p>模型传法不稳定：有时传真数组，有时把上一步的 results 整段当 JSON 字符串贴进来，
     * 有时连外层 {@code {query,count,results:[…]}} 一起传。旧实现只认 {@code instanceof List}，
     * 另两种都判"缺 results"（手机实测）。三种都收，收不下才报缺参。</p>
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseResultsArg(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) return out;
        Object source = raw;
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return out;
            try {
                source = new org.json.JSONTokener(s).nextValue();
            } catch (Throwable t) {
                return out;
            }
        }
        if (source instanceof JSONArray) {
            JSONArray a = (JSONArray) source;
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.optJSONObject(i);
                if (item != null) out.add(jsonToFlatMap(item));
            }
            return out;
        }
        if (source instanceof JSONObject) {
            JSONObject o = (JSONObject) source;
            JSONArray inner = o.optJSONArray("results");
            if (inner == null) inner = o.optJSONArray("data");
            if (inner != null) return parseResultsArg(inner);
            out.add(jsonToFlatMap(o));   // 单条对象也算一条结果
            return out;
        }
        if (source instanceof List) {
            for (Object e : (List<Object>) source) {
                if (e instanceof Map) out.add(new java.util.LinkedHashMap<>((Map<String, Object>) e));
                else if (e instanceof JSONObject) out.add(jsonToFlatMap((JSONObject) e));
            }
        }
        return out;
    }

    /** org.json 对象 → 普通 Map（模型传进来的就是这种标量形状）。 */
    private static Map<String, Object> jsonToFlatMap(JSONObject o) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, o.opt(k));
        }
        return m;
    }

    private AIToolResult search(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int limit = getIntParam(parameters, "limit", 5);

        if (query == null || query.trim().isEmpty()) {
            return missingParam("search", parameters);
        }

        if (limit <= 0) {
            limit = 5;
        }

        try {
            // 秘塔搜索：优先用用户配置的Key（见 resolveApiKey 的取值顺序），未配置则用内置默认Key
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = resolveApiKey(apiKeyManager);

            int searchPage = getIntParam(parameters, "page", 0);
            if (searchPage > 0) {
                // PAGE-IGNORED(2026-11-02)：接口不支持翻页，模型（或旧会话残留参数）传了
                // page 也只能忽略——服务端会判参数错误。留日志是为了"为什么第 2 页还是第一批"
                // 这种问题有据可查，而不是靠猜。
                AILogger.w(TAG, "Metaso search 不支持 page，已忽略 page=" + searchPage
                        + "（要更多结果请调大 limit，上限 50）");
            }
            String searchScope = getStringParam(parameters, "scope", "webpage");
            // 未显式关闭时默认带摘要（摘要就是回答依据；关掉只剩标题+链接，模型容易编）。
            boolean includeSummary = !parameters.containsKey("include_summary")
                    || getBoolParam(parameters, "include_summary", true);
            List<Map<String, String>> searchResults =
                    metasoSearch(query, limit, searchScope, includeSummary, apiKey);

            if (searchResults == null || searchResults.isEmpty()) {
                // FAIL-SEMANTICS(2026-10-11)：必须走 fail()，不能用成功构造器。
                // 原实现返回 success=true 且文案是"未找到…"，而 AgentLoopEngine.isToolSuccess 的
                // containsToolFailure 关键词表里没有"未找到"（只有"无结果/查询失败/超时"），
                // 于是"API key 失效/被限流/网络异常"这类真实故障只要走到无结果分支，
                // 都会被上层判为**成功**：不触发失败提示、不触发重试，
                // 模型还会据此告诉用户"没有找到相关信息"。本工具是本地 4 工具里唯一的信息入口，
                // 静默失败等于实时信息能力整体失效且无信号。
                return AIToolResult.fail(
                    "搜索无结果（服务端返回成功但没有条目，通常是关键词过窄或该 scope 下确无内容）: " + query
                            // COST(2026-11-02)：秘塔按次计费（一次调用一次额度，与条数无关）。
                            // 说清"这次已经扣了"，模型才不会把它当成"没发出去"而连续换词重搜。
                            + "。这次调用已按次扣额度：换词最多再试一次，不要连续重搜",
                    parameters);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("count", searchResults.size());
            // SCOPE-ECHO(2026-11-02)：把**实际生效**的 scope 回给模型。normalizeScope 会把
            // 非法值悄悄退回 webpage，不回显的话模型会以为"搜过论文库"并据此编结论。
            result.put("scope", normalizeScope(searchScope));
            result.put("status", "success");
            result.put("results", searchResults);
            result.put("engine", "Metaso");

            AIToolResult toolResult = new AIToolResult(result, parameters);
            // 附加搜索结果列表组件（富 UI 展示，无需模型输出标记）
            try {
                org.json.JSONArray items = new org.json.JSONArray();
                for (Map<String, String> sr : searchResults) {
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("icon", "🔍");
                    item.put("title", sr.getOrDefault("title", ""));
                    String snippet = sr.getOrDefault("snippet", "");
                    item.put("description", snippet.length() > 80 ? snippet.substring(0, 80) + "..." : snippet);
                    item.put("value", sr.getOrDefault("source", ""));
                    item.put("url", sr.getOrDefault("url", ""));
                    items.put(item);
                }
                org.json.JSONObject props = new org.json.JSONObject();
                props.put("title", "搜索结果 · " + searchResults.size() + " 条");
                props.put("items", items);
                toolResult.withComponent(com.oilquiz.app.ai.chat.component.ComponentData.of("list_card", props));
            } catch (Exception ignore) {
                // 组件附加失败不影响搜索结果返回
            }
            return toolResult;

        } catch (Exception e) {
            AILogger.e(TAG, "Search failed: " + e.getMessage(), e);
            return new AIToolResult("搜索失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * 校验秘塔 API Key 是否可用（供「API配置管理 → 测试连接」调用）。
     *
     * <p><b>为什么必须走真实搜索端点</b>（2026-10-11 联网核实）：
     * 秘塔只提供 POST {@code /api/v1/search} 这类业务端点，**没有**账号/余额/校验类的轻量 GET 端点；
     * 而 {@code APIKeyManager.testAPIConnection} 的通用逻辑是"GET 后看 HTTP 200"，
     * 对秘塔完全不适用（GET 非该端点允许的方法）。所以测试只能发一次**最小真实搜索**。
     *
     * <p><b>代价</b>：秘塔按次计费，本方法会消耗一次调用额度（size=1、includeSummary/RawContent 均 false，
     * 已是能验证鉴权的最小请求）。调用方（UI）需把这点告知用户。
     *
     * <p>与运行时同一套解析逻辑（{@link #parseMetasoError} / {@code code != 0} 业务错误判定），
     * 保证"测试通过"与"实际能用"一致 —— 避免自造一套探测逻辑而与真实行为脱节。
     *
     * @return 形如 {@code "OK|延迟ms"} 或 {@code "FAIL|原因"}（由 APIKeyManager 解析后写入 TestResult）
     */
    public String testApiKey(String apiKey) {
        long start = System.currentTimeMillis();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "FAIL|API Key 为空";
        }
        try {
            JSONObject bodyJson = new JSONObject();
            // 固定 query：秘塔按次计费，用固定词避免用户测试时的查询内容影响可复现性
            bodyJson.put("q", "秘塔AI搜索");
            bodyJson.put("scope", "webpage");
            bodyJson.put("includeSummary", false);
            bodyJson.put("includeRawContent", false);
            bodyJson.put("size", 1);

            RequestBody body = RequestBody.create(bodyJson.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8"));
            Request request = NetworkUtil.createApiRequestBuilder(METASO_SEARCH_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey.trim())
                    .post(body)
                    .build();

            try (Response response = clientFor(NET_TIMEOUT_MS).newCall(request).execute()) {
                long latency = System.currentTimeMillis() - start;
                int code = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (code == 401 || code == 403) {
                    return "FAIL|API Key 无效或无权访问（HTTP " + code + "）";
                }
                if (code == 429) {
                    return "FAIL|触发限流（HTTP 429），请稍后再试";
                }
                if (!response.isSuccessful()) {
                    String err = parseMetasoError(responseBody);
                    return "FAIL|" + (err != null ? err : "HTTP " + code);
                }
                // HTTP 2xx 仍可能是业务错误（秘塔把"余额/鉴权"类问题放在 body 的 code 字段，
                // 见 parseMetasoSearchResponse 的注释与实测）。必须解析 body 才能判定。
                JSONObject json = new JSONObject(responseBody);
                int bizCode = json.optInt("code", 0);
                if (bizCode != 0) {
                    String msg = json.optString("message", "未知错误");
                    return "FAIL|服务返回错误[" + bizCode + "]: " + msg;
                }
                // 注意：webpages 为空**不算失败** —— 鉴权已通过，仅该查询无结果。
                return "OK|" + latency;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "testApiKey failed: " + e.getMessage(), e);
            return "FAIL|" + e.getMessage();
        }
    }

    private List<Map<String, String>> metasoSearch(String query, int count,
                                                   String scope, boolean includeSummary,
                                                   String apiKey) throws Exception {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            // 构造秘塔搜索请求体（参数严格对照秘塔API文档）
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("q", query);
            // API-AUDIT(2026-11-02)：scope 取白名单，默认 webpage；includeSummary 默认 true——
            // 摘要就是模型回答的依据，关掉它省的是流量、丢的是事实。
            bodyJson.put("scope", normalizeScope(scope));
            bodyJson.put("includeSummary", includeSummary);
            bodyJson.put("includeRawContent", false);
            // SEARCH-PAGE(2026-11-02)：size 上限 50；**不传 page**——实测带 page（含 page=0）
            // 服务端直接判 errCode 1000"请求参数错误"，官方示例与社区实现的 body 也没有 page。
            int size = Math.max(1, Math.min(50, count));
            // COMMUNITY-REF：官方示例与社区 MCP 实现都传字符串（"10"），照做减少格式分歧。
            bodyJson.put("size", String.valueOf(size));

            RequestBody body = RequestBody.create(bodyJson.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8"));

            // 使用 NetworkUtil 统一构造请求（带 User-Agent 等 header，与 AIWeatherManager 等保持一致）
            Request request = NetworkUtil.createApiRequestBuilder(METASO_SEARCH_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            AILogger.i(TAG, "Metaso Search: " + query);

            try (Response response = clientFor(NET_TIMEOUT_MS).newCall(request).execute()) {
                int responseCode = response.code();
                AILogger.i(TAG, "Metaso Search Response Code: " + responseCode);

                String responseBody = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    results = parseMetasoSearchResponse(responseBody);
                    AILogger.i(TAG, "Parsed " + results.size() + " search results");
                    if (results.isEmpty()) {
                        // PARSE-DIAG(2026-11-02)：200 但解析不到条目时必须看到真实结构，
                        // 否则只能靠猜（本轮就是 includeSummary 打开后 0 条）。
                        AILogger.w(TAG, "Metaso empty-results body(" + responseBody.length() + "): "
                                + responseBody.substring(0, Math.min(800, responseBody.length())));
                    }
                } else if (responseCode == 401) {
                    throw new Exception("秘塔搜索API认证失败，请检查API密钥是否正确");
                } else if (responseCode == 429) {
                    throw new Exception("秘塔搜索API请求频率超限，请稍后再试");
                } else {
                    // 秘塔可能返回HTTP 200但body含业务错误码，解析错误消息
                    String errMsg = parseMetasoError(responseBody);
                    throw new Exception(errMsg != null ? errMsg : "秘塔搜索API返回错误码: " + responseCode);
                }
            }

        } catch (Exception e) {
            AILogger.e(TAG, "Metaso Search API 调用失败: " + e.getMessage(), e);
            throw e;
        }

        return results;
    }

    /**
     * ERR-CODE(2026-11-02)：秘塔的成败**只看业务码，不看耗时**。
     *
     * <p>三个端点（search / reader / open search v2）都可能在 <b>HTTP 200</b> 的 body 里返回业务错误，
     * 键族有两种：{@code {errCode,errMsg}} 与 {@code {code,message}}。旧实现只认其中一种，
     * 于是 {@code errCode:1000} 被当成"0 条结果"、问答的错误被吞成 null——真正的原因（参数错误、
     * 额度用尽、该页解析不了）对模型完全不可见，模型只能瞎猜并重复请求（秘塔按次计费）。</p>
     *
     * <p>这里只做一件事：把码翻成**带下一步动作**的说法。已知码只写实测见过的（1000/4000/5000），
     * 未知码原样带上编号与消息，**不编造含义**。</p>
     */
    private static String metasoErrorText(int code, String msg) {
        String m = (msg == null || msg.trim().isEmpty()) ? "服务端未给出说明" : msg.trim();
        switch (code) {
            case 1000:
                return "秘塔请求参数错误(1000)：" + m
                        + "。常见原因是传了接口不支持的翻页参数；scope 只能是 webpage/document/scholar/image/video/podcast，size 取 1..50。改正参数再发一次，不要原样重试";
            case 4000:
                return "秘塔未找到相关数据(4000)：" + m
                        + "。这个目标服务端解析不了，换一个来源或换关键词；对同一目标重复请求没有意义";
            case 5000:
                return "秘塔服务端错误(5000)：" + m
                        + "。内置共享 Key 可能已失效或额度用尽——可在「设置 → API 配置 → 秘塔搜索」填自己的 Key。先别重试";
            default:
                return "秘塔服务错误(" + code + ")：" + m
                        + "。重试会再发一次请求，而秘塔按次扣额度，所以先按上面说明判断是否值得重试";
        }
    }

    /** 从响应 body 取业务码；成功/无码返回 null。两种键族都读，缺省按 0 处理。 */
    private static int extractMetasoErrorCode(JSONObject json) {
        if (json == null) return 0;
        if (json.has("errCode")) return json.optInt("errCode", 0);
        if (json.has("code")) return json.optInt("code", 0);
        return 0;
    }

    private static String extractMetasoErrorMsg(JSONObject json) {
        if (json == null) return null;
        String m = json.optString("errMsg", "");
        if (m.isEmpty()) m = json.optString("message", "");
        return m;
    }

    /** 从**原始 body 文本**取业务码；不是 JSON / 没有码字段返回 null（"没有码"≠"码是 0"）。 */
    private static Integer peekMetasoErrorCode(String body) {
        try {
            JSONObject j = new JSONObject(body);
            if (j.has("errCode")) return j.optInt("errCode", 0);
            if (j.has("code")) return j.optInt("code", 0);
        } catch (Throwable notJson) {
            // 网关/HTML 错误页：没有业务码，交给 HTTP 状态码那条路
        }
        return null;
    }

    /** 从原始 body 文本取错误消息（两种键族）。 */
    private static String peekMetasoErrorMsg(String body) {
        try {
            return extractMetasoErrorMsg(new JSONObject(body));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 日志/回传给模型的响应片段：只截够诊断的长度，不把整篇塞进去。 */
    private static String clipForLog(String s) {
        if (s == null || s.isEmpty()) return "(空响应体)";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private List<Map<String, String>> parseMetasoSearchResponse(String jsonResponse) {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            JSONObject responseJson = new JSONObject(jsonResponse);

            // ERRCODE(2026-11-02)：业务码一律走 metasoErrorText —— HTTP 200 + errCode 非 0
            // 是真实故障（参数被拒/额度用尽），绝不能当成"搜到 0 条"交给模型。
            int bizCode = extractMetasoErrorCode(responseJson);
            if (bizCode != 0) {
                String emsg = extractMetasoErrorMsg(responseJson);
                AILogger.w(TAG, "Metaso search 业务错误 code=" + bizCode + " msg=" + emsg);
                throw new IllegalStateException(metasoErrorText(bizCode, emsg));
            }

            // 秘塔搜索结果在 "webpages" 数组中（实测响应结构）
            JSONArray values = responseJson.optJSONArray("webpages");
            if (values == null) {
                // 兼容性回退：尝试其他可能的结果数组字段名
                values = findResultsArray(responseJson);
            }
            if (values == null) {
                AILogger.w(TAG, "未在秘塔响应中找到搜索结果");
                return results;
            }

            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;

                Map<String, String> resultItem = new HashMap<>();
                // 字段名对齐秘塔实测响应：title/link/snippet/date
                resultItem.put("title", firstNonEmpty(item, "title", "name", "subject"));
                // scope=image/video/podcast 时链接字段不同（image_url 等），一并兼容
                resultItem.put("url", firstNonEmpty(item, "link", "url", "linkUrl", "href",
                        "imageUrl", "image_url", "videoUrl", "video_url"));
                resultItem.put("snippet", firstNonEmpty(item, "snippet", "summary", "description", "content", "abstract"));
                resultItem.put("source", firstNonEmpty(item, "source", "siteName", "domain"));
                resultItem.put("date", firstNonEmpty(item, "date", "publishTime", "publishDate", "time"));

                if (!resultItem.get("title").isEmpty() && !resultItem.get("url").isEmpty()) {
                    results.add(resultItem);
                }
            }

        } catch (IllegalStateException biz) {
            // ERR-CODE(2026-11-02)：业务码结论必须**穿过去**，不能被"解析失败"兜住变成空列表。
            // 兜住的后果就是这次事故的形状：服务端明明说了"参数错误/额度问题"，
            // 模型只看到"搜索无结果"，于是换个词再搜（又扣一次额度）。
            throw biz;
        } catch (Exception e) {
            AILogger.e(TAG, "解析秘塔搜索响应失败: " + e.getMessage(), e);
        }

        return results;
    }

    /** 在JSON对象中查找搜索结果数组，兼容多种嵌套结构 */
    private JSONArray findResultsArray(JSONObject json) {
        // COMMUNITY-REF(2026-11-02)：不同 scope 的结果数组键不同，只认 webpages 会让
        // scholar/document/image/video/podcast 全部解析成 0 条。
        String[] directKeys = {"webpages", "documents", "scholars", "images", "videos", "podcasts",
                "results", "data", "searchResults", "items", "list", "value"};
        for (String key : directKeys) {
            JSONArray arr = json.optJSONArray(key);
            if (arr != null && arr.length() > 0) return arr;
        }
        // 嵌套在 data 对象里
        JSONObject data = json.optJSONObject("data");
        if (data != null) {
            for (String key : directKeys) {
                JSONArray arr = data.optJSONArray(key);
                if (arr != null && arr.length() > 0) return arr;
            }
        }
        return null;
    }

    /** 返回JSON对象中第一个非空字符串字段 */
    private String firstNonEmpty(JSONObject obj, String... keys) {
        for (String key : keys) {
            String val = obj.optString(key, "");
            if (!val.isEmpty()) return val;
        }
        return "";
    }

    /** 解析秘塔业务错误消息（HTTP 200但body含错误码的情况） */
    private String parseMetasoError(String responseBody) {
        // ERR-CODE(2026-11-02)：与 search/reader/ask 共用同一套取码与翻码逻辑
        // （{errCode,errMsg} 与 {code,message} 两种键族都可能出现，metasoErrorText 按码给下一步动作）。
        Integer code = peekMetasoErrorCode(responseBody);
        if (code == null || code == 0) return null;
        return metasoErrorText(code, peekMetasoErrorMsg(responseBody));
    }

    /** 秘塔智能问答：基于搜索增强生成（RAG）回答问题，返回答案+引用来源 */
    private AIToolResult askMetaso(Map<String, Object> parameters) {
        String question = getStringParam(parameters, "question", null);
        if (question == null || question.trim().isEmpty()) {
            return missingParam("ask", parameters);
        }

        String model = getStringParam(parameters, "model", "concise");
        if (model.trim().isEmpty()) {
            model = "concise";
        }

        try {
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = resolveApiKey(apiKeyManager);

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("question", question);
            bodyJson.put("model", model);
            bodyJson.put("stream", false);

            RequestBody body = RequestBody.create(bodyJson.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8"));

            Request request = NetworkUtil.createApiRequestBuilder(METASO_CHAT_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            AILogger.i(TAG, "Metaso Chat: " + question);

            try (Response response = clientFor(ASK_TIMEOUT_MS).newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                int http = response.code();
                AILogger.i(TAG, "Metaso Chat Response Code: " + http);

                // ERR-CODE(2026-11-02)：判定只看**码**（业务码优先，其次 HTTP 状态码），不看耗时。
                // 秘塔按次计费，所以每一次"要不要再发一次"都必须由码来决定，而不是"等久了就当失败"。
                Integer biz = peekMetasoErrorCode(responseBody);
                if (biz != null && biz != 0) {
                    String meaning = metasoErrorText(biz, peekMetasoErrorMsg(responseBody));
                    AILogger.w(TAG, "Metaso Chat 业务错误 code=" + biz + " http=" + http);
                    return AIToolResult.fail(meaning, parameters);
                }
                if (http == 401 || http == 403) {
                    return AIToolResult.fail("秘塔问答鉴权失败(HTTP " + http
                            + ")：API Key 无效或无该接口权限，重试没有意义，请检查「设置 → API 配置 → 秘塔搜索」", parameters);
                }
                if (http == 429) {
                    return AIToolResult.fail("秘塔问答被限流(HTTP 429)：请等一会儿再问，连续重发只会继续被限并扣额度", parameters);
                }
                if (http >= 200 && http < 300) {
                    Map<String, Object> result = parseMetasoChatResponse(responseBody);
                    if (result != null) {
                        result.put("question", question);
                        result.put("model", model);
                        result.put("engine", "Metaso");
                        return new AIToolResult(result, parameters);
                    }
                    // 2xx、码为 0、却没有 data：这是响应形态问题，不是"答案失败"。
                    // 不再自动补打一次搜索（那会多扣一次额度），把 body 前缀带回去让判断有依据。
                    return AIToolResult.fail("秘塔问答响应缺少 data 字段（HTTP " + http + "，业务码 0），"
                            + "响应前 200 字: " + clipForLog(responseBody)
                            + "。别原样重试；需要来源可改用 action=search（另一次调用、另扣一次额度）", parameters);
                }
                // 到这里只剩"HTTP 非 2xx 且 body 没有业务码"。历史上 400 是这个端点换了请求形态，
                // 所以只多试**一次**标准 chat_completions。
                AIToolResult cc = (http == 400 || http == 404 || http == 405)
                        ? metasoChatCompletions(question, model, apiKey) : null;
                if (cc != null) return cc;
                // 404/405 = 这个问答端点根本不存在/不允许，属于**能力缺失**而不是本次失败：
                // 这时降级到普通搜索是有意义的（仍是一次独立调用、独立扣额度，note 里写明白）。
                // 其它状态码（5xx/限流/形态问题）不自动补打，避免"一次提问扣两次钱"。
                if (http == 404 || http == 405) {
                    AIToolResult fb = askFallbackToSearch(question, parameters);
                    if (fb != null) return fb;
                }
                AILogger.w(TAG, "Metaso Chat HTTP " + http + " body=" + clipForLog(responseBody));
                return AIToolResult.fail("秘塔问答请求失败（HTTP " + http
                        + "，body 无业务码）。这不代表问法错了，先按状态码判断：4xx=请求形态/权限，5xx=服务端；"
                        + "重试会再扣一次额度，必要时就改用 action=search 自己汇总", parameters);
            }
        } catch (IllegalStateException biz) {
            // 解析层抛出的业务码结论（metasoErrorText），原样交给模型，不再包一层"失败"。
            AILogger.w(TAG, "Metaso Chat 业务错误: " + biz.getMessage());
            return AIToolResult.fail(biz.getMessage(), parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Metaso Chat failed: " + e.getMessage(), e);
            // ASK-SLOW(2026-11-02)：传输层异常（含连接被掐/读超时）**不自动降级去 search**。
            // 秘塔按次计费，一次问题烧两次额度是最糟的结果；这里把码/异常如实报出来，
            // 是否再试由模型（和用户）决定。
            return AIToolResult.fail("秘塔问答请求未完成（传输层异常，不是服务返回的业务码）: " + e.getMessage()
                    + "。ask 出答案本来就要几十秒，别把慢当失败反复重试——每次请求都按次扣额度", parameters);
        }
    }

    /**
     * API-AUDIT(2026-11-02)：问答接口不可用时的降级路径——用同一个问题走普通搜索，
     * 返回结果并标注 mode=search_fallback，模型据此知道"没拿到智能问答，但有网页结果"。
     * 失败返回 null（调用方走原错误）。
     */
    private AIToolResult askFallbackToSearch(String question, Map<String, Object> parameters) {
        try {
            String apiKey = resolveApiKey(APIKeyManager.getInstance(context));
            int limit = getIntParam(parameters, "limit", 5);
            // SCOPE-THRU(2026-11-02)：降级路径也要尊重 scope —— 用户要"论文"而问答接口挂了时，
            // 写死 webpage 会把 scholar 悄悄换成普通网页结果，模型据此作答却毫不知情。
            String scope = getStringParam(parameters, "scope", "webpage");
            List<Map<String, String>> results =
                    metasoSearch(question, limit, scope, true, apiKey);
            if (results == null || results.isEmpty()) return null;
            Map<String, Object> out = new HashMap<>();
            out.put("question", question);
            out.put("scope", normalizeScope(scope));
            out.put("engine", "Metaso");
            out.put("mode", "search_fallback");
            out.put("note", "智能问答接口当前不可用，已回退为普通搜索结果（回退同样算一次调用、扣一次额度），请基于结果自行汇总");
            out.put("results", results);
            AILogger.i(TAG, "Metaso ask fallback to search: " + results.size() + " results");
            return new AIToolResult(out, parameters);
        } catch (Throwable t) {
            AILogger.w(TAG, "askFallbackToSearch failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * API-AUDIT(2026-11-02)：秘塔问答的 OpenAI 兼容形态（/api/v1/chat/completions）。
     * 官方介绍"问答 API 支持 chat_completions 格式接入"，而旧的 /api/open/search/v2 实测 400。
     * 成功返回 {answer, mode:"chat_completions", engine:"Metaso"}；失败返回 null（调用方继续降级）。
     */
    private AIToolResult metasoChatCompletions(String question, String model, String apiKey) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", question);
            msgs.put(m);
            body.put("messages", msgs);
            body.put("stream", false);

            Request req = NetworkUtil.createApiRequestBuilder("https://metaso.cn/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .post(RequestBody.create(body.toString(),
                            okhttp3.MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response resp = clientFor(ASK_TIMEOUT_MS).newCall(req).execute()) {
                String rb = resp.body() != null ? resp.body().string() : "";
                AILogger.i(TAG, "Metaso chat/completions code=" + resp.code());
                if (!resp.isSuccessful()) return null;
                JSONObject j = new JSONObject(rb);
                String answer = null;
                JSONArray choices = j.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject c0 = choices.optJSONObject(0);
                    JSONObject msg = c0 != null ? c0.optJSONObject("message") : null;
                    if (msg != null) answer = msg.optString("content", null);
                }
                if (answer == null || answer.trim().isEmpty()) return null;
                Map<String, Object> out = new HashMap<>();
                out.put("answer", answer);
                out.put("question", question);
                out.put("mode", "chat_completions");
                out.put("engine", "Metaso");
                return new AIToolResult(out, null);
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "metasoChatCompletions failed: " + t.getMessage());
            return null;
        }
    }

    /** 解析秘塔问答响应：{errCode:0, data:{text, references, balance}} */
    private Map<String, Object> parseMetasoChatResponse(String jsonResponse) {
        Map<String, Object> result = new HashMap<>();
        try {
            JSONObject json = new JSONObject(jsonResponse);
            // ERR-CODE(2026-11-02)：问答端点同样"HTTP 200 + body 业务码"。
            // 旧实现把非 0 码判成 null，调用方只能回一句"秘塔问答失败: <整段 body>"，
            // 模型看不到真实原因（额度用尽？参数被拒？），于是重试——而每次重试都按次扣额度。
            // 现在直接把码翻成结论并抛出，由 askMetaso 原样交给模型。
            int code = extractMetasoErrorCode(json);
            if (code != 0) {
                throw new IllegalStateException(metasoErrorText(code, extractMetasoErrorMsg(json)));
            }

            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                AILogger.w(TAG, "秘塔问答响应缺少data字段");
                return null;
            }

            result.put("answer", data.optString("text", ""));
            result.put("balance", data.optInt("balance", 0));

            // 解析引用来源
            JSONArray refs = data.optJSONArray("references");
            if (refs != null) {
                List<Map<String, String>> references = new ArrayList<>();
                for (int i = 0; i < refs.length(); i++) {
                    JSONObject ref = refs.optJSONObject(i);
                    if (ref == null) continue;
                    Map<String, String> refItem = new HashMap<>();
                    refItem.put("title", ref.optString("title", ""));
                    refItem.put("link", ref.optString("link", ""));
                    refItem.put("date", ref.optString("date", ""));
                    refItem.put("source", ref.optString("author", ""));
                    references.add(refItem);
                }
                result.put("references", references);
            }

            return result;
        } catch (IllegalStateException biz) {
            // 业务码结论不能被"解析失败"吞掉（那会退化成一句看不出原因的 null）
            throw biz;
        } catch (Exception e) {
            AILogger.e(TAG, "解析秘塔问答响应失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 秘塔网页读取：服务端抓取并解析网页，返回结构化markdown文本（质量优于本地Jsoup，能处理JS渲染页） */
    private AIToolResult readUrlMetaso(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        if (url == null || url.trim().isEmpty()) {
            return missingParam("read_url", parameters);
        }

        try {
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = resolveApiKey(apiKeyManager);

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("url", url);
            // COMMUNITY-REF(2026-11-02)：参考实现只传 {url} + Accept: text/plain，
            // 不传 format（该字段在本版本上并非必填，带上反而多一个未知参数）。
            // 返回体既可能是 JSON（{title,markdown,...}）也可能是纯文本，解析侧两种都处理。

            RequestBody body = RequestBody.create(bodyJson.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8"));

            Request request = NetworkUtil.createApiRequestBuilder(METASO_READER_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "text/plain")
                    .post(body)
                    .build();

            AILogger.i(TAG, "Metaso Reader: " + url);

            try (Response response = clientFor(NET_TIMEOUT_MS).newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                AILogger.i(TAG, "Metaso Reader Response Code: " + response.code());

                if (response.isSuccessful()) {
                    // API-AUDIT(2026-11-02)：实测 /api/v1/reader 无论 format=json|markdown
                    // 都返回同一份 JSON：{title,url,author,date,markdown,credits}。
                    // 旧实现把整段 JSON 当 content 塞给模型 → "JSON 套 JSON"，正文还得模型自己解。
                    // 这里解析出结构化字段，正文给 markdown 字段；解析失败才退回原文。
                    Map<String, Object> result = new HashMap<>();
                    result.put("url", url);
                    result.put("engine", "Metaso");
                    String contentText = responseBody;
                    try {
                        JSONObject j = new JSONObject(responseBody);
                        // API-AUDIT(2026-11-02)：读取接口出错时同样是 HTTP 200 + JSON
                        // {"errCode":4000,"errMsg":"未找到相关数据"}（例如 SPA 首页抓不到正文）。
                        // 不拦就会把错误 JSON 当正文交给模型（假成功）→ 模型基于错误内容编答案。
                        // 判定用与 search/ask 同一套 metasoErrorText（按码，不按耗时）。
                        int readerErr = extractMetasoErrorCode(j);
                        String mdPreview = j.optString("markdown", "");
                        if (readerErr != 0 && mdPreview.isEmpty()) {
                            String errMsg = extractMetasoErrorMsg(j);
                            AILogger.w(TAG, "Metaso Reader errCode=" + readerErr
                                    + " msg=" + errMsg + " url=" + url);
                            return new AIToolResult(metasoErrorText(readerErr, errMsg)
                                    + "；本工具读的是秘塔服务端解析结果，也可用工具注册表调用 webpage_reader 直抓", parameters);
                        }
                        if (j.has("title")) result.put("title", j.optString("title", ""));
                        if (j.has("author")) result.put("author", j.optString("author", ""));
                        if (j.has("date")) result.put("date", j.optString("date", ""));
                        String md = j.optString("markdown", "");
                        contentText = md != null && !md.isEmpty() ? md : j.optString("content", responseBody);
                    } catch (Throwable notJson) {
                        // 非 JSON（个别站点/网关）→ 保留原文
                    }
                    // 长正文按字符分页（与 webpage_reader 同口径）
                    int startChar = Math.max(0, getIntParam(parameters, "start_char", 0));
                    int maxChars = getIntParam(parameters, "max_chars", 0);
                    if (startChar > 0 || maxChars > 0) {
                        int total = contentText.length();
                        if (startChar > total) startChar = total;
                        int end = maxChars > 0 ? Math.min(total, startChar + maxChars) : total;
                        result.put("text", contentText.substring(startChar, end));
                        Map<String, Object> page = new HashMap<>();
                        page.put("total_chars", total);
                        page.put("start_char", startChar);
                        page.put("end_char", end);
                        page.put("truncated", end < total);
                        page.put("next_start", end);
                        result.put("page", page);
                    } else {
                        result.put("text", contentText);
                    }
                    return new AIToolResult(result, parameters);
                } else if (response.code() == 401) {
                    return new AIToolResult("秘塔API认证失败，请检查API密钥", parameters);
                } else {
                    return new AIToolResult("秘塔网页读取失败，HTTP码: " + response.code(), parameters);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Metaso Reader failed: " + e.getMessage(), e);
            return new AIToolResult("秘塔网页读取失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getWebpage(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);

        if (url == null) {
            return missingParam("get_webpage", parameters);
        }

        try {
            String content = fetchWebpage(url);

            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("status", "success");
            result.put("content", content);
            result.put("length", content.length());

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取网页失败: " + e.getMessage(), parameters);
        }
    }

    private String fetchWebpage(String urlString) throws Exception {
        Request request = NetworkUtil.createRequestBuilder(urlString)
                .get()
                .build();

        try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP Error: " + response.code());
            }
            return response.body() != null ? response.body().string() : "";
        }
    }
    
    private AIToolResult searchAndRead(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int limit = getIntParam(parameters, "limit", 3);
        int detailIndex = getIntParam(parameters, "detailIndex", -1);

        if (query == null || query.trim().isEmpty()) {
            return missingParam("search_and_read", parameters);
        }

        if (limit <= 0) {
            limit = 3;
        }

        try {
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", limit);
            // SCOPE-THRU(2026-11-02)：组合路径要透传 scope/include_summary。
            // 旧实现只搬 query+limit，用户要"论文"时 search_and_read 会悄悄搜成普通网页。
            copyIfPresent(parameters, searchParams, "scope");
            copyIfPresent(parameters, searchParams, "include_summary");

            AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }

            Map<String, Object> searchData = (Map<String, Object>) searchResult.getResult();
            List<Map<String, String>> results = (List<Map<String, String>>) searchData.get("results");
            
            if (detailIndex >= 0 && detailIndex < results.size()) {
                Map<String, String> selectedResult = results.get(detailIndex);
                String detailUrl = selectedResult.get("url");
                
                Map<String, Object> detailResult = new HashMap<>();
                detailResult.put("query", query);
                detailResult.put("selectedIndex", detailIndex);
                detailResult.put("selectedTitle", selectedResult.get("title"));
                detailResult.put("selectedUrl", detailUrl);
                
                try {
                    String content = fetchWebpage(detailUrl);
                    Map<String, Object> extracted = extractWebpageContent(content, detailUrl);
                    detailResult.put("content", extracted);
                    detailResult.put("status", "success_with_detail");
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to fetch detail page: " + e.getMessage());
                    detailResult.put("content", "无法获取详情页内容: " + e.getMessage());
                    detailResult.put("status", "success_without_detail");
                }
                
                return new AIToolResult(detailResult, parameters);
            }

            // ACTION-NAME(2026-11-02)：不传 detailIndex 时**默认就读**，不再只回一句"请带 detailIndex"。
            // 用户在手机实测：这个 action 名字叫 search_and_read 却只给清单，要模型再来一轮逐条指定，
            // 白搭一轮推理。正文抓取走本地 Jsoup（fetchWebpage），**不调秘塔、不扣额度**，
            // 所以默认读前几条没有花钱代价；上限 3 条只是控上下文。
            int autoReadCount = Math.min(3, results.size());
            List<Map<String, Object>> detailed = new ArrayList<>();
            for (int i = 0; i < autoReadCount; i++) {
                Map<String, String> r = results.get(i);
                String u = r.get("url");
                Map<String, Object> one = new HashMap<>();
                one.put("index", i);
                one.put("title", r.get("title"));
                one.put("url", u);
                try {
                    String html = fetchWebpage(u);
                    one.put("content", extractWebpageContent(html, u));
                    one.put("read_ok", true);
                } catch (Exception e) {
                    AILogger.w(TAG, "search_and_read 默认读第 " + i + " 条失败: " + e.getMessage());
                    one.put("error", "这一页抓不下来: " + e.getMessage());
                    one.put("read_ok", false);
                }
                detailed.add(one);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("count", results.size());
            result.put("status", "success_with_details");
            result.put("results", results);
            result.put("detailedContents", detailed);
            result.put("note", "已默认读取前 " + autoReadCount + " 条正文（本地抓取，不额外扣额度）；"
                    + "要读指定那一条就带 detailIndex=N（从 0 开始）");

            return new AIToolResult(result, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Search and read failed: " + e.getMessage(), e);
            return new AIToolResult("搜索并阅读失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getDynamicContent(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        String content = getStringParam(parameters, "content", null);

        if (url == null && content == null) {
            return missingParam("get_dynamic_content", parameters);
        }

        try {
            if (content == null) {
                content = fetchWebpage(url);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("isDynamic", isDynamicPage(content));
            
            if (isDynamicPage(content)) {
                result.put("dynamicData", extractDynamicData(content));
            }
            
            result.put("content", extractWebpageContent(content, url));
            result.put("status", "success");

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Get dynamic content failed: " + e.getMessage(), e);
            return new AIToolResult("获取动态内容失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult smartSearch(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int maxResults = getIntParam(parameters, "maxResults", 5);
        boolean autoRead = getBoolParam(parameters, "autoRead", true);

        if (query == null || query.trim().isEmpty()) {
            return missingParam("smart_search", parameters);
        }

        if (maxResults <= 0) {
            maxResults = 5;
        }

        try {
            AILogger.i(TAG, "Starting smart search for: " + query);
            
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", maxResults);
            // SCOPE-THRU(2026-11-02)：同 search_and_read，别把 scope 丢成默认 webpage。
            copyIfPresent(parameters, searchParams, "scope");
            copyIfPresent(parameters, searchParams, "include_summary");
            
            AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }

            Map<String, Object> searchData = (Map<String, Object>) searchResult.getResult();
            List<Map<String, String>> results = (List<Map<String, String>>) searchData.get("results");
            
            if (results == null || results.isEmpty()) {
                // FAIL-SEMANTICS(2026-10-11)：同 search()，见该处说明。此处 searchResult 是成功但
                // 无条目，同样必须按失败上报，否则上层判成功、用户被告知"没有找到"。
                return AIToolResult.fail(
                    "搜索无结果（服务端返回成功但没有条目，通常是关键词过窄或该 scope 下确无内容）: " + query
                            // COST(2026-11-02)：秘塔按次计费（一次调用一次额度，与条数无关）。
                            // 说清"这次已经扣了"，模型才不会把它当成"没发出去"而连续换词重搜。
                            + "。这次调用已按次扣额度：换词最多再试一次，不要连续重搜",
                    parameters);
            }

            List<Map<String, Object>> analyzedResults = analyzeSearchResults(results, query);
            List<Integer> selectedIndices = selectRelevantResults(analyzedResults);

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("query", query);
            finalResult.put("totalResults", results.size());
            finalResult.put("analyzedResults", analyzedResults);
            finalResult.put("selectedIndices", selectedIndices);

            if (autoRead && !selectedIndices.isEmpty()) {
                List<Map<String, Object>> detailedContents = new ArrayList<>();
                
                for (Integer index : selectedIndices) {
                    try {
                        Map<String, String> result = results.get(index);
                        String url = result.get("url");
                        
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("index", index);
                        detail.put("title", result.get("title"));
                        detail.put("url", url);
                        detail.put("originalSnippet", result.get("snippet"));
                        
                        try {
                            String content = fetchWebpage(url);
                            Map<String, Object> extracted = extractWebpageContent(content, url);
                            detail.put("content", extracted);
                            detail.put("success", true);
                        } catch (Exception e) {
                            AILogger.w(TAG, "Failed to fetch detail for URL: " + url);
                            detail.put("error", "无法获取详情: " + e.getMessage());
                            detail.put("success", false);
                        }
                        
                        detailedContents.add(detail);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Error processing result index " + index + ": " + e.getMessage());
                    }
                }
                
                finalResult.put("detailedContents", detailedContents);
                finalResult.put("summary", generateSmartSummaryFromDetails(detailedContents, query));
                finalResult.put("status", "success_with_details");
            } else {
                finalResult.put("status", "success");
                finalResult.put("note", "使用 autoRead=true 可自动获取详情内容");
            }

            return new AIToolResult(finalResult, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Smart search failed: " + e.getMessage(), e);
            return new AIToolResult("智能搜索失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult smartRead(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        // RESULTS-INPUT(2026-11-02)：List / JSON 字符串 / 整段搜索返回都收（手机实测原样传被拒）。
        List<Map<String, Object>> results = parseResultsArg(parameters.get("results"));

        if (results.isEmpty()) {
            return missingParam("smart_read", parameters);
        }

        try {
            List<Map<String, Object>> analyzedResults = new ArrayList<>();

            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> result = results.get(i);
                String title = String.valueOf(result.get("title"));
                String snippet = String.valueOf(result.get("snippet"));
                String url = String.valueOf(result.get("url"));
                
                Map<String, Object> analysis = analyzeResult(title, snippet, url, query, i);
                analyzedResults.add(analysis);
            }

            List<Integer> selectedIndices = selectRelevantResults(analyzedResults);
            List<Map<String, Object>> detailedContents = new ArrayList<>();

            for (Integer index : selectedIndices) {
                try {
                    Map<String, Object> result = results.get(index);
                    String url = (String) result.get("url");
                    
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("index", index);
                    detail.put("title", result.get("title"));
                    detail.put("url", url);
                    
                    try {
                        String content = fetchWebpage(url);
                        Map<String, Object> extracted = extractWebpageContent(content, url);
                        detail.put("content", extracted);
                        detail.put("success", true);
                    } catch (Exception e) {
                        detail.put("error", "无法获取详情: " + e.getMessage());
                        detail.put("success", false);
                    }
                    
                    detailedContents.add(detail);
                } catch (Exception e) {
                    AILogger.e(TAG, "Error reading result index " + index + ": " + e.getMessage());
                }
            }

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("query", query);
            finalResult.put("selectedCount", selectedIndices.size());
            finalResult.put("selectedIndices", selectedIndices);
            finalResult.put("detailedContents", detailedContents);
            finalResult.put("summary", generateSmartSummaryFromDetails(detailedContents, query));
            finalResult.put("status", "success");

            return new AIToolResult(finalResult, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Smart read failed: " + e.getMessage(), e);
            return new AIToolResult("智能阅读失败: " + e.getMessage(), parameters);
        }
    }
    
    private List<Map<String, Object>> analyzeSearchResults(List<Map<String, String>> results, String query) {
        List<Map<String, Object>> analyzed = new ArrayList<>();
        
        for (int i = 0; i < results.size(); i++) {
            Map<String, String> result = results.get(i);
            String title = result.get("title");
            String snippet = result.get("snippet");
            String url = result.get("url");
            
            Map<String, Object> analysis = analyzeResult(title, snippet, url, query, i);
            analyzed.add(analysis);
        }
        
        return analyzed;
    }
    
    private Map<String, Object> analyzeResult(String title, String snippet, String url, String query, int index) {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("index", index);
        analysis.put("title", title);
        analysis.put("snippet", snippet);
        analysis.put("url", url);
        
        double relevanceScore = calculateRelevance(title, snippet, query);
        analysis.put("relevanceScore", relevanceScore);
        
        boolean isHighQuality = isHighQualityResult(title, snippet, url);
        analysis.put("isHighQuality", isHighQuality);
        
        String contentCategory = classifyUrl(url);
        analysis.put("contentCategory", contentCategory);
        
        analysis.put("trustworthiness", calculateTrustworthiness(url));
        
        return analysis;
    }
    
    private double calculateRelevance(String title, String snippet, String query) {
        if (title == null || snippet == null || query == null) {
            return 0.0;
        }
        
        double score = 0.0;
        String lowerTitle = title.toLowerCase();
        String lowerSnippet = snippet.toLowerCase();
        String lowerQuery = query.toLowerCase();
        
        String[] queryWords = lowerQuery.split("\\s+");
        
        for (String word : queryWords) {
            if (lowerTitle.contains(word)) {
                score += 2.0;
            }
            if (lowerSnippet.contains(word)) {
                score += 1.0;
            }
        }
        
        score /= queryWords.length * 3.0;
        
        if (lowerTitle.startsWith(lowerQuery)) {
            score += 0.2;
        }
        
        return Math.min(1.0, score);
    }
    
    private boolean isHighQualityResult(String title, String snippet, String url) {
        if (title == null || title.length() < 5) return false;
        if (snippet == null || snippet.length() < 20) return false;
        if (url == null || !url.startsWith("https://")) return false;
        
        String[] trustedDomains = {"gov.cn", "edu.cn", "org.cn", ".com.cn", ".net.cn"};
        for (String domain : trustedDomains) {
            if (url.contains(domain)) {
                return true;
            }
        }
        
        return snippet.length() > 100;
    }
    
    private String classifyUrl(String url) {
        if (url == null) return "unknown";
        
        if (url.contains(".gov.")) return "government";
        if (url.contains(".edu.")) return "education";
        if (url.contains(".org")) return "organization";
        if (url.contains("news.") || url.contains(".news.")) return "news";
        if (url.contains("blog.") || url.contains(".blog.")) return "blog";
        if (url.contains("github.com") || url.contains("gitcode.com")) return "code";
        if (url.contains("baike.") || url.contains("wiki")) return "encyclopedia";
        
        return "general";
    }
    
    private double calculateTrustworthiness(String url) {
        if (url == null) return 0.0;
        
        double score = 0.5;
        
        if (url.startsWith("https://")) score += 0.1;
        if (url.contains(".gov.")) score += 0.2;
        if (url.contains(".edu.")) score += 0.2;
        if (url.contains(".org")) score += 0.1;
        
        return Math.min(1.0, score);
    }
    
    private List<Integer> selectRelevantResults(List<Map<String, Object>> analyzedResults) {
        List<Integer> selected = new ArrayList<>();
        
        analyzedResults.sort((a, b) -> {
            double scoreA = (Double) a.get("relevanceScore");
            double scoreB = (Double) b.get("relevanceScore");
            return Double.compare(scoreB, scoreA);
        });
        
        for (int i = 0; i < Math.min(3, analyzedResults.size()); i++) {
            Map<String, Object> result = analyzedResults.get(i);
            double relevance = (Double) result.get("relevanceScore");
            boolean highQuality = (Boolean) result.get("isHighQuality");
            
            if (relevance > 0.3 || highQuality) {
                selected.add((Integer) result.get("index"));
            }
        }
        
        if (selected.isEmpty() && !analyzedResults.isEmpty()) {
            selected.add((Integer) analyzedResults.get(0).get("index"));
        }
        
        return selected;
    }
    
    private Map<String, Object> generateSmartSummaryFromDetails(List<Map<String, Object>> details, String query) {
        Map<String, Object> summary = new HashMap<>();
        List<String> keyPoints = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        
        for (Map<String, Object> detail : details) {
            boolean success = (Boolean) detail.get("success");
            if (!success) continue;
            
            String title = (String) detail.get("title");
            String url = (String) detail.get("url");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) detail.get("content");
            if (content != null) {
                String summaryText = (String) content.get("summary");
                @SuppressWarnings("unchecked")
                List<String> keywords = (List<String>) content.get("keywords");
                
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
        
        summary.put("keyPoints", keyPoints);
        summary.put("sources", sources);
        summary.put("detailedSummary", fullText.toString().trim());
        summary.put("conciseSummary", generateConciseSummary(keyPoints, fullText.toString(), query));
        
        return summary;
    }
    
    private String generateConciseSummary(List<String> keyPoints, String fullText, String query) {
        StringBuilder summary = new StringBuilder();
        
        if (keyPoints != null && !keyPoints.isEmpty()) {
            summary.append("搜索到以下相关信息：\n");
            for (int i = 0; i < keyPoints.size(); i++) {
                summary.append(i + 1).append(". ").append(keyPoints.get(i)).append("\n");
            }
        }
        
        if (fullText != null && !fullText.isEmpty()) {
            String text = removeHtmlTags(fullText);
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            summary.append("\n详细摘要：").append(text);
        }
        
        if (query != null) {
            summary.append("\n\n如需了解更多关于 \"").append(query).append("\" 的信息，请告诉我。");
        }
        
        return summary.toString();
    }
    
    private boolean isDynamicPage(String content) {
        for (String keyword : DYNAMIC_CONTENT_KEYWORDS) {
            if (content.contains(keyword)) {
                AILogger.i(TAG, "Detected dynamic page with keyword: " + keyword);
                return true;
            }
        }
        return content.contains("application/ld+json") || 
               content.contains("window.") && content.contains("=") && content.contains(";");
    }
    
    private Map<String, Object> extractDynamicData(String content) {
        Map<String, Object> dynamicData = new HashMap<>();
        
        String jsonLdData = extractJsonLd(content);
        if (jsonLdData != null && !jsonLdData.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(jsonLdData);
                dynamicData.put("jsonLd", jsonObject);
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to parse JSON-LD: " + e.getMessage());
            }
        }
        
        Map<String, String> windowVariables = extractWindowVariables(content);
        if (!windowVariables.isEmpty()) {
            dynamicData.put("windowVariables", windowVariables);
        }
        
        return dynamicData;
    }
    
    private String extractJsonLd(String content) {
        Matcher matcher = JSON_LD_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    private Map<String, String> extractWindowVariables(String content) {
        Map<String, String> variables = new HashMap<>();
        
        for (String keyword : DYNAMIC_CONTENT_KEYWORDS) {
            int index = content.indexOf(keyword);
            if (index != -1) {
                int endIndex = content.indexOf(";", index);
                if (endIndex == -1) {
                    endIndex = content.indexOf("</script>", index);
                }
                if (endIndex != -1) {
                    String variableContent = content.substring(index, endIndex).trim();
                    if (variableContent.contains("=")) {
                        String[] parts = variableContent.split("=", 2);
                        if (parts.length == 2) {
                            variables.put(parts[0].trim(), parts[1].trim());
                        }
                    }
                }
            }
        }
        
        return variables;
    }
    
    private Map<String, Object> extractWebpageContent(String content, String url) {
        Map<String, Object> extracted = new HashMap<>();
        
        extracted.put("title", extractTitle(content));
        extracted.put("metaDescription", extractMetaDescription(content));
        extracted.put("metaKeywords", extractMetaKeywords(content));
        extracted.put("headings", extractHeadings(content));
        extracted.put("contentSlices", sliceContent(content));
        extracted.put("category", classifyContent(content));
        extracted.put("keywords", extractKeywords(content));
        extracted.put("summary", generateSmartSummary(removeHtmlTags(content), null));
        
        List<Map<String, Object>> links = extractInternalLinks(content, url);
        extracted.put("relatedLinks", links);
        
        return extracted;
    }
    
    private List<Map<String, Object>> extractInternalLinks(String content, String baseUrl) {
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        
        Matcher matcher = URL_PATTERN.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(1);
            
            if (url.length() > 256) continue;
            if (seenUrls.contains(url)) continue;
            
            String baseDomain = getDomain(baseUrl);
            String linkDomain = getDomain(url);
            
            Map<String, Object> linkInfo = new HashMap<>();
            linkInfo.put("url", url);
            linkInfo.put("isInternal", baseDomain != null && linkDomain != null && baseDomain.equals(linkDomain));
            linkInfo.put("domain", linkDomain);
            
            links.add(linkInfo);
            seenUrls.add(url);
            
            if (links.size() >= 20) break;
        }
        
        return links;
    }
    
    private String getDomain(String url) {
        try {
            URL uri = new URL(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
    
    private AIToolResult extractInfo(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        String content = getStringParam(parameters, "content", null);

        if (url == null && content == null) {
            return missingParam("extract_info", parameters);
        }

        try {
            if (content == null) {
                content = fetchWebpage(url);
            }

            Map<String, Object> extractedInfo = new HashMap<>();
            extractedInfo.put("url", url);
            
            extractedInfo.put("title", extractTitle(content));
            extractedInfo.put("metaDescription", extractMetaDescription(content));
            extractedInfo.put("metaKeywords", extractMetaKeywords(content));
            extractedInfo.put("headings", extractHeadings(content));
            extractedInfo.put("dates", extractDates(content));
            extractedInfo.put("phones", extractPhones(content));
            extractedInfo.put("emails", extractEmails(content));
            extractedInfo.put("urls", extractUrls(content));
            extractedInfo.put("contentSlices", sliceContent(content));
            extractedInfo.put("category", classifyContent(content));
            extractedInfo.put("keywords", extractKeywords(content));

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("extracted", extractedInfo);

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Extract info failed: " + e.getMessage(), e);
            return new AIToolResult("提取信息失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult summarizeResults(Map<String, Object> parameters) {
        // RESULTS-INPUT(2026-11-02)：这里原来是 `(List) parameters.get("results")` 裸强转 ——
        // 模型把数组当 JSON 字符串贴进来时直接 ClassCastException，整条摘要报"生成摘要失败"。
        List<Map<String, Object>> results = parseResultsArg(parameters.get("results"));
        String query = getStringParam(parameters, "query", null);

        if (results == null || results.isEmpty()) {
            // PHONE-AUDIT(2026-11-02)：模型按描述里"网页摘要"的字面理解传了 url，却被要求补 results，
            // 于是"四连失败"。摘要本来就该能吃 url —— 现在先试单页路径（本地抓取，不发秘塔请求、不扣额度），
            // 两个都没有才报缺参，且文案按真实必填项说。
            AIToolResult single = summarizeSingleUrl(parameters);
            if (single != null) return single;
            return missingParam("summarize", parameters);
        }

        try {
            Map<String, Object> summary = new HashMap<>();
            summary.put("query", query);
            summary.put("totalResults", results.size());
            
            List<String> keyPoints = new ArrayList<>();
            List<String> sources = new ArrayList<>();
            StringBuilder fullSummary = new StringBuilder();

            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> item = results.get(i);
                // 值可能是 Number/布尔（JSON 原样传进来），不能按 String 强转（ClassCastException
                // 会把整条摘要打成"生成摘要失败"，用户看到的又是一次无谓重试）
                String title = strOf(item.get("title"));
                String snippet = strOf(item.get("snippet"));
                String url = strOf(item.get("url"));

                if (title != null && !title.isEmpty()) {
                    keyPoints.add((i + 1) + ". " + title);
                    sources.add(url);
                }
                
                if (snippet != null && !snippet.isEmpty()) {
                    fullSummary.append(snippet).append("\n\n");
                }
            }

            summary.put("keyPoints", keyPoints);
            summary.put("sources", sources);
            summary.put("summary", generateSmartSummary(fullSummary.toString(), query));
            summary.put("detailedSummary", fullSummary.toString().trim());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("summary", summary);
            result.put("source_note", "本摘要由你传入的 results 合成，未再调用秘塔，不扣额度");

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Summarize failed: " + e.getMessage(), e);
            return new AIToolResult("生成摘要失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * summarize 的单页形态：给 url（或已有 content）就抓来抓去摘要，本地 Jsoup，不发秘塔请求。
     * 拿不到 url 时返回 null，由调用方报缺参。
     */
    private AIToolResult summarizeSingleUrl(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        try {
            String html = getStringParam(parameters, "content", null);
            if ((html == null || html.trim().isEmpty()) && url != null && !url.trim().isEmpty()) {
                html = fetchWebpage(url);
            }
            if (html == null || html.trim().isEmpty()) return null;

            Map<String, Object> page = extractWebpageContent(html, url);
            // extractWebpageContent 没有 "text" 键（它给的是 headings/contentSlices/summary），
            // 正文自己从 HTML 取，省得依赖一个不存在的字段拿到空摘要。
            String body = removeHtmlTags(html);
            if (body == null) body = "";
            Map<String, Object> summary = new HashMap<>();
            summary.put("url", url);
            summary.put("title", page.get("title"));
            summary.put("summary", generateSmartSummary(body, getStringParam(parameters, "query", null)));
            summary.put("detailedSummary", body.trim());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("summary", summary);
            result.put("source_note", "单页摘要：本地抓取解析，未调用秘塔，不扣额度");
            AILogger.i(TAG, "summarize 走单页形态 url=" + url);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.w(TAG, "summarize 单页形态失败: " + e.getMessage());
            return null;
        }
    }

    /** 安全取字符串：非字符串值（数字/布尔）也转成文本，不抛 ClassCastException。 */
    private static String strOf(Object v) {
        if (v == null || v == JSONObject.NULL) return null;
        return v instanceof String ? (String) v : String.valueOf(v);
    }
    
    private String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "";
    }
    
    private String extractMetaDescription(String html) {
        Matcher matcher = META_DESCRIPTION_PATTERN.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "";
    }
    
    private String extractMetaKeywords(String html) {
        Matcher matcher = META_KEYWORDS_PATTERN.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "";
    }
    
    private List<String> extractHeadings(String html) {
        List<String> headings = new ArrayList<>();
        
        Matcher h1Matcher = H1_PATTERN.matcher(html);
        while (h1Matcher.find()) {
            headings.add("H1: " + cleanText(h1Matcher.group(1)));
        }
        
        Matcher h2Matcher = H2_PATTERN.matcher(html);
        while (h2Matcher.find()) {
            headings.add("H2: " + cleanText(h2Matcher.group(1)));
        }
        
        return headings;
    }
    
    private List<String> extractDates(String html) {
        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(html);
        while (matcher.find()) {
            dates.add(matcher.group(1));
        }
        return dates;
    }
    
    private List<String> extractPhones(String html) {
        List<String> phones = new ArrayList<>();
        Matcher matcher = PHONE_PATTERN.matcher(html);
        while (matcher.find()) {
            phones.add(matcher.group(1));
        }
        return phones;
    }
    
    private List<String> extractEmails(String html) {
        List<String> emails = new ArrayList<>();
        Matcher matcher = EMAIL_PATTERN.matcher(html);
        while (matcher.find()) {
            emails.add(matcher.group(1));
        }
        return emails;
    }
    
    private List<String> extractUrls(String html) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(html);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return urls;
    }
    
    private List<Map<String, Object>> sliceContent(String html) {
        List<Map<String, Object>> slices = new ArrayList<>();
        
        String text = removeHtmlTags(html);
        String[] paragraphs = text.split("\\n\\n+");
        
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.length() > 50) {
                Map<String, Object> slice = new HashMap<>();
                slice.put("index", i + 1);
                slice.put("content", paragraph);
                slice.put("length", paragraph.length());
                slice.put("isSummary", isSummarySection(paragraph));
                slices.add(slice);
            }
        }
        
        return slices;
    }
    
    private String removeHtmlTags(String html) {
        // 块级标签替换为换行，保留段落结构（否则整页折叠成一段，切片失效）
        String text = html.replaceAll("(?i)<(p|div|br|li|h[1-6]|tr|section|article|blockquote|table)[^>]*>", "\n");
        text = text.replaceAll("<[^>]+>", "");
        // 水平空白折叠为单个空格，但保留 \n
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        // 压缩多余空行
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }
    
    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
    
    private boolean isSummarySection(String text) {
        String lowerText = text.toLowerCase();
        return lowerText.contains("总结") || lowerText.contains("摘要") || 
               lowerText.contains("简介") || lowerText.contains("概述") ||
               lowerText.contains("summary") || lowerText.contains("abstract");
    }
    
    private String classifyContent(String text) {
        String lowerText = text.toLowerCase();
        
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
    
    private List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        Set<String> stopWords = new HashSet<>(Arrays.asList(
            "的", "是", "在", "有", "和", "了", "我", "你", "他", "她", "它",
            "这", "那", "这些", "那些", "什么", "怎么", "为什么", "因为", "所以",
            "但是", "如果", "可以", "可能", "应该", "需要", "会", "不会", "能"
        ));
        
        String cleanText = removeHtmlTags(text).toLowerCase();
        String[] words = cleanText.split("[\\s\\p{Punct}]+");
        
        Map<String, Integer> wordCount = new HashMap<>();
        for (String word : words) {
            if (word.length() >= 2 && !stopWords.contains(word)) {
                wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
            }
        }
        
        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(wordCount.entrySet());
        sortedEntries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        for (int i = 0; i < Math.min(10, sortedEntries.size()); i++) {
            keywords.add(sortedEntries.get(i).getKey());
        }
        
        return keywords;
    }
    
    private String generateSmartSummary(String content, String query) {
        if (content == null || content.isEmpty()) {
            return "暂无摘要";
        }
        
        String[] sentences = content.split("[。！？]");
        List<String> relevantSentences = new ArrayList<>();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() > 20) {
                boolean relevant = false;
                if (query != null && !query.isEmpty()) {
                    String[] queryWords = query.split("\\s+");
                    for (String word : queryWords) {
                        if (sentence.contains(word)) {
                            relevant = true;
                            break;
                        }
                    }
                } else {
                    relevant = true;
                }
                
                if (relevant) {
                    relevantSentences.add(sentence);
                }
            }
        }
        
        if (relevantSentences.isEmpty()) {
            relevantSentences.addAll(Arrays.asList(sentences).subList(0, Math.min(3, sentences.length)));
        }
        
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(3, relevantSentences.size()); i++) {
            if (i > 0) summary.append("。");
            summary.append(relevantSentences.get(i));
        }
        
        return summary.toString() + "。";
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        // PARAM-CLARITY(2026-11-02)：这张表以前还是旧契约（列着早已移除的 get_weather/location，
        // 没有 ask/read_url/scope/include_summary/分段参数），而它会被 AgentToolView 与
        // AIToolCreatorTool 当"工具说明"读出来 —— 与真正下发的 schema 两套说法就是误导。
        // 口径与 @Tool 注解、AIToolManager 的 ToolDefinition 保持一致。
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: ask(默认，直接给结论+来源，扣额度)/search(要一批来源/链接，扣额度)/"
                + "read_url(服务端读网页正文，扣额度)｜get_webpage(本地Jsoup抓网页)/extract_info(页面元数据)/"
                + "summarize(需 results 或 url)/get_dynamic_content/smart_read(需 results) 都是本地解析、不扣额度｜"
                + "search_and_read/smart_search 只在搜索那一步扣额度");
        descriptions.put("question", "要回答的完整问题（ask 用；不是关键词，也不是网址）");
        descriptions.put("query", "搜索关键词（search/search_and_read/smart_search 用；"
                + "网页地址请填 url，别塞进这里）");
        descriptions.put("keyword", "搜索关键词（query 的别名）");
        descriptions.put("model", "问答深度: concise(简洁)/detail(深入)/research(研究)，默认 concise（ask 用）");
        descriptions.put("limit", "返回条数 1..50，默认 5（按次计费，一次给够，别为多看几条反复搜；没有翻页）");
        descriptions.put("num_results", "返回结果数量（limit 的别名）");
        descriptions.put("scope", "搜索范围: webpage(默认)/document(文库)/scholar(学术文献)/image/video/podcast；"
                + "写 paper 按 scholar 处理");
        descriptions.put("include_summary", "是否返回内容摘要，默认 true（关掉只剩标题+链接，更容易编造）");
        descriptions.put("url", "完整网页地址（read_url/get_webpage/extract_info/get_dynamic_content 用）");
        descriptions.put("content", "网页正文（extract_info/get_dynamic_content 用，与 url 二选一）");
        descriptions.put("start_char", "read_url 正文起始字符偏移，默认 0（长文续读用）");
        descriptions.put("max_chars", "read_url 本次最大字符数，默认 0=整篇，长文建议 4000");
        descriptions.put("maxResults", "最大结果数（smart_search 用，默认 5）");
        descriptions.put("autoRead", "是否自动读取详情（smart_search 用，默认 true）");
        descriptions.put("results", "搜索结果列表（summarize/smart_read 必填）：上一步 search 返回的 results 数组、它的 JSON 字符串、或整段搜索返回都收");
        descriptions.put("detailIndex", "搜索结果索引（search_and_read 用，从 0 开始，指定要读的那条）");
        return descriptions;
    }
}