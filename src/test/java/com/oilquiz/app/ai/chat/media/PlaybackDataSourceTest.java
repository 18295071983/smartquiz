package com.oilquiz.app.ai.chat.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 播放数据源的路由守卫（2026-10-25）。
 *
 * <p>真机故障：聊天里点视频/音频卡的 ▶，卡片立刻显示
 * {@code ⚠ 视频播放失败: Source error}，重试无效、把文件重命名成纯英文也无效。
 * logcat 里真正的原因在 {@code AudioPlaybackService$SchemeDataSource}：它把<b>所有非 RTMP
 * 地址</b>都交给了 {@code DefaultHttpDataSource}，而卡片里的媒体大多是 Agent 落在工作区的
 * 本地文件（{@code file:///storage/...}），于是
 * {@code ClassCastException: sun.net.www.protocol.file.FileURLConnection cannot be cast to
 * java.net.HttpURLConnection} → ExoPlayer 报 {@code Source error}。</p>
 *
 * <p>两条用例钉住修复后的分派口径：本地文件必须能被真正读出来（走 media3 的
 * {@code DefaultDataSource}），而 http(s) 仍必须落在网络工厂上（请求头/播放缓存都在那一层，
 * 不能被默认数据源旁路掉）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class PlaybackDataSourceTest {

    /**
     * 与线上一致的"只懂 HTTP"的网络侧工厂：修复前整条链路就是它，所以这里故意不提供任何
     * 本地文件能力——一旦分派退化成"全走 HTTP"，{@link #localFileIsReadable} 会直接抛
     * {@code ClassCastException} 而不是读到内容。
     */
    private static DataSource.Factory httpOnlyFactory() {
        return () -> new DefaultHttpDataSource.Factory().createDataSource();
    }

    @Test
    public void localFileIsReadable() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        byte[] payload = "smartquiz-local-media".getBytes(StandardCharsets.UTF_8);
        File file = File.createTempFile("sq-playback-", ".mp4", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(payload);
        }

        DataSource dataSource = AudioPlaybackService
                .createDataSourceFactory(context, httpOnlyFactory())
                .createDataSource();
        try {
            assertEquals("file:// 的长度要能报出来（走 HTTP 数据源会在这里抛 ClassCastException）",
                    payload.length, dataSource.open(new DataSpec(Uri.fromFile(file))));
            assertArrayEquals("本地媒体内容必须原样读出", payload, readFully(dataSource, payload.length));
        } finally {
            dataSource.close();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void httpStillUsesNetworkFactory() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        byte[] payload = "network".getBytes(StandardCharsets.UTF_8);
        final Uri[] opened = new Uri[1];

        DataSource.Factory networkFactory = () -> new StubDataSource(payload, opened);
        DataSource dataSource = AudioPlaybackService
                .createDataSourceFactory(context, networkFactory)
                .createDataSource();
        try {
            assertEquals("http(s) 仍必须落到网络工厂（默认数据源只负责本地 scheme）",
                    payload.length, dataSource.open(new DataSpec(
                            Uri.parse("https://example.com/media/a.mp4"))));
            assertNotNull("网络工厂必须真的被调用", opened[0]);
            assertEquals("https://example.com/media/a.mp4", opened[0].toString());
        } finally {
            dataSource.close();
        }
    }

    /** 读到指定长度（数据源约定结尾返回 {@link C#RESULT_END_OF_INPUT}）。 */
    private static byte[] readFully(DataSource dataSource, int length) throws IOException {
        byte[] buffer = new byte[length];
        int total = 0;
        while (total < length) {
            int read = dataSource.read(buffer, total, length - total);
            if (read == C.RESULT_END_OF_INPUT) break;
            total += Math.max(read, 0);
            if (read <= 0) break;
        }
        assertEquals("没读满", length, total);
        return buffer;
    }

    /** 网络工厂的替身：不碰网络，只记下被请求的地址并吐固定字节。 */
    private static final class StubDataSource implements DataSource {
        private final byte[] payload;
        private final Uri[] opened;
        private int offset;

        StubDataSource(byte[] payload, Uri[] opened) {
            this.payload = payload;
            this.opened = opened;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) {
            opened[0] = dataSpec.uri;
            offset = 0;
            return payload.length;
        }

        @Override
        public int read(byte[] buffer, int off, int length) {
            int remaining = payload.length - offset;
            if (remaining <= 0) return C.RESULT_END_OF_INPUT;
            int count = Math.min(length, remaining);
            System.arraycopy(payload, offset, buffer, off, count);
            offset += count;
            return count;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return opened[0];
        }

        @Override
        public void close() {
        }
    }
}
