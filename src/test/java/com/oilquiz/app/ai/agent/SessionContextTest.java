package com.oilquiz.app.ai.agent;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * 会话作用域（线程局部）与路径安全清洗。
 *
 * <p>为什么要钉住两件事：①它必须是<b>线程局部</b>的——若退回静态字段，两个会话的并发工具就会共用
 * 同一个归属；②会话键会拼进文件路径（{@code tmp/s_<key>}），清洗不到位就能用 {@code ../}
 * 把临时目录指到工作区外面。</p>
 */
public class SessionContextTest {

    @After
    public void tearDown() {
        SessionContext.clear();
    }

    @Test
    public void scopeIsThreadLocalAndClearedInFinally() throws Exception {
        SessionContext.set("sess-A");
        assertEquals("sess-A", SessionContext.current());

        // 另一个线程看不到主线程的作用域（并发会话各自的工具线程必须互不可见）
        AtomicReference<String> seen = new AtomicReference<>("unset");
        Thread t = new Thread(() -> {
            seen.set(SessionContext.current());
            SessionContext.set("sess-B");
            // 自己的作用域自己可见
            assertEquals("sess-B", SessionContext.current());
        });
        t.start();
        t.join();
        assertNull("别的线程不该继承主线程作用域", seen.get());
        assertEquals("主线程作用域不受子线程影响", "sess-A", SessionContext.current());

        SessionContext.clear();
        assertNull(SessionContext.current());
    }

    @Test
    public void setReplacesAndBlankClears() {
        SessionContext.set("  a  ");
        assertEquals("a", SessionContext.current());
        SessionContext.set("b");
        assertEquals("b", SessionContext.current());
        // 空/空白 = 清除作用域（调用方据此回落到全局缺省）
        SessionContext.set("   ");
        assertNull(SessionContext.current());
        SessionContext.set("c");
        SessionContext.set(null);
        assertNull(SessionContext.current());
    }

    @Test
    public void sanitizeBlocksPathTraversalAndCapsLength() {
        // 路径分隔符与 .. 全部变成下划线：拼进 tmp/s_<key> 时不可能跳出目录
        // 非 [A-Za-z0-9_-] 一律换成下划线：../.. 与斜杠都不再具备路径语义
        String trav = SessionContext.sanitizeOrNull("../../etc/passwd");
        assertEquals("______etc_passwd", trav);
        assertFalse(trav.contains("/"));
        assertFalse(trav.contains(".."));
        assertEquals("a_b", SessionContext.sanitizeOrNull("a/b"));
        assertEquals("a_b", SessionContext.sanitizeOrNull("a\\b"));
        assertEquals("session_20261027", SessionContext.sanitizeOrNull("session 20261027"));
        assertEquals("ok-1_2", SessionContext.sanitizeOrNull("ok-1.2"));

        assertNull(SessionContext.sanitizeOrNull(null));
        assertNull(SessionContext.sanitizeOrNull(""));
        assertNull(SessionContext.sanitizeOrNull("   "));

        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 100; i++) longId.append("x");
        assertEquals(48, SessionContext.sanitizeOrNull(longId.toString()).length());
    }
}
