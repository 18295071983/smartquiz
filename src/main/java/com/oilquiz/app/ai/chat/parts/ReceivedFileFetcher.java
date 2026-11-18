package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

import com.oilquiz.app.ai.remote.ReceivedFileStore;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 从 {@link ReceivedFileStore} 取文件流（2026-11-18）。
 *
 * <p>顺序：先看登记处（文件可能已随事件到达或被别的卡加载过）→ 没有就按 ref 的 PC 路径
 * 发 {@code file_get} → 等 {@code file_end} 落盘登记（15s 兜底）。超时/失败由渲染器出重试卡。</p>
 */
final class ReceivedFileFetcher implements AssetLoader.Fetcher {

    private static final long WAIT_MS = 15_000L;

    private final Context ctx;

    ReceivedFileFetcher(Context context) {
        this.ctx = context.getApplicationContext();
    }

    @Override
    public InputStream open(String id, String path) throws Exception {
        ReceivedFileStore.Entry entry = ReceivedFileStore.peek(id);
        if (entry == null && path != null && !path.isEmpty()) {
            if (!DshFileClient.request(ctx, path, id)) {
                throw new IOException("未连接电脑，无法取文件");
            }
            entry = ReceivedFileStore.await(id, WAIT_MS);
        }
        String err = ReceivedFileStore.errorOf(id);
        if (err != null) {
            throw new IOException("插件返回错误：" + err);
        }
        if (entry == null || entry.file == null || !entry.file.isFile()) {
            throw new IOException("文件未送达：" + id);
        }
        return new FileInputStream(entry.file);
    }
}
