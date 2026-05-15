#include "agent_inference.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <random>
#include <sstream>
#include <thread>

#define LOG_TAG "AgentInferenceOptimized"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace agent_inference {

static const int ADRENO_SAFE_UBATCH = 32;

bool AgentInferenceContext::processPromptBatch(const std::vector<llama_token>& tokens, int& nPast) {
    if (tokens.empty() || !ctx) return false;

    llama_batch batch = llama_batch_init((int)tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = nPast + (int)i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == tokens.size() - 1) ? 1 : 0;
    }
    batch.n_tokens = (int)tokens.size();

    int ret = llama_decode(ctx, batch);
    llama_batch_free(batch);

    if (ret != 0) {
        LOGE("processPromptBatch decode failed: %d", ret);
        return false;
    }

    nPast += (int)tokens.size();
    return true;
}

bool AgentInferenceContext::processSingleToken(llama_token token, int nPast) {
    if (!ctx) return false;

    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.token[0] = token;
    batch.pos[0] = nPast;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    batch.n_tokens = 1;

    int ret = llama_decode(ctx, batch);
    llama_batch_free(batch);

    if (ret != 0) {
        LOGE("processSingleToken decode failed: %d", ret);
        return false;
    }

    return true;
}

std::string AgentInferenceContext::buildToolsGrammar(const std::vector<ToolDef>& tools) {
    if (tools.empty()) return "";

    std::ostringstream grammar;
    grammar << "root ::= thought action\n";
    grammar << "thought ::= [^\n]* \"\\n\"\n";
    grammar << "action ::= \"行动: \" toolname \"\\n\" \"行动输入: \" toolinput\n";
    grammar << "toolname ::= ";

    for (size_t i = 0; i < tools.size(); i++) {
        grammar << "\"" << tools[i].name << "\"";
        if (i + 1 < tools.size()) grammar << " | ";
    }
    grammar << "\n";
    grammar << "toolinput ::= [^\n]*\n";

    return grammar.str();
}

} // namespace agent_inference
