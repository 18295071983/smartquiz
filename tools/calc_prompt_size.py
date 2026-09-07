import re

with open('src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java', 'r', encoding='utf-8') as f:
    content = f.read()

match = re.search(r'private String buildFcSystemPrompt\(\) \{(.*?)\n    \}', content, re.DOTALL)
if match:
    prompt_code = match.group(1)
    strings = re.findall(r'sb\.append\("(.*?)"\)', prompt_code, re.DOTALL)
    system_prompt = ''.join(s.replace('\\n', '\n').replace('\\"', '"') for s in strings)
    print(f'系统提示词字符数: {len(system_prompt)}')
    print(f'系统提示词约token数: {len(system_prompt)//2}')

tools = ['ai_weather', 'time_date', 'location', 'network_search']
print(f'\n四个工具: {tools}')
print('估算: 每个工具schema约300-500字符, 四个约1200-2000字符')
print('总提示词约: 800(系统) + 1500(工具schema) = 2300字符 ≈ 1100-1500 token')
