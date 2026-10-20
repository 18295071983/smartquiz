package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.oilquiz.app.R;

/**
 * 「聊天页界面」档位设置（2026-10-20，P0）。
 *
 * <p>在此之前的唯一写入点是 v2 自己 inflate 的「⋯」面板——也就是说从旧版切不到新版，
 * 只能 adb 改 pref。这里把入口搬到工具抽屉（旧版/新版都能打开），并负责
 * "写完 pref 立即重建"：{@code ChatUiV2Chrome.apply()} 只在 {@code initData} 跑一次，
 * 不重建就会出现"外壳还是 v2、消息层已按 v1 绑定"的分脑状态（日期与时间戳一起消失
 * 那个 bug 的触发条件）。</p>
 *
 * <p>生成中不切换：重建会打断正在跑的流式输出。此时只提示，不改 pref。</p>
 *
 * <p>这是合并的过渡件：等消息层收敛成一套、v1 分支删除后，
 * 本类与 {@link ChatUiFlags} 一起删掉。</p>
 */
public final class ChatUiSetting {

    private ChatUiSetting() {
    }

    /** 抽屉按钮文字："聊天页：新版 / 聊天页：旧版" */
    public static String buttonLabel(Context context) {
        if (context == null) return "";
        return context.getString(ChatUiFlags.isUiV2Enabled(context)
                ? R.string.chat_ui_mode_button_v2 : R.string.chat_ui_mode_button_v1);
    }

    /**
     * 弹二选一。选中且与当前不同时写 pref 并立即重建页面。
     *
     * @param busy 正在生成时为 true：不写 pref、不重建，只提示
     */
    public static void showPicker(final Activity activity, final boolean busy) {
        if (activity == null) return;
        final boolean v2 = ChatUiFlags.isUiV2Enabled(activity);
        String[] labels = new String[]{
                activity.getString(R.string.chat_ui_mode_v1) + "\n"
                        + activity.getString(R.string.chat_ui_mode_v1_desc),
                activity.getString(R.string.chat_ui_mode_v2) + "\n"
                        + activity.getString(R.string.chat_ui_mode_v2_desc),
        };
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.chat_ui_mode_title)
                .setSingleChoiceItems(labels, v2 ? 1 : 0, (dialog, which) -> {
                    dialog.dismiss();
                    boolean target = which == 1;
                    if (target == v2) return;
                    if (busy) {
                        Toast.makeText(activity, R.string.chat_ui_mode_busy, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ChatUiFlags.setUiV2Enabled(activity, target);
                    // 立即重建：外壳（ChatUiV2Chrome）与消息层必须同一档，
                    // 否则 v2 建的 holder 会在下一次 rebind 时按 v1 判据把日期/时间一起收掉。
                    activity.recreate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
