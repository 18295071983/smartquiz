# -*- coding: utf-8 -*-
# AIChatActivity.java: updateStreamingTokenStats 也调用 refreshNativeStateUI()
# （推理中 token 流式更新走此路径，之前只接了 updateTokenStatsUI 导致状态条不更新）
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

rep(
'''    private void updateStreamingTokenStats(int totalTokens, float tokensPerSecond) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(View.VISIBLE);''',
'''    private void updateStreamingTokenStats(int totalTokens, float tokensPerSecond) {
        // native 状态条（状态机阶段 + KV 缓存）随推理 token 流式更新实时刷新
        refreshNativeStateUI();
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(View.VISIBLE);''',
'call refresh in updateStreamingTokenStats')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("STREAM REFRESH OK")
