package com.oilquiz.app.ai.feature;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.OnlineInferenceService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 功能级"一段 prompt → 一段文本"的统一出词入口（2026-10-25）。
 *
 * <p>背景有两层，都得治：</p>
 * <ol>
 *   <li><b>白占内存</b>：{@code QuestionGenerateActivity} / {@code QuestionAnalyzeActivity} /
 *       {@code LearningAssistantActivity} / {@code TranslateActivity}（{@code AICenterActivity}
 *       四个按钮真正打开的页面）每个生成方法开头都是
 *       {@code if (!aiService.isInitialized()) aiService.initializeSafe();}——点一下小功能就把
 *       本地 GGUF 权重拉进内存。真机实测 {@code Native Heap Heap Alloc≈984MB}、swap 492MB，
 *       而用户全程用的是在线模型。同一份取向问题在 {@code ai/feature/} 下那四个同名工具类里
 *       也是一样的写法（它们目前 main 里没有调用方，一并收口，免得以后接上又漂）。</li>
 *   <li><b>主线程阻塞</b>：{@link AIService#initializeSafe()} 在主线程走的是
 *       {@code task.get(30, TimeUnit.SECONDS)}（AIService:627-643），而这些 Activity 是在
 *       按钮点击里直接调的——加载 1GB 权重远超 5 秒，是 ANR 发生器。所以这里提供
 *       {@link #completeAsync}，把"确保本地就绪"放到专用线程。</li>
 * </ol>
 *
 * <p>口径与 {@link com.oilquiz.app.ai.model.LocalModelLoadPolicy} 一致，但更精确一层：
 * 点按钮是<b>显式需求</b>，不能像"主动预加载"那样一刀切拒绝。</p>
 * <ul>
 *   <li>有激活的在线模型 → 先走在线，<b>不为跑功能去加载本地权重</b>；</li>
 *   <li>在线失败 → 只有"本地权重已在内存"才回落用它；不在内存就直接返回
 *       {@link #AI_UNAVAILABLE}（宁可报错，也不在一次点击里偷偷吃 1GB）；</li>
 *   <li>没有激活的在线模型 → 回落到本地按需加载（离线路线的用户功能不能被这次改动弄坏）。</li>
 * </ul>
 *
 * <p>在线通路复用 {@link OnlineInferenceService#generateAsync}，与
 * {@code ImportLlmEngine.inferOnline()} 同一套 API，不另造请求层。</p>
 */
public final class FeatureTextCompleter {

    private static final String TAG = "FeatureTextCompleter";

    /**
     * 同步版的总等待上限：在线一次 + 本地初始化（模型加载可能十几到几十秒）+ 本地推理。
     * 只给 {@link #complete} 这种本来就阻塞的调用方用；Activity 一律走 {@link #completeAsync}。
     */
    private static final long SYNC_WAIT_TIMEOUT_SEC = 180L;

    /**
     * "确保本地模型就绪"的专用线程。单线程即可：同一时刻只会有一个模型初始化在跑
     * （{@code AIService.modelInitSerialExecutor} 本身就是串行的），这里只是把等待挪出主线程。
     */
    private static final ExecutorService LOCAL_INIT_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FeatureLocalModelInit");
                t.setDaemon(true);
                return t;
            });

    /**
     * 出词失败时调用方统一回给用户的字符串。
     *
     * <p>{@code "Error:"} 前缀是被依赖的契约——{@code ToolResultInterpreter}、
     * {@code ImportLlmEngine}、{@code QuestionRepairEngine}，以及这四个 Activity 自己
     * 都按 {@code startsWith("Error:")} 判失败，改前缀会让它们把失败当成功。</p>
     */
    public static final String AI_UNAVAILABLE = "Error: AI 推理不可用（在线模型失败或本地模型未加载）";

    private final Context appContext;
    private final AIService aiService;

    public FeatureTextCompleter(Context context) {
        this.appContext = context.getApplicationContext();
        this.aiService = AIService.getInstance(context);
    }

    /**
     * 纯判定：这一次能不能用本地权重（真去加载）。
     *
     * <p>在线模型激活且本地还没加载时返回 false —— 这正是"用在线却为一个小功能把 GGUF 拉进内存"
     * 的场景。本地已在内存则返回 true（钱已经花了，别再浪费一次推理）。</p>
     */
    public static boolean mayUseLocalWeights(boolean hasActiveOnlineModel, boolean localWeightsInMemory) {
        return !hasActiveOnlineModel || localWeightsInMemory;
    }

    /**
     * 出词（异步版，给 Activity 用）：成功 future 里是文本；不可用时 future 里是
     * {@link #AI_UNAVAILABLE}（保留 {@code "Error:"} 前缀，四个 Activity 原本就按前缀弹 Toast）。
     *
     * <p>为什么必须有异步版：{@link AIService#initializeSafe()} 在<b>主线程</b>上是
     * {@code task.get(30, TimeUnit.SECONDS)}（AIService:627-643），四个功能的 Activity
     * 又在按钮点击里直接调它——加载 1GB 权重远超 5 秒，是教科书级 ANR 发生器。
     * 这里把"确保本地就绪"放到专用线程，UI 只挂回调。</p>
     */
    public CompletableFuture<String> completeAsync(String prompt, int maxTokens) {
        OnlineModelManager.OnlineModelConfig cfg = activeOnlineConfig();
        if (cfg == null) {
            // 没有激活的在线模型：用户显式点了这个功能，按需加载本地属于被请求的行为
            return localRoute(prompt, maxTokens, true);
        }
        return OnlineInferenceService.getInstance(appContext)
                .generateAsync(prompt, cfg, null, maxTokens)
                .handle((out, err) -> {
                    if (err == null && out != null && !out.trim().isEmpty()) {
                        return CompletableFuture.completedFuture(out);
                    }
                    Log.w(TAG, "在线推理未完成(" + cfg.modelName + ")："
                            + (err == null ? "空响应" : err.getMessage()));
                    // 在线失败：只有本地权重已在内存才回落，绝不为了兜底在后台偷偷加载 1GB
                    boolean mayLocal = mayUseLocalWeights(true, aiService.isInitialized());
                    return localRoute(prompt, maxTokens, mayLocal);
                })
                .thenCompose(f -> f);
    }

    /** 本地路线：必要时先在专用线程把模型加载好，再出词。{@code mayLoadLocal=false} 时直接判不可用。 */
    private CompletableFuture<String> localRoute(String prompt, int maxTokens, boolean mayLoadLocal) {
        if (!aiService.isInitialized() && !mayLoadLocal) {
            return CompletableFuture.completedFuture(AI_UNAVAILABLE);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!aiService.isInitialized() && !aiService.initializeSafe()) {
                Log.e(TAG, "本地模型初始化失败");
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }, LOCAL_INIT_EXECUTOR).thenCompose(ok -> Boolean.TRUE.equals(ok)
                ? aiService.generateAsync(prompt, maxTokens)
                : CompletableFuture.completedFuture(AI_UNAVAILABLE));
    }

    /**
     * 出词：成功返回文本，失败返回 {@code null}（调用方负责给出自己惯用的 {@code "Error: ..."}）。
     *
     * <p>路线与 {@link #completeAsync} 完全一致（就是它的一次性等待版），避免两套判定各漂一半。</p>
     */
    public String complete(String prompt, int maxTokens) {
        try {
            String out = completeAsync(prompt, maxTokens).get(SYNC_WAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
            return out == null || out.trim().isEmpty() || AI_UNAVAILABLE.equals(out) ? null : out;
        } catch (Throwable t) {
            Log.w(TAG, "等待出词失败: " + t.getMessage());
            return null;
        }
    }

    private OnlineModelManager.OnlineModelConfig activeOnlineConfig() {
        try {
            return OnlineModelManager.getInstance(appContext).getActiveModel();
        } catch (Throwable t) {
            Log.w(TAG, "读取在线模型配置失败: " + t.getMessage());
            return null;
        }
    }
}
