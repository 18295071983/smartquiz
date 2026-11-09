package com.oilquiz.app.ai.engine.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link GenSignal} 契约与 {@link GenSignalTextMapper} 的行为测试。
 *
 * <p>这些断言锁定的正是**历史上出过故障的语义边界**：
 * <ul>
 *   <li>"进度不可用"必须与"进度为 0"区分（否则 UI 显示假的 0%，或静默回退到别的引擎的文案）；</li>
 *   <li>PREPROCESS 文案必须用**引擎提供的标签**，不能硬编码 llama.cpp 的「预处理」；</li>
 *   <li>THINKING 阶段不产出文本（由实时事件驱动，避免轮询覆盖导致闪烁）；</li>
 *   <li>速度 <=0 必须归一为 UNKNOWN，不能当成 0 t/s 显示。</li>
 * </ul>
 *
 * <p>纯 JVM 测试，无需 Android/Robolectric：这正是把映射逻辑移出 View 的目的。</p>
 */
public class GenSignalContractTest {

    /** 测试替身：把资源 id 映射成可读文本，便于断言 */
    private static final GenSignalTextMapper.Strings STRINGS = resId -> {
        if (resId == GenSignalTextMapper.Res.DECODE_SPEED) return "SPEED:%.1f";
        if (resId == GenSignalTextMapper.Res.GENERATING) return "GENERATING";
        if (resId == GenSignalTextMapper.Res.PREPROCESS) return "PREPROCESS";
        if (resId == GenSignalTextMapper.Res.PROGRESS_SPEED) return "PS:%d:%.1f";
        if (resId == GenSignalTextMapper.Res.PROGRESS) return "P:%d";
        return "R" + resId;
    };

    // ==================== 契约语义 ====================

    @Test
    public void 空闲信号不活跃() {
        GenSignal s = GenSignal.idle("test");
        assertFalse(s.isActive());
        assertFalse(s.hasProgress());
        assertFalse(s.hasTokens());
        assertNull("空闲不应产出状态文本", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 进度不可用与进度为零必须可区分() {
        // 进度 0（真实存在）→ hasProgress 为 true
        GenSignal zero = GenSignal.builder("t")
                .phase(GenSignal.Phase.PREPROCESS).running(true)
                .progressOrUnknown(0).build();
        assertTrue("进度 0 是有效值", zero.hasProgress());
        assertEquals(0f, zero.progressPercent, 0.001f);

        // 进度不可用 → hasProgress 为 false
        GenSignal unknown = GenSignal.builder("t")
                .phase(GenSignal.Phase.PREPROCESS).running(true)
                .progressOrUnknown(-1).build();
        assertFalse("负进度表示不可用", unknown.hasProgress());
        assertEquals(GenSignal.UNKNOWN, unknown.progressPercent, 0.001f);
    }

    @Test
    public void 越界进度归一为不可用() {
        GenSignal over = GenSignal.builder("t").progressOrUnknown(150).build();
        assertFalse(">100 视为不可用", over.hasProgress());
        GenSignal neg = GenSignal.builder("t").progressOrUnknown(-5).build();
        assertFalse("负数视为不可用", neg.hasProgress());
    }

    @Test
    public void 速度为零或负归一为不可用() {
        GenSignal s = GenSignal.builder("t")
                .decodeSpeedOrUnknown(0f)
                .phaseSpeedOrUnknown(-1f)
                .build();
        assertEquals(GenSignal.UNKNOWN, s.decodeSpeed, 0.001f);
        assertEquals(GenSignal.UNKNOWN, s.phaseSpeed, 0.001f);
    }

    @Test
    public void 负token数归一为不可用() {
        assertEquals(GenSignal.UNKNOWN,
                GenSignal.builder("t").tokensOrUnknown(-3).build().tokens, 0.001f);
        assertTrue(GenSignal.builder("t").tokensOrUnknown(0).build().hasTokens());
    }

    // ==================== 文案映射 ====================

    @Test
    public void 生成中优先显示解码速度() {
        GenSignal s = GenSignal.builder("t")
                .phase(GenSignal.Phase.GENERATING).running(true)
                .decodeSpeedOrUnknown(22.6f).build();
        assertEquals("SPEED:22.6", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 生成中无速度时显示生成中() {
        GenSignal s = GenSignal.builder("t")
                .phase(GenSignal.Phase.GENERATING).running(true)
                .decodeSpeedOrUnknown(0f).build();
        assertEquals("GENERATING", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 预处理无进度时用引擎提供的标签而不是硬编码术语() {
        // 这正是 NPU 的实际情况：阶段量进度（0/100）不可上报，但有中文标签「处理提示」
        GenSignal s = GenSignal.builder("geniex-npu")
                .phase(GenSignal.Phase.PREPROCESS).running(true)
                .progressOrUnknown(-1)
                .phaseLabel("处理提示")
                .build();
        assertEquals("⏳ 处理提示", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 预处理无标签才回退到资源文案() {
        GenSignal s = GenSignal.builder("t")
                .phase(GenSignal.Phase.PREPROCESS).running(true)
                .progressOrUnknown(-1)
                .phaseLabel("")
                .build();
        assertEquals("⏳ PREPROCESS", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 预处理有进度时显示百分比() {
        GenSignal s = GenSignal.builder("t")
                .phase(GenSignal.Phase.PREPROCESS).running(true)
                .progressOrUnknown(42)
                .phaseLabel("处理提示")
                .build();
        assertEquals("P:42", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 预处理有进度与速度时两者都显示() {
        GenSignal s = GenSignal.builder("t")
                .phase(GenSignal.Phase.PREPROCESS).running(true)
                .progressOrUnknown(42)
                .phaseSpeedOrUnknown(12.5f)
                .build();
        assertEquals("PS:42:12.5", GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    @Test
    public void 思考阶段不产出轮询文本() {
        // 思考段由流式事件驱动；若这里也产出文本，会与实时事件互相覆盖 → 闪烁
        GenSignal s = GenSignal.builder("t")
                .phase(GenSignal.Phase.THINKING).running(true).build();
        assertNull(GenSignalTextMapper.toStatusText(s, STRINGS));
    }

    // ==================== KV 统计（可选能力） ====================

    @Test
    public void KV统计需同时有命中率与计划数才算可显示() {
        assertTrue(new GenSignal.KvStats(50.0, 30.0, 3).isDisplayable());
        assertFalse("无计划数不可显示", new GenSignal.KvStats(50.0, 30.0, 0).isDisplayable());
        assertFalse("负命中率不可显示", new GenSignal.KvStats(-1, 30.0, 3).isDisplayable());
    }

    // ==================== 上下文占用契约 ====================

    @Test
    public void 上下文占用窗口缺失时应报告不可用而不是零() {
        ContextUsage u = new ContextUsage(ContextUsage.UNKNOWN, ContextUsage.UNKNOWN, "t");
        assertFalse(u.hasWindow());
        assertEquals(ContextUsage.UNKNOWN, u.percent());
    }

    @Test
    public void 上下文占用百分比计算与上限() {
        assertEquals(50, new ContextUsage(1000, 500, "t").percent());
        assertEquals("不得超过 100", 100, new ContextUsage(1000, 5000, "t").percent());
        assertEquals("已用未知按 0 计", 0, new ContextUsage(1000, ContextUsage.UNKNOWN, "t").percent());
    }

    // ==================== 引擎分派（不依赖 native） ====================

    @Test
    public void 分派入口永不返回null() {
        assertNotNull(GenSignalSource.current());
        // 上下文占用契约同样保证非 null（拿不到时用 UNKNOWN 表达）
        assertNotNull(GenSignalSource.contextUsage());
    }
}
