package com.oilquiz.app.util;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 日志执行上下文前缀：只负责"短标识 + 可空"，拼接规则写死在这里，免得各处各写一套。
 */
public class LogContextTest {

    @After
    public void tearDown() {
        LogContext.clear();
    }

    @Test
    public void prefixShortensBothIds() {
        assertEquals("s=abcdefgh r=12345678",
                LogContext.prefixOf("abcdefghij", "1234567890"));
        // 短 id 原样保留
        assertEquals("s=a1 r=b2", LogContext.prefixOf("a1", "b2"));
        // 只带一个时只输出那个
        assertEquals("s=sess0001", LogContext.prefixOf("sess0001", null));
        assertEquals("r=run00001", LogContext.prefixOf("  ", "run00001"));
        // 都空 → null（调用方据此不插入空括号）
        assertNull(LogContext.prefixOf(null, null));
        assertNull(LogContext.prefixOf("", ""));
    }

    @Test
    public void setAndClearAreThreadLocalAndBlankClears() throws Exception {
        LogContext.set("s=x r=y");
        assertEquals("s=x r=y", LogContext.current());
        LogContext.set("   ");
        assertNull(LogContext.current());

        LogContext.set("s=main");
        java.util.concurrent.atomic.AtomicReference<String> other = new java.util.concurrent.atomic.AtomicReference<>("unset");
        Thread t = new Thread(() -> other.set(LogContext.current()));
        t.start();
        t.join();
        assertNull("别的线程不该继承日志前缀", other.get());
        assertEquals("s=main", LogContext.current());
    }
}
