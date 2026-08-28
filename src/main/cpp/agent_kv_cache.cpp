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
        AGENT_KV_LOGI("HIT: matched %d tokens, delta eval %zu tokens",
                      matchedLen_, tokensList.size() - matchedLen_);
    } else if (partialIncremental) {
        strategy_ = Strategy::PARTIAL;
        AGENT_KV_LOGI("PARTIAL: matched %d/%d tokens, delta eval %zu tokens",
                      matchedLen_, cachedNPast_, tokensList.size() - matchedLen_);
    } else {
        // ---- 3. MISS 原因细分（诊断：区分"该清"与"意外清掉"）----
        if (!kvCacheValid_) fullEvalReason_ = "first_call_or_invalidated";
        else if (matchedLen_ == 0) fullEvalReason_ = "no_prefix_match";
        else if (mem == nullptr) fullEvalReason_ = "memory_null";
        else fullEvalReason_ = "seq_pos_mismatch";
        AGENT_KV_LOGI("FULL EVAL reason: %s (valid=%d, cached=%zu, matched=%d, new=%zu, cachedNPast=%d)",
                      fullEvalReason_, (int)kvCacheValid_, cachedTokens_.size(),
                      matchedLen_, tokensList.size(), cachedNPast_);
    }
}

bool AgentKvCache::truncate(llama_memory_t mem) {
    if (mem == nullptr || strategy_ != Strategy::PARTIAL) return false;
    // 删除 [matchedLen_, ∞) 的 KV（只保留前缀）
    if (llama_memory_seq_rm(mem, 0, matchedLen_, -1)) {
        AGENT_KV_LOGI("PARTIAL truncated at %d", matchedLen_);
        return true;
    }
    AGENT_KV_LOGE("PARTIAL seq_rm failed");
    return false;
}

void AgentKvCache::record(const std::vector<llama_token>& promptTokens,
                          const std::vector<llama_token>& generatedTokens) {
    // 记账 = prompt + 生成输出（KV 里实际 decode 的完整序列）
    cachedTokens_ = promptTokens;
    cachedTokens_.insert(cachedTokens_.end(), generatedTokens.begin(), generatedTokens.end());
    cachedNPast_ = (int)cachedTokens_.size();
    kvCacheValid_ = true;
    AGENT_KV_LOGI("bookkeeping updated: cachedNPast=%d (prompt=%zu + generated=%zu)",
                  cachedNPast_, promptTokens.size(), generatedTokens.size());
}

void AgentKvCache::invalidate() {
    cachedTokens_.clear();
    cachedNPast_ = 0;
    kvCacheValid_ = false;
    strategy_ = Strategy::FULL;
    matchedLen_ = 0;
    AGENT_KV_LOGI("cache invalidated (external KV clear)");
}
