package com.oilquiz.app.ai.chat.media;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音频/视频播放的<b>唯一</b>播放器宿主（2026-10-23）。
 *
 * <p>为什么必须是服务而不是把 ExoPlayer 放进某个单例对象：聊天卡片是 RecyclerView 的
 * 子视图，划出屏幕就会 detach。旧实现把 ExoPlayer 建在卡片里、detach 即 release，
 * 于是"一边听一边翻聊天"根本做不到；而且后台时普通进程会被系统冻结/回收。
 * {@link MediaSessionService} 是 Media3 给的标准答案：
 * <ul>
 *   <li>播放器与 {@link MediaSession} 只有一个实例，跨视图/跨页面存活；</li>
 *   <li>播放中自动以前台服务（{@code foregroundServiceType=mediaPlayback}）常驻，
 *       锁屏、切后台、划掉最近任务都继续播；</li>
 *   <li>系统媒体通知/耳机按键/锁屏控件通过 {@code MediaSession} 免开发接入。</li>
 * </ul>
 *
 * <p>界面侧：音频卡不直接碰这里的播放器，一律经 {@link AudioPlaybackManager} 的
 * {@code MediaController} 拿状态、发指令；<b>视频卡需要把画面渲染出来</b>，
 * MediaController 不提供 Surface，所以通过 {@link #sharedPlayer()} 拿真实实例绑
 * {@code PlayerView}（同进程、主线程访问）。两者操作的是同一个播放器：
 * 放视频会自然停掉音频，反之亦然。</p>
 *
 * <p>生命周期：播放时由媒体通知维持前台；暂停后用户划掉最近任务，Media3 默认
 * {@code onTaskRemoved} 会停掉服务并释放播放器（不会留一个无声的常驻服务）。</p>
 */
public class AudioPlaybackService extends MediaSessionService {

    /** 供视频卡绑 Surface 用的真实播放器（服务未启动时为 null；只在主线程读写） */
    private static volatile ExoPlayer sharedPlayer;

    public static ExoPlayer sharedPlayer() {
        return sharedPlayer;
    }

    private ExoPlayer player;
    private MediaSession session;
    /** 播放缓存（P2 离线）：边播边缓存，LRU 256MB；完整听过的内容断网可重放。 */
    private Cache mediaCache;

    @Override
    public void onCreate() {
        super.onCreate();
        // AudioAttributes.DEFAULT + handleAudioFocus=true：来电/别的 App 放声时自动让路；
        // becoming noisy（拔耳机）自动暂停，避免突然外放
        DataSource.Factory httpFactory = AudioPlaybackService::httpDataSource;
        DataSource.Factory cacheFactory = httpFactory;
        try {
            File cacheDir = new File(getCacheDir(), "media_cache");
            mediaCache = new SimpleCache(cacheDir,
                    new LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                    new StandaloneDatabaseProvider(this));
            cacheFactory = new CacheDataSource.Factory()
                    .setCache(mediaCache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        } catch (Throwable ignored) {
            // 缓存目录/数据库不可用：退回纯网络，不影响播放
            mediaCache = null;
        }
        final DataSource.Factory cacheDataSourceFactory = cacheFactory;
        DataSource.Factory dataSourceFactory =
                () -> new SchemeDataSource(cacheDataSourceFactory, new RtmpDataSource.Factory());
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        sharedPlayer = player;
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, com.oilquiz.app.MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(openApp)
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        sharedPlayer = null;
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaCache != null) {
            mediaCache.release();
            mediaCache = null;
        }
        super.onDestroy();
    }

    private static DataSource httpDataSource() {
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000);
        Map<String, String> headers = new HashMap<>(PlaybackHeaders.get());
        if (!headers.containsKey("User-Agent")) {
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        }
        f.setDefaultRequestProperties(headers);
        return f.createDataSource();
    }

    /**
     * 按 URI scheme 选 DataSource：RTMP 走 RtmpDataSource，其余走带请求头的 HTTP。
     * ExoPlayer 的 MediaSourceFactory 只有一个 DataSource，但 RTMP 与 HTTP 是两套实现，
     * 所以在这里按每次 open 的 DataSpec 延迟选择。
     */
    private static final class SchemeDataSource implements DataSource {
        private final List<TransferListener> listeners = new ArrayList<>();
        private final DataSource.Factory httpFactory;
        private final RtmpDataSource.Factory rtmpFactory;
        private DataSource delegate;

        SchemeDataSource(DataSource.Factory httpFactory, RtmpDataSource.Factory rtmpFactory) {
            this.httpFactory = httpFactory;
            this.rtmpFactory = rtmpFactory;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            if (transferListener != null) listeners.add(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            Uri uri = dataSpec.uri;
            String scheme = uri == null ? "" : uri.getScheme();
            delegate = "rtmp".equalsIgnoreCase(scheme)
                    ? rtmpFactory.createDataSource()
                    : httpFactory.createDataSource();
            for (TransferListener listener : listeners) {
                delegate.addTransferListener(listener);
            }
            return delegate.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return delegate == null ? null : delegate.getUri();
        }

        @Override
        public void close() throws IOException {
            if (delegate != null) delegate.close();
        }
    }
}
