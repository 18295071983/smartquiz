package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.export.ApkPacker;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * APK 导出工具：把 Agent 生成的 HTML（内容 / 单文件 / 整目录）用应用内置壳打包为可安装 APK。
 *
 * 原理：复用"背题"壳机制（ApkPacker）——HTML → ZIP → AES 加密(dt.jet) → 替换进壳模板
 * base.apk → apksig 签名，全部在设备端完成，无需外部构建服务。
 *
 * 壳说明：
 * - 应用名固定为"背题"，图标固定，包名固定 com.cjhtmldemo.scedzxdz（与现有导出功能一致）
 * - 壳运行时会解压整个 ZIP 并经本地 HTTP 服务(localhost:8099)加载，
 *   因此相对路径资源、fetch、ES 模块均可用；入口必须为 index.html
 *
 * 参数（HTML 来源四选一）：
 * - url:      远程地址（http/https），直接导出为在线网页 APK（壳运行时加载该地址）
 * - html:      HTML 内容字符串（适合小页面；大页面建议先用 file_generator 写入工作区再传路径）
 * - html_file: 已生成的单个 HTML 文件路径（file_generator 返回的 filePath）
 * - html_dir:  含 index.html 的目录（支持多文件/子目录资源，推荐）
 * - apk_name:  输出 APK 文件名（不含扩展名，默认 exported_时间戳）
 * - app_name:  应用名（桌面显示名，≤22 字符，默认"背题"；resources.arsc 原位补丁实现）
 * - icon_path: 应用图标 PNG 路径（未传时自动用 html_dir/icon.png；默认壳自带图标）
 * 内置前端库：本地模式自动注入 html_dir/libs/（jquery/vue3/axios/dayjs/animate.css/normalize.css）。
 */
@Tool(
    value = "export_apk",
    category = "export",
    description = "APK 导出工具：把 Agent 生成的 HTML 打包成可安装的安卓 APK 应用（设备端完成，无需电脑）。壳内暴露 22 个原生桥方法（window.AndroidApp），详见 HTML_DESIGN_RULES.md 第四节。注意：带回调的桥（screenshot/requestPermission/openFilePicker/request）回调名参数必填，缺参会在页面报 Method not found。"
            + "生成网页/HTML 内容后，用 file_generator 写入 Agent 工作区得到文件路径，再调用本工具（推荐 html_dir 或 html_file），"
            + "即可导出可安装 APK。HTML 来源三选一：html(内容字符串)/html_file(单文件路径)/html_dir(含 index.html 的目录，"
            + "支持 css/js/图片等相对资源)。注意：导出 APK 的应用名固定为“背题”、图标固定（复用内置壳模板），"
            + "如需自定义应用名/图标请告知用户当前版本暂不支持；APK 生成后返回完整路径，可提示用户安装或分享。",
    params = {
        @Param(name = "url", type = "string", description = "远程 URL（http/https），直接导出为在线网页 APK；与 html/html_file/html_dir 四选一", required = false),
        @Param(name = "html", type = "string", description = "HTML 内容字符串（与 html_file/html_dir 三选一）", required = false),
        @Param(name = "html_file", type = "string", description = "单个 HTML 文件完整路径（file_generator 返回的 filePath）", required = false),
        @Param(name = "html_dir", type = "string", description = "含 index.html 的 HTML 目录完整路径（支持相对资源，推荐）", required = false),
        @Param(name = "apk_name", type = "string", description = "输出 APK 文件名（不含 .apk 后缀，默认 app_name，无则 exported_时间戳）", required = false),
        @Param(name = "app_name", type = "string", description = "应用名（桌面显示名，UTF-8 字节 ≤22 即中文约 7 字；默认“背题”；资源名补丁实现）", required = false),
        @Param(name = "icon_path", type = "string", description = "应用图标路径（PNG/JPEG 均可，JPEG 自动转 PNG；≥192×192 建议 512×512；未传自动用 html_dir 根目录 icon.png；默认壳自带图标）", required = false),
        @Param(name = "icon_emoji", type = "string", description = "用 emoji 生成应用图标（如 📚/🏆，无需图片文件；可配 icon_bg 背景色）", required = false),
        @Param(name = "icon_bg", type = "string", description = "icon_emoji 图标背景色（#RRGGBB，默认 #4338CA 渐变）", required = false)
    }
)
public class ExportApkTool implements AITool {

    private static final String TAG = "ExportApkTool";
    /** 与现有导出功能一致的完成广播 */
    private static final String ACTION_EXPORT_COMPLETE = "com.oilquiz.app.EXPORT_COMPLETE";

    private final Context context;

    public ExportApkTool(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    public ExportApkTool() {
        this(null);
    }

    @Override
    public String getName() {
        return "export_apk";
    }

    @Override
    public String getDescription() {
        return "APK 导出工具：把 Agent 生成的 HTML 打包成可安装的安卓 APK 应用（设备端完成，无需电脑）。"
                + "生成网页/HTML 内容后，用 file_generator 写入 Agent 工作区得到文件路径，再调用本工具（推荐 html_dir 或 html_file），"
                + "即可导出可安装 APK。HTML 来源四选一（**必须互斥，多传会报错**）：url(远程 http/https 地址，导出在线网页 APK)/"
                + "html(内容字符串)/html_file(单文件路径)/html_dir(含 index.html 的目录，支持 css/js/图片等相对资源)。"
                + "可自定义：app_name(应用名，**UTF-8 字节 ≤22，中文约 7 字**，默认“背题”)、icon_path(PNG/JPEG 均可，"
                + "JPEG 自动转 PNG；图标 ≥192×192，建议 512×512)、icon_emoji+icon_bg(emoji 自动生成图标)。"
                + "**每个导出包自动派生独立包名**（com.cjhtmldemo.<slug>），多个导出可同时安装共存，互不覆盖。"
                + "HTML 设计规则（遵循可最大化壳能力）：入口必须 index.html；资源全部相对路径；viewport 加 viewport-fit=cover；"
                + "内置库自动注入 html_dir/libs/（jquery/vue3/axios/dayjs/echarts/katex/marked/lodash/highlight/dompurify/animate/normalize，直接相对路径引用）；"
                + "远程 API 跨域受限时用壳原生桥 window.AndroidApp.request()（无 CORS）；壳注入 window.AndroidApp 提供 toast/vibrate/"
                + "shareText/shareFile/openBrowser/getDeviceInfo/getNetworkType/权限管理(requestPermission/checkPermission)/"
                + "剪贴板监听/通知栏/前台服务/深链/JS注入/离线缓存/"
                + "电池/存储/系统文件选择/截图等原生能力（详见项目 apk_shell/HTML_DESIGN_RULES.md）。"
                + "APK 生成后返回完整路径、包名与 SHA-256，可提示用户安装或分享。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("url", "远程 URL（http/https），导出在线网页 APK；与 html/html_file/html_dir 四选一（互斥）");
        params.put("html", "HTML 内容字符串（与 url/html_file/html_dir 四选一，互斥）");
        params.put("html_file", "单个 HTML 文件完整路径（file_generator 返回的 filePath）");
        params.put("html_dir", "含 index.html 的 HTML 目录完整路径（支持相对资源，推荐）");
        params.put("apk_name", "输出 APK 文件名（不含 .apk 后缀，默认 app_name，无则 exported_时间戳）");
        params.put("app_name", "应用名（桌面显示名，UTF-8 字节 ≤22，中文约 7 字；默认“背题”）");
        params.put("icon_path", "应用图标路径（PNG/JPEG 均可，JPEG 自动转 PNG；≥192×192 建议 512；未传自动用 html_dir/icon.png）");
        params.put("icon_emoji", "用 emoji 生成应用图标（如 📚/🏆，无需图片文件；可配 icon_bg 背景色）");
        params.put("icon_bg", "icon_emoji 图标背景色（#RRGGBB，默认 #4338CA 渐变）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        if (context == null) {
            return AIToolResult.fail("export_apk 缺少应用上下文，无法导出");
        }
        try {
            // 1. HTML 来源参数互斥校验：url / html / html_file / html_dir 四选一（R3）
            String[] srcKeys = {"url", "html", "html_file", "html_dir"};
            java.util.List<String> given = new java.util.ArrayList<>();
            for (String k : srcKeys) {
                Object v = parameters.get(k);
                if (v != null && String.valueOf(v).trim().length() > 0) given.add(k);
            }
            if (given.size() > 1) {
                return AIToolResult.fail("HTML 来源参数必须四选一，不能同时传多个：" + String.join(" / ", given)
                        + "。请只保留 url / html / html_file / html_dir 中的一个。");
            }

            // 2. 输出目录：Agent 工作区下 apk_export（公开工作区时用户可直接访问），失败回退私有目录
            File outDir = new File(AgentWorkspace.getInstance(context).getWorkspaceDir(), "apk_export");
            if (!outDir.exists() && !outDir.mkdirs()) {
                outDir = new File(context.getFilesDir(), "apk_export");
                if (!outDir.exists() && !outDir.mkdirs()) {
                    return AIToolResult.fail("无法创建 APK 输出目录");
                }
            }

            // 3. 输出文件名：apk_name 参数 > app_name > 时间戳
            String apkName = String.valueOf(parameters.get("apk_name") == null ? "" : parameters.get("apk_name"));
            if (apkName.trim().isEmpty()) {
                Object nameObj = parameters.get("app_name");
                if (nameObj != null && String.valueOf(nameObj).trim().length() > 0) {
                    apkName = String.valueOf(nameObj).trim();
                }
            }
            if (apkName.trim().isEmpty()) {
                apkName = "exported_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            }
            apkName = apkName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File outApk = new File(outDir, apkName + ".apk");

            // 4. 解析导出模式：url 优先 → 本地 HTML（内容/文件/目录）
            long start = System.currentTimeMillis();
            ApkPacker.AppMeta meta = buildAppMeta(parameters, htmlDirHint(parameters));
            // 独立包名（R0 核心修复）：按应用名+输出名派生 slug → 每个导出包可共存安装
            String seed = (meta != null && meta.label != null ? meta.label : "backti")
                    + "|" + apkName;
            String pkg = "com.cjhtmldemo." + ApkPacker.derivePackageSlug(seed);
            ApkPacker.AppMeta finalMeta = (meta != null)
                    ? ApkPacker.AppMeta.of(meta.label, meta.iconPng, pkg)
                    : ApkPacker.AppMeta.ofLabel("背题").withPackage(pkg);
            Object urlObj = parameters.get("url");
            String remoteUrl = urlObj == null ? "" : String.valueOf(urlObj).trim();
            if (!remoteUrl.isEmpty()) {
                if (!(remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://"))) {
                    return AIToolResult.fail("url 必须以 http:// 或 https:// 开头");
                }
                ApkPacker.buildApkFromUrl(context, remoteUrl, outApk, finalMeta);
            } else {
                File htmlSrc = resolveHtmlSource(parameters);
                if (htmlSrc == null) {
                    return AIToolResult.fail("请提供 HTML 来源参数之一：url(远程地址) / html(内容) / html_file(文件路径) / html_dir(目录，须含 index.html)");
                }
                // 目录 → 整目录打包；文件/内容 → 单文件打包
                if (htmlSrc.isDirectory()) {
                    ApkPacker.buildApkFromDir(context, htmlSrc, outApk, finalMeta);
                } else {
                    ApkPacker.buildApk(context, htmlSrc, outApk, finalMeta);
                }
            }
            long elapsed = System.currentTimeMillis() - start;

            // 5. 发送完成广播（复用现有导出链路，便于 UI 感知）
            try {
                Intent intent = new Intent(ACTION_EXPORT_COMPLETE);
                intent.putExtra("file_path", outApk.getAbsolutePath());
                intent.putExtra("export_type", "apk_agent");
                intent.putExtra("package_name", pkg);
                context.sendBroadcast(intent);
            } catch (Exception e) {
                Log.w(TAG, "broadcast failed: " + e.getMessage());
            }

            // 6. 返回结果（含关键元数据，R6：package/sha256 便于核对与自动化）
            double sizeMb = outApk.length() / 1048576.0;
            String sha256 = sha256Hex(outApk);
            Map<String, Object> info = new HashMap<>();
            info.put("apk_path", outApk.getAbsolutePath());
            info.put("apk_size_mb", String.format(Locale.US, "%.2f", sizeMb));
            info.put("apk_name", apkName);
            info.put("app_label", finalMeta.label != null ? finalMeta.label : "背题");
            info.put("package_name", pkg);
            info.put("version_name", "1.0");
            info.put("version_code", 1);
            info.put("sha256", sha256);
            info.put("elapsed_ms", elapsed);
            String result = "APK 导出成功！\n"
                    + "APK 路径: " + outApk.getAbsolutePath() + "\n"
                    + "APK 大小: " + String.format(Locale.US, "%.2f", sizeMb) + " MB\n"
                    + "应用名: " + (finalMeta.label != null ? finalMeta.label : "背题") + "\n"
                    + "包名: " + pkg + "\n"
                    + "版本: 1.0 (1)\n"
                    + "SHA-256: " + sha256 + "\n"
                    + "生成耗时: " + (elapsed / 1000.0) + " 秒\n"
                    + "说明: 每个导出包包名唯一，可与之前的导出**同时安装共存**（不再互相覆盖）；"
                    + "可直接安装到手机，或通过文件管理器分享给他人。";
            AILogger.i(TAG, "APK exported -> " + outApk.getAbsolutePath() + " size=" + outApk.length() + " pkg=" + pkg);
            return AIToolResult.success(result, info);
        } catch (Exception e) {
            Log.e(TAG, "APK export failed", e);
            return AIToolResult.fail("APK 导出失败: " + e.getMessage());
        }
    }

    /** 计算文件 SHA-256（小写 hex） */
    private static String sha256Hex(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /**
     * 组装导出元信息。优先级：
     * 应用名：参数 app_name > html_dir/app.json 的 name > 默认（背题）
     * 图标：参数 icon_path > 参数 icon_emoji（Canvas 生成）> html_dir/icon.png > app.json 的 icon > 壳默认
     */
    private ApkPacker.AppMeta buildAppMeta(Map<String, Object> parameters, File htmlDirHint) throws Exception {
        String appName = null;
        JSONObject appJson = readAppJson(htmlDirHint);
        Object nameObj = parameters.get("app_name");
        if (nameObj != null && String.valueOf(nameObj).trim().length() > 0) {
            appName = String.valueOf(nameObj).trim();
        } else if (appJson != null && appJson.optString("name", "").trim().length() > 0) {
            appName = appJson.getString("name").trim();
        }
        byte[] icon = null;
        File iconFile = null;
        Object iconObj = parameters.get("icon_path");
        if (iconObj != null && String.valueOf(iconObj).trim().length() > 0) {
            iconFile = new File(String.valueOf(iconObj));
            if (!iconFile.isFile()) {
                throw new IllegalStateException("图标文件不存在: " + iconFile.getAbsolutePath());
            }
        }
        if (iconFile == null) {
            Object emojiObj = parameters.get("icon_emoji");
            if (emojiObj != null && String.valueOf(emojiObj).trim().length() > 0) {
                String bg = String.valueOf(parameters.get("icon_bg") == null ? "" : parameters.get("icon_bg")).trim();
                icon = generateEmojiIcon(String.valueOf(emojiObj).trim(), bg);
                AILogger.i(TAG, "icon generated from emoji: " + emojiObj);
            }
        }
        if (iconFile == null && icon == null && htmlDirHint != null) {
            File auto = new File(htmlDirHint, "icon.png");
            if (auto.isFile()) iconFile = auto;
            else if (appJson != null && appJson.optString("icon", "").trim().length() > 0) {
                File cfgIcon = new File(htmlDirHint, appJson.getString("icon").trim());
                if (cfgIcon.isFile()) iconFile = cfgIcon;
            }
        }
        if (iconFile != null) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(iconFile)) {
                icon = new byte[(int) iconFile.length()];
                int off = 0, n;
                while (off < icon.length && (n = fis.read(icon, off, icon.length - off)) != -1) off += n;
            }
            // 魔数校验 + JPEG 自动转 PNG + 尺寸校验（R5：32×32 小图直接拒绝，<512 提示）
            if (icon.length >= 8 && (icon[0] & 0xFF) == 0x89 && icon[1] == 'P' && icon[2] == 'N' && icon[3] == 'G') {
                int[] wh = pngSize(icon);
                if (wh == null) throw new IllegalStateException("PNG 解析失败（无法读取尺寸）: " + iconFile.getAbsolutePath());
                checkIconSize(wh[0], wh[1]);
            } else if (icon.length >= 3 && (icon[0] & 0xFF) == 0xFF && (icon[1] & 0xFF) == 0xD8 && (icon[2] & 0xFF) == 0xFF) {
                // JPEG → 转 PNG（BitmapFactory 解码再压缩，无外部依赖）
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(icon, 0, icon.length);
                if (bmp == null) throw new IllegalStateException("JPEG 解码失败: " + iconFile.getAbsolutePath());
                checkIconSize(bmp.getWidth(), bmp.getHeight());
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                icon = bos.toByteArray();
                AILogger.i(TAG, "JPEG icon converted to PNG: " + iconFile.getAbsolutePath());
            } else {
                throw new IllegalStateException("图标必须是 PNG 或 JPEG 文件（其他格式不支持）: " + iconFile.getAbsolutePath());
            }
        }
        if (appName == null && icon == null) return null;
        return ApkPacker.AppMeta.of(appName, icon);
    }

    /** PNG 尺寸（IHDR 宽高，无需完整解码）；非法返回 null */
    private static int[] pngSize(byte[] png) {
        if (png.length < 24) return null;
        try {
            int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
            int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
            if (w <= 0 || h <= 0) return null;
            return new int[]{w, h};
        } catch (Exception e) {
            return null;
        }
    }

    /** 图标尺寸校验：<192 拒绝（装机糊）；192-511 提示偏小；≥512 通过 */
    private static void checkIconSize(int w, int h) throws IllegalStateException {
        int min = Math.min(w, h);
        if (min < 192) {
            throw new IllegalStateException("图标过小（" + w + "×" + h + "），至少需要 192×192，建议 512×512");
        }
        if (min < 512) {
            AILogger.w(TAG, "图标偏小（" + w + "×" + h + "），建议 512×512");
        }
    }

    /** 读取 html_dir 根目录 app.json（可配置 name/icon），不存在返回 null */
    private JSONObject readAppJson(File htmlDirHint) {
        if (htmlDirHint == null) return null;
        File f = new File(htmlDirHint, "app.json");
        if (!f.isFile()) return null;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            AILogger.w(TAG, "app.json 解析失败: " + e.getMessage());
            return null;
        }
    }

    /** 用 emoji 生成 512×512 应用图标 PNG（渐变背景 + 居中 emoji），无外部依赖 */
    private byte[] generateEmojiIcon(String emoji, String bgColor) throws Exception {
        int size = 512;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);

        int startColor = parseColor(bgColor, 0xFF4338CA);
        int endColor = darken(startColor);
        android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(
                0, 0, size, size, startColor, endColor, android.graphics.Shader.TileMode.CLAMP);
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setShader(gradient);
        float radius = size / 2f;
        canvas.drawRoundRect(0, 0, size, size, radius, radius, bgPaint);

        android.graphics.Paint textPaint = new android.graphics.Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setTextSize(size * 0.58f);
        android.graphics.Rect bounds = new android.graphics.Rect();
        textPaint.getTextBounds(emoji, 0, emoji.length(), bounds);
        float baseline = (size - bounds.height()) / 2f - bounds.top;
        canvas.drawText(emoji, size / 2f, baseline, textPaint);

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }

    private static int parseColor(String hex, int def) {
        try {
            if (hex != null && hex.startsWith("#") && hex.length() == 7) {
                return android.graphics.Color.parseColor(hex);
            }
        } catch (Exception ignored) { }
        return def;
    }

    private static int darken(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0, hsv[2] * 0.75f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** 若参数指定了 html_dir 则返回其 File（用于自动识别 icon.png / app.json），否则 null */
    private File htmlDirHint(Map<String, Object> parameters) {
        Object dirObj = parameters.get("html_dir");
        if (dirObj != null && String.valueOf(dirObj).trim().length() > 0) {
            File dir = new File(String.valueOf(dirObj));
            if (dir.isDirectory()) return dir;
        }
        return null;
    }

    /** 解析 HTML 来源：html(内容) / html_file(文件) / html_dir(目录)，返回 null 表示参数缺失 */
    private File resolveHtmlSource(Map<String, Object> parameters) throws Exception {
        Object htmlObj = parameters.get("html");
        if (htmlObj != null && String.valueOf(htmlObj).trim().length() > 0) {
            // UUID 临时目录：并发导出不互抢（R1：目录唯一 + 用完即弃）
            File tmp = new File(context.getCacheDir(), "export_apk_" + java.util.UUID.randomUUID().toString().substring(0, 8));
            File indexFile = new File(tmp, "index.html");
            if (!tmp.exists() && !tmp.mkdirs()) {
                throw new IllegalStateException("无法创建临时目录: " + tmp.getAbsolutePath());
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(indexFile);
            fos.write(String.valueOf(htmlObj).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
            return indexFile;
        }

        Object fileObj = parameters.get("html_file");
        if (fileObj == null) fileObj = parameters.get("html_path");
        if (fileObj != null && String.valueOf(fileObj).trim().length() > 0) {
            File f = new File(String.valueOf(fileObj));
            if (!f.exists()) throw new IllegalStateException("HTML 文件不存在: " + f.getAbsolutePath());
            return f;
        }

        Object dirObj = parameters.get("html_dir");
        if (dirObj != null && String.valueOf(dirObj).trim().length() > 0) {
            File dir = new File(String.valueOf(dirObj));
            if (!dir.isDirectory()) throw new IllegalStateException("HTML 目录不存在: " + dir.getAbsolutePath());
            if (!new File(dir, "index.html").exists()) {
                throw new IllegalStateException("HTML 目录缺少入口文件 index.html: " + dir.getAbsolutePath());
            }
            return dir;
        }

        return null;
    }
}
