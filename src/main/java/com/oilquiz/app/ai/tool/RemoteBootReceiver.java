package com.oilquiz.app.ai.tool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启（2026-11-13）：把"电脑端连接"重新拉起来。
 *
 * <p>为什么要这个：连接和通知都由 {@link RemoteSyncService}（前台服务）持有，
 * 它只会在 App 被打开时启动。手机重启后没人启动它，于是锁屏/后台收不到审批通知——
 * 用户看到的就是"插件没在进程级保活"。插件本身跑在电脑端 DSH 进程里，
 * 手机这边能保活的只有这条常驻连接，所以开机要把它拉回来。</p>
 *
 * <p>只在已配对时启动，避免没配对就弹一条"连接中"的常驻通知。</p>
 */
public class RemoteBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.android.internal.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        try {
            // 没配对就不启动（ensureStarted 内部已判断）
            RemoteSyncService.ensureStarted(context);
        } catch (Throwable ignored) {
            // 系统限制（后台启动 FGS）时静默：用户打开 App 仍会正常启动服务
        }
    }
}
