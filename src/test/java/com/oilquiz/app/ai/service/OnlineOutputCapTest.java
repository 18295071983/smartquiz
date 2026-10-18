package com.oilquiz.app.ai.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link OnlineInferenceService#computeOutputCap} 的输出长度口径。
 *
 * <p>背景：在线 Agent 原先由引擎硬编码两道闸门（最终答案轮 16384、工具轮再降到 8192），
 * 与模型真实能力无关，长回答和大工具参数会被 {@code finish_reason=length} 斩断。
 * 现在引擎只传 {@link OnlineInferenceService#NO_OUTPUT_CAP}，额度由这里决定。
 * 这些用例钉住的是"去掉截断后不能反过来又添一道新上限"这条线。</p>
 */
public class OnlineOutputCapTest {

    private static final int DEFAULT_CAP = 16384;
    private static final int THINKING_HIGH = 65536;
    private static final int THINKING_MAX = 131072;

    /** 不限输出 + 服务商声明了容量：按声明容量下发，不再用 app 的 16K 卡住 */
    @Test
    public void uncappedUsesDeclaredModelCapacity() {
        assertEquals(393216, OnlineInferenceService.computeOutputCap(
                OnlineInferenceService.NO_OUTPUT_CAP, 393216, DEFAULT_CAP, 0));
    }

    /** 不限输出 + 服务商没声明：退默认档，不猜"省略即无限"（多家默认值比 16K 还小） */
    @Test
    public void uncappedWithoutDeclarationFallsBackToDefault() {
        assertEquals(DEFAULT_CAP, OnlineInferenceService.computeOutputCap(
                OnlineInferenceService.NO_OUTPUT_CAP, 0, DEFAULT_CAP, 0));
    }

    /** 调用方显式给的值必须原样保留，不能被声明容量压低 */
    @Test
    public void callerSpecifiedValueIsNeverLowered() {
        assertEquals(24576, OnlineInferenceService.computeOutputCap(
                24576, 8192, DEFAULT_CAP, 0));
    }

    /** <=0（未指定，非哨兵）：用默认档，但不越过声明容量（原防御意图，此前因端点没传对而失效） */
    @Test
    public void unspecifiedUsesDefaultWithinDeclaredCapacity() {
        assertEquals(8192, OnlineInferenceService.computeOutputCap(0, 8192, DEFAULT_CAP, 0));
        assertEquals(DEFAULT_CAP, OnlineInferenceService.computeOutputCap(0, 0, DEFAULT_CAP, 0));
    }

    /** 思考模式把额度抬到档位值（DeepSeek 官方：默认 64K、max 档 128K） */
    @Test
    public void thinkingEffortLiftsCeiling() {
        assertEquals(THINKING_HIGH, OnlineInferenceService.computeOutputCap(
                OnlineInferenceService.NO_OUTPUT_CAP, 0, DEFAULT_CAP, THINKING_HIGH));
        assertEquals(THINKING_MAX, OnlineInferenceService.computeOutputCap(
                OnlineInferenceService.NO_OUTPUT_CAP, 0, DEFAULT_CAP, THINKING_MAX));
    }

    /** 思考放大不超过声明容量，且只在"我们自己抬"的那段生效（不压低调用方显式值以下） */
    @Test
    public void thinkingLiftIsClampedToDeclaredCapacity() {
        assertEquals(32768, OnlineInferenceService.computeOutputCap(
                OnlineInferenceService.NO_OUTPUT_CAP, 32768, DEFAULT_CAP, THINKING_MAX));
        // 调用方要 24576、模型能吃 65536，思考档把额度抬到 65536（只升不降）
        assertEquals(65536, OnlineInferenceService.computeOutputCap(
                24576, 65536, DEFAULT_CAP, THINKING_HIGH));
    }
}
