# -*- coding: utf-8 -*-
"""AIChatActivity：普通对话 bridge 的 onThinkingUpdate（原空实现）接思考区显示；
新增 appendBridgeThinkingToken 把 chatJson reasoning 内容写入 msg.thinkingContent（折叠展示）。"""
import io

p = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
s = io.open(p, encoding="utf-8").read()

# ---- 1) onThinkingUpdate 空实现 -> 接思考区 ----
old_th = '''            @Override
            public void onThinkingUpdate(String messageId, int stepNumber, String stepType,
                                          String title, String content, int progress) {}'''

new_th = '''            @Override
            public void onThinkingUpdate(String messageId, int stepNumber, String stepType,
                                          String title, String content, int progress) {
                // 普通对话 chatJson reasoning 事件：思考内容写入思考区（折叠显示）
                if (content == null || content.isEmpty()) return;
                appendBridgeThinkingToken(content);
            }'''

assert s.count(old_th) == 1, "onThinkingUpdate anchor not unique: %d" % s.count(old_th)
s = s.replace(old_th, new_th)

# ---- 2) createBridgeCallback 方法结束后插入 appendBridgeThinkingToken ----
anchor_after = '''    /**
     * 更新 AI 消息气泡内的 Agent 执行步骤状态行。
     */'''

new_method = '''    /**
     * Bridge 普通对话的思考更新（chatJson reasoning 事件）：写入思考区（msg.thinkingContent）。
     * 与 Agent 路径的 appendAgentThinkingToken 对应；思考默认折叠、可点击展开。
     */
    private void appendBridgeThinkingToken(String token) {
        if (token == null || token.isEmpty()) return;
        final int idx = resolveStreamingIndex();
        if (idx < 0) return;
        String snapshot;
        synchronized (streamingLock) {
            if (currentThinkingContent == null) {
                currentThinkingContent = new StringBuilder();
            }
            currentThinkingContent.append(token);
            snapshot = currentThinkingContent.toString();
        }
        isInThinking = true;
        ChatMessage msg = chatHistory.get(idx);
        msg.thinkingContent = snapshot;
        // 定时渲染（120ms 批量），防每 reasoning 事件 notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);
    }

''' + anchor_after

assert s.count(anchor_after) == 1, "insert anchor not unique: %d" % s.count(anchor_after)
s = s.replace(anchor_after, new_method)

io.open(p, "w", encoding="utf-8").write(s)
print("OK patched AIChatActivity onThinkingUpdate")
