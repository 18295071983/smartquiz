package com.oilquiz.app.util;

/**
 * 日志行上的<b>执行上下文前缀</b>（线程局部）。
 *
 * <p>为什么要它：日志格式是 {@code [时间][级别][tag] 正文}，没有会话/执行标识。两个会话同时跑 Agent 时，
 * 两边的 {@code OnlineAgentEngine}/{@code OnlineToolManager} 行会交错在一起，出问题时没法把
 * "这一次用户消息触发的全部行为"串成一条线——只能靠时间戳猜，而同秒多行时猜不出来。</p>
 *
 * <p>本类只存一个短前缀字符串（如 {@code s=ab12cd34 r=7f9e0011}），由
 * {@link com.oilquiz.app.ai.agent.SessionContext} 在进入会话/工具作用域时写入、离开时清除；
 * {@link AILogger} 在格式化时把它插在 tag 后面。没有作用域时输出与以前完全一致（不产生空括号）。</p>
 *
 * <p>放在 util 层而不是 agent 层，是为了让 AILogger 不必反向依赖 agent 包。</p>
 */
public final class LogContext {

    /** 前缀建议长度（session/run 各截 8 位，够区分且不刷屏） */
    public static final int DEFAULT_ID_LEN = 8;

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private LogContext() {
    }

    /** 当前线程的执行上下文前缀；无返回 null */
    public static String current() {
        return CURRENT.get();
    }

    /** 设置前缀；null/空白 = 清除 */
    public static void set(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            CURRENT.remove();
        } else {
            CURRENT.set(prefix.trim());
        }
    }

    /** 必须在使用方的 finally 里调：线程池线程会被复用，不清理会把前缀带给下一次执行 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 组装会话/执行前缀：{@code s=<session前8> r=<run前8>}；两者都空返回 null。
     * 单个 id 为空时只保留存在的那个（例如只带会话的工具线程）。
     */
    public static String prefixOf(String sessionId, String runId) {
        String s = shorten(sessionId);
        String r = shorten(runId);
        if (s == null && r == null) return null;
        StringBuilder sb = new StringBuilder();
        if (s != null) sb.append("s=").append(s);
        if (r != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("r=").append(r);
        }
        return sb.toString();
    }

    private static String shorten(String id) {
        if (id == null) return null;
        String v = id.trim();
        if (v.isEmpty()) return null;
        return v.length() <= DEFAULT_ID_LEN ? v : v.substring(0, DEFAULT_ID_LEN);
    }
}
