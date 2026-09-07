import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])

# 删除刚才误加的旧模型（Qwen2.5-3B保留，因为Qwen3无3B）
remove_ids = {'qwen2.5-0.5b', 'minicpm-2b', 'qwen2.5-coder-3b'}

removed = []
kept = []
for m in models:
    if m['id'] in remove_ids:
        removed.append(m['id'])
    else:
        kept.append(m)

data['presets'] = kept
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('删除旧模型:', removed)
print('当前共', len(kept), '个模型')
print('--- 3B及以下（最新小模型）---')
for m in kept:
    p = m.get('parameters', '')
    import re
    match = re.search(r'(\d+(?:\.\d+)?)\s*B', p)
    size = float(match.group(1)) if match else 999
    if size <= 3:
        print(' -', m['id'], '|', p, '|', m.get('name', ''))
print('--- 4B ---')
for m in kept:
    p = m.get('parameters', '')
    import re
    match = re.search(r'(\d+(?:\.\d+)?)\s*B', p)
    size = float(match.group(1)) if match else 0
    if size == 4:
        print(' -', m['id'], '|', p, '|', m.get('name', ''))
