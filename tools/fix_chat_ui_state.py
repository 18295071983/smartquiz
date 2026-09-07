# -*- coding: utf-8 -*-
# AIChatActivity.java: 绑定 tv_gen_phase/tv_kv_stats + refreshNativeStateUI()
# 在 updateTokenStatsUI 开头调用（推理中 token 流式更新会频繁触发，保持实时）
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

# 1) 绑定
rep(
'''            serviceStatusProgress = findViewById(R.id.service_status_progress);
            serviceStatusElapsed = findViewById(R.id.service_status_elapsed);
            ''',
'''            serviceStatusProgress = findViewById(R.id.service_status_progress);
            serviceStatusElapsed = findViewById(R.id.service_status_elapsed);
            tvGenPhase = findViewById(R.id.tv_gen_phase);
            tvKvStats = findViewById(R.id.tv_kv_stats);
            ''',
'bind views')

# 2) 字段声明：找现有 TextView 字段（在 serviceStatusElapsed 声明附近）
rep(
'''    private TextView serviceStatusElapsed;
''',
'''    private TextView serviceStatusElapsed;
    private TextView tvGenPhase;
    private TextView tvKvStats;
''',
'field decl')

# 3) updateTokenStatsUI 开头调 refreshNativeStateUI()
rep(
'''    private void updateTokenStatsUI(TokenStatsManager.TokenStats stats) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);''',
'''    private void updateTokenStatsUI(TokenStatsManager.TokenStats stats) {
        refreshNativeStateUI();
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);''',
'call refresh in updateTokenStatsUI')

# 4) 新增 refreshNativeStateUI()（插到 updateTokenStatsUI 方法结束后、formatCtxWindow 前）
rep(
'''    /**
     * 上下文窗口格式化：>=1M 显示 "1M"（如 deepseek-v4 的 1048576），>=1K 显示 "64K"，否则原值。
     */''',
'''    /**
     * 刷新顶部 native 状态条：生成流程状态机阶段（GenPhase）+ KV 增量缓存状态。
     *
     * <p>本地推理时实时展示思考段/正文生成阶段；KV 命中率与上下文占用来自
     * AgentKvCache 统计（JNI nativeGetKvCacheStats）。在线模型不适用 native 状态，
     * 自动隐藏。</p>
     */
    private void refreshNativeStateUI() {
        try {
            // 仅本地推理展示 native 状态机/KV；在线模型保持隐藏
            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                if (tvGenPhase != null) tvGenPhase.setText("状态机: --");
                if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
                return;
            }
            if (tvGenPhase != null) {
                String j = LlamaHelper.getGenPhase();
                if (j != null && !j.isEmpty()) {
                    org.json.JSONObject o = new org.json.JSONObject(j);
                    String phase = o.optString("phase", "IDLE");
                    boolean running = o.optBoolean("running", false);
                    String stop = o.optString("stop_cause", "NONE");
                    String icon = running ? "⏳" : "✓";
                    tvGenPhase.setText(String.format("%s 状态机: %s%s",
                            icon, phase, running ? "" : (" · " + stop)));
                } else {
                    tvGenPhase.setText("状态机: --");
                }
            }
            if (tvKvStats != null) {
                String j = LlamaHelper.getKvCacheStats();
                if (j != null && !j.isEmpty()) {
                    org.json.JSONObject o = new org.json.JSONObject(j);
                    double hit = o.optDouble("hit_rate_pct", -1);
                    double usage = o.optDouble("ctx_usage_pct", -1);
                    int plans = o.optInt("plans", 0);
                    if (hit >= 0 && plans > 0) {
                        tvKvStats.setVisibility(View.VISIBLE);
                        tvKvStats.setText(String.format("KV ⚡%.0f%% 占%.0f%%",
                                hit, usage >= 0 ? usage : 0));
                    } else {
                        tvKvStats.setVisibility(View.GONE);
                    }
                }
            }
        } catch (Throwable t) {
            // 解析失败静默，不影响主流程
        }
    }

    /**
     * 上下文窗口格式化：>=1M 显示 "1M"（如 deepseek-v4 的 1048576），>=1K 显示 "64K"，否则原值。
     */''',
'add refreshNativeStateUI')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("AICHATACTIVITY FIX OK")
