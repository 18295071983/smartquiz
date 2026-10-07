package com.oilquiz.app.ai.engine.contract;

/**
 * 把 {@link GenSignal} 映射为状态条显示文本 —— **纯函数，无 Android/引擎依赖，可单测**。
 *
 * <p>这是契约重构带来的直接收益：原先这段映射逻辑内联在
 * {@code GenerationStatusBar.refresh()} 里，混合了"读 native JSON + 解析字段 + 判断引擎 +
 * 选字符串资源"，既无法单测，也让字段名不一致的故障只能靠真机肉眼发现
 * （实测：NPU 下长期显示 llama.cpp 术语「⏳ 预处理」）。</p>
 *
 * <p>本类只依赖 {@link GenSignal} 与一个"取字符串"的函数，因此可以对每个阶段、
 * 每种"进度可用/不可用"组合写断言。</p>
 */
public final class GenSignalTextMapper {

    /** 字符串资源取用（由调用方注入 {@code context::getString}，便于测试替身） */
    public interface Strings {
        String get(int resId);
    }

    /** 文案模板资源 id（与 strings_migrated.xml 对应） */
    public static final class Res {
        /** 解码速度：如 "⚡ %.1f t/s" */
        public static final int DECODE_SPEED = com.oilquiz.app.R.string.h_743faf7e;
        /** 生成中（无速度） */
        public static final int GENERATING = com.oilquiz.app.R.string.h_ad0acdc5;
        /** 预处理（无进度） */
        public static final int PREPROCESS = com.oilquiz.app.R.string.h_803f889b;
        /** 进度 + 速度：%.1f%% · %.1f t/s 之类 */
        public static final int PROGRESS_SPEED = com.oilquiz.app.R.string.h_f8a477f6;
        /** 仅进度 */
        public static final int PROGRESS = com.oilquiz.app.R.string.h_2dcef5d6;

        private Res() {
        }
    }

    private GenSignalTextMapper() {
    }

    /**
     * 返回该信号应显示的单行文本；返回 {@code null} 表示**由实时事件驱动、本映射不产出文本**
     * （THINKING 阶段由流式思考回调负责，避免 800ms 轮询与实时事件互相覆盖造成闪烁）。
     */
    public static String toStatusText(GenSignal signal, Strings strings) {
        if (signal == null || !signal.isActive()) {
            return null;
        }
        switch (signal.phase) {
            case GENERATING:
                if (signal.decodeSpeed > 0) {
                    return String.format(strings.get(Res.DECODE_SPEED), signal.decodeSpeed);
                }
                return strings.get(Res.GENERATING);

            case PREPROCESS:
                if (signal.hasProgress()) {
                    int pct = (int) signal.progressPercent;
                    if (signal.phaseSpeed > 0) {
                        return String.format(strings.get(Res.PROGRESS_SPEED), pct, signal.phaseSpeed);
                    }
                    return String.format(strings.get(Res.PROGRESS), pct);
                }
                // 进度不可用：显示**引擎提供的阶段标签**（如 NPU 的「处理提示」），
                // 而不是回退到 llama.cpp 的固定术语「预处理」。
                String label = signal.phaseLabel;
                if (label == null || label.isEmpty()) {
                    label = strings.get(Res.PREPROCESS);
                }
                return "⏳ " + label;

            case THINKING:
                // 由 thinking 事件驱动（见 GenerationStatusBar.onThinkingSegment）
                return null;

            default:
                return null;
        }
    }
}
