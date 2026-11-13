#include "agent_kv_cache.h"

void AgentKvCache::plan(const std::vector<llama_token>& tokensList, llama_memory_t mem) {
    matchedLen_ = 0;
    strategy_ = Strategy::FULL;
    fullEvalReason_ = "unknown";

    // ---- 1. 前缀匹配：新 prompt 与缓存 token 序列逐 token 比较 ----
    if (kvCacheValid_ && mem != nullptr && !cachedTokens_.empty()) {
        size_t minLen = std::min(tokensList.size(), cachedTokens_.size());
        size_t m = 0;
        while (m < minLen && tokensList[m] == cachedTokens_[m]) m++;
        matchedLen_ = (int)m;
    }

    // ---- 2. 三级策略判定 ----
    bool incremental = false;
    bool partialIncremental = false;
    if (kvCacheValid_ && matchedLen_ > 0 && mem != nullptr) {
        llama_pos seqMax = llama_memory_seq_pos_max(mem, 0);
        if (seqMax >= matchedLen_ - 1) {
            if (matchedLen_ == cachedNPast_ && seqMax == cachedNPast_ - 1) {
                incremental = true;   // 严格超集 + 位置一致
            } else if (matchedLen_ < (int)tokensList.size()) {
                partialIncremental = true;  // 前缀可复用，截断后增量
            }
        } else {
            AGENT_KV_LOGW("seq_pos_max mismatch: expected >=%d, got %lld; invalidating cache",
                          matchedLen_ - 1, (long long)seqMax);
        }
    }

    if (incremental) {
        strategy_ = Strategy::INCREMENTAL;
        incCount_++;
        AGENT_KV_LOGI("HIT: matched %d tokens, delta eval %zu tokens",
                      matchedLen_, tokensList.size() - matchedLen_);
    } else if (partialIncremental) {
        strategy_ = Strategy::PARTIAL;
        partCount_++;
        // 把"失配点在 prompt 内还是生成尾"带出来：matched < lastPromptTokens 说明
        // 历史/模板重渲染已经改了 prompt 中段，不是单纯生成尾漂移。
        AGENT_KV_LOGI("PARTIAL: matched %d/%d (prompt=%d + gen=%d), delta eval %zu tokens",
                      matchedLen_, cachedNPast_, lastPromptTokens_, lastGeneratedTokens_,
                      tokensList.size() - matchedLen_);
    } else {
        // ---- 3. MISS 原因细分（诊断：区分"该清"与"意外清掉"）----
        fullCount_++;
        if (!kvCacheValid_) fullEvalReason_ = "first_call_or_invalidated";
        else if (matchedLen_ == 0) fullEvalReason_ = "no_prefix_match";
        else if (mem == nullptr) fullEvalReason_ = "memory_null";
        else fullEvalReason_ = "seq_pos_mismatch";
        AGENT_KV_LOGD_KV("FULL EVAL reason: %s (valid=%d, cached=%zu, matched=%d, new=%zu, cachedNPast=%d)",
                      fullEvalReason_, (int)kvCacheValid_, cachedTokens_.size(),
                      matchedLen_, tokensList.size(), cachedNPast_);
    }

    // ---- 监控：聚合统计 ----
    // 首轮也打一次：短会话（plan < STATS_INTERVAL）否则一条摘要都看不到，而"第一次就没命中"
    // 恰恰是最有诊断价值的那个点（首轮通常 FULL，用它对照后续 inc）。
    planCount_++;
    if (planCount_ == 1 || planCount_ % STATS_INTERVAL == 0) {
        dumpStats();
    }
}

void AgentKvCache::dumpStats() {
    long total = planCount_;
    double hit = hitRate();
    double planned = plannedHitRate();
    double usage = ctxUsage();
    // 两个百分比并排：hit=实际复用，planned=计划复用。差值就是"打算 PARTIAL 但截断失败"
    // 的那部分（本机混合架构下通常等于全部 part）——排障时先看差值，再看 full_reason。
    //
    // KV-STATS-VISIBLE(2026-11-13)：这行原为 DEBUG，而 engineLogAppend 有一条
    // `if (level == 'D') return;` —— 于是 App 内"引擎日志"页与落盘日志里永远看不到摘要，
    // 只能在 logcat 里捞。命中率改成实际态之后，这个摘要就是唯一能回答"本地优化到底有没有效"
    // 的数，必须能落地。降为 DEBUG 的那批（PARTIAL seq_rm 失败）保持原级别不动：那是
    // 设计内的性能回退，不该在用户视图里冒充故障。
    AGENT_KV_LOGI("KV-STATS: plans=%ld inc=%d part=%d part_applied=%d full=%d "
                  "hit=%.1f%% planned=%.1f%% ctx_usage=%.1f%% (cachedNPast=%d/%d)",
                  total, incCount_, partCount_, partApplied_, fullCount_, hit * 100.0,
                  planned * 100.0, usage * 100.0, cachedNPast_, ctxSize_);
}

bool AgentKvCache::truncate(llama_memory_t mem) {
    if (mem == nullptr || strategy_ != Strategy::PARTIAL) return false;
    // 删除 [matchedLen_, ∞) 的 KV（只保留前缀）
    if (llama_memory_seq_rm(mem, 0, matchedLen_, -1)) {
        partApplied_++;   // HIT-RATE-HONEST：只有这里成功，才算真正复用上（命中率分子）
        AGENT_KV_LOGI("PARTIAL truncated at %d", matchedLen_);
        return true;
    }
    // 混合记忆（attention+recurrent hybrid）的 seq_rm 只支持尾部 n_rs_seq 窗口内回滚：
    // 深截断（截断距离 = cachedNPast - matchedLen）超出回滚窗口必然失败 → 回退全量 prefill。
    // 保留失败详情便于诊断（2026-09-23 真机实测：每次工具轮后 PARTIAL 均在此失败，20s/轮）。
    //
    // KV-TRIM-TAIL(2026-11-08)：不要试图用 sys_probe::kvTrimTail() 替换这里——它内部也是
    // 同一条 llama_memory_seq_rm(mem, 0, keep, -1)，而失败根因是 ctx_params.n_rs_seq=0
    // （循环层不保留回滚快照），不是"截断方向不对"。要省掉全量 prefill，只能避免 prompt
    // 在中间失配（历史裁剪/模板变化）或增大 n_ctx 让裁剪不发生；n_rs_seq>0 的内存代价
    // 本机实测过大（见 native-lib.cpp 2540 附近：设 256 直接触发 lowmemorykiller）。
    //
    // LOG-LEVEL-FIX(2026-11-01)：这行原先用 LOGE，引擎日志里每轮一条红字，读日志的人会当成
    // 引擎故障——实际是**设计内的性能回退**（下一行 I 级 "full eval %zu tokens" 已记录结果）。
    // 降到 KV 调试级，与 KV-STATS 同类：诊断时开 debug 才看，用户视图不再出现假报错。
    AGENT_KV_LOGD_KV("PARTIAL seq_rm unsupported in hybrid memory (rollback=%d from cachedNPast=%d); full eval fallback",
                  cachedNPast_ - matchedLen_, cachedNPast_);
    return false;
}

void AgentKvCache::record(const std::vector<llama_token>& promptTokens,
                          const std::vector<llama_token>& generatedTokens) {
    // 记账 = prompt + 生成输出（KV 里实际 decode 的完整序列）
    cachedTokens_ = promptTokens;
    cachedTokens_.insert(cachedTokens_.end(), generatedTokens.begin(), generatedTokens.end());
    cachedNPast_ = (int)cachedTokens_.size();
    lastPromptTokens_ = (int)promptTokens.size();
    lastGeneratedTokens_ = (int)generatedTokens.size();
    kvCacheValid_ = true;
    AGENT_KV_LOGI("bookkeeping updated: cachedNPast=%d (prompt=%zu + generated=%zu), ctx_usage=%.1f%%",
                  cachedNPast_, promptTokens.size(), generatedTokens.size(), ctxUsage() * 100.0);
}

void AgentKvCache::invalidate() {
    cachedTokens_.clear();
    cachedNPast_ = 0;
    kvCacheValid_ = false;
    strategy_ = Strategy::FULL;
    matchedLen_ = 0;
    AGENT_KV_LOGI("cache invalidated (external KV clear)");
}
