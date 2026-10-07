package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

import com.oilquiz.app.ai.engine.contract.GenSignal;
import com.oilquiz.app.ai.engine.contract.GenSignalSource;

/**
 * 状态条数据源实现：**只把引擎无关契约转交给渲染层**。
 *
 * <p><b>契约重构（2026-10-07）</b>：本类原先逐个方法判断"是不是 NPU"再分别取数
 * （{@code npu() ? NpuEngineState : LlamaHelper}），共 6 个方法各写一遍。这有两个问题：
 * <ul>
 *   <li>引擎判断散落在 UI 适配层，每加一个引擎都要改这里；</li>
 *   <li>返回值是字符串化 JSON，字段名靠约定 —— 实测 NPU 的 prefill 字段名与状态栏期望不一致，
 *       于是静默降级、长期显示 llama.cpp 术语「⏳ 预处理」。</li>
 * </ul>
 *
 * <p>现在引擎分派集中在 {@link GenSignalSource}，本类退化为纯转发，不再认识任何引擎。
 * 与 demo 的 {@code ChatShellView.bindSources(nativeSource, statsSource)} 架构保持一致。</p>
 */
public class AppNativeSource implements GenerationStatusBar.NativeSource {

    public AppNativeSource(Context context) {
        // context 目前无需持有（数据源已完全走契约层）；保留构造签名以兼容既有调用点
    }

    /** 生成状态：引擎分派交给契约层 */
    @Override
    public GenSignal getSignal() {
        return GenSignalSource.current();
    }

    /** KV 缓存统计：可选能力，引擎不具备时返回 null（UI 隐藏） */
    @Override
    public GenSignal.KvStats getKvStats() {
        return GenSignalSource.kvStats();
    }

    /** 是否在线模型：由页面按路由决定；此处默认本地（本数据源只服务本地引擎状态条） */
    @Override
    public boolean isUsingOnlineModel() {
        return false;
    }
}
