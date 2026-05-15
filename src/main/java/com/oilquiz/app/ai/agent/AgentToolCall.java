package com.oilquiz.app.ai.agent;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentToolCall {
    private String toolName;
    private Map<String, Object> parameters;
    private String reason;

    public AgentToolCall(String toolName, Map<String, Object> parameters, String reason) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.reason = reason;
    }

    public String getToolName() { return toolName; }
    public Map<String, Object> getParameters() { return parameters; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return "AgentToolCall{tool='" + toolName + "', reason='" + reason + "'}";
    }

    public static class ToolCallResult {
        private final String toolName;
        private final String result;
        private final boolean success;
        private final String error;

        private ToolCallResult(String toolName, String result, boolean success, String error) {
            this.toolName = toolName;
            this.result = result;
            this.success = success;
            this.error = error;
        }

        public static ToolCallResult success(String toolName, String result) {
            return new ToolCallResult(toolName, result, true, null);
        }

        public static ToolCallResult failure(String toolName, String error) {
            return new ToolCallResult(toolName, null, false, error);
        }

        public String getToolName() { return toolName; }
        public String getResult() { return result; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }

        public String toPromptString() {
            if (success) {
                return "【工具执行结果 - " + toolName + "】\n" + result;
            } else {
                return "【工具执行失败 - " + toolName + "】\n错误: " + error;
            }
        }
    }

    public static class ToolCallParser {
        private static final String TAG = "ToolCallParser";

        public static List<AgentToolCall> parseFromAIResponse(String aiResponse) {
            List<AgentToolCall> calls = new ArrayList<>();
            if (aiResponse == null || aiResponse.isEmpty()) return calls;

            String[] lines = aiResponse.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("```tool") || line.startsWith("[tool]") || line.startsWith("<tool>")) {
                    AgentToolCall call = parseToolCallBlock(lines, i);
                    if (call != null) {
                        calls.add(call);
                    }
                } else if (line.contains("call工具") || line.contains("使用工具") || line.contains("tool:")) {
                    AgentToolCall call = parseInlineToolCall(line);
                    if (call != null) {
                        calls.add(call);
                    }
                }
            }

            AILogger.d(TAG, "Parsed " + calls.size() + " tool calls from AI response");
            return calls;
        }

        private static AgentToolCall parseToolCallBlock(String[] lines, int startIndex) {
            StringBuilder content = new StringBuilder();
            int i = startIndex;
            while (i < lines.length) {
                String line = lines[i];
                if (line.contains("```") && i > startIndex) break;
                if (line.contains("]") && i > startIndex) break;
                if (line.contains(">") && i > startIndex) break;
                content.append(line).append("\n");
                i++;
            }

            String block = content.toString();
            String toolName = extractValue(block, "tool:", "name:");
            String reason = extractValue(block, "reason:", "调用原因");

            if (toolName == null || toolName.isEmpty()) {
                toolName = extractFirstWord(block);
            }

            if (toolName == null || toolName.isEmpty()) return null;

            Map<String, Object> params = parseParameters(block);
            return new AgentToolCall(toolName, params, reason != null ? reason : "获取信息");
        }

        private static AgentToolCall parseInlineToolCall(String line) {
            String toolName = null;
            String reason = null;

            if (line.contains("tool:")) {
                int start = line.indexOf("tool:") + 5;
                int end = Math.min(line.indexOf("\n", start), line.length());
                if (end > start) {
                    toolName = line.substring(start, end).trim();
                }
            } else if (line.contains("call工具")) {
                int start = line.indexOf("call工具") + "call工具".length();
                int end = Math.min(line.indexOf("\n", start), line.length());
                if (end > start) {
                    String after = line.substring(start, end).trim();
                    int spaceIdx = after.indexOf(" ");
                    if (spaceIdx > 0) {
                        toolName = after.substring(0, spaceIdx);
                        reason = after.substring(spaceIdx).trim();
                    } else {
                        toolName = after;
                    }
                }
            }

            if (toolName == null || toolName.isEmpty()) return null;

            return new AgentToolCall(toolName, new java.util.HashMap<>(), reason != null ? reason : "获取信息");
        }

        private static String extractValue(String text, String... keys) {
            for (String key : keys) {
                int idx = text.indexOf(key);
                if (idx >= 0) {
                    int start = idx + key.length();
                    int end = text.indexOf("\n", start);
                    if (end < 0) end = text.length();
                    return text.substring(start, end).trim();
                }
            }
            return null;
        }

        private static String extractFirstWord(String text) {
            if (text == null || text.isEmpty()) return null;
            text = text.replaceAll("[`\\[<\\]>\\n]", "").trim();
            int spaceIdx = text.indexOf(" ");
            if (spaceIdx > 0) return text.substring(0, spaceIdx).trim();
            return text.trim();
        }

        private static Map<String, Object> parseParameters(String block) {
            Map<String, Object> params = new java.util.HashMap<>();
            String[] lines = block.split("\n");
            for (String line : lines) {
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        if (!key.equals("tool") && !key.equals("name") && !key.equals("reason")) {
                            params.put(key, value);
                        }
                    }
                }
            }
            return params;
        }
    }
}
