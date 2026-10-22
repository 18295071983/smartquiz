package com.oilquiz.app.ai.tool;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具的副作用口径（<b>单一写者</b>）：哪些工具是只读/幂等的。
 *
 * <p>这一个集合同时决定两件事，因为它们的判据本来就是同一个"重复执行有没有后果"：
 * <ol>
 *   <li><b>结果能不能缓存复用</b> —— 有副作用的工具（写库、生成文件、执行 shell/Python、
 *       操作应用、UI 交互）一旦按 "工具名+参数" 缓存，第二次同样调用会被<b>静默跳过</b>：
 *       模型以为又跑了一遍，实际拿到的是上次的旧结果。</li>
 *   <li><b>超时后能不能重试</b> —— 超时不等于没执行。命令可能已经跑完一半只是没来得及回传，
 *       此时重试就是把写操作做第二遍（重复导入、重复生成、重复删除）。</li>
 * </ol>
 *
 * <p>为什么要抽出来：这套判定原先只写在 {@code OnlineToolManager} 里，而对话页另一条执行路径
 * {@code AgentService} 有自己的一份规则 —— 它对<b>除 ui_component 外的所有工具</b>都缓存，
 * 且对任何工具超时都一律重试两次。结果是同一个 {@code linux_shell} / {@code python_execute}
 * / {@code database} 调用，在一条路径上安全、在另一条路径上会被跳过或做两遍。
 * 现在两条路径都问这里。
 */
public final class ToolSideEffects {

    /**
     * 只读/幂等工具白名单（<b>白名单而非黑名单</b>：新工具默认视为有副作用，
     * 要缓存/重试得先显式加进来，避免默认值把副作用工具漏放进来）。
     */
    private static final Set<String> READONLY_IDEMPOTENT_TOOLS = new HashSet<>(Arrays.asList(
            "ai_weather", "network_search", "webpage_reader", "smart_research",
            "location", "time_date", "calculator", "python_calculate",
            "file_reader", "file_analyzer", "tool_registry", "get_models_profile"
    ));

    private ToolSideEffects() {
    }

    /** 该工具是否只读/幂等：可安全缓存复用，也可在超时后重试 */
    public static boolean isReadOnlyIdempotent(String toolName) {
        return toolName != null && READONLY_IDEMPOTENT_TOOLS.contains(toolName);
    }

    /** 该工具是否有副作用：不缓存结果，超时不重试 */
    public static boolean hasSideEffects(String toolName) {
        return !isReadOnlyIdempotent(toolName);
    }
}
