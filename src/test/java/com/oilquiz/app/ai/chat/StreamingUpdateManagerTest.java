package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link StreamingUpdateManager} 单元测试。
 *
 * <p>必须跑在 Robolectric 下：生产实现把回调
 * {@code mainHandler.post(() -> callback.onUpdate(...))} 抛到主线程
 * （见 {@code StreamingUpdateManager#flushUpdatesLocked}）。纯 JUnit 环境没有主 Looper，
 * post 会静默丢失，于是"回调收到什么内容"这类断言永远拿到空串——之前 5 条红就是这么来的。
 * 所以每个用例在 addToken/flush 之后都要 {@link #idleMainLooper()} 一次，
 * 让排队的回调真正执行；{@link #callbackIsDeliveredOnMainThread()} 专门钉住这个契约。
 *
 * <p>另一处与直觉不同、但属于现行契约的行为：{@code lastUpdateTimeMs} 初值是 0，
 * 所以<b>第一个 token 一定触发一次立即 flush</b>（timeSinceLastUpdate 巨大 →
 * shouldForceUpdate=true）。这不是缺陷（首个 token 早点亮屏正是想要的），
 * 但会让"addToken 一次后 pending==1"这种断言失效，本文件按实际契约写。
 */
@RunWith(RobolectricTestRunner.class)
public class StreamingUpdateManagerTest {

    private StreamingUpdateManager manager;
    private StringBuilder accumulatedContent;
    private AtomicInteger updateCount;
    private AtomicInteger totalTokensReceived;

    @Before
    public void setUp() {
        accumulatedContent = new StringBuilder();
        updateCount = new AtomicInteger(0);
        totalTokensReceived = new AtomicInteger(0);
    }

    @After
    public void tearDown() {
        manager = null;
    }

    /** 执行主线程上排队的回调；不补这一步，onUpdate/onStatsUpdate 永远不会被调用 */
    private void idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private StreamingUpdateManager.UpdateCallback recorder() {
        return new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
                accumulatedContent.append(content);
                updateCount.incrementAndGet();
                totalTokensReceived.addAndGet(tokens);
            }
        };
    }

    @Test
    public void testBasicTokenAccumulation() {
        manager = new StreamingUpdateManager(recorder(), 100, 300, 3);

        manager.addToken("H");
        manager.addToken("e");
        manager.addToken("l");
        manager.addToken("l");
        manager.addToken("o");
        manager.flush();
        idleMainLooper();

        // 回调按"批次"给整段累积内容：冷启动先给 "H"，flush 再给 "ello"
        assertEquals("Hello", accumulatedContent.toString());
        assertEquals(5, totalTokensReceived.get());
    }

    @Test
    public void callbackIsDeliveredOnMainThread() {
        final AtomicReference<String> seen = new AtomicReference<>();
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
                seen.set(content);
            }
        }, 100, 300, 10);

        manager.addToken("A");
        manager.flush();
        // 还没 idle：回调必须仍未发生
        assertNull("flush() 只是把回调排到主线程，不能同步回调", seen.get());

        idleMainLooper();
        assertEquals("A", seen.get());
    }

    @Test
    public void testFlushEmptiesAccumulatedTokens() {
        manager = new StreamingUpdateManager(recorder(), 100, 300, 2);

        manager.addToken("A");
        manager.addToken("B");
        manager.flush();
        idleMainLooper();

        int countAfterFirstFlush = updateCount.get();
        assertEquals(2, manager.getTotalTokensGenerated());

        manager.addToken("C");
        manager.flush();
        idleMainLooper();

        assertEquals("ABC", accumulatedContent.toString());
        assertTrue(updateCount.get() > countAfterFirstFlush);
        // 批次清空后不应把上一批再吐一遍
        assertEquals(3, totalTokensReceived.get());
    }

    @Test
    public void testResetClearsAccumulator() {
        manager = new StreamingUpdateManager(recorder(), 100, 300, 5);

        manager.addToken("X");
        manager.addToken("Y");
        idleMainLooper();                  // 冷启动那次 flush 已经把 "X" 送出去了
        manager.reset();                   // 丢弃还没发出去的 "Y"
        accumulatedContent.setLength(0);   // 只关心 reset 之后的内容

        manager.addToken("Z");
        manager.flush();
        idleMainLooper();

        assertEquals("Z", accumulatedContent.toString());
        assertEquals(1, manager.getTotalTokensGenerated());
    }

    @Test
    public void testStatsUpdateCallback() {
        final AtomicReference<StreamingUpdateManager.StreamingStats> statsRef =
                new AtomicReference<>();

        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
                accumulatedContent.append(content);
            }

            @Override
            public void onStatsUpdate(StreamingUpdateManager.StreamingStats stats) {
                statsRef.set(stats);
            }
        }, 50, 100, 2);

        manager.addToken("A");
        manager.addToken("B");
        manager.flush();
        idleMainLooper();

        assertNotNull(statsRef.get());
        assertEquals(2, statsRef.get().totalTokens);
    }

    @Test
    public void testGetTotalTokens() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
            }
        }, 100, 300, 10);

        for (int i = 0; i < 10; i++) {
            manager.addToken("T");
        }
        idleMainLooper();

        assertEquals(10, manager.getTotalTokensGenerated());
    }

    @Test
    public void testElapsedTime() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
            }
        }, 100, 300, 10);

        manager.addToken("Test");
        manager.flush();
        idleMainLooper();

        assertTrue(manager.getElapsedTimeMs() >= 0);
    }

    @Test
    public void testTokensPerSecond() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
            }
        }, 100, 300, 10);

        manager.addToken("T1");
        manager.addToken("T2");
        manager.addToken("T3");
        manager.flush();
        idleMainLooper();

        assertTrue(manager.getTokensPerSecond() >= 0);
    }

    /**
     * 攒批计数。冷启动第一个 token 会被立即 flush（见类注释），
     * 所以"pending 会攒起来"要从第二批开始断言。
     */
    @Test
    public void testPendingTokenCount() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
            }
        }, 100, 300, 5);

        assertEquals(0, manager.getPendingTokenCount());

        manager.addToken("A");     // 冷启动 → 立即 flush，pending 归 0
        idleMainLooper();
        assertEquals(0, manager.getPendingTokenCount());

        manager.addToken("B");     // 未到 minTokens(5)、未到 minInterval(100ms) → 攒着
        assertEquals(1, manager.getPendingTokenCount());

        manager.addToken("C");
        assertEquals(2, manager.getPendingTokenCount());

        manager.flush();
        idleMainLooper();
        assertEquals(0, manager.getPendingTokenCount());
    }

    @Test
    public void testGetCurrentStats() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
            }
        }, 100, 300, 5);

        manager.addToken("A");
        manager.addToken("B");
        idleMainLooper();

        StreamingUpdateManager.StreamingStats stats = manager.getCurrentStats();
        assertNotNull(stats);
        assertEquals(2, stats.totalTokens);
    }

    @Test
    public void testStreamingStatsToString() {
        StreamingUpdateManager.StreamingStats stats =
                new StreamingUpdateManager.StreamingStats(100, 10, 5000, 20.0f, 100);

        String str = stats.toString();
        assertNotNull(str);
        assertTrue(str.contains("100"));
        assertTrue(str.contains("20"));
    }
}
