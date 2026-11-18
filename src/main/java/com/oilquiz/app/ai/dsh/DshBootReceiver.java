package com.oilquiz.app.ai.dsh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启内置 DSH（2026-11-19）。
 *
 * <p>重启手机后不用先打开 App：收到 BOOT_COMPLETED 就把 DshService 拉起来；
 * 首次安装还没解压时也会开始解压（约 15 秒），之后常驻。</p>
 */
public class DshBootReceiver extends BroadcastReceiver {

    private static final String TAG = "DshBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        try {
            DshService.start(context);
            Log.i(TAG, "boot: dsh service started");
        } catch (Throwable t) {
            Log.w(TAG, "boot start failed: " + t.getMessage());
        }
    }
}
