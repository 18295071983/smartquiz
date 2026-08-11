import sys

with open('D:/qzq/smartquiz/src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java', 'r', encoding='utf-8') as f:
    content = f.read()

# Find the section after "return new NativeGenerateResult" in nativeGenerateSyncWithThinking
# We need to find the section that starts with "// 从完整文本中分离思考和正文"
# and ends with "return new NativeGenerateResult(content, thinking);"

# Find the start of the section
marker_start = '        // \u4ece\u5b8c\u6574\u6587\u672c\u4e2d\u5206\u79bb\u601d\u8003\u548c\u6b63\u6587'
idx_start = content.find(marker_start)

# Find the return statement
marker_end = '        return new NativeGenerateResult(content, thinking);\n    }'
idx_end = content.find(marker_end, idx_start)

if idx_start < 0 or idx_end < 0:
    print(f"ERROR: Could not find markers. start={idx_start}, end={idx_end}")
    sys.exit(1)

print(f"Found section from {idx_start} to {idx_end + len(marker_end)}")

new_section = '''        // \u4ece\u5b8c\u6574\u6587\u672c\u4e2d\u5206\u79bb\u601d\u8003\u548c\u6b63\u6587
        String fullText = fullResult.toString().trim();
        String thinking = "";
        String content = fullText;

        // \u68c0\u6d4b C++ \u5c42 common_chat_parse \u4f20\u6765\u7684 [TOOL_CALLS] \u6807\u8bb0
        List<NativeToolCall> toolCalls = null;
        String toolCallsMarker = "[TOOL_CALLS]";
        String toolCallsEndMarker = "[/TOOL_CALLS]";
        int tcStart = fullText.indexOf(toolCallsMarker);
        int tcEnd = fullText.indexOf(toolCallsEndMarker);
        if (tcStart >= 0 && tcEnd > tcStart) {
            String toolCallsJson = fullText.substring(tcStart + toolCallsMarker.length(), tcEnd);
            try {
                JSONArray arr = new JSONArray(toolCallsJson);
                toolCalls = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("id", null);
                    String name = obj.getString("name");
                    String argsStr = obj.optString("arguments", "{}");
                    JSONObject argsJson = new JSONObject();
                    try { argsJson = new JSONObject(argsStr); } catch (Exception ignored) {}
                    toolCalls.add(new NativeToolCall(id, name, argsStr, argsJson));
                }
                AILogger.i(TAG, "Parsed " + toolCalls.size() + " tool calls from C++ common_chat_parse");
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to parse [TOOL_CALLS]: " + e.getMessage());
            }
            // \u4ece\u6b63\u6587\u4e2d\u53bb\u9664 [TOOL_CALLS] \u6807\u8bb0
            content = (fullText.substring(0, tcStart) + fullText.substring(tcEnd + toolCallsEndMarker.length())).trim();
        }

        // \u68c0\u6d4b C++ \u5c42\u4f20\u6765\u7684 [REASONING] \u6807\u8bb0
        String reasoningMarker = "[REASONING]";
        String reasoningEndMarker = "[/REASONING]";
        int rStart = content.indexOf(reasoningMarker);
        int rEnd = content.indexOf(reasoningEndMarker);
        if (rStart >= 0 && rEnd > rStart) {
            thinking = content.substring(rStart + reasoningMarker.length(), rEnd).trim();
            content = (content.substring(0, rStart) + content.substring(rEnd + reasoningEndMarker.length())).trim();
        }

        // \u4e5f\u68c0\u6d4b\u6a21\u578b\u81ea\u5df1\u8f93\u51fa\u7684 lais...