package com.oilquiz.app.ai.agent;

/**
 * 一次工具执行所归属的<b>会话作用域</b>（线程局部）。
 *
 * <p><b>为什么需要它</b>：引擎不是单例（对话页、AgentRouter、调试桥各能建一个），两个会话同时跑
 * Agent 时，凡是"全局可变"的东西都会互相踩：{@code AgentWorkspace} 的 tmp 是个共享目录，
 * 谁先结束谁清空；{@code TaskStateTracker.activeSession} 是单值，后启动的会话把前一个顶掉，
 * 任务就记到别人名下。这类串味的共同点是：<b>真正的归属信息（sessionId）在调用栈里丢了，
 * 只能从全局状态里"猜"</b>。</p>
 *
 * <p>本类把归属信息显式带下来：引擎在提交工具执行时把 sessionId 交给
 * {@code OnlineToolManager}，后者在真正执行工具的那个线程上 {@link #set}，执行完 {@link #clear}。
 * 工具侧（tmp 目录、任务清单、用量记录）优先读这里，读不到才回落到全局缺省——所以单会话场景
 * 行为与以前完全一致，多会话场景才体现差别。</p>
 *
 * <p>刻意<b>不</b>做成 Android 的 Context/ThreadLocal 组合，也不做成全局 map：
 * 作用域只在"工具执行期间"这个极短窗口存在，用完即清，不会随线程池线程泄漏到下一次调用。</p>
 */
public final class SessionContext {

    /** 清洗后最长保留的会话键长度（避免文件路径过长/日志刷屏） */
    private static final int MAX_KEY_LEN = 48;

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionContext() {
    }

    /** 当前线程所属会话；无作用域返回 null（调用方应回落到全局缺省） */
    public static String current() {
        return CURRENT.get();
    }

    /** 设置当前线程的会话；传 null/空 = 清除作用域 */
    public static void set(String sessionId) {
        String s = sanitizeOrNull(sessionId);
        if (s == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(s);
        }
    }

    /** 清除作用域（必须在 finally 里调，线程池线程会被复用） */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 归一化会话键：空 → null；非 {@code [A-Za-z0-9_-]} 一律换成下划线并截断。
     *
     * <p>会话 id 会进文件路径（{@code tmp/s_<key>}），必须在这里就挡掉路径分隔符与 {@code ..}，
     * 否则一个外部可影响的 id 就能把临时目录指到工作区外面去。</p>
     */
    public static String sanitizeOrNull(String sessionId) {
        if (sessionId == null) return null;
        String s = sessionId.trim();
        if (s.isEmpty()) return null;
        String cleaned = s.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (cleaned.length() > MAX_KEY_LEN) cleaned = cleaned.substring(0, MAX_KEY_LEN);
        return cleaned.isEmpty() ? null : cleaned;
    }
}
