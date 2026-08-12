import sys
with open('D:/qzq/smartquiz/.workbuddy/memory/2026-08-10.md', 'r', encoding='utf-8') as f:
    content = f.read()
addition = """

## 本地 Agent 思考/输出策略彻底简化（用户要求：模型想输出什么就输出什么）

### 用户反馈
- "不要管模型输出什么，模型如果输出的带有思考标记时，就传递思考内容，如果没有传递思考标记，就直接输出正文"
- "引擎中不要强行判断，也不要必须要模型输出思考内容"
- "本地库设计也是不要让模型必须输出思考内容"

### 改动
1. C++ 层：generateWithTools 永远不传 enableThinking=true 给 generateStream，不再追加 lais，不再强制模型输出思考内容
2. Java 层：nativeGenerateSyncWithThinking 简化为：所有 token 统一作为正文收集和流式输出，生成完成后从完整文本中检测 lais/</think 标签分离思考和正文
3. 系统提示词：移除"请先思考用户需求"的引导语
4. 删除 isPartialThinkTag 方法（不再需要跨 token 检测）
"""
content = content + addition
with open('D:/qzq/smartquiz/.workbuddy/memory/2026-08-10.md', 'w', encoding='utf-8') as f:
    f.write(content)
print("Memory updated!")
