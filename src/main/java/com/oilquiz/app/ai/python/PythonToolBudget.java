package com.oilquiz.app.ai.python;

import com.oilquiz.app.ai.tool.ToolTimeouts;

/**
 * Python 系工具的超时口径（申报公式的<b>单一写者</b>）。
 *
 * <p>要解决的问题：调用方 {@code OnlineToolManager} 对所有工具套 30 秒默认超时，而
 * {@code PythonToolManager} 各入口自己的执行预算是 10/30/60 秒不等（写在各自的调用点上）。
 * 只要"工具内预算 ≥ 框架超时"，就一定是框架先掐断：模型拿到的是通用文案
 * "工具 X 执行超时（已等待 30s）"，而 Python 端产出的 stdout / {@code TimeoutError} /
 * {@code environment_error} 结构全部丢掉，只能盲目重试。{@code python_chart} 最典型 ——
 * 它自己写了 60 秒预算，实际永远只能跑到 30 秒。
 *
 * <p>修法：工具自己经 {@code AITool.executionTimeoutMs(args)} 申报"预算 + 余量"，
 * 调用方取"默认值与申报值的较大者"（并受全局硬上限约束）。这套机制 remote_dsh 已在用
 * （见 {@code RemoteDshTool#executionTimeoutMs}），这里把 Python 系全部接上并共用同一公式，
 * 避免六个工具各写一份余量、日后各自漂移。
 *
 * <p>注意：申报只是<b>把掐断权还给工具自己</b>，不会把工作时长拉长 ——
 * 脚本仍然在自己的预算到点时停下，区别只在于模型看到的是哪一种超时。
 */
public final class PythonToolBudget {

    /**
     * 申报时加的余量（秒）：预算只覆盖 {@code run_code} 里那段脚本，之外还有 Chaquopy 调用、
     * 命名空间准备、缺失依赖的安装轮次（自动修复每轮都会先扫一遍 import）与结果序列化。
     * 不留这层，"工具内不超时"仍然是假话。
     */
    public static final int MARGIN_SECONDS = 20;

    /** 申报硬上限不再自写一份：与两条执行路径共用 {@link ToolTimeouts#MAX_TOOL_TIMEOUT_MS} */
    private PythonToolBudget() {
    }

    /** 由本工具的执行预算（秒）算出要申报给调用方的毫秒数 */
    public static long declaredMs(int budgetSeconds) {
        return Math.min(ToolTimeouts.MAX_TOOL_TIMEOUT_MS, (budgetSeconds + MARGIN_SECONDS) * 1000L);
    }
}
