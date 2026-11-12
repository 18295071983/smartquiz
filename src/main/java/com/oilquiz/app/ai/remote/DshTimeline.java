package com.oilquiz.app.ai.remote;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 手机端的 DSH 事件转换器（纯 JVM，无 Android 依赖）。
 *
 * <p><b>为什么要重写</b>：旧实现是 {@code AIChatActivity} 里一个 380 行的
 * {@code applyDshEvent} if-else，把六件事揉在同一个方法里 —— 解析 JSON、维护
 * {@code (turn, step)} 归组、拼卡片 props、弹审批对话框、改进度文案、推进断点游标。
 * 后果是：不能单测（只能真机看）、实时同步与"历史导入"各写一份且行为不同
 * （导入的会话没有任何工具卡/待办卡）、新增一种事件要动 Activity、
 * 出错时游标照样前进（事件永久丢失）。</p>
 *
 * <p><b>现在的边界</b>：本类只做"事件 → 时间线模型"的纯函数式归约
 * （{@link #reduce(DshEvent)} → {@link Update}）；消息怎么投影由
 * {@link DshProjection} 负责；卡片 props 由 {@link DshCards} 负责。
 * 这样实时同步、断点补拉、历史导入三条路径共用同一份转换规则。</p>
 *
 * <p>模型形状与 App 自己的编排对齐（一轮 = 一个 turn，一步 = 一次模型调用）：</p>
 * <pre>
 * turn 1
 *   step 1  reasoning / text / tools[call+result]
 *   step 2  …
 * </pre>
 *
 * <p><b>游标语义</b>：{@link #seq()} 是"已经成功归约到的最大事件序号"。调用方
 * 只有在投影落屏之后才应把它持久化 —— 归约抛异常时不会前进，事件下次还能补回。</p>
 */
public final class DshTimeline {

    /** 审批/反问这类"要弹 UI"的副作用，由调用方决定怎么呈现 */
    public enum ActionKind { APPROVAL, APPROVAL_GONE, QUESTION, QUESTION_GONE }

    public static final class Action {
        public final ActionKind kind;
        public final String id;
        public final JSONObject request;

        Action(ActionKind kind, String id, JSONObject request) {
            this.kind = kind;
            this.id = id != null ? id : "";
            this.request = request;
        }
    }

    /** 一次工具调用（call 与 result 合成一条，手机上是一张卡，而不是两行） */
    public static final class Tool {
        public final String callId;
        public final String name;
        public final String args;
        public String result = "";
        public boolean error;
        public boolean finished;

        Tool(String callId, String name, String args) {
            this.callId = callId != null ? callId : "";
            this.name = name != null && !name.trim().isEmpty() ? name.trim() : "工具";
            this.args = args != null ? args : "";
        }
    }

    /** 一步：一次模型调用产生的思考/正文/工具 */
    public static final class Step {
        public final int turn;
        public final int step;
        public String reasoning = "";
        public String text = "";
        public final LinkedHashMap<String, Tool> tools = new LinkedHashMap<>();

        Step(int turn, int step) {
            this.turn = turn;
            this.step = step;
        }
    }

    /**
     * 一次归约的结果。调用方按需消费：
     * <ul>
     *   <li>{@link #contentChanged}：正文/思考/工具变了 → 需要重新投影消息；</li>
     *   <li>{@link #cardsChanged}：卡片集合变了 → 需要重绘/重绑；</li>
     *   <li>{@link #phase}/{@link #phaseText}：底部状态行文案；</li>
     *   <li>{@link #ended}：这一轮结束；</li>
     *   <li>{@link #actions}：审批/反问弹层。</li>
     * </ul>
     */
    public static final class Update {
        public boolean contentChanged;
        public boolean cardsChanged;
        public boolean ended;
        /** 电脑端日程刚变化（手机应重拉 /v2/schedule） */
        public boolean scheduleChanged;
        public ChatMessage.InferencePhase phase;
        public String phaseText;
        /** 电脑端会话标题变了（session/title 事件）；null = 没变 */
        public String sessionTitle;
        public int seq = -1;
        public final List<Action> actions = new ArrayList<>();

        public boolean hasAnything() {
            return contentChanged || cardsChanged || ended || scheduleChanged || phase != null
                    || phaseText != null || sessionTitle != null || !actions.isEmpty();
        }

        public static Update none(int seq) {
            Update u = new Update();
            u.seq = seq;
            return u;
        }
    }

    private final TreeMap<Integer, TreeMap<Integer, Step>> steps = new TreeMap<>();
    private final LinkedHashMap<String, DshCards.Card> cards = new LinkedHashMap<>();
    /** 卡片 key → 它首次/最近一次被写时所处的轮次（跨 turn 的全局序，1 基） */
    private final Map<String, Integer> cardRounds = new TreeMap<>();
    private final DshCards cardMapper = new DshCards();
    private final Map<String, Integer> unknownTypes = new TreeMap<>();

    private int lastTurn = 1;
    private int lastStep = 1;
    private int lastSeq = -1;
    /** 计划模式当前状态（plan/mode 事件） */
    private boolean planActive = false;

    /**
     * 归约一条事件。
     *
     * <p>幂等性：服务端重连时会从 {@code since} 重放，同一条事件可能再次到达。
     * 这里按 {@code seq} 去重（{@code seq > 0 && seq <= lastSeq} 直接忽略），
     * 否则 {@code job/registered} 这类"计数型"事件会被重复累加。</p>
     */
    public Update reduce(DshEvent ev) {
        if (ev == null) return Update.none(lastSeq);
        if (ev.seq > 0 && ev.seq <= lastSeq) return Update.none(lastSeq);

        int turn = ev.located() ? ev.turn : lastTurn;
        int step = ev.located() && ev.step != DshEvent.NO_INDEX ? ev.step : lastStep;
        if (ev.located()) {
            lastTurn = turn;
            if (ev.step != DshEvent.NO_INDEX) lastStep = step;
        }

        Update u = new Update();
        switch (ev.type) {
            case "user/message":
                // 同步气泡是"助手回合"：手机自己发出的那句已经在本地落过；电脑端敲的句子
                // 属于另一条时间线（当前聊天页没有"插一条用户消息"的入口），这里明确忽略，
                // 但不算"未识别事件"——否则每次同步都会把 unknownTypes 刷成噪声。
                break;
            case "turn/start":
                u.phase = ChatMessage.InferencePhase.THINKING;
                break;
            case "step/start":
                u.phase = ChatMessage.InferencePhase.THINKING;
                u.phaseText = "第 " + step + " 步 · 思考中";
                break;
            case "step/end":
                u.phase = ChatMessage.InferencePhase.GENERATING;
                u.phaseText = "第 " + step + " 步 · 完成";
                break;
            case "assistant/message": {
                JSONObject message = ev.data != null ? ev.data.optJSONObject("message") : null;
                JSONArray parts = message != null ? message.optJSONArray("content") : null;
                StringBuilder answer = new StringBuilder();
                StringBuilder thinking = new StringBuilder();
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i += 1) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null) continue;
                        String tx = p.optString("text");
                        if (tx.isEmpty()) continue;
                        String pt = p.optString("type");
                        if ("reasoning".equals(pt) || "thinking".equals(pt)) thinking.append(tx);
                        else answer.append(tx);
                    }
                }
                Step box = step(turn, step);
                // 权威累计值：比流式增量长（或相同）才替换；短了说明事件早于 delta 到达，
                // 保留已有更长文本，避免"打字打一半又被吞掉"。
                if (answer.length() >= box.text.length()) box.text = answer.toString();
                if (thinking.length() >= box.reasoning.length()) box.reasoning = thinking.toString();
                u.contentChanged = answer.length() > 0 || thinking.length() > 0;
                if (answer.length() > 0) u.phase = ChatMessage.InferencePhase.GENERATING;
                break;
            }
            case "tool/call": {
                if (ev.data == null) break;
                String callId = ev.data.optString("callId").trim();
                if (callId.isEmpty()) break;
                Step box = step(turn, step);
                if (!box.tools.containsKey(callId)) {
                    box.tools.put(callId, new Tool(callId, ev.data.optString("name", ""),
                            ev.data.optString("arguments", "")));
                    u.cardsChanged = true;
                    u.contentChanged = true;
                    u.phase = ChatMessage.InferencePhase.GENERATING;
                    u.phaseText = "第 " + step + " 步 · 正在运行 "
                            + box.tools.get(callId).name;
                }
                break;
            }
            case "tool/result": {
                String callId = ev.data != null ? ev.data.optString("toolCallId").trim() : "";
                Step box = step(turn, step);
                Tool tool = box.tools.get(callId);
                // 结果先到（补拉丢头/事件乱序）：不落一张没有调用的幽灵卡
                if (tool == null) break;
                JSONObject message = ev.data.optJSONObject("message");
                tool.result = message != null
                        ? DshCards.collectText(message.optJSONArray("content")) : "";
                tool.error = ev.data.optBoolean("isError", false)
                        || ev.data.optBoolean("is_error", false);
                tool.finished = true;
                u.contentChanged = true;
                u.cardsChanged = true;
                break;
            }
            case "turn/end":
                u.ended = true;
                u.contentChanged = true;
                break;
            case "stream/delta": {
                // 实时 token 增量（宿主内 agent 的瞬态帧，不落盘）：直接追加到所在步骤，
                // 随后的 assistant/message 是权威累计值，会把它替换成一致内容。
                if (ev.data == null) break;
                String kind = ev.data.optString("kind", "text");
                String text = ev.data.optString("text");
                if (text.isEmpty()) break;
                Step box = step(turn, step);
                if ("reasoning".equals(kind)) {
                    box.reasoning = box.reasoning + text;
                    u.phase = ChatMessage.InferencePhase.THINKING;
                } else if ("text".equals(kind)) {
                    box.text = box.text + text;
                    u.phase = ChatMessage.InferencePhase.GENERATING;
                } else {
                    break; // tool-call-delta 不属于正文
                }
                u.contentChanged = true;
                break;
            }
            case "stream/end":
                break;
            case "plan/mode": {
                planActive = ev.data != null && ev.data.optBoolean("active", false);
                cards.put("plan", cardMapper.planCard(planActive));
                cardRounds.put("plan", 0);
                u.cardsChanged = true;
                break;
            }
            case "subagent/activity": {
                if (ev.data == null) break;
                String childId = ev.data.optString("child_id");
                if (childId.isEmpty()) break;
                String label = ev.data.optString("label", "");
                cards.put("subagent-" + childId,
                        cardMapper.subagentCard(childId, label, subagentBrief(ev.data)));
                cardRounds.put("subagent-" + childId, 0);
                u.cardsChanged = true;
                break;
            }
            case "schedule/changed":
                u.scheduleChanged = true;
                break;
            case "session/title": {
                String title = ev.data == null ? "" : ev.data.optString("title", "").trim();
                if (!title.isEmpty()) u.sessionTitle = title;
                break;
            }
            case "approval":
                u.actions.add(new Action(ActionKind.APPROVAL, ev.id, ev.raw.optJSONObject("request")));
                break;
            case "approval_gone":
                u.actions.add(new Action(ActionKind.APPROVAL_GONE, ev.id, null));
                break;
            case "question":
                u.actions.add(new Action(ActionKind.QUESTION, ev.id, ev.raw.optJSONObject("request")));
                break;
            case "question_gone":
                u.actions.add(new Action(ActionKind.QUESTION_GONE, ev.id, null));
                break;
            default: {
                DshCards.Card card = DshCards.handles(ev.type) ? cardMapper.map(ev, turn) : null;
                if (card != null) {
                    cards.put(card.key, card); // 同 key 覆盖，保持首次出现的位置
                    cardRounds.put(card.key, roundIndexOf(turn, step));
                    u.cardsChanged = true;
                } else if (!DshCards.handles(ev.type)) {
                    unknownTypes.merge(ev.type, 1, Integer::sum);
                }
                break;
            }
        }

        if (ev.seq > lastSeq) lastSeq = ev.seq;
        u.seq = lastSeq;
        return u;
    }

    private Step step(int turn, int step) {
        TreeMap<Integer, Step> byStep = steps.get(turn);
        if (byStep == null) {
            byStep = new TreeMap<>();
            steps.put(turn, byStep);
        }
        Step box = byStep.get(step);
        if (box == null) {
            box = new Step(turn, step);
            byStep.put(step, box);
        }
        return box;
    }

    /** 按 (turn, step) 升序的全部步骤（投影顺序的唯一真值） */
    public List<Step> orderedSteps() {
        List<Step> out = new ArrayList<>();
        for (TreeMap<Integer, Step> byStep : steps.values()) {
            out.addAll(byStep.values());
        }
        return Collections.unmodifiableList(out);
    }

    /** 卡片按首次出现顺序（同 key 覆盖不挪位，避免每 3 秒补拉时卡片跳来跳去） */
    public List<DshCards.Card> orderedCards() {
        return Collections.unmodifiableList(new ArrayList<>(cards.values()));
    }

    /** 计划模式当前状态（plan/mode 事件）；页面用它显示开关 */
    public boolean isPlanActive() {
        return planActive;
    }

    /** 子代理活动的可读摘要 */
    private static String subagentBrief(JSONObject data) {
        String kind = data.optString("kind", "");
        if ("text".equals(kind)) {
            String text = data.optString("text", "").trim();
            return text.isEmpty() ? "正在输出" : ("最近：" + text);
        }
        if ("tool".equals(kind)) {
            String tool = data.optString("tool", "工具");
            String args = data.optString("args", "");
            return "正在运行 " + tool + (args.isEmpty() ? "" : "（" + args + "）");
        }
        if ("tool-result".equals(kind)) {
            return data.optBoolean("error", false) ? "工具失败" : "工具完成";
        }
        if ("step".equals(kind)) {
            return "第 " + data.optInt("step", 0) + " 步";
        }
        if ("turn-end".equals(kind)) {
            String stop = data.optString("stop", "");
            return stop.isEmpty() ? "本轮结束" : ("本轮结束（" + stop + "）");
        }
        return "";
    }

    /** 卡片所属轮次（1 基；0 = 还没有对应步骤）。渲染层据此把卡插到"第 N 步"之后 */
    public int roundOfCard(String key) {
        Integer r = cardRounds.get(key);
        return r == null ? 0 : r;
    }

    /** 当前已归约到的轮次数（= 最后一步的全局序号）。审批落在哪一步就用它 */
    public int currentRound() {
        int n = 0;
        for (TreeMap<Integer, Step> byStep : steps.values()) n += byStep.size();
        return n;
    }

    /** (turn, step) 在全局有序步骤里的序号（1 基）；该步还没建出来时返回当前总数 */
    private int roundIndexOf(int turn, int step) {
        int n = 0;
        for (Map.Entry<Integer, TreeMap<Integer, Step>> e : steps.entrySet()) {
            for (Integer s : e.getValue().keySet()) {
                n += 1;
                if (e.getKey() == turn && s == step) return n;
            }
        }
        return n;
    }

    /** 全部步骤的正文拼接（兼容旧投影：ChatMessage.content / 搜索 / 复制） */
    public String answer() {
        StringBuilder sb = new StringBuilder();
        for (Step s : orderedSteps()) {
            if (s.text == null || s.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s.text);
        }
        return sb.toString();
    }

    /** 全部步骤的思考拼接（兼容旧投影：thinkingContent） */
    public String thinking() {
        StringBuilder sb = new StringBuilder();
        for (Step s : orderedSteps()) {
            if (s.reasoning == null || s.reasoning.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s.reasoning);
        }
        return sb.toString();
    }

    /** 已成功归约的最大事件序号；-1 = 还没有事件 */
    public int seq() {
        return lastSeq;
    }

    /** 未识别的事件类型 → 次数。用于"电脑端发了新事件，手机端静默丢掉"的可观测性 */
    public Map<String, Integer> unknownTypes() {
        return Collections.unmodifiableMap(unknownTypes);
    }

    public int unknownEventKinds() {
        return unknownTypes.size();
    }
}
