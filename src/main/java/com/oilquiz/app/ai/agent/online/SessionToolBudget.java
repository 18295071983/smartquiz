package com.oilquiz.app.ai.agent.online;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话工具集的预算与准入（<b>纯逻辑</b>，不碰网络、不碰注册表）。
 *
 * <p>要解决的问题不是"怎么把前缀缓存调命中率"，而是<b>能力口径</b>：
 * 一个工具一旦被模型看见定义，它就有权在后续轮次里调用；把已经给出去的定义再"淘汰"掉，
 * 模型下一句就是"工具不存在"，用户看到的是 AI 突然不会用了。旧实现按
 * {@code MAX_SESSION_TOOLS=9} 做 LRU 淘汰，数量到了 9 就丢最旧的非核心工具，
 * 而同一轮里 {@code registerToolIntoSession} 又只增不裁——两处口径不一致，
 * 淘汰只在轮次开始时发生，所以真实风险是"上一轮发现的工具这一轮没了"。
 *
 * <p>现在的规则只有一条：<b>按体积准入，准入过的不再收回</b>。
 * 上限用字节而不是个数：一个 {@code mcp__github__create_issue} 的 schema 抵得上
 * 五六个 {@code time_date}，按个数封顶既容易撑爆请求，又会在该多的时候砍掉能力。
 * 满了怎么继续干活：告诉模型"工具集已满"，让它走 {@code mcp_toolbox(action=call)}
 * 代执行（不需要把定义并进来），或删掉不再用的动态工具腾位置。
 *
 * <p>核心集豁免：核心工具是入口（{@code tool_registry}/{@code mcp_toolbox} 本身就在核心集里），
 * 把它们挤掉等于把"发现新工具"的能力也关掉，那是比超限更糟的失败。
 */
public final class SessionToolBudget {

    /**
     * 工具定义段允许占的字节数（不是 token：字节是装配时就知道的确定量，token 要等服务商算）。
     *
     * <p><b>取向：能力优先，成本不设限</b>（2026-11-04 用户两次明确："把工具数量放大，
     * 不要怕花钱"、"我有百万上下文模型，不要考虑成本问题"）。400KB ≈ 10 万 tokens，
     * 在百万上下文里只吃掉约 10% 窗口，换来的是<b>模型发现过的工具永远还在</b>——
     * 旧实现按"数量 9 个 + LRU 淘汰"控制体积，实际后果是模型上一轮拿到的定义下一轮没了，
     * 再调用就是"工具不存在"，用户看到的是 AI 突然不会用。
     *
     * <p>那这个上限还有什么用？只剩一件事：防<b>异常服务端</b>一次性推上来上千个工具
     * （或某个服务商的 schema 写炸了把请求撑到几十 MB）。它是护栏，不是节流阀；
     * 撞上了也不是静默丢弃，而是把"没并进来了 + 还能怎么走"如实告诉模型。
     * 要调整只改这一个常量（唯一写者）。
     */
    public static final int MAX_TOOLSET_BYTES = 400_000;

    /**
     * 数量硬顶，同样是护栏不是预算（600 个足够容纳任何真实服务的完整目录）。
     * 正常情况下永远撞不到。
     */
    public static final int MAX_TOOLSET_TOOLS = 600;

    /** 预留给"下一个工具可能比它自己那一份估计值更大"的余量（同一工具重定义会变大） */
    public static final int ADMISSION_MARGIN_BYTES = 512;

    private SessionToolBudget() {
    }

    /** 准入结论 */
    public static final class Verdict {
        public final boolean admitted;
        public final int usedBytes;
        public final int toolCount;
        /** 拒绝原因（给日志用）；允许时为 null */
        public final String reason;
        /** 给模型看的下一步（拒绝时才非空）——只说"不行"不够，得告诉它怎么办 */
        public final String modelHint;

        private Verdict(boolean admitted, int usedBytes, int toolCount, String reason, String modelHint) {
            this.admitted = admitted;
            this.usedBytes = usedBytes;
            this.toolCount = toolCount;
            this.reason = reason;
            this.modelHint = modelHint;
        }

        static Verdict ok(int usedBytes, int toolCount) {
            return new Verdict(true, usedBytes, toolCount, null, null);
        }

        static Verdict no(int usedBytes, int toolCount, String reason, String modelHint) {
            return new Verdict(false, usedBytes, toolCount, reason, modelHint);
        }
    }

    /**
     * 判断能不能把 {@code toolName} 并进本轮工具集。
     *
     * @param current      当前已准入的工具名（顺序即请求里的顺序）
     * @param coreTools    豁免上限的核心工具（挤不动）
     * @param bytesOf      每个工具自己那份定义的字节数
     * @param toolName     想加的工具
     * @param extraBytes   该工具定义的字节数
     */
    public static Verdict admit(Collection<String> current,
                                Set<String> coreTools,
                                java.util.Map<String, Integer> bytesOf,
                                String toolName,
                                int extraBytes) {
        List<String> now = current == null ? new ArrayList<>() : new ArrayList<>(current);
        int used = totalBytes(now, bytesOf);
        if (toolName != null && now.contains(toolName)) {
            // 已在集内：定义刷新（重取 schema）不受预算约束，否则同一工具会因为变胖而被逐出
            return Verdict.ok(used, now.size());
        }
        // 核心工具豁免：核心集自己超预算也要让它进来，否则连"发现工具"的入口都没了
        boolean isCore = coreTools != null && toolName != null && coreTools.contains(toolName);
        if (isCore) {
            return Verdict.ok(used + Math.max(0, extraBytes), now.size() + 1);
        }
        int count = now.size() + (toolName == null || toolName.isEmpty() ? 0 : 1);
        if (count > MAX_TOOLSET_TOOLS) {
            return Verdict.no(used, now.size(), "数量到硬顶 " + MAX_TOOLSET_TOOLS,
                    "本轮工具集已达数量硬顶（" + MAX_TOOLSET_TOOLS + " 个）。"
                            + "这个工具的定义没有并入，但你可以立刻用 mcp_toolbox(action=call, server=…, tool=…) "
                            + "代执行它，或先删掉不再使用的动态工具腾出位置。");
        }
        long projected = (long) used + Math.max(0, extraBytes) + ADMISSION_MARGIN_BYTES;
        if (projected > MAX_TOOLSET_BYTES) {
            return Verdict.no(used, now.size(), "字节预算已满 " + used + "/" + MAX_TOOLSET_BYTES,
                    "本轮工具集已达体积上限（已用 " + used + " 字节，上限 " + MAX_TOOLSET_BYTES
                            + " 字节），" + (toolName == null ? "" : "「" + toolName + "」的定义")
                            + "没有并入，所以直接调用它会报“工具不在本轮工具集”。"
                            + "两条出路：1) 用 mcp_toolbox(action=call, server=…, tool=…, args=…) 代执行，"
                            + "不需要把定义并进来；2) 删掉不再用的动态工具（create_dynamic_tool(action=delete)）腾位置。");
        }
        return Verdict.ok(used + Math.max(0, extraBytes), count);
    }

    /** 已准入工具占用的字节（缺测量的按 0 算，宁少报不误报为已满）。 */
    public static int totalBytes(Collection<String> names, java.util.Map<String, Integer> bytesOf) {
        if (names == null) return 0;
        int sum = 0;
        for (String n : names) {
            Integer b = bytesOf == null ? null : bytesOf.get(n);
            sum += b != null ? b : 0;
        }
        return sum;
    }

    /**
     * 轮次开始时的收口：只从<b>队尾</b>丢掉超出预算的工具，且永不丢核心。
     *
     * <p>为什么丢队尾而不是 LRU 最旧的：队列是"发现顺序"，队尾是最近才并入的，
     * 模型大概率还没调用过它；而队头是这一轮真正在用的入口。LRU 淘汰会逐掉
     * 模型已经看过定义并打算继续用的工具，那是把缓存问题换成正确性问题。
     */
    public static List<String> trimToFit(List<String> ordered,
                                         Set<String> coreTools,
                                         java.util.Map<String, Integer> bytesOf) {
        List<String> out = ordered == null ? new ArrayList<>() : new ArrayList<>(ordered);
        int used = totalBytes(out, bytesOf);
        if (used <= MAX_TOOLSET_BYTES && out.size() <= MAX_TOOLSET_TOOLS) return out;
        for (int i = out.size() - 1; i >= 0 && (used > MAX_TOOLSET_BYTES || out.size() > MAX_TOOLSET_TOOLS); i--) {
            String n = out.get(i);
            if (coreTools != null && coreTools.contains(n)) continue;
            Integer b = bytesOf == null ? null : bytesOf.get(n);
            used -= b != null ? b : 0;
            out.remove(i);
        }
        return out;
    }

    /** 稳定顺序的唯一口径：核心在前（按 coreTools 给的顺序），其余按发现顺序。 */
    public static LinkedHashSet<String> orderedSet(Collection<String> names) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (names != null) out.addAll(names);
        return out;
    }

    /** 给日志的一行体积摘要。 */
    public static String describe(Collection<String> names, java.util.Map<String, Integer> bytesOf) {
        int used = totalBytes(names, bytesOf);
        int n = names == null ? 0 : names.size();
        return "tools=" + n + " 个, bytes=" + used + "/" + MAX_TOOLSET_BYTES
                + (used > MAX_TOOLSET_BYTES ? " (超)" : "");
    }
}
