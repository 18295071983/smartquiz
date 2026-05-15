#include "agent_inference.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <random>
#include <sstream>

#define LOG_TAG "AgentInference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace agent_inference {

static const int ADRENO_SAFE_UBATCH = 32;

AgentInferenceContext::AgentInferenceContext()
    : model(nullptr), ctx(nullptr), vocab(nullptr),
      contextSize(0), threadCount(4), gpuLayers(0), batchSize(512),
      totalTokensUsed(0),
      shouldStop(false), currentState(AgentState::IDLE),
      hasPromptInCache(false), cachedPromptLength(0),
      useGrammarConstraints(false) {
    LOGI("AgentInferenceContext created");
}

AgentInferenceContext::~AgentInferenceContext() {
    release();
}

void AgentInferenceContext::setGpuLayers(int layers) {
    gpuLayers = layers;
    LOGI("GPU layers set to: %d", gpuLayers);
}

int AgentInferenceContext::getGpuLayers() const {
    return gpuLayers;
}

void AgentInferenceContext::resetContextMemory() {
    if (ctx) {
        llama_memory_t mem = llama_get_memory(ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
    }
    hasPromptInCache = false;
    cachedPromptTokens.clear();
    cachedPromptLength = 0;
}

bool AgentInferenceContext::initialize(const std::string& modelPath,
                                        int ctxSize, int nThreads) {
    std::lock_guard<std::mutex> lock(mutex);

    this->contextSize = ctxSize;
    this->threadCount = nThreads > 0 ? nThreads : 4;

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = gpuLayers;
    modelParams.use_mmap = true;

    LOGI("Loading model: %s (gpu_layers=%d)", modelPath.c_str(), gpuLayers);
    model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (!model) {
        LOGE("Failed to load model");
        return false;
    }

    vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("Failed to get vocab");
        llama_model_free(model);
        model = nullptr;
        return false;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = contextSize;
    ctxParams.n_threads = threadCount;
    ctxParams.n_threads_batch = threadCount;
    ctxParams.n_batch = batchSize;
    ctxParams.n_ubatch = ADRENO_SAFE_UBATCH;

    ctx = llama_init_from_model(model, ctxParams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        model = nullptr;
        vocab = nullptr;
        return false;
    }

    totalTokensUsed = 0;
    hasPromptInCache = false;
    currentState = AgentState::IDLE;
    LOGI("AgentInferenceContext initialized (ctx=%d, threads=%d, gpu_layers=%d, n_ubatch=%d)",
         contextSize, threadCount, gpuLayers, ADRENO_SAFE_UBATCH);
    return true;
}

bool AgentInferenceContext::isInitialized() const {
    return model != nullptr && ctx != nullptr && vocab != nullptr;
}

void AgentInferenceContext::cancel() {
    shouldStop = true;
    currentState = AgentState::CANCELLED;
    LOGI("Agent inference cancelled");
}

void AgentInferenceContext::reset() {
    std::lock_guard<std::mutex> lock(mutex);
    shouldStop = false;
    currentState = AgentState::IDLE;
    totalTokensUsed = 0;
    resetContextMemory();
    LOGI("AgentInferenceContext reset");
}

void AgentInferenceContext::release() {
    std::lock_guard<std::mutex> lock(mutex);
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
    vocab = nullptr;
    contextSize = 0;
    cachedPromptTokens.clear();
    hasPromptInCache = false;
    LOGI("AgentInferenceContext released");
}

// ========== MAIN ENTRY POINTS ==========

std::string AgentInferenceContext::generateAgentResponse(
        const std::string& userInput, const InferenceParams& params,
        ToolExecutor toolExecutor) {
    shouldStop = false;
    totalTokensUsed = 0;

    AgentTokenCallback nullCallback = nullptr;

    switch (params.mode) {
        case AgentMode::DIRECT:
            return executeDirect(userInput, params, toolExecutor);
        case AgentMode::CHAIN_OF_THOUGHT:
            return executeChainOfThought(userInput, params, toolExecutor);
        case AgentMode::REACT:
            return executeReAct(userInput, params, nullCallback, toolExecutor);
        case AgentMode::PLAN_EXECUTE:
            return executePlanExecute(userInput, params, nullCallback, toolExecutor);
        default:
            return executeDirect(userInput, params, toolExecutor);
    }
}

bool AgentInferenceContext::generateAgentResponseStream(
        const std::string& userInput, const InferenceParams& params,
        AgentTokenCallback callback, ToolExecutor toolExecutor) {
    shouldStop = false;
    totalTokensUsed = 0;

    std::string result;
    switch (params.mode) {
        case AgentMode::DIRECT:
            result = executeDirectStream(userInput, params, callback, toolExecutor);
            break;
        case AgentMode::CHAIN_OF_THOUGHT:
            result = executeChainOfThoughtStream(userInput, params, callback, toolExecutor);
            break;
        case AgentMode::REACT:
            result = executeReAct(userInput, params, callback, toolExecutor);
            break;
        case AgentMode::PLAN_EXECUTE:
            result = executePlanExecute(userInput, params, callback, toolExecutor);
            break;
        default:
            result = executeDirect(userInput, params, toolExecutor);
            break;
    }

    if (!result.empty() && callback) {
        notifyToken(callback, result, AgentState::COMPLETED, "completion", 100);
    }
    return !result.empty();
}

// ========== MODE EXECUTORS ==========

std::string AgentInferenceContext::executeDirect(const std::string& input,
        const InferenceParams& params, ToolExecutor /*toolExecutor*/) {
    currentState = AgentState::GENERATING;

    std::string prompt = buildPromptFromTemplate(
        "你是一个智能助手，请用中文回答。", input);
    if (prompt.empty()) {
        LOGE("Failed to build chat prompt");
        return "";
    }

    std::lock_guard<std::mutex> lock(mutex);
    resetContextMemory();

    return generateSync(prompt, params.maxTokens,
                        params.temperature, params.topP, params.topK);
}

std::string AgentInferenceContext::executeChainOfThought(const std::string& input,
        const InferenceParams& params, ToolExecutor /*toolExecutor*/) {
    std::string thinking;
    {
        std::lock_guard<std::mutex> lock(mutex);
        currentState = AgentState::THINKING;
        resetContextMemory();

        std::string thinkingUserPrompt = "问题: " + input +
            "\n\n请对上述问题进行逐步分析，写出思考过程。";
        std::string thinkingPrompt = buildPromptFromTemplate(
            "请对问题进行逐步分析，先写出思考过程，再给出最终答案。", thinkingUserPrompt);
        if (thinkingPrompt.empty()) return "";

        thinking = generateSync(thinkingPrompt,
                                params.thinkingMaxTokens, params.temperature,
                                params.topP, params.topK);
    }

    std::string answer;
    {
        std::lock_guard<std::mutex> lock(mutex);
        currentState = AgentState::GENERATING;
        resetContextMemory();

        std::string answerUserPrompt;
        if (!thinking.empty()) {
            answerUserPrompt = "原始问题: " + input +
                "\n\n思考过程:\n" + thinking +
                "\n\n请基于以上思考过程，给出简洁的最终答案。";
        } else {
            answerUserPrompt = "问题: " + input + "\n\n请直接给出答案。";
        }

        std::string answerPrompt = buildPromptFromTemplate(
            "基于思考过程给出最终答案。如果思考过程已包含答案，请简洁总结。", answerUserPrompt);
        if (answerPrompt.empty()) return "";

        answer = generateSync(answerPrompt,
                              params.maxTokens, params.temperature,
                              params.topP, params.topK);
    }

    currentState = AgentState::COMPLETED;
    return answer;
}

std::string AgentInferenceContext::executeReAct(const std::string& input,
        const InferenceParams& params, AgentTokenCallback callback,
        ToolExecutor toolExecutor) {
    std::ostringstream finalAnswer;
    std::ostringstream contextBuffer;
    std::ostringstream toolHistoryStr;

    contextBuffer << "用户问题: " << input << "\n";

    for (int i = 0; i < params.maxLoopIterations && !shouldStop; i++) {
        notifyStateChange(callback, AgentState::THINKING, i * 100 / params.maxLoopIterations);

        std::string reactPrompt = buildReActPrompt(input, contextBuffer.str(),
                toolHistoryStr.str(), params, i);

        std::string reactResponse;
        {
            std::lock_guard<std::mutex> lock(mutex);
            resetContextMemory();

            reactResponse = generateSync(reactPrompt,
                    std::min(params.thinkingMaxTokens, params.maxTokens / 2),
                    params.temperature, params.topP, params.topK);
        }

        if (reactResponse.empty()) break;

        std::string thought, action, actionInput, answer;
        parseReActResponse(reactResponse, thought, action, actionInput, answer);

        if (!thought.empty()) {
            if (callback) {
                notifyToken(callback, thought, AgentState::THINKING, "thought", 0);
            }
            contextBuffer << "思考: " << thought << "\n";
        }

        if (!action.empty()) {
            currentState = AgentState::ACTING;
            if (callback) {
                notifyToken(callback, action, AgentState::ACTING, "tool_call", 0);
            }
            contextBuffer << "行动: " << action << "(" << actionInput << ")\n";

            currentState = AgentState::OBSERVING;
            std::string observation;
            if (toolExecutor) {
                observation = toolExecutor(action, actionInput);
            } else {
                observation = "工具 [" + action + "] 已调用。参数: " + actionInput;
            }

            if (callback) {
                notifyToken(callback, observation, AgentState::OBSERVING, "observation", 0);
            }
            contextBuffer << "观察: " << observation << "\n";
            toolHistoryStr << action << "(" << actionInput << ") → " << observation << "\n";
        }

        if (!answer.empty()) {
            finalAnswer.str("");
            finalAnswer << answer;
            break;
        }
    }

    currentState = AgentState::COMPLETED;
    return finalAnswer.str();
}

std::string AgentInferenceContext::executePlanExecute(const std::string& input,
        const InferenceParams& params, AgentTokenCallback callback,
        ToolExecutor toolExecutor) {
    currentState = AgentState::THINKING;
    notifyStateChange(callback, currentState, 0);

    std::string planResponse;
    {
        std::lock_guard<std::mutex> lock(mutex);
        resetContextMemory();

        planResponse = generateSync(buildPlanPrompt(input, params),
                                    params.thinkingMaxTokens, params.temperature,
                                    params.topP, params.topK);
    }

    std::vector<std::string> steps = parsePlanSteps(planResponse);
    if (steps.empty()) {
        steps.push_back("直接回答用户问题");
    }

    std::ostringstream result;
    result << "## 执行计划\n\n";
    for (size_t i = 0; i < steps.size(); i++) {
        result << (i + 1) << ". " << steps[i] << "\n";
    }
    result << "\n---\n\n";

    for (size_t i = 0; i < steps.size() && !shouldStop; i++) {
        const std::string& step = steps[i];
        currentState = AgentState::THINKING;
        notifyStateChange(callback, currentState, (i * 100) / steps.size());

        bool isToolStep = (step.find("调用") != std::string::npos ||
                          step.find("工具") != std::string::npos ||
                          step.find("查询") != std::string::npos);

        if (isToolStep && !params.tools.empty() && toolExecutor) {
            notifyToken(callback, "工具执行: " + step, AgentState::ACTING, "tool_step", 0);

            std::string toolPrompt = buildToolSelectionPrompt(step, params);
            std::string toolResponse;
            {
                std::lock_guard<std::mutex> lock(mutex);
                resetContextMemory();

                toolResponse = generateSync(toolPrompt, params.toolResponseMaxTokens,
                                            params.temperature, params.topP, params.topK);
            }

            std::string thought, action, actionInput, answer;
            parseReActResponse(toolResponse, thought, action, actionInput, answer);

            if (!action.empty()) {
                std::string obs = toolExecutor(action, actionInput);
                result << "> **工具** [" << action << "] → " << obs << "\n\n";
                if (callback) {
                    notifyToken(callback, obs, AgentState::OBSERVING, "tool_result", 0);
                }
            }
        } else {
            notifyToken(callback, "执行步骤: " + step, AgentState::GENERATING, "step_exec", 0);

            std::string stepUserPrompt = "原始问题: " + input +
                "\n当前子任务: " + step +
                "\n\n请执行此子任务，给出清晰的结果。";
            std::string stepPrompt = buildPromptFromTemplate(
                "执行以下子任务，请用中文给出清晰的结果。", stepUserPrompt);

            std::string stepResult;
            {
                std::lock_guard<std::mutex> lock(mutex);
                resetContextMemory();

                stepResult = generateSync(stepPrompt,
                        std::min(params.maxTokens / std::max(1, (int)steps.size()), 256),
                        params.temperature, params.topP, params.topK);
            }
            if (!stepResult.empty()) {
                result << stepResult << "\n\n";
            }
        }
    }

    currentState = AgentState::COMPLETED;
    return result.str();
}

// ========== PROMPT BUILDERS ==========

bool AgentInferenceContext::buildChatPromptWithTemplate(
        const std::vector<std::string>& roles,
        const std::vector<std::string>& contents,
        std::string& output) {
    if (!model || !vocab) {
        LOGE("Model not loaded, cannot build chat prompt");
        return false;
    }

    if (roles.size() != contents.size() || roles.empty()) {
        LOGE("Invalid message arrays");
        return false;
    }

    size_t msgCount = roles.size();
    std::vector<llama_chat_message> chatMessages(msgCount);

    for (size_t i = 0; i < msgCount; i++) {
        chatMessages[i].role = roles[i].c_str();
        chatMessages[i].content = contents[i].c_str();
    }

    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) {
        LOGI("Using model chat template");
    } else {
        LOGI("No chat template found, using fallback");
    }

    size_t totalChars = 0;
    for (const auto& c : contents) totalChars += c.size();
    size_t bufSize = totalChars * 2 + msgCount * 64;
    std::vector<char> buf(bufSize);

    int result = llama_chat_apply_template(
        tmpl,
        chatMessages.data(),
        chatMessages.size(),
        true,
        buf.data(),
        buf.size()
    );

    if (result > (int)bufSize) {
        buf.resize(result);
        result = llama_chat_apply_template(
            tmpl,
            chatMessages.data(),
            chatMessages.size(),
            true,
            buf.data(),
            buf.size()
        );
    }

    if (result < 0) {
        LOGE("Failed to apply chat template");
        return false;
    }

    output = std::string(buf.data(), result);
    LOGI("Built chat prompt with %zu messages, final length: %zu", msgCount, output.length());
    return true;
}

std::string AgentInferenceContext::buildPromptFromTemplate(
        const std::string& systemPrompt, const std::string& userPrompt) {
    std::vector<std::string> roles = {"system", "user"};
    std::vector<std::string> contents = {systemPrompt, userPrompt};
    std::string output;
    if (!buildChatPromptWithTemplate(roles, contents, output)) {
        LOGE("buildPromptFromTemplate failed");
        return "";
    }
    return output;
}

std::string AgentInferenceContext::buildThinkingPrompt(const std::string& input, const InferenceParams& /*params*/) {
    std::string userPrompt = "问题: " + input +
        "\n\n请对上述问题进行逐步分析，写出思考过程。";
    return buildPromptFromTemplate(
        "请对问题进行逐步分析，先写出思考过程，再给出最终答案。", userPrompt);
}

std::string AgentInferenceContext::buildAnswerPrompt(const std::string& input, const std::string& thinking, const InferenceParams& /*params*/) {
    std::string userPrompt;
    if (!thinking.empty()) {
        userPrompt = "原始问题: " + input +
            "\n\n思考过程:\n" + thinking +
            "\n\n请基于以上思考过程，给出简洁的最终答案。";
    } else {
        userPrompt = "问题: " + input + "\n\n请直接给出答案。";
    }
    return buildPromptFromTemplate(
        "基于思考过程给出最终答案。如果思考过程已包含答案，请简洁总结。", userPrompt);
}

std::string AgentInferenceContext::buildReActPrompt(const std::string& input, const std::string& contextBuffer,
                                                       const std::string& toolHistory, const InferenceParams& params,
                                                       int iteration) {
    std::ostringstream userPrompt;
    userPrompt << "问题: " << input << "\n\n"
               << "当前迭代: " << iteration << "/" << params.maxLoopIterations << "\n";

    if (!contextBuffer.empty()) {
        userPrompt << "\n对话历史:\n" << contextBuffer << "\n";
    }

    if (!toolHistory.empty()) {
        userPrompt << "\n工具使用历史:\n" << toolHistory << "\n";
    }

    userPrompt << "\n请按以下格式输出（每行一个标签）：\n"
               << "思考: [你的思考]\n"
               << "行动: [工具名称 或 直接回答]\n"
               << "行动输入: [工具参数]\n"
               << "最终答案: [如果不需要工具]\n";

    std::string systemPrompt = "你是一个会使用工具的智能助手。按以下步骤思考和行动：\n"
        "1. 思考(Thought)：分析问题\n"
        "2. 行动(Action)：决定是否调用工具\n"
        "3. 观察(Observation)：处理工具返回结果\n"
        "4. 答案(Final Answer)：给出最终答案\n";

    if (!params.tools.empty()) {
        systemPrompt += "\n可用工具:\n";
        for (const auto& tool : params.tools) {
            systemPrompt += "- " + tool.name + ": " + tool.description + "\n";
        }
    }

    return buildPromptFromTemplate(systemPrompt, userPrompt.str());
}

std::string AgentInferenceContext::buildPlanPrompt(const std::string& input, const InferenceParams& /*params*/) {
    std::string userPrompt = "任务: " + input +
        "\n\n请列出执行步骤（每行一个步骤，使用数字编号）：";
    return buildPromptFromTemplate(
        "你是一个擅长规划的智能助手。请将复杂任务分解为多个步骤。", userPrompt);
}

std::string AgentInferenceContext::buildToolSelectionPrompt(const std::string& step, const InferenceParams& params) {
    std::string userPrompt = "当前步骤: " + step +
        "\n\n请判断是否需要工具，并输出：\n"
        "思考: [分析]\n"
        "行动: [工具名 或 直接完成]\n"
        "行动输入: [参数]\n";

    std::string systemPrompt = "请决定是否需要调用工具来完成这个步骤。";
    if (!params.tools.empty()) {
        systemPrompt += "\n可用工具:\n";
        for (const auto& tool : params.tools) {
            systemPrompt += "- " + tool.name + ": " + tool.description + "\n";
        }
    }

    return buildPromptFromTemplate(systemPrompt, userPrompt);
}

// ========== GENERATION CORE ==========

std::string AgentInferenceContext::generateSync(const std::string& prompt,
        int maxTokens, float temperature, float topP, int topK) {
    if (!ctx || !vocab) return "";

    std::vector<llama_token> promptTokens = tokenize(prompt);
    if (promptTokens.empty()) return "";

    LOGI("Generation: prompt=%zu tokens, maxTokens=%d", promptTokens.size(), maxTokens);

    llama_batch promptBatch = llama_batch_init((int)promptTokens.size(), 0, 1);
    for (int i = 0; i < (int)promptTokens.size(); i++) {
        promptBatch.token[i] = promptTokens[i];
        promptBatch.pos[i] = i;
        promptBatch.n_seq_id[i] = 1;
        promptBatch.seq_id[i][0] = 0;
        promptBatch.logits[i] = (i == (int)promptTokens.size() - 1) ? 1 : 0;
    }
    promptBatch.n_tokens = (int)promptTokens.size();

    int ret = llama_decode(ctx, promptBatch);
    llama_batch_free(promptBatch);
    if (ret != 0) {
        LOGE("Prompt decode failed: %d", ret);
        return "";
    }

    int nPast = (int)promptTokens.size();
    int nRemain = maxTokens;
    std::string output;

    auto startTime = std::chrono::steady_clock::now();

    while (nRemain > 0 && !shouldStop) {
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - startTime).count();
        if (elapsed > 300) {
            LOGW("Generation timeout after %d seconds", 300);
            break;
        }

        llama_token sampledToken = sampleToken(ctx, temperature, topP, topK);

        if (llama_vocab_is_eog(vocab, sampledToken)) break;

        if (sampledToken == 0 || sampledToken == 128001 || sampledToken == 128009) break;

        std::string tokenStr = detokenize(sampledToken);
        if (tokenStr.find("<|im_end|>") != std::string::npos ||
            tokenStr.find("</s>") != std::string::npos ||
            tokenStr.find("<|endoftext|") != std::string::npos ||
            tokenStr.find("<|eot_id|>") != std::string::npos) {
            break;
        }

        output += tokenStr;

        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.token[0] = sampledToken;
        batch.pos[0] = nPast;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        ret = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (ret != 0) {
            LOGE("Token decode failed: %d", ret);
            break;
        }

        nPast++;
        nRemain--;
        totalTokensUsed++;
    }

    LOGI("Generation done: %zu chars, %d tokens used", output.size(), totalTokensUsed);
    return output;
}

bool AgentInferenceContext::generateStreaming(const std::string& prompt,
        int maxTokens, float temperature, float topP, int topK,
        AgentTokenCallback callback) {
    if (!ctx || !vocab) return false;

    std::vector<llama_token> promptTokens = tokenize(prompt);
    if (promptTokens.empty()) return false;

    LOGI("Streaming Generation: prompt=%zu tokens, maxTokens=%d", promptTokens.size(), maxTokens);

    llama_batch promptBatch = llama_batch_init((int)promptTokens.size(), 0, 1);
    for (int i = 0; i < (int)promptTokens.size(); i++) {
        promptBatch.token[i] = promptTokens[i];
        promptBatch.pos[i] = i;
        promptBatch.n_seq_id[i] = 1;
        promptBatch.seq_id[i][0] = 0;
        promptBatch.logits[i] = (i == (int)promptTokens.size() - 1) ? 1 : 0;
    }
    promptBatch.n_tokens = (int)promptTokens.size();

    int ret = llama_decode(ctx, promptBatch);
    llama_batch_free(promptBatch);
    if (ret != 0) {
        LOGE("Prompt decode failed: %d", ret);
        return false;
    }

    int nPast = (int)promptTokens.size();
    int nRemain = maxTokens;
    bool inThinking = false;

    auto startTime = std::chrono::steady_clock::now();

    while (nRemain > 0 && !shouldStop) {
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - startTime).count();
        if (elapsed > 300) break;

        llama_token sampledToken = sampleToken(ctx, temperature, topP, topK);

        if (llama_vocab_is_eog(vocab, sampledToken)) break;
        if (sampledToken == 0 || sampledToken == 128001 || sampledToken == 128009) break;

        std::string tokenStr = detokenize(sampledToken);

        if (tokenStr.find("<thinking>") != std::string::npos) {
            inThinking = true;
            tokenStr.clear();
        } else if (tokenStr.find("</thinking>") != std::string::npos) {
            inThinking = false;
            tokenStr.clear();
        } else if (tokenStr.find("<think>") != std::string::npos) {
            inThinking = true;
            tokenStr.clear();
        } else if (tokenStr.find("</think>") != std::string::npos) {
            inThinking = false;
            tokenStr.clear();
        }

        if (tokenStr.find("</s>") != std::string::npos ||
            tokenStr.find("<|im_end|>") != std::string::npos ||
            tokenStr.find("<|eot_id|>") != std::string::npos) {
            break;
        }

        if (callback && !tokenStr.empty()) {
            AgentState state = inThinking ? AgentState::THINKING : AgentState::GENERATING;
            notifyToken(callback, tokenStr, state, "token", 0);
        }

        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.token[0] = sampledToken;
        batch.pos[0] = nPast;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        ret = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (ret != 0) break;

        nPast++;
        nRemain--;
        totalTokensUsed++;
    }

    return true;
}

std::string AgentInferenceContext::executeDirectStream(const std::string& input,
        const InferenceParams& params, AgentTokenCallback callback,
        ToolExecutor /*toolExecutor*/) {
    currentState = AgentState::GENERATING;

    std::string prompt = buildPromptFromTemplate(
        "你是一个智能助手，请用中文回答。", input);
    if (prompt.empty()) return "";

    std::lock_guard<std::mutex> lock(mutex);
    resetContextMemory();

    generateStreaming(prompt, params.maxTokens,
                      params.temperature, params.topP, params.topK,
                      callback);
    return "";
}

std::string AgentInferenceContext::executeChainOfThoughtStream(const std::string& input,
        const InferenceParams& params, AgentTokenCallback callback,
        ToolExecutor /*toolExecutor*/) {
    currentState = AgentState::THINKING;

    std::string thinkingPrompt = buildThinkingPrompt(input, params);
    {
        std::lock_guard<std::mutex> lock(mutex);
        resetContextMemory();

        generateStreaming(thinkingPrompt, params.thinkingMaxTokens,
                          params.temperature, params.topP, params.topK,
                          callback);
    }

    currentState = AgentState::GENERATING;
    std::string answerPrompt = buildAnswerPrompt(input, "", params);
    {
        std::lock_guard<std::mutex> lock(mutex);
        resetContextMemory();

        generateStreaming(answerPrompt, params.maxTokens,
                          params.temperature, params.topP, params.topK,
                          callback);
    }

    currentState = AgentState::COMPLETED;
    return "";
}

// ========== TOKENIZATION ==========

std::vector<llama_token> AgentInferenceContext::tokenize(const std::string& text) {
    if (!vocab) return {};
    int nTokens = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, true, true);
    if (nTokens <= 0) return {};
    std::vector<llama_token> tokens(nTokens);
    if (llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), nTokens, true, true) < 0) {
        return {};
    }
    return tokens;
}

std::string AgentInferenceContext::detokenize(llama_token token) {
    if (!vocab) return "";
    char buf[256] = {0};
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n >= 0) {
        return std::string(buf, n);
    }
    return "";
}

// ========== SAMPLING ==========

llama_token AgentInferenceContext::sampleToken(llama_context* ctx,
        float temperature, float topP, int topK) {
    auto* logits = llama_get_logits(ctx);
    int nVocab = llama_vocab_n_tokens(vocab);

    if (temperature <= 0.0f) {
        llama_token bestTok = 0;
        float bestLogit = -1e9f;
        for (int i = 0; i < nVocab; i++) {
            if (logits[i] > bestLogit) {
                bestLogit = logits[i];
                bestTok = i;
            }
        }
        return bestTok;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    if (topK > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(std::min(topK, nVocab)));
    }
    if (topP > 0.0f && topP < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    llama_token result = llama_sampler_sample(smpl, ctx, -1);
    llama_sampler_free(smpl);
    return result;
}

// ========== RESPONSE PARSING ==========

void AgentInferenceContext::parseReActResponse(const std::string& response,
        std::string& thought, std::string& action,
        std::string& actionInput, std::string& finalAnswer) {
    thought.clear();
    action.clear();
    actionInput.clear();
    finalAnswer.clear();

    std::istringstream stream(response);
    std::string line;
    while (std::getline(stream, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) {
            line.pop_back();
        }
        while (!line.empty() && (line.front() == ' ')) {
            line.erase(0, 1);
        }

        if (line.find("思考:") == 0 || line.find("思考：") == 0) {
            thought = line.substr(line.find(":") != std::string::npos ?
                    line.find(":") + 1 : line.find("：") + 1);
            while (!thought.empty() && thought.front() == ' ') thought.erase(0, 1);
        } else if (line.find("行动:") == 0 || line.find("行动：") == 0) {
            size_t pos = line.find(":") != std::string::npos ?
                    line.find(":") + 1 : line.find("：") + 1;
            action = line.substr(pos);
            while (!action.empty() && action.front() == ' ') action.erase(0, 1);
        } else if (line.find("行动输入:") == 0 || line.find("行动输入：") == 0) {
            size_t pos = line.find(":") != std::string::npos ?
                    line.find(":") + 1 : line.find("：") + 1;
            actionInput = line.substr(pos);
            while (!actionInput.empty() && actionInput.front() == ' ') actionInput.erase(0, 1);
        } else if (line.find("最终答案:") == 0 || line.find("最终答案：") == 0) {
            size_t pos = line.find(":") != std::string::npos ?
                    line.find(":") + 1 : line.find("：") + 1;
            finalAnswer = line.substr(pos);
            while (!finalAnswer.empty() && finalAnswer.front() == ' ') finalAnswer.erase(0, 1);
        }
    }

    if (finalAnswer.empty() && response.find("行动:") == std::string::npos) {
        finalAnswer = response;
    }
}

std::vector<std::string> AgentInferenceContext::parsePlanSteps(const std::string& planResponse) {
    std::vector<std::string> steps;

    std::istringstream stream(planResponse);
    std::string line;
    while (std::getline(stream, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        while (!line.empty() && line.front() == ' ') line.erase(0, 1);

        if (line.empty()) continue;

        size_t pos = 0;
        while (pos < line.size()) {
            if (std::isdigit(line[pos]) || line[pos] == '.' || line[pos] == ')' ||
                line[pos] == ':' || line[pos] == ' ') {
                pos++;
            } else if (pos + 2 < line.size() && line.compare(pos, 3, "\u3001") == 0) {
                pos += 3;
            } else if (pos + 2 < line.size() && line.compare(pos, 3, "\uFF1A") == 0) {
                pos += 3;
            } else {
                break;
            }
        }
        line = line.substr(pos);

        if (!line.empty() && line.size() > 2) {
            steps.push_back(line);
        }
    }
    return steps;
}

// ========== CALLBACK NOTIFICATION ==========

void AgentInferenceContext::notifyStateChange(AgentTokenCallback callback,
        AgentState state, int progress) {
    currentState = state;
    if (callback) {
        callback("", state, "state_change", progress);
    }
}

void AgentInferenceContext::notifyToken(AgentTokenCallback callback,
        const std::string& token, AgentState state,
        const std::string& eventType, int progress) {
    currentState = state;
    if (callback) {
        callback(token, state, eventType, progress);
    }
}

} // namespace agent_inference
