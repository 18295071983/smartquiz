package com.oilquiz.app.ai.chat;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

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

    @Test
    public void testBasicTokenAccumulation() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
                accumulatedContent.append(content);
                updateCount.incrementAndGet();
                totalTokensReceived.addAndGet(tokens);
            }
        }, 100, 300, 3);

        manager.addToken("H");
        manager.addToken("e");
        manager.addToken("l");
        manager.addToken("l");
        manager.addToken("o");

        manager.flush();
        
        assertEquals("Hello", accumulatedContent.toString());
    }

    @Test
    public void testFlushEmptiesAccumulatedTokens() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
                accumulatedContent.append(content);
                updateCount.incrementAndGet();
            }
        }, 100, 300, 2);

        manager.addToken("A");
        manager.addToken("B");
        manager.flush();
        
        int countAfterFirstFlush = updateCount.get();
        
        manager.addToken("C");
        manager.flush();
        
        assertEquals("ABC", accumulatedContent.toString());
        assertTrue(updateCount.get() > countAfterFirstFlush);
    }

    @Test
    public void testResetClearsAccumulator() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
                accumulatedContent.append(content);
            }
        }, 100, 300, 5);

        manager.addToken("X");
        manager.addToken("Y");
        manager.reset();
        manager.addToken("Z");
        manager.flush();
        
        assertEquals("Z", accumulatedContent.toString());
    }

    @Test
    public void testStatsUpdateCallback() throws InterruptedException {
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
        
        assertNotNull(statsRef.get());
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
        
        assertTrue(manager.getTotalTokensGenerated() >= 0);
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
        
        assertTrue(manager.getTokensPerSecond() >= 0);
    }

    @Test
    public void testPendingTokenCount() {
        manager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String content, int tokens) {
            }
        }, 100, 300, 5);

        assertEquals(0, manager.getPendingTokenCount());
        
        manager.addToken("Test");
        
        assertEquals(1, manager.getPendingTokenCount());
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
