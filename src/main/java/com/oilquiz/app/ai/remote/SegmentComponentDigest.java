package com.oilquiz.app.ai.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 段内"非工具组件"的投影指纹（2026-11-15，§19C 非工具组件纳入失效）。
 *
 * <p>背景：尾段增量投影的指纹（{@link SegmentProjectionCache.Progress}）以前只数
 * 卡片<b>张数</b>，不认卡片<b>内容</b>；`subagent_card` 根本不在 cards 里（它由
 * {@link DshTimeline#subagents()} 直接投影），`layout` 又藏在 {@code tool/result.meta}
 * 里。于是"子代理状态更新了，但段消息不重建"——甲板读到的 {@code subagent_card.props}
 * 还是旧的（真机病历：子代理跑了新工具，轨迹卡不动）。</p>
 *
 * <p>本类只回答一个问题：<b>这一段里所有会进 components 的东西，摘要变了没有</b>。</p>
 * <ul>
 *   <li>卡片：取 {@code (type, key, 关键字段摘要)}，不序列化整个 props；只有会渲染的
 *       字段参与（见 {@link #KEY_FIELDS}），无关字段（埋点/内部标记）变化不算；</li>
 *   <li>{@code dynamic}（未知事件的动态兜底卡）没有固定 schema，所有字段都算；</li>
 *   <li>工具卡里的 {@code layout}（{@code tool/result.meta.layout}）：算 meta 摘要；</li>
 *   <li>子代理轨迹：算 {@code label/mode/lastText/steps}（<b>只给尾段</b>——投影会把
 *       当前全部子代理写进每段，若算进每段，子代理一活跃所有封口段都会失效）。</li>
 * </ul>
 *
 * <p>成本：每帧每段一次线性遍历 + 字符串 hashCode；{@code tool/result.meta} 可能很大，
 * 其摘要只在结果到达时算一次（{@code DshTimeline.Tool.metaDigest}），指纹每帧只读 long。
 * 与 {@link SegmentProjectionCache} 一样只在主线程调用，无锁。</p>
 */
public final class SegmentComponentDigest {

    /**
     * 卡片通用渲染字段：只有这些字段变化才算"卡片内容变了"。
     *
     * <p>取自 {@link DshCards} 当前产出的全部卡型（todo_card / file_list / alert_card /
     * job / code_card / steps / markdown_doc / tool_call / dynamic）的 props 字段并集；
     * 新增卡型时把它的渲染字段补进来即可（漏了只是退化成"不触发重建"，不会算错内容）。</p>
     */
    private static final String[] KEY_FIELDS = {
            "title", "text", "content", "level", "type", "status", "detail", "label",
            "value", "progress", "items", "files", "steps", "metrics", "rows", "headers",
            "buttons", "actions", "action", "jump", "childId", "toolName", "args", "result",
            "code", "language", "command", "output", "job_label", "percent", "error", "done"
    };

    private SegmentComponentDigest() {
    }

    /**
     * 一段的组件摘要。
     *
     * @param timeline        当前回合时间线（null → 0，调用方当作"没有组件"）
     * @param fromStep        段起始步（含，0 基）
     * @param toStep          段结束步（不含，0 基）
     * @param includeSubagents 是否把子代理轨迹算进来（Activity 只对<b>尾段</b>传 true）
     */
    public static long digest(DshTimeline timeline, int fromStep, int toStep,
                              boolean includeSubagents) {
        if (timeline == null) return 0L;
        long h = 1125899906842597L;   // 一个质数种子，避免"空段都算出 0"
        List<DshCards.Card> cards = timeline.orderedCards();
        for (DshCards.Card card : cards) {
            if (card == null) continue;
            int round = timeline.roundOfCard(card.key);   // 1 基原始步序；0 = 全局卡
            int ordinal = round - 1;
            if (round <= 0 || ordinal < fromStep || ordinal >= toStep) continue;
            h = h * 31 + cardHash(card);
        }
        // layout 组件由 tool/result.meta 派生（不是 DshCards.Card），单独按工具算。
        List<DshTimeline.Step> steps = timeline.orderedSteps();
        int end = Math.min(toStep, steps.size());
        for (int i = Math.max(0, fromStep); i < end; i += 1) {
            DshTimeline.Step step = steps.get(i);
            if (step == null) continue;
            for (DshTimeline.Tool tool : step.tools.values()) {
                if (tool == null || tool.meta == null) continue;
                h = h * 31 + tool.callId.hashCode();
                h = h * 31 + tool.metaDigest;
            }
        }
        if (includeSubagents) {
            for (DshTimeline.Subagent sa : timeline.subagents()) {
                h = h * 31 + subagentHash(sa);
            }
        }
        return h;
    }

    /** 一张卡的摘要：{@code (type, key, 关键字段摘要)}；dynamic 全字段参与。 */
    static long cardHash(DshCards.Card card) {
        if (card == null) return 0L;
        long h = 17L;
        h = h * 31 + String.valueOf(card.type).hashCode();
        h = h * 31 + String.valueOf(card.key).hashCode();
        JSONObject props = card.props;
        if (props == null) return h;
        if ("dynamic".equals(card.type)) {
            // 未知事件的动态卡没有固定 schema：字段并集不可枚举，全量摘要（只算 hash，不落缓存）
            return hashJson(h, props);
        }
        for (String field : KEY_FIELDS) {
            if (!props.has(field)) continue;
            h = h * 31 + field.hashCode();
            h = hashJson(h, props.opt(field));
        }
        return h;
    }

    /** 子代理轨迹摘要：与 {@code DshProjection.subagentComponent} 的 props 字段一一对应。 */
    static long subagentHash(DshTimeline.Subagent sa) {
        if (sa == null) return 0L;
        long h = 19L;
        h = h * 31 + textHash(sa.childId);
        h = h * 31 + textHash(sa.label);
        h = h * 31 + textHash(sa.mode);
        h = h * 31 + textHash(sa.lastText);
        for (DshTimeline.SubagentStep st : sa.steps.values()) {
            if (st == null) continue;
            h = h * 31 + textHash(st.key);
            h = h * 31 + textHash(st.tool);
            h = h * 31 + textHash(st.args);
            h = h * 31 + textHash(st.status);
            h = h * 31 + textHash(st.result);
        }
        return h;
    }

    /**
     * 规范化 JSON 摘要（包内可见）：给 {@code DshTimeline.Tool.metaDigest} 在结果到达时算一次。
     * 对象键排序（同内容不同插入顺序必须同 hash）、数组保序；只滚 hash、不产生中间字符串。
     */
    static long jsonHash(Object value) {
        return hashJson(17L, value);
    }

    /**
     * 规范化 JSON 摘要：对象键排序（同内容不同插入顺序必须同 hash）、数组保序。
     * 与 {@code AssistantTurn.canonicalJson} 同语义，但只滚 hash、不产生中间字符串。
     */
    private static long hashJson(long h, Object value) {
        if (value == null || value == JSONObject.NULL) return h * 31 + 1;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
            Collections.sort(keys);
            for (String k : keys) {
                h = h * 31 + k.hashCode();
                h = hashJson(h, o.opt(k));
            }
            return h * 31 + 2;
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i += 1) h = hashJson(h, a.opt(i));
            return h * 31 + 3;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return h * 31 + String.valueOf(value).hashCode();
        }
        return h * 31 + textHash(String.valueOf(value));
    }

    private static long textHash(String s) {
        return s == null ? 0L : s.hashCode();
    }
}
