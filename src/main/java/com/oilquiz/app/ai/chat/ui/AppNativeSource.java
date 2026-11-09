package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

import com.oilquiz.app.ai.engine.contract.GenSignal;
import com.oilquiz.app.ai.engine.contract.GenSignalSource;

/**
 * 状态条数据源实现：**只把引擎无关契约转交给渲染层**。
 *
 * <p><b>契约重构（2026-10-07）</b>：本类原先逐个方法判断"是不是 NPU"再分别取数，
 * 共 6 个方法各写一遍 —— 引擎判断散落在 UI 适配层，每加一个引擎都要改这里；
 * 且返回值是字符串化 JSON，字段名靠约定，曾因此静默降级、长期显示「⏳ 预处理」。</p>
 *
 * <p>现在分派集中在 {@link GenSignalSource}（2026-11-09 后只剩本地 llama.cpp 一路），
 * 本类退化为纯转发，不再认识任何引擎。</p>
 */
public class AppNativeSource implements GenerationStatusBar.NativeSource {

    /**
     * 在线模型判定：由页面注入。
     *
     * <p>2026-11-04 修：这里原先 {@code return false}（注释写"由页面按路由决定"，但页面没接线）。
     * 后果是在线对话时状态条仍按"本地引擎"取数：每个 token + 每 800ms 各打一次
     * {@code nativeGetKvCacheStats}，而在线模式下 llama 上下文根本没建，
     * 于是真机日志每秒刷 ~10 条 {@code helper context not initialized}。
     */
    private final java.util.function.BooleanSupplier onlineModel;

    public AppNativeSource(Context context) {
        this(context, null);
    }

    public AppNativeSource(Context context, java.util.function.BooleanSupplier onlineModel) {
        // context 目前无需持有（数据源已完全走契约层）；保留参数以兼容既有调用点
        this.onlineModel = onlineModel;
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

    /** 是否在线模型：页面注入的路由判定；未注入时按本地处理 */
    @Override
    public boolean isUsingOnlineModel() {
        if (onlineModel == null) return false;
        try {
            return onlineModel.getAsBoolean();
        } catch (Throwable t) {
            // 路由查询失败按本地处理：宁可多刷新一次状态条，也不要因为异常把状态条钉死
            return false;
        }
    }
}
