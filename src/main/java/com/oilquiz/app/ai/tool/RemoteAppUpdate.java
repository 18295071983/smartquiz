package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 客户端自更新（C6，2026-11-14）——**保守版**：只"提示 + 下载 + 交给系统安装器"，绝不静默安装。
 *
 * <p>为什么保守：这是唯一"做错会让用户没有可用界面"的一环。Android 本来也要求用户
 * 手动确认安装，所以静默安装既做不到也不该做。</p>
 *
 * <p>安全校验（缺一不可）：</p>
 * <ul>
 *   <li>URL 必须 https；</li>
 *   <li>下载后校验 **sha256**（与插件报的一致才继续）；</li>
 *   <li>交给系统安装器后由 Android 校验**签名**：签名不同会直接拒绝安装，旧版保持可用 → 天然回滚。</li>
 * </ul>
 *
 * <p>插件侧把发布信息放在 {@code /status.app_release = {version, url, sha256, notes?}}，
 * 本类负责比对版本并提示。</p>
 */
public final class RemoteAppUpdate {

    private static final String TAG = "RemoteAppUpdate";

    private RemoteAppUpdate() {
    }

    /** 当前安装包的 versionCode（拿不到返回 0） */
    public static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) (pi.getLongVersionCode());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 比对并提示（不自动下载）。
     *
     * @param release 插件下发的 {@code app_release}；为空或无 url 就忽略
     * @return true 表示发现有新版并已提示
     */
    public static boolean checkAndPrompt(final Context c, JSONObject release) {
        if (c == null || release == null) return false;
        final int remote = release.optInt("version", 0);
        final String url = release.optString("url", "").trim();
        final String sha = release.optString("sha256", "").trim();
        if (remote <= 0 || url.isEmpty() || !url.startsWith("https://")) return false;
        if (remote <= currentVersionCode(c)) return false;
        final String notes = release.optString("notes", "");
        // 用插槽通知：不打断，点一下才下载
        try {
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("id", "remote/app_update");
            data.put("title", "有新版本 " + remote);
            data.put("detail", notes.isEmpty() ? "点一下更新（会先下载再让你确认安装）" : notes);
            data.put("kind", "action");
            data.put("board", "alert");
            data.put("priority", 3);
            data.put("warn", false);
            data.put("action", "app_update");
            RemoteSlotBridge.apply(data);
        } catch (Throwable ignored) { /* 提示失败不影响主流程 */ }
        Log.i(TAG, "发现新版 version=" + remote + " current=" + currentVersionCode(c));
        return true;
    }

    /**
     * 下载 → 校验 sha256 → 交给系统安装器（用户确认后才会安装）。
     *
     * @param sha256 插件报的摘要；为空则**拒绝**（宁可不更新也不要装来路不明的包）
     */
    public static void downloadAndInstall(final Context c, final String url, final String sha256) {
        if (c == null) return;
        if (url == null || !url.startsWith("https://")) {
            toast(c, "更新地址必须是 https");
            return;
        }
        if (sha256 == null || sha256.trim().isEmpty()) {
            toast(c, "缺少校验值，已拒绝更新");
            return;
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            File out = null;
            try {
                out = new File(app.getCacheDir(), "update.apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("Accept", "application/octet-stream");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        md.update(buf, 0, n);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (byte b : md.digest()) sb.append(String.format("%02x", b));
                    final String got = sb.toString();
                    if (!got.equalsIgnoreCase(sha256.trim())) {
                        try { out.delete(); } catch (Throwable ignored) { /* 忽略 */ }
                        Log.w(TAG, "sha256 不匹配，已删除: " + got);
                        toastUi(c, "更新包校验失败，已取消");
                        return;
                    }
                }
                final File apk = out;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> install(c, apk));
            } catch (Throwable t) {
                Log.w(TAG, "下载失败: " + t.getMessage());
                toastUi(c, "更新下载失败：" + t.getMessage());
            }
        }, "app-update-download").start();
    }

    private static void install(Context c, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(c, "com.oilquiz.app.fileprovider", apk);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(c instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "拉起安装器失败: " + t.getMessage());
            toast(c, "无法拉起安装器：" + t.getMessage());
        }
    }

    private static void toast(Context c, String msg) {
        toastUi(c, msg);
    }

    private static void toastUi(final Context c, final String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
        } catch (Throwable ignored) { /* 忽略 */ }
    }
}
