package com.oilquiz.app.ai.remote;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间线 → 聊天消息的<b>唯一</b>投影。
 *
 * <p>旧实现往 {@code msg.assistantTurn.parts} 里写了一份"看起来是渲染真值"的东西，
 * 但渲染层根本不读它：{@code ChatAdapter.bindWithParts} 每次绑定都用
 * {@code AssistantTurn.fromMessageForParts(msg)} 从
 * {@code roundSnapshots + components + content} <b>现建</b> parts。于是那份 parts
 * 是纯死数据 —— 这也是为什么多步对话在手机上"思考一大段、正文一大段、工具卡全堆在最后"：
 * 真正被消费的 {@code roundSnapshots} 从来没被填过。</p>
 *
 * <p>本类把事件时间线投影成 App 已有的消息模型，让 DSH 同步过来的回合与本地/在线回合
 * 走完全相同的渲染管线：</p>
 * <ul>
 *   <li>{@code roundSnapshots}：一步一条（思考 → 正文 → 该步工具），负责<b>交错顺序</b>；</li>
 *   <li>{@code components}：工具卡 + 各类数据卡，负责卡片内容；</li>
 *   <li>{@code content / thinkingContent}：兼容投影（搜索、复制、旧渲染路径）。</li>
 * </ul>
 *
 * <p>不再维护 {@code assistantTurn}：它会触发 {@code applyToMessage} 反向覆盖上面三项，
 * 等于又开一个写者。</p>
 */
public final class DshProjection {

    /** 组件 id 发号器（Activity 用 ChatIdDispatcher，导入器用递增序号） */
    public interface IdSource {
        String idFor(String key);

        /** 无 id 场景（例如纯离线解析） */
        IdSource NONE = key -> null;
    }

    private DshProjection() {
    }

    /**
     * 把时间线写进消息。
     *
     * <p>幂等：同一时间线重复投影的结果完全一致（列表整体重建，不做增量追加），
     * 因此补拉时同一条消息被投影 N 次不会重复插卡。</p>
     *
     * @param now 无事件时间戳时的兜底时间（一般传消息时间或当前时间）
     */
    /** 一个 turn 有多少步（派发器按步切段用） */
    public static int stepCount(DshTimeline timeline) {
        return timeline == null ? 0 : timeline.orderedSteps().size();
    }

    public static void project(ChatMessage msg, DshTimeline timeline, IdSource ids, long now) {
        project(msg, timeline, ids, now, 0, Integer.MAX_VALUE);
    }

    /**
     * 按步区间投影（2026-11-14，消息派发器改密）：只吃 [fromStep, toStep) 这几步。
     * 消息派发器据此把一个 turn 拆成多条小消息 —— 在线 agent 一条消息只有 1~5 个 part，
     * DSH 一个 turn 几十步压成一条就是 150+ 个 part，RecyclerView 按消息回收时救不回来。
     */
    public static void project(ChatMessage msg, DshTimeline timeline, IdSource ids, long now,
                               int fromStep, int toStep) {
        if (msg == null || timeline == null) return;
        IdSource source = ids != null ? ids : IdSource.NONE;
        long base = now > 0 ? now : (msg.timestamp > 0 ? msg.timestamp : System.currentTimeMillis());

        List<ChatMessage.RoundSnapshot> snapshots = new ArrayList<>();
        List<ComponentData> components = new ArrayList<>();
        List<Integer> bounds = new ArrayList<>();       // 每轮正文的累计长度（contentRoundBounds）
        List<String> roundToolIds = new ArrayList<>();  // 每轮的首个 toolCallId（contentRoundToolIds）
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int round = 0;

        java.util.List<DshTimeline.Step> allSteps = timeline.orderedSteps();
        int stepIdx = 0;
        for (DshTimeline.Step step : allSteps) {
            int thisStep = stepIdx;
            stepIdx += 1;
            if (thisStep < fromStep || thisStep >= toStep) continue;
            String text = trim(step.text);
            String reason = trim(step.reasoning);
            if (text.isEmpty() && reason.isEmpty() && step.tools.isEmpty()) continue;

            if (!text.isEmpty()) {
                if (content.length() > 0) content.append("\n\n");
                content.append(text);
            }
            if (!reason.isEmpty()) {
                if (thinking.length() > 0) thinking.append("\n\n");
                thinking.append(reason);
            }

            round += 1;
            ChatMessage.RoundSnapshot snapshot = new ChatMessage.RoundSnapshot(round, text, reason, base);
            if (!step.tools.isEmpty()) {
                snapshot.toolCallIds = new ArrayList<>();
                for (DshTimeline.Tool tool : step.tools.values()) {
                    snapshot.toolCallIds.add(tool.callId);
                    components.add(toolComponent(tool, source));
                    // P2（2026-11-13）：官方载荷里的动态版式单独成一个 layout 组件
                    ComponentData layout = layoutComponent(tool, source);
                    if (layout != null) components.add(layout);
                }
            }
            snapshots.add(snapshot);
            // 流式渲染（AssistantTurn.fromMessageForStreaming）按 contentRoundBounds 切片取顺序真值。
            // DSH 投影此前只写 roundSnapshots、不写这个字段 —— 于是流式期切片为空，
            // 手机侧正文整段消失、只剩"正在运行 X"的阶段行，工具行也停在进行态。
            // 这里同步写一份边界，让两条渲染路径拿到同一份顺序。
            bounds.add(content.length());
            roundToolIds.add(snapshot.toolCallIds != null && !snapshot.toolCallIds.isEmpty()
                    ? snapshot.toolCallIds.get(0) : "");
        }

        // B15 durable（2026-11-13）：每个子代理投影成一张 subagent_card。
        // 组件随消息落盘 → 重进会话/重启后仍是同一张卡（这是 durable 的关键），
        // 不再依赖"重连时补发最后一帧"的临时手段。
        for (DshTimeline.Subagent sa : timeline.subagents()) {
            components.add(subagentComponent(sa, source));
        }

        for (DshCards.Card card : timeline.orderedCards()) {
            ComponentData data = ComponentData.of(card.type, card.props);
            data.source = "dsh";
            data.id = source.idFor("card:" + card.key);
            // 轮次定位：让 `AssistantTurn` 把卡插到它自己那一步的正文/工具之后，
            // 而不是统一堆到整条消息末尾（这正是之前"卡片全在最后"的根因）。
            data.round = timeline.roundOfCard(card.key);
            components.add(data);
        }

        msg.content = content.toString();
        // 思考：截断到预算内（真机一条消息 215 KB 思考会把解析/渲染拖死）
        msg.thinkingContent = thinking.length() > 0
                ? clip(thinking.toString(), MAX_THINKING_CHARS, "思考") : null;
        // 一个 turn 几十步 → 一条消息 150+ 个 part，RecyclerView 回收不了（见 mergeExtraRounds）
        mergeExtraRounds(snapshots, bounds, roundToolIds, content, MAX_ROUNDS_PER_MESSAGE);
        msg.roundSnapshots = snapshots;
        msg.components = components;
        // 与 roundSnapshots 同源的顺序信息：流式/未完成那一档渲染器读的就是它。
        msg.contentRoundBounds = bounds;
        msg.contentRoundToolIds = roundToolIds;
        // 单一投影：渲染真值是 roundSnapshots/components，assistantTurn 会反向覆盖它们
        msg.assistantTurn = null;
    }

    /** 工具卡：props 形状与旧实现逐字段一致（toolName/args/status/result） */
    private static ComponentData toolComponent(DshTimeline.Tool tool, IdSource ids) {
        JSONObject props = DshCards.obj(
                "toolCallId", tool.callId,
                "toolName", tool.name,
                "args", tool.args,
                "status", tool.error ? "failed" : (tool.finished ? "success" : "running"));
        // 输出/参数都按预算截断：245 张工具卡各带完整输出时，单会话能到 10 MB
        String argsClip = clip(tool.args, MAX_TOOL_ARGS_CHARS, "参数");
        if (argsClip != null && !argsClip.equals(tool.args)) DshCards.put(props, "args", argsClip);
        if (!tool.result.isEmpty()) {
            DshCards.put(props, "result", clip(tool.result, MAX_TOOL_RESULT_CHARS, "输出"));
        }
        // META(2026-11-13)：把官方 tool/result.meta 一起投影进组件 props。
        // 现在手机端卡片还没消费它，但落盘/恢复/后续做 diff/read/search 卡都靠它，
        // 丢了就只能回电脑端重取 —— 所以宁可先带上。
        if (tool.meta != null) DshCards.put(props, "meta", clip(String.valueOf(tool.meta), MAX_TOOL_META_CHARS, "元数据"));
        ComponentData data = ComponentData.of("tool_call", props);
        data.source = "dsh";
        data.id = ids.idFor("tool:" + tool.callId);
        return data;
    }

    /**
     * 官方载荷里的动态版式 → {@code layout} 组件（2026-11-13，P2）。
     *
     * <p>来源是工具通过 {@code output.presentationMeta} 派生的 {@code tool/result.meta}，
     * 约定 {@code meta.layout} 为 LayoutCardView 的 props（容器/图元/样式，可含 {@code actions}）。
     * 这是**不发版**扩展手机界面的合规通道：插件不能发自定义持久事件，但能随工具结果带 meta。</p>
     *
     * <p>props 原样透传，不在这里校验/重建 —— 渲染层对未知图元与坏节点已有 fallback；
     * 没有 layout 时返回 null，避免产生空组件。</p>
     */
    private static ComponentData layoutComponent(DshTimeline.Tool tool, IdSource ids) {
        if (!(tool.meta instanceof JSONObject)) return null;
        JSONObject layout = ((JSONObject) tool.meta).optJSONObject("layout");
        if (layout == null || layout.length() == 0) return null;
        ComponentData data = ComponentData.of("layout", layout);
        data.source = "dsh";
        data.id = ids.idFor("layout:" + tool.callId);
        return data;
    }

    /**
     * 子代理轨迹 → {@code subagent_card} 组件（2026-11-13，B15 durable）。
     *
     * <p>props：{@code {label, childId, lastText, steps:[{tool,args,status,result}]}}。
     * 空轨迹也保留一张卡（至少能看出"某子代理正在跑"），但完全无信息（无 label 无步骤）时不产卡。</p>
     */
    private static ComponentData subagentComponent(DshTimeline.Subagent sa, IdSource ids) {
        JSONObject props = DshCards.obj(
                "childId", sa.childId,
                "label", sa.label == null || sa.label.isEmpty() ? "子代理" : sa.label);
        // 2026-11-14：刚创建、还没跑工具时也有内容可显示（"已启动 · 模式：…"）
        if (sa.mode != null && !sa.mode.isEmpty()) {
            DshCards.put(props, "mode", sa.mode);
        }
        if (sa.lastText != null && !sa.lastText.isEmpty()) {
            DshCards.put(props, "lastText", sa.lastText);
        }
        org.json.JSONArray steps = new org.json.JSONArray();
        for (DshTimeline.SubagentStep st : sa.steps.values()) {
            JSONObject row = DshCards.obj(
                    "tool", st.tool, "args", st.args, "status", st.status);
            if (st.result != null && !st.result.isEmpty()) DshCards.put(row, "result", st.result);
            steps.put(row);
        }
        if (steps.length() > 0) DshCards.put(props, "steps", steps);
        // 只画在甲板卡槽里（2026-11-13）：子代理是"当前状态"而不是对话内容，
        // 放聊天流会跟甲板重复；数据仍随消息落盘（durable），只是消息流不再画。
        DshCards.put(props, "deck_only", true);
        ComponentData data = ComponentData.of("subagent_card", props);
        data.source = "dsh";
        data.id = ids.idFor("subagent:" + sa.childId);
        return data;
    }

    // ==================== 转换器预算（2026-11-14） ====================
    //
    // 真机病历：一条 AI 消息 = 215 KB 思考 + 245 张工具卡（每张带完整命令/输出）
    // → 单会话 10 MB，加载/渲染把主线程拖住 9 秒（MainThreadWatchdog 抓到）。
    //
    // DSH 自己的界面也不把整段思考/完整输出铺出来：思考折叠、工具行只显示摘要、
    // 详情按需展开。这里按同一口径在**投影时**截断 —— 不是渲染时再省，
    // 因为落盘、恢复、搜索都读这份数据。

    /** 单条消息保留的思考上限：超过就截断并标注（详情仍在电脑端会话日志里） */
    private static final int MAX_THINKING_CHARS = 8000;
    /** 工具参数/输出单卡上限 */
    private static final int MAX_TOOL_ARGS_CHARS = 400;
    private static final int MAX_TOOL_RESULT_CHARS = 800;
    /** 官方 presentationMeta 上限（diff/read 等卡要用，留得比结果宽） */
    private static final int MAX_TOOL_META_CHARS = 4000;

    /**
     * 截断并标注。返回 null 表示原样保留（没超限）。
     * 标注里带原始长度，用户/排查都能看出"这里被省了"，不是数据丢了。
     */
    private static String clip(String value, int max, String what) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max) + "\n…（" + what + "已截断，原文 " + value.length() + " 字）";
    }
    /**
     * 轮次合并上限（2026-11-14）。
     *
     * <p>真机病历：DSH 一个 turn 有 51 步（每步一次模型生成）→ 一条消息里 51 段思考 +
     * 51 段正文 + 245 张工具卡 ≈ 150+ 个 part 视图。RecyclerView 是按**消息**回收的，
     * 一条消息内部再多视图也回收不了 —— 滚动时每次重绑一个巨型 item 就卡。
     * 在线 agent 的消息一条只有 1–5 个 part，所以同样的列表、同样的回收配置下它不卡。</p>
     *
     * <p>这里把超出的轮次合并成最后一轮：正文按原顺序拼、思考拼起来再截断、工具 id 归到这一轮。
     * 顺序与内容不变（正文仍完整），只是"折叠成更少的块"，和 DSH 自己的界面一致。</p>
     */
    private static final int MAX_ROUNDS_PER_MESSAGE = 6;

    /** 把超过上限的轮次并进最后一轮；同时修正 contentRoundBounds / contentRoundToolIds。 */
    private static void mergeExtraRounds(List<ChatMessage.RoundSnapshot> snapshots,
                                         List<Integer> bounds,
                                         List<String> roundToolIds,
                                         StringBuilder content,
                                         int maxRounds) {
        if (snapshots == null || snapshots.size() <= maxRounds) return;
        int keep = Math.max(1, maxRounds - 1);
        ChatMessage.RoundSnapshot merged = snapshots.get(keep - 1);
        StringBuilder text = new StringBuilder(merged.content == null ? "" : merged.content);
        StringBuilder think = new StringBuilder(merged.thinking == null ? "" : merged.thinking);
        List<String> toolIds = new ArrayList<>();
        if (merged.toolCallIds != null) toolIds.addAll(merged.toolCallIds);
        for (int i = keep; i < snapshots.size(); i += 1) {
            ChatMessage.RoundSnapshot s = snapshots.get(i);
            if (s == null) continue;
            if (s.content != null && !s.content.trim().isEmpty()) {
                if (text.length() > 0) text.append("\n\n");
                text.append(s.content);
            }
            if (s.thinking != null && !s.thinking.trim().isEmpty()) {
                if (think.length() > 0) think.append("\n\n");
                think.append(s.thinking);
            }
            if (s.toolCallIds != null) toolIds.addAll(s.toolCallIds);
        }
        merged.content = text.toString();
        merged.thinking = clip(think.toString(), MAX_THINKING_CHARS, "思考");
        merged.toolCallIds = toolIds;
        // 截掉被合并掉的快照
        snapshots.subList(keep, snapshots.size()).clear();
        // 边界/工具 id 跟着合并：最后一轮的边界就是全部正文长度
        if (bounds != null && bounds.size() > keep) {
            bounds.subList(keep, bounds.size()).clear();
        }
        if (bounds != null) {
            bounds.add(content.length());
        }
        if (roundToolIds != null && roundToolIds.size() > keep) {
            roundToolIds.subList(keep, roundToolIds.size()).clear();
        }
        if (roundToolIds != null) {
            roundToolIds.add(toolIds.isEmpty() ? "" : toolIds.get(0));
        }
    }
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
