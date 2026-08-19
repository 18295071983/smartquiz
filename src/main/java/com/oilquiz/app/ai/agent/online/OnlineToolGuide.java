package com.oilquiz.app.ai.agent.online;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.Map;

/**
 * 在线模型专用工具指南 —— 独立于 {@link com.oilquiz.app.ai.tool.AIToolUsageGuide}。
 *
 * 生成原生 function calling 格式的工具使用指南（非 TOOLS_CALL 文本格式）：
 * - 原生 function calling 使用说明（无需文本格式标记）
 * - 按类别分组的工具清单
 * - 工具组合示例（从 {@link OnlineToolChain} 获取）
 * - 错误处理指引
 * - 支持按需生成单工具详细说明
 * - 带缓存机制，工具数量变化时自动刷新
 */
public class OnlineToolGuide {

    private static final String TAG = "OnlineToolGuide";

    private final OnlineToolRegistry registry;
    private final OnlineToolChain chain;
    private final OnlineToolUsageTracker usageTracker;

    /** 完整指南缓存 */
    private volatile String cachedGuide;
    private volatile int cachedToolCount = -1;
    private volatile int cachedPatternVersion = -1;

    public OnlineToolGuide(OnlineToolRegistry registry, OnlineToolChain chain) {
        this(registry, chain, null);
    }

    public OnlineToolGuide(OnlineToolRegistry registry, OnlineToolChain chain,
                           OnlineToolUsageTracker usageTracker) {
        this.registry = registry;
        this.chain = chain;
        this.usageTracker = usageTracker;
    }
    private volatile boolean cacheDirty = true;

    /** 速查缓存 */
    private volatile String cachedQuickReference;

    /** 标记缓存脏（工具或链变化时调用） */
    public void markCacheDirty() {
        cacheDirty = true;
        cachedQuickReference = null;
    }

    // ==================== 完整指南 ====================

    /**
     * 生成完整工具指南（带缓存）。
     * 工具数量变化或缓存脏时重建。
     */
    public String buildGuide() {
        int currentCount = registry.getEnabledToolCount();
        int currentPatternVersion = usageTracker != null ? usageTracker.getPatternVersion() : 0;
        if (!cacheDirty && cachedGuide != null && currentCount == cachedToolCount
                && currentPatternVersion == cachedPatternVersion) {
            return cachedGuide;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("         在线模型工具使用指南（原生 Function Calling）\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");

        // 1. 调用协议
        sb.append("【一、调用协议】\n");
        sb.append("使用原生 function calling 调用工具，无需任何文本格式标记：\n");
        sb.append("  • 系统已通过 API 的 tools 参数注册所有可用工具\n");
        sb.append("  • 直接在响应中发起 tool_calls，系统会自动执行并将结果以 tool 角色消息返回\n");
        sb.append("  • 可在一个响应中发起多个 tool_calls（并行执行）\n");
        sb.append("  • 参数为 JSON 对象，严格匹配下方工具定义的参数名与类型\n");
        sb.append("  • 必填参数缺失会导致执行失败，请确保参数完整\n\n");

        // 2. 工具清单（按类别分组，仅列工具名——描述/参数在 function 定义与 tool_registry 中，避免系统提示词冗余）
        sb.append("【二、工具清单（按类别，仅工具名，全量参考）】\n");
        Map<String, List<String>> categoryIndex = registry.getCategoryIndex();
        for (Map.Entry<String, List<String>> e : categoryIndex.entrySet()) {
            sb.append("  ▸ ").append(categoryDisplayName(e.getKey())).append(": ");
            boolean firstTool = true;
            for (String toolName : e.getValue()) {
                if (!firstTool) sb.append(", ");
                sb.append(toolName);
                firstTool = false;
            }
            sb.append("\n");
        }

        // 2.5 工具发现（MCP 式）：工具定义按消息意图裁剪注入（省 token），
        // 模型不确定工具细节时可调用 tool_registry 自行查找
        sb.append("【工具发现】当前轮的 function calling 定义已按消息意图裁剪注入（只含相关工具）。"
                + "【重要】只能调用 tools 参数中实际提供的工具（function 定义），不能调用清单里有但本轮未注入的工具——"
                + "如需清单中的其他工具，先调 tool_registry 的 get 获取其参数 schema，再决定是否调用（系统会动态补充定义）。"
                + "tool_registry 用法：list 列出全部工具（含描述）、search 按关键词查找、get 获取单个工具完整参数 schema。"
                + "UI 组件：需要用户确认/输入/选择/进度反馈/展示结构化信息时，直接用 ui_component 工具（系统原生组件弹窗 + 内置组件进聊天流，详见组件清单）。\n\n");

        // 3. 工具组合示例（静态链 + 学习到的历史模式）
        sb.append("【三、工具组合示例】\n");
        List<String[]> combos = chain.getCombinationPairs();
        if (!combos.isEmpty()) {
            for (String[] pair : combos) {
                sb.append("  • ").append(pair[0]).append(" → ").append(pair[1]).append("\n");
            }
        }
        // 自进化：注入历史高频组合（持久化统计，跨会话累积）
        List<OnlineToolUsageTracker.ToolPattern> learned = usageTracker != null
                ? usageTracker.discoverPatterns() : new java.util.ArrayList<>();
        if (!learned.isEmpty()) {
            sb.append("  （经验提示：历史对话中以下组合效果良好，可优先参考）\n");
            int shown = 0;
            for (OnlineToolUsageTracker.ToolPattern p : learned) {
                if (shown >= 5) break;
                sb.append("  • ").append(p.toolA).append(" + ").append(p.toolB)
                        .append("（用过 ").append(p.count).append(" 次）\n");
                shown++;
            }
        }
        if (combos.isEmpty() && learned.isEmpty()) {
            sb.append("  （暂无组合建议）\n");
        }
        sb.append("\n");

        // 4. 错误处理指引
        sb.append("【四、错误处理】\n");
        sb.append("  • 参数错误：系统会注入该工具的详细参数定义和缺失参数分析，请据此修正后重试\n");
        sb.append("  • 工具不适用：系统会推荐替代工具（回退链），请判断是否适合后调用\n");
        sb.append("  • 同一工具连续失败 2 次：更换策略或向用户澄清，不要无限重试\n");
        sb.append("  • 缺少前置信息时（如查天气无城市），可先调用依赖工具（如 location）补充\n\n");

        // 5. 调用规则
        sb.append("【五、调用规则】\n");
        sb.append("  1. 优先使用专用工具，而非聚合工具 app_toolkit\n");
        sb.append("  2. 文件路径必须为绝对路径（如 /storage/emulated/0/...）\n");
        sb.append("  3. 涉及权限的操作（定位/权限管理）会自动触发权限请求\n");
        sb.append("  4. 善用推理能力先思考再行动，可多轮推理和调用工具\n");
        sb.append("  5. 如果已有足够信息，直接回答用户，不要调用不必要的工具\n\n");

        sb.append("═══════════════════════════════════════════════════════\n");

        cachedGuide = sb.toString();
        cachedToolCount = currentCount;
        cachedPatternVersion = currentPatternVersion;
        cacheDirty = false;
        AILogger.i(TAG, "Built guide for " + currentCount + " tools (pattern v" + currentPatternVersion + ")");
        return cachedGuide;
    }

    // ==================== 单工具详细说明 ====================

    /**
     * 生成单工具的详细参数说明（不缓存，按需生成）。
     */
    public String buildToolDetail(String toolName) {
        OnlineToolRegistry.ToolMeta meta = registry.getToolMeta(toolName);
        if (meta == null) {
            return "工具不存在: " + toolName;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("工具详细说明: ").append(toolName).append("\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");
        sb.append("功能: ").append(meta.description).append("\n");
        sb.append("类别: ").append(categoryDisplayName(meta.category)).append("\n\n");

        sb.append("参数:\n");
        ToolDefinition def = meta.definition;
        if (def != null && def.getParameters() != null && !def.getParameters().isEmpty()) {
            for (ParamDefinition param : def.getParameters()) {
                sb.append("  - ").append(param.getName());
                sb.append(" (").append(param.getType() != null ? param.getType() : "string").append(")");
                sb.append(param.isRequired() ? " [必填]" : " [可选]");
                if (param.getDefaultValue() != null) {
                    sb.append(" [默认: ").append(param.getDefaultValue()).append("]");
                }
                sb.append("\n");
                sb.append("    说明: ").append(param.getDescription()).append("\n");
                if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                    sb.append("    枚举: ").append(String.join(" / ", param.getEnumValues())).append("\n");
                }
            }
        } else {
            sb.append("  （无参数）\n");
        }

        // 相关工具链
        List<String> fallbacks = chain.getFallbackTools(toolName);
        List<String> combinations = chain.getCombinations(toolName);
        List<String> dependencies = chain.getDependencies(toolName);
        sb.append("\n关联工具:\n");
        if (!dependencies.isEmpty()) {
            sb.append("  依赖（缺少参数时可先调用）: ").append(String.join(", ", dependencies)).append("\n");
        }
        if (!fallbacks.isEmpty()) {
            sb.append("  回退（失败时替代）: ").append(String.join(", ", fallbacks)).append("\n");
        }
        if (!combinations.isEmpty()) {
            sb.append("  组合（建议搭配使用）: ").append(String.join(", ", combinations)).append("\n");
        }
        if (dependencies.isEmpty() && fallbacks.isEmpty() && combinations.isEmpty()) {
            sb.append("  （无关联工具）\n");
        }
        sb.append("═══════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // ==================== 工具名速查 ====================

    /**
     * 生成工具名速查（一行列表，带缓存）。
     */
    public String buildQuickReference() {
        int currentCount = registry.getEnabledToolCount();
        if (cachedQuickReference != null && currentCount == cachedToolCount) {
            return cachedQuickReference;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("可用工具: ");
        boolean first = true;
        for (OnlineToolRegistry.ToolMeta meta : registry.getAllToolMetas()) {
            if (!first) sb.append(", ");
            sb.append(meta.name);
            first = false;
        }
        cachedQuickReference = sb.toString();
        return cachedQuickReference;
    }

    // ==================== 内部工具 ====================

    /** 类别英文键 → 中文显示名 */
    private String categoryDisplayName(String category) {
        if (category == null) return "其他";
        switch (category) {
            case "weather": return "天气";
            case "search": return "搜索";
            case "file": return "文件";
            case "code": return "代码/计算";
            case "translation": return "翻译";
            case "location": return "定位";
            case "database": return "数据库";
            case "system": return "系统";
            case "toolkit": return "聚合工具";
            case "meta": return "元工具";
            case "calculator": return "计算";
            default: return category;
        }
    }
}
