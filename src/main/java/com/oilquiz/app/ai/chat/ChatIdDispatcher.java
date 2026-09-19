package com.oilquiz.app.ai.chat;

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
 * - reset()：对话历史清空时调用，派发器归零重来（T1 重新开始）。
 *
 * 线程安全：AtomicLong + synchronized 保证并发申请不重号。
 * 唯一性说明：主/子 id 仅保证"当前会话内"唯一（reset 后重新计数）；
 * 消息级全局唯一 id 仍由 ChatMessage.id（UUID）承担（跨会话恢复不冲突）。
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
    private final ConcurrentHashMap<IdType, AtomicLong> subCounters = new ConcurrentHashMap<>();
    private volatile String currentMasterId = null;

    private ChatIdDispatcher() {
    }

    /** 申请主 id（新回合开始）：T1、T2…；同时重置所有子类型序号 */
    public synchronized String applyMasterId() {
        subCounters.clear();
        currentMasterId = "T" + masterCounter.getAndIncrement();
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
        long n = subCounters.computeIfAbsent(t, k -> new AtomicLong(1)).getAndIncrement();
        return base + "-" + t.code + n;
    }

    /** 查看当前主 id（不推进序号；无则返回 null） */
    public synchronized String peekMasterId() {
        return currentMasterId;
    }

    /** 重置派发器：对话历史清空时调用，主 id 从 T1 重新开始、子序号清零 */
    public synchronized void reset() {
        masterCounter.set(1);
        subCounters.clear();
        currentMasterId = null;
    }
}
