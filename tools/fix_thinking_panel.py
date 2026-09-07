# -*- coding: utf-8 -*-
# AIChatActivity.java: 顶部思考面板（逐行实时滚动）+ 去掉 marquee
# 1) 去掉 marquee 字段 lastThinkShown/lastThinkUiUpdate（恢复纯状态显示）
# 2) THINKING 分支恢复 "⏳ 思考中"（去掉思考内容预览/setSelected）
# 3) 加 tvThinkingPanel/tvThinkingPanelScroll/thinkingPanel 字段
# 4) thinkingRefreshRunnable 更新面板文本 + 自动滚动到底部
# 5) refreshNativeStateUI 控制面板可见性（THINKING 显示，其他 GONE）
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

# 1) 去 marquee 字段 → 改为思考面板字段
rep(
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    private String lastThinkShown;          // 思考内容 marquee 上次显示（内容未变化不重置滚动）
    private long lastThinkUiUpdate;         // 思考内容 UI 上次节流更新时间戳''',
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    private android.view.View thinkingPanel;           // 顶部实时思考面板（逐行显示）
    private android.widget.ScrollView thinkingPanelScroll;
    private TextView tvThinkingPanel;''',
'panel fields')

# 2) THINKING 分支恢复纯状态
rep(
'''                        } else if ("THINKING".equals(phase)) {
                            // 思考段实时显示模型思考内容：marquee 滚动 + 2.5s 节流更新
                            // （native 每 token 累积，若每 800ms setText 会一直重置滚动位置，
                            //   节流让滚动能真正跑起来，内容阶段性跳到最新累积）
                            String think = LlamaHelper.getThinkingContent();
                            if (think != null && !think.isEmpty()) {
                                String preview = think.replace('\\n', ' ').replace('\\r', ' ').trim();
                                // 取最新 200 字滚动展示（思考尾部=最新进展）
                                if (preview.length() > 200) preview = preview.substring(preview.length() - 200);
                                long now = System.currentTimeMillis();
                                String disp = "⏳ 思考中 " + preview;
                                if (lastThinkShown == null || !disp.equals(lastThinkShown)
                                        || (now - lastThinkUiUpdate >= 2500)) {
                                    tvGenPhase.setText(disp);
                                    lastThinkShown = disp;
                                    lastThinkUiUpdate = now;
                                }
                            } else if (lastThinkShown == null) {
                                tvGenPhase.setText("⏳ THINKING");
                                lastThinkShown = "⏳ THINKING";
                            }
                            tvGenPhase.setSelected(true);   // 激活 marquee 滚动
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                            tvGenPhase.setSelected(false);  // 非思考阶段停止滚动
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;''',
'''                        } else if ("THINKING".equals(phase)) {
                            // 思考阶段：状态条只显示阶段状态，思考内容由下方思考面板逐行实时滚动
                            tvGenPhase.setText("⏳ 思考中");
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;
                        // 思考面板：思考中显示（逐行实时思考），其他阶段隐藏
                        if (thinkingPanel != null) {
                            thinkingPanel.setVisibility("THINKING".equals(phase) ? View.VISIBLE : View.GONE);
                        }''',
'phase bar pure state')

# 3) thinkingRefreshRunnable 更新面板 + 滚动
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
                // 顶部思考面板：与气泡思考区同源，逐行实时刷新 + 自动滚动跟随最新内容
                if (thinkingPanel != null && thinkingPanel.getVisibility() == View.VISIBLE
                        && tvThinkingPanel != null && msg.thinkingContent != null) {
                    tvThinkingPanel.setText(msg.thinkingContent);
                    if (thinkingPanelScroll != null) {
                        thinkingPanelScroll.post(() ->
                                thinkingPanelScroll.fullScroll(android.view.View.FOCUS_DOWN));
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                currentStreamingMessageIndex = -1;
            }''',
'panel refresh')

# 4) 绑定
rep(
'''            tvGenPhase = findViewById(R.id.tv_gen_phase);
            tvKvStats = findViewById(R.id.tv_kv_stats);''',
'''            tvGenPhase = findViewById(R.id.tv_gen_phase);
            tvKvStats = findViewById(R.id.tv_kv_stats);
            thinkingPanel = findViewById(R.id.thinking_panel);
            thinkingPanelScroll = findViewById(R.id.thinking_panel_scroll);
            tvThinkingPanel = findViewById(R.id.tv_thinking_panel);''',
'panel bind')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PANEL STEP2 OK")
