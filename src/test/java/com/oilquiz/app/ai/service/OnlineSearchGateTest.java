package com.oilquiz.app.ai.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 在线预搜索的闸门（2026-11-02）。
 *
 * <p>现状问题：勾"联网搜索"后，`applySearchToPrompt` 对**每一条**消息先打一次秘塔搜索
 * （按次扣积分），而且成功后还抑制官方联网注入 —— 等于每家服务商都先烧我们一次钱。
 * 参考 DeepSeek 的做法：它 Chat Completions 根本没有内置联网参数，只有 Responses 端的
 * `web_search` 工具（且仅部分模型），所以"官方能不能搜"必须按**端点**判断；
 * 能搜就交给服务端执行（不花我们额度），不能搜才轮到自己，且只在问题确实要实时信息时才预搜索。
 */
public class OnlineSearchGateTest {

    /** 意图闸：这几类问题才值得先烧一次额度。 */
    @Test
    public void liveInfoIntentsAreRecognized() {
        String[] yes = {
                "今天北京天气怎么样",
                "现在几点",
                "最新油价是多少",
                "人民币兑美元汇率",
                "最近有什么新闻",
                "2026年诺贝尔奖公布了吗",
                "我们厂机泵的实时运行数据怎么看",
                "最新的 Python 版本号是多少",
        };
        for (String q : yes) {
            assertTrue("应判为需要实时信息: " + q, OnlineInferenceService.needsLiveInfo(q));
        }
    }

    /** 普通知识/代码/翻译类问题不该触发预搜索（模型自己会用知识答）。 */
    @Test
    public void everydayQuestionsDoNotTriggerPresearch() {
        String[] no = {
                "解释一下牛顿第二定律",
                "帮我写一段 python 读取 excel 的代码",
                "什么是光合作用",
                "把这段话翻译成英文：今天天气不错",
                "总结一下这篇论文的主要贡献",   // 正文已在上下文里，不需要外部搜索
        };
        for (String q : no) {
            assertFalse("不该触发预搜索: " + q, OnlineInferenceService.needsLiveInfo(q));
        }
    }

    /** 闸门接线：官方优先 → 意图闸 → 才轮到自烧额度；三条调用点都要带 apiUrl（按端点判断）。 */
    @Test
    public void gateIsWiredWithProviderEndpointAwareness() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/oilquiz/app/ai/service/OnlineInferenceService.java")),
                StandardCharsets.UTF_8);

        assertTrue("方法签名要带 apiUrl（官方联网按端点判断）",
                src.contains("applySearchToPrompt(String prompt, boolean webSearch, String apiUrl)"));
        assertTrue("要先判官方能力", src.contains("GATE-1"));
        assertTrue("要按端点判断是否真能用官方联网", src.contains("getWebSearchEndpoint(apiUrl)"));
        assertTrue("要有意图闸", src.contains("GATE-2") && src.contains("needsLiveInfo(prompt)"));
        assertTrue("官方能搜时不许再打秘塔", src.contains("跳过自有预搜索"));

        int calls = 0, idx = 0;
        while ((idx = src.indexOf("applySearchToPrompt(prompt, webSearch", idx)) >= 0) {
            calls++;
            idx += 10;
        }
        assertTrue("预搜索入口不止一处，必须都改成三参（含 apiUrl），实际 " + calls, calls >= 3);
        assertFalse("不许留下两参调用（会默认走不到端点判断）",
                src.contains("applySearchToPrompt(prompt, webSearch)"));
    }
}
