package com.oilquiz.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 隐私政策同意状态（2026-10-24）。
 *
 * <p>应用商店合规要求："首次启动弹窗告知 → 用户主动同意 → 才初始化/联网"。本类只存一个
 * 布尔值 + 政策版本号：版本号比 {@link #CURRENT_VERSION} 旧时视为未同意，用户会被再次要求确认
 * （政策实质变更时改这个常量即可）。</p>
 *
 * <p>存在独立 SharedPreferences（{@value #PREFS}）里，不和业务配置混在一起；
 * 用户"清除应用数据"即等于撤回同意。</p>
 */
public final class PrivacyConsent {

    private static final String PREFS = "privacy_consent";
    private static final String KEY_ACCEPTED = "accepted";
    private static final String KEY_VERSION = "version";

    /** 与 {@code assets/apk_shell/guides/PRIVACY_POLICY.md} 的"更新日期"保持一致 */
    public static final String CURRENT_VERSION = "2026-10-24";

    private PrivacyConsent() {
    }

    /** 是否已同意当前版本的隐私政策 */
    public static boolean isAccepted(Context context) {
        if (context == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_ACCEPTED, false)
                    && CURRENT_VERSION.equals(sp.getString(KEY_VERSION, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户点了"同意并继续" */
    public static void accept(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ACCEPTED, true)
                    .putString(KEY_VERSION, CURRENT_VERSION)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 撤回同意（例如设置页提供"撤回隐私授权"入口时调用），下次启动会重新弹窗 */
    public static void revoke(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ACCEPTED, false)
                    .remove(KEY_VERSION)
                    .apply();
        } catch (Throwable ignored) {
        }
    }
}
