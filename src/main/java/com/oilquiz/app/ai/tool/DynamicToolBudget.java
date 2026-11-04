package com.oilquiz.app.ai.tool;

/**
 * 动态工具一次调用的时间预算（<b>唯一权威</b>）。
 *
 * <p>背景：动态工具的执行逻辑是模型自己写的脚本，耗时不可控，而这条链路上以前
 * 时限是<b>三处各说各话</b>：JS 走 {@code JsExecuteTool} 时被硬写 {@code timeout=10}，
 * Python 走 {@code PythonToolManager.executeCode(code, null)} 时吃引擎默认 30s，
 * DSL 里的 {@code call_tool} 每行又能各自等 30~60s —— 一段十行的 DSL 脚本可以合法地
 * 跑几分钟。而 {@link DynamicAITool}／{@link com.oilquiz.app.ai.python.PythonDynamicTool}
 * 都不申报 {@link AITool#executionTimeoutMs}，于是调用方（
 * {@code OnlineToolManager.TOOL_TIMEOUT_MS}=30s、AgentService、AgentLoopEngine）
 * 到点放弃，脚本却还在跑：模型看到一句通用"超时"，工具那边的副作用没人收尾。
 * 这正是 {@link ToolTimeouts} 注释里记录过的同一个病，只是在动态工具这一支没被接上。
 *
 * <p>为什么是"一个预算 + 引擎各自夹"而不是"外层再套一个 Future"：
 * Python 的超时只是 join 不到 daemon 线程、WebView 的 JS 根本杀不掉，套一层 Future
 * 只会多造一个"我放弃了但线程还在"的孤儿——那等于把问题从调用方挪进本类。
 * 现在由 DSL 解释器<b>逐行自检剩余时间</b>（真能停），JS/Python 把剩余时间作为
 * 引擎超时下发（到点由引擎自己收），对外只申报"脚本时限 + 收尾余量"这一个数。
 *
 * <p>取值依据：60s 足够容纳一次嵌套 {@code call_tool}（上限同为 60s）加前后处理；
 * 再长的任务应该走可轮询的后台通道（{@code termux} / {@code remote_dsh}），
 * 而不是让一个回合干等 —— 回合预算只有 5 分钟（{@link ToolTimeouts#TURN_TOOL_BUDGET_MS}）。
 */
public final class DynamicToolBudget {

    /** 一次动态工具调用允许脚本占用的墙上时间上限（毫秒） */
    public static final long SCRIPT_BUDGET_MS = 60_000L;

    /** 申报给调用方的等待在脚本时限之外留的收尾余量（结果拼装、日志、卡片） */
    public static final long CALLER_MARGIN_MS = 5_000L;

    /** JsExecuteTool 自身允许的最大 timeout（秒），超过会被它静默夹回 30，这里提前对齐 */
    public static final int JS_ENGINE_MAX_SECONDS = 30;

    /** Python 引擎单次执行允许的最大 timeout（秒）：与引擎默认档一致，不额外放宽 */
    public static final int PYTHON_ENGINE_MAX_SECONDS = 30;

    private DynamicToolBudget() {
    }

    /** 调用方应当等待的毫秒数：脚本跑满也不会被外层提前掐断 */
    public static long callerWaitMs() {
        return SCRIPT_BUDGET_MS + CALLER_MARGIN_MS;
    }

    /**
     * 把"还剩多少时间"换算成引擎的秒级超时：至少 1 秒，最多不超过该引擎的上限。
     *
     * <p>向下取整（而不是四舍五入）：给引擎多 0.5 秒就是让脚本多占用户 0.5 秒，
     * 预算是上限不是建议。
     *
     * <p>为什么要 {@code Math.max(1, ...)}：剩余时间被前面的行吃掉到 0 时，
     * 传 0 秒给引擎会被解读成"用引擎默认"，等于绕开预算；宁可给 1 秒立即失败。
     */
    public static int engineTimeoutSeconds(long remainingMs, int engineMaxSeconds) {
        if (remainingMs <= 0) return 1;
        long secs = remainingMs / 1000L;
        return (int) Math.max(1L, Math.min((long) engineMaxSeconds, secs));
    }

    /** 本次调用已经用掉多少毫秒（从 {@code startedAtMs} 起算） */
    public static long elapsedMs(long startedAtMs) {
        return System.currentTimeMillis() - startedAtMs;
    }

    /** 还剩多少毫秒可用；不会为负 */
    public static long remainingMs(long startedAtMs) {
        long left = SCRIPT_BUDGET_MS - elapsedMs(startedAtMs);
        return left > 0 ? left : 0;
    }

    /** 预算是否已经见底（DSL 逐行自检用） */
    public static boolean exhausted(long startedAtMs) {
        return remainingMs(startedAtMs) <= 0;
    }
}
