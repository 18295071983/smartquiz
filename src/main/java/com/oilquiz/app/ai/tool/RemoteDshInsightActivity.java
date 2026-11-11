package com.oilquiz.app.ai.tool;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 远程连接扩展面板（2026-11-12）：日程 / 计划模式 / 子代理。
 *
 * <p>补的是三类"手机上原本看不到"的东西：</p>
 * <ul>
 *   <li><b>日程</b>：`ctx.schedule` 的宿主任务不在会话日志里，只能走 {@code /v2/schedule}；
 *       每张卡可「执行一次」「删除」，变更后电脑端 {@code schedule/changed} 会推给 /events；</li>
 *   <li><b>计划</b>：{@code plan/mode} 是会话事件（时间线里有卡），这里额外给一个开关，
 *       走 {@code /v2/plan} 读写 {@code ctx.planMode}（需要会话在电脑端 live）；</li>
 *   <li><b>子代理</b>：只有登记卡不够，这里列 {@code /v2/subagents} 的目录 + 每个子会话的
 *       最近活动/是否在跑；实时活动还会以 {@code subagent/activity} 事件进对话时间线。</li>
 * </ul>
 */
public class RemoteDshInsightActivity extends AppCompatActivity {

    private static final String TAG = "RemoteDshInsight";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout host;
    private TextView status;

    private boolean loading = false;
    private boolean planActive = false;
    private boolean planLive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_dsh_insight);
        host = findViewById(R.id.insight_host);
        status = findViewById(R.id.insight_status);
        View refresh = findViewById(R.id.btn_insight_refresh);
        if (refresh != null) refresh.setOnClickListener(v -> load());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private String targetSession() {
        String s = RemoteDshTool.getSyncTarget(this);
        if (s == null || s.isEmpty()) s = RemoteDshTool.getDirectSessionId(this);
        return s == null ? "" : s;
    }

    // ================= 加载 =================

    private void load() {
        if (loading) return;
        if (!RemoteDshTool.isConfigured(this) || !RemoteDshTool.isConnected(this)) {
            setStatus("还没连接电脑：先在「远程连接（电脑）」里配对");
            return;
        }
        loading = true;
        setStatus("加载中…");
        final String sid = targetSession();
        new Thread(() -> {
            Map<String, Object> schedule = null;
            Map<String, Object> plan = null;
            Map<String, Object> subs = null;
            String error = null;
            try {
                schedule = RemoteDshTool.fetchSchedule(this);
            } catch (Exception e) {
                error = "日程：" + e.getMessage();
            }
            try {
                plan = RemoteDshTool.fetchPlan(this, sid);
            } catch (Exception e) {
                if (error == null) error = "计划：" + e.getMessage();
            }
            try {
                subs = RemoteDshTool.fetchSubagents(this, sid);
            } catch (Exception e) {
                if (error == null) error = "子代理：" + e.getMessage();
            }
            final Map<String, Object> fSchedule = schedule;
            final Map<String, Object> fPlan = plan;
            final Map<String, Object> fSubs = subs;
            final String fError = error;
            ui.post(() -> {
                loading = false;
                render(fSchedule, fPlan, fSubs, fError);
            });
        }, "dsh-insight").start();
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text == null ? "" : text);
    }

    // ================= 渲染 =================

    private void render(Map<String, Object> schedule, Map<String, Object> plan,
                        Map<String, Object> subs, String error) {
        if (host == null) return;
        host.removeAllViews();

        StringBuilder st = new StringBuilder();
        if (error != null) st.append(error);
        appendScheduleSection(schedule, st);
        appendPlanSection(plan, st);
        appendSubagentSection(subs, st);
        if (st.length() == 0) st.append("数据已更新");
        setStatus(st.toString());
    }

    private void appendScheduleSection(Map<String, Object> resp, StringBuilder st) {
        host.addView(sectionTitle("日程（电脑端定时任务）", null));
        if (resp == null) {
            host.addView(note("取不到日程（电脑端插件可能没有 schedule 服务）"));
            return;
        }
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note("日程不可用：" + str(resp.get("error")) + " " + str(resp.get("hint"))));
            return;
        }
        Object tasksObj = resp.get("tasks");
        if (!(tasksObj instanceof java.util.List) || ((java.util.List<?>) tasksObj).isEmpty()) {
            host.addView(note("暂无定时任务"));
            return;
        }
        int shown = 0;
        for (Object o : (java.util.List<?>) tasksObj) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) o;
            host.addView(scheduleRow(t));
            shown += 1;
            if (shown >= 30) break;
        }
    }

    private View scheduleRow(Map<String, Object> task) {
        LinearLayout box = card();
        String title = str(task.get("title"));
        String kind = str(task.get("kind"));
        String id = str(task.get("id"));
        String state = str(task.get("status"));
        String at = str(task.get("scheduledAt"));
        TextView tv = new TextView(this);
        tv.setText((title.isEmpty() ? "（未命名）" : title) + "  ·  " + statusLabel(state));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(textColor());
        box.addView(tv);

        TextView meta = note("类型 " + kind + " · 下次 " + friendlyTime(at)
                + (str(task.get("executionActive")).isEmpty() ? "" : " · 正在执行"));
        box.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button run = new Button(this);
        run.setText("执行一次");
        run.setOnClickListener(v -> scheduleWrite("run", id, null, "已触发执行"));
        actions.addView(run);
        Button del = new Button(this);
        del.setText("删除");
        del.setOnClickListener(v -> confirmDeleteSchedule(id, title));
        actions.addView(del);
        box.addView(actions);
        return box;
    }

    private void appendPlanSection(Map<String, Object> resp, StringBuilder st) {
        host.addView(sectionTitle("计划模式", null));
        LinearLayout box = card();
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) {
            planLive = false;
            String hint = resp == null ? "取不到计划状态" : (str(resp.get("error")) + " " + str(resp.get("hint")));
            box.addView(note(hint));
            host.addView(box);
            return;
        }
        planLive = true;
        planActive = Boolean.TRUE.equals(resp.get("active"));
        boolean pending = Boolean.TRUE.equals(resp.get("pending"));
        TextView tv = new TextView(this);
        tv.setText(planActive ? "已开启：模型先给计划，等你批准后执行" : "已关闭");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(textColor());
        box.addView(tv);
        if (pending) box.addView(note("有一次切换正在等待下一步生效"));
        Button toggle = new Button(this);
        toggle.setText(planActive ? "退出计划模式" : "进入计划模式");
        toggle.setOnClickListener(v -> planWrite(!planActive));
        box.addView(toggle);
        host.addView(box);
    }

    private void appendSubagentSection(Map<String, Object> resp, StringBuilder st) {
        host.addView(sectionTitle("子代理", null));
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note(resp == null ? "取不到子代理" : ("子代理不可用：" + str(resp.get("error")))));
            return;
        }
        Object children = resp.get("children");
        if (!(children instanceof JSONArray) && !(children instanceof java.util.List)) {
            host.addView(note("这条会话没有子代理"));
            return;
        }
        java.util.List<?> list = (java.util.List<?>) children;
        if (list.isEmpty()) {
            host.addView(note("这条会话没有子代理"));
            return;
        }
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) o;
            LinearLayout box = card();
            String label = str(c.get("label"));
            boolean live = Boolean.TRUE.equals(c.get("live"));
            TextView head = new TextView(this);
            head.setText((label.isEmpty() ? shortId(str(c.get("child_id"))) : label)
                    + (live ? "  ·  运行中" : "  ·  已结束"));
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            head.setTextColor(textColor());
            box.addView(head);
            String last = describeSubagentLast(c);
            box.addView(note(last.isEmpty() ? "暂无活动" : last));
            String lastText = str(c.get("last_text"));
            if (!lastText.isEmpty()) box.addView(note("最近输出：" + lastText));
            host.addView(box);
        }
    }

    private static String describeSubagentLast(Map<String, Object> c) {
        Object lastObj = c.get("last");
        if (!(lastObj instanceof Map)) return "";
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) lastObj;
        String kind = str(last.get("kind"));
        if ("text".equals(kind)) return "最近：" + str(last.get("text"));
        if ("tool".equals(kind)) return "正在运行 " + str(last.get("tool")) + "（" + str(last.get("args")) + "）";
        if ("tool-result".equals(kind)) return Boolean.TRUE.equals(last.get("error")) ? "工具失败" : "工具完成";
        if ("step".equals(kind)) return "第 " + str(last.get("step")) + " 步";
        if ("turn-end".equals(kind)) return "本轮结束";
        return "";
    }

    // ================= 写操作 =================

    private void scheduleWrite(String action, String id, JSONObject extra, String okHint) {
        new Thread(() -> {
            String err = null;
            boolean ok = false;
            try {
                Map<String, Object> r = RemoteDshTool.scheduleAction(this, action, id, extra);
                ok = Boolean.TRUE.equals(r.get("ok"));
                if (!ok) err = str(r.get("error")) + " " + str(r.get("detail"));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final boolean fOk = ok;
            final String fErr = err;
            ui.post(() -> {
                if (fOk) {
                    toast(okHint);
                    load();
                } else {
                    toast("操作失败：" + (fErr == null ? "未知" : fErr));
                }
            });
        }, "dsh-schedule").start();
    }

    private void confirmDeleteSchedule(String id, String title) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除日程")
                .setMessage("删除「" + (title.isEmpty() ? id : title) + "」？此操作会同时删除它的投递历史。")
                .setPositiveButton("删除", (d, w) -> scheduleWrite("edit", id,
                        jsonOf("edit_action", "delete"), "已删除"))
                .setNegativeButton("取消", null)
                .show();
    }

    private void planWrite(boolean active) {
        final String sid = targetSession();
        new Thread(() -> {
            String err = null;
            boolean ok = false;
            try {
                Map<String, Object> r = RemoteDshTool.setPlan(this, sid, active);
                ok = Boolean.TRUE.equals(r.get("ok"));
                if (!ok) err = str(r.get("error")) + " " + str(r.get("hint"));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final boolean fOk = ok;
            final String fErr = err;
            ui.post(() -> {
                if (fOk) {
                    toast(active ? "已进入计划模式" : "已退出计划模式");
                    load();
                } else {
                    toast("切换失败：" + (fErr == null ? "未知" : fErr));
                }
            });
        }, "dsh-plan").start();
    }

    // ================= 小工具 =================

    private TextView sectionTitle(String title, String subtitle) {
        TextView tv = new TextView(this);
        tv.setText(title + (subtitle == null ? "" : "  ·  " + subtitle));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setTextColor(textColor());
        tv.setPadding(0, dp(14), 0, dp(6));
        return tv;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x12000000);
        bg.setStroke(1, 0x22000000);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(hintColor());
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private int textColor() {
        try {
            return ThemeColors.attr(this, R.attr.colorOnSurface);
        } catch (Throwable t) {
            return 0xFF222222;
        }
    }

    private int hintColor() {
        try {
            return ThemeColors.attr(this, R.attr.colorOnSurfaceVariant);
        } catch (Throwable t) {
            return 0xFF777777;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static String statusLabel(String status) {
        if ("active".equals(status)) return "运行中";
        if ("paused".equals(status)) return "已暂停";
        if ("ended".equals(status)) return "已结束";
        return status;
    }

    private static String friendlyTime(String iso) {
        if (iso == null || iso.isEmpty()) return "—";
        try {
            // 形如 2026-11-12T09:00:00.000Z
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso);
            if (d == null) return iso;
            SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            out.setTimeZone(java.util.TimeZone.getDefault());
            return out.format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() <= 12 ? id : id.substring(0, 12) + "…";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static JSONObject jsonOf(String k, Object v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception ignored) {
            // ignore
        }
        return o;
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
