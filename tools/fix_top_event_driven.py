# -*- coding: utf-8 -*-
# AIChatActivity.java: 顶部单行改为 thinking 事件驱动（无 800ms 轮询限制）+ 保留上滚动画
# 1) refreshNativeStateUI THINKING 分支：只首次初始化 "💭 "（内容更新交给 thinkingRefreshRunnable）
# 2) thinkingRefreshRunnable：更新气泡思考区 + 顶部单行（buildThinkingSegment + 上滚动画）
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label, expect=1):
    global src
    c = src.count(old)
    if c != expect:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, expect)
    print("[OK] %s" % label)

# 1) THINKING 分支：只初始化
rep(
'''                        } else if ("THINKING".equals(phase)) {
                            // 单行滚动切换：实时显示思考内容（按行切段，内容增长跳最新段、
                            // 停顿轮播各段），段落变化时上滚切换动画（无跑马灯定时重置问题）
                            String think = LlamaHelper.getThinkingContent();
                            if (think != null && !think.isEmpty()) {
                                String disp = "💭 " + buildThinkingSegment(think);
                                if (!disp.equals(lastThinkShown)) {
                                    tvGenPhase.setText(disp);
                                    lastThinkShown = disp;
                                    startThinkingRollerAnim();
                                }
                            } else {
                                tvGenPhase.setText("⏳ 思考中");
                                lastThinkShown = "⏳ 思考中";
                            }
                        } else {''',
'''                        } else if ("THINKING".equals(phase)) {
                            // 顶部单行由 thinking 事件驱动（120ms 节流，无 800ms 轮询限制）：
                            // 这里只负责首次进入思考时初始化显示，内容段落切换/动画交给 thinkingRefreshRunnable
                            if (lastThinkShown == null) {
                                tvGenPhase.setText("💭 ");
                                lastThinkShown = "💭 ";
                            }
                        } else {''',
'thinking branch event-driven')

# 2) thinkingRefreshRunnable 加顶部单行更新
rep(
'''            if (idx < 0 || idx >= chatHistory.size() || chatAdapter == null) return;
            try {
                ChatMessage msg = chatHistory.get(idx);
                chatAdapter.updateMessageThinkingContent(idx, msg.thinkingContent);
            } catch (IndexOutOfBoundsException e) {
                currentStreamingMessageIndex = -1;
            }''',
'''            if (idx < 0 || idx >= chatHistory.size() || chatAdapter == null) return;
            try {
                ChatMessage msg = chatHistory.get(idx);
                chatAdapter.updateMessageThinkingContent(idx, msg.thinkingContent);
                // 顶部单行：thinking 事件驱动（120ms 节流），段落切换 + 上滚动画，无 800ms 轮询限制
                if (isInThinking && tvGenPhase != null && tvGenPhase.getVisibility() == View.VISIBLE
                        && msg.thinkingContent != null && !msg.thinkingContent.isEmpty()) {
                    String disp = "💭 " + buildThinkingSegment(msg.thinkingContent);
                    if (!disp.equals(lastThinkShown)) {
                        tvGenPhase.setText(disp);
                        lastThinkShown = disp;
                        startThinkingRollerAnim();
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                currentStreamingMessageIndex = -1;
            }''',
'runnable top update')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("EVENT-DRIVEN TOP OK")
