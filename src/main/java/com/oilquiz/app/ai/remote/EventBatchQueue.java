package com.oilquiz.app.ai.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 事件批处理队列（2026-11-15，§14 事件分流）。
 *
 * <p>为什么需要：电脑端工具连续调用时每秒 50–100 条事件，旧实现是
 * {@code listener.onEvent → runOnUiThread → 逐条归约 + 逐条投影}，主线程被事件淹没，
 * 队列越积越长，表现为"比电脑慢半拍、越用越延迟"。现在网络回调只做
 * <b>入队</b>（O(1)，不碰 UI），由 UI 线程按帧批量取（见 {@code AIChatActivity}），
 * 一帧最多 {@code 50} 条，只归约、帧末投影一次。</p>
 *
 * <p>为什么纯 Java：线程模型 + 丢弃策略 + ack 水位最容易写错，却最难在真机上复现。
 * 这里不依赖 Android / org.json，单测直接喂字符串。</p>
 *
 * <p>三条不变量：</p>
 * <ol>
 *   <li><b>有界</b>：容量满时丢<b>最旧</b>的，并记账（{@link #dropped()}）——
 *       宁可少画一条工具结果，也不能让 App 把内存/主线程拖死；</li>
 *   <li><b>有序</b>：FIFO 是主序；同一批内如果出现乱序（服务端补发/双通道重叠），
 *       按 seq 升序稳定排序 —— 无 seq 的合成帧（审批/提问/ui 帧）跟随它入队时的水位，
 *       不会被排到无关事件前面；</li>
 *   <li><b>ack 只认批里最后一条</b>：{@link Batch#ackSeq} 是批内最大 seq，
 *       调用方处理完整批再 {@code confirmApplied(ackSeq)}。逐条 ack 会"提前认账"，
 *       一旦中途崩溃/异常，剩下的就再也补不回来了。</li>
 * </ol>
 *
 * <p>线程安全：{@link #offer} 可能在 OkHttp/SSE 读线程调用，{@link #drain} 在主线程。
 * 内部 {@link ConcurrentLinkedQueue} + 一把锁（只为把"深度检查/记账/发号"做成原子的；
 * 每秒几百次锁竞争可以忽略）。</p>
 *
 * @param <T> 事件对象类型（生产是 org.json.JSONObject，单测是 String）
 */
public final class EventBatchQueue<T> {

    /** 默认上限：一条事件按 1–2KB 估，2000 条约 2–4MB，超过就是真积压，丢最旧的。 */
    public static final int DEFAULT_CAPACITY = 2000;

    /** 一条入队记录（seq 与顺序号是批处理/去重的依据）。 */
    public static final class Entry<T> {
        public final T item;
        /** 事件自带的 seq；&lt;= 0 表示合成帧（审批/提问/ui 帧，不参与水位） */
        public final int seq;
        /** 全局自增顺序号：同一 groupSeq 内按它排，保证 FIFO */
        final long order;
        /** 排序键：正 seq 用自己的；合成帧沿用入队时的水位（跟着"它所属的那一段"） */
        final int groupSeq;

        Entry(T item, int seq, long order, int groupSeq) {
            this.item = item;
            this.seq = seq;
            this.order = order;
            this.groupSeq = groupSeq;
        }
    }

    /** 一批事件（不可变视图；调用方只读）。 */
    public static final class Batch<T> {
        public final List<Entry<T>> entries;
        /**
         * 这批的落屏水位＝批内最大正 seq（0 = 整批都是合成帧，不用 ack）。
         *
         * <p>必须在<b>整批处理完</b>之后拿它去 {@code confirmApplied}。按批内顺序最后一个
         * 正 seq 取也一样（批已按 seq 升序），用 max 是为了对乱序输入也正确。</p>
         */
        public final int ackSeq;
        /** 入队时被丢弃的旧事件数（把"丢过"这件事带到处理侧记一次日志） */
        public final int droppedBefore;

        Batch(List<Entry<T>> entries, int ackSeq, int droppedBefore) {
            this.entries = entries;
            this.ackSeq = ackSeq;
            this.droppedBefore = droppedBefore;
        }

        public int size() {
            return entries.size();
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    private final ConcurrentLinkedQueue<Entry<T>> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final int capacity;
    /** 全局入队顺序（单调递增，只在 lock 内读写） */
    private long orderSeq;
    /** 最近一条正 seq —— 合成帧的 groupSeq 取它（"跟着它所属的水位"） */
    private int lastPositiveSeq;
    private int depth;
    /** 自上次 takePeakDepth 以来的峰值深度（验收日志要看"最高堆到多少"） */
    private int peakDepth;
    private int dropped;
    private long offered;
    private long drained;

    public EventBatchQueue() {
        this(DEFAULT_CAPACITY);
    }

    public EventBatchQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public int capacity() {
        return capacity;
    }

    /**
     * 入队一条事件（任意线程）。
     *
     * @param item 事件对象（null 直接忽略）
     * @param seq  事件 seq；&lt;= 0 表示合成帧
     * @return true = 已入队；false = 参数被忽略（null）
     */
    public boolean offer(T item, int seq) {
        if (item == null) return false;
        synchronized (lock) {
            if (depth >= capacity) {
                // 满了：丢最旧的。丢新的会让"正在发生的"永远进不来，丢旧的只损失已过期的历史。
                Entry<T> oldest = queue.poll();
                if (oldest != null) {
                    depth -= 1;
                    dropped += 1;
                }
            }
            int group = seq > 0 ? seq : lastPositiveSeq;
            if (seq > 0) lastPositiveSeq = seq;
            queue.offer(new Entry<>(item, seq, orderSeq, group));
            orderSeq += 1;
            depth += 1;
            if (depth > peakDepth) peakDepth = depth;
            offered += 1;
        }
        return true;
    }

    /**
     * 取一批（主线程），最多 {@code max} 条；空队列返回空批（调用方据此停止循环）。
     *
     * <p>批内按 (groupSeq, 入队顺序) 升序 —— FIFO 流下顺序不变（seq 本来就递增），
     * 只有双通道重叠/补发导致的乱序才会被纠正。</p>
     */
    public Batch<T> drain(int max) {
        int limit = Math.max(1, max);
        List<Entry<T>> out = new ArrayList<>(Math.min(limit, 64));
        int droppedBefore;
        synchronized (lock) {
            for (int i = 0; i < limit; i += 1) {
                Entry<T> e = queue.poll();
                if (e == null) break;
                out.add(e);
                depth -= 1;
            }
            droppedBefore = dropped;
            dropped = 0;              // 记账交给本批的调用方记日志
            drained += out.size();
        }
        if (out.isEmpty()) return new Batch<>(out, 0, droppedBefore);
        Collections.sort(out, COMPARATOR);
        int ack = 0;
        for (Entry<T> e : out) {
            if (e.seq > ack) ack = e.seq;
        }
        return new Batch<>(out, ack, droppedBefore);
    }

    /** 稳定排序：主键 groupSeq（水位段），次键 order（入队顺序）。 */
    private static final Comparator<Entry<?>> COMPARATOR =
            (a, b) -> {
                if (a.groupSeq != b.groupSeq) return Integer.compare(a.groupSeq, b.groupSeq);
                return Long.compare(a.order, b.order);
            };

    /**
     * 快照重建用：丢掉队列里 seq &lt;= {@code watermark} 的旧事件，保留更新的与合成帧。
     *
     * <p>快照已经把水位之前的状态整体重建过了，这些事件再应用就是重复（工具卡会"闪回去"）。
     * 返回实际丢掉条数（日志用）。</p>
     */
    public int dropUpTo(int watermark) {
        if (watermark <= 0) return 0;
        int removed = 0;
        synchronized (lock) {
            int n = queue.size();
            for (int i = 0; i < n; i += 1) {
                Entry<T> e = queue.poll();
                if (e == null) break;
                if (e.seq > 0 && e.seq <= watermark) {
                    removed += 1;
                    continue;
                }
                queue.offer(e);       // 保留：重新入队保持相对顺序（同一把锁内完成）
            }
            depth = queue.size();
            dropped += removed;
        }
        return removed;
    }

    /** 清空（重连/切会话/快照重建时用）。 */
    public int clear() {
        synchronized (lock) {
            int n = 0;
            while (queue.poll() != null) n += 1;
            depth = 0;
            peakDepth = 0;
            lastPositiveSeq = 0;
            return n;
        }
    }

    public int depth() {
        synchronized (lock) {
            return depth;
        }
    }

    /**
     * 取走窗口内峰值深度（取走即归零到当前深度）：验收日志要报"队列峰值"，
     * 但峰值不能一直挂着旧值，否则重连后那一行永远很大。
     */
    public int takePeakDepth() {
        synchronized (lock) {
            int peak = peakDepth;
            peakDepth = depth;
            return peak;
        }
    }

    /** 累计被丢弃的旧事件数（不重置）。 */
    public int droppedTotal() {
        synchronized (lock) {
            return dropped;
        }
    }

    /**
     * 取走"自上次 drain 以来丢弃数"：处理侧每批记一次日志用。
     * 与 {@link Batch#droppedBefore} 不同，这个不受批边界影响。
     */
    public int takeDropped() {
        synchronized (lock) {
            int n = dropped;
            dropped = 0;
            return n;
        }
    }

    public long offeredTotal() {
        synchronized (lock) {
            return offered;
        }
    }

    public long drainedTotal() {
        synchronized (lock) {
            return drained;
        }
    }

    public boolean isEmpty() {
        return depth() == 0;
    }

    /** 诊断串：{@code depth/capacity dropped=…} */
    public String stats() {
        synchronized (lock) {
            return "depth=" + depth + "/" + capacity + " dropped=" + dropped
                    + " offered=" + offered + " drained=" + drained;
        }
    }
}
