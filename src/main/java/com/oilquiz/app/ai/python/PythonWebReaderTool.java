package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Python 网页阅读与信息提取工具（预置脚本：requests + BeautifulSoup）。
 *
 * 与 Java 版 webpage_reader 互补：
 * - fetch：requests 抓取（GET/POST、自定义 headers/params/JSON body、超时）
 * - extract：bs4 提取标题/meta/标题结构/正文/链接/表格/JSON-LD
 * - fetch_json：请求 JSON API，返回结构化数据
 *
 * 适合登录态/cookie/自定义请求头/API 等 Java 工具不便处理的场景。
 */
@Tool(
    value = "python_web_reader",
    description = "Python网页工具(requests+bs4)：抓取网页/API并提取信息。fetch=GET/POST抓取(可带headers/params/JSON body)；extract=提取标题/正文/链接/表格/JSON-LD；fetch_json=请求JSON API。适合登录态/自定义请求头/API场景",
    category = "python",
    aliases = {"python网页", "py_web", "python_web", "网页抓取", "py_fetch"},
    actions = {
        @Action(name = "fetch", description = "抓取网页HTML/文本"),
        @Action(name = "extract", description = "提取网页关键信息(标题/正文/链接/表格/JSON-LD)"),
        @Action(name = "fetch_json", description = "请求JSON API返回结构化数据")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: fetch/extract/fetch_json", required = true),
        @Param(name = "url", type = "string", description = "目标URL", required = true),
        @Param(name = "method", type = "string", description = "HTTP方法(fetch/fetch_json用，GET/POST，默认GET)", required = false),
        @Param(name = "headers", type = "object", description = "自定义请求头JSON，如{\"Cookie\":\"...\",\"User-Agent\":\"...\"}", required = false),
        @Param(name = "params", type = "object", description = "URL查询参数JSON", required = false),
        @Param(name = "data", type = "object", description = "POST的JSON body(fetch/fetch_json用)", required = false),
        @Param(name = "content", type = "string", description = "已有HTML内容(extract用，与url二选一)", required = false),
        @Param(name = "timeout", type = "integer", description = "超时秒数(默认15)", required = false),
        @Param(name = "max_chars", type = "integer", description = "内容最大输出字符数(默认8000)", required = false)
    }
)
public class PythonWebReaderTool extends BaseAITool {
    private static final String TAG = "PythonWebReaderTool";
    private final Context context;
    private final PythonToolManager toolManager;

    public PythonWebReaderTool(Context context) {
        super("python_web_reader", "Python网页工具(requests+bs4)：抓取网页/API并提取信息");
        this.context = context.getApplicationContext();
        this.toolManager = PythonToolManager.getInstance(context);
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    return AIToolResult.fail("Python 工具初始化失败，请检查 Chaquopy 配置");
                }
            }
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "fetch";
            String code;
            switch (action) {
                case "extract":
                    code = buildExtractCode(parameters);
                    break;
                case "fetch_json":
                    code = buildFetchCode(parameters, true);
                    break;
                case "fetch":
                default:
                    code = buildFetchCode(parameters, false);
                    break;
            }
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, null);
            return formatResult(result, action);
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage(), e);
            return AIToolResult.fail("Python网页工具失败: " + errText(e));
        }
    }

    /** 抓取脚本（fetch / fetch_json 共用，jsonOnly 控制输出形式） */
    private String buildFetchCode(Map<String, Object> parameters, boolean jsonOnly) {
        String url = strParam(parameters, "url", "");
        String method = strParam(parameters, "method", "GET");
        String headers = jsonParam(parameters, "headers");
        // 自动注入应用内已保存的 WebView 登录态 Cookie（未显式传 Cookie 时）
        // 安全：明文 HTTP 不自动携带登录态 Cookie（防中间人窃听请求头中的登录凭证）
        String cookie = "";
        if (!url.startsWith("http://") || !com.oilquiz.app.webview.AppCookieStore.getInstance().hasLogin(url)) {
            String ck = com.oilquiz.app.webview.AppCookieStore.getInstance().getCookieHeader(url);
            cookie = ck != null ? ck : "";
        }
        cookie = quoteString(cookie);
        String params = jsonParam(parameters, "params");
        String data = jsonParam(parameters, "data");
        int timeout = intParam(parameters, "timeout", 15);
        int maxChars = intParam(parameters, "max_chars", 8000);

        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import json\n" +
            "try:\n" +
            "    import requests\n" +
            "except Exception as e:\n" +
            "    print('依赖不可用: ' + str(e))\n" +
            "    raise SystemExit\n" +
            "\n" +
            "url = %s\n" +
            "method = %s\n" +
            "headers = %s or {}\n" +
            "if 'Cookie' not in headers:\n" +
            "    _stored_cookie = %s\n" +
            "    if _stored_cookie:\n" +
            "        headers['Cookie'] = _stored_cookie\n" +
            "params = %s or {}\n" +
            "data = %s\n" +
            "timeout = %d\n" +
            "max_chars = %d\n" +
            "json_only = %s\n" +
            "\n" +
            "try:\n" +
            "    if method.upper() == 'POST':\n" +
            "        if data is not None:\n" +
            "            resp = requests.post(url, headers=headers, params=params, json=data, timeout=timeout)\n" +
            "        else:\n" +
            "            resp = requests.post(url, headers=headers, params=params, timeout=timeout)\n" +
            "    else:\n" +
            "        resp = requests.get(url, headers=headers, params=params, timeout=timeout)\n" +
            "\n" +
            "    resp.encoding = resp.apparent_encoding or resp.encoding or 'utf-8'\n" +
            "    print('状态码: ' + str(resp.status_code))\n" +
            "    print('最终URL: ' + resp.url)\n" +
            "    print('编码: ' + resp.encoding)\n" +
            "    print('Content-Type: ' + resp.headers.get('Content-Type', ''))\n" +
            "\n" +
            "    if json_only:\n" +
            "        try:\n" +
            "            obj = resp.json()\n" +
            "            s = json.dumps(obj, ensure_ascii=False, indent=1)\n" +
            "            if len(s) > max_chars:\n" +
            "                s = s[:max_chars] + '\\n...(截断，原始%%d字符)' %% len(s)\n" +
            "            print('==JSON==')\n" +
            "            print(s)\n" +
            "        except Exception as e:\n" +
            "            print('JSON解析失败，输出原始文本: ' + str(e))\n" +
            "            text = resp.text\n" +
            "            if len(text) > max_chars:\n" +
            "                text = text[:max_chars] + '...(截断)'\n" +
            "            print(text)\n" +
            "    else:\n" +
            "        text = resp.text\n" +
            "        total = len(text)\n" +
            "        if len(text) > max_chars:\n" +
            "            text = text[:max_chars] + '\\n...(截断，原始%%d字符)' %% total\n" +
            "        print('==内容==')\n" +
            "        print(text)\n" +
            "except Exception as e:\n" +
            "    print('抓取失败: ' + str(e))\n",
            quoteString(url), quoteString(method), headers, cookie, params, data,
            timeout, maxChars, jsonOnly ? "True" : "False"
        );
    }

    /** 提取脚本（bs4） */
    private String buildExtractCode(Map<String, Object> parameters) {
        String url = strParam(parameters, "url", "");
        String content = strParam(parameters, "content", null);
        int maxChars = intParam(parameters, "max_chars", 8000);
        // 自动注入应用内已保存的 WebView 登录态 Cookie
        // 安全：明文 HTTP 不自动携带登录态 Cookie（防中间人窃听请求头中的登录凭证）
        String cookie = "";
        if (!url.startsWith("http://") || !com.oilquiz.app.webview.AppCookieStore.getInstance().hasLogin(url)) {
            String ck = com.oilquiz.app.webview.AppCookieStore.getInstance().getCookieHeader(url);
            cookie = ck != null ? ck : "";
        }
        cookie = quoteString(cookie);

        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import json\n" +
            "try:\n" +
            "    from bs4 import BeautifulSoup\n" +
            "except Exception as e:\n" +
            "    print('bs4不可用: ' + str(e))\n" +
            "    raise SystemExit\n" +
            "\n" +
            "url = %s\n" +
            "html = %s\n" +
            "max_chars = %d\n" +
            "\n" +
            "if not html:\n" +
            "    try:\n" +
            "        import requests\n" +
            "        _h = {}\n" +
            "        _stored_cookie = %s\n" +
            "        if _stored_cookie:\n" +
            "            _h['Cookie'] = _stored_cookie\n" +
            "        resp = requests.get(url, headers=_h, timeout=15)\n" +
            "        resp.encoding = resp.apparent_encoding or 'utf-8'\n" +
            "        html = resp.text\n" +
            "        print('状态码: ' + str(resp.status_code))\n" +
            "    except Exception as e:\n" +
            "        print('抓取失败: ' + str(e))\n" +
            "        raise SystemExit\n" +
            "\n" +
            "try:\n" +
            "    soup = BeautifulSoup(html, 'html.parser')\n" +
            "    for tag in soup(['script', 'style', 'noscript', 'iframe', 'nav', 'header', 'footer', 'aside', 'form']):\n" +
            "        tag.decompose()\n" +
            "\n" +
            "    title = soup.title.get_text(strip=True) if soup.title else ''\n" +
            "\n" +
            "    desc = ''\n" +
            "    m = soup.find('meta', attrs={'name': 'description'}) or soup.find('meta', attrs={'property': 'og:description'})\n" +
            "    if m and m.get('content'):\n" +
            "        desc = m['content']\n" +
            "\n" +
            "    headings = [h.name.upper() + ': ' + h.get_text(strip=True) for h in soup.find_all(['h1', 'h2', 'h3']) if h.get_text(strip=True)]\n" +
            "\n" +
            "    links = []\n" +
            "    for a in soup.find_all('a', href=True):\n" +
            "        href = a['href']\n" +
            "        if href.startswith(('javascript:', 'mailto:', 'tel:')) or href.startswith('#'):\n" +
            "            continue\n" +
            "        txt = a.get_text(strip=True)[:80]\n" +
            "        links.append({'url': href, 'text': txt})\n" +
            "        if len(links) >= 30:\n" +
            "            break\n" +
            "\n" +
            "    main = soup.find('article') or soup.find('main') or soup.select_one('[role=main]') or soup.body or soup\n" +
            "    text = main.get_text('\\n', strip=True) if main else ''\n" +
            "    if len(text) > max_chars:\n" +
            "        text = text[:max_chars] + '...(截断，原始%%d字符)' %% len(text)\n" +
            "\n" +
            "    tables = []\n" +
            "    for t in soup.find_all('table'):\n" +
            "        rows = []\n" +
            "        for tr in t.find_all('tr'):\n" +
            "            cells = [td.get_text(strip=True) for td in tr.find_all(['td', 'th'])]\n" +
            "            if cells:\n" +
            "                rows.append(cells)\n" +
            "        if rows:\n" +
            "            tables.append(rows)\n" +
            "        if len(tables) >= 5:\n" +
            "            break\n" +
            "\n" +
            "    json_ld = None\n" +
            "    for s in soup.find_all('script', attrs={'type': 'application/ld+json'}):\n" +
            "        try:\n" +
            "            raw = s.string or s.get_text()\n" +
            "            json_ld = json.loads(raw) if raw and raw.strip() else None\n" +
            "            break\n" +
            "        except Exception:\n" +
            "            pass\n" +
            "\n" +
            "    print('标题: ' + title)\n" +
            "    print('描述: ' + desc)\n" +
            "    print('URL: ' + url)\n" +
            "    if headings:\n" +
            "        print('标题结构: ' + ' | '.join(headings[:15]))\n" +
            "    print('==正文==')\n" +
            "    print(text)\n" +
            "    if links:\n" +
            "        print('==链接(前%%d)==' %% len(links))\n" +
            "        for l in links:\n" +
            "            print(l['text'] + ' -> ' + l['url'])\n" +
            "    if tables:\n" +
            "        print('==表格(前%%d个)==' %% len(tables))\n" +
            "        for ti, t in enumerate(tables):\n" +
            "            header = ' | '.join(str(c) for c in t[0]) if t else ''\n" +
            "            print('表格' + str(ti) + ': ' + header)\n" +
            "            for r in t[1:6]:\n" +
            "                print('  ' + ' | '.join(str(c) for c in r))\n" +
            "    if json_ld is not None:\n" +
            "        print('==JSON-LD==')\n" +
            "        print(json.dumps(json_ld, ensure_ascii=False)[:max_chars])\n" +
            "except Exception as e:\n" +
            "    print('提取失败: ' + str(e))\n",
            quoteString(url), quoteString(content), maxChars, cookie
        );
    }

    private AIToolResult formatResult(PythonToolManager.ExecutionResult result, String action) {
        Map<String, Object> info = new HashMap<>();
        info.put("action", action);
        info.put("attempts", result.attempts);
        if (result.success) {
            String output = result.stdout != null && !result.stdout.isEmpty()
                    ? result.stdout : (result.result != null ? result.result : "");
            info.put("stdout", output);
            return new AIToolResult(output, info, true);
        }
        String error = result.error != null ? result.error
                : (result.stderr != null && !result.stderr.isEmpty() ? result.stderr : "未知错误");
        info.put("error", error);
        return new AIToolResult("Python网页工具失败: " + error, info, false);
    }

    // ===== 参数工具 =====

    private String strParam(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 参数 JSON 对象 → Python 字面量（None / dict） */
    private String jsonParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) return "None";
        try {
            if (v instanceof String && ((String) v).trim().startsWith("{")) {
                new org.json.JSONObject((String) v); // 校验
                return ((String) v).trim();
            }
            org.json.JSONObject obj = new org.json.JSONObject();
            if (v instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    obj.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            } else {
                obj.put("value", String.valueOf(v));
            }
            return obj.toString();
        } catch (Exception e) {
            return "None";
        }
    }

    private String quoteString(String s) {
        if (s == null) return "None";
        return "\"" + s.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t") + "\"";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: fetch(抓取HTML/文本)/extract(提取信息)/fetch_json(请求JSON API)");
        params.put("url", "目标URL(必填)");
        params.put("method", "HTTP方法(GET/POST，默认GET)");
        params.put("headers", "自定义请求头JSON，如{\"Cookie\":\"...\",\"User-Agent\":\"...\"}");
        params.put("params", "URL查询参数JSON");
        params.put("data", "POST的JSON body");
        params.put("content", "已有HTML内容(extract用，与url二选一)");
        params.put("timeout", "超时秒数(默认15)");
        params.put("max_chars", "内容最大输出字符数(默认8000)");
        return params;
    }
}
