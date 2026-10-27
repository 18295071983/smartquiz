package com.oilquiz.app.ai.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 空转/重复调用检测：同一批工具被反复调用时打断，避免"50 轮烧 token"和重复的副作用。
 *
 * <p><b>为什么不直接"同参就去重"</b>：轮询型调用本来就会用相同参数反复调
 * （{@code knowledge_base(import_status)}、{@code ui_component(get_result)} 等），
 * 按参数去重会把合法的长任务直接判死。所以这里按两件事分流：</p>
 * <ul>
 *   <li><b>同一轮内</b>、同参、且工具<b>有副作用</b> → 直接不执行（==SKIP）。同一轮模型把
 *       删文件写了两遍是最危险的一种，这一条是硬拦；只读工具照常执行（结果缓存本来就会命中）。</li>
 *   <li><b>跨轮</b>：连续若干轮里，所有"有副作用调用"都是重复（且本轮确实有副作用调用）→ 累计；
 *       到 {@link #HINT_AFTER_ROUNDS} 注入提示、到 {@link #STOP_AFTER_ROUNDS} 强制收尾。
 *       只读/轮询调用不计入，也不会因为它们的重复而触发收尾。</li>
 * </ul>
 *
 * <p>key 归一化：工具名 + 参数 JSON 的<b>规范化</b>形式（对象键排序，递归），避免"同一参数、
 * 键序不同"绕过检测。</p>
 *
 * <p>纯逻辑、不依赖 Context/线程，可直接单测（见 {@code ToolCallDeduperTest}）。</p>
 */
public final class ToolCallDeduper {

    /** 连续这么多轮"全是重复的副作用调用"→ 注入一次提示 */
    public static final int HINT_AFTER_ROUNDS = 2;
    /** 连续这么多轮 → 建议强制收尾（调用方决定怎么收） */
    public static final int STOP_AFTER_ROUNDS = 3;

    /** 一次调用的判定结果 */
    public enum Verdict {
        /** 本轮第一次出现，正常执行 */
        NEW,
        /** 本轮重复（只读/轮询类）——照常执行，但计入"本轮有进展" */
        REPEAT_EXECUTED,
        /** 本轮重复且工具带副作用——不应执行 */
        SKIP
    }

    /** 本轮内出现过的 key（判"同一轮重复"用） */
    private final Map<String, Integer> seenThisRound = new HashMap<>();
    /** 本回合（一次用户消息）内出现过的 key（判"跨轮是否有进展"用） */
    private final Map<String, Integer> seenThisTurn = new HashMap<>();
    /** 本轮是否有过"非重复的副作用调用"（有=本轮有进展） */
    private boolean roundHadNewSideEffect;
    private boolean roundHadSideEffect;
    /** 连续"全是重复副作用调用"的轮数 */
    private int consecutiveAllDuplicateRounds;

    /** 每轮开头调用（重置本轮视图） */
    public void beginRound() {
        seenThisRound.clear();
        roundHadNewSideEffect = false;
        roundHadSideEffect = false;
    }

    /**
     * 判一次工具调用。
     *
     * @param toolName   工具名
     * @param arguments  原始参数 JSON（可为 null/空）
     * @param sideEffect 是否有副作用（{@code !ToolSideEffects.isReadOnlyIdempotent(name)}）
     * @param pollingLike 是否为轮询型调用（同参反复调用属正常，例如 import_status/get_result）
     */
    public Verdict judge(String toolName, String arguments, boolean sideEffect, boolean pollingLike) {
        String key = keyOf(toolName, arguments);
        boolean seenInRound = seenThisRound.containsKey(key);
        boolean seenInTurn = seenThisTurn.containsKey(key);
        seenThisRound.put(key, seenInRound ? seenThisRound.get(key) + 1 : 1);
        seenThisTurn.put(key, seenInTurn ? seenThisTurn.get(key) + 1 : 1);

        if (sideEffect) roundHadSideEffect = true;
        // 跨轮判断"有进展"：本回合里第一次出现的副作用调用才算进展。
        // 只看"本轮内是否重复"会把"每轮各调一次同样的删文件"当成有进展，永远不触发收尾。
        if (sideEffect && !seenInTurn) roundHadNewSideEffect = true;
        if (!seenInRound) {
            return Verdict.NEW;
        }
        if (sideEffect && !pollingLike) {
            return Verdict.SKIP;
        }
        return Verdict.REPEAT_EXECUTED;
    }

    /**
     * 每轮结束调用。
     *
     * @return 累计后的"连续全重复轮数"（0 表示本轮有进展或被清零）
     */
    public int endRound() {
        // 只有"本轮确实发生过副作用调用、而且没有一个新的"才算空转轮；
        // 纯只读轮（查时间/读文件）不计、轮询轮也不计入进展
        if (roundHadSideEffect && !roundHadNewSideEffect) {
            consecutiveAllDuplicateRounds++;
        } else if (roundHadNewSideEffect) {
            consecutiveAllDuplicateRounds = 0;
        }
        return consecutiveAllDuplicateRounds;
    }

    public int consecutiveAllDuplicateRounds() {
        return consecutiveAllDuplicateRounds;
    }

    /** 跨 execute 重置（一次用户消息 = 一个回合） */
    public void reset() {
        beginRound();
        seenThisTurn.clear();
        consecutiveAllDuplicateRounds = 0;
    }

    /** 工具名 + 规范化参数：对象键排序、递归，避免键序不同导致同一调用被判成新的 */
    static String keyOf(String toolName, String arguments) {
        String name = toolName == null ? "" : toolName;
        return name + "#" + canonical(arguments);
    }

    /** 把 JSON 规范化成"键排序后的紧凑串"；解析失败退回原始串（至少不会因为解析问题抛异常） */
    static String canonical(String json) {
        if (json == null) return "";
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return "";
        try {
            JsonElement el = JsonParser.parseString(trimmed);
            StringBuilder sb = new StringBuilder();
            writeCanonical(el, sb);
            return sb.toString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    private static void writeCanonical(JsonElement el, StringBuilder sb) {
        if (el == null || el.isJsonNull()) {
            sb.append("null");
            return;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            List<String> keys = new ArrayList<>(obj.keySet());
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(keys.get(i)).append(':');
                writeCanonical(obj.get(keys.get(i)), sb);
            }
            sb.append('}');
            return;
        }
        if (el.isJsonArray()) {
            sb.append('[');
            List<JsonElement> items = new ArrayList<>();
            for (JsonElement it : el.getAsJsonArray()) items.add(it);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(items.get(i), sb);
            }
            sb.append(']');
            return;
        }
        sb.append(el.toString());
    }
}
