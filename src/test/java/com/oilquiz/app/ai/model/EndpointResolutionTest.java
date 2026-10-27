package com.oilquiz.app.ai.model;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 端点解析地基：优先级、"端点形态 → 协议"的推导，以及<b>写入前的端点覆盖形态校验</b>。
 *
 * <p><b>为什么专门测它</b>：这两件事决定每个请求实际打哪个地址。此前它们散落在八处主对话调用
 * 与两条 Responses 发起路径里，各自还带一条字面量兜底（{@code endpoint 为空就 "/responses"}、
 * {@code 不含 /api/ 就 "/api/chat"}），改一处漏七处，而"API 突然不能用"就是这类漂移的代价。
 * 收口成唯一出口后，优先级与推导规则必须有用例钉住。</p>
 *
 * <p>纯静态、不依赖 Context/配置表，所以能在本机真跑（{@code ProviderConfigManager.resolve}
 * 需要加载 assets 表，只能靠真机验，见类注释与交付说明）。</p>
 */
public class EndpointResolutionTest {

    @Test
    public void protocolIsDerivedFromEndpointShape() {
        assertEquals(ProviderConfigManager.PROTOCOL_RESPONSES,
                ProviderConfigManager.protocolForEndpoint("/responses"));
        assertEquals(ProviderConfigManager.PROTOCOL_ANTHROPIC,
                ProviderConfigManager.protocolForEndpoint("/v1/messages"));
        assertEquals(ProviderConfigManager.PROTOCOL_OLLAMA,
                ProviderConfigManager.protocolForEndpoint("/api/chat"));
        assertEquals(ProviderConfigManager.PROTOCOL_OPENAI,
                ProviderConfigManager.protocolForEndpoint("/chat/completions"));
        // 绝对地址同样按结尾判断（表里允许写完整 URL）
        assertEquals(ProviderConfigManager.PROTOCOL_RESPONSES,
                ProviderConfigManager.protocolForEndpoint("https://api.deepseek.com/responses"));
    }

    @Test
    public void unknownOrBlankEndpointFallsBackToOpenAiCompat() {
        // 认不出的形态必须落到最广兼容的那档，而不是抛异常或返回 null 让调用方判空
        assertEquals(ProviderConfigManager.PROTOCOL_OPENAI,
                ProviderConfigManager.protocolForEndpoint(null));
        assertEquals(ProviderConfigManager.PROTOCOL_OPENAI,
                ProviderConfigManager.protocolForEndpoint("   "));
        assertEquals(ProviderConfigManager.PROTOCOL_OPENAI,
                ProviderConfigManager.protocolForEndpoint("/custom/completions"));
    }

    @Test
    public void precedenceIsOverrideThenModelThenProviderThenStandard() {
        assertArrayEquals(new String[]{"/mine", ProviderConfigManager.SOURCE_CONFIG_OVERRIDE},
                ProviderConfigManager.pickEndpoint("/mine", "/model", "/provider", "/std"));
        assertArrayEquals(new String[]{"/model", ProviderConfigManager.SOURCE_MODEL_TABLE},
                ProviderConfigManager.pickEndpoint(null, "/model", "/provider", "/std"));
        assertArrayEquals(new String[]{"/provider", ProviderConfigManager.SOURCE_PROVIDER_TABLE},
                ProviderConfigManager.pickEndpoint(null, null, "/provider", "/std"));
        assertArrayEquals(new String[]{"/std", ProviderConfigManager.SOURCE_STANDARD},
                ProviderConfigManager.pickEndpoint(null, null, null, "/std"));
    }

    @Test
    public void blankOverrideMustNotMaskTheTable() {
        // 关键回归：界面上"清空端点框"存进来是 null，但历史数据/输入框可能留下 ""
        // —— "" 若被当成有效覆盖，就等于把服务商配置表的端点整个屏蔽掉（表现为"API 打不通"）
        assertArrayEquals(new String[]{"/provider", ProviderConfigManager.SOURCE_PROVIDER_TABLE},
                ProviderConfigManager.pickEndpoint("", "  ", "/provider", "/std"));
    }

    @Test
    public void overrideValueIsTrimmedBeforeUse() {
        assertArrayEquals(new String[]{"/responses", ProviderConfigManager.SOURCE_CONFIG_OVERRIDE},
                ProviderConfigManager.pickEndpoint("  /responses  ", null, null, null));
    }

    @Test
    public void responsesPathConstantMatchesTheProtocolShape() {
        // 拼地址必须用 RESPONSES_PATH：它一旦与 protocolForEndpoint 认的那档不同步，
        // 探测与放量判断就会各说各话。
        // 现状（按 providers.json 实测）：services.agent.endpoint 存的是真路径 "/responses"（4 家），
        // 另有 2 家把 services.webSearch.endpoint 写成裸标识 "responses" —— 后者当前没有任何消费者
        // 拿它拼地址（webSearch 只读 param/enabled），所以不产生错误 URL，但也正因此不能指望它。
        assertEquals("/responses", ProviderConfigManager.RESPONSES_PATH);
        assertEquals(ProviderConfigManager.PROTOCOL_RESPONSES,
                ProviderConfigManager.protocolForEndpoint(ProviderConfigManager.RESPONSES_PATH));
    }

    @Test
    public void endpointOverrideAcceptsPathOrFullUrlOnly() {
        // 合法：留空＝不覆盖（回落配置表）；/ 开头的路径；完整 http(s) 地址（大小写不敏感）
        assertTrue(ProviderConfigManager.isEndpointOverrideValid(null));
        assertTrue(ProviderConfigManager.isEndpointOverrideValid(""));
        assertTrue(ProviderConfigManager.isEndpointOverrideValid("   "));
        assertTrue(ProviderConfigManager.isEndpointOverrideValid("/responses"));
        assertTrue(ProviderConfigManager.isEndpointOverrideValid("  /chat/completions "));
        assertTrue(ProviderConfigManager.isEndpointOverrideValid("https://api.deepseek.com/v1/responses"));
        assertTrue(ProviderConfigManager.isEndpointOverrideValid("HTTP://192.168.1.9:11434/api/chat"));
        assertNull(ProviderConfigManager.endpointOverrideError("agent ", "/responses"));

        // 非法：不以 / 开头的相对路径会被 buildUrl 直接粘在 baseUrl 尾部（…/v1responses），
        // 且 protocolForEndpoint 还把它认成 openai —— 必须在写入前就拒掉
        assertFalse(ProviderConfigManager.isEndpointOverrideValid("responses"));
        assertFalse(ProviderConfigManager.isEndpointOverrideValid("  responses  "));
        assertFalse(ProviderConfigManager.isEndpointOverrideValid("v1/responses"));
        assertFalse(ProviderConfigManager.isEndpointOverrideValid("api.deepseek.com/v1/responses"));
        assertNotNull(ProviderConfigManager.endpointOverrideError("chat ", "responses"));
    }

    @Test
    public void endpointOverrideErrorNamesTheOffendingValue() {
        // 报错必须把用户实际写的那段值带回去：只说"格式不对"的话，人不知道自己哪个字符少了
        String err = ProviderConfigManager.endpointOverrideError("agent ", " responses ");
        assertTrue(err, err.contains("agent"));
        assertTrue(err, err.contains("responses"));
        assertTrue(err, err.contains("/responses"));
        assertNull(ProviderConfigManager.endpointOverrideError("agent ", null));
    }

    @Test
    public void versionedEndpointIsNotPrefixedTwice() {
        // Gemini 的 OpenAI 兼容层是 /v1beta/... ：版本号后面跟着字母。
        // 判据若只认 ^/v\d+/（原实现），它会被拼成 …/v1/v1beta/…，一个必然 404 的地址
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                ProviderConfigManager.buildUrlFor(
                        "https://generativelanguage.googleapis.com", "/v1beta/openai/chat/completions"));
        // /v2.1 同理（讯飞那类带点小版本的版本段）
        assertEquals("https://spark-api.cn-huabei-1.xf-yun.com/v2.1/tti",
                ProviderConfigManager.buildUrlFor(
                        "https://spark-api.cn-huabei-1.xf-yun.com", "/v2.1/tti"));
        // 传统 /v1/ 形态行为不变（Anthropic）
        assertEquals("https://api.anthropic.com/v1/messages",
                ProviderConfigManager.buildUrlFor("https://api.anthropic.com", "/v1/messages"));
    }

    @Test
    public void baseUrlEndingInAnyVersionSegmentIsNotRewritten() {
        // 用户从官方文档抄的地址常自带版本尾巴；这条也要认 /v1beta，否则又补一个 /v1
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/responses",
                ProviderConfigManager.buildUrlFor(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "/responses"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/responses",
                ProviderConfigManager.buildUrlFor(
                        "https://ark.cn-beijing.volces.com/api/v3", "/responses"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/x:generateContent",
                ProviderConfigManager.buildUrlFor(
                        "https://generativelanguage.googleapis.com/v1beta", "/models/x:generateContent"));
        // 根地址（没有版本尾巴）仍按 OpenAI 习惯补 /v1
        assertEquals("https://api.deepseek.com/v1/responses",
                ProviderConfigManager.buildUrlFor("https://api.deepseek.com", "/responses"));
        // 完整 URL 端点原样返回，不拼 baseUrl
        assertEquals("https://open.bigmodel.cn/api/v1/responses",
                ProviderConfigManager.buildUrlFor(
                        "https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/api/v1/responses"));
        // baseUrl 已经以该 endpoint 结尾时不重复拼
        assertEquals("https://api.openai.com/v1/chat/completions",
                ProviderConfigManager.buildUrlFor(
                        "https://api.openai.com/v1/chat/completions", "/chat/completions"));
    }
}
