package com.oilquiz.app.ai.chat.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * dock 插槽的命名优先级档位（§22，2026-11-15）。
 *
 * <p><b>为什么需要它</b>：`priority` 是纯数字，数字散落在插件与 App 的十几处调用点里
 * （断线 6、压缩 10、权限 35、任务 40、MCP 55、日程 60…），改一处排序就要在两边各读一遍代码，
 * 而且「谁该压在谁上面」只存在于注释里。现在收敛成 6 个命名档位：</p>
 *
 * <table>
 *   <tr><th>档位</th><th>数值</th><th>语义</th></tr>
 *   <tr><td>p0</td><td>0</td><td>打断式：审批 / 等待用户</td></tr>
 *   <tr><td>p1</td><td>5</td><td>失败 / 重试 / 断线</td></tr>
 *   <tr><td>p2</td><td>10</td><td>权限 / 沙箱</td></tr>
 *   <tr><td>p3</td><td>20</td><td>插话队列 / 上下文压缩</td></tr>
 *   <tr><td>p4</td><td>30</td><td>目标 / 计划</td></tr>
 *   <tr><td>p5</td><td>50</td><td>后台任务 / 子代理 / 统计</td></tr>
 * </table>
 *
 * <p><b>与插件同名同值</b>：插件 `tools/dsh-plugin-smartquiz-remote/index.js` 里有一张
 * {@code SLOT_PRIORITY = {p0:0, p1:5, p2:10, p3:20, p4:30, p5:50}}；两侧必须保持
 * 同名列同一数值（{@code SlotPrioritiesTest} 与插件 `slot.test.mjs` 各钉一份）。</p>
 *
 * <p><b>兼容</b>：数值语义不变（越小越靠上），旧帧只带 `priority` 时按数值排序照旧；
 * `Slot.priority` 仍是 int，档位只是"取值的唯一来源"。未知/缺失档位回退 {@link #DEFAULT}
 * （=p4），与插件 `pushUiSlot` 的默认值一致。</p>
 */
public final class SlotPriorities {

    /** 打断式：审批 / 等待用户（最高优先级）。 */
    public static final int P0 = 0;
    /** 失败 / 重试 / 断线。 */
    public static final int P1 = 5;
    /** 权限 / 沙箱。 */
    public static final int P2 = 10;
    /** 插话队列 / 上下文压缩。 */
    public static final int P3 = 20;
    /** 目标 / 计划。 */
    public static final int P4 = 30;
    /** 后台任务 / 子代理 / 统计。 */
    public static final int P5 = 50;

    /** 未指定档位时的默认值：p4（目标/计划档）。 */
    public static final int DEFAULT = P4;

    /** 档位名 → 数值（唯一来源；不可变）。 */
    private static final Map<String, Integer> TABLE;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("p0", P0);
        m.put("p1", P1);
        m.put("p2", P2);
        m.put("p3", P3);
        m.put("p4", P4);
        m.put("p5", P5);
        TABLE = Collections.unmodifiableMap(m);
    }

    private SlotPriorities() {
    }

    /** 全部档位（档位名 → 数值；用于校验/文档/单测，不可修改）。 */
    public static Map<String, Integer> table() {
        return TABLE;
    }

    /**
     * 档位名 → 数值。
     *
     * <p>大小写与首尾空白都不敏感（`" P2 "` = p2）；未知档位（`p9`）、空串、null
     * 一律回退 {@link #DEFAULT}（p4）——与插件默认值一致，帧写错了不会把排序搞乱。</p>
     */
    public static int of(String level) {
        Integer v = valueOf(level);
        return v == null ? DEFAULT : v.intValue();
    }

    /** 是否是已知档位（供"未知 level 但有 priority"时决定用谁）。 */
    public static boolean isKnown(String level) {
        return valueOf(level) != null;
    }

    /** 档位名 → 数值；未知返回 null（不套默认值，调用方自行决定回退）。 */
    private static Integer valueOf(String level) {
        if (level == null) return null;
        String key = level.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) return null;
        return TABLE.get(key);
    }

    /**
     * 数值 → 档位名（诊断用）。
     *
     * <p>只有正好等于档位数值才命中；历史遗留的中间值（如 6 / 15 / 35 / 60）返回空串，
     * 不硬凑成"最近的档位"，免得日志误导人。</p>
     */
    public static String levelOf(int priority) {
        for (Map.Entry<String, Integer> e : TABLE.entrySet()) {
            if (e.getValue().intValue() == priority) return e.getKey();
        }
        return "";
    }

    /** 兼容旧代码的等级校验：数值是否落在已知档位上。 */
    public static boolean isTier(int priority) {
        return !levelOf(priority).isEmpty();
    }
}
