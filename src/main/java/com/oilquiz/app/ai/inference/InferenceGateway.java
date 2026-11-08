package com.oilquiz.app.ai.inference;

import com.oilquiz.app.ai.engine.NpuEngineRouter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * INFERENCE-GATEWAY(2026-11-08)：Agent / 普通对话进入推理引擎的唯一闸门。
 *
 * <p>当前真实调用链里，AgentLoopEngine、ModelExecutionBridge 都直接调
 * {@code NpuEngineRouter.chatJson}，AIService/InferenceRouter 又是另外两个入口。
 * 结果是没有统一的“谁在生成、上下文够不够、现在能不能进、取消打谁”。</p>
 *
 * <p>本类只做基础设施，不改模型/工具能力：</p>
 * <ul>
 *   <li>单生成锁：同一时刻只允许一个 chatJson/chatSend（native context 本身单路）；</li>
 *   <li>owner + generationId：调试/取消能定位是谁在跑；</li>
 *   <li>自动释放：complete/error/异常/显式 cancel 都会释放，避免一次超时把后续请求永久锁死；</li>
 *   <li>ContextLedger：统一真实 KV/n_ctx/剩余空间读数；</li>
 *   <li>就绪判据：chatReady = nativeValid && nCtx>0，而不是只看 isNativeStateValid。</li>
 * </ul>
 */
public final class InferenceGateway {

    private static final String TAG = "InferenceGateway";
    private static final InferenceGateway INSTANCE = new InferenceGateway();

    private final AtomicBoolean generating = new AtomicBoolean(false);
    private final AtomicLong nextId = new AtomicLong(0);
    private volatile long activeId = -1;
    private volatile String activeOwner = "";

    private InferenceGateway() {
    }

    public static InferenceGateway get() {
        return INSTANCE;
    }

    public boolean isGenerating() {
        return generating.get();
    }

    public String activeOwner() {
        return activeOwner;
    }

    public long activeGenerationId() {
        return activeId;
    }

    public ContextLedger.Snapshot context() {
        return ContextLedger.snapshot();
    }

    public boolean isChatReady() {
        return ContextLedger.isChatReady();
    }

    /**
     * BACKEND-LINK(2026-11-08)：本地 llama 请求必须在 chat context 真正建好后才能开始。
     * 之前只检查“模型在内存/native handle 有效”，会在 n_ctx 还没建出来时放行，
     * native 直接回 Helper context not initialized —— 用户看到“推理没有开始”。
     * NPU 走自己的 GenieX 生命周期，不要求 llama chat context。
     */
    public boolean isBackendReady() {
        try {
            if (NpuEngineRouter.isNpuActive()
                    || com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                // NPU 有自己的 GenieX 加载/错误语义；交给 NpuEngineRouter 报准确状态，
                // 不要用 llama chatReady 把它误判成“本地后端未就绪”。
                return true;
            }
        } catch (Throwable ignored) {
        }
        return ContextLedger.isChatReady();
    }

    /**
     * 只等，不在这里 initModel；装载由 UI/Bridge 的单飞流程负责。
     * 放在 begin 之前，等待期间不占用生成锁。
     */
    public boolean awaitBackendReady(long timeoutMs) {
        if (isBackendReady()) return true;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isBackendReady()) return true;
        }
        return false;
    }

    public void chatJson(String requestJson, LlamaHelper.JsonCallback callback) {
        chatJson(requestJson, callback, "unknown");
    }

    /**
     * 开始一次生成。返回 generationId；已有生成在跑返回 -1。
     * 调用方必须在 complete/error/异常/超时分支调用 {@link #end(long)}，
     * 或使用会自动释放的 {@link #chatJson}/{@link #chatSend}。
     */
    public long begin(String owner) {
        if (!generating.compareAndSet(false, true)) {
            return -1;
        }
        long id = nextId.incrementAndGet();
        activeId = id;
        activeOwner = owner == null ? "unknown" : owner;
        AILogger.i(TAG, "generation begin id=" + id + " owner=" + activeOwner
                + " (" + context().describe() + ")");
        return id;
    }

    /** 结束指定 generation；旧 id 不会误释放后来者的锁。 */
    public void end(long id) {
        if (id <= 0) return;
        if (activeId != id) return;
        activeId = -1;
        activeOwner = "";
        generating.set(false);
        AILogger.i(TAG, "generation end id=" + id);
    }

    /** 强制释放（超时/取消），owner 为空时释放当前任意生成。 */
    public void forceEnd(String owner) {
        long id = activeId;
        if (id <= 0) return;
        if (owner != null && !owner.isEmpty() && !owner.equals(activeOwner)) return;
        AILogger.w(TAG, "generation forceEnd id=" + id + " owner=" + activeOwner);
        end(id);
    }

    /** 取消当前生成：先停 native/NPU，再释放闸门。 */
    public void cancelAll() {
        try {
            NpuEngineRouter.stopGeneration();
        } catch (Throwable t) {
            AILogger.w(TAG, "stopGeneration failed: " + t.getMessage());
        }
        forceEnd(null);
    }

    /**
     * 统一 chatJson 入口：自动 begin/end，complete/error 自动释放。
     * 这是 AgentLoopEngine / ModelExecutionBridge 的 chatJson 唯一入口。
     */
    public void chatJson(String requestJson, LlamaHelper.JsonCallback callback, String owner) {
        if (callback == null) return;
        // 后端未就绪就不 begin：避免 UI 看到“已开始生成”，实际 native 还没上下文。
        if (!awaitBackendReady(30_000L)) {
            callback.onError("本地推理后端未就绪（chat context 未建立），"
                    + "本次请求没有开始。请等模型加载完成后再发送。");
            return;
        }
        long id = begin(owner);
        if (id < 0) {
            callback.onError("推理通道忙（当前 owner=" + activeOwner
                    + "），本次请求未执行");
            return;
        }
        final AtomicBoolean finished = new AtomicBoolean(false);
        LlamaHelper.JsonCallback wrapped = new LlamaHelper.JsonCallback() {
            @Override
            public void onJson(String json) {
                // 先释放再回调：ModelExecutionBridge 的 complete/tool 回调可能同步发起下一轮
                // chatJson，若先回调后释放会把自己判成 busy。
                if (isTerminal(json)) finishOnce();
                callback.onJson(json);
            }

            @Override
            public void onError(String error) {
                finishOnce();
                callback.onError(error);
            }

            private void finishOnce() {
                if (finished.compareAndSet(false, true)) end(id);
            }
        };
        try {
            NpuEngineRouter.chatJson(requestJson, wrapped);
        } catch (Throwable t) {
            if (finished.compareAndSet(false, true)) end(id);
            callback.onError("推理入口异常: " + t.getMessage());
        }
    }

    /** 统一 chatSend 入口（AIService 之外的普通流式路径可用）。 */
    public void chatSend(String message, int maxTokens, float temperature, float topP, int topK,
                         boolean enableThinking, LlamaHelper.TokenCallback callback, String owner) {
        if (callback == null) return;
        if (!awaitBackendReady(30_000L)) {
            callback.onError("本地推理后端未就绪（chat context 未建立），"
                    + "本次请求没有开始。请等模型加载完成后再发送。");
            return;
        }
        long id = begin(owner);
        if (id < 0) {
            callback.onError("推理通道忙（当前 owner=" + activeOwner
                    + "），本次请求未执行");
            return;
        }
        final AtomicBoolean finished = new AtomicBoolean(false);
        LlamaHelper.TokenCallback wrapped = new LlamaHelper.TokenCallback() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete(String fullText) {
                // 先释放再回调：完成回调里可能同步触发下一轮
                if (finished.compareAndSet(false, true)) end(id);
                callback.onComplete(fullText);
            }

            @Override
            public void onError(String error) {
                if (finished.compareAndSet(false, true)) end(id);
                callback.onError(error);
            }
        };
        try {
            NpuEngineRouter.chatSend(message, maxTokens, temperature, topP, topK,
                    enableThinking, wrapped);
        } catch (Throwable t) {
            if (finished.compareAndSet(false, true)) end(id);
            callback.onError("推理入口异常: " + t.getMessage());
        }
    }

    /** 轻量判定终态事件；只有 complete/error 会释放闸门。 */
    private static boolean isTerminal(String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(json);
            String type = o.optString("type", "");
            return "complete".equals(type) || "error".equals(type);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String describe() {
        return "generation=" + isGenerating()
                + " id=" + activeId
                + " owner=" + activeOwner
                + " | " + context().describe();
    }
}
