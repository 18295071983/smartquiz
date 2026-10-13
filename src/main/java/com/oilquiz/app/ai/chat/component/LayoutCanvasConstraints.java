package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.python.NativeLayoutRenderer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态布局画布（layout_canvas / layout_editor）的约束层。
 *
 * <p>此前这些规则只写在 prompt 里当"铁律"，或藏在可选的
 * {@code layout_editor(action=validate)} dry-run 里——{@code set}/{@code add}/{@code patch}
 * 三条写入路径**一条都不校验**，于是出现过：未知控件类型直接把画布渲染成
 * "⚠ 画布渲染失败"的死弹窗、两个输入控件同 key 导致提交时值被静默覆盖、
 * 一次 set 塞进几千节点把主线程重渲染拖到 ANR、模型反复重建画布并存多个弹窗。</p>
 *
 * <p>本层把规则变成代码级强制，策略是<b>能修则修、修不了才拒</b>：
 * <ul>
 *   <li><b>自动修复</b>：输入控件缺 {@code key} 时按类型补一个唯一 key（回填到结果文本里，
 *       让模型知道实际键名），避免一次"报错→重试"的往返；</li>
 *   <li><b>硬拒绝</b>：结构非法（复用 {@link NativeLayoutRenderer#validate}）、key 重复、
 *       节点/子项/深度/体积预算超限、编辑频率超限——错误精确到节点路径，
 *       且<b>不改动画布现状</b>（在候选副本上校验，通过后才提交）；</li>
 *   <li><b>只告警</b>：button 缺 action 等"能渲染但交互不完整"的问题。</li>
 * </ul>
 *
 * <p>回退：开关 {@code ai_chat_prefs.layout_canvas_constraints}（默认开），
 * 关闭后行为与加入本层之前完全一致。</p>
 */
public final class LayoutCanvasConstraints {

    private static final String TAG = "LayoutCanvasConstr";

    /** 约束开关（ai_chat_prefs，默认开） */
    public static final String PREFS_NAME = "ai_chat_prefs";
    public static final String PREF_KEY_ENABLED = "layout_canvas_constraints";

    // ---------- 预算（都是实测友好值，超限即拒绝并给出拆分建议） ----------
    /** 单画布控件节点上限 */
    public static final int MAX_NODES = 150;
    /** 单个容器 children 上限（超出建议用 tabs/accordion/scroll 分页） */
    public static final int MAX_CHILDREN_PER_CONTAINER = 40;
    /** 嵌套深度上限（与 NativeLayoutRenderer.validate 同口径） */
    public static final int MAX_DEPTH = 8;
    /** layout JSON 文本体积上限（字符数） */
    public static final int MAX_LAYOUT_CHARS = 200_000;
    /** 同时存活的画布数上限（治理"反复重建画布"） */
    public static final int MAX_ACTIVE_CANVASES = 3;
    /** 同一画布每秒编辑次数上限（每次编辑都会整树重渲染） */
    public static final int MAX_EDITS_PER_SECOND = 5;
    /** 遍历硬上限：远大于 MAX_DEPTH，只为挡 StackOverflow */
    private static final int HARD_MAX_TRAVERSE_DEPTH = 64;

    /** 需要 key 才能收集值的控件类型（值收集按 key 建 Map，重复/缺失都会丢数据） */
    public static final Set<String> INPUT_TYPES = new HashSet<>(Arrays.asList(
            "input", "number", "password", "multiline", "otp", "email", "tel", "url",
            "search", "search_bar", "tag_input", "select", "switch", "checkbox",
            "checkbox_group", "radio", "radio_group", "date", "time", "datetime",
            "color", "rating", "toggle", "dropdown", "stepper", "slider_range", "slider"));

    /** 容器类布局节点（用于统计"单容器子项数"） */
    public static final Set<String> CONTAINER_TYPES = new HashSet<>(Arrays.asList(
            "column", "row", "scroll", "card", "wrap", "grid", "tabs", "stack",
            "accordion", "carousel"));

    private LayoutCanvasConstraints() {
    }

    // ==================== 开关 ====================

    public static boolean enabled(Context context) {
        if (context == null) {
            return true;
        }
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(Context context, boolean value) {
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_KEY_ENABLED, value).apply();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 检查结果 ====================

    /** 一次约束检查的结果：errors=必须拒绝，warnings/repairs=已放行但要回给模型看 */
    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> repairs = new ArrayList<>();
        /** 实际节点数（供结果文案与预算提示） */
        public int nodes;
        /** 实际最大深度 */
        public int maxDepth;

        public boolean ok() {
            return errors.isEmpty();
        }

        /** 给模型看的完整说明（错误在前，修复与告警在后） */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!errors.isEmpty()) {
                sb.append("布局不符合画布约束，已拒绝（画布保持原样）:\n")
                        .append(joinLines(errors));
            }
            if (!repairs.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("已自动修复:\n").append(joinLines(repairs));
            }
            if (!warnings.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("提示:\n").append(joinLines(warnings));
            }
            return sb.toString();
        }

        private static String joinLines(List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append("  - ").append(list.get(i));
            }
            return sb.toString();
        }
    }

    // ==================== 全量检查（含就地自动修复） ====================

    /**
     * 校验一棵候选布局树，并在可自动修复处就地改写（如补 key）。
     *
     * <p>调用方必须传<b>副本</b>：本方法会 mutate 传入的 JSON，未通过时不能污染现有画布。</p>
     *
     * @param layout 候选布局（{"root":{...}} 或单节点）
     * @return 检查结果；{@link Report#ok()} 为 false 时调用方应拒绝写入并把
     *         {@link Report#describe()} 返回给模型
     */
    public static Report inspect(Context context, JSONObject layout) {
        Report rep = new Report();
        if (layout == null) {
            rep.errors.add("布局为空");
            return rep;
        }
        // 1) 体积
        String raw;
        try {
            raw = layout.toString();
        } catch (Throwable t) {
            rep.errors.add("布局无法序列化: " + t.getMessage());
            return rep;
        }
        if (raw.length() > MAX_LAYOUT_CHARS) {
            rep.errors.add("布局 JSON 过大（" + raw.length() + " 字符，上限 "
                    + MAX_LAYOUT_CHARS + "）；请拆成多张画布或用 tabs/accordion 分页");
            return rep;
        }

        // 2) 结构：复用渲染器同款校验（未知类型/children 非对象/root 非对象/深度>8），
        //    放行已注册组件类型名与 use 模板引用，避免误杀合法嵌套。
        Set<String> known = knownComponentNames(context);
        String structErr = NativeLayoutRenderer.validate(layout, known);
        if (structErr != null) {
            rep.errors.add(structErr);
        }

        // 3) 预算 + 键唯一性 + 输入控件 key / button action
        JSONObject root = layout.optJSONObject("root");
        if (root == null) {
            root = layout;
        }
        Set<String> keys = new HashSet<>();
        int[] counters = new int[]{0, 0}; // [0]=nodes [1]=maxDepth
        walk(context, root, "root", 0, keys, counters, rep, known);
        rep.nodes = counters[0];
        rep.maxDepth = counters[1];

        if (rep.nodes > MAX_NODES) {
            rep.errors.add("节点数超限：当前 " + rep.nodes + " 个，单画布上限 " + MAX_NODES
                    + "；请用 tabs/accordion 分页或拆成多张画布");
        }
        return rep;
    }

    private static void walk(Context context, JSONObject node, String path, int depth,
                             Set<String> keys, int[] counters, Report rep, Set<String> known) {
        if (node == null) {
            return;
        }
        // 递归自我保护：结构校验报"嵌套过深"后仍会继续走这棵树，
        // 模型给出的畸形深嵌套（或互相引用）会把调用线程的栈打爆（StackOverflowError 不是 Exception，
        // 上层 catch(Exception) 拦不住），这里硬截断
        if (depth > HARD_MAX_TRAVERSE_DEPTH) {
            rep.errors.add(path + ": 嵌套深度超过硬上限 " + HARD_MAX_TRAVERSE_DEPTH + "，已停止遍历");
            return;
        }
        counters[0]++;
        if (depth + 1 > counters[1]) {
            counters[1] = depth + 1;
        }
        String type = node.optString("type", "column");

        // key 唯一性（值收集是 Map by key，重复会静默覆盖用户输入）
        String key = node.optString("key", "");
        if (!key.isEmpty()) {
            if (!keys.add(key)) {
                rep.errors.add(path + ": key '" + key + "' 重复（同名控件的值会互相覆盖，提交时丢数据）");
            }
        } else if (INPUT_TYPES.contains(type)) {
            // 自动修复：补一个唯一 key，并把键名回给模型
            String generated = generateKey(type, keys);
            try {
                node.put("key", generated);
                keys.add(generated);
                rep.repairs.add(path + ": 输入控件 " + type + " 缺 key，已自动补为 key=\""
                        + generated + "\"（收集/提交时用这个键名）");
            } catch (Throwable t) {
                rep.errors.add(path + ": 输入控件 " + type + " 缺 key（自动补全失败: "
                        + t.getMessage() + "）");
            }
        }
        if ("button".equals(type) && !node.has("action")) {
            rep.warnings.add(path + ": button 缺 action，用户点击不会回传结果"
                    + "（需要收集值请加 action，如 \"submit\"）");
        }

        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            if (children.length() > MAX_CHILDREN_PER_CONTAINER
                    && (CONTAINER_TYPES.contains(type) || depth == 0)) {
                rep.errors.add(path + "(" + type + "): 子节点 " + children.length()
                        + " 个超过单容器上限 " + MAX_CHILDREN_PER_CONTAINER
                        + "；请用 tabs/accordion/wrap 分组");
            }
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                if (c == null) {
                    continue; // 结构非法已由 NativeLayoutRenderer.validate 报出
                }
                walk(context, c, path + "/children/" + i, depth + 1, keys, counters, rep, known);
            }
        }
        // 未注册类型自带 layout（现场展开渲染）：其内部同样要算预算与键唯一性
        if (!known.contains(type) && !node.has("use")) {
            JSONObject inner = node.optJSONObject("layout");
            if (inner != null) {
                JSONObject innerRoot = inner.optJSONObject("root");
                walk(context, innerRoot != null ? innerRoot : inner,
                        path + "/layout", depth + 1, keys, counters, rep, known);
            }
        }
    }

    /** 生成不与现有 key 冲突的自动键名 */
    private static String generateKey(String type, Set<String> used) {
        String base = type.replaceAll("[^a-z0-9_]", "");
        for (int i = 1; i < 1000; i++) {
            String candidate = "auto_" + base + "_" + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return "auto_" + System.currentTimeMillis();
    }

    /** 已注册/可嵌套引用的组件名集合（类型表 + 插件表 + layout 模板表） */
    public static Set<String> knownComponentNames(Context context) {
        Set<String> known = new HashSet<>();
        if (context == null) {
            return known;
        }
        try {
            known.addAll(com.oilquiz.app.ai.tool.UIComponentTypeRegistry
                    .getInstance(context).typeNames());
        } catch (Throwable ignored) {
        }
        try {
            known.addAll(com.oilquiz.app.ai.tool.UIComponentPluginManager
                    .getInstance(context).pluginNames());
        } catch (Throwable ignored) {
        }
        try {
            known.addAll(com.oilquiz.app.ai.python.LayoutTemplateRegistry
                    .getInstance(context).templateNames());
        } catch (Throwable ignored) {
        }
        return known;
    }

    // ==================== 画布尺寸（模型可自选） ====================

    /** 尺寸取值下限/上限（dp 或屏幕比例），越界一律夹取而不是拒绝，避免模型一次写错就开不出窗 */
    public static final int MIN_CANVAS_WIDTH_DP = 240;
    public static final int MIN_CANVAS_HEIGHT_DP = 200;
    /** 宽度最大占屏比例 */
    public static final float MAX_CANVAS_WIDTH_RATIO = 0.94f;
    /** 高度最大占屏比例（留出状态栏/导航栏与对话框边距） */
    public static final float MAX_CANVAS_HEIGHT_RATIO = 0.92f;
    /** 未指定时的默认画布高度比例（与历史行为一致） */
    public static final float DEFAULT_CANVAS_HEIGHT_RATIO = 0.72f;

    /** 解析后的画布尺寸意图（dp 为单位，<=0 表示未指定） */
    public static final class CanvasSize {
        /** 窗口宽（dp）；<=0 用对话框默认宽 */
        public int widthDp;
        /** 内容区固定高（dp）；wrapHeight=true 时无意义 */
        public int heightDp;
        /** 内容区高度上限（dp）；wrapHeight=true 时用它做封顶 */
        public int maxHeightDp;
        /** true=高度随内容自适应（封顶 maxHeightDp） */
        public boolean wrapHeight;
        /** 模型是否显式指定过尺寸（false 时宿主保持原默认行为） */
        public boolean specified;
        /** 给模型的回告：取值被夹取/纠正的说明 */
        public String note = "";

        @Override
        public String toString() {
            return "CanvasSize{widthDp=" + widthDp + ", heightDp=" + heightDp
                    + ", maxHeightDp=" + maxHeightDp + ", wrapHeight=" + wrapHeight + "}";
        }
    }

    /**
     * 从组件 props 解析模型自选的画布尺寸。
     *
     * <p>可用键（都可放顶层或 props 内，由 ui_component 的通用参数透传并入 props）：
     * {@code canvas_width} / {@code canvas_height} / {@code canvas_max_height}，
     * 取值支持：dp 数字（如 420）、比例（"0.8" 或 "80%"）、关键字
     * （{@code full|max}=贴屏上限，{@code half}=半屏，{@code auto|wrap}=随内容自适应）。</p>
     *
     * <p>越界只夹取不拒绝：尺寸写歪不该让画布开不出来。</p>
     *
     * @param props   组件 props（可能为 null）
     * @param context 用于读屏幕尺寸与 density
     */
    public static CanvasSize resolveCanvasSize(JSONObject props, Context context) {
        CanvasSize size = new CanvasSize();
        if (props == null || context == null) {
            return size;
        }
        float[] screenDp = screenDp(context);
        float screenW = screenDp[0];
        float screenH = screenDp[1];
        StringBuilder note = new StringBuilder();

        int w = readDp(props, "canvas_width", screenW, MIN_CANVAS_WIDTH_DP,
                (int) (screenW * MAX_CANVAS_WIDTH_RATIO), note, "宽");
        int h = readDp(props, "canvas_height", screenH, MIN_CANVAS_HEIGHT_DP,
                (int) (screenH * MAX_CANVAS_HEIGHT_RATIO), note, "高");
        int maxH = readDp(props, "canvas_max_height", screenH, MIN_CANVAS_HEIGHT_DP,
                (int) (screenH * MAX_CANVAS_HEIGHT_RATIO), note, "最大高");

        String hRaw = String.valueOf(props.opt("canvas_height"));
        if (isWrapKeyword(hRaw)) {
            size.wrapHeight = true;
            size.maxHeightDp = maxH > 0 ? maxH : (int) (screenH * DEFAULT_CANVAS_HEIGHT_RATIO);
            size.specified = true;
        } else {
            size.heightDp = h;
            size.maxHeightDp = maxH;
            if (h > 0 || maxH > 0) {
                size.specified = true;
            }
        }
        size.widthDp = w;
        if (w > 0) {
            size.specified = true;
        }
        size.note = note.toString();
        return size;
    }

    private static boolean isWrapKeyword(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase();
        return "auto".equals(s) || "wrap".equals(s) || "content".equals(s) || "自适应".equals(s);
    }

    /** 读一个尺寸参数：支持 dp 数字 / "0.8" / "80%" / full|max|half；非法值忽略并记说明 */
    private static int readDp(JSONObject props, String key, float screenDp, int minDp, int maxDp,
                              StringBuilder note, String label) {
        Object raw = props.opt(key);
        if (raw == null) {
            return 0;
        }
        int value;
        if (raw instanceof Number) {
            // JSON 里 0.8 是数字（不是字符串）：0<v<=1 按屏幕比例理解，否则按 dp
            double d = ((Number) raw).doubleValue();
            value = d > 0d && d <= 1d ? (int) (screenDp * d) : (int) d;
        } else {
            String s = String.valueOf(raw).trim().toLowerCase();
            if (s.isEmpty() || "null".equals(s)) {
                return 0;
            }
            if ("full".equals(s) || "max".equals(s) || "全屏".equals(s)) {
                value = maxDp;
            } else if ("half".equals(s) || "半屏".equals(s)) {
                value = (int) (screenDp * 0.5f);
            } else if (isWrapKeyword(s)) {
                return 0; // 自适应由调用方按高度处理
            } else if (s.endsWith("%")) {
                try {
                    value = (int) (screenDp * (Float.parseFloat(s.substring(0, s.length() - 1)) / 100f));
                } catch (Exception e) {
                    note.append(key + " 取值无法识别（" + s + "），已忽略；");
                    return 0;
                }
            } else {
                try {
                    float f = Float.parseFloat(s);
                    // 纯小数按屏幕比例理解（0.8 → 80% 屏宽），整数按 dp
                    value = f > 0f && f <= 1f ? (int) (screenDp * f) : (int) f;
                } catch (Exception e) {
                    note.append(key + " 取值无法识别（" + s + "），已忽略；");
                    return 0;
                }
            }
        }
        if (value < minDp) {
            note.append(label).append(" ").append(value).append("dp 低于下限，已按 ").append(minDp).append("dp；");
            return minDp;
        }
        if (value > maxDp) {
            note.append(label).append(" ").append(value).append("dp 超出上限，已按 ").append(maxDp).append("dp；");
            return maxDp;
        }
        return value;
    }

    /** 屏幕宽高（dp）与 density：返回 [widthDp, heightDp, density] */
    public static float[] screenDp(Context context) {
        try {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            float density = dm.density <= 0f ? 1f : dm.density;
            return new float[]{dm.widthPixels / density, dm.heightPixels / density, density};
        } catch (Throwable t) {
            return new float[]{360f, 640f, 1f};
        }
    }

    // ==================== 编辑频率节流 ====================

    private static final ConcurrentHashMap<String, Deque<Long>> EDIT_TIMES =
            new ConcurrentHashMap<>();

    /**
     * 同一画布的编辑节流检查（只读窗口，不计数）。
     *
     * <p>刻意与 {@link #recordEdit} 分开：被约束拒绝的编辑<b>不该</b>消耗配额，
     * 否则模型连续几次改错就会撞上"编辑过于频繁"这条误导性文案，
     * 看不到真正的结构错误。</p>
     *
     * @return null=允许本次编辑；非 null=拒绝文案
     */
    public static String throttleEdit(String componentId) {
        if (componentId == null || componentId.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = EDIT_TIMES.get(componentId);
        if (times == null) {
            return null;
        }
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 1000L) {
                times.pollFirst();
            }
            if (times.size() >= MAX_EDITS_PER_SECOND) {
                return "编辑过于频繁（1 秒内已 " + times.size() + " 次，上限 " + MAX_EDITS_PER_SECOND
                        + " 次）：每次编辑都会整树重渲染，请把多处改动合并成一次 layout_editor(action=set) 整树替换";
            }
            return null;
        }
    }

    /** 一次编辑<b>成功生效</b>后记账（与 {@link #throttleEdit} 配对） */
    public static void recordEdit(String componentId) {
        if (componentId == null || componentId.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = EDIT_TIMES.get(componentId);
        if (times == null) {
            times = new ArrayDeque<>();
            Deque<Long> existing = EDIT_TIMES.putIfAbsent(componentId, times);
            if (existing != null) {
                times = existing;
            }
        }
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 1000L) {
                times.pollFirst();
            }
            times.addLast(now);
        }
    }

    /** 画布关闭时清理节流记录，避免键无限增长 */
    public static void forgetCanvas(String componentId) {
        if (componentId != null) {
            EDIT_TIMES.remove(componentId);
        }
    }

    // ==================== 活跃画布上限 ====================

    /**
     * 是否还能再开一张画布。超限说明模型在反复重建画布（prompt 明令禁止但此前无约束），
     * 每张画布都是一个 AlertDialog + 会话 + 控件树，必须限住。
     */
    public static String checkActiveCanvasBudget(int currentActive) {
        if (currentActive >= MAX_ACTIVE_CANVASES) {
            return "已存在 " + currentActive + " 张活跃画布（上限 " + MAX_ACTIVE_CANVASES
                    + "）：请复用同一个 component_id 用 layout_editor 编辑，"
                    + "或先用 ui_component(action=close) 关掉不需要的画布";
        }
        return null;
    }

    // ==================== 提交辅助 ====================

    /** 深拷贝（在副本上校验，通过后再换入画布，保证失败不破坏现状） */
    public static JSONObject copyOf(JSONObject src) {
        if (src == null) {
            return null;
        }
        try {
            return new JSONObject(src.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 记录一次被拒绝的写入（排查用） */
    public static void logRejected(String action, String componentId, Report rep) {
        if (rep == null || rep.ok()) {
            return;
        }
        Log.w(TAG, "rejected " + action + " on canvas " + componentId
                + " errors=" + rep.errors);
    }

    /** 供结果文案引用：把 Map 里的键排序后拼接（模型侧稳定输出） */
    public static List<String> sortedKeys(Map<String, Object> map) {
        List<String> out = new ArrayList<>();
        if (map != null) {
            out.addAll(map.keySet());
            java.util.Collections.sort(out);
        }
        return out;
    }
}
