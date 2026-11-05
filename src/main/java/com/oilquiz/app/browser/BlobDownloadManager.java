package com.oilquiz.app.browser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code blob:} / {@code data:} 下载。
 *
 * <p>DownloadManager 只接受 http(s)。blob URL 的数据只存在于页面 JS 上下文里，
 * 因此流程是：宿主注入一段 fetch 脚本 → 页面把 blob 切片读成 base64 →
 * 通过 {@code SmartQuizBlob} 桥回传 → 宿主拼成临时文件 → 写入系统“下载”目录。</p>
 *
 * <p>桥本身不暴露任意文件路径：只接受本进程刚生成的随机 requestId，
 * 且每个请求只能写自己的临时文件；并发数与总大小都有上限。</p>
 */
public final class BlobDownloadManager {

    private static final String TAG = "BlobDownloadManager";

    /** 同时进行的 blob 下载数上限 */
    private static final int MAX_CONCURRENT = 3;
    /** 单个文件大小上限（blob 本身在内存里，不能无限制放大） */
    static final long MAX_BYTES = 200L * 1024 * 1024;
    /** 与 buildFetchScript 中的 CH 保持一致 */
    static final int CHUNK_BYTES = 256 * 1024;
    /** 长时间没有新分片则清理，避免页面崩溃后临时文件永久残留 */
    private static final long STALE_MS = 5L * 60 * 1000;
    private static final long STALE_CHECK_MS = 60L * 1000;

    public interface Listener {
        /** 已写入下载目录，fileName 为最终文件名 */
        void onSaved(String fileName);

        /** reason 可直接展示给用户 */
        void onError(String reason);
    }

    private static final class Pending {
        final String id;
        final String fileName;
        final String mimeType;
        final File tempFile;
        final Listener listener;
        FileOutputStream out;
        long bytes;
        volatile long lastActivity = System.currentTimeMillis();

        Pending(String id, String fileName, String mimeType, File tempFile,
                Listener listener, FileOutputStream out) {
            this.id = id;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.tempFile = tempFile;
            this.listener = listener;
            this.out = out;
        }
    }

    static final class DataPayload {
        final String mime;
        final byte[] bytes;

        DataPayload(String mime, byte[] bytes) {
            this.mime = mime;
            this.bytes = bytes;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final File dir;

    public BlobDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        File cache = context.getCacheDir();
        this.dir = new File(cache != null ? cache : context.getFilesDir(), "blob_downloads");
        // 下载不跨进程：只清理明显过期的 .part，避免误删另一个浏览器实例正在写的文件
        try {
            if (dir.exists()) {
                long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
                File[] stale = dir.listFiles();
                if (stale != null) {
                    int swept = 0;
                    int left = 0;
                    for (File f : stale) {
                        if (f.lastModified() >= cutoff) continue;
                        if (f.delete()) swept++;
                        else if (f.exists()) left++;
                    }
                    if (left > 0) {
                        // 清不掉就是缓存一直在长：下次用户查"为什么占空间"时得有这条对得上
                        Log.w(TAG, "过期 .part 有 " + left + " 个没删掉（清掉 " + swept + " 个）: "
                                + dir.getPath());
                    }
                }
            } else if (!dir.mkdirs()) {
                Log.w(TAG, "blob 缓存目录建不出来: " + dir.getPath() + "，下载会在建临时文件时失败");
            }
        } catch (Throwable sweepFailed) {
            Log.w(TAG, "blob 缓存清扫没跑成: " + sweepFailed.getMessage());
        }
    }

    /**
     * 开始一个 blob 下载。
     *
     * @return 随机 requestId；并发超限或建临时文件失败返回 null
     */
    public String begin(String contentDisposition, String mimeType, Listener listener) {
        synchronized (pending) {
            if (pending.size() >= MAX_CONCURRENT) return null;
        }
        String id = UUID.randomUUID().toString();
        String name = BrowserDownloader.fileNameFromDispositionOrMime(contentDisposition, mimeType);
        String mime = normalizeMime(mimeType);
        try {
            if (!dir.exists() && !dir.mkdirs()) return null;
            File temp = new File(dir, id + ".part");
            FileOutputStream out = new FileOutputStream(temp);
            Pending p = new Pending(id, name, mime, temp, listener, out);
            pending.put(id, p);
            main.postDelayed(() -> sweepStale(id), STALE_CHECK_MS);
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "创建 blob 临时文件失败: " + t.getMessage());
            return null;
        }
    }

    /** 页面回传一个 base64 分片（JS 桥线程） */
    public void append(String id, String base64) {
        if (id == null || base64 == null) return;
        Pending p = pending.get(id);
        if (p == null) return;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            synchronized (p) {
                if (p.out == null) return;
                if (p.bytes + bytes.length > MAX_BYTES) {
                    pending.remove(id);
                    closeQuietly(p);
                    p.tempFile.delete();
                    postError(p.listener, "文件过大，无法下载");
                    return;
                }
                p.out.write(bytes);
                p.bytes += bytes.length;
                p.lastActivity = System.currentTimeMillis();
            }
        } catch (Throwable t) {
            fail(id, t.getMessage());
        }
    }

    /** 页面表示 blob 已经全部回传（JS 桥线程） */
    public void complete(String id) {
        final Pending p = id == null ? null : pending.remove(id);
        if (p == null) return;
        try {
            io.execute(() -> {
                try {
                    synchronized (p) {
                        if (p.out != null) {
                            p.out.flush();
                            p.out.close();
                            p.out = null;
                        }
                    }
                    String saved = saveToDownloads(p.tempFile, p.fileName, p.mimeType);
                    p.tempFile.delete();
                    if (saved == null) {
                        postError(p.listener, "无法写入下载目录");
                    } else {
                        postSaved(p.listener, saved);
                    }
                } catch (Throwable t) {
                    p.tempFile.delete();
                    postError(p.listener, t.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            closeQuietly(p);
            p.tempFile.delete();
            postError(p.listener, "下载管理器已关闭");
        }
    }

    /** 页面主动报错 */
    public void fail(String id, String reason) {
        final Pending p = id == null ? null : pending.remove(id);
        if (p == null) return;
        closeQuietly(p);
        p.tempFile.delete();
        postError(p.listener, reason == null || reason.isEmpty() ? "下载失败" : reason);
    }

    /**
     * data: URL 下载：Java 侧直接解码，不需要页面 JS。
     */
    public void saveDataUrl(String url, String contentDisposition, String mimeType, Listener listener) {
        if (url == null) {
            postError(listener, "无效的数据链接");
            return;
        }
        try {
            io.execute(() -> {
                File temp = null;
                try {
                    DataPayload payload = parseDataUrl(url);
                    if (payload == null) throw new IOException("无效的数据链接");
                    if (payload.bytes.length > MAX_BYTES) throw new IOException("文件过大，无法下载");

                    String mime = payload.mime != null ? payload.mime : normalizeMime(mimeType);
                    String name = BrowserDownloader.fileNameFromDispositionOrMime(
                            contentDisposition, mime);
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建临时目录");
                    temp = new File(dir, UUID.randomUUID() + ".part");
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        out.write(payload.bytes);
                    }
                    String saved = saveToDownloads(temp, name, mime);
                    if (saved == null) throw new IOException("无法写入下载目录");
                    postSaved(listener, saved);
                } catch (Throwable t) {
                    postError(listener, t.getMessage());
                } finally {
                    if (temp != null) temp.delete();
                }
            });
        } catch (RejectedExecutionException e) {
            postError(listener, "下载管理器已关闭");
        }
    }

    /** Activity 销毁：删除所有未完成临时文件并停止线程 */
    public void cancelAll() {
        List<Pending> remaining;
        synchronized (pending) {
            remaining = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (Pending p : remaining) {
            closeQuietly(p);
            p.tempFile.delete();
        }
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ==================== 页面侧脚本 ====================

    /**
     * 生成注入到当前页面的 fetch 脚本：把 blob 按 {@link #CHUNK_BYTES} 切片读成
     * base64，逐片调用 {@code SmartQuizBlob.chunk}，最后 {@code complete}。
     */
    public static String buildFetchScript(String requestId, String blobUrl) {
        String id = JSONObject.quote(requestId == null ? "" : requestId);
        String src = JSONObject.quote(blobUrl == null ? "" : blobUrl);
        return "(function(){"
                + "var rid=" + id + ",src=" + src + ";"
                + "if(!window.SmartQuizBlob){return;}"
                + "function fail(m){try{SmartQuizBlob.fail(rid,String(m||'error'));}catch(e){}}"
                + "try{"
                + "fetch(src,{credentials:'include'}).then(function(r){"
                + "if(!r.ok)throw new Error('HTTP '+r.status);return r.blob();"
                + "}).then(function(b){"
                + "var size=b.size,off=0,CH=" + CHUNK_BYTES + ";"
                + "function next(){"
                + "if(off>=size){try{SmartQuizBlob.complete(rid);}catch(e){}return;}"
                + "var fr=new FileReader();"
                + "fr.onload=function(){"
                + "try{var s=String(fr.result),i=s.indexOf(',');"
                + "SmartQuizBlob.chunk(rid,i>=0?s.substring(i+1):'');"
                + "}catch(e){fail(e);return;}"
                + "off+=CH;setTimeout(next,0);"
                + "};"
                + "fr.onerror=function(){fail('read failed');};"
                + "fr.readAsDataURL(b.slice(off,Math.min(off+CH,size)));"
                + "}"
                + "next();"
                + "}).catch(function(e){fail(e);});"
                + "}catch(e){fail(e);}"
                + "})();";
    }

    // ==================== 内部 ====================

    private void sweepStale(String id) {
        Pending p = pending.get(id);
        if (p == null) return;
        if (System.currentTimeMillis() - p.lastActivity > STALE_MS) {
            pending.remove(id);
            closeQuietly(p);
            p.tempFile.delete();
            postError(p.listener, "下载超时");
        } else {
            main.postDelayed(() -> sweepStale(id), STALE_CHECK_MS);
        }
    }

    private static void closeQuietly(Pending p) {
        if (p == null) return;
        synchronized (p) {
            if (p.out != null) {
                try {
                    p.out.flush();
                    p.out.close();
                } catch (Throwable notClosed) {
                    // flush/close 失败 = 尾部数据可能还在缓冲里没到磁盘，而 .part 之后会被
                    // 当成完整文件搬进下载目录。这不是"收尾而已"，要留痕。
                    Log.w(TAG, "blob 输出流没关好（.part 尾部可能没落盘）: " + notClosed.getMessage());
                }
                p.out = null;
            }
        }
    }

    /**
     * 回调投递。listener 是界面侧代码，它抛异常不能把"下载好了"这件事一起吞掉——
     * 原来两处 {@code catch (Throwable ignored)} 正是这个效果：文件已经落盘，界面一声不吭。
     */
    private void postSaved(final Listener listener, final String fileName) {
        if (listener == null) return;
        main.post(() -> {
            try {
                listener.onSaved(fileName);
            } catch (Throwable listenerCrashed) {
                Log.w(TAG, "下载完成回调抛异常（文件已存: " + fileName + "）: " + listenerCrashed);
            }
        });
    }

    private void postError(final Listener listener, final String reason) {
        if (listener == null) return;
        main.post(() -> {
            try {
                listener.onError(reason);
            } catch (Throwable listenerCrashed) {
                Log.w(TAG, "下载失败回调又抛异常（原始原因: " + reason + "）: " + listenerCrashed);
            }
        });
    }

    private static String normalizeMime(String mimeType) {
        if (mimeType == null) return "application/octet-stream";
        String mime = mimeType.trim();
        int semi = mime.indexOf(';');
        if (semi >= 0) mime = mime.substring(0, semi).trim();
        return mime.isEmpty() ? "application/octet-stream" : mime;
    }

    /** 解析 data:[mime][;base64],payload；失败返回 null */
    static DataPayload parseDataUrl(String url) throws Exception {
        if (url == null || !url.regionMatches(true, 0, "data:", 0, 5)) return null;
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String meta = url.substring(5, comma);
        String lower = meta.toLowerCase(Locale.ROOT);
        boolean base64 = lower.contains(";base64");
        String mime = meta;
        int semi = mime.indexOf(';');
        if (semi >= 0) mime = mime.substring(0, semi);
        mime = mime.trim();
        if (mime.isEmpty()) mime = "text/plain";

        String payload = url.substring(comma + 1);
        // base64 膨胀约 4/3，百分比编码最多 3 倍；先做粗上限，避免解码前就 OOM
        if (payload.length() > MAX_BYTES * 4) {
            throw new IOException("文件过大，无法下载");
        }
        byte[] bytes;
        if (base64) {
            bytes = Base64.decode(payload, Base64.DEFAULT);
        } else {
            bytes = Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
        }
        return new DataPayload(mime, bytes);
    }

    /**
     * 先写 MediaStore 的公共“下载”目录；失败再退回 App 专属下载目录。
     *
     * @return 最终文件名；失败返回 null
     */
    private String saveToDownloads(File source, String displayName, String mimeType) {
        String name = BrowserDownloader.sanitize(displayName);
        String mime = normalizeMime(mimeType);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IOException("openOutputStream 返回 null");
                    copy(in, out);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                source.delete();
                return name;
            }
        } catch (Throwable t) {
            Log.w(TAG, "写入系统下载目录失败: " + t.getMessage());
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable orphanLeft) {
                    // 这行 IS_PENDING=1 的记录会一直挂在系统"下载"里当半个文件，
                    // 用户删不掉也打不开——必须留下这条对得上的日志。
                    Log.w(TAG, "失败下载留下的 MediaStore 记录没删掉: " + uri + " —— "
                            + orphanLeft.getMessage());
                }
            }
        }

        // 兜底：App 专属下载目录（不需要存储权限）
        try {
            File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (base == null) base = new File(context.getFilesDir(), "downloads");
            if (!base.exists() && !base.mkdirs()) return null;
            File target = uniqueFile(base, name);
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            source.delete();
            return target.getName();
        } catch (Throwable t) {
            Log.w(TAG, "写入 App 下载目录失败: " + t.getMessage());
            return null;
        }
    }

    private static File uniqueFile(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return target;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        out.flush();
    }
}
