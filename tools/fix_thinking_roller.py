# -*- coding: utf-8 -*-
# AIChatActivity.java: 顶部改为「单行 + 段落滚动切换」
# 1) 去掉多行面板字段，加 thinkingSegmentIndex/thinkingSegments/lastThinkShown
# 2) THINKING 分支：单行显示最新思考段（内容增长跳最新，停顿轮播各段）+ 上滚切换动画
# 3) thinkingRefreshRunnable 去掉面板更新（只更新气泡思考区）
# 4) 去掉面板可见性逻辑
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

# 1) 字段：去掉面板字段 → 单行滚动切换字段
rep(
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    private android.view.View thinkingPanel;           // 顶部实时思考面板（逐行显示）
    private android.widget.ScrollView thinkingPanelScroll;
    private TextView tvThinkingPanel;''',
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    // 顶部单行滚动切换：思考内容按行切段，轮播/跟随最新段显示
    private java.util.List<String> thinkingSegments = new java.util.ArrayList<>();
    private int thinkingSegmentIndex = 0;
    private String lastThinkShown;''',
'roller fields')

# 2) 去掉绑定（面板）
rep(
'''            tvGenPhase = findViewById(R.id.tv_gen_phase);
            tvKvStats = findViewById(R.id.tv_kv_stats);
            thinkingPanel = findViewById(R.id.thinking_panel);
            thinkingPanelScroll = findViewById(R.id.thinking_panel_scroll);
            tvThinkingPanel = findViewById(R.id.tv_thinking_panel);''',
'''            tvGenPhase = findViewById(R.id.tv_gen_phase);
            tvKvStats = findViewById(R.id.tv_kv_stats);''',
'unbind panel')

# 3) thinkingRefreshRunnable 去掉面板更新
rep(
'''                chatAdapter.updateMessageThinkingContent(idx, msg.thinkingContent);
                // 顶部思考面板：与气泡思考区同源，逐行实时刷新 + 自动滚动跟随最新内容
                if (thinkingPanel != null && thinkingPanel.getVisibility() == View.VISIBLE
                        && tvThinkingPanel != null && msg.thinkingContent != null) {
                    tvThinkingPanel.setText(msg.thinkingContent);
                    if (thinkingPanelScroll != null) {
                        thinkingPanelScroll.post(() ->
                                thinkingPanelScroll.fullScroll(android.view.View.FOCUS_DOWN));
                    }
                }''',
'''                chatAdapter.updateMessageThinkingContent(idx, msg.thinkingContent);''',
'runnable no panel')

# 4) THINKING 分支：单行滚动切换（去掉面板可见性）
rep(
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
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;''',
'roller thinking branch')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ROLLER STEP1 OK")
