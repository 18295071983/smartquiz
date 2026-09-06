# -*- coding: utf-8 -*-
# AIChatActivity.java: 回退打字机改动 → 恢复单行滚动切换版本（打字机逐字处理中文切坏字符导致乱码）
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

# 1) 字段：打字机 → 单行滚动切换字段
rep(
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    // 顶部单行打字机：thinking 增量事件实时驱动（无 800ms 轮询限制），逐字显示思考内容；
    // 遇换行 → 新行重新打字（按行实时显示）
    private final StringBuilder thinkingTypeBuffer = new StringBuilder();
    private boolean thinkingTyping = false;
    private final android.os.Handler typingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastPhaseShown;
    private final Runnable typingTickRunnable = new Runnable() {
        @Override public void run() {
            thinkingTyping = false;
            if (tvGenPhase == null) return;
            synchronized (thinkingTypeBuffer) {
                if (thinkingTypeBuffer.length() == 0) return;
                char c = thinkingTypeBuffer.charAt(0);
                thinkingTypeBuffer.deleteCharAt(0);
                if (c == '\\n') {
                    // 新行：当前行结束，从新行重新打字（按行实时显示）
                    tvGenPhase.setText("💭 ");
                } else {
                    tvGenPhase.append(String.valueOf(c));
                }
                if (thinkingTypeBuffer.length() > 0) {
                    thinkingTyping = true;
                    typingHandler.postDelayed(typingTickRunnable, 20);
                }
            }
        }
    };''',
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    // 顶部单行滚动切换：思考内容按行切段，轮播/跟随最新段显示
    private java.util.List<String> thinkingSegments = new java.util.ArrayList<>();
    private int thinkingSegmentIndex = 0;
    private String lastThinkShown;''',
'fields revert')

# 2) THINKING 分支：恢复单行滚动切换
rep(
'''                        } else if ("THINKING".equals(phase)) {
                            // 打字机实时显示：思考内容由 thinking 增量事件逐字打出（无节流限制）。
                            // 这里只负责首次进入思考时初始化显示（"💭 "），内容追加交给打字机
                            if (!"THINKING".equals(lastPhaseShown)) {
                                tvGenPhase.setText("💭 ");
                                stopThinkingTyping();
                                lastPhaseShown = "THINKING";
                            }
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                            lastPhaseShown = phase;
                            stopThinkingTyping();   // 非思考阶段停止打字机
                        }''',
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
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                            lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始
                        }''',
'thinking branch revert')

# 3) 方法：打字机 → 单行滚动切换方法
rep(
'''    /**
     * thinking 增量事件到达：追加到打字机 buffer 并启动逐字显示（实时，无节流限制）。
     * 思考中每行内容到达 → 打字机逐字打出；遇换行 → 新行重新打字。
     */
    private void enqueueThinkingType(String inc) {
        if (inc == null || inc.isEmpty()) return;
        synchronized (thinkingTypeBuffer) {
            thinkingTypeBuffer.append(inc);
            if (!thinkingTyping) {
                thinkingTyping = true;
                typingHandler.post(typingTickRunnable);
            }
        }
    }

    /** 停止打字机并清空待打内容（非思考阶段调用） */
    private void stopThinkingTyping() {
        synchronized (thinkingTypeBuffer) {
            thinkingTypeBuffer.setLength(0);
            thinkingTyping = false;
            typingHandler.removeCallbacks(typingTickRunnable);
        }
    }

    private void refreshNativeStateUI() {''',
'''    /**
     * 单行滚动切换：把思考内容按行切段，轮播/跟随最新段。
     * 内容增长（新段出现）→ 跳到最新段（实时显示最新进展）；
     * 内容停顿 → 轮播各段（滚动切换效果）。
     */
    private String buildThinkingSegment(String think) {
        java.util.List<String> segs = new java.util.ArrayList<>();
        for (String line : think.split("\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) segs.add(t);
        }
        if (segs.isEmpty()) {
            segs.add(think);
        }
        int oldSize = thinkingSegments.size();
        thinkingSegments = segs;
        if (segs.size() > oldSize) {
            // 内容增长：显示最新段（最新进展）
            thinkingSegmentIndex = segs.size() - 1;
        } else {
            // 内容停顿：轮播到下一段（滚动切换）
            thinkingSegmentIndex = (thinkingSegmentIndex + 1) % segs.size();
        }
        String seg = segs.get(thinkingSegmentIndex);
        // 单行截断：保留最新尾部（最新思考）
        if (seg.length() > 34) seg = seg.substring(seg.length() - 34);
        return seg;
    }

    /** 单行段落切换动画：新内容从下方滚入（无跑马灯定时重置问题） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    0, 0, 14f, 0);
            a.setDuration(220);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            tvGenPhase.startAnimation(a);
        } catch (Exception ignored) {}
    }

    private void refreshNativeStateUI() {''',
'methods revert')

# 4) appendBridge/Agent 去掉 enqueueThinkingType
rep(
'''        msg.thinkingContent = snapshot;
        // 顶部单行打字机：thinking 增量事件实时逐字显示（无 800ms 轮询限制）
        enqueueThinkingType(token);
        // 定时渲染（120ms 批量），防每 reasoning 事件 notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);''',
'''        msg.thinkingContent = snapshot;
        // 定时渲染（120ms 批量），防每 reasoning 事件 notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);''',
'bridge hook revert')

rep(
'''        // 顶部单行打字机：thinking 增量事件实时逐字显示
        enqueueThinkingType(token);
        // 定时渲染（120ms 批量），防每 token notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);''',
'''        // 定时渲染（120ms 批量），防每 token notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);''',
'agent hook revert')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("TYPEWRITER REVERT OK")
