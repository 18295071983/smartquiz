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
        if (needsTools || needsVision) {
            return false;   // 这两个能力当前仍留在 llama.cpp（工具调用/多模态待接）
        }
        if (!NpuLlmChat.isEngineEnabled()) {
            return false;
        }
        return NpuLlmChat.isLoaded() || NpuLlmChat.hasUsableModel();
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
            NpuLlmChat.ensureLoadedAsync(ctx, new NpuLlmChat.LoadListener() {
                @Override
                public void onLoaded(String modelName) {
                    ok[0] = true;
                    Log.i(TAG, "NPU 模型已加载: " + modelName);
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "NPU 模型加载失败: " + message);
                    latch.countDown();
                }
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
}
