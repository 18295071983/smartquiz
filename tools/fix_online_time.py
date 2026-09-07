# -*- coding: utf-8 -*-
# OnlineInferenceService.java: 在线普通对话注入当前日期（权威事实）
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\OnlineInferenceService.java"
s = io.open(path, "r", encoding="utf-8").read()
old = '''            // 构建消息列表
            JsonArray messages = new JsonArray();
            
            // 添加历史消息
            if (history != null) {
                for (ChatMessage msg : history) {
                    JsonObject message = new JsonObject();
                    if (msg.isSystemMessage()) {
                        message.addProperty("role", "system");
                    } else if (msg.isUserMessage()) {
                        message.addProperty("role", "user");
                    } else if (msg.isAIMessage()) {
                        message.addProperty("role", "assistant");
                    }
                    message.addProperty("content", msg.content);
                    messages.add(message);
                }
            }'''
new = '''            // 构建消息列表
            JsonArray messages = new JsonArray();

            // 注入当前日期（权威事实）：防止模型用训练截止时间回答"今天几号/最新"类问题
            try {
                JsonObject sysMsg = new JsonObject();
                sysMsg.addProperty("role", "system");
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyy年M月d日 EEEE", java.util.Locale.CHINA);
                sysMsg.addProperty("content", "当前日期：" + sdf.format(new java.util.Date())
                        + "。这是系统实时提供的当前时间，回答今天/几号/当前时间/最新等问题以它为准，不要使用训练数据中的旧时间。");
                messages.add(sysMsg);
            } catch (Exception e) {
                AILogger.w(TAG, "Time inject failed: " + e.getMessage());
            }

            // 添加历史消息
            if (history != null) {
                for (ChatMessage msg : history) {
                    JsonObject message = new JsonObject();
                    if (msg.isSystemMessage()) {
                        message.addProperty("role", "system");
                    } else if (msg.isUserMessage()) {
                        message.addProperty("role", "user");
                    } else if (msg.isAIMessage()) {
                        message.addProperty("role", "assistant");
                    }
                    message.addProperty("content", msg.content);
                    messages.add(message);
                }
            }'''
c = s.count(old)
if c != 1:
    print("FAIL count=%d" % c); sys.exit(1)
s = s.replace(old, new)
io.open(path, "w", encoding="utf-8", newline="\n").write(s)
print("ONLINE CHAT TIME OK")
