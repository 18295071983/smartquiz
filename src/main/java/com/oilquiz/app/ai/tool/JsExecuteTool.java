package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * JS 代码执行工具：基于系统 WebView（V8/Chromium 内核）在后台执行 JavaScript 代码并返回输出。
 *
 * 背景：手机端 Agent 此前没有 JS 执行引擎（无 Node/Rhino/QuickJS），面对 JS 只能查代码、
 * 不能运行验证——本工具复用系统 WebView 补齐"运行 JS 看结果"能力，与 python_execute 平级。
 *
 * 安全沙箱：
 * - 页面为内存 HTML（about:blank 引导页），不加载任何远程资源；
 * - 禁止 file/content 访问、禁止弹窗与文件 URL 访问；
 * - 内存页 origin 为 null，fetch/XHR 跨域会被 CORS 拦截（不能访问本机文件/任意网络）；
 * - 网络能力走 Android 原生桥（window.__http.get/post，Promise 形态）：仅 http/https、6s 超时、响应 ≤512KB；
 * - 文件能力走 Android 原生桥（window.__fs.read/write/list/delete/exists）：仅限 Agent 工作区 files/ 内，
 *   相对路径拒 .. 穿越、绝对路径越界拒绝、文件 ≤512KB；
 * - 无 Java 互操作（非 Rhino/Nashorn）；每次执行新建 WebView，用完销毁；并发上限 4。
 *
 * 参数：
 * - code: 要执行的 JavaScript 代码（语句/表达式均可，支持 console.log 调试输出）
 * - timeout: 可选，执行超时秒数（1-30，默认 8）
 */
@Tool(value = "js_execute", category = "code")
public class JsExecuteTool implements AITool {

    private static final String TAG = "JsExecuteTool";
    private static final int DEFAULT_TIMEOUT_SECONDS = 8;
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final int MAX_CONCURRENCY = 4;
    private static final Semaphore CONCURRENCY = new Semaphore(MAX_CONCURRENCY);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context appContext;

    public JsExecuteTool() {
        this.appContext = SmartQuizApplication.getAppContext();
    }

    public JsExecuteTool(Context context) {
        this.appContext = context != null
                ? context.getApplicationContext()
                : SmartQuizApplication.getAppContext();
    }

    @Override
    public String getName() {
        return "js_execute";
    }

    @Override
    public String getDescription() {
        return "JS代码执行工具：在后台执行 JavaScript 代码并返回运行结果（弥补手机端无 Node 的缺口）。"
                + "code=要执行的JS代码（语句或表达式均可），执行后返回：成功与否、返回值、console.log 输出、错误信息。"
                + "支持标准 ES6 核心语法（箭头函数/模板字符串/解构/let-const/class/Promise/**async-await**）；"
                + "**支持 Promise/异步结果**（返回 Promise 的代码会等待其结果，受 timeout 限制）；"
                + "**支持受限网络**：window.__http.get(url)/__http.post(url, body) 返回 **Promise**（可 await），"
                + "解析后为 {ok,status,body} 对象（内部为原生同步调用，多次调用会串行累计耗时）；"
                + "仅 http/https、6s 超时、响应≤512KB——可调 API/拉数据；"
                + "**支持受限文件**：window.__fs.read/write/list/delete/exists 返回 **Promise**（可 await），解析后为对象"
                + "（仅限 Agent 工作区 files/ 目录内，相对路径防 .. 穿越，绝对路径越出工作区会拒绝，文件≤512KB）——可读写工作区文件；"
                + "执行引擎是系统 WebView 的 JS 引擎（evaluateJavascript），**无 Java 互操作**（Java 包/类不可用，非 Rhino/Nashorn）；"
                + "**ES 能力以实测为准**：现代机型（WebView Chromium 较新）实测支持 ES2020+（可选链?. / 空值合并?? / BigInt / "
                + ".at() / replaceAll / findLast / structuredClone 等）；老机型 WebView 版本较低时较新特性可能缺失，"
                + "跨机型稳妥写法仍建议 ES6 核心语法；可用 console.log/error/warn 打印调试；"
                + "适合：算法验证、数据转换/清洗、JSON 处理、正则测试、前端逻辑调试、URL 编解码、调用 HTTP API。"
                + "**顶层 return 已自动兼容**：检测到代码顶层有 return 会自动包成立即执行函数 IIFE 并返回其结果，"
                + "可直接写 return 值; 返回结果；也可用 console.log 输出。"
                + "限制：原生 fetch/XHR 跨域会被 CORS 拦截（网络请用 __http，文件请用 __fs），不能访问 DOM/页面渲染。"
                + "**并发上限：单批同时执行 ≤4 路**（超出会等待 2s 后明确报『JS 执行并发已满』，不会静默丢值）；"
                + "**单个脚本内部多次桥接调用（__http/__fs）请串行 await**，勿用 Promise.all 并发打桥（会累积阻塞与并发压力）；"
                + "排查\"无返回值\"时先确认未超并发，再用 console.log 逐点打桩定位卡点。"
                + "超时用 timeout 参数（秒，默认8，最大30），死循环代码会被超时终止。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "要执行的 JavaScript 代码（语句或表达式，可用 console.log 输出调试）");
        params.put("timeout", "执行超时秒数（1-30，默认 8）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Object codeObj = parameters.get("code");
        if (codeObj == null) codeObj = parameters.get("script");
        if (codeObj == null || String.valueOf(codeObj).trim().isEmpty()) {
            return AIToolResult.fail("缺少参数: code（要执行的 JavaScript 代码）");
        }
        String code = String.valueOf(codeObj);
        // 顶层 return 是 JS 语法错误（Illegal return statement）：检测到行首 return 且不在
        // 函数体内时，自动包进立即执行函数 IIFE 并显式捕获返回值（与动态工具路径一致）
        if (hasTopLevelReturn(code)) {
            code = "const __js_ret = (() => {\n" + code + "\n})();\n__js_ret;";
        }
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        try {
            Object t = parameters.get("timeout");
            if (t != null) {
                double secs = Double.parseDouble(String.valueOf(t));
                timeout = (int) Math.min(MAX_TIMEOUT_SECONDS, Math.max(1, secs));
            }
        } catch (Exception ignored) {
        }

        // 并发闸：最多 MAX_CONCURRENCY 路同时执行；先等待 2s 拿许可（排队场景友好），
        // 超时则明确报错——绝不静默丢弃（超限丢值曾表现为"执行成功但无返回值"，已修复）
        boolean acquired;
        try {
            acquired = CONCURRENCY.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return AIToolResult.fail("JS 执行被中断");
        }
        if (!acquired) {
            return AIToolResult.fail("JS 执行并发已满（同时最多 " + MAX_CONCURRENCY
                    + " 路），请降低并发或分批调用后重试——超限时结果会被明确拒绝，不会静默丢值");
        }
        try {
            return runJs(code, timeout);
        } finally {
            CONCURRENCY.release();
        }
    }

    /** 粗略检测脚本是否在顶层用了 return（行首 return 且不在函数体内）。
     *  启发式足够覆盖常见写法：脚本末尾 `return xxx;` / `return xxx`。 */
    private static boolean hasTopLevelReturn(String logic) {
        String[] lines = logic.split("\n");
        int braceDepth = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*")
                    || line.startsWith("*")) {
                continue;
            }
            braceDepth += countChar(line, '{');
            braceDepth -= countChar(line, '}');
            if (braceDepth == 0 && line.startsWith("return")) {
                return true;
            }
        }
        return false;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    private AIToolResult runJs(String code, int timeoutSeconds) {
        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final String[] rawResult = new String[1];
        final WebView[] holder = new WebView[1];
        final Throwable[] setupError = new Throwable[1];

        MAIN.post(() -> {
            try {
                WebView webView = new WebView(appContext);
                holder[0] = webView;
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                // 关闭 DOM 存储：null origin 页面的 localStorage 在不同 WebView 实例间可能共享，
                // 关闭可消除该泄漏面（引导页只用内存数组，不依赖 localStorage）
                s.setDomStorageEnabled(false);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setAllowFileAccessFromFileURLs(false);
                s.setAllowUniversalAccessFromFileURLs(false);
                s.setJavaScriptCanOpenWindowsAutomatically(false);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        loaded.countDown();
                    }
                });
                // Android 原生桥：JS 侧 AndroidBridge.done(结果JSON) 回传异步(Promise)结果；
                // httpGet/httpPost 提供受限网络（仅 http/https，超时/大小限制见 http()）
                webView.addJavascriptInterface(new JsBridge(rawResult, done), "AndroidBridge");
                webView.loadDataWithBaseURL(null, BOOTSTRAP_HTML, "text/html", "utf-8", null);
            } catch (Throwable t) {
                setupError[0] = t;
                loaded.countDown();
                done.countDown();
            }
        });

        try {
            if (!loaded.await(5, TimeUnit.SECONDS)) {
                return AIToolResult.fail("JS 运行环境初始化超时");
            }
            if (setupError[0] != null) {
                return AIToolResult.fail("JS 运行环境初始化失败: " + setupError[0].getMessage());
            }
            if (holder[0] == null) {
                return AIToolResult.fail("JS 运行环境不可用");
            }
            final WebView webView = holder[0];
            // 用户代码 JSON 转义后嵌入 window.__run("...")（__run 内用 eval 执行）
            final String quoted = JSONObject.quote(code);
            MAIN.post(() -> {
                try {
                    webView.evaluateJavascript("window.__run(" + quoted + ")", value -> {
                        // Promise 路径：__run 返回 __async_pending 标记，结果由 AndroidBridge.done
                        // 异步回传（rawResult 已被覆盖），这里不写不 countDown，避免覆盖真结果；
                        // 同步路径：正常写入并结束。
                        if (value != null && value.contains("__async_pending")) {
                            return;
                        }
                        rawResult[0] = value != null ? value : "null";
                        done.countDown();
                    });
                } catch (Throwable t) {
                    setupError[0] = t;
                    done.countDown();
                }
            });
            if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                return AIToolResult.fail("JS 执行超时（" + timeoutSeconds
                        + "s），代码可能死循环或耗时过长；可调大 timeout 后重试");
            }
            if (setupError[0] != null) {
                return AIToolResult.fail("JS 执行失败: " + setupError[0].getMessage());
            }

            String raw = rawResult[0];
            // 桥接结果完整性校验：rawResult 为空/非法（并发超限被丢弃、WebView 异常、桥回传丢失）时
            // 必须明确报错，绝不假装"执行成功"——否则表现为"跑完了但没返回值"
            if (raw == null || raw.trim().isEmpty()) {
                return AIToolResult.fail("JS 桥接结果丢失（可能是并发超限被丢弃或 WebView 异常），请降低并发后重试");
            }
            // evaluateJavascript 返回值是 JSON 编码字符串（页面 return 值再被编码一层）
            Object inner;
            try {
                inner = new JSONTokener(raw).nextValue();
            } catch (Exception pe) {
                return AIToolResult.fail("JS 桥接结果解析失败（" + pe.getMessage() + "），请降低并发后重试");
            }
            JSONObject result = new JSONObject(String.valueOf(inner));
            boolean ok = result.optBoolean("ok");
            StringBuilder sb = new StringBuilder(ok ? "执行成功" : "执行出错: " + result.optString("error", "未知错误"));
            Object value = result.opt("value");
            if (value != null && !JSONObject.NULL.equals(value)) {
                sb.append("\n返回值: ").append(value);
            }
            JSONArray logs = result.optJSONArray("logs");
            if (logs != null && logs.length() > 0) {
                sb.append("\nconsole 输出:");
                for (int i = 0; i < logs.length(); i++) {
                    sb.append("\n  ").append(logs.optString(i));
                }
            }
            Map<String, Object> info = new HashMap<>();
            info.put("ok", ok);
            info.put("value", value != null && !JSONObject.NULL.equals(value) ? value : "");
            info.put("error", result.optString("error", ""));
            return ok ? AIToolResult.success(sb.toString(), info)
                      : AIToolResult.fail(sb.toString(), info);
        } catch (Exception e) {
            AILogger.w(TAG, "JS execute error: " + e.getMessage());
            return AIToolResult.fail("JS 执行失败: " + e.getMessage());
        } finally {
            // 清理：主线程销毁 WebView
            if (holder[0] != null) {
                final WebView wv = holder[0];
                MAIN.post(() -> {
                    try {
                        wv.stopLoading();
                        wv.loadUrl("about:blank");
                        wv.destroy();
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
    }

    /** 引导页：注入 console 捕获 + eval 执行入口 + Promise 结果回传桥 + 受限网络桥（纯内存，无网络资源） */
    private static final String BOOTSTRAP_HTML =
            "<html><body><script>"
            + "window.__jslogs=[];"
            + "(function(){"
            + "var push=function(a){var p=[];for(var i=0;i<a.length;i++){var x=a[i];"
            + "p.push(typeof x==='object'?(x===null?'null':JSON.stringify(x)):String(x));}"
            + "window.__jslogs.push(p.join(' '));};"
            + "console.log=function(){push(arguments);};"
            + "console.error=function(){push(arguments);};"
            + "console.warn=function(){push(arguments);};"
            + "console.info=function(){push(arguments);};"
            + "window.__enc=function(v){return v===undefined?null:(typeof v==='object'?JSON.stringify(v):String(v));};"
            + "window.__errMsg=function(e){return (e&&e.message)?String(e.message):String(e);};"
            + "window.__run=function(c){"
            + "try{var r=eval(c);"
            + "if(r&&typeof r.then==='function'){"
            + "r.then(function(v){AndroidBridge.done(JSON.stringify({ok:true,value:window.__enc(v),logs:window.__jslogs}));})"
            + ".catch(function(e){AndroidBridge.done(JSON.stringify({ok:false,error:window.__errMsg(e),logs:window.__jslogs}));});"
            + "return JSON.stringify({ok:true,value:'__async_pending',logs:[]});}"
            + "return JSON.stringify({ok:true,value:window.__enc(r),logs:window.__jslogs});}"
            + "catch(e){return JSON.stringify({ok:false,error:window.__errMsg(e),logs:window.__jslogs});}};"
            + "window.__http={"
            + "get:function(url){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.httpGet(url)));});},"
            + "post:function(url,body){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.httpPost(url,body)));});}};"
            + "window.__fs={"
            + "read:function(p){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.fsRead(p)));});},"
            + "write:function(p,c){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.fsWrite(p,c)));});},"
            + "list:function(d){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.fsList(d)));});},"
            + "delete:function(p){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.fsDelete(p)));});},"
            + "exists:function(p){return new Promise(function(resolve){resolve(JSON.parse(AndroidBridge.fsExists(p)));});}};"
            + "})();"
            + "</script></body></html>";

    /** JS ↔ Android 桥：done 回传 Promise 结果；httpGet/httpPost 提供受限网络。
     *  方法在 WebView JS 线程同步调用，HttpURLConnection 有 10s 超时兜底，不阻塞 UI。 */
    private class JsBridge {

        private final String[] rawResult;
        private final CountDownLatch done;

        JsBridge(String[] rawResult, CountDownLatch done) {
            this.rawResult = rawResult;
            this.done = done;
        }

        @JavascriptInterface
        public void done(String resultJson) {
            try {
                if (resultJson != null && !resultJson.isEmpty()) {
                    rawResult[0] = resultJson;
                }
            } finally {
                done.countDown();
            }
        }

        @JavascriptInterface
        public String httpGet(String url) {
            return http(url, null, "GET");
        }

        @JavascriptInterface
        public String httpPost(String url, String body) {
            return http(url, body, "POST");
        }

        private String http(String url, String body, String method) {
            try {
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    return "{\"ok\":false,\"error\":\"仅支持 http/https URL\"}";
                }
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod(method);
                if ("POST".equals(method)) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    java.io.OutputStream os = conn.getOutputStream();
                    try {
                        os.write(body == null ? "{}".getBytes("UTF-8") : body.getBytes("UTF-8"));
                    } finally {
                        os.close();
                    }
                }
                int status = conn.getResponseCode();
                java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                boolean tooBig = false;
                if (is != null) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(is, "UTF-8"));
                    try {
                        char[] buf = new char[4096];
                        int n;
                        int total = 0;
                        while ((n = r.read(buf)) != -1) {
                            total += n;
                            if (total > 512 * 1024) {
                                tooBig = true;
                                break;
                            }
                            sb.append(buf, 0, n);
                        }
                    } finally {
                        r.close();
                    }
                }
                conn.disconnect();
                if (tooBig) {
                    return "{\"ok\":false,\"error\":\"响应超过512KB限制\"}";
                }
                return "{\"ok\":true,\"status\":" + status + ",\"body\":"
                        + org.json.JSONObject.quote(sb.toString()) + "}";
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(msg) + "}";
            }
        }

        // ==================== 受限文件桥（仅限 Agent 工作区 files/ 内） ====================

        /** 解析路径到工作区长期文件区 files/；相对路径（已拒 .. 穿越）解析到 files/ 下，
         *  绝对路径也强制校验必须在 files/ 目录内（防越界读写工作区外文件）。返回 null 表示非法。 */
        private java.io.File resolveFsFile(String path) {
            try {
                com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                        com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(appContext);
                java.io.File filesDir = ws.getFilesDir();
                java.io.File f;
                // JS 侧无参调用时 undefined 会以字符串 "undefined"/"null" 传入，一律视为默认根目录
                if (path != null && !path.isEmpty() && !"undefined".equals(path) && !"null".equals(path)) {
                    java.io.File pf = new java.io.File(path);
                    if (pf.isAbsolute()) {
                        f = pf;
                    } else {
                        f = ws.resolveFileToFiles(path);
                        if (f == null) return null;
                    }
                } else {
                    f = filesDir;
                }
                String base = filesDir.getCanonicalPath();
                String target = f.getCanonicalPath();
                if (!target.equals(base) && !target.startsWith(base + java.io.File.separator)) {
                    return null;
                }
                return f;
            } catch (Throwable t) {
                return null;
            }
        }

        @JavascriptInterface
        public String fsRead(String path) {
            java.io.File f = resolveFsFile(path);
            if (f == null) {
                return "{\"ok\":false,\"error\":\"路径非法或越出工作区\"}";
            }
            try {
                if (!f.isFile()) {
                    return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote("文件不存在: " + path) + "}";
                }
                if (f.length() > 512 * 1024) {
                    return "{\"ok\":false,\"error\":\"文件超过512KB限制\"}";
                }
                java.io.FileReader r = new java.io.FileReader(f);
                StringBuilder sb = new StringBuilder();
                try {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = r.read(buf)) != -1) {
                        sb.append(buf, 0, n);
                    }
                } finally {
                    r.close();
                }
                return "{\"ok\":true,\"content\":" + org.json.JSONObject.quote(sb.toString()) + "}";
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(msg) + "}";
            }
        }

        @JavascriptInterface
        public String fsWrite(String path, String content) {
            java.io.File f = resolveFsFile(path);
            if (f == null) {
                return "{\"ok\":false,\"error\":\"路径非法或越出工作区\"}";
            }
            try {
                java.io.File parent = f.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                java.io.FileWriter w = new java.io.FileWriter(f);
                try {
                    w.write(content == null ? "" : content);
                } finally {
                    w.close();
                }
                return "{\"ok\":true}";
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(msg) + "}";
            }
        }

        @JavascriptInterface
        public String fsList(String dir) {
            java.io.File d = resolveFsFile(dir == null || dir.isEmpty() ? "" : dir);
            if (d == null) {
                return "{\"ok\":false,\"error\":\"路径非法或越出工作区\"}";
            }
            try {
                java.io.File[] children = d.listFiles();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"path\":").append(org.json.JSONObject.quote(d.getAbsolutePath()))
                        .append(",\"files\":[");
                if (children != null) {
                    boolean first = true;
                    for (java.io.File c : children) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"name\":").append(org.json.JSONObject.quote(c.getName()))
                                .append(",\"isDir\":").append(c.isDirectory())
                                .append(",\"size\":").append(c.isDirectory() ? 0 : c.length()).append('}');
                    }
                }
                sb.append("]}");
                return sb.toString();
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(msg) + "}";
            }
        }

        @JavascriptInterface
        public String fsDelete(String path) {
            java.io.File f = resolveFsFile(path);
            if (f == null) {
                return "{\"ok\":false,\"error\":\"路径非法或越出工作区\"}";
            }
            try {
                if (!f.exists()) {
                    return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote("不存在: " + path) + "}";
                }
                if (f.isDirectory()) {
                    java.io.File[] children = f.listFiles();
                    if (children != null && children.length > 0) {
                        return "{\"ok\":false,\"error\":\"目录非空，请先删除子项\"}";
                    }
                }
                boolean del = f.delete();
                return del ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"删除失败\"}";
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                return "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(msg) + "}";
            }
        }

        @JavascriptInterface
        public String fsExists(String path) {
            java.io.File f = resolveFsFile(path);
            if (f == null) {
                return "{\"ok\":false,\"error\":\"路径非法或越出工作区\"}";
            }
            return "{\"ok\":true,\"exists\":" + f.exists() + ",\"isDir\":" + f.isDirectory() + "}";
        }
    }
}
