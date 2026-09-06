# -*- coding: utf-8 -*-
# LlamaHelper.java: nativeGetPrefillProgress 声明 + getPrefillProgress 封装
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\jni\LlamaHelper.java"
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
'''    private static native float nativeGetPhaseSpeed();

    public static float getMemoryUsage() {''',
'''    private static native float nativeGetPhaseSpeed();

    /**
     * PREPROCESS（prefill）阶段进度 JSON：{"done":已处理,"total":本轮总数,"pct":百分比}。
     * 用于对话页状态条显示 prefill 进度；空闲返回 0/0。
     */
    public static String getPrefillProgress() {
        if (!libraryLoaded) return null;
        try {
            return nativeGetPrefillProgress();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetPrefillProgress unavailable: " + e.getMessage());
            return null;
        }
    }

    private static native String nativeGetPrefillProgress();

    public static float getMemoryUsage() {''',
'llamahelper prefill')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("LLAMAHELPER OK")
