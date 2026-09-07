# -*- coding: utf-8 -*-
# AIChatActivity.java: 状态机栏改为"仅推理中显示，空闲/完成/无数据一律隐藏"
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

# 整段替换 refreshNativeStateUI：空闲/在线/无数据一律隐藏
rep(
'''    private void refreshNativeStateUI() {
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
    }''',
'''    private void refreshNativeStateUI() {
        try {
            // 在线模型：native 状态机不适用，隐藏
            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
                if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
                return;
            }
            // 状态机栏：仅推理进行中显示；空闲/完成/无数据一律隐藏
            if (tvGenPhase != null) {
                String j = LlamaHelper.getGenPhase();
                boolean show = false;
                if (j != null && !j.isEmpty()) {
                    org.json.JSONObject o = new org.json.JSONObject(j);
                    String phase = o.optString("phase", "IDLE");
                    boolean running = o.optBoolean("running", false);
                    if (running) {
                        tvGenPhase.setText("⏳ " + phase);
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;
                    }
                }
                if (!show) tvGenPhase.setVisibility(View.GONE);
            }
            // KV 缓存栏：有统计记录才显示
            if (tvKvStats != null) {
                String j = LlamaHelper.getKvCacheStats();
                boolean show = false;
                if (j != null && !j.isEmpty()) {
                    org.json.JSONObject o = new org.json.JSONObject(j);
                    double hit = o.optDouble("hit_rate_pct", -1);
                    double usage = o.optDouble("ctx_usage_pct", -1);
                    int plans = o.optInt("plans", 0);
                    if (hit >= 0 && plans > 0) {
                        tvKvStats.setText(String.format("KV ⚡%.0f%% 占%.0f%%",
                                hit, usage >= 0 ? usage : 0));
                        tvKvStats.setVisibility(View.VISIBLE);
                        show = true;
                    }
                }
                if (!show) tvKvStats.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            // 解析失败/异常：隐藏状态条，不影响主流程
            if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
            if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
        }
    }''',
'replace refreshNativeStateUI hide-when-idle')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("HIDE WHEN IDLE OK")
