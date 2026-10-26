package com.oilquiz.app.ai.model;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * 端点解析地基：优先级与"端点形态 → 协议"的推导。
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
        // 表里 services.*.endpoint 存的是协议标识 "responses"（不带斜杠），
        // 拼地址必须用 RESPONSES_PATH；两者一旦不同步，探测与放量判断就会各说各话
        assertEquals("/responses", ProviderConfigManager.RESPONSES_PATH);
        assertEquals(ProviderConfigManager.PROTOCOL_RESPONSES,
                ProviderConfigManager.protocolForEndpoint(ProviderConfigManager.RESPONSES_PATH));
    }
}
