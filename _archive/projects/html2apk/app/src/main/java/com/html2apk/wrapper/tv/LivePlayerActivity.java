package com.html2apk.wrapper.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 电视直播播放页（原生 ExoPlayer + HLS）。
 *
 * <p>为什么直播要走原生页而不是 WebView {@code <video>}：Android WebView 对 m3u8/HLS
 * 支持因机型而异，而且拿不到直播窗口、UA/Referer、重连等控制。这里用 media3 的
 * HlsMediaSource，并复用主应用验证过的直播策略：</p>
 * <ul>
 *   <li>起播点离直播边缘 10s（IPTV 小窗口只留 25s，默认偏移会放到 20 多秒就停）；</li>
 *   <li>BehindLiveWindowException → 自动回到直播边缘重连（最多 5 次）；</li>
 *   <li>一直 BUFFERING 且没有在下载 → 看门狗 12s 后重连，解决"画面停住但不报错"；</li>
 *   <li>当前频道 UA/Referer 通过 ResolvingDataSource 注入，切台自动生效。</li>
 * </ul>
 *
 * <p>界面：全屏、上下悬浮控制条、频道列表、画面比例切换；支持遥控器左右切台、
 * 确认键暂停/继续。视频不进入后台播放（切后台自动暂停）。</p>
 */
public final class LivePlayerActivity extends Activity {

    public static final String EXTRA_SESSION = "live_session";
    public static final String EXTRA_CHANNELS = "live_channels";

    private static final long LIVE_TARGET_OFFSET_MS = 10_000L;
    private static final long LIVE_RECOVER_DELAY_MS = 1_200L;
    private static final int MAX_LIVE_RECOVERIES = 5;
    private static final long LIVE_STALL_TIMEOUT_MS = 12_000L;
    private static final long LIVE_STALL_CHECK_MS = 2_000L;

    private static volatile WeakReference<LivePlayerActivity> currentRef;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<LiveChannel> channels = new ArrayList<>();
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private PlayerView playerView;
    private LiveStore store;

    private int currentIndex = 0;
    private String sourceName = "";
    private String sessionPath;
    private volatile String currentPlayUrl = "";
    private volatile Map<String, String> currentHeaders = Collections.emptyMap();

    private int liveRecoveries;
    private boolean recovering;
    private int playGeneration;
    private int httpRetries;
    private boolean watchdogRunning;
    private long bufferingSince;
    private boolean userPaused;
    private boolean pausedByLifecycle;
    private boolean errorVisible;
    private boolean controlsVisible = true;

    private FrameLayout root;
    private LinearLayout centerBox;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private ProgressBar spinner;
    private TextView errorText;
    private TextView retryButton;
    private TextView titleView;
    private ChannelLogoView logoView;
    private TextView liveBadge;
    private TextView statusView;
    private TextView playButton;

    public static void finishIfOpen() {
        LivePlayerActivity a = currentRef == null ? null : currentRef.get();
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(a::finish);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new LiveStore(this);
        currentRef = new WeakReference<>(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        applyImmersive(true);

        if (!readSession(getIntent())) {
            Toast.makeText(this, "没有可播放的频道", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        createPlayer();
        playChannel(Math.max(0, Math.min(currentIndex, channels.size() - 1)));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        if (!readSession(intent)) return;
        if (player != null) {
            liveRecoveries = 0;
            playChannel(Math.max(0, Math.min(currentIndex, channels.size() - 1)));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (pausedByLifecycle && player != null && !userPaused) {
            player.play();
        }
        pausedByLifecycle = false;
        ensureWatchdog();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopWatchdog();
        if (player != null && player.isPlaying()) {
            pausedByLifecycle = true;
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        LivePlayerActivity a = currentRef == null ? null : currentRef.get();
        if (a == this) currentRef = null;
        emit("closed", null);
        stopWatchdog();
        handler.removeCallbacksAndMessages(null);
        resolver.shutdownNow();
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (sessionPath != null) {
            try {
                File f = new File(sessionPath);
                if (f.isFile() && isInSessionDir(f)) f.delete();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive(true);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            switchChannel(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            switchChannel(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            togglePlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showChannelList();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== 会话 ====================

    private boolean readSession(Intent intent) {
        if (intent == null) return false;
        String path = intent.getStringExtra(EXTRA_SESSION);
        String raw = null;
        if (path != null && !path.isEmpty()) {
            File f = new File(path);
            if (f.isFile() && isInSessionDir(f)) {
                raw = readText(f);
                if (sessionPath != null && !sessionPath.equals(path)) {
                    try {
                        File old = new File(sessionPath);
                        if (old.isFile() && isInSessionDir(old)) old.delete();
                    } catch (Exception ignored) {
                    }
                }
                sessionPath = path;
            }
        }
        if (raw == null) raw = intent.getStringExtra(EXTRA_CHANNELS);
        if (raw == null || raw.isEmpty()) return false;

        try {
            JSONObject session = new JSONObject(raw);
            JSONArray arr = session.optJSONArray("channels");
            if (arr == null) arr = new JSONArray(raw);
            List<LiveChannel> parsed = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                LiveChannel c = LiveChannel.fromJson(arr.optJSONObject(i));
                if (c != null && c.isValid()) parsed.add(c);
            }
            if (parsed.isEmpty()) return false;
            channels.clear();
            channels.addAll(parsed);
            currentIndex = Math.max(0, Math.min(session.optInt("index", 0), channels.size() - 1));
            sourceName = session.optString("source", "");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInSessionDir(File f) {
        try {
            File dir = new File(getFilesDir(), "live_tv").getCanonicalFile();
            String p = f.getCanonicalPath();
            return p.startsWith(dir.getPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 8 * 1024 * 1024) return null;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 播放器 ====================

    private void createPlayer() {
        try {
            DataSource.Factory base = new DefaultHttpDataSource.Factory()
                    .setUserAgent(LivePlaylistRepository.DEFAULT_UA)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true);

            // 当前频道的 UA/Referer 等头随切台切换；URI 不变时也能立刻生效
            ResolvingDataSource.Factory withHeaders = new ResolvingDataSource.Factory(base, dataSpec -> {
                Map<String, String> h = currentHeaders;
                if (h == null || h.isEmpty()) return dataSpec;
                return dataSpec.buildUpon().setHttpRequestHeaders(h).build();
            });

            DefaultMediaSourceFactory mediaSourceFactory =
                    new DefaultMediaSourceFactory(withHeaders)
                            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS);

            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .build();
            player.addListener(playerListener);
            playerView.setPlayer(player);
        } catch (Exception e) {
            showError("播放器初始化失败：" + message(e));
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING) {
                if (!errorVisible) showLoading();
            } else if (state == Player.STATE_READY) {
                liveRecoveries = 0;
                httpRetries = 0;
                errorVisible = false;
                hideCenter();
                updateLiveBadge();
                ensureWatchdog();
                scheduleHideControls();
            } else if (state == Player.STATE_ENDED) {
                showError("播放已结束");
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (!isPlaying && !pausedByLifecycle && player != null
                    && player.getPlaybackState() != Player.STATE_BUFFERING
                    && player.getPlaybackState() != Player.STATE_IDLE) {
                userPaused = true;
            }
            updatePlayButton();
            if (isPlaying) {
                scheduleHideControls();
            } else {
                showControls();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (recoverBehindLiveWindow(error)) return;
            // 源返回 403/404：可能是跳转 token 过期、分片主机变化或临时抖动。
            // 重新解析原始地址并重起，比直接把错误丢给用户更实用（最多 2 次）。
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    && httpRetries < 2) {
                httpRetries++;
                final int retryGen = playGeneration;
                android.util.Log.w("LivePlayer", "HTTP status error, retry #" + httpRetries);
                handler.postDelayed(() -> {
                    if (retryGen == playGeneration) playChannel(currentIndex, true);
                }, LIVE_RECOVER_DELAY_MS);
                showLoading();
                return;
            }
            String msg = describeError(error);
            showError(msg);
            emitError(msg);
        }
    };

    private void playChannel(int index) {
        playChannel(index, false);
    }

    /**
     * @param httpRetry true=HTTP 错误后的自动重试，不清零 httpRetries
     */
    private void playChannel(int index, boolean httpRetry) {
        if (player == null || channels.isEmpty()) return;
        int idx = Math.max(0, Math.min(index, channels.size() - 1));
        LiveChannel ch = channels.get(idx);
        currentIndex = idx;
        currentHeaders = ch.headers;
        errorVisible = false;
        userPaused = false;
        if (!httpRetry) {
            liveRecoveries = 0;
            httpRetries = 0;
        }

        updateTitle();
        showLoading();

        if (!ch.isHttp()) {
            showError("暂不支持该协议（" + schemeOf(ch.url) + "），请使用 HLS(m3u8) 的 HTTP 地址");
            return;
        }
        if (isDash(ch.url)) {
            showError("暂不支持 DASH(mpd) 直播源，请使用 HLS(m3u8)");
            return;
        }

        final int gen = ++playGeneration;
        if (isHls(ch.url)) {
            // 关键：HLS 清单可能 302 到另一台主机，且分片是相对路径。
            // media3 用"传入的清单 URI"解析相对分片；不预解析跳转就会把分片拼到旧主机 → 404/403。
            resolver.execute(() -> {
                final String finalUrl = resolveFinalUrl(ch);
                handler.post(() -> {
                    if (gen != playGeneration || isFinishing() || isDestroyed()) return;
                    prepareMedia(ch, finalUrl);
                });
            });
        } else {
            prepareMedia(ch, ch.url);
        }
    }

    private void prepareMedia(LiveChannel ch, String playUrl) {
        try {
            currentPlayUrl = playUrl;
            player.setMediaItem(buildMediaItem(ch, playUrl), true);
            player.prepare();
            player.play();
            store.setLastChannel(ch, sourceName, currentIndex);
            updatePlayButton();
            ensureWatchdog();
            emit("started", ch);
        } catch (Exception e) {
            showError("起播失败：" + message(e));
        }
    }

    /**
     * 跟随 302/301 直到最终清单地址。频道自带的 UA/Referer 会一起带上，
     * 否则部分防盗链源在解析跳转时就返回 403。
     */
    private String resolveFinalUrl(LiveChannel ch) {
        String current = ch.url;
        HttpURLConnection conn = null;
        try {
            for (int i = 0; i < 6; i++) {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", LivePlaylistRepository.DEFAULT_UA);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (ch.headers != null) {
                    for (Map.Entry<String, String> e : ch.headers.entrySet()) {
                        String k = e.getKey();
                        if (k == null || e.getValue() == null) continue;
                        if ("Host".equalsIgnoreCase(k) || "Content-Length".equalsIgnoreCase(k)
                                || "Connection".equalsIgnoreCase(k)) continue;
                        conn.setRequestProperty(k, e.getValue());
                    }
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) break;
                    String next = new URL(url, loc).toString();
                    android.util.Log.i("LivePlayer", "redirect " + code + ": " + stripQuery(current)
                            + " -> " + stripQuery(next));
                    current = next;
                    conn.disconnect();
                    conn = null;
                    continue;
                }
                break;
            }
        } catch (Exception e) {
            android.util.Log.w("LivePlayer", "resolve redirect failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return current;
    }

    private static boolean isHls(String url) {
        return MimeTypes.APPLICATION_M3U8.equals(mimeTypeOf(url));
    }

    private MediaItem buildMediaItem(LiveChannel ch, String playUrl) {
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(playUrl)
                .setMediaId(ch.id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(ch.name)
                        .setArtist(ch.group)
                        .build());
        String mime = mimeTypeOf(playUrl);
        if (mime != null) b.setMimeType(mime);
        b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                .build());
        return b.build();
    }

    /**
     * 显式 MIME：带 query 的 m3u8 也能识别；无扩展名的直播地址按 HLS 尝试；
     * 已知渐进式格式交给默认推断。
     */
    private static String mimeTypeOf(String url) {
        String path = stripQuery(url).toLowerCase(Locale.US);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")
                || path.endsWith(".mkv") || path.endsWith(".ts") || path.endsWith(".mp3")
                || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".flac")
                || path.endsWith(".ogg")) {
            return null;
        }
        return MimeTypes.APPLICATION_M3U8; // IPTV 里大量无扩展名 HLS 端点
    }

    private static String stripQuery(String url) {
        String s = url == null ? "" : url;
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('#');
        if (cut >= 0) s = s.substring(0, cut);
        return s;
    }

    private static boolean isDash(String url) {
        return stripQuery(url).toLowerCase(Locale.US).endsWith(".mpd");
    }

    private static String schemeOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        return i > 0 ? url.substring(0, i) : "";
    }

    private void switchChannel(int delta) {
        if (channels.size() <= 1) {
            showControls();
            return;
        }
        int next = (currentIndex + delta + channels.size()) % channels.size();
        playChannel(next);
        showControls();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            userPaused = true;
            player.pause();
        } else {
            userPaused = false;
            player.play();
        }
        showControls();
        updatePlayButton();
    }

    private void retryNow() {
        if (player == null) return;
        liveRecoveries = 0;
        recovering = false;
        errorVisible = false;
        playChannel(currentIndex);
    }

    // ==================== 直播自愈 ====================

    private boolean recoverBehindLiveWindow(PlaybackException error) {
        if (!isBehindLiveWindow(error)) return false;
        if (liveRecoveries >= MAX_LIVE_RECOVERIES) {
            String msg = "直播窗口反复过期，已停止自动重连";
            showError(msg);
            emitError(msg);
            return true;
        }
        if (recovering) return true; // 已经在等重连，避免连续错误叠加多次 prepare
        recovering = true;
        showLoading();
        handler.postDelayed(() -> {
            recovering = false;
            restartAtLiveEdge("behind live window");
        }, LIVE_RECOVER_DELAY_MS);
        return true;
    }

    private void restartAtLiveEdge(String reason) {
        if (player == null) return;
        if (liveRecoveries >= MAX_LIVE_RECOVERIES) {
            showError("直播连续重连失败，请检查源");
            return;
        }
        liveRecoveries++;
        try {
            LiveChannel ch = currentChannel();
            if (ch != null) {
                currentHeaders = ch.headers;
                String playUrl = currentPlayUrl == null || currentPlayUrl.isEmpty()
                        ? ch.url : currentPlayUrl;
                player.setMediaItem(buildMediaItem(ch, playUrl), true);
            }
            player.prepare();
            player.play();
            ensureWatchdog();
        } catch (Exception e) {
            showError("重连失败：" + message(e));
        }
    }

    private void ensureWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        bufferingSince = 0;
        handler.postDelayed(stallWatchdog, LIVE_STALL_CHECK_MS);
    }

    private void stopWatchdog() {
        watchdogRunning = false;
        bufferingSince = 0;
        handler.removeCallbacks(stallWatchdog);
    }

    private final Runnable stallWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!watchdogRunning) return;
            Player p = player;
            if (p == null || isFinishing()) {
                watchdogRunning = false;
                return;
            }
            boolean live;
            boolean buffering;
            boolean loading;
            try {
                live = p.isCurrentMediaItemLive();
                buffering = p.getPlayWhenReady() && p.getPlaybackState() == Player.STATE_BUFFERING;
                loading = p.isLoading();
            } catch (Exception e) {
                live = false;
                buffering = false;
                loading = true;
            }
            if (!live) {
                watchdogRunning = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (!buffering || loading) {
                bufferingSince = 0;
            } else if (bufferingSince == 0) {
                bufferingSince = now;
            } else if (now - bufferingSince >= LIVE_STALL_TIMEOUT_MS) {
                bufferingSince = 0;
                restartAtLiveEdge("stalled without loading");
            }
            handler.postDelayed(this, LIVE_STALL_CHECK_MS);
        }
    };

    private static boolean isBehindLiveWindow(PlaybackException error) {
        Throwable t = error;
        for (int i = 0; t != null && i < 10; i++) {
            if (t instanceof BehindLiveWindowException) return true;
            t = t.getCause();
        }
        return false;
    }

    private String describeError(PlaybackException e) {
        if (e == null) return "播放失败";
        String code = e.getErrorCodeName();
        String prefix;
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                prefix = "网络连接失败";
                break;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                prefix = "网络连接超时";
                break;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                prefix = "源返回 HTTP 错误";
                break;
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
                prefix = "源返回的内容不是可播放流";
                break;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                prefix = "直播清单格式错误";
                break;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                prefix = "流格式不支持";
                break;
            default:
                prefix = "播放失败";
                break;
        }
        int status = httpStatus(e);
        String extra = status > 0 ? (" HTTP " + status) : ("（" + code + "）");
        android.util.Log.e("LivePlayer", "playback error: " + prefix + extra
                + " cause=" + e.getCause());
        return prefix + extra;
    }

    /** 从 PlaybackException 的 cause 链里取 HTTP 状态码（拿不到返回 -1） */
    private static int httpStatus(PlaybackException error) {
        Throwable t = error;
        for (int i = 0; t != null && i < 10; i++) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
            t = t.getCause();
        }
        return -1;
    }

    // ==================== 界面 ====================

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部：频道名 + 直播标记 + 关闭
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(14), dp(12), dp(20));
        topBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(225, 0, 0, 0), Color.TRANSPARENT}));

        logoView = new ChannelLogoView(this, 38);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        logoLp.setMargins(0, 0, dp(10), 0);
        topBar.addView(logoView, logoLp);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleView, titleLp);

        liveBadge = new TextView(this);
        liveBadge.setText("直播");
        liveBadge.setTextColor(Color.WHITE);
        liveBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        liveBadge.setGravity(Gravity.CENTER);
        liveBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#D32F2F"));
        badgeBg.setCornerRadius(dp(4));
        liveBadge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMargins(0, 0, dp(10), 0);
        topBar.addView(liveBadge, badgeLp);

        TextView close = makeButton("关闭");
        close.setOnClickListener(v -> finish());
        topBar.addView(close);
        root.addView(topBar, frameLp(Gravity.TOP));

        // 中间：加载/错误
        centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER);
        centerBox.setPadding(dp(30), dp(28), dp(30), dp(28));
        GradientDrawable centerBg = new GradientDrawable();
        centerBg.setColor(Color.argb(225, 21, 28, 36));
        centerBg.setCornerRadius(dp(18));
        centerBg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        centerBox.setBackground(centerBg);
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        centerBox.addView(spinner, new LinearLayout.LayoutParams(dp(42), dp(42)));

        errorText = new TextView(this);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        errorText.setGravity(Gravity.CENTER);
        errorText.setMaxWidth(dp(420));
        errorText.setPadding(0, dp(12), 0, dp(12));
        centerBox.addView(errorText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        retryButton = makeButton("重试");
        retryButton.setPadding(dp(28), dp(9), dp(28), dp(9));
        retryButton.setTextColor(Color.parseColor("#06121F"));
        retryButton.setBackground(buttonDrawable(Color.parseColor("#3EA6FF"), Color.parseColor("#3EA6FF")));
        retryButton.setOnClickListener(v -> retryNow());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryLp.gravity = Gravity.CENTER_HORIZONTAL;
        centerBox.addView(retryButton, retryLp);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(centerBox, centerLp);

        // 底部：状态 + 控制按钮
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(12), dp(24), dp(12), dp(16));
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(220, 0, 0, 0), Color.TRANSPARENT}));

        statusView = new TextView(this);
        statusView.setTextColor(Color.argb(190, 255, 255, 255));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setPadding(dp(6), 0, dp(6), dp(8));
        bottomBar.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        TextView prev = makeButton("上一个");
        prev.setOnClickListener(v -> switchChannel(-1));
        row.addView(prev, weighted());

        playButton = makeButton("暂停");
        playButton.setTextColor(Color.parseColor("#06121F"));
        playButton.setBackground(buttonDrawable(Color.parseColor("#3EA6FF"), Color.parseColor("#3EA6FF")));
        playButton.setOnClickListener(v -> togglePlay());
        row.addView(playButton, weighted());

        TextView next = makeButton("下一个");
        next.setOnClickListener(v -> switchChannel(1));
        row.addView(next, weighted());

        TextView list = makeButton("频道");
        list.setOnClickListener(v -> showChannelList());
        row.addView(list, weighted());

        TextView aspect = makeButton("比例");
        aspect.setOnClickListener(v -> cycleAspect());
        row.addView(aspect, weighted());

        bottomBar.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottomBar, frameLp(Gravity.BOTTOM));

        root.setOnClickListener(v -> toggleControls());
        playerView.setOnClickListener(v -> toggleControls());

        setContentView(root);
        showControls();
        showLoading();
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private FrameLayout.LayoutParams frameLp(int gravity) {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
    }

    private TextView makeButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(9), dp(12), dp(9));
        v.setClickable(true);
        v.setFocusable(true);

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = buttonDrawable(Color.argb(190, 255, 255, 255), Color.WHITE);
        GradientDrawable normal = buttonDrawable(Color.argb(120, 0, 0, 0), Color.argb(70, 255, 255, 255));
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        v.setBackground(bg);
        return v;
    }

    private GradientDrawable buttonDrawable(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private void updateTitle() {
        if (channels.isEmpty()) return;
        LiveChannel ch = channels.get(Math.max(0, Math.min(currentIndex, channels.size() - 1)));
        titleView.setText(ch.name);
        if (logoView != null) logoView.bind(ch);
        StringBuilder sb = new StringBuilder();
        if (!sourceName.isEmpty()) sb.append(sourceName).append(" · ");
        if (!ch.group.isEmpty()) sb.append(ch.group).append(" · ");
        sb.append(currentIndex + 1).append('/').append(channels.size());
        statusView.setText(sb.toString());
        updateLiveBadge();
    }

    private void updateLiveBadge() {
        boolean live = false;
        try {
            live = player != null && player.isCurrentMediaItemLive();
        } catch (Exception ignored) {
        }
        liveBadge.setText(live ? "直播" : "播放");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(live ? Color.parseColor("#D32F2F") : Color.parseColor("#546E7A"));
        bg.setCornerRadius(dp(4));
        liveBadge.setBackground(bg);
    }

    private void updatePlayButton() {
        boolean playing = false;
        try {
            playing = player != null && player.isPlaying();
        } catch (Exception ignored) {
        }
        if (playButton != null) playButton.setText(playing ? "暂停" : "播放");
    }

    private void showLoading() {
        errorVisible = false;
        if (centerBox == null) return;
        centerBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        errorText.setText("正在缓冲…");
        errorText.setTextColor(Color.argb(190, 255, 255, 255));
        errorText.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        showControls();
    }

    private void showError(String message) {
        errorVisible = true;
        if (centerBox == null) return;
        centerBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        errorText.setVisibility(View.VISIBLE);
        errorText.setTextColor(Color.WHITE);
        errorText.setText(message == null ? "播放失败" : message);
        retryButton.setVisibility(View.VISIBLE);
        showControls();
        handler.removeCallbacks(hideControlsRunnable);
    }

    private void hideCenter() {
        if (centerBox != null) centerBox.setVisibility(View.GONE);
        errorVisible = false;
    }

    private void toggleControls() {
        if (controlsVisible) {
            setControlsVisible(false);
        } else {
            showControls();
        }
    }

    private void showControls() {
        setControlsVisible(true);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (topBar != null) topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) scheduleHideControls();
        else handler.removeCallbacks(hideControlsRunnable);
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable);
        if (errorVisible) return;
        boolean playing = false;
        try {
            playing = player != null && player.isPlaying();
        } catch (Exception ignored) {
        }
        if (playing) handler.postDelayed(hideControlsRunnable, 4500);
    }

    private final Runnable hideControlsRunnable = () -> {
        if (errorVisible) return;
        boolean playing = false;
        try {
            playing = player != null && player.isPlaying();
        } catch (Exception ignored) {
        }
        if (playing) setControlsVisible(false);
    };

    private void cycleAspect() {
        if (playerView == null) return;
        int mode = playerView.getResizeMode();
        int next;
        String label;
        if (mode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            next = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            label = "铺满裁切";
        } else if (mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            next = AspectRatioFrameLayout.RESIZE_MODE_FILL;
            label = "拉伸填满";
        } else {
            next = AspectRatioFrameLayout.RESIZE_MODE_FIT;
            label = "适应屏幕";
        }
        playerView.setResizeMode(next);
        showControls();
        Toast.makeText(this, "画面：" + label, Toast.LENGTH_SHORT).show();
    }

    private void showChannelList() {
        if (channels.isEmpty() || isFinishing()) return;
        showControls();
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return channels.size();
            }

            @Override
            public Object getItem(int position) {
                return channels.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                boolean current = position == currentIndex;
                LinearLayout row = new LinearLayout(LivePlayerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(9), dp(18), dp(9));
                row.setMinimumHeight(dp(58));
                row.setBackgroundColor(current
                        ? Color.argb(55, 62, 166, 255) : Color.TRANSPARENT);

                LiveChannel c = channels.get(position);
                ChannelLogoView logo = new ChannelLogoView(LivePlayerActivity.this, 36);
                logo.bind(c);
                LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                logoLp.setMargins(0, 0, dp(11), 0);
                row.addView(logo, logoLp);

                LinearLayout text = new LinearLayout(LivePlayerActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(LivePlayerActivity.this);
                name.setText(c.name);
                name.setTextColor(current ? Color.parseColor("#7CC4FF") : Color.WHITE);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                if (current) name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                text.addView(name, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView meta = new TextView(LivePlayerActivity.this);
                String g = c.group.isEmpty() ? "" : c.group + " · ";
                meta.setText(g + (position + 1) + "/" + channels.size()
                        + (current ? " · 正在播放" : ""));
                meta.setTextColor(Color.argb(150, 255, 255, 255));
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                meta.setPadding(0, dp(2), 0, 0);
                text.addView(meta, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(text, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(sourceName.isEmpty() ? "频道列表" : "频道 · " + sourceName)
                .setAdapter(adapter, (d, which) -> {
                    playChannel(which);
                    d.dismiss();
                })
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(d -> {
            ListView lv = dialog.getListView();
            if (lv != null) lv.setSelection(Math.max(0, currentIndex - 2));
        });
        dialog.show();
    }

    private LiveChannel currentChannel() {
        if (channels.isEmpty()) return null;
        return channels.get(Math.max(0, Math.min(currentIndex, channels.size() - 1)));
    }

    // ==================== 事件 / 系统栏 ====================

    private void emit(String action, LiveChannel channel) {
        try {
            JSONObject o = new JSONObject();
            o.put("action", action);
            o.put("source", sourceName == null ? "" : sourceName);
            o.put("index", currentIndex);
            o.put("count", channels.size());
            LiveChannel ch = channel != null ? channel : currentChannel();
            if (ch != null) o.put("channel", ch.toJson());
            LiveTvEvents.emit(o.toString());
        } catch (Exception ignored) {
        }
    }

    private void emitError(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("action", "error");
            o.put("message", message == null ? "" : message);
            o.put("source", sourceName == null ? "" : sourceName);
            o.put("index", currentIndex);
            LiveChannel ch = currentChannel();
            if (ch != null) o.put("channel", ch.toJson());
            LiveTvEvents.emit(o.toString());
        } catch (Exception ignored) {
        }
    }

    private void applyImmersive(boolean on) {
        Window w = getWindow();
        if (w == null) return;
        w.setStatusBarColor(Color.TRANSPARENT);
        try {
            w.setNavigationBarColor(Color.TRANSPARENT);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(!on);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            View dv = w.getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (on) {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            } else {
                flags &= ~(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            dv.setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = on
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(lp);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static String message(Exception e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
