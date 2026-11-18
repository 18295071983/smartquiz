package com.oilquiz.app.ai.remote;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已收到的文件登记处（2026-11-18，文件回传渲染链路）。
 *
 * <p>插件侧的文件流走 {@code /ws/file}（{@code file_begin → file_chunk(b64) → file_end}），
 * 落盘后登记到这里；渲染器按 {@code ref.id} 等待/取用，而不是去猜 HTTP 路径。
 * 这样文件从哪来（工具产物、open_file、后续的 /file 端点）对渲染器是同一件事。</p>
 *
 * <p>等待用短轮询（20ms）：文件在同一个 WS 连接上分帧到达，通常几十毫秒内完成；
 * 不引 Condition/锁图，调用方也不会被永久挂住（超时返回 null）。</p>
 */
public final class ReceivedFileStore {

    /** 一个已落盘文件：{@code id} 由插件给，{@code name} 只用于展示。 */
    public static final class Entry {
        public final File file;
        public final String name;
        public final String mime;

        Entry(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    private static final Map<String, Entry> FILES = new ConcurrentHashMap<>();
    /** 失败登记：插件回 file_error 时写入，等待方立刻失败而不是干等 15s。 */
    private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();

    private ReceivedFileStore() {
    }

    public static void put(String id, File file, String name, String mime) {
        if (id == null || id.isEmpty() || file == null) {
            return;
        }
        FILES.put(id, new Entry(file, name == null ? "" : name, mime == null ? "" : mime));
    }

    public static void fail(String id, String error) {
        if (id != null && !id.isEmpty()) {
            ERRORS.put(id, error == null ? "file_error" : error);
        }
    }

    public static String errorOf(String id) {
        return id == null ? null : ERRORS.get(id);
    }

    /** 已在内存里的文件；没有返回 null。 */
    public static Entry peek(String id) {
        return id == null ? null : FILES.get(id);
    }

    /** 等文件到达（最多 {@code timeoutMs}）；超时返回 null。 */
    public static Entry await(String id, long timeoutMs) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            Entry e = FILES.get(id);
            if (e != null) {
                return e;
            }
            if (ERRORS.containsKey(id)) {
                return null; // 失败已登记：立刻返回，调用方用 errorOf 取原因
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 测试/退出清理用。 */
    public static void clear() {
        FILES.clear();
        ERRORS.clear();
    }
}
