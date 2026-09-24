package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.python.PythonToolManager;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.StructuredParamTool;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.webview.AppCookieStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 抖音分享链接解析下载器（内置工具 v3.0：官方内核 + suxun 兜底双通道）。
 *
 * 主通道：本地官方解析内核（assets/dy_builtin/dy_official_core.py + dy_src 签名包，
 * 随 APK 内置，运行时解包到应用内部存储）——本地计算 a_bogus + x-secsdk-web-signature 签名，
 * 直连抖音官方 aweme/v1/web/aweme/detail 接口，拿无码率压缩播放地址，无限次、不依赖第三方解析站。
 * 辅通道：suxun 免费解析源（assets/douyin_downloader.py）——官方通道失败时自动回退。
 *
 * Cookie 链路：执行前从 AppCookieStore（WebView 捕获）取 douyin.com 登录态 Cookie，
 * 经内核 set_cookie_provider 注入（内核 MODE=always_fresh 每次调用都会吸收最新浏览器 Cookie）。
 *
 * 参数：url(必填)；what=all|video|bgm|image；save_dir；force=true 强制重解析；source=auto|official|suxun。
 */
@Tool(value = "douyin_downloader", category = "network")
public class DouyinDownloaderTool implements AITool, StructuredParamTool {

    private static final String TAG = "DouyinDownloaderTool";
    private static final String ASSET_OFFICIAL_DIR = "dy_builtin";
    private static final String ASSET_WRAPPER = "dy_official_wrapper.py";
    private static final String ASSET_SUXUN = "douyin_downloader.py";
    private static final String BUILTIN_DIR = "dy_builtin";
    private static final String DY_COOKIE_DOMAIN = "https://www.douyin.com";

    private final Context context;
    private final List<ParamDefinition> definitions;

    public DouyinDownloaderTool() {
        this(SmartQuizApplication.getAppContext());
    }

    public DouyinDownloaderTool(Context context) {
        this.context = context != null
                ? context.getApplicationContext()
                : SmartQuizApplication.getAppContext();
        this.definitions = buildDefinitions();
    }

    private static List<ParamDefinition> buildDefinitions() {
        List<ParamDefinition> defs = new ArrayList<>();
        defs.add(new ParamDefinition("url", "string",
                "抖音分享链接或整段分享文案（自动提取其中的 http 链接），也支持纯 aweme_id 数字", true));
        defs.add(new ParamDefinition("what", "string",
                "下载内容：all=视频+BGM+封面+图集+文案；video=视频+BGM；bgm=仅背景音乐；image=仅封面/图集", false,
                "all", java.util.Arrays.asList("all", "video", "bgm", "image")));
        defs.add(new ParamDefinition("save_dir", "string",
                "保存目录（工作区相对路径，默认 files）", false, "files", null));
        defs.add(new ParamDefinition("force", "string",
                "\"true\"=忽略本地缓存强制重新解析", false,
                "false", java.util.Arrays.asList("false", "true")));
        defs.add(new ParamDefinition("source", "string",
                "解析通道：auto=官方优先失败自动回退第三方源(默认)；official=仅官方内核；suxun=仅第三方源", false,
                "auto", java.util.Arrays.asList("auto", "official", "suxun")));
        return defs;
    }

    @Override
    public String getName() {
        return "douyin_downloader";
    }

    @Override
    public String getDescription() {
        return "抖音分享链接解析下载器 v3.2（内置工具：官方内核随 APK 内置 + 第三方源兜底，双通道自动切换）。"
                + "输入分享链接/整段分享文案/纯aweme_id，自动下载：①无水印视频(无码率压缩) ②背景音乐BGM ③封面图 ④图集多图 ⑤文案，"
                + "返回标题/作者/点赞/发布时间/文件大小。"
                + "主通道：官方内核（assets/dy_builtin 内置，本地算签名直连 aweme/v1/web/aweme/detail，无限次、稳定，"
                + "缺 UIFID 自动用内置 WebView 抓取，NEED_COOKIE 失效自动重试自愈）；"
                + "官方失败自动回退第三方解析源（每日限20次+24h缓存）。"
                + "参数：url=分享链接/文案/纯ID(必填)；what=all|video|bgm|image(默认all)；source=auto|official|suxun(默认auto=官方优先)；"
                + "quality=best|sd|hd；force=true强制重解析；save_dir=保存目录(默认files)。"
                + "参数：url(必填)=分享链接或文案或aweme_id；what=all|video|bgm|image；save_dir=保存子目录；"
                + "force=true强制重解析；source=auto|official|suxun(默认auto)。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("url", "抖音分享链接或整段分享文案（自动提取其中的 http 链接），也支持纯 aweme_id 数字；必填");
        params.put("what", "下载内容：all=视频+BGM+封面+图集+文案(默认)；video=视频+BGM；bgm=仅背景音乐；image=仅封面/图集");
        params.put("save_dir", "保存目录（工作区相对路径，默认 files）");
        params.put("force", "\"true\"=忽略本地缓存强制重新解析，默认 false");
        params.put("source", "解析通道：auto=官方优先失败自动回退第三方源(默认)；official=仅官方内核；suxun=仅第三方源");
        return params;
    }

    @Override
    public List<ParamDefinition> getParameterDefinitions() {
        return definitions;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (context == null) {
                return AIToolResult.fail("抖音下载工具未初始化", null);
            }
            String source = parameters.get("source") != null
                    ? String.valueOf(parameters.get("source")).trim().toLowerCase() : "auto";
            if (!"official".equals(source) && !"suxun".equals(source)) {
                source = "auto";
            }
            PythonToolManager toolManager = PythonToolManager.getInstance(context);
            if (!toolManager.isInitialized()) {
                toolManager.initialize();
            }

            // ---- 通道一：官方内核（official / auto）----
            AIToolResult officialFail = null;
            if (!"suxun".equals(source)) {
                AIToolResult official = tryOfficial(toolManager, parameters);
                if (official != null && official.isSuccess()) {
                    return official;
                }
                officialFail = official;
                // NEED_COOKIE 自愈：自动用内置 WebView 抓取正式 UIFID 后重试一次官方
                if (officialFail != null && officialFail.getAdditionalInfo() != null
                        && "NEED_COOKIE".equals(String.valueOf(
                        officialFail.getAdditionalInfo().get("officialErrorCode")))) {
                    boolean got = ensureFormalUifid(true);
                    AILogger.i(TAG, "UIFID 自愈抓取：" + (got ? "成功" : "未取到") + "，重试官方内核");
                    AIToolResult retry = tryOfficial(toolManager, parameters);
                    if (retry != null && retry.isSuccess()) {
                        return retry;
                    }
                    officialFail = retry != null ? retry : officialFail;
                }
                if ("official".equals(source)) {
                    return failWithFallbackNote(officialFail);
                }
            }

            // ---- 通道二：第三方源（suxun / auto 回退）----
            AIToolResult fallback = trySuxun(toolManager, parameters);
            if (officialFail != null && fallback != null && fallback.isSuccess()) {
                // 官方失败后回退成功：保留官方诊断供排查
                Map<String, Object> info = fallback.getAdditionalInfo();
                if (info != null) {
                    info.put("fallbackFrom", officialFail.getErrorMessage());
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "douyin_downloader failed: " + e.getMessage(), e);
            Map<String, Object> info = new HashMap<>();
            info.put("toolName", getName());
            info.put("error", e.getMessage());
            return AIToolResult.fail("抖音下载工具执行异常: " + e.getMessage(), info);
        }
    }

    // ==================== 官方内核通道 ====================

    /** 官方内核执行；返回 null 表示官方通道不可用（需回退），否则返回结果（成功或带诊断的失败） */
    private AIToolResult tryOfficial(PythonToolManager toolManager, Map<String, Object> parameters) {
        try {
            File builtin = ensureAssetsExtracted();
            if (builtin == null) {
                return null;
            }
            migrateExternalCookie();
            // 官方内核需要真实 UIFID：无正式 UIFID 时先用内置 WebView 自动抓取
            ensureFormalUifid(false);
            String wrapper = loadAssetText(ASSET_WRAPPER);
            if (wrapper == null || wrapper.trim().isEmpty()) {
                return null;
            }
            String cookie = getDouyinCookieHeader();
            String fullCode = buildOfficialCode(wrapper, builtin.getAbsolutePath(), cookie, parameters);
            PythonToolManager.ExecutionResult result = toolManager.executeCode(fullCode, null, 150);

            Map<String, Object> info = new HashMap<>();
            info.put("toolName", getName());
            info.put("isBuiltin", true);
            info.put("channel", "official");
            info.put("attempts", result.attempts);

            String stdout = result.stdout != null ? result.stdout.trim() : "";
            if (result.success && !stdout.isEmpty()) {
                Map<String, Object> parsed = tryParseJson(stdout);
                if (parsed != null) {
                    info.put("parsed", parsed);
                    boolean ok = "true".equals(String.valueOf(parsed.get("ok")));
                    if (ok) {
                        info.put("source", parsed.get("source"));
                        info.put("cache", parsed.get("cache"));
                        return AIToolResult.success(stdout, info);
                    }
                    // 官方明确失败（NEED_COOKIE/SIGN_FAIL/NOT_FOUND/网络）→ 带诊断信息回退
                    Object err = parsed.get("error");
                    Object diag = parsed.get("diag");
                    StringBuilder sb = new StringBuilder();
                    if (err instanceof Map) {
                        Map<?, ?> em = (Map<?, ?>) err;
                        sb.append("官方内核：").append(em.get("code")).append(" — ").append(em.get("msg"));
                        Object hint = em.get("hint");
                        if (hint != null) {
                            sb.append("。").append(hint);
                        }
                        Object errCode = em.get("code");
                        if (errCode != null) {
                            info.put("officialErrorCode", String.valueOf(errCode));
                        }
                    } else {
                        sb.append("官方内核：").append(err);
                    }
                    if (diag != null) {
                        info.put("diag", diag);
                    }
                    info.put("officialError", sb.toString());
                    AIToolResult failRes = AIToolResult.fail(sb.toString(), info);
                    return failRes;
                }
                return AIToolResult.success(stdout, info);
            }
            // Python 层失败（脚本异常）→ 回退
            info.put("pythonError", result.error);
            info.put("stderr", result.stderr);
            return AIToolResult.fail("官方内核脚本执行失败：" + result.error, info);
        } catch (Exception e) {
            Log.w(TAG, "official channel failed: " + e.getMessage());
            Map<String, Object> info = new HashMap<>();
            info.put("channel", "official");
            info.put("error", e.getMessage());
            return AIToolResult.fail("官方内核执行异常: " + e.getMessage(), info);
        }
    }

    // ==================== suxun 第三方通道 ====================

    private AIToolResult trySuxun(PythonToolManager toolManager, Map<String, Object> parameters) {
        try {
            String script = loadAssetText(ASSET_SUXUN);
            if (script == null || script.trim().isEmpty()) {
                return AIToolResult.fail("内置第三方解析脚本缺失：assets/douyin_downloader.py 未打包或为空", null);
            }
            String fullCode = buildFullCode(script, parameters);
            PythonToolManager.ExecutionResult result = toolManager.executeCode(fullCode, null, 120);

            Map<String, Object> info = new HashMap<>();
            info.put("toolName", getName());
            info.put("isBuiltin", true);
            info.put("channel", "suxun");
            info.put("attempts", result.attempts);

            String stdout = result.stdout != null ? result.stdout.trim() : "";
            if (result.success) {
                Map<String, Object> parsed = tryParseJson(stdout);
                if (parsed != null) {
                    info.put("parsed", parsed);
                    boolean ok = "true".equals(String.valueOf(parsed.get("ok")));
                    if (ok) {
                        info.put("source", parsed.get("source"));
                        info.put("files", parsed.get("files"));
                        return AIToolResult.success(stdout, info);
                    }
                    Object err = parsed.get("error");
                    Object tip = parsed.get("tip");
                    StringBuilder sb = new StringBuilder();
                    sb.append("下载未完成：").append(err == null ? "未知原因" : err);
                    if (tip != null) {
                        sb.append("。").append(tip);
                    }
                    return AIToolResult.fail(sb.toString(), info);
                }
                return AIToolResult.success(stdout, info);
            }
            StringBuilder errMsg = new StringBuilder("第三方解析脚本执行失败");
            if (result.error != null) {
                errMsg.append("：").append(result.error);
            }
            if (result.stderr != null && !result.stderr.isEmpty()) {
                errMsg.append("\n").append(result.stderr.trim());
            }
            info.put("error", result.error);
            info.put("stderr", result.stderr);
            return AIToolResult.fail(errMsg.toString(), info);
        } catch (Exception e) {
            Log.e(TAG, "suxun channel failed: " + e.getMessage(), e);
            Map<String, Object> info = new HashMap<>();
            info.put("channel", "suxun");
            info.put("error", e.getMessage());
            return AIToolResult.fail("第三方解析执行异常: " + e.getMessage(), info);
        }
    }

    private AIToolResult failWithFallbackNote(AIToolResult officialFail) {
        // official 通道已失败且用户指定仅官方：直接返回官方失败，附回退提示
        Map<String, Object> info = officialFail != null ? officialFail.getAdditionalInfo() : null;
        if (info == null) {
            info = new HashMap<>();
        }
        info.put("fallbackHint", "可在 source=auto 时自动回退第三方解析源");
        return AIToolResult.fail(officialFail != null ? officialFail.getErrorMessage() : "官方内核不可用", info);
    }

    /** 取抖音官方内核所需 Cookie：合并 www.douyin.com 与 m.douyin.com 两个域的已存值
     *  （www 含 web_sign_token，m 含 UIFID_TEMP —— 后者在官方内核里兜底为 UIFID） */
    private String getDouyinCookieHeader() {
        AppCookieStore store = AppCookieStore.getInstance();
        String www = store.getCookieHeader("https://www.douyin.com");
        String m = store.getCookieHeader("https://m.douyin.com");
        StringBuilder sb = new StringBuilder();
        if (www != null && !www.isEmpty()) {
            sb.append(www.trim());
        }
        if (m != null && !m.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(m.trim());
        }
        return sb.toString();
    }

    private volatile boolean uifidAutoAttempted = false;

    /**
     * 用内置 WebView 自动抓取正式 UIFID（官方 detail 接口的必需登录态，只有浏览器能拿到）。
     * - force=false：已有正式 UIFID 或本进程已尝试过则跳过（正常解析路径）
     * - force=true：强制重新抓取（官方报 NEED_COOKIE 后的自愈路径）
     * 抓取方式：隐藏 WebView 加载 https://www.douyin.com/ → 页面 JS 种下 HttpOnly UIFID →
     * 加载完成后延迟 4 秒捕获回 AppCookieStore（provider 即可读到）。
     */
    private boolean ensureFormalUifid(boolean force) {
        if (!force && uifidAutoAttempted) {
            return false;
        }
        try {
            AppCookieStore store = AppCookieStore.getInstance();
            if (!force) {
                String www = store.getCookieHeader("https://www.douyin.com");
                if (www != null && www.contains("UIFID=")) {
                    return true; // 已有正式 UIFID
                }
            }
            uifidAutoAttempted = true;
            final CountDownLatch latch = new CountDownLatch(1);
            final WebView[] holder = new WebView[1];
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    WebView wv = new WebView(context);
                    holder[0] = wv;
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setUserAgentString(com.oilquiz.app.webview.WebViewDefaults.CHROME_USER_AGENT);
                    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                    s.setAllowFileAccess(false);
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            // UIFID 常由页面 JS / 后续接口延迟种下：加载完成后等待 4 秒再捕获
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    AppCookieStore.getInstance().captureFromWebView("https://www.douyin.com");
                                    AILogger.i(TAG, "WebView 捕获抖音 Cookie 完成");
                                } catch (Throwable ignored) {
                                } finally {
                                    latch.countDown();
                                }
                            }, 4000);
                        }
                    });
                    wv.loadUrl("https://www.douyin.com/");
                    // 总超时兜底（15 秒），避免阻塞工具执行
                    new Thread(() -> {
                        try {
                            Thread.sleep(15000);
                        } catch (InterruptedException ignored) {
                        }
                        latch.countDown();
                    }).start();
                } catch (Throwable t) {
                    AILogger.w(TAG, "UIFID WebView 启动失败: " + t.getMessage());
                    latch.countDown();
                }
            });
            latch.await(17, TimeUnit.SECONDS);
            // 主线程销毁 WebView
            if (holder[0] != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        holder[0].stopLoading();
                        holder[0].loadUrl("about:blank");
                        holder[0].destroy();
                    } catch (Throwable ignored) {
                    }
                });
            }
            String www = store.getCookieHeader("https://www.douyin.com");
            return www != null && www.contains("UIFID=");
        } catch (Throwable t) {
            AILogger.w(TAG, "ensureFormalUifid failed: " + t.getMessage());
            return false;
        }
    }

    // ==================== assets 解包与脚本装配 ====================

    /** 把 assets/dy_builtin 解包到内部存储（幂等：core.py 已在且非空则跳过） */
    private File ensureAssetsExtracted() {
        try {
            File dest = new File(context.getFilesDir(), BUILTIN_DIR);
            File core = new File(dest, "dy_official_core.py");
            if (isExtractedComplete(core, dest)) {
                return dest;
            }
            // 重新解包（清旧防残留）
            if (dest.exists()) {
                deleteRecursively(dest);
            }
            dest.mkdirs();
            AssetManager am = context.getAssets();
            extractAssetDir(am, ASSET_OFFICIAL_DIR, dest);
            return isExtractedComplete(core, dest) ? dest : null;
        } catch (Exception e) {
            Log.w(TAG, "extract dy_builtin failed: " + e.getMessage());
            return null;
        }
    }

    /** 解包完整性校验：内核 + 签名包关键文件都到位才视为成功（防半解包幂等跳过） */
    private boolean isExtractedComplete(File core, File dest) {
        if (core == null || !core.exists() || core.isDirectory() || core.length() <= 1000) {
            return false;
        }
        String[] keys = {"dy_src/aBogus.py", "dy_src/websign.py", "dy_pkg/aBogus.py"};
        for (String k : keys) {
            File f = new File(dest, k);
            if (!f.exists() || f.isDirectory() || f.length() == 0) {
                return false;
            }
        }
        return true;
    }

    private void extractAssetDir(AssetManager am, String path, File dest) throws java.io.IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            // 空数组/null 可能是"文件"也可能是"空目录"（Android 各版本行为不一）：
            // 用 open 探测——能打开即文件（含 0 字节文件如 __init__.py），打开失败即空目录。
            // dest 此时就是完整目标路径（文件或空目录）。
            try (InputStream in = am.open(path)) {
                File parent = dest.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
            } catch (java.io.IOException e) {
                dest.mkdirs();
            }
            return;
        }
        // 有子项 = 目录：递归
        dest.mkdirs();
        for (String c : children) {
            extractAssetDir(am, path + "/" + c, new File(dest, c));
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        f.delete();
    }

    private String loadAssetText(String asset) {
        try (InputStream in = context.getAssets().open(asset)) {
            byte[] buf = new byte[in.available()];
            int off = 0;
            int n;
            while ((n = in.read(buf, off, buf.length - off)) != -1) {
                off += n;
                if (off == buf.length) {
                    byte[] more = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, more, 0, buf.length);
                    buf = more;
                }
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            AILogger.e(TAG, "load asset " + asset + " failed: " + e.getMessage(), e);
            return null;
        }
    }

    /** 装配官方内核执行代码：注入解包目录、私有 cookie 文件、WebView Cookie、script_args */
    private String buildOfficialCode(String wrapper, String filesDir, String cookie,
                                     Map<String, Object> parameters) {
        File cookieFile = new File(context.getFilesDir(), "dy_webview_cookie.txt");
        String code = wrapper
                .replace("__FILES_DIR__", filesDir)
                .replace("__COOKIE_FILE__", cookieFile.getAbsolutePath())
                .replace("__WEBVIEW_COOKIE__", cookie.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\r", "\\r").replace("\n", "\\n"));
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        sb.append("import sys\n");
        sb.append("sys.path.insert(0, '.')\n\n");
        sb.append("# 脚本参数\n");
        sb.append("script_args = ").append(mapToPythonDict(parameters)).append("\n\n");
        sb.append(code);
        return sb.toString();
    }

    /**
     * Cookie 安全加固：把旧版遗留的外部明文 cookie 文件迁移到应用私有目录，
     * 并尝试删除外部明文文件。迁移后内核只读写私有文件（wrapper 已重定向 K.COOKIE_FILE）。
     */
    private void migrateExternalCookie() {
        try {
            File priv = new File(context.getFilesDir(), "dy_webview_cookie.txt");
            File ext = new File("/storage/emulated/0/Download/OilQuiz/agent_workspace/files/dy_webview_cookie.txt");
            if (!ext.exists()) {
                return;
            }
            if (!priv.exists() || priv.length() == 0) {
                try (InputStream in = new java.io.FileInputStream(ext);
                     FileOutputStream fos = new FileOutputStream(priv)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "migrate cookie copy failed: " + e.getMessage());
                }
            }
            // 尝试删除外部明文文件（属主可能非本应用，删不掉不阻塞）
            try {
                ext.delete();
                Log.i(TAG, "external plaintext cookie file removed");
            } catch (Throwable t) {
                Log.w(TAG, "external cookie file not deletable (left as-is): " + t.getMessage());
            }
        } catch (Exception e) {
            Log.w(TAG, "migrate cookie failed: " + e.getMessage());
        }
    }

    /** 注入 script_args（与 Python 动态工具同一形态）后返回完整脚本 */
    private String buildFullCode(String originalCode, Map<String, Object> parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        sb.append("import sys\n");
        sb.append("sys.path.insert(0, '.')\n\n");
        sb.append("# 脚本参数\n");
        sb.append("script_args = ").append(mapToPythonDict(parameters)).append("\n\n");
        sb.append(originalCode);
        return sb.toString();
    }

    private String mapToPythonDict(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String key = entry.getKey();
            Object value = entry.getValue();
            sb.append("'").append(key.replace("'", "\\'")).append("': ");
            sb.append(pythonValue(value));
        }
        sb.append("}");
        return sb.toString();
    }

    private String pythonValue(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String) {
            return "'" + ((String) value).replace("'", "\\'") + "'";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "True" : "False";
        }
        if (value instanceof java.util.List) {
            return pythonList((java.util.List<?>) value);
        }
        if (value instanceof java.util.Map) {
            return mapToPythonDict((java.util.Map<String, Object>) value);
        }
        if (value instanceof org.json.JSONObject) {
            org.json.JSONObject jo = (org.json.JSONObject) value;
            Map<String, Object> m = new HashMap<>();
            java.util.Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                m.put(k, jo.opt(k));
            }
            return mapToPythonDict(m);
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray ja = (org.json.JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < ja.length(); i++) {
                list.add(ja.opt(i));
            }
            return pythonList(list);
        }
        return "'" + value.toString().replace("'", "\\'") + "'";
    }

    private String pythonList(java.util.List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(pythonValue(item));
        }
        sb.append("]");
        return sb.toString();
    }

    private Map<String, Object> tryParseJson(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject jo = new org.json.JSONObject(s);
            Map<String, Object> m = new HashMap<>();
            java.util.Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                m.put(k, jo.opt(k));
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
