# -*- coding: utf-8 -*-
# AIChatActivity.java: 思考/生成时隐藏 KV 统计，位置留给思考内容显示；空闲时显示 KV 缓存状态
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
'''            // KV 缓存栏：有统计记录才显示
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
            }''',
'''            // KV 缓存栏：思考/生成（推理中）隐藏——位置留给思考内容/生成状态显示；
            // 空闲时显示缓存状态（监控用，性能面板亦有完整卡片）
            if (tvKvStats != null) {
                boolean runningNow = false;
                String gp = LlamaHelper.getGenPhase();
                if (gp != null && !gp.isEmpty()) {
                    try {
                        runningNow = new org.json.JSONObject(gp).optBoolean("running", false);
                    } catch (Exception ignored) {}
                }
                if (runningNow) {
                    tvKvStats.setVisibility(View.GONE);
                } else {
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
            }''',
'kv hide during inference')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("KV HIDE OK")
