# -*- coding: utf-8 -*-
# 给 AIChatActivity.sendMessage 加日志：入口 + 各守卫分支，精确定位"发了不处理"
import io, sys

path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def replace_once(old, new, label):
    global src
    c = src.count(old)
    if c != 1:
        print("%s: count=%d ABORT" % (label, c))
        sys.exit(1)
    src = src.replace(old, new, 1)
    print("%s: OK" % label)

# sendMessage 入口日志
replace_once(
'''    private void sendMessage() {
        if (isGenerating) { showToast("AI正在生成中，请稍候"); return; }''',
'''    private void sendMessage() {
        AppLogger.ai(TAG, "[sendMessage] 入口: isGenerating=" + isGenerating
                + ", input=" + (inputMessage != null ? "\"" + inputMessage.getText().toString().trim() + "\"" : "null"));
        if (isGenerating) {
            AppLogger.aiW(TAG, "[sendMessage] 拦截: isGenerating=true，仍在生成中");
            showToast("AI正在生成中，请稍候");
            return;
        }''',
'sendMessage entry')

# ensureModelLoaded 分支
replace_once(
'''        if (!hasImageAttachment && !ensureModelLoaded(message)) {
            addUserMessage(message);
            inputMessage.setText("");
            return;
        }''',
'''        if (!hasImageAttachment && !ensureModelLoaded(message)) {
            AppLogger.aiW(TAG, "[sendMessage] ensureModelLoaded 返回 false，仅加入历史不推理，msg=" + message);
            addUserMessage(message);
            inputMessage.setText("");
            return;
        }
        AppLogger.ai(TAG, "[sendMessage] 通过守卫，进入 processChatMessage: " + message);''',
'sendMessage ensureModel')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PATCH OK")
