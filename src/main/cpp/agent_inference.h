#ifndef AGENT_INFERENCE_H
#define AGENT_INFERENCE_H

#include "llama.h"
#include <string>
#include <functional>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <atomic>

namespace agent_inference {

enum class AgentMode {
    DIRECT,
    CHAIN_OF_THOUGHT,
    REACT,
    PLAN_EXECUTE
};

enum class AgentState {
    IDLE,
    PREPARING,
    THINKING,
    ACTING,
    OBSERVING,
    GENERATING,
    COMPLETED,
    ERROR,
    CANCELLED
};

struct ToolDef {
    std::string name;
    std::string description;
    std::unordered_map<std::string, std::string> parameters;
};

struct InferenceParams {
    AgentMode mode = AgentMode::CHAIN_OF_THOUGHT;
    int maxTokens = 512;
    float temperature = 0.7f;
    float topP = 0.9f;
    int topK = 40;
    int maxLoopIterations = 10;
    int thinkingMaxTokens = 300;
    int toolResponseMaxTokens = 256;
    std::vector<ToolDef> tools;
    bool useKVCacheReuse = true;
    bool enableStreaming = true;
};

struct AgentStepResult {
    AgentState state;
    std::string thought;
    std::string action;
    std::string actionInput;
    std::string observation;
    std::string finalAnswer;
    int tokensUsed;
    int iteration;
};

using AgentTokenCallback = std::function<void(
    const std::string& token,
    AgentState state,
    const std::string& eventType,
    int progress
)>;

using ToolExecutor = std::function<std::string(
    const std::string& toolName,
    const std::string& params
)>;

class AgentInferenceContext {
public:
    AgentInferenceContext();
    ~AgentInferenceContext();

    bool initialize(const std::string& modelPath, int contextSize, int nThreads);
    bool isInitialized() const;

    void setGpuLayers(int layers);
    int getGpuLayers() const;

    std::string generateAgentResponse(
        const std::string& userInput,
        const InferenceParams& params,
        ToolExecutor toolExecutor
    );

    bool generateAgentResponseStream(
        const std::string& userInput,
        const InferenceParams& params,
        AgentTokenCallback callback,
        ToolExecutor toolExecutor
    );

    void cancel();
    void reset();
    void release();

    int getTotalTokensUsed() const { return totalTokensUsed; }
    int getContextSize() const { return contextSize; }
    AgentState getState() const { return currentState; }

private:
    llama_model* model;
    llama_context* ctx;
    const llama_vocab* vocab;

    int contextSize;
    int threadCount;
    int gpuLayers;
    int batchSize;
    int totalTokensUsed;

    std::atomic<bool> shouldStop;
    std::atomic<AgentState> currentState;
    std::mutex mutex;

    bool hasPromptInCache;
    std::vector<llama_token> cachedPromptTokens;
    int cachedPromptLength;

    bool useGrammarConstraints;

    void resetContextMemory();

    bool generateStreaming(const std::string& prompt, int maxTokens,
                           float temperature, float topP, int topK,
                           AgentTokenCallback callback);

    std::string executeDirectStream(const std::string& input,
                                    const InferenceParams& params,
                                    AgentTokenCallback callback,
                                    ToolExecutor toolExecutor);

    std::string executeChainOfThoughtStream(const std::string& input,
                                           const InferenceParams& params,
                                           AgentTokenCallback callback,
                                           ToolExecutor toolExecutor);

    std::string generateSync(const std::string& prompt, int maxTokens, float temperature,
                             float topP, int topK);

    std::string executeDirect(const std::string& input, const InferenceParams& params,
                              ToolExecutor toolExecutor);

    bool buildChatPromptWithTemplate(const std::vector<std::string>& roles,
                                     const std::vector<std::string>& contents,
                                     std::string& output);

    std::string buildPromptFromTemplate(const std::string& systemPrompt,
                                        const std::string& userPrompt);

    std::string executeChainOfThought(const std::string& input, const InferenceParams& params,
                                      ToolExecutor toolExecutor);

    std::string executeReAct(const std::string& input, const InferenceParams& params,
                             AgentTokenCallback callback, ToolExecutor toolExecutor);

    std::string executePlanExecute(const std::string& input, const InferenceParams& params,
                                   AgentTokenCallback callback, ToolExecutor toolExecutor);

    std::string buildThinkingPrompt(const std::string& input, const InferenceParams& params);
    std::string buildAnswerPrompt(const std::string& input, const std::string& thinking,
                                  const InferenceParams& params);
    std::string buildReActPrompt(const std::string& input, const std::string& contextBuffer,
                                 const std::string& toolHistory, const InferenceParams& params,
                                 int iteration);
    std::string buildPlanPrompt(const std::string& input, const InferenceParams& params);
    std::string buildToolSelectionPrompt(const std::string& step, const InferenceParams& params);

    std::vector<llama_token> tokenize(const std::string& text);
    std::string detokenize(llama_token token);

    bool processPromptBatch(const std::vector<llama_token>& tokens, int& nPast);
    bool processSingleToken(llama_token token, int nPast);
    llama_token sampleToken(llama_context* ctx, float temperature, float topP, int topK);

    void parseReActResponse(const std::string& response,
                            std::string& thought,
                            std::string& action,
                            std::string& actionInput,
                            std::string& finalAnswer);

    std::vector<std::string> parsePlanSteps(const std::string& planResponse);

    void notifyStateChange(AgentTokenCallback callback, AgentState state, int progress);
    void notifyToken(AgentTokenCallback callback, const std::string& token,
                     AgentState state, const std::string& eventType, int progress);

    static std::string buildToolsGrammar(const std::vector<ToolDef>& tools);
};

} // namespace agent_inference

#endif // AGENT_INFERENCE_H
