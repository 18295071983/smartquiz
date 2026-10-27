package com.oilquiz.app.ai.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 流式瞬时错误重试口径（与 OpenAI/Anthropic SDK 对齐）：
 * 连接错误/超时/408/429/5xx 可重试；401/403/404/参数错误重试无意义，必须原样报错。
 * 另钉住"吐过字就不能重发"这条由调用方判断（本类不掺和），这里只测分类与退避。
 */
public class StreamRetryPolicyTest {

    @Test
    public void connectionAndServerJitterAreTransient() {
        assertTrue(StreamRetryPolicy.isTransient("Software caused connection abort"));
        assertTrue(StreamRetryPolicy.isTransient("Connection reset by peer"));
        assertTrue(StreamRetryPolicy.isTransient("broken pipe"));
        assertTrue(StreamRetryPolicy.isTransient("unexpected end of stream"));
        assertTrue(StreamRetryPolicy.isTransient("connect timed out"));
        assertTrue(StreamRetryPolicy.isTransient("HTTP 429 Too Many Requests"));
        assertTrue(StreamRetryPolicy.isTransient("HTTP 503 Service Unavailable"));
        assertTrue(StreamRetryPolicy.isTransient("overloaded_error"));
        assertTrue(StreamRetryPolicy.isTransient("推理超时（120秒无响应）"));
    }

    @Test
    public void authAndBadRequestAreNotTransient() {
        assertFalse(StreamRetryPolicy.isTransient("HTTP 401 Unauthorized"));
        assertFalse(StreamRetryPolicy.isTransient("HTTP 403 Forbidden"));
        assertFalse(StreamRetryPolicy.isTransient("HTTP 404 Not Found"));
        assertFalse(StreamRetryPolicy.isTransient("HTTP 400 invalid parameter"));
        assertFalse(StreamRetryPolicy.isTransient("Invalid API key provided"));
        assertFalse(StreamRetryPolicy.isTransient(null));
        assertFalse(StreamRetryPolicy.isTransient("   "));
    }

    @Test
    public void backoffGrowsAndIsCapped() {
        long a = StreamRetryPolicy.backoffMs(1);
        long b = StreamRetryPolicy.backoffMs(2);
        assertTrue(a >= 500 && a <= 750);
        assertTrue(b >= 1000 && b <= 1250);
        // 大序号封顶 4s
        assertTrue(StreamRetryPolicy.backoffMs(10) <= 4250);
        assertTrue(StreamRetryPolicy.MAX_RETRIES == 2);
    }
}
