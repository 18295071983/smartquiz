package com.oilquiz.app.ai.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.model.ApiModel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 配置表「模型级能力声明位」套到 {@link ApiModel} 上的行为（{@link ModelListFetcher#applyPresetMeta}）。
 *
 * <p><b>为什么需要这个承载位</b>：2026-10-27 逐家核查过，除 DeepSeek（文档明说）和 Anthropic
 * （另一套形状）外，各家 {@code GET /models} 都不返回思考档位 / 输出上限 / 输入模态 —— 实测小米、
 * 智谱返回的每条目只有 69~77 字节，装不下这些数组。而读取侧（档位选择器、{@code reasoning_effort}
 * 注入、max_tokens 上限、多模态判定）一律以 {@code cachedModelsJson} 为唯一载体，
 * 于是"接口不声明"= 界面永远空白。合并配置表预置模型是唯一能把人工声明写进这份缓存的入口，
 * 此前它硬写 {@code contextLength = 0} 且完全不读能力声明。</p>
 *
 * <p>核心约束：宁缺毋错 —— 表里没写的绝不能编出来；写了非法值的按未声明处理而不是毁掉整次合并。</p>
 */
public class PresetModelMetaApplyTest {

    private static ApiModel preset(String id) {
        ApiModel m = new ApiModel(id);
        m.contextLength = 0;
        return m;
    }

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void fullDeclarationIsCopiedVerbatim() {
        ApiModel m = preset("glm-4.5-air");
        ModelListFetcher.applyPresetMeta(m, obj("{\"name\":\"glm-4.5-air\","
                + "\"contextLength\":131072,\"maxOutputTokens\":16384,"
                + "\"thinkingEffortLevels\":[\"high\",\"max\"],\"thinkingEffortDefault\":\"high\","
                + "\"inputModalities\":[\"text\",\"image\"]}"));

        assertEquals(131072, m.contextLength);
        // 人工声明不等于"接口检测到的真实值"：可信度标记必须保持 false，
        // 否则启动期 refreshAllContextWindows() 会把它当 API 实测值长期保留
        assertFalse("表声明不能冒充 fromApi", m.contextLengthFromApi);
        assertEquals(16384, m.maxOutputTokens);
        assertEquals(2, m.thinkingEffortLevels.size());
        assertTrue(m.hasThinkingEffortLevels());
        assertEquals("high", m.thinkingEffortDefault);
        assertTrue(m.supportsImageInput());
    }

    @Test
    public void missingDeclarationStaysUnDeclared() {
        // 纯字符串形态（meta == null）时不能凭空造出档位/上限/模态
        ApiModel m = preset("some-unknown-model");
        ModelListFetcher.applyPresetMeta(m, null);

        assertEquals(0, m.maxOutputTokens);
        assertFalse("未声明即无档位", m.hasThinkingEffortLevels());
        assertFalse("未声明即不支持图片", m.supportsImageInput());
        // 但上下文不再是 0：与 ApiModel.fromOpenAI 同一个数据驱动口径补推断值，
        // 否则列表显示 "Context: 0"，用户以为是坏数据
        assertTrue("预置条目不该再出现 Context: 0", m.contextLength > 0);
    }

    @Test
    public void invalidValuesAreIgnoredNotFatal() {
        ApiModel m = preset("glm-4.6");
        // 负数上限、非数值的上下文、档位数组里混空白与对象 —— 都不该抛出，也不该写入假值
        ModelListFetcher.applyPresetMeta(m, obj("{\"name\":\"glm-4.6\","
                + "\"contextLength\":\"8k\",\"maxOutputTokens\":-1,"
                + "\"thinkingEffortLevels\":[\"\",\"  \",{\"a\":1}],\"inputModalities\":[]}"));

        assertEquals(0, m.maxOutputTokens);
        assertFalse("空白/非字符串档位被过滤干净", m.hasThinkingEffortLevels());
        assertFalse(m.supportsImageInput());
        assertTrue("非法上下文回落为推断值而不是 0", m.contextLength > 0);
    }

    @Test
    public void partialDeclarationDoesNotBlockTheRest() {
        // 只声明档位：上限保持未声明，上下文走推断，档位仍要套上
        ApiModel m = preset("mimo-v2-pro");
        ModelListFetcher.applyPresetMeta(m, obj("{\"name\":\"mimo-v2-pro\","
                + "\"thinkingEffortLevels\":[\"low\",\"medium\",\"high\"]}"));

        assertEquals(3, m.thinkingEffortLevels.size());
        assertEquals(0, m.maxOutputTokens);
        assertTrue(m.contextLength > 0);
    }

    @Test
    public void roundTripsIntoTheCacheShapeReadersExpect() {
        // 表声明最终要经由 modelToCacheJson 落进 cachedModelsJson，被四个读取方按同名键取用；
        // 这条测试钉住"写进缓存的键名"与"表里声明的键名"之间不能错位
        ApiModel m = preset("deepseek-chat");
        JsonObject meta = obj("{\"name\":\"deepseek-chat\",\"contextLength\":65536,"
                + "\"maxOutputTokens\":8192,\"thinkingEffortLevels\":[\"low\",\"high\"],"
                + "\"thinkingEffortDefault\":\"low\",\"inputModalities\":[\"text\"]}");
        ModelListFetcher.applyPresetMeta(m, meta);

        org.json.JSONObject cached = ModelListFetcher.modelToCacheJson(m);
        ApiModel back = ModelListFetcher.modelFromCacheJson(cached);

        assertEquals(65536, back.contextLength);
        assertEquals(8192, back.maxOutputTokens);
        assertEquals(m.thinkingEffortLevels, back.thinkingEffortLevels);
        assertEquals("low", back.thinkingEffortDefault);
        assertEquals(m.inputModalities, back.inputModalities);
    }
}
