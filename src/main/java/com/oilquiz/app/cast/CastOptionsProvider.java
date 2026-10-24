package com.oilquiz.app.cast;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.List;

/**
 * Cast 配置（P2，2026-10-25）：用 Chromecast 默认 Media Receiver，不依赖自建接收端 App ID。
 *
 * <p>默认 Receiver 能播 mp4/webm/mp3/HLS 等普通媒体；DRM/自定义 UI 需要自有接收端，
 * 不在本版范围。投的是当前 ExoPlayer 的 mediaItem URI，投屏成功后本地暂停。</p>
 */
public class CastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
                .setReceiverApplicationId(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
