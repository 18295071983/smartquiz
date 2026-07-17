package com.oilquiz.app.ai.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PromptBuilder {

    public record Message(String role, String content) {}

    public static class PromptRequest {
        private String systemPrompt;
        private String globalPrompt;
        private String normalPrompt;
        private String thinkingInstruction;
        private List<Message> history;
        private String userQuery;
        private int maxHistoryPairs = 8;
        private final Map<String, String> variables = new HashMap<>();

        public PromptRequest system(String prompt) {
            this.systemPrompt = prompt;
            return this;
        }

        public PromptRequest global(String prompt) {
            this.globalPrompt = prompt;
            return this;
        }

        public PromptRequest normal(String prompt) {
            this.normalPrompt = prompt;
            return this;
        }

        public PromptRequest thinking(String instruction) {
            this.thinkingInstruction = instruction;
            return this;
        }

        public PromptRequest history(List<Message> messages) {
            this.history = messages;
            return this;
        }

        public PromptRequest query(String query) {
            this.userQuery = query;
            return this;
        }

        public PromptRequest maxHistoryPairs(int pairs) {
            this.maxHistoryPairs = pairs;
            return this;
        }

        public PromptRequest variable(String key, String value) {
            this.variables.put(key, value);
            return this;
        }

        public String build() {
            StringBuilder sb = new StringBuilder();

            if (globalPrompt != null && !globalPrompt.isBlank()) {
                sb.append("<|im_start|>global\n").append(resolveVariables(globalPrompt)).append("\n<|im_end|>\n");
            }

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                String sysContent = resolveVariables(systemPrompt);
                if (thinkingInstruction != null && !thinkingInstruction.isBlank()) {
                    sysContent = "[思考指令]\n" + resolveVariables(thinkingInstruction) + "\n\n" + sysContent;
                }
                sb.append("<|im_start|>system\n").append(sysContent).append("\n<|im_end|>\n");
            } else if (thinkingInstruction != null && !thinkingInstruction.isBlank()) {
                sb.append("<|im_start|>system\n[思考指令]\n").append(resolveVariables(thinkingInstruction)).append("\n<|im_end|>\n");
            }

            if (normalPrompt != null && !normalPrompt.isBlank()) {
                sb.append("<|im_start|>normal\n").append(resolveVariables(normalPrompt)).append("\n<|im_end|>\n");
            }

            List<Message> effectiveHistory = history != null ? history : new ArrayList<>();
            List<Message> truncated = truncateHistory(effectiveHistory, maxHistoryPairs);
            for (Message msg : truncated) {
                String role = msg.role();
                sb.append("<|im_start|>").append(role).append("\n").append(msg.content()).append("\n<|im_end|>\n");
            }

            if (userQuery != null && !userQuery.isBlank()) {
                sb.append("<|im_start|>user\n").append(resolveVariables(userQuery)).append("\n<|im_end|>\n");
            }

            sb.append("<|im_start|>assistant\n");
            return sb.toString();
        }

        /**
         * 只组装消息列表，不进行格式化。
         * 配合 native 层的 generateStreamFromMessages 使用，由 llama_chat_apply_template 自动适配模型格式。
         * 将 global+system+thinking+normal 合并为一条 system 消息（与 chatSend 路径的 mergeSystemPrompts 行为一致）。
         */
        public List<Message> buildMessages() {
            List<Message> messages = new ArrayList<>();

            // 合并 global+system+thinking+normal 为一条 system 消息
            StringBuilder sysContent = new StringBuilder();
            if (globalPrompt != null && !globalPrompt.isBlank()) {
                sysContent.append(resolveVariables(globalPrompt)).append("\n");
            }
            if (thinkingInstruction != null && !thinkingInstruction.isBlank()) {
                sysContent.append("[思考指令]\n").append(resolveVariables(thinkingInstruction)).append("\n\n");
            }
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                sysContent.append(resolveVariables(systemPrompt)).append("\n");
            }
            if (normalPrompt != null && !normalPrompt.isBlank()) {
                sysContent.append(resolveVariables(normalPrompt));
            }
            if (sysContent.length() > 0) {
                // 去掉末尾多余的换行
                String sysStr = sysContent.toString().trim();
                if (!sysStr.isEmpty()) {
                    messages.add(new Message("system", sysStr));
                }
            }

            // 历史消息
            List<Message> effectiveHistory = history != null ? history : new ArrayList<>();
            List<Message> truncated = truncateHistory(effectiveHistory, maxHistoryPairs);
            for (Message msg : truncated) {
                messages.add(new Message(msg.role(), msg.content()));
            }

            // 用户查询
            if (userQuery != null && !userQuery.isBlank()) {
                messages.add(new Message("user", resolveVariables(userQuery)));
            }

            return messages;
        }


        private String resolveVariables(String text) {
            if (text == null) return null;
            String result = text;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return result;
        }
    }

    public static String build(String system, List<Message> history, String userQuery) {
        return new PromptRequest().system(system).history(history).query(userQuery).build();
    }

    public static String buildSimple(String system, String userQuery) {
        return build(system, new ArrayList<>(), userQuery);
    }

    public static String buildForNativeLib(String globalPrompt, String systemPrompt, String normalPrompt, List<Message> history, String userQuery) {
        return new PromptRequest()
                .global(globalPrompt)
                .system(systemPrompt)
                .normal(normalPrompt)
                .history(history)
                .query(userQuery)
                .build();
    }

    public static List<Message> createHistoryFromAlternating(List<String> userMessages, List<String> assistantMessages) {
        List<Message> history = new ArrayList<>();
        if (userMessages == null || assistantMessages == null) return history;
        int minSize = Math.min(userMessages.size(), assistantMessages.size());
        for (int i = 0; i < minSize; i++) {
            history.add(new Message("user", userMessages.get(i)));
            history.add(new Message("assistant", assistantMessages.get(i)));
        }
        if (userMessages.size() > assistantMessages.size()) {
            for (int i = assistantMessages.size(); i < userMessages.size(); i++) {
                history.add(new Message("user", userMessages.get(i)));
            }
        }
        return history;
    }

    public static List<Message> truncateHistory(List<Message> history, int maxPairs) {
        if (history == null) {
            return new ArrayList<>();
        }
        
        int adjustedMaxPairs = adjustMaxHistoryByMemory(maxPairs);
        
        if (history.size() <= adjustedMaxPairs * 2) {
            return new ArrayList<>(history);
        }
        
        int startIndex = history.size() - adjustedMaxPairs * 2;
        List<Message> truncated = new ArrayList<>(history.subList(startIndex, history.size()));
        
        if (adjustedMaxPairs != maxPairs) {
            com.oilquiz.app.util.AILogger.i("PromptBuilder", 
                "History truncated due to memory: " + history.size() + " -> " + truncated.size() + 
                " messages (maxPairs: " + maxPairs + " -> " + adjustedMaxPairs + ")");
        }
        
        return truncated;
    }
    
    private static int adjustMaxHistoryByMemory(int baseMaxPairs) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long freeMB = runtime.freeMemory() / (1024 * 1024);
            long totalMB = runtime.totalMemory() / (1024 * 1024);
            float availableRatio = (float) freeMB / totalMB;
            
            if (availableRatio > 0.5f) {
                return baseMaxPairs;
            } else if (availableRatio > 0.35f) {
                return Math.max(4, baseMaxPairs - 2);
            } else if (availableRatio > 0.25f) {
                return Math.max(2, baseMaxPairs - 4);
            } else {
                return 1;
            }
        } catch (Exception e) {
            return baseMaxPairs;
        }
    }
}
