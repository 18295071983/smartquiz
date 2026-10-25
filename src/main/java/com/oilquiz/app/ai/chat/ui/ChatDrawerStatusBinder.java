package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
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
 * 值控件（当前模型 / 账号余额 / Token 用量 / 生成阶段 / KV 缓存），<b>只有状态五行</b>：
 * 原面板那条「运行日志」读的是 {@code log_content}，而这个控件没有任何生产者，
 * 搬过来就等于再造一个空壳，所以没搬——真日志入口是抽屉里既有的「📄 系统日志」→
 * {@code LogsActivity}（详见 {@link #refresh}）。
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

    /** 绑定一次：先刷一遍值，并在每次抽屉打开时刷新。 */
    public static void bind(Activity activity) {
        if (activity == null) return;
        try {
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

    /**
     * 把源控件的当前文案抄进抽屉的值控件。
     *
     * <p>两种情况按「暂无数据」处理，都不抄旧文案：源为空；或源控件<b>被刻意隐藏</b>——
     * 生成阶段与 KV 缓存在推理中/在线模式下会被 {@code setVisibility(GONE)}
     * （AIChatActivity:10113-10122 的设计：位置让给思考内容，KV 属本地引擎可选能力），
     * 此时它们的 text 是离开上一轮时留下的旧值，抄过来会显示一个过期的"阶段"。</p>
     *
     * <p>运行日志<b>不在这里</b>：原面板那条读的是 {@code log_content}，而它没有任何生产者
     * （全仓库除本类外零引用、布局里 {@code android:text=""}），是个空控件；
     * 真入口是抽屉里既有的「📄 系统日志」({@code btn_log_viewer}) → {@code LogsActivity}，
     * 它合并 AppLogger / AILogger / 崩溃报告 / 操作记录，还有实时流与过滤。</p>
     */
    public static void refresh(Activity activity) {
        if (activity == null) return;
        try {
            for (int[] pair : PAIRS) {
                View src = activity.findViewById(pair[0]);
                TextView target = activity.findViewById(pair[1]) instanceof TextView
                        ? (TextView) activity.findViewById(pair[1]) : null;
                if (target == null) continue;
                boolean shown = src != null && src.getVisibility() == View.VISIBLE;
                String value = shown && src instanceof TextView
                        ? (((TextView) src).getText() == null ? "" : ((TextView) src).getText().toString())
                        : "";
                target.setText(TextUtils.isEmpty(value)
                        ? activity.getString(R.string.chat_v2_no_data) : value);
            }
        } catch (Throwable ignored) {
        }
    }
}
