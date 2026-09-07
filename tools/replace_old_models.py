import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])

# 要删除的旧模型 id
remove_ids = {
    'qwen2.5-0.5b', 'qwen2.5-1.5b', 'qwen2.5-3b',
    'qwen2.5-coder-1.5b', 'qwen2.5-coder-3b', 'qwen2.5-vl-3b',
    'minicpm-2b'
}

removed = []
kept = []
for m in models:
    if m['id'] in remove_ids:
        removed.append(m['id'])
    else:
        kept.append(m)

# 新增的新模型
new_models = [
    {
        "id": "qwen3.8-4b-distill",
        "name": "Qwen3.8-4B-Distill",
        "description": "Qwen3.8蒸馏版，4B参数，思考链+工具调用，EmperoAI出品",
        "downloadUrl": "https://hf-mirror.com/empero-ai/Qwen3.8-4B-Distill-GGUF/resolve/main/Qwen3.8-4B-Distill-Q4_K_M.gguf",
        "sizeBytes": 2400000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 4096,
        "recommendedGpuLayers": 8,
        "architecture": "Qwen3.5",
        "parameters": "4B",
        "memory": "4GB",
        "useCases": "深度推理、Agent工具调用、中文对话",
        "sha256": "",
        "backupUrl": ""
    }
]

existing_ids = {m['id'] for m in kept}
added = []
for m in new_models:
    if m['id'] not in existing_ids:
        kept.append(m)
        added.append(m['id'])

data['presets'] = kept
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('删除旧模型:', removed)
print('新增新模型:', added)
print('当前共', len(kept), '个模型')
for m in kept:
    print(' -', m['id'], '|', m.get('parameters', ''), '|', m.get('name', ''))
