/* =====================================================================
 * ExportCli.java —— 桌面版 HTML 目录 → 签名 APK 导出工具（纯 JVM）
 * ---------------------------------------------------------------------
 * 与 App 内 ApkPacker.buildApkFromDir 完全同一链路：
 *   html_dir → ZIP → AES-128-CBC(dt.jet) → 替换进壳模板 base.apk
 *   → resources.arsc 4 字节对齐自检 → apksig v1+v2+v3 签名
 * 仅把 3 处 Context 依赖替换为本地文件/临时目录：
 *   context.getAssets().open("apk_shell/base.apk")      → 模板文件路径
 *   context.getAssets().open("apk_shell/export.keystore") → keystore 路径
 *   context.getCacheDir()                               → 系统临时目录
 * 编译：javac -cp "<SDK>/build-tools/<ver>/lib/apksigner.jar" ExportCli.java
 * 运行：java -cp ".;<SDK>/build-tools/<ver>/lib/apksigner.jar" ExportCli \
 *         <html_dir> <out.apk> [label] [icon.png] [pkg_seed]
 * ===================================================================== */
import com.android.apksig.ApkSigner;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ExportCli {
    private static final String AES_KEY_STR = "MyHtmlEditorKey1";
    private static final String ENTRY_MAIN = "index.html";
    private static final String MANIFEST_JSON = "{\"main\":\"" + ENTRY_MAIN + "\",\"targver\":1}";
    private static final String SHELL_META_ASSET = "assets/apk_shell_meta.json";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final String KEY_ALIAS = "smartquiz";

    private static void log(String s) { System.out.println("[ExportCli] " + s); }
    private static void warn(String s) { System.err.println("[ExportCli][warn] " + s); }

    /* ---------- AppMeta（同 ApkPacker） ---------- */
    static final class AppMeta {
        final String label;
        final byte[] iconPng;
        final String packageName;
        AppMeta(String label, byte[] iconPng, String packageName) {
            this.label = label;
            this.iconPng = iconPng;
            this.packageName = packageName;
        }
        static AppMeta defaultMeta() { return new AppMeta(null, null, null); }
    }

    static String derivePackageSlug(String seed) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("p");
            for (int i = 0; i < 6; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("包名 slug 生成失败", e);
        }
    }

    /* ---------- 主流程 ---------- */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("用法: ExportCli <html_dir> <out.apk> [--label 应用名] [--icon icon.png] [--seed 包名seed]");
            System.out.println("  html_dir  含 index.html 的多文件网页目录（支持 css/js/子目录相对资源）");
            System.out.println("  out.apk   输出 APK 路径");
            System.out.println("  --label   可选，应用名，中文 ≤7 字（占位容量 22 UTF-16 单元 / 22 UTF-8 字节）");
            System.out.println("  --icon    可选，应用图标 PNG（≥192×192 建议 512），未传用壳自带图标");
            System.out.println("  --seed    可选，独立包名种子；同一 seed 重复导出 = 升级覆盖，不同 seed 可共存");
            System.out.println("环境变量: SHELL_TEMPLATE(默认 src/main/assets/apk_shell/base.apk)、SHELL_KEYSTORE(默认同目录 export.keystore)");
            System.exit(2);
        }
        try {
            File htmlDir = new File(args[0]);
            File outApk = new File(args[1]);
            String label = null, seed = null;
            File icon = null;
            for (int i = 2; i < args.length; i++) {
                if ("--label".equals(args[i]) && i + 1 < args.length) label = args[++i];
                else if ("--icon".equals(args[i]) && i + 1 < args.length) icon = new File(args[++i]);
                else if ("--seed".equals(args[i]) && i + 1 < args.length) seed = args[++i];
            }

            if (!htmlDir.isDirectory()) throw new IllegalStateException("HTML 目录无效: " + htmlDir);
            File entry = new File(htmlDir, ENTRY_MAIN);
            if (!entry.isFile()) throw new IllegalStateException("HTML 目录缺少入口文件: " + entry);

            String templatePath = System.getenv("SHELL_TEMPLATE");
            if (templatePath == null || templatePath.isEmpty()) templatePath = "src/main/assets/apk_shell/base.apk";
            File template = new File(templatePath);
            String ksPath = System.getenv("SHELL_KEYSTORE");
            if (ksPath == null || ksPath.isEmpty()) ksPath = new File(template.getParentFile(), "export.keystore").getPath();
            if (!template.isFile()) throw new IllegalStateException("壳模板不存在: " + template);
            File keystore = new File(ksPath);
            if (!keystore.isFile()) throw new IllegalStateException("keystore 不存在: " + keystore);

            AppMeta meta = AppMeta.defaultMeta();
            if (label != null && !label.trim().isEmpty()) meta = new AppMeta(label.trim(), null, null);
            if (icon != null) {
                if (!icon.isFile()) throw new IllegalStateException("图标不存在: " + icon);
                meta = new AppMeta(meta.label, readAll(icon), null);
            }
            if (seed != null) {
                String pkg = "com.cjhtmldemo." + derivePackageSlug(seed);
                meta = new AppMeta(meta.label, meta.iconPng, pkg);
                log("派生包名: " + pkg);
            }

            byte[] zip = dirToZip(htmlDir);
            log("HTML dir: " + htmlDir + ", ZIP size: " + zip.length);
            byte[] jet = aesEncrypt(zip);
            byte[] templateApk = readAll(template);
            byte[] manifest = MANIFEST_JSON.getBytes(StandardCharsets.UTF_8);
            byte[] unsignedApk = replaceInApk(templateApk, jet, manifest, meta, false, keystore);
            verifyArscAlignment(unsignedApk);
            signApk(unsignedApk, outApk, keystore);
            log("signed APK -> " + outApk.getAbsolutePath() + ", size: " + outApk.length());
            System.out.println("OK " + outApk.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ExportCli][error] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /* ---------- 目录 → ZIP ---------- */
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

    /* ---------- AES-128-CBC（与壳一致） ---------- */
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

    /* ---------- 模板替换（dt.jet + manifest + 应用名/图标/包名补丁） ---------- */
    static byte[] replaceInApk(byte[] templateApk, byte[] jet, byte[] manifestBytes, AppMeta meta,
                               boolean stripLibs, File keystore) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(templateApk));
        ZipEntry ze;
        while ((ze = zis.getNextEntry()) != null) {
            String name = ze.getName();
            if (name.startsWith("META-INF/")
                    && (name.endsWith(".RSA") || name.endsWith(".SF")
                    || name.endsWith(".MF") || name.contains("KEY0"))) continue;
            if (stripLibs && name.startsWith("assets/libs/")) continue;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
            if (ze.isDirectory()) entries.put(name, null);
            else entries.put(name, bos.toByteArray());
        }
        zis.close();

        entries.put("assets/dt.jet", jet);
        entries.put("assets/manifest.json", manifestBytes);

        // 模板元数据（apk_shell_meta.json）——最小 JSON 解析；
        // 模板缺少 meta 时回退到壳工程内置占位符（SmartQuizExportAppName / xxxxxxxxxxxxx），
        // 保证 --label / --seed 对任何壳模板（含新编译产物）都生效。
        String iconEntry = null, labelPlaceholder = null, packagePlaceholder = null;
        byte[] metaBytes = entries.get(SHELL_META_ASSET);
        if (metaBytes != null) {
            String m = new String(metaBytes, StandardCharsets.UTF_8);
            iconEntry = jsonStr(m, "icon_entry");
            labelPlaceholder = jsonStr(m, "label_placeholder");
            packagePlaceholder = jsonStr(m, "package_placeholder");
        }
        if (labelPlaceholder == null) labelPlaceholder = "SmartQuizExportAppName";
        if (packagePlaceholder == null) {
            byte[] axml0 = entries.get("AndroidManifest.xml");
            if (axml0 != null) {
                packagePlaceholder = detectPackagePlaceholder(axml0);
                if (packagePlaceholder != null) log("探测到包名占位符: " + packagePlaceholder);
                else warn("无法探测包名占位符，包名保持模板默认");
            }
        }
        if (meta != null && meta.label != null && !meta.label.trim().isEmpty()) {
            String label = meta.label.trim();
            if (labelPlaceholder != null) {
                byte[] arsc = entries.get("resources.arsc");
                if (arsc != null) entries.put("resources.arsc", patchLabel(arsc, labelPlaceholder, label));
                else warn("模板缺少 resources.arsc，跳过应用名补丁");
            } else warn("模板缺少 apk_shell_meta.json，应用名保持默认");
        }
        if (meta != null && meta.iconPng != null && iconEntry != null && entries.containsKey(iconEntry)) {
            entries.put(iconEntry, meta.iconPng);
            log("icon replaced: " + iconEntry + ", " + meta.iconPng.length + "B");
        }
        if (meta != null && meta.packageName != null && !meta.packageName.trim().isEmpty()) {
            String pkg = meta.packageName.trim();
            if (packagePlaceholder != null) {
                byte[] axml = entries.get("AndroidManifest.xml");
                if (axml != null) entries.put("AndroidManifest.xml", patchPackage(axml, packagePlaceholder, pkg));
                else warn("模板缺少 AndroidManifest.xml，跳过包名补丁");
            } else warn("模板缺少 apk_shell_meta.json(package_placeholder)，包名保持模板默认");
        }

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

    private static String jsonStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int s = json.indexOf('"', c + 1);
        if (s < 0) return null;
        int e = json.indexOf('"', s + 1);
        if (e < 0) return null;
        return json.substring(s + 1, e);
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

    /* ---------- 占位符探测：从 AXML 字节中找 "com.cjhtmldemo." + 连续 x（支持 UTF-8 / UTF-16LE） ---------- */
    static String detectPackagePlaceholder(byte[] axml) throws Exception {
        byte[] p8 = "com.cjhtmldemo.".getBytes(StandardCharsets.UTF_8);
        byte[] p16 = "com.cjhtmldemo.".getBytes("UTF-16LE");
        for (int mode = 0; mode < 2; mode++) {
            byte[] ph = mode == 0 ? p8 : p16;
            int step = mode == 0 ? 1 : 2;
            for (int i = 0; i <= axml.length - ph.length; i++) {
                boolean ok = true;
                for (int j = 0; j < ph.length; j++) {
                    if (axml[i + j] != ph[j]) { ok = false; break; }
                }
                if (!ok) continue;
                int cnt = 0;
                while (i + ph.length + cnt * step < axml.length
                        && axml[i + ph.length + cnt * step] == (byte) 'x') cnt++;
                if (cnt >= 8) {
                    StringBuilder sb = new StringBuilder("com.cjhtmldemo.");
                    for (int k = 0; k < cnt; k++) sb.append('x');
                    return sb.toString();
                }
            }
        }
        return null;
    }

    /* ---------- AXML 包名等长替换 ---------- */
    static byte[] patchPackage(byte[] axml, String placeholder, String newPackage) throws Exception {
        if (placeholder.length() != newPackage.length())
            throw new IllegalStateException("包名必须等长替换（占位 " + placeholder.length() + "，实际 " + newPackage.length() + "）: " + newPackage);
        if (!newPackage.matches("^[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_.]*$"))
            throw new IllegalArgumentException("包名格式非法: " + newPackage);
        byte[] ph8 = placeholder.getBytes(StandardCharsets.UTF_8);
        byte[] np8 = newPackage.getBytes(StandardCharsets.UTF_8);
        byte[] ph16 = placeholder.getBytes("UTF-16LE");
        byte[] np16 = newPackage.getBytes("UTF-16LE");
        byte[] out = replaceAll(axml, ph8, np8);
        out = replaceAll(out, ph16, np16);
        int hits8 = countHits(axml, ph8), hits16 = countHits(axml, ph16);
        log("package patched: \"" + newPackage + "\" (utf8 hits=" + hits8 + ", utf16 hits=" + hits16 + ")");
        return out;
    }

    /* ---------- arsc 对齐自检 ---------- */
    static void verifyArscAlignment(byte[] zip) throws Exception {
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0; i--) {
            if ((zip[i] & 0xFF) == 0x50 && (zip[i + 1] & 0xFF) == 0x4b
                    && (zip[i + 2] & 0xFF) == 0x05 && (zip[i + 3] & 0xFF) == 0x06) { eocd = i; break; }
        }
        if (eocd < 0) throw new IllegalStateException("未签名 APK 缺少 EOCD");
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
                int method = le16(zip, p + 10);
                arscMethod = (method == 0) ? "STORED" : "DEFLATED";
                int lname = le16(zip, localOff + 26);
                int lextra = le16(zip, localOff + 28);
                arscOffset = localOff + 30L + lname + lextra;
                break;
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        if (arscMethod == null) throw new IllegalStateException("模板缺少 resources.arsc");
        log("arsc self-check: method=" + arscMethod + ", dataOffset=" + arscOffset + " (%4=" + (arscOffset % 4) + ")");
        if (!"STORED".equals(arscMethod))
            throw new IllegalStateException("resources.arsc 必须 STORED，当前 " + arscMethod);
        if (arscOffset % 4 != 0)
            throw new IllegalStateException("resources.arsc 数据偏移未 4 字节对齐（offset=" + arscOffset + "）");
    }

    private static int le16(byte[] b, int i) { return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }
    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }
    private static byte[] replaceAll(byte[] src, byte[] pat, byte[] rep) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int i = 0;
        while (i <= src.length - pat.length) {
            boolean hit = true;
            for (int j = 0; j < pat.length; j++) { if (src[i + j] != pat[j]) { hit = false; break; } }
            if (hit) { bos.write(rep, 0, rep.length); i += pat.length; }
            else { bos.write(src[i]); i++; }
        }
        if (i < src.length) bos.write(src, i, src.length - i);
        return bos.toByteArray();
    }
    private static int countHits(byte[] src, byte[] pat) {
        int c = 0;
        outer:
        for (int i = 0; i <= src.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) { if (src[i + j] != pat[j]) continue outer; }
            c++;
        }
        return c;
    }

    /* ---------- arsc 应用名原位补丁 ---------- */
    static byte[] patchLabel(byte[] arsc, String placeholder, String newLabel) throws Exception {
        byte[] pat = placeholder.getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(arsc, pat);
        if (idx < 0) throw new IllegalStateException("未在 resources.arsc 找到应用名占位串: " + placeholder);
        int oldUnits = arsc[idx - 2] & 0xFF;
        int units = newLabel.length();
        byte[] data = newLabel.getBytes(StandardCharsets.UTF_8);
        if (units > oldUnits)
            throw new IllegalArgumentException("应用名过长：当前 " + units + " 字符（UTF-8 " + data.length + " 字节）/ 上限 " + oldUnits + " 字符");
        if (data.length > pat.length)
            throw new IllegalArgumentException("应用名 UTF-8 长度超占位容量：当前 " + data.length + " 字节 / 上限 " + pat.length + " 字节");
        if (units >= 0x80 || data.length >= 0x80)
            throw new IllegalArgumentException("应用名长度超单字节编码范围（当前 " + data.length + " 字节）");
        arsc[idx - 2] = (byte) units;
        arsc[idx - 1] = (byte) data.length;
        System.arraycopy(data, 0, arsc, idx, data.length);
        java.util.Arrays.fill(arsc, idx + data.length, idx + pat.length, (byte) 0);
        log("label patched: \"" + newLabel + "\" (" + units + " units) @ " + idx);
        return arsc;
    }
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) { if (haystack[i + j] != needle[j]) continue outer; }
            return i;
        }
        return -1;
    }

    /* ---------- apksig 签名 ---------- */
    static void signApk(byte[] unsignedApk, File outApk, File keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream ksIn = new FileInputStream(keystore)) {
            ks.load(ksIn, KEYSTORE_PASSWORD.toCharArray());
        }
        PrivateKey pk = (PrivateKey) ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray());
        Certificate[] chain = ks.getCertificateChain(KEY_ALIAS);
        if (pk == null || chain == null || chain.length == 0)
            throw new IllegalStateException("keystore 中未找到别名: " + KEY_ALIAS);

        File tmpIn = File.createTempFile("sq_unsigned", ".apk");
        try (FileOutputStream fos = new FileOutputStream(tmpIn)) { fos.write(unsignedApk); }
        try {
            List<X509Certificate> certList = new ArrayList<>();
            for (Certificate c : chain) certList.add((X509Certificate) c);
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
        } finally { tmpIn.delete(); }
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
}
