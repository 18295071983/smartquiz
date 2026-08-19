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
    description = "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取",
    category = "search",
    aliases = {"search", "web_search", "bing_search"},
    actions = {
        @Action(name = "search", description = "执行网络搜索"),
        @Action(name = "ask", description = "秘塔智能问答（搜索增强生成，返回答案+引用来源）"),
        @Action(name = "read_url", description = "秘塔网页读取（服务端抓取，返回结构化markdown，能处理JS渲染页）"),
        @Action(name = "get_webpage", description = "获取网页内容（本地Jsoup抓取）"),
        @Action(name = "extract_info", description = "提取网页关键信息"),
        @Action(name = "summarize", description = "生成搜索结果摘要"),
        @Action(name = "search_and_read", description = "搜索并阅读详情"),
        @Action(name = "get_dynamic_content", description = "获取动态网页内容"),
        @Action(name = "smart_search", description = "智能搜索"),
        @Action(name = "smart_read", description = "智能阅读")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型: search/ask/read_url/get_webpage/extract_info/summarize/search_and_read/get_dynamic_content/smart_search/smart_read", required = false),
        @Param(name = "query", type = "string", description = "搜索查询（用于search、search_and_read、smart_search操作）", required = false),
        @Param(name = "keyword", type = "string", description = "搜索关键词（query的别名）", required = false),
        @Param(name = "question", type = "string", description = "问题（用于ask操作，秘塔智能问答）", required = false),
        @Param(name = "model", type = "string", description = "问答模型: concise(简洁)/detail(深入)/research(研究)，默认concise（用于ask操作）", required = false),
        @Param(name = "limit", type = "int", description = "结果数量限制(默认5)", required = false),
        @Param(name = "num_results", type = "int", description = "返回结果数量（limit的别名）", required = false),
        @Param(name = "url", type = "string", description = "网页URL（用于read_url、get_webpage、extract_info和get_dynamic_content操作）", required = false),
        @Param(name = "maxResults", type = "int", description = "最大结果数(默认5)", required = false),
        @Param(name = "autoRead", type = "boolean", description = "是否自动读取详情(默认true)", required = false)
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
        return "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取";
    }
    
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
                case "get_weather":
                    return getWeather(parameters);
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

    /** 类型安全的字符串参数提取（LLM可能传String/Number/Boolean） */
    private String getStringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        return String.valueOf(v);
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
    
    private AIToolResult search(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int limit = getIntParam(parameters, "limit", 5);

        if (query == null || query.trim().isEmpty()) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }

        if (limit <= 0) {
            limit = 5;
        }

        try {
            // 秘塔搜索：优先用用户配置的Key，未配置则用内置默认Key
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.METASO_SEARCH);
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = DEFAULT_METASO_API_KEY;
            }

            List<Map<String, String>> searchResults = metasoSearch(query, limit, apiKey);

            if (searchResults == null || searchResults.isEmpty()) {
                return new AIToolResult(
                    "未找到与 '" + query + "' 相关的搜索结果。",
                    parameters
                );
            }

            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("count", searchResults.size());
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

    private List<Map<String, String>> metasoSearch(String query, int count, String apiKey) throws Exception {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            // 构造秘塔搜索请求体（参数严格对照秘塔API文档）
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("q", query);
            bodyJson.put("scope", "webpage");
            bodyJson.put("includeSummary", false);
            bodyJson.put("includeRawContent", false);
            bodyJson.put("size", count); // 文档规定为 integer

            RequestBody body = RequestBody.create(bodyJson.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8"));

            // 使用 NetworkUtil 统一构造请求（带 User-Agent 等 header，与 AIWeatherManager 等保持一致）
            Request request = NetworkUtil.createApiRequestBuilder(METASO_SEARCH_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            AILogger.i(TAG, "Metaso Search: " + query);

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                int responseCode = response.code();
                AILogger.i(TAG, "Metaso Search Response Code: " + responseCode);

                String responseBody = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    results = parseMetasoSearchResponse(responseBody);
                    AILogger.i(TAG, "Parsed " + results.size() + " search results");
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

    private List<Map<String, String>> parseMetasoSearchResponse(String jsonResponse) {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            JSONObject responseJson = new JSONObject(jsonResponse);

            // 秘塔可能返回业务错误码 {code:5000,message:...}，code非0表示失败
            // 必须抛异常让上层感知真实错误（余额/限流），而不是吞掉当"未找到结果"
            if (responseJson.has("code") && responseJson.optInt("code", 0) != 0) {
                String msg = responseJson.optString("message", "未知错误");
                AILogger.w(TAG, "秘塔搜索返回业务错误: " + msg);
                throw new IllegalStateException("搜索服务错误(" + responseJson.optInt("code") + "): " + msg);
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
                resultItem.put("url", firstNonEmpty(item, "link", "url", "linkUrl", "href"));
                resultItem.put("snippet", firstNonEmpty(item, "snippet", "summary", "description", "content", "abstract"));
                resultItem.put("source", firstNonEmpty(item, "source", "siteName", "domain"));
                resultItem.put("date", firstNonEmpty(item, "date", "publishTime", "publishDate", "time"));

                if (!resultItem.get("title").isEmpty() && !resultItem.get("url").isEmpty()) {
                    results.add(resultItem);
                }
            }

        } catch (Exception e) {
            AILogger.e(TAG, "解析秘塔搜索响应失败: " + e.getMessage(), e);
        }

        return results;
    }

    /** 在JSON对象中查找搜索结果数组，兼容多种嵌套结构 */
    private JSONArray findResultsArray(JSONObject json) {
        String[] directKeys = {"webpages", "results", "data", "searchResults", "items", "list", "value"};
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
        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);
            String message = json.optString("message", "");
            if (code != -1) {
                return "秘塔搜索失败[" + code + "]: " + message;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 秘塔智能问答：基于搜索增强生成（RAG）回答问题，返回答案+引用来源 */
    private AIToolResult askMetaso(Map<String, Object> parameters) {
        String question = getStringParam(parameters, "question", null);
        if (question == null || question.trim().isEmpty()) {
            return new AIToolResult("Missing required parameter: question", parameters);
        }

        String model = getStringParam(parameters, "model", "concise");
        if (model.trim().isEmpty()) {
            model = "concise";
        }

        try {
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.METASO_SEARCH);
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = DEFAULT_METASO_API_KEY;
            }

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

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                AILogger.i(TAG, "Metaso Chat Response Code: " + response.code());

                if (response.isSuccessful()) {
                    Map<String, Object> result = parseMetasoChatResponse(responseBody);
                    if (result != null) {
                        result.put("question", question);
                        result.put("model", model);
                        result.put("engine", "Metaso");
                        return new AIToolResult(result, parameters);
                    } else {
                        return new AIToolResult("秘塔问答失败: " + responseBody, parameters);
                    }
                } else if (response.code() == 401) {
                    return new AIToolResult("秘塔API认证失败，请检查API密钥", parameters);
                } else {
                    return new AIToolResult("秘塔问答请求失败，HTTP码: " + response.code(), parameters);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Metaso Chat failed: " + e.getMessage(), e);
            return new AIToolResult("秘塔问答失败: " + e.getMessage(), parameters);
        }
    }

    /** 解析秘塔问答响应：{errCode:0, data:{text, references, balance}} */
    private Map<String, Object> parseMetasoChatResponse(String jsonResponse) {
        Map<String, Object> result = new HashMap<>();
        try {
            JSONObject json = new JSONObject(jsonResponse);
            int errCode = json.optInt("errCode", -1);
            if (errCode != 0) {
                AILogger.w(TAG, "秘塔问答返回错误: " + json.optString("errMsg", "未知错误"));
                return null;
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
        } catch (Exception e) {
            AILogger.e(TAG, "解析秘塔问答响应失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 秘塔网页读取：服务端抓取并解析网页，返回结构化markdown文本（质量优于本地Jsoup，能处理JS渲染页） */
    private AIToolResult readUrlMetaso(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        if (url == null || url.trim().isEmpty()) {
            return new AIToolResult("Missing required parameter: url", parameters);
        }

        try {
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.METASO_SEARCH);
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = DEFAULT_METASO_API_KEY;
            }

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("url", url);

            RequestBody body = RequestBody.create(bodyJson.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8"));

            Request request = NetworkUtil.createApiRequestBuilder(METASO_READER_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "text/plain")
                    .post(body)
                    .build();

            AILogger.i(TAG, "Metaso Reader: " + url);

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                AILogger.i(TAG, "Metaso Reader Response Code: " + response.code());

                if (response.isSuccessful()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("url", url);
                    result.put("content", responseBody);
                    result.put("engine", "Metaso");
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
            return new AIToolResult("Missing required parameter: url", parameters);
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
            return new AIToolResult("Missing required parameter: query", parameters);
        }

        if (limit <= 0) {
            limit = 3;
        }

        try {
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", limit);
            
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

            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("count", results.size());
            result.put("status", "success");
            result.put("results", results);
            result.put("note", "请使用 detailIndex 参数指定要查看的搜索结果索引（从0开始）");

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
            return new AIToolResult("Missing required parameter: url or content", parameters);
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
            return new AIToolResult("Missing required parameter: query", parameters);
        }

        if (maxResults <= 0) {
            maxResults = 5;
        }

        try {
            AILogger.i(TAG, "Starting smart search for: " + query);
            
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", maxResults);
            
            AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }

            Map<String, Object> searchData = (Map<String, Object>) searchResult.getResult();
            List<Map<String, String>> results = (List<Map<String, String>>) searchData.get("results");
            
            if (results == null || results.isEmpty()) {
                return new AIToolResult("未找到与 '" + query + "' 相关的搜索结果。", parameters);
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
        List<Map<String, Object>> results = null;
        try {
            Object raw = parameters.get("results");
            if (raw instanceof List) {
                results = (List<Map<String, Object>>) raw;
            }
        } catch (ClassCastException e) {
            AILogger.w(TAG, "results参数类型不匹配: " + e.getMessage());
        }

        if (results == null || results.isEmpty()) {
            return new AIToolResult("Missing required parameter: results", parameters);
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
    
    private AIToolResult getWeather(Map<String, Object> parameters) {
        return new AIToolResult(
            "天气查询功能请使用 get_weather 工具",
            parameters
        );
    }
    
    private AIToolResult extractInfo(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        String content = getStringParam(parameters, "content", null);

        if (url == null && content == null) {
            return new AIToolResult("Missing required parameter: url or content", parameters);
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
        List<Map<String, Object>> results = (List<Map<String, Object>>) parameters.get("results");
        String query = getStringParam(parameters, "query", null);

        if (results == null || results.isEmpty()) {
            return new AIToolResult("Missing required parameter: results", parameters);
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
                String title = (String) item.get("title");
                String snippet = (String) item.get("snippet");
                String url = (String) item.get("url");

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

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Summarize failed: " + e.getMessage(), e);
            return new AIToolResult("生成摘要失败: " + e.getMessage(), parameters);
        }
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
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: search, get_webpage, extract_info, summarize, search_and_read, get_dynamic_content, smart_search, smart_read, get_weather");
        descriptions.put("query", "搜索查询（用于search、search_and_read、smart_search和smart_read操作）");
        descriptions.put("limit", "搜索结果数量限制（用于search和search_and_read操作，默认5）");
        descriptions.put("maxResults", "最大结果数（用于smart_search操作，默认5）");
        descriptions.put("autoRead", "是否自动读取详情（用于smart_search操作，默认true）");
        descriptions.put("url", "网页URL（用于get_webpage、extract_info和get_dynamic_content操作）");
        descriptions.put("content", "网页内容（用于extract_info和get_dynamic_content操作，与url二选一）");
        descriptions.put("results", "搜索结果列表（用于summarize和smart_read操作）");
        descriptions.put("location", "位置（用于get_weather操作）");
        descriptions.put("detailIndex", "搜索结果索引（用于search_and_read操作，从0开始，指定要阅读的详情）");
        return descriptions;
    }
}