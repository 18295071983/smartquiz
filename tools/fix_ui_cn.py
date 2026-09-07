# -*- coding: utf-8 -*-
# AIChatActivity.java: 状态机英文 phase 显示改中文（完整版）
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep_all(old, new, label, expect_min=1):
    global src
    c = src.count(old)
    if c < expect_min:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new)
    print("[OK] %s x%d" % (label, c))

# 1) GENERATING 分支
rep_all(
'''                            if (ds > 0) {
                                tvGenPhase.setText(String.format("⏳ %s · %.1f t/s", phase, ds));
                            } else {
                                tvGenPhase.setText("⏳ " + phase);
                            }''',
'''                            if (ds > 0) {
                                tvGenPhase.setText(String.format("⏳ 生成中 · %.1f t/s", ds));
                            } else {
                                tvGenPhase.setText("⏳ 生成中");
                            }''',
'gen cn')

# 2) 所有 "⏳ PREPROCESS" -> "⏳ 预处理"
rep_all("⏳ PREPROCESS", "⏳ 预处理", 'pp cn')

# 3) else 分支
rep_all(
'''                            tvGenPhase.setText("⏳ " + phase);
                            lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始''',
'''                            tvGenPhase.setText("⏳ " + phaseToCn(phase));
                            lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始''',
'else cn')

# 4) phaseToCn 方法
rep_all(
'''    private void refreshNativeStateUI() {
        try {
            // 在线模型：native 状态机不适用，隐藏''',
'''    /** 状态机阶段英文 → 中文显示 */
    private String phaseToCn(String phase) {
        if ("PREPROCESS".equals(phase)) return "预处理";
        if ("THINKING".equals(phase)) return "思考中";
        if ("GENERATING".equals(phase)) return "生成中";
        if ("COMPLETE".equals(phase)) return "完成";
        if ("ERROR".equals(phase)) return "出错";
        if ("IDLE".equals(phase)) return "空闲";
        return phase;
    }

    private void refreshNativeStateUI() {
        try {
            // 在线模型：native 状态机不适用，隐藏''',
'phaseToCn')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("UI CN OK")
