# -*- coding: utf-8 -*-
import io

p = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\software\engine\AgentLoopEngine.java'
text = io.open(p, 'rb').read().decode('utf-8')

sig = '    private boolean isCompleteJsonObject(String s) {'
si = text.find(sig)
if si < 0:
    print('ERROR: isCompleteJsonObject not found')
    raise SystemExit(1)

# 方法体起止（签名行含 {，用大括号平衡）
depth = 0
i = si
while i < len(text):
    if text[i] == '{':
        depth += 1
    elif text[i] == '}':
        depth -= 1
        if depth == 0:
            break
    i += 1
body_old = text[si:i + 1]

body_new = '''    private boolean isCompleteJsonObject(String s) {
        // 用 org.json 解析器判定是否已形成完整 JSON 对象：
        // 正确处理字符串内花括号/嵌套/转义，比括号配平更可靠。
        // 累积内容不完整时 JSONObject 抛 JSONException → false（继续累积）。
        int start = s.indexOf('{');
        if (start < 0) return false;
        if (s.indexOf('}') < start) return false;   // 尚无闭合括号，未形成对象
        try {
            new JSONObject(s.substring(start));
            return true;
        } catch (JSONException e) {
            return false;
        }
    }'''

text = text[:si] + body_new + text[i + 1:]
io.open(p, 'w', encoding='utf-8', newline='').write(text)
print('isCompleteJsonObject replaced with JSONObject-based version')
