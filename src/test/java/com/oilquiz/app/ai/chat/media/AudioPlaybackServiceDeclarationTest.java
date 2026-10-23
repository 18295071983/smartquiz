package com.oilquiz.app.ai.chat.media;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import androidx.media3.session.SessionToken;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 播放服务清单声明守卫（2026-10-23）。
 *
 * <p>真机故障：音频/视频卡片点开毫无反应，日志里只有一句被吞掉的
 * {@code IllegalArgumentException: Failed to resolve SessionToken ... Manifest doesn't declare
 * one of either MediaSessionService, ...}。</p>
 *
 * <p>根因不在播放器代码，而在清单：{@link SessionToken} 构造时会用 PackageManager 按
 * {@code androidx.media3.session.MediaSessionService} 这个 action <b>反查</b>服务，确认目标
 * 组件真的是 MediaSessionService。只写 {@code <service android:name=...>} 不写 intent-filter，
 * 反查不到就直接拒绝，连接一次都不会发生——而旧代码把这个异常吞成 lastError，UI 上看不出任何异常。</p>
 *
 * <p>这两条用例分别钉住"清单里能被按 action 查到"和"SessionToken 真能构造出来"，
 * 避免下次重构清单时又把 intent-filter 删掉却全绿。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AudioPlaybackServiceDeclarationTest {

    /** media3 用来识别 MediaSessionService 的 action（见 SessionToken / MediaSessionService）。 */
    private static final String ACTION_MEDIA_SESSION_SERVICE =
            "androidx.media3.session.MediaSessionService";

    @Test
    public void serviceIsDiscoverableAsMediaSessionService() {
        Context context = ApplicationProvider.getApplicationContext();
        ComponentName component = new ComponentName(context, AudioPlaybackService.class);

        Intent intent = new Intent(ACTION_MEDIA_SESSION_SERVICE)
                .setPackage(context.getPackageName());
        boolean found = false;
        for (ResolveInfo info : context.getPackageManager().queryIntentServices(intent, 0)) {
            if (info.serviceInfo != null
                    && component.getClassName().equals(info.serviceInfo.name)) {
                found = true;
                break;
            }
        }
        assertTrue("AndroidManifest 里 AudioPlaybackService 必须声明 <action android:name=\""
                        + ACTION_MEDIA_SESSION_SERVICE + "\" />，否则 media3 认不出它是播放服务",
                found);
    }

    @Test
    public void sessionTokenBuildsForPlaybackService() {
        Context context = ApplicationProvider.getApplicationContext();
        try {
            SessionToken token = new SessionToken(context,
                    new ComponentName(context, AudioPlaybackService.class));
            assertNotNull(token);
        } catch (IllegalArgumentException e) {
            fail("SessionToken 解析失败（media3 按 intent-filter 反查服务类型，清单缺声明时必抛）: "
                    + e.getMessage());
        }
    }
}
