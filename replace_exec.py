import sys

with open('D:/qzq/smartquiz/src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java', 'r', encoding='utf-8') as f:
    content = f.read()

# Find the executeNativeAgentLoop method boundaries
start_marker = 'private void executeNativeAgentLoop(String userMessage, int maxTokens) {'
start_idx = content.find(start_marker)
end_marker = 'private NativeGenerateResult nativeGenerateSyncWithThinking('
end_idx = content.find(end_marker, start_idx)
search_area = content[start_idx:end_idx]
last_brace = search_area.rfind('\n    }')
end_pos = start_idx + last_brace + 6

new_method = '''private void executeNativeAgentLoop(String userMessage, int maxTokens) {
        // \u590d\u7528\u5728\u7ebf\u6a21\u578b Agent \u7684\u5de5\u5177\u7cfb\u7edf\uff1a\u5de5\u5177\u5b9a\u4e49\u3001\u5de5\u5177\u6267\u884c\u90fd\u8d70 OnlineToolManager
        // C++ \u5c42\u901a\u8fc7 common_chat_parse \u89e3\u6790\u6a21\u578b\u539f\u751f FC \u8f93\u51fa\uff0c\u7ed3\u679c\u901a\u8fc7 [TOOL_CALLS] \u6807\u8bb0\u4f20\u7ed9 Java \u5c42
        // \u6a21\u578b\u60f3\u8f93\u51fa\u4ec0\u4e48\u5c31\u8f93\u51fa\u4ec0\u4e48\uff0c\u4e0d\u5f3a\u5236\u601d\u8003\u3001\u4e0d\u5f3a\u5236\u5de5\u5177\u8c03\u7528\u683c\u5f0f
        OnlineToolManager onlineToolMgr = new OnlineToolManager(activity);
        String toolsJson = onlineToolMgr.getToolDefinitions();
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Native FC: toolsJson len=" + toolsJson.length());

        // \u6784\u5efa\u5bf9\u8bdd\u5386\u53f2
        List<NativeChatMsg> history = new ArrayList<>();
        history.add(new NativeChatMsg("system", getDefaultSystemPrompt()));
        history.add(new NativeChatMsg("user", userMessage));

        for (int iteration = 1; iteration <= NATIVE_MAX_ITERATIONS; iteration++) {
            if (isCancelled.get()) { finishGeneration(); cleanupAfterCompletion(); return; }

            notifyStep("\u539f\u751f\u63a8\u7406", "\u7b2c " + iteration + " \u8f6e");
            AILogger.i(TAG, "Native FC iteration " + iteration);

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
                if (result != null && result.isSuccess()) anySuccess = true;
            }
        }

        // \u8fbe\u5230\u6700\u5927\u8fed\u4ee3\uff1a\u5f3a\u5236\u603b\u7ed3
        AILogger.w(TAG, "Native FC: reached max iterations, forcing final");
        String fallback = buildNativeFallback(userMessage, history);
        streamNativeAnswer(fallback);
        notifyComplete(fallback);
        finishGeneration();
        cleanupAfterCompletion();
    }'''

new_content = content[:start_idx] + new_method + content[end_pos:]

with open('D:/qzq/smartquiz/src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java', 'w', encoding='utf-8') as f:
    f.write(new_content)

print("executeNativeAgentLoop replaced successfully!")
