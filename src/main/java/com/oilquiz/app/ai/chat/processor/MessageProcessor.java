package com.oilquiz.app.ai.chat.processor;

import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.refactor.CacheManager;
import com.oilquiz.app.ai.skill.SkillManager;

import java.util.List;
import java.util.Map;

/**
 * 消息处理引擎：路由、缓存检查、技能匹配、模型调用。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class MessageProcessor {

    private static final String TAG = "MessageProcessor";

    public interface Callback {
        void onCacheHit(String cachedResponse);
        void onSkillMatched(String skillPrompt);
        void onLocalModelCall(String prompt);
        void onOnlineModelCall(String prompt);
        void onToolExecution(String toolName, String params);
        void onEntertainmentRequest(String type);
        void onUnknownCommand(String command);
    }

    private final Callback callback;
    private AIService aiService;
    private InferenceRouter inferenceRouter;
    private CacheManager cacheManager;
    private SkillManager skillManager;

    // Tool prefix mapping
    private static final Map<String, String> TOOL_PREFIX_MAP = Map.of(
        "搜索", "network_search",
        "查找", "database_query",
        "天气", "weather",
        "位置", "location",
        "翻译", "translate",
        "文件", "file_read"
    );

    // Entertainment prefix mapping
    private static final Map<String, String> ENTERTAINMENT_MAP = Map.of(
        "笑话", "joke",
        "谜语", "riddle",
        "诗歌", "poem",
        "故事", "story",
        "名言", "quote"
    );

    public MessageProcessor(Callback callback) {
        this.callback = callback;
    }

    public void setServices(AIService aiService, InferenceRouter inferenceRouter,
                           CacheManager cacheManager, SkillManager skillManager) {
        this.aiService = aiService;
        this.inferenceRouter = inferenceRouter;
        this.cacheManager = cacheManager;
        this.skillManager = skillManager;
    }

    /**
     * 处理用户消息，返回处理结果
     */
    public ProcessResult processMessage(String userMessage) {
        ProcessResult result = new ProcessResult();

        // 1. Check for prefixed commands
        String commandResult = handlePrefixedCommand(userMessage);
        if (commandResult != null) {
            result.type = ProcessResult.Type.TOOL_COMMAND;
            result.handled = true;
            return result;
        }

        // 2. Check for entertainment requests
        String entertainmentType = checkEntertainmentRequest(userMessage);
        if (entertainmentType != null) {
            result.type = ProcessResult.Type.ENTERTAINMENT;
            result.entertainmentType = entertainmentType;
            result.handled = true;
            return result;
        }

        // 3. Check cache
        if (cacheManager != null) {
            String cached = cacheManager.getCachedResponse(userMessage);
            if (cached != null) {
                result.type = ProcessResult.Type.CACHED;
                result.cachedResponse = cached;
                result.handled = true;
                return result;
            }
        }

        // 4. Match skills
        if (skillManager != null) {
            List<SkillManager.Skill> matchedSkills = skillManager.matchSkills(userMessage);
            if (matchedSkills != null && !matchedSkills.isEmpty()) {
                SkillManager.Skill skill = matchedSkills.get(0);
                result.type = ProcessResult.Type.SKILL_MATCHED;
                result.enhancedPrompt = skill.name + ": " + skill.description;
                result.handled = true;
                return result;
            }
        }

        // 5. Route to appropriate model
        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            result.type = ProcessResult.Type.ONLINE_MODEL;
        } else {
            result.type = ProcessResult.Type.LOCAL_MODEL;
        }
        result.handled = true;
        return result;
    }

    /**
     * 处理带前缀的命令
     */
    private String handlePrefixedCommand(String message) {
        for (Map.Entry<String, String> entry : TOOL_PREFIX_MAP.entrySet()) {
            if (message.startsWith(entry.getKey())) {
                callback.onToolExecution(entry.getValue(), message);
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 检查是否是娱乐请求
     */
    private String checkEntertainmentRequest(String message) {
        for (Map.Entry<String, String> entry : ENTERTAINMENT_MAP.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 消息处理结果
     */
    public static class ProcessResult {
        public enum Type {
            TOOL_COMMAND,
            ENTERTAINMENT,
            CACHED,
            SKILL_MATCHED,
            LOCAL_MODEL,
            ONLINE_MODEL
        }

        public Type type;
        public boolean handled = false;
        public String cachedResponse;
        public String enhancedPrompt;
        public String entertainmentType;
    }
}
