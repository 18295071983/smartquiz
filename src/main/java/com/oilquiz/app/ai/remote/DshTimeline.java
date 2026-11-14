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
        /** approval/decided 的结果（allowed-once / rejected / cancelled / unavailable）；其他动作为空 */
        public final String outcome;

        Action(ActionKind kind, String id, JSONObject request) {
            this(kind, id, request, "");
        }

        Action(ActionKind kind, String id, JSONObject request, String outcome) {
            this.kind = kind;
            this.id = id != null ? id : "";
            this.request = request;
            this.outcome = outcome == null ? "" : outcome;
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
        /**
         * 工具结果的展示元数据（{@code tool/result.meta}）。
         *
         * <p>DSH 官方约定：工具可以用 {@code output.presentationMeta} 派生一份可回放的 JSON，
         * 持久化在 {@code tool/result.meta} 上，replay 时用它复现同一张卡片（diff/read/search/web…）。
         * 这里原样保留，投影时塞进组件 props —— 手机端渲染不读也不影响，读的话无需重新解析。</p>
         */
        public Object meta;
        /**
         * {@link #meta} 的稳定摘要（投影指纹用，2026-11-15 §19C）。
         *
         * <p>meta 只在 {@code tool/result} 到达时写一次，这里当场算 hash；段指纹每帧只读这个
         * long，不再重复遍历可能很大的 layout/元数据 JSON。</p>
         */
        public long metaDigest = 0L;

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

    /**
     * 子代理及其**耐久**活动轨迹（2026-11-13，B15 durable）。
     *
     * <p>以前子代理只合成一张 {@code dsh_notice} 提示卡，内容随最后一条活动覆盖，
     * 看不出"跑了哪些工具、结果如何"。现在把 {@code subagent/activity} 累成结构化步骤，
     * 投影成 {@code subagent_card} 组件：组件随消息落盘，重进会话仍是同一张卡。</p>
     */
    public static final class SubagentStep {
        public final String key;
        public String callId = "";
        public String tool = "";
        public String args = "";
        public String status = "running";   // running / success / failed
        public String result = "";
        public long ts;

        SubagentStep(String key) {
            this.key = key == null ? "" : key;
        }
    }

    /** 一个子代理 + 最近若干步（超过上限丢最旧的） */
    public static final class Subagent {
        public final String childId;
        public String label = "";
        /** 最近一次输出摘要（可选，卡片里作副标题） */
        public String lastText = "";
        /** 创建模式（continuable / one-shot …，来自 subagent/catalog；只在"刚启动"时显示） */
        public String mode = "";
        public final LinkedHashMap<String, SubagentStep> steps = new LinkedHashMap<>();

        Subagent(String childId) {
            this.childId = childId == null ? "" : childId;
        }
    }

    /** 每步子代理最多保留多少步（避免一张卡无限长） */
    static final int MAX_SUBAGENT_STEPS = 12;

    private final LinkedHashMap<String, Subagent> subagents = new LinkedHashMap<>();

    /** 子代理轨迹（投影与测试用；按首次出现顺序） */
    public java.util.Collection<Subagent> subagents() {
        return subagents.values();
    }

    /**
     * 把旧时间线的子代理轨迹并入本时间线（2026-11-13，B15 durable）。
     *
     * <p>DSH 每换一个气泡就重建时间线（{@code ensureMirrorBubble}）。子代理往往横跨
     * 两次气泡切换，如果不继承，卡片会只剩 label、步骤全丢（真机上是这样：
     * 落盘的卡只有 label 没有 steps）。这里按 childId 继承，已存在的不覆盖。</p>
     */
    public void adoptSubagentsFrom(DshTimeline previous) {
        if (previous == null || previous == this) return;
        for (Subagent sa : previous.subagents()) {
            if (sa == null || sa.childId.isEmpty()) continue;
            if (!subagents.containsKey(sa.childId)) subagents.put(sa.childId, sa);
        }
    }

    /**
     * 已知但**故意不渲染**的内部事件（2026-11-13，B14）。
     *
     * <p>它们只在电脑端内部流转（LLM 请求头/上下文、hook、PTC 分发、图片落盘等），
     * 手机上没有任何可展示的语义；如果丢进 {@link #unknownTypes}，"未识别类型"
     * 这个监控数字就永远降不到 0，真正新增的类型反而被噪声埋掉。</p>
     */
    /**
     * 纯内部事件：手机端**不渲染**，也不计入"未知事件"（否则监控里 unknown 永远降不下来）。
     *
     * <p>2026-11-14 对照 DSH 官方 {@code known-event-types.ts}（63 种）逐条过了一遍：
     * 能出内容的（插话入队、子代理目录、PTC、钩子、图片移出、模型切换）已经交给
     * {@link DshCards#map} 出卡；剩下这些确实只是线上协议/记账，不产生用户可见内容。</p>
     */
    private static final java.util.Set<String> IGNORED_TYPES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "request/context",
                    "request/header",
                    "session/end-seed",
                    "session/title-llm-request",
                    "subagent/model-selection-policy",
                    "web/deepseek-search-llm-request",
                    // 2026-11-14 补：这两类以前没登记，一直算"未知类型"
                    "feedback/record",
                    "session-log-deepseek/delivery-accepted",
                    // 2026-11-14 用户口径：瞬时状态放插槽（甲板），不进消息流 —— 这些类型不再出聊天卡，
                    // 由插件 ui/slot 驱动（收到事件更新、完成即消失）。登记为已识别，避免污染未知计数：
                    "job/registered", "job/settled",
                    "compaction/start", "compaction/summary", "compaction/prune", "compaction/end",
                    "llm/retry", "llm/retry-started",
                    "goal/change",
                    "permission/preset", "sandbox/mode", "approval/policy",
                    "agent-preset/selected", "model/selection",
                    "agent/inbox/spliced",
                    // 2026-11-14：合成帧/通道帧不是会话事件，绝不能进时间线渲染（否则会变成 JSON 卡）
                    "ui/slot", "ui/card", "state", "view", "pulse", "hello", "cmd", "view_def",
                    "__force_resync", "approval", "approval_gone", "question", "question_gone"));

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
                // FIELD-FIX(2026-11-13)：只认 callId 一种拼写时，插件/DSH 换了字段名（call_id /
                // toolCallId / tool_call_id）就整张工具卡丢失。这里按常见拼写依次尝试。
                String callId = firstNonEmpty(ev.data, "callId", "call_id", "toolCallId", "tool_call_id");
                if (callId.isEmpty()) {
                    android.util.Log.w("DshTimeline", "[probe] tool/call 缺 callId，丢弃: "
                            + ev.data.toString().substring(0, Math.min(160, ev.data.toString().length())));
                    break;
                }
                Step box = step(turn, step);
                if (!box.tools.containsKey(callId)) {
                    String toolName = firstNonEmpty(ev.data, "name", "toolName", "tool_name");
                    String toolArgs = firstNonEmpty(ev.data, "arguments", "args", "argumentsDelta");
                    box.tools.put(callId, new Tool(callId, toolName, toolArgs));
                    u.cardsChanged = true;
                    u.contentChanged = true;
                    u.phase = ChatMessage.InferencePhase.GENERATING;
                    u.phaseText = "第 " + step + " 步 · 正在运行 "
                            + box.tools.get(callId).name;
                }
                break;
            }
            case "tool/result": {
                // ★ 字段位置（2026-11-12 实测）：DSH 的 tool/result 把调用 id 放在
                //   data.message.toolCallId 里，**不是** data.toolCallId（那是 tool/call 的形状）。
                //   以前这里读 data.toolCallId 永远拿到空串 → 每次都判成"结果先到"直接丢弃，
                //   于是工具卡永远停在"运行中"（打点证据：[probe] tool/result MISS callId=）。
                JSONObject resMsg = ev.data != null ? ev.data.optJSONObject("message") : null;
                String callId = "";
                if (resMsg != null) {
                    callId = resMsg.optString("toolCallId", "").trim();
                    if (callId.isEmpty()) {
                        JSONObject src = resMsg.optJSONObject("source");
                        if (src != null) callId = src.optString("callId", "").trim();
                    }
                }
                if (callId.isEmpty() && ev.data != null) {
                    callId = ev.data.optString("toolCallId", "").trim();
                }
                Step box = step(turn, step);
                Tool tool = box.tools.get(callId);
                // 结果不一定落在"同一个 step"里：宿主补拉/乱序时 tool/result 的 step 字段
                // 可能和 tool/call 不同（真机就是这样：日志里 tool/result 到了，但按当前 step
                // 查不到调用 → 直接 break → 卡片永远停"运行中"）。这里退一步在本轮所有步骤里
                // 找这个 callId，再退一步往更早的轮次找。
                if (tool == null) {
                    tool = findToolByCallId(turn, callId);
                }
                // 真的没有对应调用（结果先到/半截历史）：**丢弃**，不要凭空造一张工具卡。
                // ORPHAN-NO-CARD(2026-11-13)：2026-11-12 曾在这里兜底建一条空名字的工具记录，
                // 结果是从中途订阅时时间线里多出一张**没有工具名、只有结果**的卡
                // （DshProjection.toolComponent 直接取 tool.name，空名就是空标题）。
                // 真正"卡片停在运行中"的场景是**结果找错了 step**，那已由上面的
                // findToolByCallId 兜住；连调用都没见过的结果没有可归属的卡，丢弃且不置
                // cardsChanged —— 与 DshTimelineTest#toolResultWithoutCall_... 的既定契约一致。
                if (tool == null) {
                    android.util.Log.w("DshTimeline", "[probe] tool/result ORPHAN callId=" + callId
                            + " turn=" + turn + " step=" + step + " → dropped");
                    break;
                }
                JSONObject message = ev.data.optJSONObject("message");
                tool.result = message != null
                        ? DshCards.collectText(message.optJSONArray("content")) : "";
                tool.error = (resMsg != null && resMsg.optBoolean("isError", false))
                        || (ev.data != null && (ev.data.optBoolean("isError", false)
                        || ev.data.optBoolean("is_error", false)));
                tool.finished = true;
                // META(2026-11-13)：官方卡片载荷，原样保留（对象/数组/字符串都可能）
                Object meta = ev.data.opt("meta");
                if (meta != null && !(meta instanceof JSONObject && ((JSONObject) meta).length() == 0)) {
                    tool.meta = meta;
                    // §19C：段指纹要认 layout/元数据变化；只在结果到达时算一次摘要
                    tool.metaDigest = SegmentComponentDigest.jsonHash(meta);
                }
                android.util.Log.i("DshTimeline", "[probe] tool/result OK callId=" + callId
                        + " turn=" + turn + " step=" + step + " error=" + tool.error);
                u.contentChanged = true;
                u.cardsChanged = true;
                break;
            }
            case "turn/end":
                u.ended = true;
                u.contentChanged = true;
                // ★ 阶段状态机必须跟着通道事件收尾（2026-11-12）：以前只置 ended，
                //   inferenceProgress 仍停在上一个 phase（GENERATING/THINKING），
                //   界面上那行"正在运行 终端命令"就永远挂着、还会一轮一轮堆叠。
                u.phase = ChatMessage.InferencePhase.COMPLETED;
                u.phaseText = null;
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
            case "subagent/catalog": {
                /**
                 * 2026-11-14：子代理**一创建就登记**，别等第一次工具调用。
                 *
                 * <p>以前只有 {@code subagent/activity} 会建 {@link Subagent}，于是"子代理刚启动、
                 * 还没跑工具"这段时间里甲板卡槽是空的，提示卡上的"跳过去看轨迹"也无处可跳。
                 * 这里在 catalog 事件就建好条目（label/mode），投影立刻能出一张空卡，
                 * 后续 activity 再往里填步骤。</p>
                 */
                if (ev.data == null) break;
                String childId = ev.data.optString("childId", ev.data.optString("child_id", "")).trim();
                if (childId.isEmpty()) break;
                Subagent sa = subagents.get(childId);
                if (sa == null) {
                    sa = new Subagent(childId);
                    subagents.put(childId, sa);
                }
                String label = ev.data.optString("label", "").trim();
                if (!label.isEmpty()) sa.label = label;
                sa.mode = ev.data.optString("mode", sa.mode == null ? "" : sa.mode);
                u.cardsChanged = true;
                break;
            }
            case "subagent/activity": {
                if (ev.data == null) break;
                String childId = ev.data.optString("child_id");
                if (childId.isEmpty()) break;
                Subagent sa = subagents.get(childId);
                if (sa == null) {
                    sa = new Subagent(childId);
                    subagents.put(childId, sa);
                }
                String label = ev.data.optString("label", "").trim();
                if (!label.isEmpty()) sa.label = label;
                String kind = ev.data.optString("kind", "");
                long ts = ev.raw == null ? 0L : ev.raw.optLong("ts", 0L);
                if ("tool".equals(kind)) {
                    String callId = ev.data.optString("callId", "").trim();
                    SubagentStep st = new SubagentStep(
                            callId.isEmpty() ? ("k" + sa.steps.size()) : callId);
                    st.callId = callId;
                    st.tool = ev.data.optString("tool", "工具");
                    st.args = ev.data.optString("args", "");
                    st.ts = ts;
                    sa.steps.put(st.key, st);
                    trimSubagentSteps(sa);
                } else if ("tool-result".equals(kind)) {
                    String callId = ev.data.optString("toolCallId", "").trim();
                    SubagentStep st = callId.isEmpty() ? lastRunningStep(sa) : sa.steps.get(callId);
                    if (st != null) {
                        st.status = ev.data.optBoolean("error", false) ? "failed" : "success";
                        String text = ev.data.optString("text", "").trim();
                        if (!text.isEmpty()) st.result = text;
                        st.ts = ts;
                    }
                } else if ("text".equals(kind)) {
                    String text = ev.data.optString("text", "").trim();
                    if (!text.isEmpty()) sa.lastText = text;
                }
                u.cardsChanged = true;
                break;
            }
            case "schedule/changed":
            case "schedule/change":
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
            // 持久审批事件（2026-11-13）：approval/request 是 agent 作用域 waterfall，
            // 插件根上下文收不到；但 approval/asked 是**落盘审计事件**，一定能收到。
            // 按它出卡：形状 {id, toolName, callId?, reason?}；approval/decided 收卡/标结果。
            case "approval/asked": {
                JSONObject d = ev.data == null ? new JSONObject() : ev.data;
                String id = d.optString("id", "").trim();
                if (id.isEmpty()) break;
                JSONObject request = new JSONObject();
                try {
                    request.put("id", id);
                    request.put("toolName", d.optString("toolName", ""));
                    request.put("callId", d.optString("callId", ""));
                    request.put("reason", d.optString("reason", ""));
                } catch (Exception ignored) {
                    // 字段拼装失败就当没有 reason
                }
                u.actions.add(new Action(ActionKind.APPROVAL, id, request));
                break;
            }
            case "approval/decided": {
                String id = ev.data == null ? "" : ev.data.optString("id", "").trim();
                String outcome = ev.data == null ? "" : ev.data.optString("outcome", "").trim();
                if (!id.isEmpty()) {
                    u.actions.add(new Action(ActionKind.APPROVAL_GONE, id, null, outcome));
                }
                break;
            }
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
                /**
                 * 2026-11-14：未知事件类型走**动态兜底** —— 只要事件带可读内容，就产一张
                 * {@code dynamic} 卡（App 侧落到 DynamicCardView，新事件不用发版）；
                 * 协议类事件没有可读内容，mapUnknown 返回 null，仍只计"未知类型"供诊断。
                 */
                boolean known = DshCards.handles(ev.type);
                // 2026-11-14：**忽略表里的类型不能走动态兜底** —— 否则每个纯状态/协议事件
                // （job/settled、compaction/*、ui/slot…）都会变成一张 "电脑事件 · xxx" 的 JSON 卡。
                // 动态兜底只服务"真正没见过的新类型"。
                boolean ignoredType = IGNORED_TYPES.contains(ev.type);
                DshCards.Card card = known
                        ? cardMapper.map(ev, turn)
                        : (ignoredType ? null : cardMapper.mapUnknown(ev, turn));
                if (card != null) {
                    cards.put(card.key, card); // 同 key 覆盖，保持首次出现的位置
                    cardRounds.put(card.key, roundIndexOf(turn, step));
                    u.cardsChanged = true;
                } else if (!known) {
                    // B14（2026-11-13）：纯内部事件（LLM 请求头、hook、PTC 分发等）不渲染、
                    // 也不该被算成"未识别类型"——否则监控里的 unknown 计数永远降不下来，
                    // 真正新增的类型反而被噪声埋掉。
                    if (!IGNORED_TYPES.contains(ev.type)) unknownTypes.merge(ev.type, 1, Integer::sum);
                }
                break;
            }
        }

        if (ev.seq > lastSeq) lastSeq = ev.seq;
        u.seq = lastSeq;
        return u;
    }

    /**
     * 在整条时间线里按 callId 找工具调用（先本轮、再更早的轮次）。
     *
     * <p>用途：{@code tool/result} 的 step 字段与 {@code tool/call} 不一致时（补拉/乱序会这样），
     * 只按当前 step 查会 miss，结果被当成"结果先到"丢掉，卡片永远停在运行中。</p>
     */
    /** 依次尝试多个字段名，取第一个非空值（各版本/各方对同一字段的拼写不一致）。 */
    private static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private Tool findToolByCallId(int turn, String callId) {
        if (callId == null || callId.isEmpty()) return null;
        TreeMap<Integer, Step> byStep = steps.get(turn);
        if (byStep != null) {
            for (Step box : byStep.values()) {
                Tool t = box.tools.get(callId);
                if (t != null) return t;
            }
        }
        for (Map.Entry<Integer, TreeMap<Integer, Step>> e : steps.descendingMap().entrySet()) {
            if (e.getKey() == turn) continue;
            for (Step box : e.getValue().values()) {
                Tool t = box.tools.get(callId);
                if (t != null) return t;
            }
        }
        return null;
    }

    /**
     * 时间线自己已经归约到的最新 seq（-1 = 还没吞过事件）。
     *
     * <p>它是去重的唯一正确依据：时间线是进程内的，重放起点与它天然一致；
     * 而消息上持久化的 {@code dshLastSeq} 是跨进程高水位，重放的低序号会被它误杀
     * （真机表现：tool/result 到了却进不了归约，卡片停在"运行中"）。</p>
     */
    public int lastSeq() {
        return lastSeq;
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

    /** 超过上限丢最旧的一步（保持插入序） */
    private static void trimSubagentSteps(Subagent sa) {
        while (sa.steps.size() > MAX_SUBAGENT_STEPS) {
            java.util.Iterator<String> it = sa.steps.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    /** 结果帧没带 callId 时，挂到最近一个还在跑的那一步上 */
    private static SubagentStep lastRunningStep(Subagent sa) {
        SubagentStep fallback = null;
        for (SubagentStep st : sa.steps.values()) {
            if ("running".equals(st.status)) fallback = st;
        }
        return fallback;
    }

    /** 卡片所属轮次（1 基；0 = 还没有对应步骤）。渲染层据此把卡插到"第 N 步"之后 */
    public int roundOfCard(String key) {
        Integer r = cardRounds.get(key);
        return r == null ? 0 : r;
    }

    /**
     * 某次工具调用所在的全局步序号（1 基；0 = 没有这次调用）。
     *
     * <p>用途：审批/反问这类"消息级"状态要落到<b>它所属的那一段</b>，而不是永远写尾段。
     * 审批请求自带 {@code callId}，用它在时间里找到批的那次调用，步号最精确；
     * 找不到（中继帧缺 callId / 旧数据）调用方才退回 {@link #currentRound()}。</p>
     */
    public int roundOfTool(String callId) {
        if (callId == null || callId.isEmpty()) return 0;
        int n = 0;
        for (Map.Entry<Integer, TreeMap<Integer, Step>> e : steps.entrySet()) {
            for (Step box : e.getValue().values()) {
                n += 1;
                if (box.tools.containsKey(callId)) return n;
            }
        }
        return 0;
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
