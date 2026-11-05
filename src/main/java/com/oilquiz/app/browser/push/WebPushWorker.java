package com.oilquiz.app.browser.push;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 后台轮询入口。WorkManager 保证进程被杀/重启后仍按周期调度
 * （用户手动 force-stop 或厂商省电策略仍可能阻断，系统行为无法绕过）。
 */
public class WebPushWorker extends Worker {

    private static final String TAG = "WebPushWorker";

    public WebPushWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            boolean force = getInputData().getBoolean("force", false);
            WebPushManager.get(getApplicationContext()).pollAll(force);
        } catch (Throwable t) {
            Log.w(TAG, "后台提醒轮询异常: " + t.getMessage());
        }
        // 单次失败不触发重试风暴；下一个周期自然会再试
        return Result.success();
    }
}
