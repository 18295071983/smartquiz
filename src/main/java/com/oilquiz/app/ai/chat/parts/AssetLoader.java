package com.oilquiz.app.ai.chat.parts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件引用加载器（2026-11-18，文件回传第二批）。
 *
 * <p>职责只有三件：按 {@code id} 取字节、落 {@code cacheDir/tool_refs/<id>}、把同一 id 的并发
 * 请求合并成一次下载。网络细节由 {@link Fetcher} 注入（App 里走插件 {@code GET /file/{id}}，
 * 单测里是内存流），本类不依赖 Android，便于测。</p>
 *
 * <p>不变量：</p>
 * <ol>
 *   <li><b>原子落盘</b>：先写 {@code <id>.part} 再 rename，进程被杀不会留下半个文件；</li>
 *   <li><b>单飞</b>：同一 id 同时只会有一个下载，其余回调挂在同一个任务上；</li>
 *   <li><b>可取消</b>：{@link #cancel(String)} 摘掉回调，下载结束也不会回调已销毁的视图。</li>
 * </ol>
 */
public final class AssetLoader {

    /** 取字节：返回流即可，超时/鉴权由实现方负责。 */
    public interface Fetcher {
        InputStream open(String id, String path) throws Exception;
    }

    /** 回调（主线程调用由调用方保证；本类只负责在后台线程结束后回调）。 */
    public interface Callback {
        void onReady(File file);

        void onError(Throwable error);
    }

    private static final class Job {
        final java.util.List<Callback> callbacks = new java.util.ArrayList<>();
        final String path;

        Job(String path) {
            this.path = path;
        }
    }

    private final File cacheDir;
    private final Fetcher fetcher;
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, File> memory = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "asset-loader");
        t.setDaemon(true); // 不持有进程；页面销毁后后台等待也能退出
        return t;
    });

    public AssetLoader(File cacheDir, Fetcher fetcher) {
        this.cacheDir = cacheDir;
        this.fetcher = fetcher;
    }

    /** 取文件；命中内存/磁盘直接回调，否则下载。 */
    public void request(final String id, String path, Callback callback) {
        if (id == null || id.isEmpty() || callback == null) {
            return;
        }
        File hit = memory.get(id);
        if (hit == null) {
            File f = new File(cacheDir, id);
            if (f.isFile() && f.length() > 0) {
                memory.put(id, f);
                hit = f;
            }
        }
        if (hit != null) {
            callback.onReady(hit);
            return;
        }
        Job job;
        boolean start = false;
        synchronized (jobs) {
            job = jobs.get(id);
            if (job == null) {
                job = new Job(path);
                jobs.put(id, job);
                start = true;
            }
            job.callbacks.add(callback);
        }
        if (start) {
            io.execute(() -> download(id, path));
        }
    }

    /** 取消某 id 上所有等待回调（不中断已开始的下载，下载完仍会落盘供下次命中）。 */
    public void cancel(String id) {
        synchronized (jobs) {
            Job job = jobs.remove(id);
            if (job != null) {
                job.callbacks.clear();
            }
        }
    }

    private void download(String id, String path) {
        File target = new File(cacheDir, id);
        File part = new File(cacheDir, id + ".part");
        Throwable error = null;
        try {
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                throw new IllegalStateException("cache dir unavailable");
            }
            try (InputStream in = fetcher.open(id, path);
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (!part.renameTo(target)) {
                throw new IllegalStateException("rename failed");
            }
        } catch (Throwable t) {
            error = t;
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        java.util.List<Callback> callbacks;
        synchronized (jobs) {
            Job job = jobs.remove(id);
            callbacks = job == null ? new java.util.ArrayList<>() : job.callbacks;
        }
        if (error == null) {
            memory.put(id, target);
        }
        final Throwable e = error;
        for (Callback cb : callbacks) {
            if (e == null) {
                cb.onReady(target);
            } else {
                cb.onError(e);
            }
        }
    }
}
