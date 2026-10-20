package com.oilquiz.app.ai.chat;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.SmartQuizApplication;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天消息 ID 派发器（2026-09-14）。
 *
 * 统一管理对话消息的 id 申请与发放，替代散落的 UUID.randomUUID()：
 * - 主 id（masterId）：一个消息对（一次用户发送 = 一个回合）一个；
 *   该回合的 user 消息、AI 回复（含思考/工具/汇总组件）共享同一主 id。
 * - 子 id（subId）：消息对内每条消息按【类型分类】独立申请，
 *   格式 {@code 主id-类型码序号}（如 T1-U1、T1-A1、T1-K1、T1-F1、T1-C1），
 *   单轮内（同主 id）、多轮内（主 id 序列）、连续对话内均可按 id 分类管理。
 *
 * 申请机制：
 * - applyMasterId()：开启新回合，返回主 id（T1、T2、T3…），并重置所有子类型序号。
 * - applySubId(IdType)：在当前主 id 下按类型申请子 id；无当前主 id 时自动先申请主 id。
 * - peekMasterId()：查看当前主 id（不推进序号）。
 * - reset()：对话历史清空时调用，只清当前回合/子序号，**不回收主 id 计数器**。
 *
 * 线程安全：AtomicLong + synchronized 保证并发申请不重号。
 * 唯一性说明（2026-10-21）：主 id 计数器持久化在
 * {@code chat_id_dispatcher.next_master}，清空历史、杀进程、重进都不会把 Tn 发第二遍；
 * 子 id 都带主 id 前缀，因此 T/C 等子 id 也跨会话唯一。消息级全局唯一 id 仍由
 * ChatMessage.id（UUID）承担。
 */
public class ChatIdDispatcher {

    /** 子 id 类型分类（适配器按类型码分桶管理） */
    public enum IdType {
        USER("U"),        // 用户消息
        AI("A"),          // AI 回复消息
        THINKING("K"),    // AI 思考（轮次/思考块）
        TOOL("F"),        // 工具消息（工具卡片/执行步骤）
        COMPONENT("C"),   // 工具生成的 UI 显示组件
        SYSTEM("S");      // 系统消息

        public final String code;

        IdType(String code) {
            this.code = code;
        }
    }

    private static final ChatIdDispatcher INSTANCE = new ChatIdDispatcher();

    public static ChatIdDispatcher getInstance() {
        return INSTANCE;
    }

    private final AtomicLong masterCounter = new AtomicLong(1);
    /**
     * 子序号计数：<b>按 {@code 主id|类型码} 分桶</b>，不是只按类型。
     *
     * <p>原先只按 {@link IdType} 计数且 {@code applyMasterId()} 会 {@code clear()}，
     * 两个后果：① 恢复补发（2 参 {@code applySubId(type, 旧turnId)}）与新回合的隐式派发
     * 共用同一个计数器 → 旧回合里冒出 {@code T2-C8} 这种与回合内历史不连续的编号；
     * ② 迟到的异步回调在 {@code clear()} 之后再给旧回合补 id，会拿到与已存在组件
     * <b>完全相同</b>的 id（重号 → 适配器按 id 定位/更新/移除时命中错误对象）。</p>
     */
    private final ConcurrentHashMap<String, AtomicLong> subCounters = new ConcurrentHashMap<>();
    private volatile String currentMasterId = null;

    /** 主 id 计数器落盘位置：跨会话/跨进程都不能回收，否则 T1 会被发第二遍 */
    private static final String PREFS_NAME = "chat_id_dispatcher";
    private static final String KEY_NEXT_MASTER = "next_master";
    private SharedPreferences prefs;
    private boolean loaded;

    private ChatIdDispatcher() {
    }

    /** 可选初始化：传入 Context 可提前绑定 SharedPreferences；不调用也会在首次发号时
     *  通过 {@link SmartQuizApplication#getAppContext()} 懒加载。 */
    public synchronized void init(Context context) {
        if (context == null) return;
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loaded = false;
        ensureLoadedLocked();
    }

    private void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        if (prefs == null) {
            try {
                Context ctx = SmartQuizApplication.getAppContext();
                if (ctx != null) {
                    prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                }
            } catch (Throwable ignored) {
                // 单元测试/极早启动没有 App 上下文：退化到进程内计数，行为同旧版
            }
        }
        if (prefs != null) {
            long next = prefs.getLong(KEY_NEXT_MASTER, 1L);
            if (next > masterCounter.get()) masterCounter.set(next);
        }
    }

    private void persistLocked() {
        if (prefs != null) {
            prefs.edit().putLong(KEY_NEXT_MASTER, masterCounter.get()).apply();
        }
    }

    /** 申请主 id（新回合开始）：T1、T2…
     *  <p>不在此清子计数器：子计数已按"主id|类型"分桶（见 {@link #subCounters}），
     *  清掉只会让迟到回调给旧回合补出重号 id。</p> */
    public synchronized String applyMasterId() {
        ensureLoadedLocked();
        long n = masterCounter.getAndIncrement();
        persistLocked();
        currentMasterId = "T" + n;
        return currentMasterId;
    }

    /** 申请子 id（当前回合内，按类型分类）：T1-U1、T1-A1、T1-K1…；
     *  无当前主 id 时自动先申请主 id（消息创建路径专用，推进新回合）。
     *  每类型独立递增（从 1 开始），适配器可按类型码分类 */
    public synchronized String applySubId(IdType type) {
        if (currentMasterId == null) {
            applyMasterId();
        }
        return applySubIdInternal(type, currentMasterId);
    }

    /** 申请子 id，指定主 id 前缀（恢复补发用：旧数据组件的 id 需挂到其消息的 turnId 下，
     *  保证组件 id 与消息对的主 id 一致，适配器按前缀聚合不脱节）。
     *  【重要】masterId 为 null/空时返回 null（不分配、不推进主 id）——
     *  补发路径不得消耗/推进派发器主 id，否则恢复旧数据会把新回合主 id 顶到 T3/T4… */
    public synchronized String applySubId(IdType type, String masterId) {
        if (masterId == null || masterId.isEmpty()) return null;
        return applySubIdInternal(type, masterId);
    }

    /** 子 id 生成核心：base 已确定非空，类型计数从 1 开始递增（T1-K1、T1-K2…） */
    private synchronized String applySubIdInternal(IdType type, String base) {
        IdType t = (type != null) ? type : IdType.SYSTEM;
        long n = subCounters.computeIfAbsent(base + "|" + t.code, k -> new AtomicLong(1))
                .getAndIncrement();
        return base + "-" + t.code + n;
    }

    /** 查看当前主 id（不推进序号；无则返回 null） */
    public synchronized String peekMasterId() {
        return currentMasterId;
    }

    /**
     * 清空对话时调用：只清当前回合计与子序号，<b>不回收主 id 计数器</b>。
     * 主计数器已落盘，回收会让下一个回合又拿 T1，与已导出的历史/残留组件重号。
     */
    public synchronized void reset() {
        ensureLoadedLocked();
        subCounters.clear();
        currentMasterId = null;
    }

    /**
     * 历史恢复后<b>播种</b>主 id 计数器：把 {@code masterCounter} 抬到已恢复的最大
     * {@code T<n>} 序号 + 1。
     *
     * <p>为什么必须有：{@code masterCounter} 是进程内状态、原先没有任何 seed 入口，
     * 而 {@code turnId}/{@code subId} 是随消息持久化的。杀掉应用重进 → 恢复出 T1..T8 的历史，
     * 派发器仍从 T1 开始发号 → 新回合的 turnId 与旧消息<b>重号</b>。
     * {@code ChatAdapter.getLastMessageByTurnId()} 这类"按 turnId 找消息"用的是 equals 命中<b>第一条</b>，
     * 于是流式内容/组件会被写进<b>旧的</b>那个气泡（用户看到的"主消息 id 不匹配"）。</p>
     *
     * <p>同时把 {@code currentMasterId} 置空：恢复出来的历史回合不该成为"当前回合"，
     * 否则迟到的隐式 {@code applySubId(type)} 会把子 id 挂到某个旧回合下。</p>
     *
     * @param turnIds 已恢复消息的 turnId 集合（null/空元素忽略）
     * @return 播种后的下一个主 id 序号（用于日志）；无有效 turnId 时返回当前值
     */
    public synchronized long seedFromTurnIds(java.util.Collection<String> turnIds) {
        ensureLoadedLocked();
        if (turnIds == null || turnIds.isEmpty()) {
            return masterCounter.get();
        }
        long max = 0L;
        for (String id : turnIds) {
            if (id == null) continue;
            String s = id.trim();
            if (s.length() < 2 || s.charAt(0) != 'T') continue;
            long n = -1L;
            try {
                n = Long.parseLong(s.substring(1));
            } catch (NumberFormatException ignored) {
                continue;   // 非 "T<数字>" 形态（自定义/脏数据）忽略
            }
            if (n > max) max = n;
        }
        if (max > 0 && max >= masterCounter.get()) {
            masterCounter.set(max + 1);
            persistLocked();
            currentMasterId = null;
        }
        return masterCounter.get();
    }
}
