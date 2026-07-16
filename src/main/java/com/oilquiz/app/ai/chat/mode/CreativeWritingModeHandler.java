package com.oilquiz.app.ai.chat.mode;

import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.service.AIService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CreativeWritingModeHandler implements ModeHandler {
    private static final String TAG = "CreativeWriter";
    
    private final AIService aiService;
    private final Set<String> activeMessageIds;
    private final ExecutorService executor;
    
    public CreativeWritingModeHandler(AIService aiService) {
        this.aiService = aiService;
        this.activeMessageIds = ConcurrentHashMap.newKeySet();
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    @Override
    public void handleMessage(ChatMessage userMessage, String messageId, ModeHandlerCallback callback) {
        if (userMessage == null || userMessage.content == null) {
            if (callback != null) {
                callback.onError(messageId, "Invalid message");
            }
            return;
        }
        
        activeMessageIds.add(messageId);
        
        ChatMessage initialAiMessage = createInitialAIMessage(messageId, userMessage);
        if (callback != null) {
            callback.onMessageCreated(messageId, initialAiMessage);
        }
        
        executor.submit(() -> {
            try {
                executeCreativeWriting(userMessage.content, messageId, callback);
            } catch (Exception e) {
                Log.e(TAG, "Creative writing error: " + messageId, e);
                if (callback != null && activeMessageIds.contains(messageId)) {
                    callback.onError(messageId, "创作失败: " + e.getMessage());
                }
            } finally {
                activeMessageIds.remove(messageId);
            }
        });
    }
    
    private void executeCreativeWriting(String message, String messageId, ModeHandlerCallback callback) throws Exception {
        long startTime = System.currentTimeMillis();
        int totalTokens = 0;
        
        Log.i(TAG, "Starting creative writing for: " + messageId);
        
        // 手动选择主题：从消息中提取用户指定的主题
        String theme = extractUserSpecifiedTheme(message);
        sendThinkingUpdate(callback, messageId, 1, "THEME", "主题识别", 
            "识别创作主题: " + theme, 20);
        if (checkCancelled(messageId)) return;
        
        sendThinkingUpdate(callback, messageId, 2, "OUTLINE", "大纲生成", 
            "正在生成创作大纲...", 40);
        
        // 生成大纲提示词
        String outlinePrompt = "请为以下创作任务生成大纲（列出3-6个部分）：\n" +
            "任务：" + message + "\n" +
            "主题：" + theme + "\n" +
            "请只输出大纲，每个部分占一行，以数字开头。";
        
        String outline = aiService.generateSync(outlinePrompt, 300);
        if (checkCancelled(messageId)) return;
        
        if (outline == null || outline.isEmpty()) {
            throw new Exception("无法生成大纲");
        }
        
        List<String> sections = parseOutline(outline);
        Log.i(TAG, "Outline generated: " + sections.size() + " sections for " + messageId);
        
        sendThinkingUpdate(callback, messageId, 3, "WRITE", "分段撰写", 
            "开始撰写，共 " + sections.size() + " 个部分", 50);
        
        StringBuilder fullContent = new StringBuilder();
        int[] tokenCounter = {0};
        
        for (int i = 0; i < sections.size(); i++) {
            if (checkCancelled(messageId)) return;
            
            int sectionProgress = 50 + ((i + 1) * 40 / sections.size());
            sendThinkingUpdate(callback, messageId, 3, "WRITE", "分段撰写", 
                "正在撰写第 " + (i + 1) + "/" + sections.size() + " 部分: " + sections.get(i), 
                sectionProgress);
            
            String sectionPrompt = "根据以下大纲和主题，撰写第" + (i + 1) + "部分内容：\n" +
                "主题：" + theme + "\n" +
                "当前部分：" + sections.get(i) + "\n" +
                "已有内容：" + (fullContent.length() > 200 ? fullContent.substring(fullContent.length() - 200) : fullContent.toString()) + "\n" +
                "请直接撰写这一部分的内容，保持与前文连贯。";
            
            final int sectionIndex = i;
            final StringBuilder sectionContent = new StringBuilder();
            
            try {
                aiService.generateStream(sectionPrompt, 400, new AIService.GenerateStreamCallback() {
                    @Override
                    public void onToken(String token) {
                        if (!activeMessageIds.contains(messageId)) {
                            return;
                        }
                        if (token != null) {
                            sectionContent.append(token);
                            tokenCounter[0]++;
                            if (callback != null) {
                                callback.onToken(messageId, token);
                            }
                        }
                    }
                    
                    @Override
                    public void onSuccess(String fullText) {
                    }
                    
                    @Override
                    public void onError(Exception error) {
                        Log.w(TAG, "Section " + (sectionIndex + 1) + " generation error: " + error.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Section " + (i + 1) + " failed, using placeholder", e);
                String placeholder = "\n\n## " + sections.get(i) + "\n\n[本部分生成中...]\n\n";
                sectionContent.append(placeholder);
            }
            
            if (sectionContent.length() > 0) {
                if (fullContent.length() > 0) {
                    fullContent.append("\n\n");
                }
                fullContent.append(sectionContent.toString());
            }
            
            sleep(300);
        }
        
        if (checkCancelled(messageId)) return;
        
        sendThinkingUpdate(callback, messageId, 4, "POLISH", "润色总结", 
            "正在润色和总结...", 95);
        
        String polishPrompt = "请对以下创作内容进行润色和优化，保持原意但让文字更流畅优美：\n\n" +
            fullContent.toString();
        
        final StringBuilder polishedContent = new StringBuilder();
        
        try {
            aiService.generateStream(polishPrompt, 1000, new AIService.GenerateStreamCallback() {
                @Override
                public void onToken(String token) {
                    if (!activeMessageIds.contains(messageId)) {
                        return;
                    }
                    if (token != null) {
                        polishedContent.append(token);
                        tokenCounter[0]++;
                    }
                }
                
                @Override
                public void onSuccess(String fullText) {
                }
                
                @Override
                public void onError(Exception error) {
                    Log.w(TAG, "Polish error: " + error.getMessage());
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Polish failed, using original content", e);
            polishedContent.append(fullContent);
        }
        
        if (checkCancelled(messageId)) return;
        
        long elapsed = System.currentTimeMillis() - startTime;
        float tps = elapsed > 0 ? (tokenCounter[0] * 1000.0f) / elapsed : 0;
        
        sendThinkingUpdate(callback, messageId, 5, "COMPLETE", "完成", 
            "创作完成！", 100);
        
        String finalContent = polishedContent.length() > 0 ? polishedContent.toString() : fullContent.toString();
        
        if (callback != null && activeMessageIds.contains(messageId)) {
            Object stats = createStats(tokenCounter[0], elapsed, tps);
            callback.onComplete(messageId, finalContent, stats);
        }
        
        Log.i(TAG, "Creative writing completed: " + messageId + ", tokens: " + tokenCounter[0] + ", time: " + elapsed + "ms");
    }
    
    private void sendThinkingUpdate(ModeHandlerCallback callback, String messageId,
                                     int stepNumber, String stepType, String title,
                                     String content, int progress) {
        if (callback != null && activeMessageIds.contains(messageId)) {
            StreamingEvent.ThinkingStepData stepData = new StreamingEvent.ThinkingStepData(
                stepNumber, stepType, title, content, progress
            );
            callback.onThinkingUpdate(messageId, stepData);
        }
    }
    
    private String extractUserSpecifiedTheme(String message) {
        // 用户可以通过"写一篇关于XXX的XXX"格式指定主题
        // 或者由用户在选择写作模式时指定
        
        // 尝试从消息中提取主题关键词
        String lower = message.toLowerCase();
        
        // 检查常见主题关键词
        String[] themes = {"科幻", "奇幻", "悬疑", "爱情", "历史", "武侠", "恐怖", "童话", "励志", "幽默"};
        for (String theme : themes) {
            if (lower.contains(theme)) {
                return theme;
            }
        }
        
        // 尝试匹配特定格式："写一篇XX" 或 "创作XX"
        String[] patterns = {
            "关于(.+?)的", "写一篇(.+?)的", "创作(.+?)的", 
            "写(.+?)小说", "写(.+?)故事", "写(.+?)文章"
        };
        
        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(message);
            if (m.find()) {
                return m.group(1);
            }
        }
        
        // 如果无法识别，默认为"通用"
        return "通用";
    }
    
    private List<String> parseOutline(String outline) {
        List<String> sections = new ArrayList<>();
        if (outline == null) return sections;
        
        String[] lines = outline.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            trimmed = trimmed.replaceAll("^\\d+[.、)）]\\s*", "");
            trimmed = trimmed.replaceAll("^[-•●○]\s*", "");
            if (!trimmed.isEmpty() && trimmed.length() > 2) {
                sections.add(trimmed);
            }
        }
        
        if (sections.isEmpty()) {
            sections.add("引言");
            sections.add("发展");
            sections.add("高潮");
            sections.add("结局");
        }
        
        if (sections.size() > 6) {
            sections = sections.subList(0, 6);
        }
        
        return sections;
    }
    
    private Object createStats(int tokens, long elapsedMs, float tps) {
        return new Object() {
            public int totalTokens = tokens;
            public long generationTimeMs = elapsedMs;
            public float tokensPerSecond = tps;
        };
    }
    
    private boolean checkCancelled(String messageId) {
        if (!activeMessageIds.contains(messageId)) {
            Log.i(TAG, "Message cancelled: " + messageId);
            return true;
        }
        return false;
    }
    
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public void cancel(String messageId) {
        if (messageId != null) {
            activeMessageIds.remove(messageId);
            Log.i(TAG, "Creative writing cancelled: " + messageId);
        }
    }
    
    @Override
    public String getModeName() {
        return "creative";
    }
    
    private ChatMessage createInitialAIMessage(String messageId, ChatMessage userMessage) {
        return new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(messageId)
            .parentId(userMessage.id)
            .content("")
            .status(ChatMessage.MessageStatus.GENERATING)
            .build();
    }
}
