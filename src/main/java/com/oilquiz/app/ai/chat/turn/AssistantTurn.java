package com.oilquiz.app.ai.chat.turn;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮用户请求对应的完整助手回合（业界 parts 模型，2026-10-21）。
 *
 * <p>结构对齐 OpenAI Responses 的 output items、Anthropic Messages 的 content
 * blocks、Vercel AI SDK 的 UIMessage.parts：</p>
 *
 * <pre>
 * AssistantTurn
 *   turnId
 *   modelCalls[]   // 每次模型生成一条：fullText / reasoning / stopReason / tool ids
 *   parts[]        // 有序：reasoning → text → tool_call → tool_result → ...
 * </pre>
 *
 * <p><b>单一权威</b>：parts + modelCalls 是持久化真值；{@link ChatMessage#content}
 * 只是兼容/搜索/复制用的投影，不由它反向推导 parts。</p>
 */
public class AssistantTurn {

    public static final int VERSION = 1;

    public int version = VERSION;
    public String turnId;
    /**
     * text parts 是否是 {@code ChatMessage.content} 的连续切片（恢复旧数据、无逐轮快照时为 true，
     * 见 {@link #addRoundsFromBounds}）。此时正文/边界的真值就在 content/bounds 上，
     * {@link #applyToMessage} 必须原样跳过：切片里"只有空白"的段不进 parts，拼回去会丢那截空白，
     * 而 {@code "\n\n"} 分隔规则又会凭空加行——一来一回，每次重开历史正文都在漂移。
     * 持久化字段，缺省 false（新数据与旧 JSON 都按逐轮快照口径投影）。
     */
    public boolean textPartsAreSlices;
    public List<ModelCall> modelCalls = new ArrayList<>();
    public List<Part> parts = new ArrayList<>();

    public static class ModelCall {
        public String id;
        public int round;
        /** 该次模型生成的完整正文（清理后的 fullText） */
        public String content;
        /** 该次生成的 reasoning */
        public String reasoning;
        /** native / API 的停止原因，如 eos / ctx_full / stop_word */
        public String stopReason;
        public long timestamp;
        public List<String> toolCallIds = new ArrayList<>();

        public ModelCall() {
        }

        public ModelCall(String id, int round, String content, String reasoning,
                         String stopReason, long timestamp) {
            this.id = id;
            this.round = round;
            this.content = content != null ? content : "";
            this.reasoning = reasoning != null ? reasoning : "";
            this.stopReason = stopReason != null ? stopReason : "";
            this.timestamp = timestamp;
        }
    }

    public static class Part {
        public static final String TYPE_REASONING = "reasoning";
        public static final String TYPE_TEXT = "text";
        public static final String TYPE_TOOL_CALL = "tool_call";
        public static final String TYPE_TOOL_RESULT = "tool_result";
        public static final String TYPE_ERROR = "error";
        /** 审批/人机确认块（对齐 AI SDK 的 approval-requested/responded 态）。 */
        public static final String TYPE_APPROVAL = "approval";
        /** 产物/数据卡块（chart/table/json/文件…，对齐 AI SDK 的 data-* part）。 */
        public static final String TYPE_DATA = "data";
        /** 连续多工具折叠成的一张组卡（对齐 legacy "已运行N个工具"），成员在 {@link #group}。 */
        public static final String TYPE_TOOL_GROUP = "tool_group";

        /** 生成态（对齐 AI SDK UIMessage part.state）。缺省/null 视为已定稿。 */
        public static final String STATE_STREAMING = "streaming";
        public static final String STATE_DONE = "done";
        /** 被用户中断的收尾态：半截输出，不再流式。 */
        public static final String STATE_CANCELLED = "cancelled";

        /** 审批 part 的决策态（复用 {@code status} 字段承载）。 */
        public static final String APPROVAL_PENDING = "pending";
        public static final String APPROVAL_APPROVED = "approved";
        public static final String APPROVAL_DENIED = "denied";

        public String id;
        public String type;
        public String text;
        public String toolCallId;
        public String toolName;
        public String args;
        public String result;
        public String status;
        public String state;
        /** 工具耗时 / 思考时长（ms），0=无。供渲染器显示"Xs·N步""耗时 Xs"。 */
        public long durationMs;
        public long timestamp;
        /**
         * 产物/数据 part 携带的组件数据（chart/table/json/文件…）。transient：权威真值是
         * {@code ChatMessage.components}，这里只是渲染期引用，不落库、不参与 equals/指纹。
         */
        public transient com.oilquiz.app.ai.chat.component.ComponentData data;
        /** tool_group part 的成员（每个是已并入 result 的 tool_call part）。transient。 */
        public transient java.util.List<Part> group;
        /**
         * 该工具所属的产物组件（§11.3 归属）：渲染时挂在工具卡下方，随工具进组折叠。transient——
         * 权威真值仍是 {@code ChatMessage.components}，这里只是渲染期引用，供指纹区分内容变化。
         */
        public transient java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> products;

        public Part() {
        }

        /** 该 part 是否正在流式生成（per-part 信号，取代旧的整条消息级 streaming 布尔）。 */
        public boolean isStreaming() {
            return STATE_STREAMING.equals(state);
        }

        /** 该 part 是否处于待用户确认的审批态。 */
        public boolean isApproval() {
            return TYPE_APPROVAL.equals(type);
        }

        /** 审批是否还没决（pending）；已批/已拒返回 false。 */
        public boolean isApprovalPending() {
            return isApproval() && (status == null || APPROVAL_PENDING.equals(status));
        }

        public static Part text(String id, String text, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_TEXT;
            p.text = text != null ? text : "";
            p.timestamp = ts;
            return p;
        }

        public static Part reasoning(String id, String text, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_REASONING;
            p.text = text != null ? text : "";
            p.timestamp = ts;
            return p;
        }

        public static Part toolCall(String id, String toolCallId, String toolName,
                                    String args, String status, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_TOOL_CALL;
            p.toolCallId = toolCallId;
            p.toolName = toolName;
            p.args = args;
            p.status = status;
            p.timestamp = ts;
            return p;
        }

        /** 工具结果 part：id 由所属 tool_call 的 id 派生，保证稳定且唯一。 */
        public static Part toolResult(Part call, String result, long ts) {
            Part p = new Part();
            p.id = (call != null && call.id != null ? call.id : "tool") + ID_RESULT_SUFFIX;
            p.type = TYPE_TOOL_RESULT;
            p.toolCallId = call != null ? call.toolCallId : null;
            p.toolName = call != null ? call.toolName : null;
            p.result = result != null ? result : "";
            p.status = call != null ? call.status : null;
            p.timestamp = ts;
            return p;
        }

        /**
         * 审批/人机确认 part：{@code text} 承载说明文案，{@code toolName} 承载权限名，
         * {@code status} 承载决策态（{@link #APPROVAL_PENDING}/{@link #APPROVAL_APPROVED}/
         * {@link #APPROVAL_DENIED}），{@code toolCallId} 关联被审批的工具调用。
         */
        public static Part approval(String id, String toolCallId, String permission,
                                    String prompt, String status, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_APPROVAL;
            p.toolCallId = toolCallId;
            p.toolName = permission;
            p.text = prompt != null ? prompt : "";
            p.status = status != null ? status : APPROVAL_PENDING;
            p.timestamp = ts;
            return p;
        }

        /** 产物/数据 part：真正内容在 {@code data}（引用 {@code ChatMessage.components}）。 */
        public static Part data(String id, com.oilquiz.app.ai.chat.component.ComponentData data, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_DATA;
            p.data = data;
            p.timestamp = ts;
            return p;
        }

        /** 工具组 part：成员是已并入 result 的若干 tool_call part。 */
        public static Part toolGroup(String id, java.util.List<Part> members, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_TOOL_GROUP;
            p.group = members;
            p.timestamp = ts;
            return p;
        }

        /**
         * 内容指纹：对 Part 的展示相关内容做确定性哈希（type / state / text / toolCallId /
         * toolName / args / result / status）。<b>不含 id、timestamp</b>——它回答的是
         * "同一个 id 的内容是否变化"：逐字段相同则指纹相同，任一内容字段变化则指纹变化。
         *
         * <p>含 {@code state}：text/reasoning 从 streaming→done（即便正文没变）也必须触发重渲，
         * 好让渲染器补全 markdown、关掉未闭合围栏处理。</p>
         *
         * <p>算法为 FNV-1a 64 位，仅做字符算术运算，跨 JVM/设备结果稳定；每个字段带
         * 长度前缀，避免相邻字段拼接产生歧义；null 与空串视为等价。</p>
         */
        public String fingerprint() {
            long h = FNV_OFFSET;
            h = fnvField(h, type);
            h = fnvField(h, state);
            h = fnvField(h, text);
            h = fnvField(h, toolCallId);
            h = fnvField(h, toolName);
            h = fnvField(h, args);
            h = fnvField(h, result);
            h = fnvField(h, status);
            h = fnvField(h, Long.toString(durationMs));
            if (products != null) {
                for (com.oilquiz.app.ai.chat.component.ComponentData pd : products) {
                    h = fnvField(h, pd != null ? contentDigest(pd) : "-"); // 产物变化→工具卡重绑（§11.3）
                }
            }
            if (group != null) {
                for (Part m : group) {
                    h = fnvField(h, m != null ? m.fingerprint() : "-"); // 成员变化→组卡重绑
                }
            }
            return Long.toString(h, 36);
        }

        private static long fnvField(long h, String s) {
            String v = s == null ? "" : s;
            h = fnvChars(h, Integer.toString(v.length()));
            return fnvChars(h, v);
        }

        private static long fnvChars(long h, String s) {
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= FNV_PRIME;
            }
            return h;
        }

        private static final long FNV_OFFSET = 0xcbf29ce484222325L;
        private static final long FNV_PRIME = 0x100000001b3L;
        static final String ID_REASONING_SUFFIX = ":reasoning";
        static final String ID_TEXT_SUFFIX = ":text";
        static final String ID_TOOL_PREFIX = ":tool:";
        static final String ID_RESULT_SUFFIX = ":result";
    }

    /**
     * parts 渲染专用的 canonical 构建：在 {@link #fromMessage} 结果上，把含内联
     * {@code ```component```} 标记的 text part 展开成「text 段 / data 段」交错序列
     * （{@code data} 段承载从标记现场解析出的组件，legacy 只渲染 data part 卡、text 段不再含标记）。
     *
     * <p>返回<b>新建</b>的 turn，绝不返回/改动 {@code msg.assistantTurn}，故不影响
     * {@link #applyToMessage} 的现网投影（flag=false 零影响）。仅 {@code PartsTurnBinder} 用。</p>
     */
    public static AssistantTurn fromMessageForParts(ChatMessage msg) {
        return fromMessageForParts(msg, false);
    }

    /** 带主题（mermaid/公式 html 需 isDark）的 parts 构建。 */
    public static AssistantTurn fromMessageForParts(ChatMessage msg, boolean dark) {
        if (msg == null) {
            return null;
        }
        AssistantTurn base = fromMessage(msg);
        expandInline(base, dark);
        attachOwnedProducts(base);
        groupTools(base);
        return base;
    }

    /**
     * 把 parts 里的工具序列整理成组卡（§11.1）：
     * ① 每条 {@code tool_result} 并入其 {@code tool_call}（按 toolCallId），删掉独立 result part；
     * ② 连续 {@code tool_call}（≥1 个即算一组）折叠成一个 {@link Part#TYPE_TOOL_GROUP}（成员存 {@link Part#group}）；
     *    阈值取 1 是为了对齐 legacy：每个工具轮都走活动卡，单工具也显示"已运行 1 个工具"。
     * 只作用于传入的可安全修改 turn（fromMessageForParts 新建的），勿传 live turn。
     */
    public static void groupTools(AssistantTurn base) {
        if (base == null || base.parts == null) {
            return;
        }
        // ① 合并 result 到对应 tool_call，丢弃独立 tool_result part
        java.util.Map<String, Part> callById = new java.util.HashMap<>();
        java.util.List<Part> merged = new java.util.ArrayList<>();
        for (Part p : base.parts) {
            if (p == null) {
                continue;
            }
            if (Part.TYPE_TOOL_CALL.equals(p.type) && p.toolCallId != null) {
                callById.put(p.toolCallId, p);
                merged.add(p);
            } else if (Part.TYPE_TOOL_RESULT.equals(p.type)) {
                Part call = p.toolCallId != null ? callById.get(p.toolCallId) : null;
                if (call != null) {
                    if (p.result != null && !p.result.isEmpty()) call.result = p.result;
                    if ((call.status == null || call.status.isEmpty()) && p.status != null) call.status = p.status;
                    if (call.durationMs == 0L) call.durationMs = p.durationMs;
                }
                // 没匹配到 call 的孤儿 result：丢弃（避免悬空）
            } else {
                merged.add(p);
            }
        }
        // ② 连续 ≥2 tool_call 折叠成组卡
        java.util.List<Part> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < merged.size()) {
            Part p = merged.get(i);
            if (p != null && Part.TYPE_TOOL_CALL.equals(p.type)) {
                java.util.List<Part> run = new java.util.ArrayList<>();
                run.add(p);
                int j = i + 1;
                while (j < merged.size() && merged.get(j) != null
                        && Part.TYPE_TOOL_CALL.equals(merged.get(j).type)) {
                    run.add(merged.get(j));
                    j++;
                }
                if (run.size() >= 1) {
                    // 阈值取 1：legacy 每个工具轮都走活动卡（单工具也显示"已运行 1 个工具"，
                    // 真机基线截图可见），若只在 ≥2 时成组，单工具会渲成整张 ToolCallCardView，观感与现网不一致。
                    out.add(Part.toolGroup(run.get(0).id + ":grp", run, run.get(0).timestamp));
                } else {
                    out.add(p);
                }
                i = j;
            } else {
                out.add(p);
                i++;
            }
        }
        base.parts = out;
    }

    /**
     * 产物归属（§11.3）：把带 {@code producerToolCallId}/{@code producerToolName} 的 data part
     * 折进其所属 {@code tool_call} part 的 {@link Part#products}（渲染时挂在工具卡下、随工具进组折叠），
     * 并从顶层序列移除；无归属的 data part（含内联/mermaid 切出的）保持顶层。
     * 须在 {@link #expandInline} 之后、{@link #groupTools} 之前调用（成员先带上产物再折叠）。
     * 只作用于可安全修改的 turn（fromMessageForParts 新建的），勿传 live turn。
     */
    public static void attachOwnedProducts(AssistantTurn base) {
        if (base == null || base.parts == null) {
            return;
        }
        // 归属键 → 该工具 part（id 优先，缺 id 退化 name；与 legacy productOwnerKey 同口径）
        java.util.Map<String, Part> byId = new java.util.HashMap<>();
        java.util.Map<String, Part> byName = new java.util.HashMap<>();
        for (Part p : base.parts) {
            if (p == null || !Part.TYPE_TOOL_CALL.equals(p.type)) {
                continue;
            }
            if (p.toolCallId != null && !p.toolCallId.isEmpty()) {
                byId.putIfAbsent(p.toolCallId, p);
            }
            if (p.toolName != null && !p.toolName.isEmpty()) {
                byName.putIfAbsent(p.toolName, p);
            }
        }
        if (byId.isEmpty() && byName.isEmpty()) {
            return; // 无工具 → 全部保持顶层
        }
        java.util.List<Part> kept = new java.util.ArrayList<>();
        for (Part p : base.parts) {
            if (p == null) {
                continue;
            }
            Part owner = null;
            if (Part.TYPE_DATA.equals(p.type) && p.data != null && p.data.props != null) {
                String prodId = p.data.props.optString("producerToolCallId", "");
                String prodName = p.data.props.optString("producerToolName", "");
                if (!prodId.isEmpty()) {
                    owner = byId.get(prodId);
                }
                if (owner == null && !prodName.isEmpty()) {
                    owner = byName.get(prodName);
                }
            }
            if (owner != null) {
                if (owner.products == null) {
                    owner.products = new java.util.ArrayList<>();
                }
                owner.products.add(p.data); // 折进所属工具，移除顶层卡
            } else {
                kept.add(p);
            }
        }
        base.parts = kept;
    }

    public static void expandInline(AssistantTurn base) {
        expandInline(base, false);
    }

    /**
     * 就地展开：把 text part 里内嵌的富结构切成交错 text+data part，让 parts 路径自己渲：
     * ① {@code ```component```} 标记 → {@code ComponentContentSplitter}；
     * ② mermaid / {@code $$公式$$} → {@code MermaidMathSplitter}（产出 html 组件，与 legacy 同源）。
     * 调用方须传<b>可安全修改</b>的 turn（新建或 {@code msg.assistantTurn.copy()}），勿传 live turn。
     */
    public static void expandInline(AssistantTurn base, boolean dark) {
        if (base == null) {
            return;
        }
        java.util.List<Part> out = new java.util.ArrayList<>();
        for (Part p : base.parts) {
            if (p == null || !Part.TYPE_TEXT.equals(p.type) || p.text == null) {
                out.add(p);
                continue;
            }
            if (com.oilquiz.app.ai.chat.component.ComponentContentSplitter
                    .containsComponent(p.text)) {
                emitSegments(out, splitComponent(p.text), p);
            } else if (com.oilquiz.app.ai.chat.component.MermaidMathSplitter
                    .containsStructure(p.text)) {
                emitSegments(out, splitMermaidMath(p.text, dark), p);
            } else {
                out.add(p);
            }
        }
        base.parts = out;
    }

    private interface Seg {
        boolean isComponent();
        String text();
        com.oilquiz.app.ai.chat.component.ComponentData component();
    }

    private static java.util.List<Seg> splitComponent(String text) {
        java.util.List<Seg> r = new java.util.ArrayList<>();
        for (com.oilquiz.app.ai.chat.component.ComponentContentSplitter.Segment s
                : com.oilquiz.app.ai.chat.component.ComponentContentSplitter.split(text)) {
            r.add(new Seg() {
                public boolean isComponent() { return s.isComponent; }
                public String text() { return s.text; }
                public com.oilquiz.app.ai.chat.component.ComponentData component() { return s.component; }
            });
        }
        return r;
    }

    private static java.util.List<Seg> splitMermaidMath(String text, boolean dark) {
        java.util.List<Seg> r = new java.util.ArrayList<>();
        for (com.oilquiz.app.ai.chat.component.MermaidMathSplitter.Segment s
                : com.oilquiz.app.ai.chat.component.MermaidMathSplitter.split(text, dark)) {
            r.add(new Seg() {
                public boolean isComponent() { return s.isComponent; }
                public String text() { return s.text; }
                public com.oilquiz.app.ai.chat.component.ComponentData component() { return s.component; }
            });
        }
        return r;
    }

    private static void emitSegments(java.util.List<Part> out, java.util.List<Seg> segs, Part src) {
        int seq = 0;
        for (Seg seg : segs) {
            if (seg == null) {
                continue;
            }
            if (seg.isComponent() && seg.component() != null) {
                Part dp = Part.data(src.id + ":p" + (seq++), seg.component(), src.timestamp);
                dp.args = contentDigest(seg.component()); // 参与指纹，区分同类型不同内容的内联卡
                dp.state = src.state;
                out.add(dp);
            } else if (!seg.isComponent() && seg.text() != null && !seg.text().trim().isEmpty()) {
                Part tp = Part.text(src.id + ":p" + (seq++), seg.text(), src.timestamp);
                tp.state = src.state;
                out.add(tp);
            }
        }
    }

    /**
     * 从当前 ChatMessage 的兼容字段构建 canonical turn。
     *
     * <p><b>这是 parts 顺序的唯一生产者</b>：实时提交（{@code AIChatActivity} 完成回调）与历史恢复
     * （{@link AssistantTurnRestore}）都走这里，故同一条消息在"刚生成完"和"退出重进"两种形态下
     * 的 part 顺序必然一致——顺序不再有第二个来源。</p>
     *
     * <p>顺序真值按可得性三选一，优先级即数据权威度：</p>
     * <ol>
     *   <li>{@code roundSnapshots}：每轮全量正文 + 该轮 toolCallIds（2026-10-21 起的权威）；</li>
     *   <li>{@code contentRoundBounds} + {@code contentRoundToolIds}：旧数据没有逐轮快照，但
     *       legacy 把"第 i 个工具挂在正文第 i 个偏移处"记在边界里，按它切正文即可复刻
     *       {@code ChatAdapter.renderRoundAssembled} 的时序（思考→正文→工具→下一轮正文）；</li>
     *   <li>都没有：一条 model call + 一个 text part。</li>
     * </ol>
     */
    public static AssistantTurn fromMessage(ChatMessage msg) {
        if (msg == null) return null;
        AssistantTurn turn = new AssistantTurn();
        turn.turnId = msg.turnId;
        long base = msg.timestamp > 0 ? msg.timestamp : System.currentTimeMillis();

        if (msg.roundSnapshots != null && !msg.roundSnapshots.isEmpty()) {
            addRoundsFromSnapshots(turn, msg, base);
        } else if (msg.contentRoundBounds != null && !msg.contentRoundBounds.isEmpty()) {
            addRoundsFromBounds(turn, msg, base);
        } else {
            // 旧数据/无逐轮快照：至少保留一条 model call + text part
            String callId = turn.turnId != null ? turn.turnId + "-R1" : "R1";
            ModelCall call = new ModelCall(callId, 1, msg.content, msg.thinkingContent,
                    "", base);
            turn.modelCalls.add(call);
            if (msg.thinkingContent != null && !msg.thinkingContent.trim().isEmpty()) {
                turn.parts.add(Part.reasoning(callId + Part.ID_REASONING_SUFFIX,
                        msg.thinkingContent, base));
            }
            if (msg.content != null && !msg.content.trim().isEmpty()) {
                turn.parts.add(Part.text(callId + Part.ID_TEXT_SUFFIX, msg.content, base));
            }
        }
        fillToolPartsFromComponents(turn, msg);
        appendDataParts(turn, msg);
        return turn;
    }

    /** 逐轮快照分支：每轮 reasoning → text → 该轮工具。 */
    private static void addRoundsFromSnapshots(AssistantTurn turn, ChatMessage msg, long base) {
        for (ChatMessage.RoundSnapshot snap : msg.roundSnapshots) {
            if (snap == null) continue;
            String callId = callId(turn.turnId, snap.round);
            ModelCall call = new ModelCall(callId, snap.round,
                    snap.content, snap.thinking, "", snap.timestamp > 0 ? snap.timestamp : base);
            if (snap.toolCallIds != null) call.toolCallIds.addAll(snap.toolCallIds);
            turn.modelCalls.add(call);

            if (snap.thinking != null && !snap.thinking.trim().isEmpty()) {
                Part rp = Part.reasoning(callId + Part.ID_REASONING_SUFFIX, snap.thinking,
                        call.timestamp);
                // 接上该轮思考耗时（legacy 用同一来源），供渲染器显示"已深度思考 Xs"。
                rp.durationMs = thinkingRoundDurationMs(msg, snap.round - 1);
                turn.parts.add(rp);
            }
            if (snap.content != null && !snap.content.trim().isEmpty()) {
                turn.parts.add(Part.text(callId + Part.ID_TEXT_SUFFIX, snap.content,
                        call.timestamp));
            }
            // 工具调用按该轮顺序挂到 text 后；id 以 toolCallId 为键，保证跨重建稳定、
            // 且不随其它轮次或前序工具的增减而漂移。缺 toolCallId 的脏数据跳过。
            if (snap.toolCallIds != null) {
                for (String toolId : snap.toolCallIds) {
                    if (toolId == null || toolId.isEmpty()) continue;
                    turn.parts.add(Part.toolCall(
                            callId + Part.ID_TOOL_PREFIX + toolId,
                            toolId, "", "", "done", call.timestamp));
                }
            }
        }
    }

    /**
     * 旧数据分支：按 {@code contentRoundBounds} 把正文切成每轮一段，工具插在所属段之后。
     *
     * <p>切片规则与 {@code ChatAdapter.renderRoundAssembled} 逐字对齐（第 i 段 =
     * {@code content[bound[i-1], bound[i])}，第 i 个工具跟在段 i 之后），因此旧消息恢复后
     * 拿到的 parts 顺序 = 它今天在 legacy 里被渲染的顺序。思考取 {@code thinkingRounds[i]}，
     * 与"第 i 轮思考产出第 i 段正文"的 legacy 约定一致。</p>
     *
     * <p>边界里的工具 id 可能为空（早于 toolCallId 落库的数据）：此时 part 的 toolCallId 留空、
     * id 用 {@code #i} 占位，交给 {@link #fillToolPartsFromComponents} 按序号就地认领组件，
     * 不再退化到"补到末尾"——那正是恢复后顺序错乱的来源。</p>
     */
    private static void addRoundsFromBounds(AssistantTurn turn, ChatMessage msg, long base) {
        turn.textPartsAreSlices = true;
        String content = msg.content != null ? msg.content : "";
        java.util.List<Integer> bounds = msg.contentRoundBounds;
        java.util.List<String> ids = msg.contentRoundToolIds;
        int n = bounds.size();
        int start = 0;
        for (int i = 0; i <= n; i++) {
            int end = content.length();
            if (i < n) {
                Integer raw = bounds.get(i);
                end = raw == null ? start : Math.max(start, Math.min(raw, content.length()));
            }
            String seg = start < content.length() ? content.substring(start, end) : "";
            String think = thinkingRoundAt(msg, i);
            String callId = callId(turn.turnId, i + 1);
            ModelCall call = new ModelCall(callId, i + 1, seg, think, "", base);
            turn.modelCalls.add(call);

            if (think != null && !think.trim().isEmpty()) {
                Part rp = Part.reasoning(callId + Part.ID_REASONING_SUFFIX, think, base);
                rp.durationMs = thinkingRoundDurationMs(msg, i);
                turn.parts.add(rp);
            }
            if (!seg.trim().isEmpty()) {
                turn.parts.add(Part.text(callId + Part.ID_TEXT_SUFFIX, seg, base));
            }
            if (i < n) {
                String toolId = (ids != null && i < ids.size() && ids.get(i) != null)
                        ? ids.get(i) : "";
                if (!toolId.isEmpty()) call.toolCallIds.add(toolId);
                turn.parts.add(Part.toolCall(
                        callId + Part.ID_TOOL_PREFIX + (toolId.isEmpty() ? ("#" + i) : toolId),
                        toolId, "", "", "done", base));
            }
            start = end;
        }
    }

    private static String callId(String turnId, int round) {
        return turnId != null ? turnId + "-R" + round : "R" + round;
    }

    private static String thinkingRoundAt(ChatMessage msg, int index) {
        if (msg.thinkingRounds == null || index < 0 || index >= msg.thinkingRounds.size()) {
            return "";
        }
        String v = msg.thinkingRounds.get(index);
        return v != null ? v : "";
    }

    /** 第 index 轮（0 基）思考耗时；越界或旧数据缺列表时按 0 处理。 */
    private static long thinkingRoundDurationMs(ChatMessage msg, int index) {
        try {
            return msg.thinkingRoundDurationMs(index);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    /**
     * 把 {@code msg.components} 里非 tool_call 的组件（chart/table/json/文件…）追加成 data part。
     *
     * <p>id 稳定：优先 {@code data:{componentId}}，缺 id 用序号（components 顺序确定，重建稳定）。
     * 把组件内容摘要写进 {@code args}（参与指纹）——否则同类型的两个卡片指纹全同，引擎会误判"没变"
     * 而跳过第二个的绑定。{@code data} 本身是渲染用的 transient 引用。</p>
     */
    private static void appendDataParts(AssistantTurn turn, ChatMessage msg) {
        if (msg.components == null || msg.components.isEmpty()) {
            return;
        }
        int idx = 0;
        for (com.oilquiz.app.ai.chat.component.ComponentData c : msg.components) {
            if (c == null || "tool_call".equals(c.type)) {
                idx++;
                continue;
            }
            String id = (c.id != null && !c.id.isEmpty()) ? ("data:" + c.id) : ("data#" + idx);
            Part p = Part.data(id, c, 0L);
            p.args = contentDigest(c);
            turn.parts.add(p);
            idx++;
        }
    }

    /** 组件内容稳定摘要（String.hashCode 规范稳定），写进 data part 的 args 供指纹区分。 */
    private static String contentDigest(com.oilquiz.app.ai.chat.component.ComponentData c) {
        String key = c.type + "|" + (c.props != null ? c.props.toString() : "");
        return Integer.toString(key.hashCode());
    }

    /** 从消息的 tool_call 组件回填 name/args/status/result，并补 TOOL_RESULT part。 */
    private static void fillToolPartsFromComponents(AssistantTurn turn, ChatMessage msg) {
        if (msg.components == null || msg.components.isEmpty()) return;
        java.util.Map<String, org.json.JSONObject> byId = new java.util.HashMap<>();
        java.util.List<org.json.JSONObject> all = new java.util.ArrayList<>();
        for (com.oilquiz.app.ai.chat.component.ComponentData c : msg.components) {
            if (c == null || c.props == null || !"tool_call".equals(c.type)) continue;
            all.add(c.props);
            String id = c.props.optString("toolCallId", "");
            if (!id.isEmpty()) byId.put(id, c.props);
        }
        java.util.Set<org.json.JSONObject> used =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // 遍历时会向 turn.parts 插入 result part，故对快照列表迭代，避免 ConcurrentModificationException；
        // 插入位置用 indexOf(真实列表) 计算，新插入的 result 不参与本轮匹配。
        for (Part p : new ArrayList<>(turn.parts)) {
            if (p == null || !Part.TYPE_TOOL_CALL.equals(p.type)) continue;
            if (p.toolCallId == null || p.toolCallId.isEmpty()) continue;
            org.json.JSONObject props = byId.get(p.toolCallId);
            if (props == null) continue;
            used.add(props);
            attachToolProps(turn, p, props);
        }
        // ② 无 toolCallId 的 part（旧数据的轮次边界里没记 id）按出现顺序与"无 id 的工具组件"
        //    对齐、就地回填。缺这一步时它们会全部掉进下面的"补到末尾"分支，恢复后工具卡
        //    顺序就与实时（按轮内联）不一致——这正是"恢复后 part 顺序混乱"的来源。
        java.util.List<Part> idlessParts = new ArrayList<>();
        for (Part p : new ArrayList<>(turn.parts)) {
            if (p != null && Part.TYPE_TOOL_CALL.equals(p.type)
                    && (p.toolCallId == null || p.toolCallId.isEmpty())) idlessParts.add(p);
        }
        java.util.List<org.json.JSONObject> idlessProps = new ArrayList<>();
        for (org.json.JSONObject props : all) {
            if (!used.contains(props) && props.optString("toolCallId", "").isEmpty()) {
                idlessProps.add(props);
            }
        }
        int paired = Math.min(idlessParts.size(), idlessProps.size());
        for (int i = 0; i < paired; i++) {
            org.json.JSONObject props = idlessProps.get(i);
            used.add(props);
            attachToolProps(turn, idlessParts.get(i), props);
        }
        // 配不上组件的空壳 part（既无 id 也无可回填的组件）：摘掉，避免渲出一张空白工具行，
        // 也避免它随后与 ③ 补进末尾的真组件重复成两张卡。
        for (int i = paired; i < idlessParts.size(); i++) {
            turn.parts.remove(idlessParts.get(i));
        }
        // ③ 仍没有任何 part 认领的组件（轮次边界比组件少、或回调乱序）：补到末尾兜底，
        //    保证"一个工具都不丢"。时间戳取消息时间而非构建时刻，恢复与实时才可能一致。
        int seq = 0;
        for (org.json.JSONObject props : all) {
            if (used.contains(props)) continue;
            String toolCallId = props.optString("toolCallId", "");
            String partId = toolCallId.isEmpty()
                    ? "legacy:ord" + (seq++)
                    : "legacy:" + toolCallId;
            long ts = msg.timestamp > 0 ? msg.timestamp : 0L;
            Part p = Part.toolCall(partId, toolCallId,
                    props.optString("toolName", ""), props.optString("args", ""),
                    props.optString("status", "done"), ts);
            p.durationMs = props.optLong("durationMs", props.optLong("executionTimeMs", 0L));
            turn.parts.add(p);
            String result = props.optString("result", "");
            if (!result.isEmpty()) {
                turn.parts.add(Part.toolResult(p, result, p.timestamp));
            }
        }
    }

    /** 把 tool_call 组件的 props 写进已就位的 part，并在其后补一条 tool_result part。 */
    private static void attachToolProps(AssistantTurn turn, Part p, org.json.JSONObject props) {
        if (turn == null || p == null || props == null) return;
        p.toolName = props.optString("toolName", p.toolName);
        p.args = props.optString("args", p.args);
        p.status = props.optString("status", p.status);
        p.durationMs = props.optLong("durationMs", props.optLong("executionTimeMs", 0L));
        String result = props.optString("result", "");
        if (!result.isEmpty()) {
            Part r = Part.toolResult(p, result, p.timestamp);
            int at = turn.parts.indexOf(p) + 1;
            turn.parts.add(at, r);
        }
    }

    /**
     * 把 canonical parts 投影回兼容字段：
     * content / contentRoundBounds / contentRoundToolIds。
     *
     * <p>这是“移除手工 bounds”的关键：UI 仍按 content+bounds 渲染，但 bounds 不再由
     * onToolCallStart 手写，而是由 parts 顺序在提交时统一派生。</p>
     */
    public void applyToMessage(ChatMessage msg) {
        if (msg == null) return;
        if (textPartsAreSlices) return; // 正文/边界的真值本来就在 msg 上，切片 turn 不回写（见字段注释）
        StringBuilder sb = new StringBuilder();
        java.util.List<Integer> bounds = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Part p : parts) {
            if (p == null) continue;
            if (Part.TYPE_TEXT.equals(p.type)) {
                if (p.text == null || p.text.isEmpty()) continue;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n\n");
                }
                sb.append(p.text);
            } else if (Part.TYPE_TOOL_CALL.equals(p.type)) {
                bounds.add(sb.length());
                ids.add(p.toolCallId != null ? p.toolCallId : "");
            }
        }
        if (sb.length() == 0) return;
        msg.content = sb.toString();
        msg.contentRoundBounds = bounds;
        msg.contentRoundToolIds = ids;
    }

    /** 兼容投影：按 parts 顺序拼出正文（不含 tool 参数/结果）。 */
    public String deriveText() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p == null || !Part.TYPE_TEXT.equals(p.type)) continue;
            if (p.text == null || p.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p.text);
        }
        return sb.toString();
    }

    /** 供落库/并发安全使用的深拷贝。 */
    public AssistantTurn copy() {
        AssistantTurn t = new AssistantTurn();
        t.version = version;
        t.turnId = turnId;
        t.textPartsAreSlices = textPartsAreSlices;
        for (ModelCall c : modelCalls) {
            if (c == null) continue;
            ModelCall n = new ModelCall(c.id, c.round, c.content, c.reasoning,
                    c.stopReason, c.timestamp);
            if (c.toolCallIds != null) n.toolCallIds.addAll(c.toolCallIds);
            t.modelCalls.add(n);
        }
        for (Part p : parts) {
            if (p == null) continue;
            Part n = new Part();
            n.id = p.id;
            n.type = p.type;
            n.text = p.text;
            n.toolCallId = p.toolCallId;
            n.toolName = p.toolName;
            n.args = p.args;
            n.result = p.result;
            n.status = p.status;
            n.state = p.state;
            n.durationMs = p.durationMs;
            n.timestamp = p.timestamp;
            n.data = p.data; // transient 引用浅拷贝，保证副本仍可渲染
            n.products = p.products; // transient 引用浅拷贝（§11.3 归属产物）
            t.parts.add(n);
        }
        return t;
    }
}
