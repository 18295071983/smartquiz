package com.oilquiz.app.ai.chat.media;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 音频播放的界面侧入口（进程内单例，2026-10-23）。
 *
 * <p>播放器本体在 {@link AudioPlaybackService}，这里只持有一个
 * {@link MediaController}：连接服务、读播放状态、下发播放/暂停/切歌/跳转。
 * 所以<b>卡片只是控制器，不是播放器</b>——卡片 detach、消息列表回收、切换页面
 * 都不会中断播放；重新出现的卡片从 {@link #snapshot()} 拿到当前位置继续画进度。</p>
 *
 * <p><b>视频卡也走这里</b>：播放/暂停/切歌同样调 {@link #playOrToggle}，只是画面需要
 * 真实播放器实例，额外通过 {@link #whenPlayerReady} 拿到 {@code ExoPlayer} 绑
 * {@code PlayerView}。音频与视频共用一个播放器，天然互斥。</p>
 *
 * <p>线程约定：Media3 的 MediaController 只能在应用主线程调用，本类所有公开方法都应在
 * 主线程调用（卡片点击/回调本来就在主线程）。进度轮询也走主线程 Handler
 * （400ms），与旧实现的后台线程轮询不同——ExoPlayer/Controller 跨线程访问会抛异常。</p>
 *
 * <p>单曲判断用 {@code mediaId}：卡片把自己的解析后 URI 传进来，状态里带回同一个
 * mediaId，多张卡片因此能各自判断"是不是我在放"。</p>
 */
public final class AudioPlaybackManager {

    private static final String TAG = "AudioPlaybackManager";

    /** 播放状态快照（不可变，给卡片渲染用） */
    public static final class State {
        public final String mediaId;
        public final String title;
        public final String artist;
        public final long positionMs;
        /** <=0 表示时长未知（流媒体/尚未 READY），UI 显示 --:-- 并禁用拖动 */
        public final long durationMs;
        public final boolean playing;
        public final boolean buffering;
        public final boolean ended;
        public final String error;

        State(String mediaId, String title, String artist, long positionMs, long durationMs,
              boolean playing, boolean buffering, boolean ended, String error) {
            this.mediaId = mediaId == null ? "" : mediaId;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = durationMs > 0 && durationMs != C.TIME_UNSET ? durationMs : 0;
            this.playing = playing;
            this.buffering = buffering;
            this.ended = ended;
            this.error = error;
        }

        public boolean isTrack(String uri) {
            return uri != null && !mediaId.isEmpty() && mediaId.equals(uri);
        }
    }

    /** 卡片注册它（attach 时加、detach 时移除）；回调在主线程 */
    public interface Listener {
        void onAudioStateChanged(State state);
    }

    /**
     * 视频卡要把画面渲染出来，需要的是<b>真实播放器实例</b>（MediaController 不提供 Surface）。
     * 播放器由 {@link AudioPlaybackService} 持有，服务连接完成时回调一次。
     */
    public interface PlayerReadyListener {
        void onPlayerReady(androidx.media3.exoplayer.ExoPlayer player);
    }

    private static volatile AudioPlaybackManager instance;

    public static AudioPlaybackManager get(Context context) {
        AudioPlaybackManager m = instance;
        if (m == null) {
            synchronized (AudioPlaybackManager.class) {
                m = instance;
                if (m == null) {
                    m = new AudioPlaybackManager(context.getApplicationContext());
                    instance = m;
                }
            }
        }
        return m;
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerReadyListener> playerReadyListeners =
            new CopyOnWriteArrayList<>();

    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private String pendingUri, pendingTitle, pendingArtist;
    private boolean pendingLoop;
    private float pendingSpeed = 1.0f;
    /** 控制器未连上时暂存的队列（播放列表卡点了某一首） */
    private java.util.List<String> pendingQueueUris, pendingQueueTitles;
    private int pendingQueueIndex;
    private String lastError;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (listeners.isEmpty()) return;      // 没有卡片在看，不必刷 UI（播放器照常播）
            notifyState();
            if (controller != null && (controller.isPlaying() || isBuffering())) {
                main.postDelayed(this, 400);
            }
        }
    };

    private AudioPlaybackManager(Context appContext) {
        this.appContext = appContext;
    }

    // ==================== 卡片侧 ====================

    public void addListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        ensureController();
        listener.onAudioStateChanged(snapshot());
        main.removeCallbacks(tick);
        main.post(tick);
    }

    /** 卡片 detach 只注销 UI，不停播放——这是"划出屏幕还继续听"的关键 */
    public void removeListener(Listener listener) {
        if (listener == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) main.removeCallbacks(tick);
    }

    // ==================== 视频卡用：拿真实播放器实例渲染 Surface ====================

    /** 确保播放服务已连接（视频卡绑 Surface 前调用；重复调用无副作用） */
    public void ensureService() {
        ensureController();
    }

    /** 服务持有的真实播放器；未连接时为 null */
    public androidx.media3.exoplayer.ExoPlayer playerOrNull() {
        return AudioPlaybackService.sharedPlayer();
    }

    /** 播放器可用时回调一次（已可用则立即回调；回调都在主线程） */
    public void whenPlayerReady(PlayerReadyListener listener) {
        if (listener == null) return;
        androidx.media3.exoplayer.ExoPlayer p = playerOrNull();
        if (p != null) {
            listener.onPlayerReady(p);
            return;
        }
        playerReadyListeners.add(listener);
        ensureController();
    }

    private void dispatchPlayerReady() {
        if (playerReadyListeners.isEmpty()) return;
        androidx.media3.exoplayer.ExoPlayer p = playerOrNull();
        if (p == null) return;
        for (PlayerReadyListener l : playerReadyListeners) {
            try {
                l.onPlayerReady(p);
            } catch (Throwable ignored) {
            }
        }
        playerReadyListeners.clear();
    }

    /** 播放/暂停这张卡片；换成别的曲目则切歌。uri 用卡片解析后的完整地址做 mediaId */
    public void playOrToggle(String uri, String title, String artist) {
        playOrToggle(uri, title, artist, false, 1.0f);
    }

    /**
     * @param loop  单曲循环（只在"切到这张卡"时应用到播放器；同曲目暂停/继续不动它）
     * @param speed 倍速，0.1~2.0 之外按 1.0 处理，同上只在切歌时应用
     */
    public void playOrToggle(String uri, String title, String artist, boolean loop, float speed) {
        if (uri == null || uri.isEmpty()) return;
        MediaController c = controller;
        if (c == null) {
            // 控制器还在连服务：记下来，连上后立刻执行
            pendingUri = uri;
            pendingTitle = title;
            pendingArtist = artist;
            pendingLoop = loop;
            pendingSpeed = speed;
            ensureController();
            return;
        }
        String currentId = currentMediaId(c);
        if (uri.equals(currentId)) {
            if (c.isPlaying()) {
                c.pause();
            } else {
                lastError = null;   // 重试播放时清掉上一轮错误态，否则卡片一直挂着 ⚠
                if (c.getPlaybackState() == Player.STATE_ENDED) c.seekTo(0);
                c.play();
            }
            notifyState();
            return;
        }
        lastError = null;
        MediaItem.Builder itemBuilder = new MediaItem.Builder()
                .setUri(uri)
                .setMediaId(uri)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title == null ? "" : title)
                        .setArtist(artist == null ? "" : artist)
                        .build());
        String mime = mimeTypeOf(uri);
        if (mime != null) itemBuilder.setMimeType(mime);
        MediaItem item = itemBuilder.build();
        c.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        c.setPlaybackSpeed(speed > 0.1f && speed <= 2.0f ? speed : 1.0f);
        c.setMediaItem(item);
        c.prepare();
        c.play();
        notifyState();
    }

    public void seekTo(long positionMs) {
        MediaController c = controller;
        if (c == null) return;
        try {
            c.seekTo(Math.max(0, positionMs));
            main.postDelayed(this::notifyState, 60);   // 跳转后立刻回一帧（暂停时也更新）
        } catch (Throwable ignored) {
        }
    }

    /**
     * 用一组曲目建<b>播放队列</b>并从 {@code startIndex} 开始播（播放列表卡 / 通知栏上下首）。
     *
     * @param uris   已解析的完整地址（同时作为 mediaId）
     * @param titles 与 uris 等长的标题，可 null
     */
    public void playQueue(java.util.List<String> uris, java.util.List<String> titles, int startIndex) {
        if (uris == null || uris.isEmpty()) return;
        MediaController c = controller;
        if (c == null) {
            pendingQueueUris = new java.util.ArrayList<>(uris);
            pendingQueueTitles = titles == null ? null : new java.util.ArrayList<>(titles);
            pendingQueueIndex = startIndex;
            ensureController();
            return;
        }
        java.util.List<MediaItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            String uri = uris.get(i);
            if (uri == null || uri.isEmpty()) continue;
            String t = titles != null && i < titles.size() ? titles.get(i) : "";
            MediaItem.Builder itemBuilder = new MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(uri)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(t).build());
            String mime = mimeTypeOf(uri);
            if (mime != null) itemBuilder.setMimeType(mime);
            items.add(itemBuilder.build());
        }
        if (items.isEmpty()) return;
        lastError = null;
        int index = Math.max(0, Math.min(startIndex, items.size() - 1));
        c.setMediaItems(items, index, 0);
        c.prepare();
        c.play();
        notifyState();
    }

    /** 队列下一首（没有下一首时什么都不做） */
    public void skipToNext() {
        MediaController c = controller;
        if (c == null) return;
        try {
            if (c.hasNextMediaItem()) {
                lastError = null;
                c.seekToNextMediaItem();
                c.play();
                notifyState();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 队列上一首 */
    public void skipToPrevious() {
        MediaController c = controller;
        if (c == null) return;
        try {
            if (c.hasPreviousMediaItem()) {
                lastError = null;
                c.seekToPreviousMediaItem();
                c.play();
                notifyState();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前播放状态；控制器尚未连上时返回空状态（不阻塞 UI） */
    public State snapshot() {
        MediaController c = controller;
        if (c == null) {
            return new State("", "", "", 0, 0, false, false, false, lastError);
        }
        MediaItem item = c.getCurrentMediaItem();
        String id = item != null && item.mediaId != null ? item.mediaId : "";
        String title = "";
        String artist = "";
        if (item != null && item.mediaMetadata != null) {
            if (item.mediaMetadata.title != null) title = item.mediaMetadata.title.toString();
            if (item.mediaMetadata.artist != null) artist = item.mediaMetadata.artist.toString();
        }
        long dur = 0;
        long pos = 0;
        try {
            dur = c.getDuration();
            pos = c.getCurrentPosition();
        } catch (Throwable ignored) {
        }
        int st = Player.STATE_IDLE;
        try {
            st = c.getPlaybackState();
        } catch (Throwable ignored) {
        }
        return new State(id, title, artist, pos, dur,
                c.isPlaying(), st == Player.STATE_BUFFERING, st == Player.STATE_ENDED, lastError);
    }

    // ==================== 服务连接 ====================

    private void ensureController() {
        if (controller != null || controllerFuture != null) return;
        try {
            // 这个 SessionToken 会在构造时用 PackageManager 按 action 反查清单里的服务，
            // 校验"你确实是 MediaSessionService"。所以 AudioPlaybackService 的 <service>
            // 必须带 androidx.media3.session.MediaSessionService 的 intent-filter，
            // 否则这里直接抛 IllegalArgumentException，一次都连不上（2026-10-23 真机根因：
            // 卡片点了没反应，旧代码把异常吞成 lastError，UI 完全看不出来）。
            SessionToken token = new SessionToken(appContext,
                    new ComponentName(appContext, AudioPlaybackService.class));
            MediaController.Builder builder = new MediaController.Builder(appContext, token);
            builder.setListener(controllerListener);
            controllerFuture = builder.buildAsync();
            controllerFuture.addListener(() -> {
                try {
                    controller = controllerFuture.get();
                    lastError = null;   // 连上了：清掉上一次"连接失败"，别让卡片一直挂着 ⚠
                    Log.i(TAG, "controller connected, player=" + AudioPlaybackService.sharedPlayer());
                    controller.addListener(playerListener);
                    // 服务 onCreate 已经把真实播放器建好，视频卡在这里拿到实例绑 Surface
                    dispatchPlayerReady();
                    String u = pendingUri, t = pendingTitle, a = pendingArtist;
                    boolean lp = pendingLoop;
                    float sp = pendingSpeed;
                    java.util.List<String> qu = pendingQueueUris, qt = pendingQueueTitles;
                    int qi = pendingQueueIndex;
                    pendingUri = pendingTitle = pendingArtist = null;
                    pendingQueueUris = pendingQueueTitles = null;
                    if (qu != null) {
                        playQueue(qu, qt, qi);
                    } else if (u != null) {
                        playOrToggle(u, t, a, lp, sp);
                    } else {
                        notifyState();
                    }
                } catch (Throwable t) {
                    controllerFuture = null;
                    lastError = "播放服务连接失败";
                    Log.e(TAG, "connect failed", t);
                    notifyState();
                }
            }, ContextCompat.getMainExecutor(appContext));
        } catch (Throwable t) {
            lastError = "播放服务连接失败";
            Log.e(TAG, "bind failed", t);
            notifyState();
        }
    }

    private boolean isBuffering() {
        try {
            return controller != null && controller.getPlaybackState() == Player.STATE_BUFFERING;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * HLS（.m3u8）地址显式给 MIME：DefaultMediaSourceFactory 靠 MIME（或扩展名）挑
     * HlsMediaSource。显式给的好处是带 query 的直播地址（token/时间戳/签名）也能认出来；
     * 其它地址返回 null，继续走默认推断（mp4/mp3/flac/…）。
     */
    static String mimeTypeOf(String uri) {
        if (uri == null) return null;
        String path = uri;
        int cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        return path.toLowerCase(java.util.Locale.US).endsWith(".m3u8")
                ? MimeTypes.APPLICATION_M3U8 : null;
    }

    private static String currentMediaId(MediaController c) {
        try {
            MediaItem item = c.getCurrentMediaItem();
            return item != null && item.mediaId != null ? item.mediaId : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void notifyState() {
        if (listeners.isEmpty()) return;
        State s = snapshot();
        for (Listener l : listeners) {
            try {
                l.onAudioStateChanged(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            notifyState();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            lastError = error == null ? "播放失败" : String.valueOf(error.getMessage());
            notifyState();
        }
    };

    private final MediaController.Listener controllerListener = new MediaController.Listener() {
        @Override
        public void onDisconnected(MediaController controller) {
            // 服务被系统/用户停掉：清掉连接，下次点播放会重新连
            AudioPlaybackManager.this.controller = null;
            controllerFuture = null;
            // 等待播放器的回调是持有 View 的一次性任务，服务没了就别留着（防泄漏）
            playerReadyListeners.clear();
            notifyState();
        }
    };
}
