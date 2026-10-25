package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.R;

/**
 * 工具抽屉里的「本次会话状态」区块绑定（2026-10-25）。
 *
 * <p>这几行原本只存在于聊天页右上角「⋯」弹出的信息面板（已删除）。搬进工具中心后，
 * <b>事实源仍是原活动上的那几个控件</b>：{@code service_status_text}（当前模型/服务状态）、
 * {@code tv_api_balance}、{@code tv_token_stats}、{@code tv_gen_phase}、{@code tv_kv_stats}
 * ——它们一直在被各自的刷新方（{@code ServiceStatusManager}、统计条、生成状态条等）写，
 * v2 把头部收起来之后没地方看，这里只是把同一份数重新露出来，<b>不另算一遍、不新建状态</b>。</p>
 *
 * <p>刷新时机＝抽屉每次打开（与被删掉的面板"打开时取值"行为一致）+ {@link #bind} 时一次。
 * 已知局限：抽屉<b>保持打开</b>期间生成状态变化不会自动跳字（旧面板是模态的，不存在这个状态）。
 * 要实时可另接 {@code ServiceStatusManager} 的发布口，但那条路会把 UI 绑定塞进状态层，
 * 这次没做，先如实记在这里。</p>
 */
public final class ChatDrawerStatusBinder {

    /**
     * 成对的 {源控件 id, 抽屉里的值控件 id}。
     * 写成一张表是为了让"少接一个"这种漂移一眼可见，而不是散成五段 setXxx。
     */
    private static final int[][] PAIRS = {
            {R.id.service_status_text, R.id.tv_drawer_model},
            {R.id.tv_api_balance, R.id.tv_drawer_balance},
            {R.id.tv_token_stats, R.id.tv_drawer_tokens},
            {R.id.tv_gen_phase, R.id.tv_drawer_phase},
            {R.id.tv_kv_stats, R.id.tv_drawer_kv},
    };

    private ChatDrawerStatusBinder() {
    }

    /** 绑定一次：挂「运行日志」入口、先刷一遍值、并在每次抽屉打开时刷新。 */
    public static void bind(Activity activity) {
        if (activity == null) return;
        try {
            View logBtn = activity.findViewById(R.id.btn_drawer_log);
            if (logBtn != null) {
                logBtn.setOnClickListener(v -> showRunLog(activity));
            }
            refresh(activity);
            androidx.drawerlayout.widget.DrawerLayout drawer =
                    activity.findViewById(R.id.drawer_layout);
            if (drawer != null) {
                drawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                    @Override
                    public void onDrawerOpened(View drawerView) {
                        if (drawerView != null && drawerView.getId() == R.id.tool_drawer) {
                            refresh(activity);
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
            // 绑定失败不影响抽屉本身的功能（工具/模型/清空等入口都在）
        }
    }

    /** 把源控件的当前文案抄进抽屉的值控件；源为空时显示「暂无数据」。 */
    public static void refresh(Activity activity) {
        if (activity == null) return;
        try {
            for (int[] pair : PAIRS) {
                if (!(activity.findViewById(pair[1]) instanceof TextView)) continue;
                TextView target = (TextView) activity.findViewById(pair[1]);
                String value = textOf(activity, pair[0]);
                target.setText(TextUtils.isEmpty(value)
                        ? activity.getString(R.string.chat_v2_no_data) : value);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 运行日志：内容同样取自现成的 {@code log_content}，只读弹层（原面板那条链接的替代）。 */
    private static void showRunLog(Activity activity) {
        try {
            TextView tv = new TextView(activity);
            tv.setText(textOf(activity, R.id.log_content));
            if (TextUtils.isEmpty(tv.getText())) {
                tv.setText(activity.getString(R.string.chat_v2_no_data));
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setTextIsSelectable(true);
            int pad = dp(activity, 16);
            tv.setPadding(pad, pad, pad, pad);
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(tv);
            new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.chat_v2_log))
                    .setView(scroll)
                    .setNegativeButton(R.string.close, null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private static String textOf(Activity activity, int id) {
        View v = activity.findViewById(id);
        if (!(v instanceof TextView)) return "";
        CharSequence t = ((TextView) v).getText();
        return t == null ? "" : t.toString();
    }

    private static int dp(Activity activity, float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
