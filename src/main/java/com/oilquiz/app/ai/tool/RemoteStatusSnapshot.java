package com.oilquiz.app.ai.tool;

import java.util.Map;

/**
 * 电脑端 {@code /status} 的一次快照（2026-11-13）。
 *
 * <p><b>为什么单独成一个纯类</b>：状态栏要显示的是"电脑现在忙不忙、有没有待审批"，
 * 这些字段全在 `/status` 里，但 UI 不该去翻嵌套 JSON。解析与文案集中在这里，
 * 不依赖 Android，可直接单测（{@link #parse(Object)} 接受 Map 或 JSONObject）。</p>
 *
 * <p>口径来源（插件 {@code statusBody()}）：{@code cli.running/queued/max}、
 * {@code approval_relay.pending}、{@code devices}、{@code sessions_count}、
 * {@code active_jobs}、{@code version}。</p>
 */
public final class RemoteStatusSnapshot {

    public final boolean ok;
    public final String version;
    public final int cliRunning;
    public final int cliQueued;
    public final int cliMax;
    public final int approvalsPending;
    public final int devices;
    public final int sessionsCount;
    public final int activeJobs;
    public final long fetchedAt;

    public RemoteStatusSnapshot(boolean ok, String version, int cliRunning, int cliQueued, int cliMax,
                                int approvalsPending, int devices, int sessionsCount, int activeJobs,
                                long fetchedAt) {
        this.ok = ok;
        this.version = version == null ? "" : version;
        this.cliRunning = Math.max(0, cliRunning);
        this.cliQueued = Math.max(0, cliQueued);
        this.cliMax = Math.max(0, cliMax);
        this.approvalsPending = Math.max(0, approvalsPending);
        this.devices = Math.max(0, devices);
        this.sessionsCount = Math.max(0, sessionsCount);
        this.activeJobs = Math.max(0, activeJobs);
        this.fetchedAt = fetchedAt;
    }

    /** 电脑端当前执行态：运行中 / 排队 N / 空闲。 */
    public String executionText() {
        if (cliRunning > 0) return "运行中";
        if (cliQueued > 0) return "排队 " + cliQueued;
        if (activeJobs > 0) return "后台 " + activeJobs;
        return "空闲";
    }

    /**
     * 需要黄色提示的原因；空串 = 健康。
     *
     * <p>顺序即优先级：待审批最急（人不动它就一直挂着），其次排队，最后后台任务。</p>
     */
    public String warningText() {
        if (approvalsPending > 0) return "审批 " + approvalsPending;
        if (cliQueued > 0) return "排队 " + cliQueued;
        if (activeJobs > 0) return "后台 " + activeJobs;
        return "";
    }

    /** 状态条主行用：{@code 运行中 · 审批 1 · 排队 2}（健康时只有执行态）。 */
    public String headline() {
        StringBuilder sb = new StringBuilder(executionText());
        if (approvalsPending > 0) sb.append(" · 审批 ").append(approvalsPending);
        if (cliQueued > 0 && cliRunning > 0) sb.append(" · 排队 ").append(cliQueued);
        return sb.toString();
    }

    /** 解析 `/status` 响应（容错：缺字段一律 0/空，不让状态条因为某个字段改名而崩）。 */
    public static RemoteStatusSnapshot parse(Object root, long fetchedAt) {
        Object cli = child(root, "cli");
        Object approval = child(root, "approval_relay");
        return new RemoteStatusSnapshot(
                boolOf(child(root, "ok"), true),
                strOf(child(root, "version")),
                intOf(child(cli, "running"), 0),
                intOf(child(cli, "queued"), 0),
                intOf(child(cli, "max"), 0),
                intOf(child(approval, "pending"), 0),
                intOf(child(root, "devices"), 0),
                intOf(child(root, "sessions_count"), 0),
                intOf(child(root, "active_jobs"), 0),
                fetchedAt);
    }

    // ---- 容错取值：httpJson 可能给 Map，也可能是 JSONObject ----

    static Object child(Object o, String key) {
        if (o == null) return null;
        if (o instanceof Map) return ((Map<?, ?>) o).get(key);
        if (o instanceof org.json.JSONObject) return ((org.json.JSONObject) o).opt(key);
        return null;
    }

    static int intOf(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    static String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static boolean boolOf(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return "true".equalsIgnoreCase(((String) o).trim());
        return def;
    }
}
