package com.oilquiz.app.ai.agent.online.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量审批策略的选取与计划（<b>纯逻辑</b>，不碰 Android、不碰存储）。
 *
 * <p>为什么要有这个类：管理页原来只能一个一个工具点（{@code 工具 → 审批策略 → 点一项}），
 * 而现实是服务商<b>整批不发 annotations</b>，判定全靠名称/描述启发式。用户想在装完
 * 一个大服务后"把像写操作的那几个先钉成询问"，得点十几下——点得越快，他越会改成最宽的档
 * 来止损（这正是设备日志里"连点两次始终允许"的形状）。批量入口把这件事压成两次选择。
 *
 * <p>选取判据用<b>判定依据文本</b>而不是重新实现一遍推断：依据由
 * {@link McpManager#approvalBasis} 产出、同时展示给用户看，两边同源就不会出现
 * "列表里写着像写操作、勾选时却按另一套规则算"的分叉。
 */
public final class McpApprovalBatch {

    /** 预选范围：决定弹框打开时默认勾上谁 */
    public enum Preset {
        /** 所有"像写操作"的（服务商声明可破坏/非只读，或名称/描述命中写词） */
        WRITE_LIKE("像写操作的"),
        /** 所有当前判定为只读/未命中写词的 */
        READ_ONLY("判定为只读的"),
        /** 当前生效策略是"每次询问"的 */
        CURRENT_ASK("当前要询问的"),
        /** 当前生效策略是"自动允许"的 */
        CURRENT_AUTO("当前自动放行的"),
        /** 全部 */
        ALL("全部工具");

        public final String label;

        Preset(String label) {
            this.label = label;
        }
    }

    /** 一个工具的现状（由调用方从 McpManager/UI 视角取好，本类只按这些字段判断） */
    public static final class Item {
        public final String name;
        /** 当前生效策略（auto/ask/deny），不含显式与否 */
        public final String effectivePolicy;
        /** 已有的<b>显式</b>设定；空＝跟随推断 */
        public final String explicitPolicy;
        /** 判定依据文案，与列表里给用户看的那句同源 */
        public final String basis;

        public Item(String name, String effectivePolicy, String explicitPolicy, String basis) {
            this.name = name == null ? "" : name;
            this.effectivePolicy = effectivePolicy == null ? McpConstants.APPROVAL_ASK : effectivePolicy;
            this.explicitPolicy = explicitPolicy == null ? "" : explicitPolicy;
            this.basis = basis == null ? "" : basis;
        }
    }

    /** 一条将要发生的改动 */
    public static final class Change {
        public final String toolName;
        /** 改动前的显式设定（空＝原来跟随推断） */
        public final String from;
        /** 改动后的显式设定（空＝恢复跟随推断） */
        public final String to;
        /** 为什么被选中（判定依据文案） */
        public final String reason;

        Change(String toolName, String from, String to, String reason) {
            this.toolName = toolName;
            this.from = from;
            this.to = to;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return toolName + ": " + (from.isEmpty() ? "跟随推断" : from)
                    + " → " + (to.isEmpty() ? "跟随推断" : to);
        }
    }

    private McpApprovalBatch() {
    }

    /** 预选判据：命中这个档的工具。 */
    public static boolean matches(Item item, Preset preset) {
        if (item == null) return false;
        switch (preset) {
            case WRITE_LIKE:
                return looksWriteLike(item.basis);
            case READ_ONLY:
                return !looksWriteLike(item.basis);
            case CURRENT_ASK:
                return McpConstants.APPROVAL_ASK.equals(item.effectivePolicy);
            case CURRENT_AUTO:
                return McpConstants.APPROVAL_AUTO.equals(item.effectivePolicy);
            case ALL:
            default:
                return true;
        }
    }

    /**
     * 依据文案里"像写操作"的三种来源：服务商声明可破坏、声明非只读、名称/描述命中写词。
     * 注意"服务商声明只读"和"未命中写操作关键词"是反面。
     */
    private static boolean looksWriteLike(String basis) {
        if (basis == null || basis.isEmpty()) return false;
        return basis.startsWith("服务商声明可破坏")
                || basis.startsWith("服务商声明非只读")
                || basis.contains("像写操作");
    }

    /**
     * 生成改动计划：命中预选<b>且真的会变</b>的工具。
     *
     * <p>"真的会变"这条不能省：把一个已经显式设为 ask 的工具再"设为询问"，写下去只是把
     * 同一句话重写一遍，但配置表会被整份重写一次——计划里就该没有它，否则 toast 上的数量
     * 和用户心里改的东西会对不上。
     *
     * @param targetPolicy 目标显式策略；{@code ""}＝恢复"跟随默认"（清掉显式设定）
     */
    public static List<Change> plan(List<Item> items, Preset preset, String targetPolicy) {
        List<Change> out = new ArrayList<>();
        String target = normalize(targetPolicy);
        if (items == null) return out;
        for (Item item : items) {
            if (item == null || item.name.isEmpty()) continue;
            if (!matches(item, preset)) continue;
            if (target.equals(item.explicitPolicy)) continue;   // 已经是这个显式值，不重复写
            out.add(new Change(item.name, item.explicitPolicy, target, item.basis));
        }
        return out;
    }

    /** 未知/损坏的策略值一律按最安全的"询问"处理，和 {@code McpManager.normalizeApproval} 同口径。 */
    private static String normalize(String policy) {
        if (policy == null || policy.isEmpty() || "default".equalsIgnoreCase(policy)) return "";
        if (McpConstants.APPROVAL_AUTO.equals(policy)
                || McpConstants.APPROVAL_ASK.equals(policy)
                || McpConstants.APPROVAL_DENY.equals(policy)) {
            return policy;
        }
        return McpConstants.APPROVAL_ASK;
    }

    /** 给用户看的一句话摘要（改动数量 + 目标档）。 */
    public static String describe(List<Change> changes, int totalItems, Preset preset) {
        if (changes == null || changes.isEmpty()) {
            return "没有要改的（" + preset.label + "的工具已经是这一档了）";
        }
        return "将修改 " + changes.size() + " 个工具（" + preset.label + "，共 "
                + totalItems + " 个工具）";
    }
}
