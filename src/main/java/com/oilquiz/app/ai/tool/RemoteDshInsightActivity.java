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
    /** 是否已铺上"壁纸 + 遮罩"：此时前景必须用浅色（35% 黑遮罩之上深字读不出来） */
    private boolean onWallpaper = false;

    // ---- 折叠状态与最近一次数据（点"展开已完成"时就地重绘，不用再拉一次网络） ----
    private boolean showDoneJobs = false;
    private Map<String, Object> lastSchedule;
    private Map<String, Object> lastPlan;
    private Map<String, Object> lastSubs;
    private Map<String, Object> lastJobs;
    private Map<String, Object> lastGoal;
    private Map<String, Object> lastMcp;
    private Map<String, Object> lastPerm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_dsh_insight);
        host = findViewById(R.id.insight_host);
        status = findViewById(R.id.insight_status);
        View refresh = findViewById(R.id.btn_insight_refresh);
        if (refresh != null) refresh.setOnClickListener(v -> load());
        // WALLPAPER-SCRIM(2026-11-13)：这个页面原先没接应用壁纸，壁纸原图直接透上来，
        // 深色照片上压深色文字整屏读不清。改用 AppWallpaperManager 的"壁纸 + 35% 黑遮罩"
        // （与主页/聊天页同一套）；接了壁纸时前景色切成浅色，没有壁纸时退回确定性色板。
        View root = findViewById(R.id.insight_root);
        onWallpaper = com.oilquiz.app.theme.AppWallpaperManager.applyTo(this, root);
        if (!onWallpaper && root != null) root.setBackgroundColor(bgColor());
        View content = findViewById(android.R.id.content);
        if (content != null && !onWallpaper) content.setBackgroundColor(bgColor());
        TextView title = findViewById(R.id.insight_title);
        if (title != null) title.setTextColor(textColor());
        if (status != null) status.setTextColor(hintColor());
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
            Map<String, Object> jobs = null;
            Map<String, Object> goal = null;
            Map<String, Object> mcp = null;
            Map<String, Object> perm = null;
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
            // P3(2026-11-13)：后台任务 / 目标由插件从会话事件派生（/v2/jobs、/v2/goal）
            RemoteSdk sdk = new RemoteSdk(this);
            try {
                jobs = sdk.jobs(sid);
            } catch (Exception e) {
                if (error == null) error = "后台任务：" + e.getMessage();
            }
            try {
                goal = sdk.goal(sid);
            } catch (Exception e) {
                if (error == null) error = "目标：" + e.getMessage();
            }
            try {
                mcp = sdk.mcp(sid);
            } catch (Exception e) {
                if (error == null) error = "MCP：" + e.getMessage();
            }
            try {
                perm = sdk.permission();
            } catch (Exception e) {
                if (error == null) error = "权限：" + e.getMessage();
            }
            final Map<String, Object> fSchedule = schedule;
            final Map<String, Object> fPlan = plan;
            final Map<String, Object> fSubs = subs;
            final Map<String, Object> fJobs = jobs;
            final Map<String, Object> fGoal = goal;
            final Map<String, Object> fMcp = mcp;
            final Map<String, Object> fPerm = perm;
            final String fError = error;
            ui.post(() -> {
                loading = false;
                render(fSchedule, fPlan, fSubs, fJobs, fGoal, fMcp, fPerm, fError);
            });
        }, "dsh-insight").start();
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text == null ? "" : text);
    }

    // ================= 渲染 =================

    private void render(Map<String, Object> schedule, Map<String, Object> plan,
                        Map<String, Object> subs, Map<String, Object> jobs,
                        Map<String, Object> goal, Map<String, Object> mcp,
                        Map<String, Object> perm, String error) {
        if (host == null) return;
        lastSchedule = schedule;
        lastPlan = plan;
        lastSubs = subs;
        lastJobs = jobs;
        lastGoal = goal;
        lastMcp = mcp;
        lastPerm = perm;
        host.removeAllViews();

        StringBuilder st = new StringBuilder();
        if (error != null) st.append(error);
        // UI-REFRESH：状态类（任务/目标/权限）在前，事件类（日程/计划/子代理）在中，操作在最后
        appendJobsSection(jobs, st);
        appendGoalSection(goal, st);
        appendPermissionSection(perm, st);
        appendScheduleSection(schedule, st);
        appendPlanSection(plan, st);
        appendSubagentSection(subs, st);
        appendMcpSection(mcp, st);
        appendClientActions(st);
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        setStatus(st.length() == 0 ? ("已更新 · " + stamp) : (st + " · " + stamp));
    }

    /**
     * 权限（沙箱 / 档位 / 审批策略）：由电脑端自己的事件上报，手机只读。
     *
     * <p>这三样决定"电脑能替你做多危险的事"——一眼可见比藏在卡片里重要。</p>
     */
    private void appendPermissionSection(Map<String, Object> resp, StringBuilder st) {
        host.addView(sectionTitle("权限（电脑端）", null));
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note(resp == null ? "取不到权限状态" : "权限不可用：" + str(resp.get("error"))));
            return;
        }
        String sandbox = str(resp.get("sandbox"));
        String preset = str(resp.get("preset"));
        String policy = str(resp.get("policy"));
        if (sandbox.isEmpty() && preset.isEmpty() && policy.isEmpty()) {
            host.addView(note("电脑端还没上报权限状态（等一次会话事件后自动出现）"));
            return;
        }
        LinearLayout box = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        if (!sandbox.isEmpty()) {
            row.addView(chip("沙箱 " + sandbox,
                    "danger-full-access".equals(sandbox) ? TONE_BAD : TONE_NEUTRAL));
            row.addView(gap());
        }
        if (!preset.isEmpty()) {
            row.addView(chip("档位 " + preset, TONE_NEUTRAL));
            row.addView(gap());
        }
        if (!policy.isEmpty()) {
            row.addView(chip("审批 " + policy, TONE_NEUTRAL));
        }
        box.addView(row);
        host.addView(box);
    }

    /** chip 之间的水平间距 */
    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    /**
     * 客户端可发起的选项（白名单）：清空任务列表、重建时间线。
     *
     * <p>这两条都是"手机上能自救"的动作：任务列表看花了可以清；时间线错乱了可以重建。
     * 计划开关与日程执行仍走各自已有入口，不在这里重复开口子。</p>
     */
    private void appendClientActions(StringBuilder st) {
        host.addView(sectionTitle("客户端操作", null));
        LinearLayout box = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(compactButton("清空任务列表", false,
                v -> runAction("jobs.clear", "已清空任务列表")));
        row.addView(compactButton("重建时间线", false,
                v -> runAction("resync", "已请求重建")));
        box.addView(row);
        box.addView(note("这两条只影响手机上的显示：清空任务列表不会动电脑端正在跑的任务；"
                + "重建时间线会让对话按电脑端水位重新对齐。"));
        host.addView(box);
    }

    private void runAction(String action, String okHint) {
        final String act = action;
        new Thread(() -> {
            try {
                Map<String, Object> r = new RemoteSdk(this).action(act, null);
                boolean ok = Boolean.TRUE.equals(r.get("ok"));
                ui.post(() -> {
                    toast(ok ? okHint : ("失败：" + str(r.get("error"))));
                    if (ok) load();
                });
            } catch (Exception e) {
                ui.post(() -> toast("失败：" + e.getMessage()));
            }
        }, "dsh-action").start();
    }

    /**
     * MCP 服务：只列**本次会话真正调用过**的服务（插件从 {@code mcp__<server>__<tool>} 派生）。
     *
     * <p>刻意不显示"已连接 N 个"——插件拿不到连接表，硬编一个数只会骗人。</p>
     */
    private void appendMcpSection(Map<String, Object> resp, StringBuilder st) {
        Object serversObj = resp != null && Boolean.TRUE.equals(resp.get("ok")) ? resp.get("servers") : null;
        int total = serversObj instanceof java.util.List ? ((java.util.List<?>) serversObj).size() : 0;
        host.addView(sectionTitle("MCP 服务（本次会话用过）", total > 0 ? (total + " 个") : null));
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note(resp == null ? "取不到 MCP 状态" : "MCP 不可用：" + str(resp.get("error"))));
            return;
        }
        if (total == 0) {
            host.addView(note("本次会话还没调用过 MCP 工具"));
            return;
        }
        // 服务名很短，用 chip 横排比"每个服务一张卡"紧凑得多
        LinearLayout box = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (Object o : (java.util.List<?>) serversObj) {
            row.addView(chip(String.valueOf(o), TONE_NEUTRAL));
            row.addView(gap());
        }
        box.addView(row);
        host.addView(box);
    }

    /** 后台任务（/v2/jobs，插件从 job/registered|settled 事件派生的状态）。 */
    private void appendJobsSection(Map<String, Object> resp, StringBuilder st) {
        Object itemsObj = resp != null && Boolean.TRUE.equals(resp.get("ok")) ? resp.get("items") : null;
        int total = itemsObj instanceof java.util.List ? ((java.util.List<?>) itemsObj).size() : 0;
        host.addView(sectionTitle("后台任务（电脑端）", total > 0 ? (total + " 条") : null));
        if (resp == null) {
            host.addView(note("取不到后台任务（电脑端插件版本较旧？）"));
            return;
        }
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note("后台任务不可用：" + str(resp.get("error"))));
            return;
        }
        if (total == 0) {
            host.addView(note("暂无后台任务"));
            return;
        }
        // GROUP-JOBS(2026-11-13)：运行中的直接列；已完成的默认折成一行"已完成 N 条"。
        // 原来 20 条全铺出来，正在跑的任务反而被一屏历史压到下面。
        java.util.List<Map<String, Object>> running = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> finished = new java.util.ArrayList<>();
        for (Object o : (java.util.List<?>) itemsObj) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> j = (Map<String, Object>) o;
            if ("running".equals(str(j.get("status")))) running.add(j);
            else finished.add(j);
        }
        int shown = 0;
        for (Map<String, Object> j : running) {
            if (shown >= SHOW_MAX) break;
            host.addView(jobRow(j));
            shown += 1;
        }
        if (!finished.isEmpty()) {
            if (!showDoneJobs) {
                host.addView(jobGroupToggle(finished.size()));
            } else {
                for (Map<String, Object> j : finished) {
                    if (shown >= SHOW_MAX) break;
                    host.addView(jobRow(j));
                    shown += 1;
                }
                capNote(host, shown, total);
                host.addView(jobGroupToggleCollapse(finished.size()));
            }
        }
    }

    private View jobRow(Map<String, Object> j) {
        LinearLayout box = card();
        String label = str(j.get("label"));
        String status = str(j.get("status"));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label.isEmpty() ? "（未命名）" : label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(textColor());
        head.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(chip(jobStatusLabel(status),
                "running".equals(status) ? TONE_OK
                        : ("failed".equals(status) ? TONE_BAD : TONE_NEUTRAL)));
        box.addView(head);
        return box;
    }

    /** "已完成 N 条 · 点击展开"：点了就地重绘（不重新拉网络）。 */
    private View jobGroupToggle(int count) {
        TextView tv = new TextView(this);
        tv.setText("已完成 " + count + " 条 · 点击展开");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(accentColor());
        tv.setPadding(dp(4), dp(10), dp(4), dp(10));
        tv.setOnClickListener(v -> {
            showDoneJobs = true;
            rerender();
        });
        return tv;
    }

    private View jobGroupToggleCollapse(int count) {
        TextView tv = new TextView(this);
        tv.setText("收起已完成的 " + count + " 条");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(hintColor());
        tv.setPadding(dp(4), dp(8), dp(4), dp(4));
        tv.setOnClickListener(v -> {
            showDoneJobs = false;
            rerender();
        });
        return tv;
    }

    /** 用最近一次数据重绘（折叠/展开用，不触发网络请求）。 */
    private void rerender() {
        render(lastSchedule, lastPlan, lastSubs, lastJobs, lastGoal, lastMcp, lastPerm, null);
    }

    /**
     * 目标（/v2/goal）：2026-11-15 体检后不再是只读。
     *
     * <p>暂停/恢复/清除都走 {@code /v2/action}，插件转 DSH GoalService 的
     * pause/resume/clear（live agent + exact revision）；失败会明确显示原因。</p>
     */
    private void appendGoalSection(Map<String, Object> resp, StringBuilder st) {
        Object g = resp != null && Boolean.TRUE.equals(resp.get("ok")) ? resp.get("goal") : null;
        host.addView(sectionTitle("目标（电脑端）", null));
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note(resp == null ? "取不到目标状态" : "目标不可用：" + str(resp.get("error"))));
            return;
        }
        if (!(g instanceof Map)) {
            host.addView(note("当前没有进行中的目标"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> goal = (Map<String, Object>) g;
        boolean active = Boolean.TRUE.equals(goal.get("active"));
        boolean disarmed = "disarmed".equals(str(goal.get("activation")));
        String phase = str(goal.get("phase"));
        LinearLayout box = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(str(goal.get("title")).isEmpty() ? "（未命名目标）" : str(goal.get("title")));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(textColor());
        head.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(chip(active ? "进行中" : "已结束", active ? TONE_OK : TONE_NEUTRAL));
        box.addView(head);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (!"complete".equals(phase)) {
            boolean paused = !active || disarmed;
            actions.addView(compactButton(paused ? "恢复" : "暂停", false,
                    v -> goalWrite(paused ? "goal.resume" : "goal.pause")));
        }
        actions.addView(compactButton("清除", true, v -> goalWrite("goal.clear")));
        box.addView(actions);
        host.addView(box);
    }

    /** 目标操作：POST /v2/action（goal.pause|resume|clear），完成后重拉一次面板。 */
    private void goalWrite(final String action) {
        final String sid = targetSession();
        if (sid.isEmpty()) {
            setStatus("还没有跟随的会话，无法操作目标");
            return;
        }
        new Thread(() -> {
            String err = null;
            Map<String, Object> r = null;
            try {
                java.util.Map<String, Object> extra = new java.util.HashMap<>();
                extra.put("session_id", sid);
                extra.put("caps", "cmd.ui");
                r = new RemoteSdk(this).action(action, extra);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Map<String, Object> fR = r;
            final String fErr = err;
            ui.post(() -> {
                boolean ok = fR != null && Boolean.TRUE.equals(fR.get("ok"));
                if (!ok) {
                    String why = fR == null ? (fErr == null ? "电脑端不可达" : fErr)
                            : str(fR.get("hint")) + " " + str(fR.get("error"));
                    setStatus("目标操作失败：" + why.trim());
                }
                load();
            });
        }, "dsh-goal-write").start();
    }

    private static String jobStatusLabel(String status) {
        if ("running".equals(status)) return "运行中";
        if ("failed".equals(status)) return "失败";
        if ("done".equals(status)) return "完成";
        return status == null || status.isEmpty() ? "未知" : status;
    }

    private void appendScheduleSection(Map<String, Object> resp, StringBuilder st) {
        Object tasksObj = resp != null && Boolean.TRUE.equals(resp.get("ok")) ? resp.get("tasks") : null;
        int total = tasksObj instanceof java.util.List ? ((java.util.List<?>) tasksObj).size() : 0;
        host.addView(sectionTitle("日程（电脑端定时任务）", total > 0 ? (total + " 条") : null));
        if (resp == null) {
            host.addView(note("取不到日程（电脑端插件可能没有 schedule 服务）"));
            return;
        }
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note("日程不可用：" + str(resp.get("error")) + " " + str(resp.get("hint"))));
            return;
        }
        if (total == 0) {
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
            if (shown >= SHOW_MAX) break;
        }
        capNote(host, shown, total);
    }

    /** 每节最多铺这么多行；再多就只提示总数（避免长列表把别的分区推走） */
    private static final int SHOW_MAX = 8;

    private View scheduleRow(Map<String, Object> task) {
        LinearLayout box = card();
        String title = str(task.get("title"));
        String kind = str(task.get("kind"));
        String id = str(task.get("id"));
        String state = str(task.get("status"));
        String at = str(task.get("scheduledAt"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(title.isEmpty() ? "（未命名）" : title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(textColor());
        head.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(chip(statusLabel(state), "active".equals(state) ? TONE_OK : TONE_NEUTRAL));
        box.addView(head);

        TextView meta = note("类型 " + kind + " · 下次 " + friendlyTime(at)
                + (str(task.get("executionActive")).isEmpty() ? "" : " · 正在执行"));
        box.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(compactButton("执行一次", false,
                v -> scheduleWrite("run", id, null, "已触发执行")));
        actions.addView(compactButton("删除", true,
                v -> confirmDeleteSchedule(id, title)));
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
        Object children = resp != null && Boolean.TRUE.equals(resp.get("ok")) ? resp.get("children") : null;
        int total = children instanceof java.util.List ? ((java.util.List<?>) children).size() : 0;
        host.addView(sectionTitle("子代理", total > 0 ? (total + " 个") : null));
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) {
            host.addView(note(resp == null ? "取不到子代理" : ("子代理不可用：" + str(resp.get("error")))));
            return;
        }
        if (!(children instanceof java.util.List) || total == 0) {
            host.addView(note("这条会话没有子代理"));
            return;
        }
        int shown = 0;
        for (Object o : (java.util.List<?>) children) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) o;
            LinearLayout box = card();
            String label = str(c.get("label"));
            boolean live = Boolean.TRUE.equals(c.get("live"));
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(label.isEmpty() ? shortId(str(c.get("child_id"))) : label);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            name.setTextColor(textColor());
            head.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(chip(live ? "运行中" : "已结束", live ? TONE_OK : TONE_NEUTRAL));
            box.addView(head);
            String last = describeSubagentLast(c);
            box.addView(note(last.isEmpty() ? "暂无活动" : last));
            String lastText = str(c.get("last_text"));
            if (!lastText.isEmpty()) box.addView(note("最近输出：" + lastText));
            host.addView(box);
            shown += 1;
            if (shown >= SHOW_MAX) break;
        }
        capNote(host, shown, total);
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

    /**
     * 分区标题：左侧主题色竖条 + 标题 + 可选计数徽标。
     *
     * <p>UI-REFRESH(2026-11-13)：原来只是一行 15sp 纯文本，八个分区从头排到尾，
     * 读起来像一大段日志。竖条给"分区起点"一个视觉锚点，徽标让"这一节有几条"一眼可见。</p>
     */
    private View sectionTitle(String title, String badge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(6));

        View accent = new View(this);
        accent.setBackgroundColor(accentColor());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(3), dp(14));
        alp.rightMargin = dp(8);
        row.addView(accent, alp);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(textColor());
        row.addView(tv);

        if (badge != null && !badge.isEmpty()) {
            TextView b = chip(badge, TONE_NEUTRAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(8);
            row.addView(b, blp);
        }
        return row;
    }

    /** 状态徽标配色：中性 / 正常 / 提醒 / 错误 */
    private static final int TONE_NEUTRAL = 0;
    private static final int TONE_OK = 1;
    private static final int TONE_WARN = 2;
    private static final int TONE_BAD = 3;

    private static int toneBase(int tone) {
        switch (tone) {
            case TONE_OK: return 0xFF2E7D32;
            case TONE_WARN: return 0xFFB26A00;
            case TONE_BAD: return 0xFFC62828;
            default: return 0xFF808080;
        }
    }

    /**
     * 状态胶囊：底色/字色都由"主题文字色 + 语义色"混合得出。
     *
     * <p>不写死颜色是为了深色模式也能读：浅色主题下接近深色语义色，深色主题下自动变浅。</p>
     */
    private TextView chip(String text, int tone) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        int base = toneBase(tone);
        tv.setTextColor(blend(textColor(), base, tone == TONE_NEUTRAL ? 0.35f : 0.6f));
        android.graphics.drawable.GradientDrawable bgDrawable = new android.graphics.drawable.GradientDrawable();
        bgDrawable.setCornerRadius(dp(8));
        bgDrawable.setColor(blend(surfaceColor(), base, 0.16f));
        tv.setBackground(bgDrawable);
        tv.setPadding(dp(7), dp(2), dp(7), dp(2));
        return tv;
    }

    /** 卡片：改用主题 surface（原来固定 7% 黑，在深色模式/悬浮窗压暗时几乎读不出）。 */
    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(surfaceColor());
        bg.setStroke(1, outlineColor());
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    /** 紧凑按钮：默认 Material 按钮在每张卡里都撑到 48dp，八个分区叠起来非常松。 */
    private Button compactButton(String text, boolean danger, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(34));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setTextColor(danger ? blend(textColor(), 0xFFC62828, 0.7f) : accentColor());
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setStroke(1, blend(surfaceColor(), danger ? 0xFFC62828 : accentColor(), 0.5f));
        bg.setColor(0x00000000);
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** 长列表封顶：超过 max 条只列前 max 条，并明确写"共 N 条"，避免把别的分区推走。 */
    private void capNote(LinearLayout parent, int shown, int total) {
        if (total > shown) {
            parent.addView(note("仅显示前 " + shown + " 条 · 共 " + total + " 条（更多请到电脑端看）"));
        }
    }

    /** 系统是否深色模式（决定用哪套固定色板）。 */
    private boolean night() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int accentColor() {
        return onWallpaper ? 0xFFC9BEFF : (night() ? 0xFFA79BFF : 0xFF5B4BDB);
    }

    /** 页面背景：确定性色板，深/浅各一套（有壁纸时不用它）。 */
    private int bgColor() {
        return night() ? 0xFF121016 : 0xFFF5F7FB;
    }

    /** 卡片底色：有壁纸时用半透明深色，让壁纸透一点但文字仍然清楚。 */
    private int surfaceColor() {
        if (onWallpaper) return 0xB3121016;
        return night() ? 0xFF1E1B26 : 0xFFFFFFFF;
    }

    private int outlineColor() {
        if (onWallpaper) return 0x40FFFFFF;
        return night() ? 0xFF2E2A3A : 0xFFE5E7EB;
    }

    /** 颜色混合：t=0 取 base，t=1 取 over。 */
    private static int blend(int base, int over, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        int r = (int) (android.graphics.Color.red(base) * (1 - u) + android.graphics.Color.red(over) * u);
        int g = (int) (android.graphics.Color.green(base) * (1 - u) + android.graphics.Color.green(over) * u);
        int b = (int) (android.graphics.Color.blue(base) * (1 - u) + android.graphics.Color.blue(over) * u);
        return android.graphics.Color.argb(255, r, g, b);
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
        if (onWallpaper) return 0xFFF4F2FA;
        return night() ? 0xFFE8E6EF : 0xFF1F2937;
    }

    private int hintColor() {
        if (onWallpaper) return 0xFFCFCBDB;
        return night() ? 0xFFA5A1B3 : 0xFF6B7280;
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
