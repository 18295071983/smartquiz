package com.oilquiz.app.ai.engine;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.util.PromptBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 本地引擎「无缝切换」路由器：llama.cpp ⇄ NPU（Qualcomm GenieX / Hexagon HTP）。
 *
 * <p><b>设计原则（2026-10-04 按需求实现）</b>：
 * <ol>
 *   <li><b>无缝</b>：调用方（ALChat / 推理队列 / 推理路由的本地兜底）继续用同一套签名；
 *       开关一开就整体走 NPU，一关就整体回 llama.cpp，无需改业务代码。</li>
 *   <li><b>功能不降级</b>：**能力感知路由** —— 只有"普通文本生成"才走 NPU；
 *       需要<b>思考链</b>、<b>工具调用</b>、<b>多模态</b>的请求一律留在 llama.cpp
 *       （GenieX 侧载的 Qwen3 是纯对话模型，这些能力会明显变差）。</li>
 *   <li><b>故障自动回退</b>：NPU 未加载/加载失败/推理异常 → 自动回退 llama.cpp，
 *       用户最多感到"这次慢一点"，不会得到"功能不可用"。</li>
 * </ol>
 *
 * <p>为什么放在这一层：本地推理有 10+ 个入口直接调 {@link LlamaHelper}
 * （ALChat / InferenceQueue / InferenceRouter / Agent 思考链 / 题库导入 …）。
 * 在"普通生成"的这几个入口接一层路由，既不碰工具链/多模态逻辑，又能让对话类功能吃到 NPU。
 */
public final class NpuEngineRouter {

    private static final String TAG = "NpuEngineRouter";

    /** 首次加载模型可能较慢（含 GenieX native 初始化 + DSP 建图），给足时间 */
    private static final long LOAD_TIMEOUT_MS = 180_000L;

    private static volatile Context appContext;

    private NpuEngineRouter() {
    }

    /** 应用启动调用一次：缓存 Context，并把持久化开关同步进 {@link NpuLlmChat} 的静态镜像 */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        try {
            appContext = context.getApplicationContext();
            boolean enabled = appContext
                    .getSharedPreferences("npu_engine_prefs", Context.MODE_PRIVATE)
                    .getBoolean("npu_enabled", false);
            NpuLlmChat.setEngineEnabled(enabled);
            NpuLlmChat.loadPreferredModelName(appContext);   // 恢复用户指定的 NPU 模型
        } catch (Throwable t) {
            Log.w(TAG, "init: " + t);
        }
    }

    /**
     * 能力感知判定：是否把这次生成交给 NPU。
     *
     * @param needsThinking 需要思考链（推理链输出）
     * @param needsTools    需要工具/函数调用
     * @param needsVision   需要多模态（图像）
     */
    public static boolean shouldRouteToNpu(boolean needsThinking, boolean needsTools, boolean needsVision) {
        // 思考链：GenieX 的 applyChatTemplate 支持 enable_thinking，已透传 → 不再需要留 llama.cpp
        if (needsVision) {
            return false;   // 多模态仍留 llama.cpp（待接 VlmWrapper）
        }
        // 工具调用：GenieX 的 applyChatTemplate 第二参就是工具定义（已透传）→ 一并交给 NPU。
        // 不再按模型尺寸设门槛：引擎开关打开就统一走 NPU，模型能力差异由用户选模型解决。
        if (!NpuLlmChat.isEngineEnabled()) {
            return false;
        }
        // 只要引擎开关打开就尝试 NPU：模型登记由 ensureLoadedBlocking()
        // → ensureLoadedAsync() 内部完成。原先还要求 isLoaded() || hasUsableModel()，
        // 但 hasUsableModel() 依赖静态 localFiles（要跑过 registerAppModelLibrary 才填充），
        // 新进程/Agent 进程里尚未填充 → 误判不可用 → 回退 llama.cpp（2026-10-05 实测确认）。
        return true;
    }

    /** 当前是否真的会走 NPU（给状态栏/日志用，不做加载） */
    public static boolean isNpuActive() {
        return shouldRouteToNpu(false, false, false);
    }

    // ==================== 阻塞式生成 ====================

    public static String generate(List<PromptBuilder.Message> messages, int maxTokens,
                                  float temperature, float topP, int topK) {
        if (shouldRouteToNpu(false, false, false)) {
            try {
                if (ensureLoadedBlocking()) {
                    return NpuLlmChat.generateBlocking(roles(messages), contents(messages),
                            maxTokens == 0 ? 2048 : maxTokens, 10 * 60 * 1000L, false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "NPU 阻塞生成失败，回退 llama.cpp: " + t);
            }
        }
        return LlamaHelper.generate(messages, maxTokens, temperature, topP, topK);
    }

    public static String generate(String prompt, int maxTokens,
                                  float temperature, float topP, int topK) {
        if (shouldRouteToNpu(false, false, false)) {
            try {
                if (ensureLoadedBlocking()) {
                    return NpuLlmChat.generateBlocking(new String[]{"user"}, new String[]{prompt},
                            maxTokens == 0 ? 2048 : maxTokens, 10 * 60 * 1000L);
                }
            } catch (Throwable t) {
                Log.w(TAG, "NPU 阻塞生成失败，回退 llama.cpp: " + t);
            }
        }
        return LlamaHelper.generate(prompt, maxTokens, temperature, topP, topK);
    }

    // ==================== 流式生成 ====================

    public static void generateStream(List<PromptBuilder.Message> messages, int maxTokens,
                                      float temperature, float topP, int topK,
                                      boolean enableThinking, LlamaHelper.TokenCallback callback) {
        if (shouldRouteToNpu(enableThinking, false, false) && ensureLoadedBlocking()) {
            try {
                streamNpu(roles(messages), contents(messages),
                        maxTokens == 0 ? 2048 : maxTokens, callback);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "NPU 流式生成失败，回退 llama.cpp: " + t);
                releaseNpuBeforeFallback();
            }
        }
        LlamaHelper.generateStream(messages, maxTokens, temperature, topP, topK, enableThinking, callback);
    }

    public static void generateStream(String prompt, int maxTokens,
                                      float temperature, float topP, int topK,
                                      boolean enableThinking, LlamaHelper.TokenCallback callback) {
        if (shouldRouteToNpu(enableThinking, false, false) && ensureLoadedBlocking()) {
            try {
                streamNpu(new String[]{"user"}, new String[]{prompt},
                        maxTokens == 0 ? 2048 : maxTokens, callback);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "NPU 流式生成失败，回退 llama.cpp: " + t);
                releaseNpuBeforeFallback();
            }
        }
        LlamaHelper.generateStream(prompt, maxTokens, temperature, topP, topK, enableThinking, callback);
    }

    /** 停止当前生成（两条引擎都停，调用方无需判断走了哪条） */
    public static void stopGeneration() {
        try {
            LlamaHelper.stopGeneration();
        } catch (Throwable ignored) {
        }
        try {
            NpuLlmChat.stopGenerate();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 内部 ====================


    /**
     * 工具调用（Agent 主循环）走 NPU：与 {@code LlamaHelper.generateWithTools} 同签名，
     * 把 role/content/toolsJson 转成 GenieX 需要的字符串后透传（它的 chat template 支持 tools）。
     * 失败自动回退 llama.cpp。
     */
    public static void generateWithTools(String[] roles, byte[][] contents, byte[] toolsJson,
                                        int maxTokens, float temperature, float topP, int topK,
                                        boolean enableThinking, LlamaHelper.TokenCallback callback) {
        if (shouldRouteToNpu(enableThinking, true, false) && ensureLoadedBlocking()) {
            try {
                String[] texts = new String[contents == null ? 0 : contents.length];
                for (int i = 0; i < texts.length; i++) {
                    texts[i] = contents[i] == null ? ""
                            : new String(contents[i], java.nio.charset.StandardCharsets.UTF_8);
                }
                String tools = toolsJson == null ? null
                        : new String(toolsJson, java.nio.charset.StandardCharsets.UTF_8);
                // 与 llama.cpp 对齐：common_chat_tool_parse 只认 OpenAI 风格
                // [{"type":"function","function":{name,description,parameters}}]。
                // App 的 buildToolsJson() 若给的是简化形状（{name,description,parameters} 平铺），
                // 这里归一化后再交给 GenieX，否则模板的 tools 变量注入不进去 → 模型只会普通对话。
                tools = normalizeToolsJson(tools);
                Log.i(TAG, "工具调用→NPU: messages=" + (roles == null ? 0 : roles.length)
                        + ", toolsJson=" + (tools == null ? 0 : tools.length()) + " 字符, 预览="
                        + (tools == null ? "null" : tools.substring(0, Math.min(220, tools.length()))));
                final StringBuilder full = new StringBuilder();
                NpuLlmChat.sendChatAsync(roles, texts, maxTokens == 0 ? 2048 : maxTokens, enableThinking,
                        new NpuLlmChat.GenerateListener() {
                            @Override
                            public void onToken(String text) {
                                full.append(text);
                                if (callback != null) callback.onToken(text);
                            }

                            @Override
                            public void onCompleted(int tokens, float tps, long elapsedMs) {
                                Log.i(TAG, "工具调用→NPU 完成: " + tokens + " tokens / " + tps + " t/s, 输出预览="
                                        + full.substring(0, Math.min(220, full.length())));
                                if (callback != null) callback.onComplete(full.toString());
                            }

                            @Override
                            public void onError(String message) {
                                if (callback != null) callback.onError("NPU 推理失败: " + message);
                            }
                        }, tools);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "NPU 工具调用失败，回退 llama.cpp: " + t);
                releaseNpuBeforeFallback();
            }
        }
        LlamaHelper.generateWithTools(roles, contents, toolsJson, maxTokens, temperature, topP, topK,
                enableThinking, callback);
    }

    /**
     * 把工具定义归一化成 llama.cpp 期望的 OpenAI 风格（与 native 侧 common_chat_tool_parse 对齐）：
     * <pre>[{"type":"function","function":{"name":..,"description":..,"parameters":{..}}}]</pre>
     * 已是该形状原样返回；简化形状（{name,description,parameters} 平铺）逐项包装；
     * 解析失败返回原串（不阻断，交给 GenieX 自行处理）。
     */
    private static String normalizeToolsJson(String tools) {
        if (tools == null || tools.trim().isEmpty()) {
            return tools;
        }
        try {
            String t = tools.trim();
            if (t.startsWith("{")) {
                org.json.JSONObject obj = new org.json.JSONObject(t);
                org.json.JSONArray arr = obj.optJSONArray("tools");
                if (arr == null) arr = obj.optJSONArray("functions");
                if (arr == null) return tools;
                t = arr.toString();
            }
            if (!t.startsWith("[")) {
                return tools;
            }
            org.json.JSONArray in = new org.json.JSONArray(t);
            org.json.JSONArray out = new org.json.JSONArray();
            boolean wrapped = false;
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONObject item = in.optJSONObject(i);
                if (item == null) {
                    out.put(in.get(i));
                    continue;
                }
                if (item.has("function") || "function".equals(item.optString("type"))) {
                    out.put(item);
                    continue;
                }
                org.json.JSONObject fn = new org.json.JSONObject();
                if (item.has("name")) fn.put("name", item.opt("name"));
                if (item.has("description")) fn.put("description", item.opt("description"));
                if (item.has("parameters")) {
                    fn.put("parameters", item.opt("parameters"));
                } else if (item.has("input_schema")) {
                    fn.put("parameters", item.opt("input_schema"));
                } else {
                    fn.put("parameters", new org.json.JSONObject().put("type", "object"));
                }
                org.json.JSONObject wrap = new org.json.JSONObject();
                wrap.put("type", "function");
                wrap.put("function", fn);
                out.put(wrap);
                wrapped = true;
            }
            if (wrapped) {
                Log.i(TAG, "工具 schema 已从简化形状归一化为 OpenAI 形状（共 " + out.length() + " 个工具）");
            }
            return out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "工具 schema 归一化失败（按原样透传）: " + t);
            return tools;
        }
    }

    /**
     * 单条消息流式对话（与 {@code LlamaHelper.chatSend} 同签名）：NPU 优先，失败回退 llama.cpp。
     *
     * <p>对话页的流式回复走的就是这条；拆掉顶层拦截后必须由它接管，否则 NPU 引擎开启时
     * 本地模型未加载（启动时跳过预加载）会导致 chatSend 失败。
     */
    public static void chatSend(String message, int maxTokens, float temperature, float topP, int topK,
                                boolean enableThinking, LlamaHelper.TokenCallback callback) {
        if (shouldRouteToNpu(enableThinking, false, false) && ensureLoadedBlocking()) {
            try {
                streamNpu(new String[]{"user"}, new String[]{message},
                        maxTokens == 0 ? 2048 : maxTokens, callback);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "NPU chatSend 失败，回退 llama.cpp: " + t);
                releaseNpuBeforeFallback();
            }
        }
        LlamaHelper.chatSend(message, maxTokens, temperature, topP, topK, enableThinking, callback);
    }


    /**
     * JSON 聊天入口（与 {@code LlamaHelper.chatJson} 同签名）。
     *
     * <p><b>这是对话页与本地 Agent 的真实入口</b>（`ModelExecutionBridge` 3 处 + `AgentLoopEngine`
     * + `AIChatViewModel`），之前只路由 chatSend/generate* 全部无效就是因为漏了它。
     *
     * <p>事件协议与 native 完全一致（`native-lib.cpp` L3569/3581/3720/3929/3449）：
     * <pre>
     *   {"type":"token","content":...,"is_tool_call":false}
     *   {"type":"thinking","content":...}      // 思考段
     *   {"type":"tool_call","id":...,"name":...,"arguments":...}
     *   {"type":"complete","content":"全文"}
     *   {"type":"error","message":...}
     * </pre>
     * 请求解析失败 / NPU 未就绪 / 推理异常 → 原样回退 `LlamaHelper.chatJson`（行为与改动前一致）。
     */
    public static void chatJson(String requestJson, LlamaHelper.JsonCallback callback) {
        if (callback == null) {
            return;
        }
        if (requestJson == null || requestJson.isEmpty()) {
            emit(callback, "{\"type\":\"error\",\"message\":\"empty request\"}");
            return;
        }

        org.json.JSONArray msgs = null;
        int maxTokens = 0;
        boolean thinking = false;
        String toolsJson = null;
        try {
            org.json.JSONObject req = new org.json.JSONObject(requestJson);
            msgs = req.optJSONArray("messages");
            maxTokens = req.optInt("max_tokens", 0);
            thinking = req.optBoolean("enable_thinking", false);
            org.json.JSONArray tools = req.optJSONArray("tools");
            if (tools != null && tools.length() > 0) {
                toolsJson = tools.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "chatJson 请求解析失败，回退 llama.cpp: " + t);
        }

        // NPU-TRIM-HISTORY: NPU 没有 native 的 KV/prefix 复用，每轮都要全量重算；
        // 且超长 prompt 会被截断导致工具轮错乱。这里按内存预算规划出的 nCtx 裁剪历史。
        // 裁剪前先让引擎按内存预算规划一次（否则会用兜底 8192，浪费上下文）
        try {
            if (appContext != null) {
                NpuLlmChat.planForCurrentModel(appContext);
            }
        } catch (Throwable ignored) {
        }
        msgs = trimMessagesForNCtx(msgs, NpuLlmChat.plannedNCtxValue(), toolsJson);

        boolean routable = msgs != null && msgs.length() > 0
                && shouldRouteToNpu(thinking, toolsJson != null, false);
        if (!routable || !ensureLoadedBlocking()) {
            Log.w(TAG, "chatJson 未走 NPU: routable=" + routable
                    + ", npuLoaded=" + NpuLlmChat.isLoaded()
                    + ", state=" + NpuLlmChat.getStateName()
                    + " -> 回退 llama.cpp（NPU 模式下本地模型可能未加载，会表现为长时间无响应）");
            LlamaHelper.chatJson(requestJson, callback);
            return;
        }

        final String[] roles = new String[msgs.length()];
        final String[] contents = new String[msgs.length()];
        int toolRounds = 0;
        for (int i = 0; i < msgs.length(); i++) {
            org.json.JSONObject m = msgs.optJSONObject(i);
            String role = (m == null || m.optString("role").isEmpty()) ? "user" : m.optString("role");
            String content = (m == null) ? "" : m.optString("content", "");
            // NPU-TOOLMSG: GenieX 的 ChatMessage 只有 role+content，结构化 tool_calls/tool_call_id
            // 在这一层会丢失 → 第二轮（工具结果回填后）模型接不上"谁调了什么、结果配给谁"，
            // 表现就是"卡在工具执行后"。这里做文本化降级：
            //   ① assistant.tool_calls → 追加 <tool_call>{…}</tool_call> 文本，保留调用痕迹
            //   ② tool 角色 / 带 tool_call_id 的消息 → 转成 user 轮并标注 id，保证能被模板渲染
            // STRUCTURED-MSG: 不再做"文本化降级"——GenieX 的 ChatMessage 原生支持 toolCalls/
            // toolCallId/toolName，这里只需保持 role/content 原样，结构化字段由 messagesJson 原样传下去。
            if (m != null && ("tool".equals(role) || !m.optString("tool_call_id", "").isEmpty()
                    || m.optJSONArray("tool_calls") != null)) {
                toolRounds++;
            }
            roles[i] = role;
            contents[i] = content;
        }
        Log.i(TAG, "chatJson→NPU 消息转换: " + msgs.length() + " 条, 工具结果轮=" + toolRounds);

        Log.i(TAG, "chatJson→NPU: messages=" + msgs.length() + ", maxTokens=" + maxTokens
                + ", thinking=" + thinking + ", tools=" + (toolsJson == null ? 0 : toolsJson.length()) + "字符");

        final StringBuilder full = new StringBuilder();
        final ThinkStreamer streamer = new ThinkStreamer(callback);
        final int[] emittedCalls = new int[]{0};   // 已下发的 tool_call 条数（增量去重）
        try {
            // NPU-META-EVENT: 与 native 协议对齐 —— 首个 token 前下发 meta（思考标签）。
            // AgentLoopEngine 用 ThinkingTagConfig.fromJson(event) 读 thinking_start_tag /
            // thinking_end_tags 来做思考剥离与流式；缺这个事件会让工具轮的输出解析错乱。
            String thinkStart = " thinking";
            String thinkEnd = " response";
            try {
                String mn = NpuLlmChat.currentOrPreferredModelName().toLowerCase();
                if (mn.contains("qwen3.5") || mn.contains("qwen35")) {
                    thinkStart = "<think>";
                    thinkEnd = "</think>";
                }
            } catch (Throwable ignored) {
            }
            emit(callback, "{\"type\":\"meta\",\"thinking_start_tag\":"
                    + org.json.JSONObject.quote(thinkStart) + ",\"thinking_end_tags\":["
                    + org.json.JSONObject.quote(thinkEnd) + "]}");

            NpuLlmChat.sendChatAsync(roles, contents, maxTokens > 0 ? maxTokens : 2048, thinking,
                    new NpuLlmChat.GenerateListener() {
                        @Override
                        public void onToken(String text) {
                            full.append(text);
                            // 边生成边分流发出：思考段走 thinking 事件、正文走 token 事件。
                            streamer.feed(text);
                            // NPU-TOOLCALL-STREAM: 与 native 一致地"边生成边下发"结构化 tool_call，
                            // 不依赖轮末一次性事件（Agent 的流式去重逻辑按 name+arguments 判重）。
                            emitNewToolCalls(callback, full.toString(), emittedCalls);
                        }

                        @Override
                        public void onCompleted(int tokens, float tps, long elapsedMs) {
                            streamer.flush();
                            java.util.List<String> allCalls = toolCallEvents(full.toString());
                            for (int i = emittedCalls[0]; i < allCalls.size(); i++) {
                                emit(callback, allCalls.get(i));
                            }
                            emittedCalls[0] = allCalls.size();
                            // NPU-AGENT-DIAG: 事件汇总（判定"模型没吐工具调用"还是"协议不对齐"）
                            String allText = full.toString();
                            Log.i(TAG, "NPU Agent 轮次汇总: 正文 " + allText.length() + " 字符, tool_call="
                                    + allCalls.size() + ", 含<tool_call>标签=" + allText.contains("<tool_call"));
                            emit(callback, "{\"type\":\"complete\",\"content\":"
                                    + org.json.JSONObject.quote(full.toString()) + "}");
                        }

                        @Override
                        public void onError(String message) {
                            emit(callback, "{\"type\":\"error\",\"message\":"
                                    + org.json.JSONObject.quote("NPU 推理失败: " + message) + "}");
                        }
                    }, toolsJson, msgs.toString());
        } catch (Throwable t) {
            Log.w(TAG, "NPU chatJson 失败，回退 llama.cpp: " + t);
            releaseNpuBeforeFallback();
            LlamaHelper.chatJson(requestJson, callback);
        }
    }

    private static void emit(LlamaHelper.JsonCallback cb, String json) {
        try {
            cb.onJson(json);
        } catch (Throwable ignored) {
        }
    }

    private static String tokenEvent(String text) {
        return "{\"type\":\"token\",\"content\":" + org.json.JSONObject.quote(text)
                + ",\"is_tool_call\":false}";
    }

    private static String thinkingEvent(String text) {
        return "{\"type\":\"thinking\",\"content\":" + org.json.JSONObject.quote(text) + "}";
    }

    /**
     * 思考段/正文的**增量分流器**：把模型输出按 think 标记拆成 thinking / token 事件实时发出。
     *
     * <p>为什么需要：思考模式下若等整段生成完再发，界面只能停在"思考中…"没有任何反馈
     * （4B 在 NPU 上约 19 t/s，长回答会像卡死）。这里保留少量尾部字符以处理标记跨 token
     * 的情况，其余立刻发出，实现真正的逐段流式。
     */
    private static final class ThinkStreamer {
        private final LlamaHelper.JsonCallback cb;
        private final StringBuilder carry = new StringBuilder();
        private boolean inThink = false;

        ThinkStreamer(LlamaHelper.JsonCallback cb) {
            this.cb = cb;
        }

        void feed(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            carry.append(text);
            drain(false);
        }

        void flush() {
            drain(true);
        }

        private void drain(boolean end) {
            while (carry.length() > 0) {
                if (!inThink) {
                    int i = carry.indexOf("<think");
                    if (i >= 0) {
                        if (i > 0) {
                            emitToken(carry.substring(0, i));
                            carry.delete(0, i);
                        }
                        int gt = carry.indexOf(">");
                        if (gt < 0) {
                            if (end) {
                                emitToken(carry.toString());
                                carry.setLength(0);
                            }
                            return;
                        }
                        carry.delete(0, gt + 1);
                        inThink = true;
                        continue;
                    }
                    int keep = end ? 0 : 6;
                    if (carry.length() > keep) {
                        int cut = carry.length() - keep;
                        emitToken(carry.substring(0, cut));
                        carry.delete(0, cut);
                    }
                    return;
                }
                int i = carry.indexOf("</think");
                if (i >= 0) {
                    if (i > 0) {
                        emitThinking(carry.substring(0, i));
                        carry.delete(0, i);
                    }
                    int gt = carry.indexOf(">");
                    if (gt < 0) {
                        if (end) {
                            carry.setLength(0);
                        }
                        return;
                    }
                    carry.delete(0, gt + 1);
                    inThink = false;
                    continue;
                }
                int keep = end ? 0 : 8;
                if (carry.length() > keep) {
                    int cut = carry.length() - keep;
                    emitThinking(carry.substring(0, cut));
                    carry.delete(0, cut);
                }
                return;
            }
        }

        private void emitToken(String s) {
            if (!s.isEmpty()) {
                emit(cb, tokenEvent(s));
            }
        }

        private void emitThinking(String s) {
            if (!s.isEmpty()) {
                emit(cb, thinkingEvent(s));
                // 兼容消费方：部分路径认 reasoning 事件（native 两种都会发）
                emit(cb, "{\"type\":\"reasoning\",\"content\":" + org.json.JSONObject.quote(s) + "}");
            }
        }
    }

    /** 从模型输出里解析 <tool_call>{"name":…,"arguments":{…}}</tool_call>，按 native 协议 emit */
    private static java.util.List<String> toolCallEvents(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", java.util.regex.Pattern.DOTALL)
                    .matcher(text);
            int idx = 0;
            while (m.find()) {
                org.json.JSONObject call = new org.json.JSONObject(m.group(1));
                String name = call.optString("name", "");
                if (name.isEmpty()) {
                    continue;
                }
                org.json.JSONObject ev = new org.json.JSONObject();
                ev.put("type", "tool_call");
                ev.put("id", "npu_call_" + (idx++));
                ev.put("name", name);
                Object args = call.opt("arguments");
                ev.put("arguments", args == null ? "{}" : args.toString());
                out.add(ev.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "tool_call 解析失败: " + t);
        }
        return out;
    }

    /** 回退到 llama.cpp 前释放 NPU 权重，避免两套模型同时常驻 */
    private static void releaseNpuBeforeFallback() {
        try {
            NpuLlmChat.release();
        } catch (Throwable ignored) {
        }
    }

    private static void streamNpu(String[] roles, String[] contents, int maxTokens,
                                  LlamaHelper.TokenCallback callback) {
        final StringBuilder full = new StringBuilder();
        NpuLlmChat.sendChatAsync(roles, contents, maxTokens, new NpuLlmChat.GenerateListener() {
            @Override
            public void onToken(String text) {
                full.append(text);
                if (callback != null) {
                    callback.onToken(text);
                }
            }

            @Override
            public void onCompleted(int tokens, float tps, long elapsedMs) {
                if (callback != null) {
                    callback.onComplete(full.toString());
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.onError("NPU 推理失败: " + message);
                }
            }
        });
    }

    /**
     * 确保 NPU 模型已加载（阻塞）。失败返回 false，由调用方回退 llama.cpp。
     * 已加载则立即返回（无额外开销）。
     */
    private static boolean ensureLoadedBlocking() {
        if (NpuLlmChat.isLoaded()) {
            return true;
        }
        Context ctx = appContext;
        if (ctx == null) {
            Log.w(TAG, "appContext 为空，无法加载 NPU 模型（回退 llama.cpp）");
            return false;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        try {
            com.oilquiz.app.ai.service.AIService.getInstance(ctx).ensureNpuLoadedAsync(loaded -> {
                ok[0] = loaded;
                latch.countDown();
            });
            if (!latch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "NPU 模型加载超时，回退 llama.cpp");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "NPU 模型加载异常，回退 llama.cpp: " + t);
            return false;
        }
        return ok[0] && NpuLlmChat.isLoaded();
    }

    private static String[] roles(List<PromptBuilder.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new String[]{"user"};
        }
        String[] out = new String[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            PromptBuilder.Message m = messages.get(i);
            String role = (m == null || m.role() == null || m.role().isEmpty()) ? "user" : m.role();
            out[i] = role;
        }
        return out;
    }

    private static String[] contents(List<PromptBuilder.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new String[]{""};
        }
        List<String> out = new ArrayList<>(messages.size());
        for (PromptBuilder.Message m : messages) {
            out.add(m == null || m.content() == null ? "" : m.content());
        }
        return out.toArray(new String[0]);
    }

    /**
     * NPU-TRIM-HISTORY：按 nCtx 裁剪历史消息（保留 system 与最近的对话）。
     * 估算：1 token ≈ 3.5 个字符（中英混排的保守值）；为输出留 25% 余量。
     */
    private static org.json.JSONArray trimMessagesForNCtx(org.json.JSONArray msgs, int nCtx, String toolsJson) {
        if (msgs == null || msgs.length() == 0 || nCtx <= 0) {
            return msgs;
        }
        try {
            // 预算按 token 估：中文 ≈1.5 字符/token、其它 ≈4 字符/token（原先统一按 3.5 字符，
            // 中文密集时会低估 token 数 → 真会撑爆；JSON/英文又会高估 → 浪费）。
            int toolsTokens = estTokens(toolsJson);
            int budgetTokens = (int) (nCtx * 0.75) - toolsTokens;
            Log.i(TAG, "NPU 预算: nCtx=" + nCtx + " -> 可用约 " + budgetTokens
                    + " tokens（已扣 tools " + toolsTokens + " tokens / " 
                    + (toolsJson == null ? 0 : toolsJson.length()) + " 字符）");
            int total = 0;
            for (int i = 0; i < msgs.length(); i++) {
                org.json.JSONObject m = msgs.optJSONObject(i);
                total += (m == null) ? 0 : estTokens(m.optString("content", ""));
            }
            if (total <= budgetTokens) {
                return msgs;
            }
            java.util.List<org.json.JSONObject> keep = new java.util.ArrayList<>();
            org.json.JSONObject system = null;
            int used = 0;
            for (int i = msgs.length() - 1; i >= 0; i--) {
                org.json.JSONObject m = msgs.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                boolean isSystem = "system".equals(m.optString("role"));
                if (isSystem) {
                    system = m;   // system 单独保留（放最前）
                    continue;
                }
                int len = estTokens(m.optString("content", ""));
                if (used + len > budgetTokens && !keep.isEmpty()) {
                    break;
                }
                keep.add(m);
                used += len;
            }
            java.util.Collections.reverse(keep);
            org.json.JSONArray out = new org.json.JSONArray();
            if (system != null) {
                out.put(system);
            }
            for (org.json.JSONObject m : keep) {
                out.put(m);
            }
            Log.w(TAG, "NPU 历史裁剪: " + msgs.length() + " -> " + out.length()
                    + " 条（约 " + total + " -> " + (used + (system == null ? 0 : estTokens(system.optString("content"))))
                    + ", nCtx=" + nCtx + "）");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "历史裁剪失败（按原样发送）: " + t);
            return msgs;
        }
    }

    /**
     * NPU-TOOLCALL-STREAM：把 full 文本里**新出现**的完整 &lt;tool_call&gt; 块转成结构化事件下发，
     * 已下发的条数由 counter[0] 记录（避免重复执行工具）。
     */
    private static void emitNewToolCalls(LlamaHelper.JsonCallback cb, String full, int[] counter) {
        try {
            java.util.List<String> all = toolCallEvents(full);
            for (int i = counter[0]; i < all.size(); i++) {
                emit(cb, all.get(i));
            }
            if (all.size() > counter[0]) {
                counter[0] = all.size();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 粗略 token 估算：CJK 字符 ≈1.5 字符/token，其它（ASCII/JSON）≈4 字符/token。
     * 只用于"裁剪预算"这一用途，宁可略保守。
     */
    private static int estTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        int other = s.length() - cjk;
        return (int) (cjk / 1.5 + other / 4.0) + 1;
    }
}
