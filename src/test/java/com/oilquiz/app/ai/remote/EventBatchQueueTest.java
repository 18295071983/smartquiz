package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 批处理队列：顺序、ack 水位、上限丢弃、快照水位清理（2026-11-15，§14）。
 *
 * <p>这四条都是"真机上很难复现、写错了却会静默丢事件"的地方，用纯 JVM 单测钉住。</p>
 */
public class EventBatchQueueTest {

    private static List<String> items(EventBatchQueue.Batch<String> batch) {
        List<String> out = new ArrayList<>();
        for (EventBatchQueue.Entry<String> e : batch.entries) out.add(e.item);
        return out;
    }

    @Test
    public void drainKeepsOfferOrderAndAcksLastSeq() {
        EventBatchQueue<String> q = new EventBatchQueue<>(100);
        for (int i = 1; i <= 5; i += 1) q.offer("e" + i, i);

        EventBatchQueue.Batch<String> b = q.drain(50);
        assertEquals(5, b.size());
        assertEquals(java.util.Arrays.asList("e1", "e2", "e3", "e4", "e5"), items(b));
        // 落屏确认只报"批里最后一条"：逐条 ack 会在中途崩溃时丢事件
        assertEquals(5, b.ackSeq);
        assertTrue(q.isEmpty());
    }

    @Test
    public void drainRespectsMaxPerFrame() {
        EventBatchQueue<String> q = new EventBatchQueue<>(200);
        for (int i = 1; i <= 120; i += 1) q.offer("e" + i, i);

        EventBatchQueue.Batch<String> first = q.drain(50);
        assertEquals(50, first.size());
        assertEquals(50, first.ackSeq);
        assertEquals(70, q.depth());

        EventBatchQueue.Batch<String> second = q.drain(50);
        assertEquals("e51", second.entries.get(0).item);
        assertEquals(100, second.ackSeq);
    }

    @Test
    public void outOfOrderSeqWithinBatchIsSortedAscending() {
        EventBatchQueue<String> q = new EventBatchQueue<>(100);
        // 双通道重叠（WS 推 + pull 补）时同一帧里可能乱序到达
        q.offer("e9", 9);
        q.offer("e7", 7);
        q.offer("e8", 8);

        List<String> got = items(q.drain(50));
        assertEquals(java.util.Arrays.asList("e7", "e8", "e9"), got);
    }

    @Test
    public void syntheticFramesKeepTheirPlaceAroundWatermark() {
        // 审批/提问帧没有 seq：不能因为它们被排到整批最前面或最后面
        EventBatchQueue<String> q = new EventBatchQueue<>(100);
        q.offer("e1", 1);
        q.offer("approval", 0);
        q.offer("e2", 2);
        q.offer("question", -1);
        q.offer("e3", 3);

        EventBatchQueue.Batch<String> b = q.drain(50);
        assertEquals(java.util.Arrays.asList("e1", "approval", "e2", "question", "e3"), items(b));
        // 合成帧不参与水位：ack 仍是最大正 seq
        assertEquals(3, b.ackSeq);
    }

    @Test
    public void overflowDropsOldestAndCounts() {
        EventBatchQueue<String> q = new EventBatchQueue(3);
        for (int i = 1; i <= 5; i += 1) q.offer("e" + i, i);

        assertEquals(3, q.depth());
        // 丢了最旧的两条：正在发生的不能丢
        assertEquals(2, q.takeDropped());
        assertEquals(0, q.takeDropped());        // takeDropped 取走即清零

        EventBatchQueue.Batch<String> b = q.drain(10);
        assertEquals(java.util.Arrays.asList("e3", "e4", "e5"), items(b));
        assertEquals(5, b.ackSeq);
    }

    @Test
    public void emptyDrainReturnsEmptyBatchWithoutAck() {
        EventBatchQueue<String> q = new EventBatchQueue<>(10);
        EventBatchQueue.Batch<String> b = q.drain(50);
        assertTrue(b.isEmpty());
        assertEquals(0, b.ackSeq);
    }

    @Test
    public void dropUpToSnapshotWatermarkKeepsNewerEvents() {
        EventBatchQueue<String> q = new EventBatchQueue<>(100);
        for (int i = 1; i <= 10; i += 1) q.offer("e" + i, i);
        q.offer("approval", 0);

        int removed = q.dropUpTo(6);
        assertEquals(6, removed);
        List<String> got = items(q.drain(50));
        // 7..10 留着；合成帧没有 seq，不参与水位过滤（排在水位 10 之后，同属一段）
        assertEquals(java.util.Arrays.asList("e7", "e8", "e9", "e10", "approval"), got);
    }

    @Test
    public void clearResetsDepth() {
        EventBatchQueue<String> q = new EventBatchQueue<>(100);
        for (int i = 1; i <= 7; i += 1) q.offer("e" + i, i);
        assertEquals(7, q.clear());
        assertEquals(0, q.depth());
        assertTrue(q.isEmpty());
    }

    @Test
    public void offerNullIsIgnored() {
        EventBatchQueue<String> q = new EventBatchQueue<>(10);
        assertFalse(q.offer(null, 1));
        assertEquals(0, q.depth());
    }

    @Test
    public void peakDepthTracksHighWaterAndResetsOnClear() {
        EventBatchQueue<String> q = new EventBatchQueue<>(10);
        for (int i = 1; i <= 7; i += 1) q.offer("e" + i, i);
        assertEquals(7, q.takePeakDepth());      // 峰值报出去
        assertEquals(7, q.takePeakDepth());      // 没再涨：保持当前深度
        q.clear();
        assertEquals(0, q.takePeakDepth());
    }
}
