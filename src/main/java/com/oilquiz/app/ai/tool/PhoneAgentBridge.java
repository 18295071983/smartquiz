package com.oilquiz.app.ai.tool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 电脑驱动手机 agent 的单次往返桥（2026-11-18，agent_ask）。
 *
 * <p>为什么不另起一套推理：手机 agent 的提示词拼装、工具选择、多轮循环都在
 * 聊天页的 {@code AIChatActivity} 里（系统提示、工具 schema、本地/在线模型）。
 * 这里只做"把电脑的 prompt 塞进聊天页的发送管线，等这一轮结束拿最终文本"，
 * 保证和用户手输完全同一条路径。</p>
 *
 * <p>一次只允许一条（与 MCP 授权同一思路）：并发请求直接返回 busy。</p>
 */
public final class PhoneAgentBridge {

    private static final Object LOCK = new Object();
    private static volatile CountDownLatch latch;
    private static volatile AtomicReference<String> text;
    private static volatile AtomicReference<String> error;

    private PhoneAgentBridge() {
    }

    /** 占用一次往返；已有请求在跑时返回 false。 */
    public static boolean begin() {
        synchronized (LOCK) {
            if (latch != null) return false;
            latch = new CountDownLatch(1);
            text = new AtomicReference<>(null);
            error = new AtomicReference<>(null);
            return true;
        }
    }

    /** 聊天页生成完成时调用（主线程）。 */
    public static void onReply(String content) {
        AtomicReference<String> t = text;
        if (t != null) t.set(content == null ? "" : content);
        CountDownLatch l = latch;
        if (l != null) l.countDown();
    }

    /** 聊天页生成失败时调用（主线程）。 */
    public static void onError(String message) {
        AtomicReference<String> e = error;
        if (e != null) e.set(message == null ? "手机 agent 执行失败" : message);
        CountDownLatch l = latch;
        if (l != null) l.countDown();
    }

    /** 等待结果；返回 null 表示超时。 */
    public static String await(long timeoutMs) throws InterruptedException {
        CountDownLatch l = latch;
        if (l == null) return null;
        if (!l.await(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS)) return null;
        AtomicReference<String> e = error;
        if (e != null && e.get() != null) return null;
        AtomicReference<String> t = text;
        return t == null ? "" : t.get();
    }

    /** 释放占用（finally 调用）。 */
    public static void finish() {
        synchronized (LOCK) {
            latch = null;
            text = null;
            error = null;
        }
    }

    /** 最近一次失败原因（await 返回 null 时用来区分超时/报错）。 */
    public static String lastError() {
        AtomicReference<String> e = error;
        return e == null ? null : e.get();
    }

    /** 当前是否有请求在跑（UI 可用它显示状态）。 */
    public static boolean isBusy() {
        CountDownLatch l = latch;
        return l != null && l.getCount() > 0;
    }
}
