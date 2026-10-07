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
                Log.w(TAG, "NPU chatSend 失败: " + t);
                releaseNpuBeforeFallback();
                if (!canFallbackToLlama()) {
                    Log.w(TAG, "NPU chatSend 失败但引擎开启中 → 不回退 llama.cpp（它不可用）");
                    if (callback != null) {
                        callback.onError("NPU 推理失败: " + t.getMessage());
                    }
                    return;
                }
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
        // NPU-SAMPLER: 采样参数此前整条丢失（App 侧组了 temperature/top_p/top_k，NPU 路径没读），
        // 导致生成完全依赖 GenieX native 默认（SamplerConfig 各字段默认 0）。
        // 这里读出来透传给 sendChatAsync；缺省 -1 由 Kotlin 侧回落到 0.6/0.9/40 与 llama.cpp 对齐。
        float temperature = -1f;
        float topP = -1f;
        int topK = -1;
        final long entryMs = System.currentTimeMillis();
        Log.i(TAG, "NPU-PROFILE 入口: chatJson 收到请求（线程 " + Thread.currentThread().getName() + "）");
        try {
            org.json.JSONObject req = new org.json.JSONObject(requestJson);
            msgs = req.optJSONArray("messages");
            maxTokens = req.optInt("max_tokens", 0);
            thinking = req.optBoolean("enable_thinking", false);
            org.json.JSONArray tools = req.optJSONArray("tools");
            if (tools != null && tools.length() > 0) {
                toolsJson = tools.toString();
            }
            if (req.has("temperature")) temperature = (float) req.optDouble("temperature", -1);
            if (req.has("top_p")) topP = (float) req.optDouble("top_p", -1);
            if (req.has("top_k")) topK = req.optInt("top_k", -1);
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

        // NPU-PROFILE-SEG: 分段计时。实测"模型已就绪后仍有一段无日志的空等"（数秒到十几秒），
        // 需要确定它落在哪一段：planForCurrentModel（读 GGUF 头）/ ensureLoadedBlocking（加载或等在途）
        // / 消息转换 / 真正的 sendChatAsync。没有这三个数字就只能靠猜。
        final long segT0 = System.currentTimeMillis();
        Log.i(TAG, "NPU-PROFILE 入口→规划前耗时: " + (segT0 - entryMs) + "ms");
        boolean routable = msgs != null && msgs.length() > 0
                && shouldRouteToNpu(thinking, toolsJson != null, false);
        final long segTPlan = System.currentTimeMillis();
        final boolean loaded = routable && ensureLoadedBlocking();
        final long segTLoad = System.currentTimeMillis();
        if (!routable || !loaded) {
            // 未走 NPU：清掉待注入图片，避免污染下一轮 NPU 生成
            try {
                NpuLlmChat.setPendingImagePaths(java.util.Collections.emptyList());
            } catch (Throwable ignored) {
            }
            // NPU-FALLBACK-GUARD：回退 llama.cpp 只在**确实应该**的时候做。
            // 若 NPU 引擎处于开启状态，llama.cpp 侧的模型/上下文根本不存在（NPU 模式下故意不建），
            // 此时回退等于把请求送进死路，且会让上层误以为是 llama 的问题。
            // 只有"NPU 没开"或"NPU 明确 ERROR"才回退；"在途/未就绪"返回明确错误。
            final boolean npuOn = com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled();
            final String npuState = com.oilquiz.app.ai.engine.NpuLlmChat.getStateName();
            if (npuOn && !"ERROR".equals(npuState)) {
                Log.w(TAG, "chatJson NPU 尚未就绪（state=" + npuState + ", routable=" + routable
                        + "）→ 不回退 llama.cpp（NPU 模式下它不可用）");
                try {
                    if (callback != null) {
                        callback.onError("NPU 模型尚未就绪（state=" + npuState + "），请稍候重试");
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
            Log.w(TAG, "chatJson 未走 NPU: routable=" + routable
                    + ", npuLoaded=" + NpuLlmChat.isLoaded()
                    + ", state=" + npuState + ", npuOn=" + npuOn
                    + " -> 回退 llama.cpp");
            LlamaHelper.chatJson(requestJson, callback);
            return;
        }
        Log.i(TAG, "NPU-PROFILE 分段: 预算规划=" + (segTPlan - segT0) + "ms, 确保加载="
                + (segTLoad - segTPlan) + "ms（合计 " + (segTLoad - segT0) + "ms）");

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

            // NPU-THINK-SPLIT：分流器用**同一份**标签配置，并以 enable_thinking 作为
            // "标记前内容归属"的判据 —— 不再依赖"模型是否会输出标签"这种不可控假设。
            final ThinkStreamer streamer = new ThinkStreamer(callback, thinkStart, thinkEnd, thinking);

            // 推理期 WakeLock：NPU 生成同样怕灭屏/切后台（与 llama.cpp 路径一致）
            com.oilquiz.app.ai.engine.NpuEngineState.get().beginInference();
        acquireLock(appContext);
            // NPU-MAXTOKENS: 上限收敛 —— 官方 demo 用 2048；原先沿用请求里的 16384，模型会一直写
            // （实测 590 token / 3m32s，观感"永远在转"）。思考态给 4096。
            // 分档：Agent（带工具）需要更长输出（工具调用 + 工具结果后的最终回答）；
            // 普通对话则收敛到 2048（官方 demo 值），避免"一直写"。
            boolean hasToolsForLimit = toolsJson != null && !toolsJson.isEmpty();
            int npuCap = hasToolsForLimit ? 8192 : (thinking ? 4096 : 2048);
            int npuMaxTokens = Math.min(maxTokens > 0 ? maxTokens : npuCap, npuCap);
            Log.i(TAG, "NPU maxTokens=" + npuMaxTokens + "（请求 " + maxTokens + ", thinking=" + thinking + "）");
            NpuLlmChat.sendChatAsync(roles, contents, npuMaxTokens, thinking,
                    new NpuLlmChat.GenerateListener() {
                        @Override
                        public void onToken(String text) {
                            full.append(text);
                            // 边生成边分流发出：思考段走 thinking 事件、正文走 token 事件。
                            streamer.feed(text);
                            com.oilquiz.app.ai.engine.NpuEngineState.get().onToken();
                            // NPU-TOOLCALL-STREAM: 与 native 一致地"边生成边下发"结构化 tool_call，
                            // 不依赖轮末一次性事件（Agent 的流式去重逻辑按 name+arguments 判重）。
                            emitNewToolCalls(callback, full.toString(), emittedCalls);
                        }

                        @Override
                        public void onCompleted(int tokens, float tps, long elapsedMs) {
                            streamer.flush();
                            com.oilquiz.app.ai.engine.NpuEngineState.get().endInference(
                                    tokens, tps);
                            java.util.List<String> allCalls = toolCallEvents(full.toString());
                            for (int i = emittedCalls[0]; i < allCalls.size(); i++) {
                                emit(callback, allCalls.get(i));
                            }
                            emittedCalls[0] = allCalls.size();
                            // NPU-AGENT-DIAG: 事件汇总（判定"模型没吐工具调用"还是"协议不对齐"）
                            String allText = full.toString();
                            Log.i(TAG, "NPU Agent 轮次汇总: 正文 " + allText.length() + " 字符, tool_call="
                                    + allCalls.size() + ", 含<tool_call>标签=" + allText.contains("<tool_call"));
                            releaseLock(appContext);
                            // THINK-SPLIT：complete 下发**剥离后的干净正文**（与流式中途一致），
                            // 否则思考段会被当作正文覆盖进主消息（审计定位的"思考/正文混在一起"）。
                            String finalBody = streamer.getCleanBody();
                            emit(callback, "{\"type\":\"complete\",\"content\":"
                                    + org.json.JSONObject.quote(finalBody) + "}");
                        }

                        @Override
                        public void onError(String message) {
                            releaseLock(appContext);
                            com.oilquiz.app.ai.engine.NpuEngineState.get().endInference(0, 0f);
                            emit(callback, "{\"type\":\"error\",\"message\":"
                                    + org.json.JSONObject.quote("NPU 推理失败: " + message) + "}");
                        }
                    }, toolsJson, msgs.toString(), temperature, topP, topK);
        } catch (Throwable t) {
            Log.w(TAG, "NPU chatJson 失败: " + t);
            releaseNpuBeforeFallback();
            if (!canFallbackToLlama()) {
                Log.w(TAG, "NPU chatJson 失败但引擎开启中 → 不回退 llama.cpp（它不可用）");
                emit(callback, "{\"type\":\"error\",\"message\":"
                        + org.json.JSONObject.quote("NPU 推理失败: " + t.getMessage()) + "}");
                return;
            }
            Log.w(TAG, "NPU chatJson 失败，回退 llama.cpp");
            LlamaHelper.chatJson(requestJson, callback);
        }
    }

    /**
     * NPU-FALLBACK-GUARD：当前是否**允许**回退到 llama.cpp。
     *
     * <p>NPU 引擎开启时，llama.cpp 的模型与聊天上下文根本不存在（NPU 模式下故意不创建），
     * 回退过去等于把请求送进死路，还会让上层误判成 llama 的问题。只有两种情况值得回退：
     * ① NPU 引擎没开（本来就走 llama）；② NPU 状态明确为 ERROR（真的坏了）。</p>
     *
     * <p>注意"在途/未就绪"不属于回退理由——那只是还没加载完，应当让调用方等待或重试。</p>
     */
    private static boolean canFallbackToLlama() {
        try {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                return true;
            }
            return "ERROR".equals(com.oilquiz.app.ai.engine.NpuLlmChat.getStateName());
        } catch (Throwable t) {
            return true;   // NPU 不可用时保持原回退行为
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
     * 思考段/正文的**增量分流器**：把模型原始输出拆成 thinking / token 事件，实时发出。
     *
     * <p><b>为什么需要</b>：思考模式下若等整段生成完再发，界面只能停在「思考中…」没有反馈
     * （NPU 上长回答会像卡死）。所以要按增量边解析边发。</p>
     *
     * <p><b>为什么重写（2026-10-07）</b>：旧实现只按字面 {@code "<think"} / {@code "</think"}
     * 切分，且有若干实现缺陷，导致思考内容与正文分不开、一起渲染进主消息：
     * <ol>
     *   <li><b>只认开场标签</b>：Qwen3.5 的 chat template 尾部已写成
     *       {@code <|im_start|>assistant\n<think>\n\n</think>\n\n} —— 即**模板里已带标签**，
     *       模型只需往中间填内容，输出里往往**没有开场标签、只有结尾标签**（甚至两个都没有）。
     *       旧实现的 {@code inThink} 因此永远为 false，思考内容整段被当成正文。</li>
     *   <li><b>查找 "&gt;" 的位置是全局的</b>（{@code carry.indexOf(">")}）而不是从标签起点往后找，
     *       标签后若先出现别的 {@code >} 会把标签尾部切错，残留碎片漏进正文。</li>
     *   <li><b>保留长度写死为魔法数</b>（6 / 8），与真实标签长度无关，标签较长时会误切。</li>
     *   <li><b>进入思考后不再检测开场标签</b>，多段/重复标签无法处理。</li>
     * </ol>
     *
     * <p><b>新设计的语义</b>：思考内容**必然排在正文之前**，因此标签只是"思考/正文的分界标记"，
     * 不要求成对出现：
     * <ul>
     *   <li>见到<b>开场标签</b> → 其后内容为思考段；</li>
     *   <li>见到<b>结尾标签</b> → 其前内容为思考段、其后为正文（**这条覆盖模板已给开场的形态**）；</li>
     *   <li>两个标签<b>都没有</b> → 全部为正文；</li>
     *   <li>只有开场、没有结尾（模型被截断）→ 其后内容全部为思考段。</li>
     * </ul>
     * 标签匹配大小写不敏感、允许标签内空白；跨 chunk 的半个标签会被保留到下一块再判，
     * 不会把标签碎片漏进正文。
     */
    static final class ThinkStreamer {

        /** 分流位置 */
        private enum Mode {
            /** 标记出现前：此时尚不知道标记前的内容属于哪一段 */
            BEFORE_MARK,
            /** 已越过分界：正文段 */
            AFTER_MARK
        }

        private final LlamaHelper.JsonCallback cb;
        /** 开场标签（小写） */
        private final String thinkStart;
        /** 结尾标签（小写） */
        private final String thinkEnd;
        /**
         * 本轮是否请求了思考（来自 {@code enable_thinking}）。
         *
         * <p>这是"标记前内容归属"的判据，让实现**不依赖猜测或模型是否输出标签**：
         * <ul>
         *   <li>请求了思考（true）：标记前的内容是思考段 → 发 thinking 事件（它必然在正文之前）；</li>
         *   <li>未请求思考（false）：本轮不该有思考段 → 标记前的内容直接当正文发，
         *       标签（若模型画蛇添足输出）只是被吃掉，不影响正文。</li>
         * </ul>
         * 两种情况下"越过标记之后一律为正文"，所以纯正文模型也不会被误判。</p>
         */
        private final boolean expectThinking;

        /** 尚未定论、等待更多输入的缓冲 */
        private final StringBuilder carry = new StringBuilder();
        /**
         * 已下发的**干净正文**（不含思考段、不含标签）。
         *
         * <p>WHY（2026-10-07 审计定位）：轮末的 {@code complete} 事件原先直接下发模型原始输出，
         * 其中**包含思考段全文与 &lt;think&gt; 标签**；而流式期间正文是剥离过的。于是"流式中途"
         * 与"终态正文"不一致 —— 收尾时会把思考内容覆盖进主消息。这里累积剥离后的正文供 complete 使用。
         * 注意：工具调用解析仍应使用**原始全文**（标签可能参与解析），所以两者分开保存。</p>
         */
        private final StringBuilder cleanBody = new StringBuilder();
        private Mode mode = Mode.BEFORE_MARK;

        ThinkStreamer(LlamaHelper.JsonCallback cb) {
            this(cb, "<think>", "</think>", true);
        }

        ThinkStreamer(LlamaHelper.JsonCallback cb, String thinkStart, String thinkEnd,
                      boolean expectThinking) {
            this.cb = cb;
            // 统一小写做匹配；空值回退默认，避免调用方传空导致永不分流
            this.thinkStart = (thinkStart == null || thinkStart.isEmpty())
                    ? "<think>" : thinkStart.toLowerCase(java.util.Locale.US);
            this.thinkEnd = (thinkEnd == null || thinkEnd.isEmpty())
                    ? "</think>" : thinkEnd.toLowerCase(java.util.Locale.US);
            this.expectThinking = expectThinking;
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

        /** 已下发的干净正文（不含思考段与标签），供轮末 complete 事件使用 */
        String getCleanBody() {
            return cleanBody.toString();
        }

        /**
         * 主循环：反复处理缓冲，直到剩余内容必须等待更多输入为止。
         *
         * <p>把实现写成"先判定分界、再决定下发"的直线逻辑，避免把"标签识别"和
         * "半个标签保留"混在一处（旧实现正是因此把 {@code </thi} 当内容发出去）。</p>
         */
        private void drain(boolean end) {
            while (true) {
                if (mode == Mode.AFTER_MARK) {
                    // 正文段：不再识别标签（正文里出现 "<" 不该被误判）
                    String all = takeAll(end);
                    emitToken(all);
                    return;
                }

                // BEFORE_MARK：找分界标签中更靠前的那个
                int si = indexOfTag(thinkStart);
                int ei = indexOfTag(thinkEnd);
                int cut;
                boolean isEndTag;
                if (si >= 0 && (ei < 0 || si < ei)) {
                    cut = si;
                    isEndTag = false;
                } else if (ei >= 0) {
                    cut = ei;
                    isEndTag = true;
                } else {
                    // 没有完整标签：只下发"确定不含半个标签"的前缀，其余留待后续
                    String safe = takeSafe(end);
                    if (expectThinking) {
                        emitThinking(safe);
                    } else {
                        emitToken(safe);
                    }
                    return;
                }

                int gt = carry.indexOf(">", cut);
                if (gt < 0) {
                    // 标签本身还没收全（如 "<think"）：等下一块。
                    // 分界之前的内容归属已确定，可先下发。
                    if (cut > 0) {
                        String before = carry.substring(0, cut);
                        if (expectThinking && isEndTag) {
                            emitThinking(before);
                        } else {
                            emitToken(before);
                        }
                        carry.delete(0, cut);
                    }
                    return;
                }

                // 分界之前的内容
                if (cut > 0) {
                    String before = carry.substring(0, cut);
                    if (expectThinking && isEndTag) {
                        emitThinking(before);
                    } else {
                        emitToken(before);
                    }
                }
                carry.delete(0, gt + 1);   // 连标签一起丢弃（标签不下发）

                if (isEndTag) {
                    // 结尾标签之后一律是正文
                    mode = Mode.AFTER_MARK;
                } else if (!expectThinking) {
                    // 开场标签但本轮没请求思考：模型多输出了标签，其后按正文处理
                    mode = Mode.AFTER_MARK;
                }
                // 开场标签 + 请求了思考：保持 BEFORE_MARK，继续等结尾标签
                continue;
            }
        }

        /** 未定段时：取出确定安全的前缀（末尾若有"半个标签"则保留） */
        private String takeSafe(boolean end) {
            if (carry.length() == 0) {
                return "";
            }
            if (end) {
                return takeAll(true);
            }
            // 从最长可能的后缀开始检查，找最长的一个"是某个标签前缀"的后缀并保留
            int maxKeep = Math.min(carry.length(), Math.max(thinkStart.length(), thinkEnd.length()) - 1);
            for (int keep = maxKeep; keep > 0; keep--) {
                String suffix = carry.substring(carry.length() - keep)
                        .toLowerCase(java.util.Locale.US);
                if (isPrefixOfEitherTag(suffix)) {
                    int cut = carry.length() - keep;
                    if (cut <= 0) {
                        return "";   // 整个缓冲都可能是半个标签，继续等
                    }
                    String out = carry.substring(0, cut);
                    carry.delete(0, cut);
                    return out;
                }
            }
            return takeAll(false);
        }

        /** 取出并清空缓冲 */
        private String takeAll(boolean end) {
            if (carry.length() == 0) {
                return "";
            }
            String out = carry.toString();
            carry.setLength(0);
            return out;
        }

        /**
         * suffix 是否为某个标签的**前缀**（本身可以比标签短，也可以更长）。
         *
         * <p>这是修复"半个标签被当成内容发出去"的关键函数。旧实现用
         * {@code tag.startsWith(suffix)} 判断，当 suffix 比 tag **短**时（如 {@code "</thi"}
         * 对 {@code "</think>"}）比较方向正确，但当 suffix 比 tag **长**时就会漏判；
         * 更早的版本则完全没做这个判断。这里统一成"逐字符比到较短者结束"。</p>
         */
        private boolean isPrefixOfEitherTag(String suffix) {
            return isPrefix(suffix, thinkStart) || isPrefix(suffix, thinkEnd);
        }

        /** 两个字符串中较短者是较长者的前缀（含相等） */
        private static boolean isPrefix(String a, String b) {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
                return false;
            }
            int n = Math.min(a.length(), b.length());
            for (int i = 0; i < n; i++) {
                if (a.charAt(i) != b.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        /** 查找标签位置（大小写不敏感；-1 表示当前缓冲里没有完整标签） */
        private int indexOfTag(String tag) {
            if (tag.isEmpty() || carry.length() < tag.length()) {
                return -1;
            }
            String lower = carry.toString().toLowerCase(java.util.Locale.US);
            return lower.indexOf(tag);
        }

        private void emitToken(String s) {
            if (!s.isEmpty()) {
                // 累积干净正文，供轮末 complete 事件使用（避免把思考段/标签带进终态正文）
                cleanBody.append(s);
                // NPU-PHASE-FIX（2026-10-07 实测定位）：正文 token 到达时必须把状态机推进到
                // GENERATING，否则阶段永远停在 PREPROCESS → 状态栏一直显示「⏳ 处理提示」
                // （用户实测现象："正文已经在出了，状态栏还写着处理提示"）。
                // 根因：NpuEngineState.onGeneratingStarted() **此前全项目零调用**
                // （只有 emitThinking() 调了 onThinkingStarted()），所以 GENERATING 分支
                // 永远进不去，连解码速度也就永远没机会显示。
                com.oilquiz.app.ai.engine.NpuEngineState.get().onGeneratingStarted();
                emit(cb, tokenEvent(s));
            }
        }

        private void emitThinking(String s) {
            if (s.isEmpty()) {
                return;
            }
            com.oilquiz.app.ai.engine.NpuEngineState.get().onThinkingStarted();
            emit(cb, thinkingEvent(s));
            // 兼容消费方：部分路径认 reasoning 事件（native 两种都会发）
            emit(cb, "{\"type\":\"reasoning\",\"content\":" + org.json.JSONObject.quote(s) + "}");
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
    /**
     * 回退 llama.cpp 时的处理。
     *
     * <p><b>2026-10-05 行为变更</b>：原先这里会 {@code NpuLlmChat.release()} 释放 NPU 权重，
     * 结果是"任何一次瞬态失败（超时/一次异常）都会把已加载的模型丢掉"，下一条消息又要
     * 重新加载（实测约 29 秒）—— 这正是"反应很慢"的主因之一。
     *
     * <p>现改为**保持常驻**（与官方 demo 的策略一致：模型只在换模型/换引擎时释放）：
     * 释放只发生在用户显式操作处 —— {@code ModelSelectorActivity.reloadNpuModel()}（换 NPU 模型）
     * 与 {@code InferenceRouter.disableNpuEngine()}（切回 llama.cpp 引擎）。
     */
    private static void releaseNpuBeforeFallback() {
        Log.i(TAG, "NPU 回退 llama.cpp（保持 NPU 权重常驻，不释放，避免下条消息重新加载）");
    }

    private static void streamNpu(String[] roles, String[] contents, int maxTokens,
                                  LlamaHelper.TokenCallback callback) {
        final StringBuilder full = new StringBuilder();
        NpuLlmChat.sendChatAsync(roles, contents, maxTokens, new NpuLlmChat.GenerateListener() {
            @Override
            public void onToken(String text) {
                // NPU-PHASE-FIX：这条路径与 chatJson 的 ThinkStreamer 一样，收到正文 token 时
                // 必须把状态机推进到 GENERATING（否则状态栏永远停在 PREPROCESS 的「处理提示」）
                com.oilquiz.app.ai.engine.NpuEngineState.get().onGeneratingStarted();
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
        final String[] err = {null};
        try {
            com.oilquiz.app.ai.service.AIService.getInstance(ctx).ensureNpuLoadedAsync(loaded -> {
                ok[0] = loaded;
                latch.countDown();
            });
            if (!latch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // NPU-LOAD-WAIT：超时不再当作失败回退。在途加载仍在继续（AIService 侧有排队机制），
                // 此时回落 llama.cpp 在 NPU 模式下必然不可用（llama 上下文不存在），
                // 只会把请求送进一条死路。这里继续轮询到 isLoaded() 或状态明确为 ERROR。
                Log.w(TAG, "NPU 模型加载超时(" + LOAD_TIMEOUT_MS + "ms)，继续等待状态而非立即回退");
                err[0] = "timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "NPU 模型加载异常: " + t);
            err[0] = String.valueOf(t);
        }

        // 状态感知收尾：以 NpuLlmChat 的真实状态为准，而不是靠回调布尔值。
        // 只有明确 ERROR 才值得回退；其余情况（在途/就绪）都继续走 NPU。
        if (NpuLlmChat.isLoaded()) {
            return true;
        }
        if (ok[0] && NpuLlmChat.isLoaded()) {
            return true;
        }
        String st = NpuLlmChat.getStateName();
        if ("ERROR".equals(st)) {
            Log.w(TAG, "NPU 状态明确为 ERROR → 回退 llama.cpp" + (err[0] != null ? "（" + err[0] + "）" : ""));
            return false;
        }
        Log.w(TAG, "NPU 尚未就绪（state=" + st + ", ok=" + ok[0] + "）→ 本次不回退 llama.cpp");
        return false;
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

    /** NPU 推理期 WakeLock（经 AIService，失败静默） */
    private static void acquireLock(android.content.Context ctx) {
        try {
            if (ctx != null) {
                com.oilquiz.app.ai.service.AIService.getInstance(ctx).acquireInferenceLock();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void releaseLock(android.content.Context ctx) {
        try {
            if (ctx != null) {
                com.oilquiz.app.ai.service.AIService.getInstance(ctx).releaseInferenceLock();
            }
        } catch (Throwable ignored) {
        }
    }
}
