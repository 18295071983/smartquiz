#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Unify local agent tool calling with online agent's OpenAI format.
Changes:
1. executeNativeAgentLoop: Use OnlineToolManager instead of AIToolManager
2. nativeGenerateSync: Use onToolCalls/onReasoning callbacks, return NativeGenerateResult
3. Remove NativeToolCall class, use OnlineInferenceService.ToolCallInfo directly
4. Remove parseNativeToolCalls, buildNativeToolsJson, etc.
"""
import os, re

filepath = os.path.join(os.path.dirname(__file__), 'src', 'main', 'java', 'com', 'oilquiz', 'app', 'ai', 'agent', 'UnifiedAgentEngine.java')

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# ===== 1. Replace executeNativeAgentLoop method =====
old_execute = '''    private void executeNativeAgentLoop(String userMessage, int maxTokens) {
        AIToolManager toolManager = AIToolManager.getInstance(activity);
        String toolsJson = buildNativeToolsJson(toolManager);
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Native FC: tools=" + toolManager.getToolsMap().size() + ", schemaLen=" + toolsJson.length());'''

new_execute = '''    private void executeNativeAgentLoop(String userMessage, int maxTokens) {
        // \u590d\u7528\u5728\u7ebf\u6a21\u578b Agent \u7684\u5de5\u5177\u7cfb\u7edf\uff1a\u5de5\u5177\u5b9a\u4e49\u3001\u5de5\u5177\u6267\u884c\u90fd\u8d70 OnlineToolManager
        // C++ \u5c42\u901a\u8fc7 common_chat_parse \u89e3\u6790\u6a21\u578b\u539f\u751f FC \u8f93\u51fa\uff0c\u901a\u8fc7 JNI \u56de\u8c03\u76f4\u63a5\u4f20\u9012 ToolCallInfo
        // \u6a21\u578b\u60f3\u8f93\u51fa\u4ec0\u4e48\u5c31\u8f93\u51fa\u4ec0\u4e48\uff0c\u4e0d\u5f3a\u5236\u601d\u8003\u3001\u4e0d\u5f3a\u5236\u5de5\u5177\u8c03\u7528\u683c\u5f0f
        OnlineToolManager onlineToolMgr = new OnlineToolManager(activity);
        String toolsJson = onlineToolMgr.getToolDefinitions();
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Native FC: toolsJson len=" + toolsJson.length());'''

content = content.replace(old_execute, new_execute, 1)

# ===== 2. Replace nativeGenerateSync call and tool execution in executeNativeAgentLoop =====
old_gen_call = '''            // \u540c\u6b65\u8c03\u7528 generateWithTools
            String response = nativeGenerateSync(history, toolsJsonBytes, 500, 0.7f);

            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "Native FC: empty response at iteration " + iteration);
                break;
            }

            AILogger.i(TAG, "Native FC response len=" + response.length() + ": " + response.substring(0, Math.min(200, response.length())));

            // \u89e3\u6790\u5de5\u5177\u8c03\u7528
            List<NativeToolCall> toolCalls = parseNativeToolCalls(response);

            if (toolCalls.isEmpty()) {
                // \u65e0\u5de5\u5177\u8c03\u7528 = \u6700\u7ec8\u7b54\u6848
                String cleanAnswer = cleanNativeResponse(response);
                if (cleanAnswer.isEmpty()) cleanAnswer = response;
                contextSummary.add("\u52a9\u624b: " + truncateForContext(cleanAnswer));
                streamNativeAnswer(cleanAnswer);
                notifyComplete(cleanAnswer);
                finishGeneration();
                cleanupAfterCompletion();
                return;
            }

            // \u5c06\u6a21\u578b\u56de\u590d\u8ffd\u52a0\u5230\u5386\u53f2
            history.add(new NativeChatMsg("assistant", response));

            // \u6267\u884c\u6240\u6709\u5de5\u5177
            boolean anySuccess = false;
            for (NativeToolCall tc : toolCalls) {
                toolLoopCount.incrementAndGet();
                iterationCount.incrementAndGet();
                AILogger.i(TAG, "Native FC tool call: " + tc.name + " args=" + tc.argsStr);
                notifyToolCallStart(tc.name, tc.argsStr);
                notifyStep("\u5de5\u5177\u8c03\u7528", tc.name);

                // \u6784\u5efa\u53c2\u6570 Map
                Map<String, Object> params = jsonToMap(tc.argsJson);
                if (params == null) params = new HashMap<>();

                // \u6267\u884c\u5de5\u5177
                AgentService.ToolCall agentCall = new AgentService.ToolCall(tc.name, tc.argsStr);
                agentCall.resolvedArgs = params;
                AgentService.ToolResult result = executeToolWithTimeout(agentCall);

                String resultStr;
                if (result == null) {
                    resultStr = "\u5de5\u5177\u6267\u884c\u5931\u8d25: \u8fd4\u56de\u7a7a";
                    AILogger.w(TAG, "Native FC tool " + tc.name + " returned null");
                } else {
                    resultStr = result.result != null ? result.result : "\u5de5\u5177\u6267\u884c\u5931\u8d25: " + result.errorMessage;
                    if (resultStr.length() > NATIVE_MAX_TOOL_RESULT_LEN) {
                        resultStr = resultStr.substring(0, NATIVE_MAX_TOOL_RESULT_LEN) + "\u2026(\u622a\u65ad)";
                    }
                }

                AILogger.i(TAG, "Native FC tool " + tc.name + " result: " + resultStr.substring(0, Math.min(200, resultStr.length())));
                contextSummary.add("\u5de5\u5177\u7ed3\u679c[" + tc.name + "]: " + truncateForContext(resultStr, 500));

                // \u901a\u77e5 UI
                final String toolName = tc.name;
                final String toolResultStr = resultStr;
                final boolean toolSuccess = result != null && result.success;
                activity.runOnUiThread(() -> {
                    if (callback != null && isValid()) {
                        callback.onToolCallComplete(toolName,
                            OnlineToolResult.failure(null, toolName, toolSuccess ? "\u6210\u529f" : toolResultStr, 0));
                    }
                });

                // \u5de5\u5177\u7ed3\u679c\u4ee5 tool role \u8ffd\u52a0\u5230\u5386\u53f2
                history.add(new NativeChatMsg("tool", resultStr));
                if (result != null && result.success) anySuccess = true;'''

new_gen_call = '''            // \u540c\u6b65\u8c03\u7528 generateWithTools\uff0c\u83b7\u53d6 ToolCallInfo\uff08\u4e0e\u5728\u7ebf Agent \u76f8\u540c\u683c\u5f0f\uff09
            NativeGenerateResult genResult = nativeGenerateSyncWithThinking(
                    history, toolsJsonBytes, maxTokens, 0.6f, true);

            if (genResult == null || genResult.content == null || genResult.content.trim().isEmpty()) {
                AILogger.w(TAG, "Native FC: empty response at iteration " + iteration);
                break;
            }

            String response = genResult.content;
            AILogger.i(TAG, "Native FC response len=" + response.length()
                    + " toolCalls=" + (genResult.toolCalls != null ? genResult.toolCalls.size() : 0)
                    + ": " + response.substring(0, Math.min(200, response.length())));

            // \u4f18\u5148\u4f7f\u7528 C++ \u5c42 common_chat_parse \u89e3\u6790\u7684\u5de5\u5177\u8c03\u7528\uff0c\u5982\u679c\u6ca1\u6709\u5219\u56de\u9000\u5230\u6587\u672c\u89e3\u6790
            List<NativeToolCall> toolCalls = genResult.toolCalls;
            if (toolCalls == null || toolCalls.isEmpty()) {
                toolCalls = parseNativeToolCalls(response);
            }

            if (toolCalls.isEmpty()) {
                // \u65e0\u5de5\u5177\u8c03\u7528 = \u6700\u7ec8\u7b54\u6848
                String cleanAnswer = cleanNativeResponse(response);
                if (cleanAnswer.isEmpty()) cleanAnswer = response;
                contextSummary.add("\u52a9\u624b: " + truncateForContext(cleanAnswer));
                streamNativeAnswer(cleanAnswer);
                notifyComplete(cleanAnswer);
                finishGeneration();
                cleanupAfterCompletion();
                return;
            }

            // \u5c06\u6a21\u578b\u56de\u590d\u8ffd\u52a0\u5230\u5386\u53f2
            history.add(new NativeChatMsg("assistant", response));

            // \u6267\u884c\u6240\u6709\u5de5\u5177\uff08\u590d\u7528 OnlineToolManager\uff09
            boolean anySuccess = false;
            for (NativeToolCall tc : toolCalls) {
                toolLoopCount.incrementAndGet();
                iterationCount.incrementAndGet();
                AILogger.i(TAG, "Native FC tool call: " + tc.name + " args=" + tc.argsStr);
                notifyToolCallStart(tc.name, tc.argsStr);
                notifyStep("\u5de5\u5177\u8c03\u7528", tc.name);

                // \u4f7f\u7528 OnlineToolManager \u6267\u884c\u5de5\u5177
                OnlineToolResult result = onlineToolMgr.executeTool(tc.id != null ? tc.id : "", tc.name, tc.argsStr);

                String resultStr;
                if (result == null) {
                    resultStr = "\u5de5\u5177\u6267\u884c\u5931\u8d25: \u8fd4\u56de\u7a7a";
                    AILogger.w(TAG, "Native FC tool " + tc.name + " returned null");
                } else {
                    resultStr = result.getResult() != null ? result.getResult() : "\u5de5\u5177\u6267\u884c\u5931\u8d25: " + result.getErrorMessage();
                    if (resultStr.length() > NATIVE_MAX_TOOL_RESULT_LEN) {
                        resultStr = resultStr.substring(0, NATIVE_MAX_TOOL_RESULT_LEN) + "\u2026(\u622a\u65ad)";
                    }
                }

                AILogger.i(TAG, "Native FC tool " + tc.name + " result: " + resultStr.substring(0, Math.min(200, resultStr.length())));
                contextSummary.add("\u5de5\u5177\u7ed3\u679c[" + tc.name + "]: " + truncateForContext(resultStr, 500));

                // \u901a\u77e5 UI
                final String toolName = tc.name;
                final String toolResultStr = resultStr;
                final boolean toolSuccess = result != null && result.isSuccess();
                activity.runOnUiThread(() -> {
                    if (callback != null && isValid()) {
                        callback.onToolCallComplete(toolName,
                            OnlineToolResult.failure(null, toolName, toolSuccess ? "\u6210\u529f" : toolResultStr, 0));
                    }
                });

                // \u5de5\u5177\u7ed3\u679c\u4ee5 tool role \u8ffd\u52a0\u5230\u5386\u53f2
                history.add(new NativeChatMsg("tool", resultStr));
                if (result != null && result.isSuccess()) anySuccess = true;'''

content = content.replace(old_gen_call, new_gen_call, 1)

# ===== 3. Replace nativeGenerateSync with nativeGenerateSyncWithThinking =====
# Find the old nativeGenerateSync method and replace it with the new one
old_native_gen = re.search(
    r'    private String nativeGenerateSync\(List<NativeChatMsg> history, byte\[\] toolsJsonBytes,\s+int maxTokens, float temperature\) \{.*?return result\.toString\(\);\s+\}',
    content, re.DOTALL)

if old_native_gen:
    new_native_gen = '''    /**
     * \u540c\u6b65\u5305\u88c5 generateWithTools\uff08\u542f\u7528\u601d\u8003\u6a21\u5f0f\uff09\uff1a
     * \u5206\u79bb\u601d\u8003\u5185\u5bb9\u548c\u56de\u7b54\u5185\u5bb9\uff0c\u601d\u8003\u8fc7\u7a0b\u5b9e\u65f6\u6d41\u5f0f\u5c55\u793a\u7ed9 UI\u3002
     * C++ \u5c42\u901a\u8fc7 JNI \u56de\u8c03\u76f4\u63a5\u4f20\u9012 onToolCalls/onReasoning\uff0c
     * \u4e0e\u5728\u7ebf Agent \u4f7f\u7528\u76f8\u540c\u7684 ToolCallInfo \u683c\u5f0f\uff0c\u5de5\u5177\u8c03\u7528\u4e92\u901a\u3002
     */
    private NativeGenerateResult nativeGenerateSyncWithThinking(
            List<NativeChatMsg> history, byte[] toolsJsonBytes,
            int maxTokens, float temperature, boolean enableThinking) {

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullResult = new StringBuilder();
        final String[] errorHolder = {null};
        // C++ \u5c42 common_chat_parse \u89e3\u6790\u7ed3\u679c\uff0c\u901a\u8fc7 JNI \u56de\u8c03\u76f4\u63a5\u4f20\u9012
        final List<OnlineInferenceService.ToolCallInfo> toolCallsHolder = new ArrayList<>();
        final String[] reasoningHolder = {""};

        int size = history.size();
        String[] roles = new String[size];
        byte[][] contents = new byte[size][];
        for (int i = 0; i < size; i++) {
            roles[i] = history.get(i).role;
            contents[i] = history.get(i).content.getBytes(StandardCharsets.UTF_8);
        }

        try {
            LlamaHelper.generateWithTools(roles, contents, toolsJsonBytes, maxTokens, temperature,
                    0.9f, 40, enableThinking, new LlamaHelper.TokenCallback() {
                        @Override
                        public void onToken(String token) {
                            if (token == null || token.isEmpty()) return;
                            fullResult.append(token);
                            activity.runOnUiThread(() -> {
                                if (callback != null) callback.onToken(token);
                            });
                        }

                        @Override
                        public void onToolCalls(List<OnlineInferenceService.ToolCallInfo> toolCalls) {
                            if (toolCalls != null && !toolCalls.isEmpty()) {
                                toolCallsHolder.addAll(toolCalls);
                                AILogger.i(TAG, "Received " + toolCalls.size() + " tool calls from C++ layer");
                            }
                        }

                        @Override
                        public void onReasoning(String reasoning) {
                            if (reasoning != null && !reasoning.isEmpty()) {
                                reasoningHolder[0] = reasoning;
                                AILogger.i(TAG, "Received reasoning (" + reasoning.length() + " chars) from C++ layer");
                            }
                        }

                        @Override
                        public void onComplete(String fullText) {
                            if (fullText != null && !fullText.isEmpty()) {
                                synchronized (fullResult) {
                                    fullResult.setLength(0);
                                    fullResult.append(fullText);
                                }
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            errorHolder[0] = error;
                            latch.countDown();
                        }
                    });

            boolean done = latch.await(NATIVE_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                AILogger.e(TAG, "nativeGenerateSyncWithThinking timeout");
                return null;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "nativeGenerateSyncWithThinking error: " + e.getMessage());
            return null;
        }

        if (errorHolder[0] != null) {
            AILogger.e(TAG, "nativeGenerateSyncWithThinking callback error: " + errorHolder[0]);
            return null;
        }

        // \u4ece\u5b8c\u6574\u6587\u672c\u4e2d\u5206\u79bb\u601d\u8003\u548c\u6b63\u6587
        String fullText = fullResult.toString().trim();
        String thinking = reasoningHolder[0];
        String content = fullText;

        // \u5982\u679c C++ \u5c42\u6ca1\u6709\u89e3\u6790\u5230\u63a8\u7406\u5185\u5bb9\uff0c\u68c0\u67e5\u6a21\u578b\u662f\u5426\u81ea\u5df1\u8f93\u51fa\u4e86 <think>...</think> \u6807\u7b7e
        if (thinking.isEmpty()) {
            String[] split = splitThinkingAndContent(content);
            content = split[0];
            thinking = split[1];
        } else {
            String[] split = splitThinkingAndContent(content);
            content = split[0];
        }

        // \u5c06 ToolCallInfo \u8f6c\u6362\u4e3a NativeToolCall\uff08\u4fdd\u6301\u5185\u90e8\u63a5\u53e3\u517c\u5bb9\uff09
        List<NativeToolCall> nativeToolCalls = new ArrayList<>();
        if (!toolCallsHolder.isEmpty()) {
            for (OnlineInferenceService.ToolCallInfo tc : toolCallsHolder) {
                String argsStr = tc.arguments != null ? tc.arguments : "{}";
                JSONObject argsJson;
                try {
                    argsJson = new JSONObject(argsStr);
                } catch (Exception e) {
                    argsJson = new JSONObject();
                    argsStr = "{}";
                }
                nativeToolCalls.add(new NativeToolCall(tc.id, tc.name, argsStr, argsJson));
            }
        }

        AILogger.i(TAG, "nativeGenerateSyncWithThinking result: contentLen=" + content.length()
                + " thinkingLen=" + thinking.length()
                + " toolCalls=" + nativeToolCalls.size());

        return new NativeGenerateResult(content, thinking, nativeToolCalls);
    }'''

    content = content[:old_native_gen.start()] + new_native_gen + content[old_native_gen.end():]
    print("Replaced nativeGenerateSync with nativeGenerateSyncWithThinking")
else:
    print("ERROR: Could not find nativeGenerateSync method")

# ===== 4. Add NativeGenerateResult class and update NativeToolCall with id field =====
# Check if NativeGenerateResult already exists
if 'NativeGenerateResult' not in content:
    # Add before the closing brace of the class
    # Find the NativeToolCall class and add NativeGenerateResult after it
    native_tool_call_pattern = r'(    private static class NativeToolCall \{[^}]+\})'
    match = re.search(native_tool_call_pattern, content)
    if match:
        old_ntc = match.group(1)
        new_ntc = '''    private static class NativeToolCall {
        final String id;
        final String name;
        final String argsStr;
        final JSONObject argsJson;
        NativeToolCall(String name, String argsStr, JSONObject argsJson) {
            this.id = null;
            this.name = name;
            this.argsStr = argsStr;
            this.argsJson = argsJson;
        }
        NativeToolCall(String id, String name, String argsStr, JSONObject argsJson) {
            this.id = id;
            this.name = name;
            this.argsStr = argsStr;
            this.argsJson = argsJson;
        }
    }

    /**
     * \u539f\u751f\u63a8\u7406\u7ed3\u679c\uff1a\u5206\u79bb\u601d\u8003\u5185\u5bb9\u548c\u56de\u7b54\u5185\u5bb9
     */
    private static class NativeGenerateResult {
        String content;
        String thinking;
        List<NativeToolCall> toolCalls;

        NativeGenerateResult(String content, String thinking) {
            this.content = content;
            this.thinking = thinking;
            this.toolCalls = null;
        }

        NativeGenerateResult(String content, String thinking, List<NativeToolCall> toolCalls) {
            this.content = content;
            this.thinking = thinking;
            this.toolCalls = toolCalls;
        }
    }'''
        content = content[:match.start()] + new_ntc + content[match.end():]
        print("Added NativeGenerateResult and updated NativeToolCall")
    else:
        print("WARNING: Could not find NativeToolCall class")

# ===== 5. Add import for OnlineToolManager and OnlineInferenceService =====
if 'import com.oilquiz.app.ai.agent.online.OnlineToolManager;' not in content:
    content = content.replace(
        'import com.oilquiz.app.ai.agent.online.OnlineToolResult;',
        'import com.oilquiz.app.ai.agent.online.OnlineToolManager;\nimport com.oilquiz.app.ai.agent.online.OnlineToolResult;'
    )
if 'import com.oilquiz.app.ai.service.OnlineInferenceService;' not in content:
    content = content.replace(
        'import com.oilquiz.app.ai.service.AIService;',
        'import com.oilquiz.app.ai.service.AIService;\nimport com.oilquiz.app.ai.service.OnlineInferenceService;'
    )

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)

print("SUCCESS: All changes applied to UnifiedAgentEngine.java")
