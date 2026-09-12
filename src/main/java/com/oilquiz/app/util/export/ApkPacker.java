package com.oilquiz.app.util.export;

import android.content.Context;
import android.util.Log;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * APK 打包器（复用"背题"壳机制）
 *
 * 流程：index.html → ZIP(入口 index.html) → AES-128-CBC 加密(dt.jet)
 *      → 替换进内置壳模板(assets/apk_shell/base.apk) → apksig 签名 → 可安装 APK
 *
 * 加密与壳一致：key="MyHtmlEditorKey1"(UTF-8,16字节)，IV=随机16字节放文件头，
 * 解密后为 ZIP，入口按 assets/manifest.json 的 main 字段（index.html）。
 *
 * 签名：assets/apk_shell/export.keystore（PKCS12, alias=smartquiz, password=password）
 * 注意：该 keystore 仅用于导出 APK 签名，与 App 主签名一致。
 */
public final class ApkPacker {
    private static final String TAG = "ApkPacker";

    /** 与"背题"壳一致的 AES 密钥 */
    private static final String AES_KEY_STR = "MyHtmlEditorKey1";
    private static final String SHELL_TEMPLATE = "apk_shell/base.apk";
    private static final String SHELL_KEYSTORE = "apk_shell/export.keystore";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final String KEY_ALIAS = "smartquiz";
    private static final String ENTRY_MAIN = "index.html";

    /** 入口文件名（manifest.json 的 main 字段），与 ZIP 内 HTML 名一致 */
    private static final String MANIFEST_JSON = "{\"main\":\"" + ENTRY_MAIN + "\",\"targver\":1}";
    /** 壳模板元数据（gen_meta.py 写入）：图标条目 + 应用名占位 */
    private static final String SHELL_META_ASSET = "assets/apk_shell_meta.json";
    /** 默认应用名（无自定义时与原壳行为一致） */
    private static final String DEFAULT_LABEL = "背题";

    private ApkPacker() {}

    /** 导出元信息：自定义应用名、图标与包名（均为可选项） */
    public static final class AppMeta {
        public final String label;
        public final byte[] iconPng;
        /** 独立包名（可选）：形如 com.cjhtmldemo.<slug>，与模板占位等长 23 字符；缺省用模板默认包名 */
        public final String packageName;
        public AppMeta(String label, byte[] iconPng, String packageName) {
            this.label = label;
            this.iconPng = iconPng;
            this.packageName = packageName;
        }
        public static AppMeta of(String label, byte[] iconPng) {
            return new AppMeta(label, iconPng, null);
        }
        public static AppMeta of(String label, byte[] iconPng, String packageName) {
            return new AppMeta(label, iconPng, packageName);
        }
        public static AppMeta ofLabel(String label) {
            return new AppMeta(label, null, null);
        }
        public static AppMeta ofIcon(byte[] iconPng) {
            return new AppMeta(null, iconPng, null);
        }
        /** 返回带指定包名的新元信息（其余字段不变） */
        public AppMeta withPackage(String packageName) {
            return new AppMeta(label, iconPng, packageName);
        }
        /** 默认：应用名"背题"，图标用壳模板自带 */
        public static AppMeta defaultMeta() {
            return new AppMeta(DEFAULT_LABEL, null, null);
        }
    }

    /**
     * 按种子派生独立包名 slug：p + sha1hex(seed) 前 12 位（13 字符），
     * 拼到固定前缀后与模板占位 com.cjhtmldemo.xxxxxxxxxxx 等长（23 字符）。
     * 同一 seed 恒等 → 同参数重复导出仍走"升级覆盖"语义；不同 seed 互不覆盖，可共存。
     */
    public static String derivePackageSlug(String seed) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("p");
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i]));
            }
            return sb.toString(); // 13 字符
        } catch (Exception e) {
            throw new IllegalStateException("包名 slug 生成失败", e);
        }
    }

    /**
     * 由 HTML 构建签名 APK（使用壳模板默认应用名与图标）。
     *
     * @param context   应用上下文
     * @param indexHtml 生成的入口 HTML 文件
     * @param outApk    输出 APK 文件
     * @return 已签名 APK 文件
     */
    public static File buildApk(Context context, File indexHtml, File outApk) throws Exception {
        return buildApk(context, indexHtml, outApk, AppMeta.defaultMeta());
    }

    /** 由 HTML 构建签名 APK，支持自定义应用名/图标 */
    public static File buildApk(Context context, File indexHtml, File outApk, AppMeta meta) throws Exception {
        byte[] html = readAll(indexHtml);
        Log.i(TAG, "HTML size: " + html.length);

        // 1. HTML → ZIP
        byte[] zip = htmlToZip(html, ENTRY_MAIN);
        return buildApkFromZip(context, zip, MANIFEST_JSON.getBytes(StandardCharsets.UTF_8), meta, outApk);
    }

    /**
     * 由 HTML 目录构建签名 APK（整目录打包，支持相对路径资源：css/js/图片/子目录）。
     *
     * 目录中必须存在 index.html（壳 manifest.json 的 main 字段）；壳运行时会解压整个
     * ZIP 并通过本地 HTTP 服务加载，因此相对路径、fetch、ES 模块等均可用。
     *
     * @param context 应用上下文
     * @param htmlDir 含 index.html 的 HTML 目录
     * @param outApk  输出 APK 文件
     * @return 已签名 APK 文件
     */
    public static File buildApkFromDir(Context context, File htmlDir, File outApk) throws Exception {
        return buildApkFromDir(context, htmlDir, outApk, AppMeta.defaultMeta());
    }

    /** 由 HTML 目录构建签名 APK，支持自定义应用名/图标（html_dir/icon.png 可作默认图标） */
    public static File buildApkFromDir(Context context, File htmlDir, File outApk, AppMeta meta) throws Exception {
        if (htmlDir == null || !htmlDir.isDirectory()) {
            throw new IllegalArgumentException("HTML 目录无效: " + htmlDir);
        }
        if (!new File(htmlDir, ENTRY_MAIN).exists()) {
            throw new IllegalArgumentException("HTML 目录缺少入口文件: " + ENTRY_MAIN);
        }
        byte[] zip = dirToZip(htmlDir);
        Log.i(TAG, "HTML dir: " + htmlDir.getAbsolutePath() + ", ZIP size: " + zip.length);
        return buildApkFromZip(context, zip, MANIFEST_JSON.getBytes(StandardCharsets.UTF_8), meta, outApk);
    }

    /** ZIP → dt.jet（AES 加密）→ 替换进壳模板 → apksig 签名（buildApk 共用链路） */
    private static File buildApkFromZip(Context context, byte[] zip, File outApk) throws Exception {
        return buildApkFromZip(context, zip, MANIFEST_JSON.getBytes(StandardCharsets.UTF_8),
                AppMeta.defaultMeta(), outApk);
    }

    private static File buildApkFromZip(Context context, byte[] zip, byte[] manifestBytes,
                                        AppMeta meta, File outApk) throws Exception {
        return buildApkFromZip(context, zip, manifestBytes, meta, outApk, false);
    }

    private static File buildApkFromZip(Context context, byte[] zip, byte[] manifestBytes,
                                        AppMeta meta, File outApk, boolean stripLibs) throws Exception {
        Log.i(TAG, "ZIP size: " + zip.length);

        // 2. ZIP → dt.jet（AES 加密）
        byte[] jet = aesEncrypt(zip);
        Log.i(TAG, "dt.jet size: " + jet.length);

        // 3. 模板 APK → 替换 dt.jet + manifest.json + 应用名/图标/包名补丁 → 未签名 APK（内存）
        byte[] templateApk = readAsset(context, SHELL_TEMPLATE);
        byte[] unsignedApk = replaceInApk(templateApk, jet, manifestBytes, meta, stripLibs);
        Log.i(TAG, "unsigned APK size: " + unsignedApk.length);

        // 4. 签名前自检：resources.arsc 必须 STORED 且 4 字节对齐（Android 11+ 安装硬校验，报错 -124 的前置）
        verifyArscAlignment(unsignedApk);
        // 5. apksig 签名（v1+v2+v3）
        signApk(context, unsignedApk, outApk);
        Log.i(TAG, "signed APK -> " + outApk.getAbsolutePath() + ", size: " + outApk.length());
        return outApk;
    }

    /**
     * 由远程 URL 构建签名 APK（壳 manifest.json 写入 url 字段，运行时直接加载该地址）。
     *
     * 适用于 agent 生成"在线网页/服务地址"导出的场景；壳在 url 模式下不再读取 dt.jet 内容。
     *
     * @param context 应用上下文
     * @param url     远程地址（http:// 或 https://）
     * @param outApk  输出 APK 文件
     * @return 已签名 APK 文件
     */
    public static File buildApkFromUrl(Context context, String url, File outApk) throws Exception {
        return buildApkFromUrl(context, url, outApk, AppMeta.defaultMeta());
    }

    /** 由远程 URL 构建签名 APK，支持自定义应用名/图标 */
    public static File buildApkFromUrl(Context context, String url, File outApk, AppMeta meta) throws Exception {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("URL 必须以 http:// 或 https:// 开头: " + url);
        }
        // manifest.json 携带 url（JSON 安全转义）
        org.json.JSONObject obj = new org.json.JSONObject();
        obj.put("main", ENTRY_MAIN);
        obj.put("targver", 1);
        obj.put("url", url);
        byte[] manifestBytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        Log.i(TAG, "URL mode manifest: " + new String(manifestBytes, StandardCharsets.UTF_8));

        // dt.jet 占位（url 模式下壳不读取，但保持模板契约完整）
        byte[] placeholder = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>正在加载...</title>"
                + "</head><body></body></html>").getBytes(StandardCharsets.UTF_8);
        byte[] zip = htmlToZip(placeholder, ENTRY_MAIN);
        return buildApkFromZip(context, zip, manifestBytes, meta, outApk, true);
    }

    /** HTML 打包成 ZIP（入口为 mainName） */
    static byte[] htmlToZip(byte[] html, String mainName) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.setLevel(9);
        ZipEntry e = new ZipEntry(mainName);
        zos.putNextEntry(e);
        zos.write(html);
        zos.closeEntry();
        zos.close();
        return bos.toByteArray();
    }

    /** 目录递归打包成 ZIP（入口 index.html 在根，保留相对目录结构） */
    static byte[] dirToZip(File htmlDir) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.setLevel(9);
        addDirToZip(zos, htmlDir, "");
        zos.close();
        return bos.toByteArray();
    }

    private static void addDirToZip(ZipOutputStream zos, File dir, String base) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String entryName = base.isEmpty() ? child.getName() : base + "/" + child.getName();
            if (child.isDirectory()) {
                addDirToZip(zos, child, entryName);
            } else {
                ZipEntry e = new ZipEntry(entryName);
                zos.putNextEntry(e);
                zos.write(readAll(child));
                zos.closeEntry();
            }
        }
    }

    /** AES-128-CBC 加密：IV(16B) + 密文，与壳算法一致 */
    static byte[] aesEncrypt(byte[] plain) throws Exception {
        byte[] key = AES_KEY_STR.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(iv);
        out.write(ct);
        return out.toByteArray();
    }

    static byte[] replaceInApk(byte[] templateApk, byte[] jet) throws Exception {
        return replaceInApk(templateApk, jet, MANIFEST_JSON.getBytes(StandardCharsets.UTF_8),
                AppMeta.defaultMeta(), false);
    }

    /**
     * 读取模板 APK，替换 assets/dt.jet 与 assets/manifest.json，剔除签名文件。
     * 应用名/图标补丁：读取模板内 assets/apk_shell_meta.json（gen_meta.py 生成），
     * 按需做 resources.arsc 字符串池原位补丁 + 启动图标条目替换 + AndroidManifest.xml 包名等长替换。
     * resources.arsc 保持 STORED（Android 11+ 要求），并置于 ZIP 首位使其数据偏移 4 字节对齐。
     *
     * @param stripLibs true 时剔除 assets/libs/（url 模式远程页面用不到内置库，减少冗余体积）
     */
    static byte[] replaceInApk(byte[] templateApk, byte[] jet, byte[] manifestBytes, AppMeta meta,
                               boolean stripLibs) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(templateApk));
        ZipEntry ze;
        while ((ze = zis.getNextEntry()) != null) {
            String name = ze.getName();
            if (name.startsWith("META-INF/")
                    && (name.endsWith(".RSA") || name.endsWith(".SF")
                    || name.endsWith(".MF") || name.contains("KEY0"))) {
                continue;
            }
            if (stripLibs && name.startsWith("assets/libs/")) {
                continue;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
            if (ze.isDirectory()) {
                entries.put(name, null);
            } else {
                entries.put(name, bos.toByteArray());
            }
        }
        zis.close();

        entries.put("assets/dt.jet", jet);
        entries.put("assets/manifest.json", manifestBytes);

        // 模板元数据（gen_meta.py 生成）：图标条目 + 应用名占位 + 包名占位；缺失则跳过（兼容旧模板）
        String iconEntry = null;
        String labelPlaceholder = null;
        String packagePlaceholder = null;
        byte[] metaBytes = entries.get(SHELL_META_ASSET);
        if (metaBytes != null) {
            try {
                org.json.JSONObject m = new org.json.JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
                iconEntry = m.optString("icon_entry", null);
                labelPlaceholder = m.optString("label_placeholder", null);
                packagePlaceholder = m.optString("package_placeholder", null);
            } catch (Exception ignored) { }
        }
        if (meta != null && meta.label != null && !meta.label.trim().isEmpty()) {
            String label = meta.label.trim();
            if (labelPlaceholder != null) {
                byte[] arsc = entries.get("resources.arsc");
                if (arsc != null) {
                    entries.put("resources.arsc", patchLabel(arsc, labelPlaceholder, label));
                } else {
                    Log.w(TAG, "模板缺少 resources.arsc，跳过应用名补丁");
                }
            } else {
                Log.w(TAG, "模板缺少 apk_shell_meta.json，应用名保持默认");
            }
        }
        if (meta != null && meta.iconPng != null && iconEntry != null && entries.containsKey(iconEntry)) {
            entries.put(iconEntry, meta.iconPng);
            Log.i(TAG, "icon replaced: " + iconEntry + ", " + meta.iconPng.length + "B");
        }
        if (meta != null && meta.packageName != null && !meta.packageName.trim().isEmpty()) {
            String pkg = meta.packageName.trim();
            if (packagePlaceholder != null) {
                byte[] axml = entries.get("AndroidManifest.xml");
                if (axml != null) {
                    entries.put("AndroidManifest.xml", patchPackage(axml, packagePlaceholder, pkg));
                } else {
                    Log.w(TAG, "模板缺少 AndroidManifest.xml，跳过包名补丁");
                }
            } else {
                Log.w(TAG, "模板缺少 apk_shell_meta.json(package_placeholder)，包名保持模板默认");
            }
        }

        // 写 ZIP：resources.arsc 置于首位（STORED 数据从 44 字节起，天然 4 字节对齐，满足 Android 11+ 安装校验）
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.setLevel(9);
        writeEntry(zos, "resources.arsc", entries.get("resources.arsc"), true);
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String name = en.getKey();
            if ("resources.arsc".equals(name)) continue;
            writeEntry(zos, name, en.getValue(), false);
        }
        zos.close();
        return bos.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data, boolean stored) throws Exception {
        ZipEntry e = new ZipEntry(name);
        if (stored && data != null) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setCrc(crc.getValue());
        }
        zos.putNextEntry(e);
        if (data != null) zos.write(data);
        zos.closeEntry();
    }

    /**
     * AndroidManifest.xml(AXML) 包名等长替换：模板占位 com.cjhtmldemo.xxxxxxxxxxx（23 字符）
     * 与派生包名等长，在 AXML 字符串池中做 UTF-8/UTF-16 双编码全文件等长子串替换。
     * 所有以占位开头的字符串（package 属性、FileProvider authority、动态权限等）
     * 前缀一并替换为新包名，保持等长（23 字符 → 23 字符），不破坏 AXML 结构。
     */
    static byte[] patchPackage(byte[] axml, String placeholder, String newPackage) throws Exception {
        if (placeholder.length() != newPackage.length()) {
            throw new IllegalStateException("包名必须等长替换（占位 " + placeholder.length()
                    + " 字符，实际 " + newPackage.length() + " 字符）: " + newPackage);
        }
        if (!newPackage.matches("^[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_.]*$")) {
            throw new IllegalArgumentException("包名格式非法: " + newPackage);
        }
        byte[] ph8 = placeholder.getBytes(StandardCharsets.UTF_8);
        byte[] np8 = newPackage.getBytes(StandardCharsets.UTF_8);
        byte[] ph16 = placeholder.getBytes("UTF-16LE");
        byte[] np16 = newPackage.getBytes("UTF-16LE");
        byte[] out = replaceAll(axml, ph8, np8);
        out = replaceAll(out, ph16, np16);
        int hits8 = countHits(axml, ph8), hits16 = countHits(axml, ph16);
        Log.i(TAG, "package patched: \"" + newPackage + "\" (utf8 hits=" + hits8 + ", utf16 hits=" + hits16 + ")");
        return out;
    }

    /**
     * 签名前自检：解析未签名 APK 的 ZIP central directory，校验 resources.arsc
     * 为 STORED（未压缩）且数据偏移 4 字节对齐 —— Android 11+（targetSdk≥30）
     * 安装硬性要求，不满足即报 -124。不通过直接抛错，杜绝"带病出厂"。
     */
    static void verifyArscAlignment(byte[] zip) throws Exception {
        // EOCD: 0x06054b50，尾部 22 字节（无 zip64 场景，模板体积小必命中）
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0; i--) {
            if ((zip[i] & 0xFF) == 0x50 && (zip[i + 1] & 0xFF) == 0x4b
                    && (zip[i + 2] & 0xFF) == 0x05 && (zip[i + 3] & 0xFF) == 0x06) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new IllegalStateException("未签名 APK 缺少 EOCD（ZIP 结构异常）");
        int cdOff = le32(zip, eocd + 16);
        int cdCount = le16(zip, eocd + 10);
        int p = cdOff;
        String arscMethod = null;
        long arscOffset = -1;
        for (int k = 0; k < cdCount; k++) {
            if (p + 46 > zip.length) throw new IllegalStateException("central directory 截断");
            if (le32(zip, p) != 0x02014b50) throw new IllegalStateException("central directory 签名损坏 @" + p);
            int nameLen = le16(zip, p + 28);
            int extraLen = le16(zip, p + 30);
            int commentLen = le16(zip, p + 32);
            int localOff = le32(zip, p + 42);
            String name = new String(zip, p + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals("resources.arsc")) {
                int method = le16(zip, p + 10); // 0=STORED 8=DEFLATED
                arscMethod = (method == 0) ? "STORED" : "DEFLATED";
                int lname = le16(zip, localOff + 26);
                int lextra = le16(zip, localOff + 28);
                arscOffset = localOff + 30L + lname + lextra;
                break;
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        if (arscMethod == null) throw new IllegalStateException("模板缺少 resources.arsc");
        Log.i(TAG, "arsc self-check: method=" + arscMethod + ", dataOffset=" + arscOffset + " (%4=" + (arscOffset % 4) + ")");
        if (!"STORED".equals(arscMethod)) {
            throw new IllegalStateException("resources.arsc 必须 STORED（未压缩），当前 " + arscMethod
                    + " —— Android 11+ 安装硬性要求");
        }
        if (arscOffset % 4 != 0) {
            throw new IllegalStateException("resources.arsc 数据偏移未 4 字节对齐（offset=" + arscOffset
                    + "），会导致 Android 11+ 安装报 -124");
        }
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)
                | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    private static byte[] replaceAll(byte[] src, byte[] pat, byte[] rep) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i <= src.length - pat.length) {
            boolean hit = true;
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) { hit = false; break; }
            }
            if (hit) {
                bos.write(rep, 0, rep.length);
                i += pat.length;
            } else {
                bos.write(src[i]);
                i++;
            }
        }
        if (i < src.length) bos.write(src, i, src.length - i);
        return bos.toByteArray();
    }

    private static int countHits(byte[] src, byte[] pat) {
        int c = 0;
        outer:
        for (int i = 0; i <= src.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) continue outer;
            }
            c++;
        }
        return c;
    }

    /**
     * resources.arsc 字符串池原位补丁（UTF-8 池，布局 [utf16_len][utf8_len][data...]）：
     * 找到占位串（模板内唯一），改写长度头 + 内容，长度不足补零。
     * 要求新应用名 UTF-16 单元数 ≤ 占位容量（模板 meta 的 max_label_units，本项目 22）。
     */
    static byte[] patchLabel(byte[] arsc, String placeholder, String newLabel) throws Exception {
        byte[] pat = placeholder.getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(arsc, pat);
        if (idx < 0) {
            throw new IllegalStateException("未在 resources.arsc 找到应用名占位串: " + placeholder);
        }
        int oldUnits = arsc[idx - 2] & 0xFF;
        int units = newLabel.length(); // UTF-16 code units（BMP 下即字符数）
        byte[] data = newLabel.getBytes(StandardCharsets.UTF_8);
        if (units > oldUnits) {
            throw new IllegalArgumentException("应用名过长：当前 " + units + " 字符（UTF-8 " + data.length
                    + " 字节）/ 上限 " + oldUnits + " 字符。注意中文按 UTF-8 字节计算，约 " + (oldUnits / 3) + " 个汉字");
        }
        if (data.length > pat.length) {
            throw new IllegalArgumentException("应用名 UTF-8 长度超占位容量：当前 " + data.length
                    + " 字节 / 上限 " + pat.length + " 字节（中文名约 " + (pat.length / 3) + " 字）");
        }
        if (units >= 0x80 || data.length >= 0x80) {
            throw new IllegalArgumentException("应用名长度超单字节编码范围（当前 " + data.length + " 字节）");
        }
        // 原位改写：长度头 + 内容，剩余位置补零
        arsc[idx - 2] = (byte) units;
        arsc[idx - 1] = (byte) data.length;
        System.arraycopy(data, 0, arsc, idx, data.length);
        java.util.Arrays.fill(arsc, idx + data.length, idx + pat.length, (byte) 0);
        Log.i(TAG, "label patched: \"" + newLabel + "\" (" + units + " units) @ " + idx);
        return arsc;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 用 apksig 签名（v1+v2+v3），自动对齐 */
    static void signApk(Context context, byte[] unsignedApk, File outApk) throws Exception {
        // 读取导出 keystore
        InputStream ksIn = context.getAssets().open(SHELL_KEYSTORE);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(ksIn, KEYSTORE_PASSWORD.toCharArray());
        ksIn.close();

        PrivateKey pk = (PrivateKey) ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray());
        Certificate[] chain = ks.getCertificateChain(KEY_ALIAS);
        if (pk == null || chain == null || chain.length == 0) {
            throw new IllegalStateException("keystore 中未找到别名: " + KEY_ALIAS);
        }

        File tmpIn = File.createTempFile("sq_unsigned", ".apk", context.getCacheDir());
        try (FileOutputStream fos = new FileOutputStream(tmpIn)) {
            fos.write(unsignedApk);
        }

        try {
            // apksig 8.x 需要 List<X509Certificate>
            List<java.security.cert.X509Certificate> certList = new ArrayList<>();
            for (Certificate c : chain) {
                certList.add((java.security.cert.X509Certificate) c);
            }
            ApkSigner.SignerConfig signerConfig =
                    new ApkSigner.SignerConfig.Builder("CERT", pk, certList).build();
            List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<>();
            signerConfigs.add(signerConfig);

            ApkSigner.Builder builder = new ApkSigner.Builder(signerConfigs);
            builder.setInputApk(tmpIn);
            builder.setOutputApk(outApk);
            builder.setV1SigningEnabled(true);
            builder.setV2SigningEnabled(true);
            builder.setV3SigningEnabled(true);
            builder.build().sign();
        } finally {
            tmpIn.delete();
        }
    }

    static byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static byte[] readAsset(Context context, String path) throws Exception {
        try (InputStream is = context.getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
