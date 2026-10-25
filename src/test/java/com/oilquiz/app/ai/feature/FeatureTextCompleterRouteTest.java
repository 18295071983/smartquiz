package com.oilquiz.app.ai.feature;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 功能入口（题目讲解/翻译/生成/学习助手）出词路线判定单测（2026-10-25）。
 *
 * <p>钉住这次收紧的核心取向：在线模型激活时，点这些小功能<b>不许</b>再把本地 GGUF 权重
 * 拉进内存（真机实测那近 1GB native 就是这么被占的）；但本地已经在内存时不许浪费，
 * 直接用；没有在线模型时保持原有的"按需加载本地"。</p>
 */
public class FeatureTextCompleterRouteTest {

    @Test
    public void onlineActiveWithoutLocalWeightsMustNotLoadLocal() {
        assertFalse("在线激活 + 本地未加载 → 不为跑功能加载 1GB 权重",
                FeatureTextCompleter.mayUseLocalWeights(true, false));
    }

    @Test
    public void onlineActiveWithLocalWeightsInMemoryReusesThem() {
        assertTrue("钱已经花了就别浪费：在线失败时可直接回落用已在内存的本地模型",
                FeatureTextCompleter.mayUseLocalWeights(true, true));
    }

    @Test
    public void withoutOnlineLocalOnDemandLoadStillAllowed() {
        assertTrue("没有激活在线模型时，用户显式点功能仍可按需加载本地（离线路线不能被弄坏）",
                FeatureTextCompleter.mayUseLocalWeights(false, false));
        assertTrue(FeatureTextCompleter.mayUseLocalWeights(false, true));
    }

    /** 失败字符串的 "Error:" 前缀是被依赖的契约（ToolResultInterpreter 等按前缀判失败）。 */
    @Test
    public void unavailableMessageKeepsErrorPrefixContract() {
        assertTrue(FeatureTextCompleter.AI_UNAVAILABLE.startsWith("Error:"));
    }
}
