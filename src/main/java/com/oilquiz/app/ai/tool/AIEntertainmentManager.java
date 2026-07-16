package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;

import java.util.concurrent.CompletableFuture;

public class AIEntertainmentManager {

    private static final String TAG = "AIEntertainmentManager";
    private final Context context;
    private final AIService aiService;

    public AIEntertainmentManager(Context context) {
        this.context = context;
        this.aiService = AIService.getInstance(context);
    }

    // 娱乐功能类型
    public static class EntertainmentType {
        public static final String JOKE = "joke";
        public static final String RIDDLE = "riddle";
        public static final String POEM = "poem";
        public static final String STORY = "story";
        public static final String TRIVIA = "trivia";
        public static final String QUOTE = "quote";
        public static final String GAME = "game";
    }

    // 执行娱乐功能
    public CompletableFuture<String> executeEntertainment(String type, String parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (type == null || type.trim().isEmpty()) {
                    Log.e(TAG, "Entertainment type is null or empty");
                    return "错误: 娱乐类型不能为空";
                }
                
                // 确保 parameters 不为 null
                String params = parameters != null ? parameters : "";
                
                switch (type) {
                    case EntertainmentType.JOKE:
                        return tellJoke(params);
                    case EntertainmentType.RIDDLE:
                        return tellRiddle(params);
                    case EntertainmentType.POEM:
                        return writePoem(params);
                    case EntertainmentType.STORY:
                        return tellStory(params);
                    case EntertainmentType.TRIVIA:
                        return askTrivia(params);
                    case EntertainmentType.QUOTE:
                        return getQuote(params);
                    case EntertainmentType.GAME:
                        return playGame(params);
                    default:
                        return "错误: 未知的娱乐类型: " + type;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing entertainment: " + type, e);
                return "错误: 执行娱乐功能时出错: " + e.getMessage();
            }
        });
    }

    // 讲笑话
    private String tellJoke(String parameters) {
        // 解析参数: topic (可选)
        // 格式: "topic: 石油工程"
        String topic = "";
        if (parameters != null && !parameters.isEmpty() && parameters.contains("topic:")) {
            topic = parameters.split("topic:")[1].trim();
        }

        String prompt;
        if (!topic.isEmpty()) {
            prompt = "Tell a funny joke about " + topic + ". Make it appropriate and clean.";
        } else {
            prompt = "Tell a funny joke. Make it appropriate and clean.";
        }

        try {
            String result = aiService.generateSync(prompt, 500);
            return "笑话:\n" + result;
        } catch (Exception e) {
            return "讲笑话时出错: " + e.getMessage();
        }
    }

    // 猜谜语
    private String tellRiddle(String parameters) {
        // 解析参数: topic (可选)
        // 格式: "topic: 石油"
        String topic = "";
        if (parameters != null && !parameters.isEmpty() && parameters.contains("topic:")) {
            topic = parameters.split("topic:")[1].trim();
        }

        String prompt;
        if (!topic.isEmpty()) {
            prompt = "Tell a riddle about " + topic + ". Provide the answer at the end.";
        } else {
            prompt = "Tell a riddle. Provide the answer at the end.";
        }

        try {
            String result = aiService.generateSync(prompt, 500);
            return "谜语:\n" + result;
        } catch (Exception e) {
            return "讲谜语时出错: " + e.getMessage();
        }
    }

    // 写诗
    private String writePoem(String parameters) {
        // 解析参数: topic, style (可选)
        // 格式: "topic: 石油, style: 抒情"
        String topic = "自然";
        String style = "自由诗";

        if (parameters != null && !parameters.isEmpty()) {
            if (parameters.contains("topic:")) {
                topic = parameters.split("topic:")[1].split(",")[0].trim();
            }
            if (parameters.contains("style:")) {
                style = parameters.split("style:")[1].trim();
            }
        }

        String prompt = "Write a poem about " + topic + " in " + style + " style. Make it beautiful and meaningful.";

        try {
            String result = aiService.generateSync(prompt, 800);
            return "诗歌:\n" + result;
        } catch (Exception e) {
            return "写诗时出错: " + e.getMessage();
        }
    }

    // 讲故事
    private String tellStory(String parameters) {
        // 解析参数: topic, length (可选)
        // 格式: "topic: 石油工人, length: 短"
        String topic = "冒险";
        String length = "中等";

        if (parameters != null && !parameters.isEmpty()) {
            if (parameters.contains("topic:")) {
                topic = parameters.split("topic:")[1].split(",")[0].trim();
            }
            if (parameters.contains("length:")) {
                length = parameters.split("length:")[1].trim();
            }
        }

        String prompt = "Tell a story about " + topic + ". Make it " + length + " length, interesting and engaging.";

        try {
            String result = aiService.generateSync(prompt, 1000);
            return "故事:\n" + result;
        } catch (Exception e) {
            return "讲故事时出错: " + e.getMessage();
        }
    }

    //  trivia 知识问答
    private String askTrivia(String parameters) {
        // 解析参数: topic (可选)
        // 格式: "topic: 石油"
        String topic = "科学";
        if (parameters != null && !parameters.isEmpty() && parameters.contains("topic:")) {
            topic = parameters.split("topic:")[1].trim();
        }

        String prompt = "Ask a trivia question about " + topic + ". Provide the answer at the end.";

        try {
            String result = aiService.generateSync(prompt, 500);
            return "知识问答:\n" + result;
        } catch (Exception e) {
            return "提问时出错: " + e.getMessage();
        }
    }

    // 名言警句
    private String getQuote(String parameters) {
        // 解析参数: topic (可选)
        // 格式: "topic: 学习"
        String topic = "生活";
        if (parameters != null && !parameters.isEmpty() && parameters.contains("topic:")) {
            topic = parameters.split("topic:")[1].trim();
        }

        String prompt = "Provide a famous quote about " + topic + ". Include the author if known.";

        try {
            String result = aiService.generateSync(prompt, 300);
            return "名言警句:\n" + result;
        } catch (Exception e) {
            return "获取名言时出错: " + e.getMessage();
        }
    }

    // 游戏
    private String playGame(String parameters) {
        // 解析参数: type (可选)
        // 格式: "type: 猜数字"
        String gameType = "猜数字";
        if (parameters != null && !parameters.isEmpty() && parameters.contains("type:")) {
            gameType = parameters.split("type:")[1].trim();
        }

        String prompt;
        switch (gameType) {
            case "猜数字":
                prompt = "Let's play a number guessing game. Think of a number between 1 and 100, and I'll try to guess it. Start by saying 'I'm ready' when you have a number in mind.";
                break;
            case "20问":
                prompt = "Let's play 20 Questions. Think of something, and I'll ask up to 20 yes/no questions to guess what it is. Start by saying 'I'm ready' when you have something in mind.";
                break;
            case "成语接龙":
                prompt = "Let's play Chinese idiom chain game. I'll start with an idiom, and you respond with another idiom that starts with the last character of mine. Let's begin: 一心一意";
                break;
            default:
                prompt = "Let's play a game. I can play number guessing, 20 Questions, or Chinese idiom chain. Which game would you like to play?";
        }

        try {
            String result = aiService.generateSync(prompt, 500);
            return "游戏:\n" + result;
        } catch (Exception e) {
            return "游戏时出错: " + e.getMessage();
        }
    }

    // 获取可用娱乐功能列表
    public String getAvailableEntertainment() {
        StringBuilder entertainment = new StringBuilder();
        entertainment.append("可用娱乐功能:\n");
        entertainment.append("1. joke - 讲笑话\n");
        entertainment.append("   参数: topic: 主题 (可选)\n");
        entertainment.append("2. riddle - 猜谜语\n");
        entertainment.append("   参数: topic: 主题 (可选)\n");
        entertainment.append("3. poem - 写诗\n");
        entertainment.append("   参数: topic: 主题, style: 风格 (可选)\n");
        entertainment.append("4. story - 讲故事\n");
        entertainment.append("   参数: topic: 主题, length: 长度 (可选)\n");
        entertainment.append("5. trivia - 知识问答\n");
        entertainment.append("   参数: topic: 主题 (可选)\n");
        entertainment.append("6. quote - 名言警句\n");
        entertainment.append("   参数: topic: 主题 (可选)\n");
        entertainment.append("7. game - 游戏\n");
        entertainment.append("   参数: type: 游戏类型 (可选: 猜数字, 20问, 成语接龙)\n");
        return entertainment.toString();
    }
}
