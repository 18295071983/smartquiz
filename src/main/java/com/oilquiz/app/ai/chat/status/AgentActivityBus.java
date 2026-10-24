package com.oilquiz.app.ai.chat.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行活动总线（引擎侧基础设施，2026-10-25）。
 *
 * <p>工具执行统一收口 {@code AIToolManager#executeTool} 在开始时 {@link #started(String)}、
 * 结束时 {@link #finished(String)}；聊天页的文案心跳读 {@link #current()}，
 * 得到"现在在等什么（分类）+ 从什么时候开始"，据此选多样化文案
 * （{@link AgentActivityKind#statusText(long)}）并驱动工具卡重绑。</p>
 *
 * <p>为什么用静态总线而不是给每个引擎加参数：工具执行入口分散在在线 Agent、本地 Agent、
 * 能力网关等多条路径，但最终都会落到同一个 {@code executeTool}；在这里发布一次，
 * UI 无需逐条接、也无需按 components 猜状态。生成开始/结束时聊天页会 {@link #clear()} 兜底，
 * 防止异常路径留下脏活动。</p>
 */
public final class AgentActivityBus {

    /** 一次运行中的工具活动快照。 */
    public static final class Snapshot {
        public final AgentActivityKind kind;
        public final String toolName;
        /** 工具中文别名（如"Excel 处理"）；未知工具是分类兜底（"自定义工具"） */
        public final String displayName;
        /** "正在干什么"的目标摘要（文件名/查询词等，可为空） */
        public final String target;
        public final long startTime;
        /** 入队序号：并发工具之间按"谁先开始"排序（毫秒相同也能稳定区分）。 */
        final long seq;

        Snapshot(AgentActivityKind kind, String toolName, String displayName, String target,
                 long startTime, long seq) {
            this.kind = kind;
            this.toolName = toolName;
            this.displayName = displayName;
            this.target = target;
            this.startTime = startTime;
            this.seq = seq;
        }
    }

    private static final Map<String, Snapshot> RUNNING = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private AgentActivityBus() {
    }

    /**
     * 工具开始执行（带参数：用于提取"正在干什么"的目标，如文件名/查询词）。
     * 同一工具并发调用时保留最早一次的开始时刻。
     */
    public static void started(String toolName, java.util.Map<String, Object> parameters) {
        if (toolName == null || toolName.isEmpty()) return;
        long now = System.currentTimeMillis();
        String target = extractTarget(parameters);
        RUNNING.compute(toolName, (k, old) -> old != null
                ? old
                : new Snapshot(AgentActivityKind.classify(toolName), toolName,
                        com.oilquiz.app.ai.chat.ToolMetaRegistry.displayName(toolName),
                        target, now, SEQ.incrementAndGet()));
    }

    /** 不带参数的重载（只有工具名时目标为空）。 */
    public static void started(String toolName) {
        started(toolName, null);
    }

    /** 工具结束（成功/失败都算）。 */
    public static void finished(String toolName) {
        if (toolName == null || toolName.isEmpty()) return;
        RUNNING.remove(toolName);
    }

    /** 当前最早开始的那个活动；没有工具在跑返回 null。 */
    public static Snapshot current() {
        Snapshot best = null;
        for (Snapshot s : RUNNING.values()) {
            if (best == null || s.seq < best.seq) best = s;
        }
        return best;
    }

    /**
     * 从工具参数里取一个能给用户看的"目标"：文件名/路径/查询词/网址等。
     * 只取标量、去掉目录、截断，避免把整段 JSON/长参数甩到状态行。
     */
    static String extractTarget(java.util.Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return "";
        String[] keys = {"file_name", "filename", "path", "file_path", "output",
                "title", "name", "query", "q", "url", "keyword", "text"};
        for (String key : keys) {
            Object v = parameters.get(key);
            if (!(v instanceof String)) continue;
            String s = ((String) v).trim();
            if (s.isEmpty() || s.length() > 80) continue;
            // 去掉目录，只要文件名；网址去掉协议只留域名/路径开头
            if (s.contains("/") || s.contains("\\")) {
                int idx = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
                if (idx >= 0 && idx + 1 < s.length()) s = s.substring(idx + 1);
            }
            s = s.replace("http://", "").replace("https://", "");
            if (s.length() > 18) s = s.substring(0, 18) + "…";
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** 是否有工具在跑。 */
    public static boolean isRunning() {
        return !RUNNING.isEmpty();
    }

    /** 生成开始/结束等边界兜底清空（异常路径不留脏状态）。 */
    public static void clear() {
        RUNNING.clear();
    }
}
