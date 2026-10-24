package com.html2apk.wrapper;

import android.content.Context;

import java.io.File;

/**
 * 一键直播的本地静态服务（进程级单例）。
 *
 * <p>为什么需要它：包内 m3u 里的流地址可能是相对路径（如 `./stream/index.m3u8`）。
 * 没有 HTTP base 时相对地址无法解析；把解压后的 HTML 目录用 localhost 静态服务挂起来，
 * 相对流地址就能解析成 `http://127.0.0.1:<port>/...`，同时也能播放包内的分片/媒体文件。</p>
 *
 * <p>它故意不跟 MainActivity 生命周期绑定：live 模式启动首页后会 finish 掉 MainActivity，
 * 若把 server 放在 Activity 里会被 onDestroy 停掉，首页/播放器就取不到清单了。</p>
 */
public final class LiveLocalServer {

    private static final int[] PORTS = {8099, 18099, 28099, 38099};

    private static HtmlHttpServer server;
    private static int port = -1;
    private static File root;

    private LiveLocalServer() {
    }

    /** 启动（或复用）静态服务，返回以 / 结尾的 base URL */
    public static synchronized String ensure(Context context, File htmlDir) throws Exception {
        if (server != null && port > 0 && htmlDir != null && htmlDir.equals(root)) {
            return baseUrl();
        }
        stop();
        if (htmlDir == null || !htmlDir.isDirectory()) {
            throw new IllegalStateException("本地直播目录不存在");
        }
        Exception last = null;
        for (int p : PORTS) {
            try {
                HtmlHttpServer s = new HtmlHttpServer(htmlDir, p);
                s.start();
                server = s;
                port = p;
                root = htmlDir;
                return baseUrl();
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("本地直播服务启动失败", last);
    }

    public static synchronized String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        server = null;
        port = -1;
        root = null;
    }
}
