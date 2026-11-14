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
 *
 * <p>2026-11-13 扩展：合并 {@code /v2/usage} 的 token/上下文/模型（{@link #withUsage}），
 * 并记录一次 {@code /status} 往返耗时当 RTT。</p>
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

    // ---- 2026-11-13：/v2/usage 合并进来的字段 ----
    public final boolean usageOk;
    public final int pressureTokens;
    public final int contextWindow;
    public final int totalTokens;
    public final String model;
    public final String effort;
    public final int cacheHitPct;
    public final long rttMs;
    public final String serviceVersion;

    public RemoteStatusSnapshot(boolean ok, String version, int cliRunning, int cliQueued, int cliMax,
                                int approvalsPending, int devices, int sessionsCount, int activeJobs,
                                long fetchedAt) {
        this(ok, version, cliRunning, cliQueued, cliMax, approvalsPending, devices, sessionsCount,
                activeJobs, fetchedAt, false, 0, 0, 0, "", "", 0, 0L, "");
    }

    public RemoteStatusSnapshot(boolean ok, String version, int cliRunning, int cliQueued, int cliMax,
                                int approvalsPending, int devices, int sessionsCount, int activeJobs,
                                long fetchedAt, boolean usageOk, int pressureTokens, int contextWindow,
                                int totalTokens, String model, String effort, int cacheHitPct, long rttMs,
                                String serviceVersion) {
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
        this.usageOk = usageOk;
        this.pressureTokens = Math.max(0, pressureTokens);
        this.contextWindow = Math.max(0, contextWindow);
        this.totalTokens = Math.max(0, totalTokens);
        this.model = model == null ? "" : model;
        this.effort = effort == null ? "" : effort;
        this.cacheHitPct = Math.max(0, Math.min(100, cacheHitPct));
        this.rttMs = Math.max(0L, rttMs);
        this.serviceVersion = serviceVersion == null ? "" : serviceVersion;
    }

    /** 合并 {@code /v2/usage} 响应（失败就保留主快照原样）。 */
    public RemoteStatusSnapshot withUsage(Object usage, long rtt) {
        if (usage == null) return this;
        return new RemoteStatusSnapshot(ok, version, cliRunning, cliQueued, cliMax, approvalsPending,
                devices, sessionsCount, activeJobs, fetchedAt,
                boolOf(child(usage, "ok"), false),
                intOf(child(usage, "pressure_tokens"), 0),
                intOf(child(usage, "context_window"), 0),
                intOf(child(usage, "total_tokens"), 0),
                // usage 没带就保留 /status 那份（模型名），别把已有值清空
                strOf(child(usage, "model")).isEmpty() ? model : strOf(child(usage, "model")),
                strOf(child(usage, "effort")).isEmpty() ? effort : strOf(child(usage, "effort")),
                intOf(child(usage, "cache_hit_pct"), 0),
                rtt,
                version);
    }

    /** 电脑端当前执行态：运行中 / 排队 N / 空闲。 */
    public String executionText() {
        if (cliRunning > 0) return "运行中";
        if (cliQueued > 0) return "排队 " + cliQueued;
        if (activeJobs > 0) return "后台 " + activeJobs;
        return "空闲";
    }

    /** 需要黄色提示的原因；空串 = 健康。 */
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

    /** 上下文占用（有 contextWindow 才算得出百分比）。 */
    public String contextText() {
        if (!usageOk || pressureTokens <= 0 || contextWindow <= 0) return "";
        int pct = (int) Math.max(0, Math.min(100, Math.round(pressureTokens * 100.0 / contextWindow)));
        return "上下文 " + pct + "%";
    }

    /** 本轮 token（>=1K 用 K 表示）。 */
    public String tokensText() {
        if (!usageOk || totalTokens <= 0) return "";
        if (totalTokens < 1000) return totalTokens + " tok";
        return String.format(java.util.Locale.US, "%.1fK tok", totalTokens / 1000.0);
    }

    /** 缓存命中率（有数据才显示）。 */
    public String cacheText() {
        return usageOk && cacheHitPct > 0 ? "缓存 " + cacheHitPct + "%" : "";
    }

    public boolean hasUsage() {
        return usageOk && (pressureTokens > 0 || totalTokens > 0);
    }

    /** 解析 `/status` 响应（容错：缺字段一律 0/空，不让状态条因为某个字段改名而崩）。 */
    public static RemoteStatusSnapshot parse(Object root, long fetchedAt) {
        return parse(root, fetchedAt, 0L);
    }

    /** 同上，额外记录一次 /status 往返耗时（状态条的 RTT）。 */
    public static RemoteStatusSnapshot parse(Object root, long fetchedAt, long rttMs) {
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
                fetchedAt,
                // 模型名先从 /status 的 acp_agent 拿（2026-11-14：/v2/usage 目前不带 model，
                // 但"输入框下方那行"要显示电脑在用什么模型）
                false, 0, 0, 0,
                strOf(child(child(root, "acp_agent"), "model")),
                "", 0, rttMs, "");
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
