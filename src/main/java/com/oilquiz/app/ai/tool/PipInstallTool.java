package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.python.PythonToolManager;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * pip 安装工具：手机端 Python（Chaquopy 内嵌 CPython）运行时安装【纯 Python 包】。
 *
 * 背景：Chaquopy 的 site-packages 在 APK 内（不可写）且无 pip/编译器，运行时无法装带 C 扩展的包。
 * 但【纯 Python 包】（wheel 名为 py3-none-any，如 pytz/tqdm/simplejson/python-docx 等）可运行时安装：
 * 从清华 PyPI 镜像下载 wheel → 解压到 filesDir/runtime_packages/ → 注入 sys.path（跨会话持久生效）。
 *
 * 限制与行为：
 * - 只装 py3-none-any wheel；含 C 扩展（其他 wheel tag）或 sdist 一律拒绝并提示"需编译期预打包"；
 * - 依赖递归解析（Requires-Dist），纯 Python 依赖自动装，C 依赖列入 skipped 返回；
 * - 包名/版本约束：package 支持 name、name==version、name>=version、name~=version；
 * - 来源仅清华镜像（国内快），下载超时可配；
 * - 安装目录 filesDir/runtime_packages/（App 私有目录，跨重启保留），安装后立即注入 sys.path。
 */
@Tool(value = "pip_install", category = "code")
public class PipInstallTool implements AITool {

    private static final String TAG = "PipInstallTool";
    private static final String MIRROR_DEFAULT = "https://mirrors.aliyun.com/pypi/simple";
    private static final String PREF = "pip_install_config";
    private static final String KEY_MIRROR = "pip_mirror";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) MobileQuizAgent/1.0";

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    private static final Pattern WHEEL_FILENAME = Pattern.compile(
            "^([A-Za-z0-9._-]+)-([0-9][A-Za-z0-9._-]*?)(?:-[0-9][A-Za-z0-9._-]*)?-py3-none-any\\.whl$");
    private static final Pattern REQUIRES_DIST = Pattern.compile(
            "^Requires-Dist:\\s*([A-Za-z0-9._-]+)\\s*(?:\\(([^)]*)\\))?\\s*(?:;[^\\n]*)?$", Pattern.MULTILINE);

    private final Context appContext;

    public PipInstallTool() {
        this.appContext = SmartQuizApplication.getAppContext();
    }

    public PipInstallTool(Context context) {
        this.appContext = context != null
                ? context.getApplicationContext()
                : SmartQuizApplication.getAppContext();
    }

    @Override
    public String getName() {
        return "pip_install";
    }

    @Override
    public String getDescription() {
        return "pip安装工具（手机端Python运行时安装纯Python包）："
                + "① 在线安装：从PyPI镜像下载wheel并安装到本地运行环境，安装后立即生效且跨重启保留；"
                + "② 本地安装：package 传本地 .whl 文件路径（如 /sdcard/.../xxx-py3-none-any.whl）直接从该文件安装；"
                + "③ 只下载：action=download 仅下载最新匹配的wheel到工作区 files/wheels/ 返回路径，之后可再用本地安装装它；"
                + "④ 换源：source 参数指定镜像源（aliyun(默认)/tuna/pypi/自定义URL）并持久化为默认，action=set_source 单独设置。"
                + "package=包名（支持 name、name==版本、name>=版本、name~=版本）或本地whl路径。"
                + "限制：只能安装纯Python包（wheel为py3-none-any，如 pytz/tqdm/simplejson/python-docx 等）；"
                + "带C扩展的包（numpy/scipy/lxml 等，wheel含cp310/abi3等平台tag）在Android上无法运行时编译"
                + "（设备无编译工具链、公共PyPI无Android ABI的wheel），会明确拒绝并提示编译期预打包。"
                + "递归处理纯Python依赖，C依赖列入skipped返回。python_execute 可直接 import 已安装包。本工具不依赖运行时 pip 模块(自研wheel下载器)，运行时pip不可用时仍可用；与 python_execute 的 action=pip_install(pip.main编程式，装到filesDir/python_user_packages)安装目录不同互不覆盖。禁止用 subprocess 或 python -m pip(Chaquopy无独立python可执行文件)。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("package", "要安装的包名或带版本约束（pytz / python-docx==1.1.2 / requests>=2.31），或本地wheel文件路径（/sdcard/.../xxx-py3-none-any.whl）");
        params.put("action", "操作: install(默认，安装) / download(仅下载wheel到files/wheels/返回路径) / set_source(设置默认镜像源)");
        params.put("source", "镜像源: aliyun(默认，阿里云) / tuna(清华) / pypi(官方) / 自定义URL(http/https开头)；设置后持久化为默认");
        params.put("timeout", "下载超时秒数（默认 60，最大 300）");
        return params;
    }

    /** 当前生效的镜像源（持久化配置，可被 source 参数/action=set_source 更新） */
    private String getMirror() {
        try {
            android.content.SharedPreferences sp =
                    appContext.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
            String saved = sp.getString(KEY_MIRROR, MIRROR_DEFAULT);
            return saved != null && !saved.trim().isEmpty() ? saved.trim() : MIRROR_DEFAULT;
        } catch (Throwable t) {
            return MIRROR_DEFAULT;
        }
    }

    /** 保存默认镜像源（校验 http/https 前缀） */
    private boolean saveMirror(String mirror) {
        String m = mirror.trim();
        if (!m.startsWith("http://") && !m.startsWith("https://")) return false;
        try {
            appContext.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                    .edit().putString(KEY_MIRROR, m).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析镜像源参数：tuna/aliyun/pypi/自定义URL，返回规范化URL；null=不修改 */
    private String resolveMirrorParam(String source) {
        if (source == null || source.trim().isEmpty()) return null;
        String s = source.trim().toLowerCase();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return source.trim();
        }
        switch (s) {
            case "tuna":
            case "tsinghua":
            case "清华":
                return "https://pypi.tuna.tsinghua.edu.cn/simple";
            case "aliyun":
            case "阿里":
            case "阿里云":
                return MIRROR_DEFAULT;
            case "pypi":
            case "official":
            case "官方":
                return "https://pypi.org/simple";
            default:
                return null; // 未知源名：不修改
        }
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Object pkgObj = parameters.get("package");
        if (pkgObj == null) pkgObj = parameters.get("name");
        if (pkgObj == null || String.valueOf(pkgObj).trim().isEmpty()) {
            return AIToolResult.fail("缺少参数: package（要安装的包名，如 pytz 或 python-docx==1.1.2，或本地 wheel 路径）");
        }
        String spec = String.valueOf(pkgObj).trim();
        String action = "install";
        Object actObj = parameters.get("action");
        if (actObj != null && !String.valueOf(actObj).trim().isEmpty()) {
            action = String.valueOf(actObj).trim().toLowerCase();
        }
        // 源参数：设置后持久化为默认（set_source 单独处理；install/download 时也可指定本次源）
        Object srcObj = parameters.get("source");
        String sourceParam = srcObj != null ? String.valueOf(srcObj).trim() : "";
        if (action.equals("set_source")) {
            if (sourceParam.isEmpty()) {
                // 未传 source 时返回当前源
                Map<String, Object> info = new HashMap<>();
                info.put("source", getMirror());
                return AIToolResult.success("当前 pip 镜像源: " + getMirror()
                        + "\n可用 source 参数切换: tuna(清华) / aliyun(阿里云) / pypi(官方) / 自定义URL", info);
            }
            String resolved = resolveMirrorParam(sourceParam);
            if (resolved == null) {
                return AIToolResult.fail("无法识别的源: " + sourceParam
                        + "（支持 tuna / aliyun / pypi / http(s)://自定义URL）");
            }
            boolean ok = saveMirror(resolved);
            Map<String, Object> info = new HashMap<>();
            info.put("source", resolved);
            info.put("saved", ok);
            return AIToolResult.success((ok ? "pip 镜像源已切换为: " : "源设置失败（未持久化）: ") + resolved
                    + "\n后续 pip 安装默认使用该源。", info);
        }
        if (!action.equals("install") && !action.equals("download")) {
            return AIToolResult.fail("未知 action: " + action + "（支持 install / download / set_source）");
        }
        if (sourceParam != null && !sourceParam.isEmpty()) {
            String resolved = resolveMirrorParam(sourceParam);
            if (resolved != null) {
                saveMirror(resolved); // 持久化为默认
            }
        }
        boolean downloadOnly = action.equals("download");
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        try {
            Object t = parameters.get("timeout");
            if (t != null) {
                double secs = Double.parseDouble(String.valueOf(t));
                timeout = (int) Math.min(MAX_TIMEOUT_SECONDS, Math.max(5, secs));
            }
        } catch (Exception ignored) {
        }

        try {
            // 本地 wheel 文件安装
            if (!downloadOnly && isLocalWheelPath(spec)) {
                return installLocalWheel(spec, timeout);
            }
            // 只下载
            if (downloadOnly) {
                return downloadWheelOnly(spec, timeout);
            }
            return installRecursive(spec, timeout);
        } catch (Exception e) {
            AILogger.e(TAG, "pip install failed: " + e.getMessage(), e);
            return AIToolResult.fail("pip 安装失败: " + e.getMessage());
        }
    }

    /** 是否为本地 wheel 文件路径（以 / 开头或含路径分隔符且以 .whl 结尾） */
    private static boolean isLocalWheelPath(String spec) {
        if (spec.startsWith("/")) return true;
        return spec.endsWith(".whl") && spec.contains("/");
    }

    /** 递归安装（含依赖），visited 防循环 */
    private AIToolResult installRecursive(String spec, int timeout) throws Exception {
        List<String> installed = new ArrayList<>();
        List<String> skippedC = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(spec);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;
            InstallOutcome out = installOne(current, timeout);
            if (out.installedName != null) {
                installed.add(out.installedName);
            }
            if (out.skippedReason != null) {
                skippedC.add(out.installedName + (out.skippedReason != null ? " (" + out.skippedReason + ")" : ""));
            }
            if (out.dependencies != null) {
                for (String dep : out.dependencies) {
                    if (!visited.contains(dep)) queue.add(dep);
                }
            }
        }

        // 安装后把 runtime_packages 注入 sys.path（Python 已初始化时立即生效）
        ensureRuntimePathInjected();

        Map<String, Object> info = new HashMap<>();
        info.put("installed", installed);
        info.put("skipped_c_extensions", skippedC);
        StringBuilder sb = new StringBuilder();
        sb.append("安装完成。已安装: ").append(installed.isEmpty() ? "无" : String.join(", ", installed));
        if (!skippedC.isEmpty()) {
            sb.append("\n需编译期预打包(C扩展): ").append(String.join("; ", skippedC));
            sb.append("\n说明: Android 运行时无法编译 C 扩展包，如确需使用请告知，可在下次打包时加入 Chaquopy 依赖清单。");
        }
        if (installed.isEmpty() && skippedC.isEmpty()) {
            sb.append("\n（所有包均已在环境中，无需重新安装）");
        }
        sb.append("\n安装位置: ").append(new File(appContext.getFilesDir(), "runtime_packages").getAbsolutePath());
        sb.append("\npython_execute 中可直接 import 使用。");
        return AIToolResult.success(sb.toString(), info);
    }

    /** 安装后把 runtime_packages 注入 sys.path（Python 已初始化时立即生效，未初始化则下次初始化自动注入） */
    private void ensureRuntimePathInjected() {
        try {
            PythonToolManager.getInstance(appContext).ensureRuntimePackagesInPath();
        } catch (Throwable t) {
            AILogger.w(TAG, "注入 sys.path 失败(下次Python初始化会自动注入): " + t.getMessage());
        }
    }

    /** 单个包安装结果 */
    private static class InstallOutcome {
        String installedName;   // 成功安装的规范名（含版本），失败为 null
        String skippedReason;   // 被拒绝原因（C 扩展等）
        List<String> dependencies; // 待递归处理的纯 Python 依赖（null=无）
    }

    /** 从本地 wheel 文件安装（package=本地 .whl 路径），依赖在线递归 */
    private AIToolResult installLocalWheel(String path, int timeout) throws Exception {
        File wheelFile = new File(path);
        if (!wheelFile.isFile()) {
            return AIToolResult.fail("本地 wheel 文件不存在: " + path);
        }
        String filename = wheelFile.getName();
        Matcher wm = WHEEL_FILENAME.matcher(filename);
        if (!wm.matches()) {
            return AIToolResult.fail("文件名不是纯 Python wheel（需 py3-none-any）: " + filename
                    + "\n带 C 扩展的 wheel（含 cp310/abi3 等平台 tag）无法在 Android 运行时安装，需编译期预打包。");
        }
        String normName = wm.group(1).toLowerCase();
        String version = wm.group(2);
        File runtimeDir = new File(appContext.getFilesDir(), "runtime_packages");
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
            return AIToolResult.fail("无法创建安装目录");
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(wheelFile.toPath());
        unzipWheel(bytes, runtimeDir);

        // 依赖递归（在线解析纯 Python 依赖）
        List<String> deps = readDependencies(runtimeDir, normName);
        List<String> installed = new ArrayList<>();
        installed.add(normName + "==" + version);
        List<String> skippedC = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(path);
        Deque<String> queue = new ArrayDeque<>(deps);
        while (!queue.isEmpty()) {
            String dep = queue.poll();
            if (!visited.add(dep)) continue;
            InstallOutcome out = installOne(dep, timeout);
            if (out.installedName != null) installed.add(out.installedName);
            if (out.skippedReason != null) skippedC.add(out.installedName + " (" + out.skippedReason + ")");
            if (out.dependencies != null) {
                for (String d : out.dependencies) {
                    if (!visited.contains(d)) queue.add(d);
                }
            }
        }
        ensureRuntimePathInjected();

        Map<String, Object> info = new HashMap<>();
        info.put("installed", installed);
        info.put("skipped_c_extensions", skippedC);
        StringBuilder sb = new StringBuilder();
        sb.append("本地安装完成。已安装: ").append(String.join(", ", installed));
        if (!skippedC.isEmpty()) {
            sb.append("\n需编译期预打包(C扩展依赖): ").append(String.join("; ", skippedC));
        }
        sb.append("\n安装位置: ").append(runtimeDir.getAbsolutePath());
        return AIToolResult.success(sb.toString(), info);
    }

    /** 仅下载 wheel（action=download）：解析镜像选择最新匹配的纯 Python wheel，保存到工作区 files/wheels/ */
    private AIToolResult downloadWheelOnly(String spec, int timeout) throws Exception {
        String[] nc = parseNameConstraint(spec);
        String name = nc[0];
        String constraint = nc[1];
        if (!NAME_PATTERN.matcher(name).matches()) {
            return AIToolResult.fail("非法包名: " + name);
        }
        String normName = name.replace('-', '_').toLowerCase();
        String[] chosen = resolveWheelFromMirror(normName, constraint, timeout);
        if (chosen == null) {
            return AIToolResult.fail("镜像中没有找到匹配的纯 Python wheel（py3-none-any）"
                    + (constraint != null ? "（约束 " + constraint + "）" : "")
                    + "——该包可能含 C 扩展或仅提供源码包，需编译期预打包");
        }
        byte[] wheelBytes = httpGetBytes(chosen[0], timeout);
        if (wheelBytes == null) {
            return AIToolResult.fail("wheel 下载失败: " + chosen[1]);
        }
        AgentWorkspace workspace = AgentWorkspace.getInstance(appContext);
        File wheelsDir = new File(workspace.getFilesDir(), "wheels");
        if (!wheelsDir.exists() && !wheelsDir.mkdirs()) {
            return AIToolResult.fail("无法创建工作区 wheels 目录");
        }
        File out = new File(wheelsDir, chosen[1]);
        java.nio.file.Files.write(out.toPath(), wheelBytes);

        Map<String, Object> info = new HashMap<>();
        info.put("path", out.getAbsolutePath());
        info.put("filename", chosen[1]);
        info.put("version", chosen[2]);
        info.put("relative", "files/wheels/" + chosen[1]);
        return AIToolResult.success(
                "wheel 已下载: " + out.getAbsolutePath()
                        + "\n可用 pip_install 传本地路径安装：package=" + out.getAbsolutePath(),
                info);
    }

    /** 解析包名与版本约束，返回 {name, constraint} */
    private static String[] parseNameConstraint(String spec) {
        String trimmed = spec.trim();
        String name = trimmed;
        String constraint = null;
        for (String op : new String[]{"==", ">=", "<=", "~=", "!=", ">", "<"}) {
            int idx = trimmed.indexOf(op);
            if (idx > 0) {
                constraint = op + trimmed.substring(idx + op.length()).trim();
                name = trimmed.substring(0, idx).trim();
                break;
            }
        }
        return new String[]{name, constraint};
    }

    /** 解析镜像并选择匹配约束的最新纯 Python wheel；返回 {downloadUrl, filename, version}，找不到返回 null */
    private String[] resolveWheelFromMirror(String normName, String constraint, int timeout) {
        String mirror = getMirror();
        String pageUrl = mirror.endsWith("/") ? mirror + normName + "/" : mirror + "/" + normName + "/";
        String page = httpGet(pageUrl, timeout);
        if (page == null) return null;
        List<String[]> wheels = new ArrayList<>(); // {href, filename, version}
        Matcher m = Pattern.compile("<a\\s+href=\"([^\"]+)\">([^<]+\\.whl)</a>", Pattern.CASE_INSENSITIVE).matcher(page);
        while (m.find()) {
            String filename = m.group(2).trim();
            Matcher wm = WHEEL_FILENAME.matcher(filename);
            if (wm.matches() && wm.group(1).toLowerCase().equals(normName)) {
                wheels.add(new String[]{m.group(1), filename, wm.group(2)});
            }
        }
        String[] chosen = null;
        for (String[] w : wheels) {
            if (constraint != null && !versionMatches(w[2], constraint)) continue;
            if (chosen == null || compareVersions(w[2], chosen[2]) > 0) chosen = w;
        }
        if (chosen == null) return null;
        String mirrorBase = getMirror();
        if (!mirrorBase.endsWith("/")) mirrorBase += "/";
        String downloadUrl = chosen[0].startsWith("http")
                ? chosen[0] : mirrorBase + chosen[0];
        return new String[]{downloadUrl, chosen[1], chosen[2]};
    }

    private InstallOutcome installOne(String spec, int timeout) throws Exception {
        String[] nc = parseNameConstraint(spec);
        String name = nc[0];
        String constraint = nc[1];
        if (!NAME_PATTERN.matcher(name).matches()) {
            InstallOutcome bad = new InstallOutcome();
            bad.installedName = name;
            bad.skippedReason = "非法包名";
            return bad;
        }
        String normName = name.replace('-', '_').toLowerCase();

        // 已安装检查（同名视为已装，如需升级请先删目录或改版本约束）
        File runtimeDir = new File(appContext.getFilesDir(), "runtime_packages");
        boolean already = runtimeDir.exists() && runtimeDir.list((d, f) -> {
            String lf = f.toLowerCase();
            return lf.startsWith(normName) && (lf.endsWith(".dist-info") || new File(d, f).isDirectory());
        }).length > 0;

        String[] chosen = resolveWheelFromMirror(normName, constraint, timeout);
        if (chosen == null) {
            InstallOutcome fail = new InstallOutcome();
            fail.installedName = name + (constraint != null ? constraint : "");
            fail.skippedReason = "镜像中没有可用的纯 Python wheel（py3-none-any）——可能含 C 扩展、仅提供源码包或镜像访问失败，需编译期预打包";
            return fail;
        }
        if (already) {
            InstallOutcome done = new InstallOutcome();
            done.installedName = name + "==" + chosen[2];
            done.skippedReason = "已安装（跳过）";
            return done;
        }

        // 下载 wheel
        byte[] wheelBytes = httpGetBytes(chosen[0], timeout);
        if (wheelBytes == null) {
            InstallOutcome fail = new InstallOutcome();
            fail.installedName = name;
            fail.skippedReason = "wheel 下载失败";
            return fail;
        }

        // 解压到 runtime_packages/
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
            InstallOutcome fail = new InstallOutcome();
            fail.installedName = name;
            fail.skippedReason = "无法创建安装目录";
            return fail;
        }
        unzipWheel(wheelBytes, runtimeDir);

        // 读取 METADATA 依赖
        InstallOutcome out = new InstallOutcome();
        out.installedName = name + "==" + chosen[2];
        out.dependencies = readDependencies(runtimeDir, normName);
        return out;
    }

    private List<String> readDependencies(File runtimeDir, String normName) {
        List<String> deps = new ArrayList<>();
        File[] distInfos = runtimeDir.listFiles((d, f) ->
                f.toLowerCase().startsWith(normName) && f.toLowerCase().endsWith(".dist-info"));
        if (distInfos == null) return deps;
        for (File di : distInfos) {
            File meta = new File(di, "METADATA");
            if (!meta.exists()) continue;
            try {
                String content = new String(java.nio.file.Files.readAllBytes(meta.toPath()), "UTF-8");
                Matcher m = REQUIRES_DIST.matcher(content);
                while (m.find()) {
                    String dep = m.group(1).trim();
                    if (dep.isEmpty()) continue;
                    deps.add(dep);
                }
            } catch (Exception ignored) {
            }
        }
        return deps;
    }

    private void unzipWheel(byte[] bytes, File destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                // 防路径穿越
                File target = new File(destDir, entryName);
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new SecurityException("wheel 含非法路径: " + entryName);
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private String httpGet(String url, int timeout) {
        try {
            byte[] bytes = httpGetBytes(url, timeout);
            return bytes != null ? new String(bytes, "UTF-8") : null;
        } catch (Exception e) {
            AILogger.w(TAG, "HTTP GET failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

    private byte[] httpGetBytes(String url, int timeout) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(timeout * 1000);
            conn.setReadTimeout(timeout * 1000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                AILogger.w(TAG, "HTTP " + code + " for " + url);
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "HTTP GET failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

    /** 简单版本比较（a>b → >0） */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long va = i < pa.length ? parseNum(pa[i]) : 0;
            long vb = i < pb.length ? parseNum(pb[i]) : 0;
            if (va != vb) return Long.compare(va, vb);
        }
        return 0;
    }

    private static long parseNum(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        return sb.length() == 0 ? 0 : Long.parseLong(sb.toString());
    }

    /** 版本约束匹配（支持 == >= <= ~= != > <，无约束=true） */
    private static boolean versionMatches(String version, String constraint) {
        if (constraint == null) return true;
        String op = constraint.replaceAll("[^<>=!~]+.*$", "");
        String target = constraint.substring(op.length()).trim();
        int cmp = compareVersions(version, target);
        switch (op) {
            case "==": return cmp == 0;
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            case "!=": return cmp != 0;
            case ">": return cmp > 0;
            case "<": return cmp < 0;
            case "~=":
                // ~=x.y → >=x.y 且 <x.(y+1)
                String[] parts = target.split("\\.");
                if (parts.length >= 2) {
                    String upper = parts[0] + "." + (parseNum(parts[1]) + 1);
                    return cmp >= 0 && compareVersions(version, upper) < 0;
                }
                return cmp >= 0;
            default:
                return true;
        }
    }
}
