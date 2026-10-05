package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

import com.oilquiz.app.ai.engine.NpuEngineState;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.jni.LlamaHelper;

/**
 * 真实的数据源实现（对应 demo 的 {@code FakeNativeSource}）：把聊天页顶部状态条需要的
 * native 数据**按当前引擎分派** —— NPU 引擎读状态机，否则读 llama.cpp。
 *
 * <p>目的：让 {@code AIChatActivity} 不再散落"引擎判断 + 各自取数"的三元表达式，
 * 与 demo 的 {@code ChatShellView.bindSources(nativeSource, statsSource)} 架构一致。
 *
 * <p>字段格式与 llama.cpp 侧完全对齐（phase/running/prefill/tokens/tps），因此状态条渲染逻辑无需改动。
 */
public class AppNativeSource implements GenerationStatusBar.NativeSource {

    private final Context context;

    public AppNativeSource(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    private boolean npu() {
        try {
            if (context == null) {
                return false;
            }
            AIService svc = AIService.getInstance(context);
            return svc != null && svc.isNpuEngineEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 推理阶段（NPU：状态机 JSON；llama.cpp：LlamaHelper） */
    @Override
    public String getGenPhase() {
        if (npu()) {
            return NpuEngineState.get().getInferenceJson();
        }
        return LlamaHelper.getGenPhase();
    }

    /** prefill 进度：NPU 用状态机的 prefill%/tokens 合成与 llama.cpp 同结构的 JSON */
    @Override
    public String getPrefillProgress() {
        if (npu()) {
            NpuEngineState st = NpuEngineState.get();
            return "{\"progress\":" + st.getPrefillPercent()
                    + ",\"tokens\":" + st.getGeneratedTokens()
                    + ",\"phase\":\"" + st.getInferencePhaseName() + "\"}";
        }
        return LlamaHelper.getPrefillProgress();
    }

    /** KV 缓存统计：NPU 侧 SDK 未暴露 → 返回 null（状态条自动隐藏该项，不显示假数据） */
    @Override
    public String getKvCacheStats() {
        if (npu()) {
            return null;
        }
        return LlamaHelper.getKvCacheStats();
    }

    /** 是否在线模型：NPU 属本地，返回 false */
    @Override
    public boolean isUsingOnlineModel() {
        return false;
    }

    /** 正文解码速度（t/s）：NPU 用状态机记录的上次速度 */
    @Override
    public float getDecodeSpeed() {
        if (npu()) {
            return NpuEngineState.get().getLastTps();
        }
        return LlamaHelper.getDecodeSpeed();
    }

    /** 阶段吞吐：NPU 未区分阶段，与解码速度同源 */
    @Override
    public float getPhaseSpeed() {
        if (npu()) {
            return NpuEngineState.get().getLastTps();
        }
        return LlamaHelper.getPhaseSpeed();
    }
}
