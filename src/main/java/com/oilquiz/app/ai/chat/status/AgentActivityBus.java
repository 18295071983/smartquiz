package com.oilquiz.app.ai.chat.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行活动总线（引擎侧基础设施，2026-10-25）。
 *
 * <p>工具执行统一收口 {@code AIToolManager#executeTool} 在开始时 {@link #started(String, Map)}
 * （返回本次会话号）、结束时 {@link #finished(String, long)}；聊天页的文案心跳读 {@link #current()}，
 * 得到"现在在等什么（分类）+ 从什么时候开始 + 具体目标"，据此选多样化文案
 * （{@link AgentActivityKind#statusText(long)}）并驱动工具卡重绑。</p>
 *
 * <p>为什么用静态总线而不是给每个引擎加参数：工具执行入口分散在在线 Agent、本地 Agent、
 * 能力网关等多条路径，但最终都会落到同一个 {@code executeTool}；在这里发布一次，
 * UI 无需逐条接、也无需按 components 猜状态。每轮生成开始 {@link #newSession()} 换会话号，
 * 旧会话里迟到的工具结束回调不会误删新会话的活动（例如"停止后立刻重试同一个工具"）。</p>
 */
public final class AgentActivityBus {

    /** 一次运行中的工具活动快照。 */
    public static final class Snapshot {
        public final AgentActivityKind kind;
        public final String toolName;
        /** 工具中文别名（如"Excel 处理"）；未知工具是轻度润色的英文名 */
        public final String displayName;
        /** "正在干什么"的目标摘要（文件名/查询词等，可为空） */
        public final String target;
        public final long startTime;
        /** 所属生成会话号：只有当前会话的活动才展示 */
        final long session;
        /** 入队序号：并发工具之间按"谁先开始"排序（毫秒相同也能稳定区分）。 */
        final long seq;

        Snapshot(AgentActivityKind kind, String toolName, String displayName, String target,
                 long startTime, long session, long seq) {
            this.kind = kind;
            this.toolName = toolName;
            this.displayName = displayName;
            this.target = target;
            this.startTime = startTime;
            this.session = session;
            this.seq = seq;
        }
    }

    private static final Map<String, Snapshot> RUNNING = new ConcurrentHashMap<>();
    /**
     * 同名工具的嵌套/并发深度（key 含会话号）：executeTool 可能被工具内部再次调用（同名），
     * 只记录最外层快照，内层 finished 不能把外层提前清掉。
     */
    private static final Map<String, Integer> DEPTH = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicLong SESSION = new AtomicLong();

    private AgentActivityBus() {
    }

    /** 当前生成会话号（工具开始/结束配对用）。 */
    public static long currentSession() {
        return SESSION.get();
    }

    /**
     * 新一轮生成：换会话号并清空，旧会话里迟到的 {@link #finished(String, long)}
     * 不会误删新会话里同名工具的活动。
     */
    public static void newSession() {
        SESSION.incrementAndGet();
        RUNNING.clear();
        DEPTH.clear();
    }

    /**
     * 工具开始执行（带参数：用于提取"正在干什么"的目标，如文件名/查询词）。
     *
     * @return 本次会话号，结束后原样传给 {@link #finished(String, long)}
     */
    public static long started(String toolName, Map<String, Object> parameters) {
        long session = SESSION.get();
        if (toolName == null || toolName.isEmpty()) return session;
        try {
            String key = key(session, toolName);
            // 同名嵌套：只保留最外层快照（内层 started 不覆盖，内层 finished 不减到 0 不清）
            int depth = DEPTH.merge(key, 1, Integer::sum);
            if (depth > 1) return session;
            long now = System.currentTimeMillis();
            final String target = extractTarget(parameters);
            final String displayName;
            try {
                displayName = com.oilquiz.app.ai.chat.ToolMetaRegistry.displayName(toolName, parameters);
            } catch (Throwable t) {
                // 展示名兜底失败也不影响记账
                RUNNING.compute(key, (k, old) -> old != null ? old
                        : new Snapshot(AgentActivityKind.classify(toolName, parameters), toolName,
                        "Tool", target, now, session, SEQ.incrementAndGet()));
                return session;
            }
            RUNNING.compute(key, (k, old) -> old != null
                    ? old
                    : new Snapshot(AgentActivityKind.classify(toolName, parameters), toolName,
                            displayName, target, now, session, SEQ.incrementAndGet()));
        } catch (Throwable ignored) {
            // 总线只是展示辅助：任何异常都不能冒泡进工具执行链路
        }
        return session;
    }

    /** 不带参数的重载（只有工具名时目标为空）。 */
    public static long started(String toolName) {
        return started(toolName, null);
    }

    /**
     * 工具结束（成功/失败都算），用开始时的会话号配对；未知/空名静默忽略。
     * 不属于当前会话的迟到回调只清自己那条记录，不会碰新会话。
     */
    public static void finished(String toolName, long session) {
        if (toolName == null || toolName.isEmpty()) return;
        try {
            String key = key(session, toolName);
            Integer depth = DEPTH.get(key);
            if (depth == null) {
                RUNNING.remove(key);
                return;
            }
            if (depth > 1) {
                DEPTH.put(key, depth - 1);
                return;
            }
            DEPTH.remove(key);
            RUNNING.remove(key);
        } catch (Throwable ignored) {
        }
    }

    /** 兼容重载：没有捕获会话号的旧调用按当前会话处理。 */
    public static void finished(String toolName) {
        finished(toolName, SESSION.get());
    }

    /** 当前会话里最早开始的那个活动；没有工具在跑返回 null（不抛异常）。 */
    public static Snapshot current() {
        try {
            long session = SESSION.get();
            Snapshot best = null;
            for (Snapshot s : RUNNING.values()) {
                if (s.session != session) continue;
                if (best == null || s.seq < best.seq) best = s;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从工具参数里取一个能给用户看的"目标"：文件名/路径/查询词/网址等。
     * 只取标量、去掉目录、去控制字符、截断，避免把整段 JSON/长参数甩到状态行。
     * 任何异常都返回空串（不影响工具执行）。
     */
    static String extractTarget(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return "";
        try {
            String[] keys = {"file_name", "filename", "path", "file_path", "output",
                    "title", "name", "query", "q", "url", "keyword", "text"};
            for (String key : keys) {
                Object v = parameters.get(key);
                if (!(v instanceof String)) continue;
                String s = sanitize(((String) v).trim());
                if (s.isEmpty() || s.length() > 80) continue;
                // 去掉目录，只要文件名；网址去掉协议只留域名/路径开头
                if (s.contains("/") || s.contains("\\")) {
                    int idx = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
                    if (idx >= 0 && idx + 1 < s.length()) {
                        s = sanitize(s.substring(idx + 1));
                    }
                }
                s = s.replace("http://", "").replace("https://", "");
                if (s.length() > 18) s = s.substring(0, 18) + "…";
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 去掉换行/控制字符：目标只用于单行状态文案，不能带换行把布局撑乱 */
    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 当前会话是否有工具在跑。 */
    public static boolean isRunning() {
        return current() != null;
    }

    /** 生成结束等边界兜底清空（异常路径不留脏状态；保留会话号）。 */
    public static void clear() {
        RUNNING.clear();
        DEPTH.clear();
    }

    private static String key(long session, String toolName) {
        return session + "|" + toolName;
    }
}
