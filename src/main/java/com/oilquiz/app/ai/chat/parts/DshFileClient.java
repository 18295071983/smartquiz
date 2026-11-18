package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

import com.oilquiz.app.ai.tool.RemoteDshTool.DshSocketListener;
import com.oilquiz.app.ai.tool.RemoteDshTool;

import org.json.JSONObject;

import okhttp3.WebSocket;

/**
 * 手机端按需取文件（2026-11-18）：走插件的 {@code /ws/file} + {@code file_get}。
 *
 * <p>插件已有这条通道（白名单 + 32MB + 分块 + sha256），手机此前只有 {@code open_file}
 * 的 Toast、从未真正请求。这里补上发送端：首次使用时开一条文件 WS，之后复用；
 * 收到的 {@code file_begin/chunk/end} 由 {@link RemoteDshTool#openLiveSocket} 的既有处理
 * 落盘并登记到 {@code ReceivedFileStore}，渲染器只等 id。</p>
 *
 * <p>连接失败不抛出：渲染器等不到文件会走超时/失败卡；下一条请求会重新建连接。</p>
 */
final class DshFileClient {

    private static WebSocket socket;

    private static final DshSocketListener NOOP = new DshSocketListener() {
        @Override
        public void onReady(int seq) {
        }

        @Override
        public void onEvent(JSONObject event) {
        }

        @Override
        public void onPulse(long serverTs, int seq) {
        }

        @Override
        public void onClosed(String reason) {
            synchronized (DshFileClient.class) {
                socket = null;
            }
        }
    };

    private DshFileClient() {
    }

    /** 请求一个文件；{@code id} 与 file_begin/end 帧一致，渲染器按它等待。 */
    static synchronized boolean request(Context ctx, String path, String id) {
        if (ctx == null || path == null || path.isEmpty() || id == null || id.isEmpty()) {
            return false;
        }
        if (socket == null) {
            try {
                socket = RemoteDshTool.openLiveSocket(ctx,
                        RemoteDshTool.getSyncTarget(ctx), NOOP, "/ws/file");
            } catch (Throwable t) {
                socket = null;
                return false;
            }
        }
        try {
            if (socket != null) {
                socket.send(buildFrame(path, id));
                return true;
            }
        } catch (Throwable ignored) {
            // 发送失败按"没拿到"处理；下一条请求会重建连接
        }
        return false;
    }

    /** 纯函数：file_get 帧（单测用）。 */
    static String buildFrame(String path, String id) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "file_get");
            o.put("path", path);
            o.put("id", id);
            return o.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 页面/进程退出时关闭连接（下次请求会重建）。 */
    static synchronized void reset() {
        if (socket != null) {
            try {
                socket.close(1000, "reset");
            } catch (Throwable ignored) {
                // 已断就忽略
            }
            socket = null;
        }
    }
}
