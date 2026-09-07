#ifndef AGENT_KV_CACHE_H
#define AGENT_KV_CACHE_H

// ============================================================
// Agent 专用 KV 增量缓存（本地 FC 多轮循环专用）
// ============================================================
// 背景：本地 Agent（Qwen3-4B + FC）在单次任务内会连续多轮调用 chatJson
// （每轮：模型输出 tool_call → 执行工具 → 结果回填 → 下一轮继续）。
// 若每轮都全量重 eval 整个 prompt（system + tools + 历史），decode 时间
// 成倍浪费——这正是"每次全量 decode"问题的根源。
//
// 本类解决：同一任务内连续轮次复用 KV cache，只 eval 增量 token。
//   1. 完全超集（新 prompt 是旧 prompt 的 token 级超集，且 KV 位置一致）
//      → 只 eval 追加部分（最省）
//   2. 部分前缀匹配（tools JSON 增长/历史变化导致局部失配）
//      → llama_memory_seq_rm 截断失配点之后的 KV，复用前缀，只 eval 增量
//   3. 前缀为空 / KV 被外部清除 → 全量重 eval
//
// 关键记账原则：cachedTokens 必须记录"KV 里实际已 eval 的完整 token 序列"，
// 即 prompt token + 生成输出 token（此前只记 prompt 导致 seq_pos_max 校验
// 必败 → 每轮全量，详见历史修复）。
// ============================================================

#include <vector>
#include <android/log.h>

#include "llama.h"

#define AGENT_KV_LOG_TAG "AgentKvCache"
#define AGENT_KV_LOGI(...) __android_log_print(ANDROID_LOG_INFO, AGENT_KV_LOG_TAG, __VA_ARGS__)
#define AGENT_KV_LOGW(...) __android_log_print(ANDROID_LOG_WARN, AGENT_KV_LOG_TAG, __VA_ARGS__)
#define AGENT_KV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AGENT_KV_LOG_TAG, __VA_ARGS__)

/**
 * Agent 专用 KV 增量缓存。
 *
 * 用法（在生成路径中）：
 *   1. 每次调用前：cache.plan(newPromptTokens, llama_get_memory(ctx))
 *      - strategy() == INCREMENTAL → 只 eval tokens_list[matchedLen:]
 *      - strategy() == PARTIAL     → 先 cache.truncate(mem)（内部 seq_rm），
 *                                    再 eval tokens_list[matchedLen:]
 *      - strategy() == FULL        → 全量 eval（首次/失配/外部清除）
 *   2. 生成结束后：cache.record(promptTokens, generatedTokens)
 *      —— 记账包含生成输出 token，下轮才能命中
 *   3. 任何外部路径清空 KV（clearContextForInference / release / 重载）时：
 *      cache.invalidate() —— 同步失效记账，防止误报 seq_pos_mismatch
 */
class AgentKvCache {
public:
    enum class Strategy {
        INCREMENTAL,   // 完全超集，只 eval 增量
        PARTIAL,       // 部分前缀复用，截断后 eval 增量
        FULL           // 全量重 eval
    };

    AgentKvCache() = default;
    ~AgentKvCache() = default;

    // 禁止拷贝（持有大向量）
    AgentKvCache(const AgentKvCache&) = delete;
    AgentKvCache& operator=(const AgentKvCache&) = delete;

    /**
     * 增量判定：给定本轮完整 prompt token 序列与当前 KV memory，
     * 计算复用策略与匹配长度。
     * 返回后可用 matchedLen() / strategy() 查询结果。
     */
    void plan(const std::vector<llama_token>& tokensList, llama_memory_t mem);

    /**
     * PARTIAL 时调用：删除 [matchedLen, ∞) 的 KV，只保留前缀。
     * @return true 成功；false 删除失败（调用方应回退全量）
     */
    bool truncate(llama_memory_t mem);

    /**
     * 记账更新：本轮生成结束后调用。
     * @param promptTokens 本轮完整 prompt token（含 system/tools/历史/当前消息）
     * @param generatedTokens 本轮生成输出的 token（必须与 KV 实际 decode 一致）
     */
    void record(const std::vector<llama_token>& promptTokens,
                const std::vector<llama_token>& generatedTokens);

    /**
     * 失效记账：任何外部路径清空 KV 后必须调用，
     * 否则下轮 plan() 会把"KV 已空"误判为 seq_pos_mismatch。
     */
    void invalidate();

    // ---- 查询 ----
    Strategy strategy() const { return strategy_; }
    bool isIncremental() const { return strategy_ == Strategy::INCREMENTAL; }
    bool isPartial() const { return strategy_ == Strategy::PARTIAL; }
    bool isFull() const { return strategy_ == Strategy::FULL; }
    int matchedLen() const { return matchedLen_; }
    int cachedNPast() const { return cachedNPast_; }
    size_t cachedSize() const { return cachedTokens_.size(); }
    bool valid() const { return kvCacheValid_; }

    /** 全量时的原因（诊断用） */
    const char* fullEvalReason() const { return fullEvalReason_; }

    // ---- 监控统计（命中率 / 上下文占用）----
    // 记录每次 plan 的策略分布，计算 KV 增量缓存命中率；周期性输出聚合日志，
    // 供诊断"为什么没吃到增量缓存"（普通对话每轮新 prompt 多走 FULL，需可观测）。
    void setContextSize(int ctx) { ctxSize_ = ctx; }
    long planCount() const { return planCount_; }
    int incCount() const { return incCount_; }
    int partCount() const { return partCount_; }
    int fullCount() const { return fullCount_; }
    int contextSize() const { return ctxSize_; }
    double hitRate() const {
        return planCount_ > 0 ? (double)(incCount_ + partCount_) / (double)planCount_ : 0.0;
    }
    /** 上下文占用比例（0.0-1.0）：cachedNPast / n_ctx */
    double ctxUsage() const {
        return ctxSize_ > 0 ? (double)cachedNPast_ / (double)ctxSize_ : 0.0;
    }
    /** 输出聚合统计摘要（每次 plan 都累加，每 STATS_INTERVAL 次打印一次） */
    void dumpStats();

private:
    // KV 中实际已 eval 的完整 token 序列（prompt + 生成输出）
    std::vector<llama_token> cachedTokens_;
    int cachedNPast_ = 0;         // 已 eval 位置（== cachedTokens_.size()）
    bool kvCacheValid_ = false;   // 记账是否有效（未被外部清除）

    Strategy strategy_ = Strategy::FULL;
    int matchedLen_ = 0;
    const char* fullEvalReason_ = "unknown";

    // ---- 监控统计状态 ----
    static const int STATS_INTERVAL = 10;  // 每 N 次 plan 打印一次统计摘要
    int ctxSize_ = 0;              // n_ctx（上下文总长）
    long planCount_ = 0;           // plan 总调用次数
    int incCount_ = 0;             // INCREMENTAL 次数
    int partCount_ = 0;            // PARTIAL 次数
    int fullCount_ = 0;            // FULL 次数
};

#endif // AGENT_KV_CACHE_H
