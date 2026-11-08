package com.oilquiz.app.ai.jni;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.ai.spi.AppServices;
import com.oilquiz.app.util.AILogger;

/**
 * Vulkan 驱动 device lost 的一次性降级守卫（2026-11-08）。
 *
 * <p>真机 Adreno 840 / 驱动 0842.44.1：Vulkan prefill 会稳定返回
 * {@code vk::Queue::submit: ErrorDeviceLost}，同一模型走 OpenCL 正常。驱动侧
 * {@code vkGetDeviceFaultInfoEXT} 在该状态下还会空指针崩溃（llama.cpp 侧已跳过该调用）。</p>
 *
 * <p>本类只做一件事：观测到 device lost 后，<b>下一次装载</b>把用户偏好的 vulkan 运行时替换为
 * opencl，避免每次装载都撞同一条坏路径。与 {@link NpuFaultGuard} 一致：
 * <b>不改写 {@code gpu_backend} 偏好</b>，用户设置页仍显示自己的选择；用户在设置页重新选择后端时
 * 通过 {@link #clear(Context)} 解除降级，允许重新验证驱动是否已修复。</p>
 */
public final class VulkanFaultGuard {

    private static final String TAG = "VulkanFaultGuard";
    private static final String PREFS = "vulkan_fault_guard";
    private static final String K_ARMED = "demote_armed";
    private static final String K_LAST_LOSS_AT = "last_device_lost_at";
    public static final String FALLBACK_BACKEND = "opencl";

    /** 本进程是否已经打过一次"已替换"日志，避免每次装载刷屏。 */
    private static volatile boolean sAnnounced;

    private VulkanFaultGuard() {
    }

    public static boolean isArmed(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : AppServices.appContext();
        if (ctx == null) return false;
        return prefs(ctx).getBoolean(K_ARMED, false);
    }

    /** 观测到 Vulkan device lost：置位降级标记（幂等，只在首次落审计日志）。 */
    public static void onDeviceLost(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : AppServices.appContext();
        if (ctx == null) return;
        SharedPreferences sp = prefs(ctx);
        if (sp.getBoolean(K_ARMED, false)) return;
        sp.edit()
                .putBoolean(K_ARMED, true)
                .putLong(K_LAST_LOSS_AT, System.currentTimeMillis())
                .apply();
        AILogger.audit(TAG, "Vulkan device lost observed; next model load will use "
                + FALLBACK_BACKEND + " instead of vulkan (preference not overwritten)");
    }

    /** 用户在设置页显式重新选择后端：解除降级，允许再次尝试 vulkan。 */
    public static void clear(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : AppServices.appContext();
        if (ctx == null) return;
        SharedPreferences sp = prefs(ctx);
        if (!sp.getBoolean(K_ARMED, false)) return;
        sp.edit().remove(K_ARMED).apply();
        sAnnounced = false;
        AILogger.audit(TAG, "Vulkan device-lost demotion cleared by explicit backend choice");
    }

    /**
     * 装载期实际下发给 native 的后端：只有"已降级 + 请求 vulkan"时替换为 opencl。
     * auto/opencl/hexagon 一律不动（auto 在 native 侧本来就不再选 Vulkan）。
     */
    public static String effectiveBackend(Context context, String requested) {
        if (!"vulkan".equalsIgnoreCase(requested)) return requested;
        if (!isArmed(context)) return requested;
        if (!sAnnounced) {
            sAnnounced = true;
            AILogger.w(TAG, "Vulkan demoted to " + FALLBACK_BACKEND
                    + " after previous device lost (preference stays 'vulkan')");
        }
        return FALLBACK_BACKEND;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
