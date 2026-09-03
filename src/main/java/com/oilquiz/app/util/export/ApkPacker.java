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

    private ApkPacker() {}

    /**
     * 由 HTML 构建签名 APK（使用壳模板默认应用名与图标）。
     *
     * @param context   应用上下文
     * @param indexHtml 生成的入口 HTML 文件
     * @param outApk    输出 APK 文件
     * @return 已签名 APK 文件
     */
    public static File buildApk(Context context, File indexHtml, File outApk) throws Exception {
        byte[] html = readAll(indexHtml);
        Log.i(TAG, "HTML size: " + html.length);

        // 1. HTML → ZIP
        byte[] zip = htmlToZip(html, ENTRY_MAIN);
        Log.i(TAG, "ZIP size: " + zip.length);

        // 2. ZIP → dt.jet（AES 加密）
        byte[] jet = aesEncrypt(zip);
        Log.i(TAG, "dt.jet size: " + jet.length);

        // 3. 模板 APK → 替换 dt.jet + manifest.json → 未签名 APK（内存）
        byte[] templateApk = readAsset(context, SHELL_TEMPLATE);
        byte[] unsignedApk = replaceInApk(templateApk, jet);
        Log.i(TAG, "unsigned APK size: " + unsignedApk.length);

        // 4. apksig 签名（自动对齐 STORED 条目）
        signApk(context, unsignedApk, outApk);
        Log.i(TAG, "signed APK -> " + outApk.getAbsolutePath() + ", size: " + outApk.length());
        return outApk;
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

    /**
     * 读取模板 APK，替换 assets/dt.jet 与 assets/manifest.json，剔除签名文件。
     * resources.arsc 保持 STORED（Android 11+ 要求；apksig 签名时自动对齐）。
     */
    static byte[] replaceInApk(byte[] templateApk, byte[] jet) throws Exception {
        byte[] manifestBytes = MANIFEST_JSON.getBytes(StandardCharsets.UTF_8);
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

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.setLevel(9);
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String name = en.getKey();
            byte[] data = en.getValue();
            boolean stored = "resources.arsc".equals(name);
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
        zos.close();
        return bos.toByteArray();
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
