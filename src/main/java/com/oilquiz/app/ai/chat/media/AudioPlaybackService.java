package com.oilquiz.app.ai.chat.media;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * 音频播放的<b>唯一</b>播放器宿主（2026-10-23）。
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
 * <p>界面侧不直接碰这里的播放器，一律经 {@link AudioPlaybackManager} 的
 * {@code MediaController} 拿状态、发指令——多张卡片同时存在时共享同一进度。</p>
 *
 * <p>生命周期：播放时由媒体通知维持前台；暂停后用户划掉最近任务，Media3 默认
 * {@code onTaskRemoved} 会停掉服务并释放播放器（不会留一个无声的常驻服务）。</p>
 */
public class AudioPlaybackService extends MediaSessionService {

    private ExoPlayer player;
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        // AudioAttributes.DEFAULT + handleAudioFocus=true：来电/别的 App 放声时自动让路；
        // becoming noisy（拔耳机）自动暂停，避免突然外放
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
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
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
