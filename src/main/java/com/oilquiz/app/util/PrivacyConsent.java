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

    /**
     * 用户点了"同意并继续"。
     *
     * @return 是否真的落盘。{@code isAccepted()} 每次读 prefs（不是内存标记），所以写失败时
     *         它仍是 false——下次启动会重弹，方向是安全的；但<b>同意凭证没留下来</b>。
     *         调用方拿到 false 必须说出来，不能当成就功：用户以为自己同意过了，
     *         而合规记录是空的，事后无法回答"这个人什么时候同意的哪一版"。
     */
    public static boolean accept(Context context) {
        if (context == null) return false;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ACCEPTED, true)
                    .putString(KEY_VERSION, CURRENT_VERSION)
                    .apply();
            return true;
        } catch (Throwable notStored) {
            com.oilquiz.app.util.AILogger.w("PrivacyConsent",
                    "同意状态没落盘（下次启动会重新弹，且这次同意没有凭证）: " + notStored.getMessage());
            return false;
        }
    }

    /**
     * 撤回同意（设置页的"撤回隐私授权"入口调用），下次启动会重新弹窗。
     *
     * @return 是否真的落盘。<b>这里 false 是危险方向</b>：用户按了撤回、以为已经撤回，
     *         而记录仍是"已同意"。调用方不能照常跳转到"已撤回"的界面。
     */
    public static boolean revoke(Context context) {
        if (context == null) return false;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ACCEPTED, false)
                    .remove(KEY_VERSION)
                    .apply();
            return true;
        } catch (Throwable notStored) {
            com.oilquiz.app.util.AILogger.w("PrivacyConsent",
                    "撤回没落盘：本机记录仍是已同意，必须告诉用户重试: " + notStored.getMessage());
            return false;
        }
    }
}
