import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])
existing_ids = {m['id'] for m in models}

# 从 git 历史恢复的 4B 模型
models_4b = [
    {
        "id": "minicpm3-4b",
        "name": "MiniCPM3-4B",
        "description": "面壁中文模型",
        "downloadUrl": "https://hf-mirror.com/openbmb/MiniCPM3-4B-GGUF/resolve/main/minicpm3-4b-q4_k_m.gguf",
        "sizeBytes": 2400000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 4096,
        "recommendedGpuLayers": 8,
        "architecture": "MiniCPM3",
        "parameters": "4B",
        "memory": "4GB",
        "useCases": "中文对话、知识问答"
    },
    {
        "id": "qwen3-4b",
        "name": "Qwen3-4B（推荐）",
        "description": "思考链+原生工具调用双全，本地Agent首选",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
        "sizeBytes": 2400000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 4096,
        "recommendedGpuLayers": 8,
        "architecture": "Qwen3",
        "parameters": "4B",
        "memory": "4GB",
        "useCases": "深度推理、复杂中文任务、Agent工具调用"
    },
    {
        "id": "qwen3-vl-4b-thinking",
        "name": "Qwen3-VL-4B-Thinking",
        "description": "多模态Agent升级版：更强工具调用与推理（视觉+思考链）",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/main/Qwen3VL-4B-Thinking-Q4_K_M.gguf",
        "sizeBytes": 2381000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 6144,
        "recommendedGpuLayers": 8,
        "architecture": "Qwen3-VL",
        "parameters": "4B",
        "memory": "6GB",
        "useCases": "视觉理解+思考链+更强Agent工具调用",
        "mmprojUrl": "https://hf-mirror.com/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/main/mmproj-Qwen3VL-4B-Thinking-Q8_0.gguf",
        "mmprojSizeMB": 433,
        "sha256": "474ecaf1284aa6ff3273fb796c3cba55d2ee33ec0d8c63464fbd84500a9a462d",
        "mmprojSha256": "6b71c77c50944ec5d058d58ef39b687897567bea2a721ce49def98941db2cb96",
        "backupUrl": "https://modelscope.cn/models/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/master/Qwen3VL-4B-Thinking-Q4_K_M.gguf",
        "backupMmprojUrl": "https://modelscope.cn/models/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/master/mmproj-Qwen3VL-4B-Thinking-Q8_0.gguf",
        "backupSha256": "474ecaf1284aa6ff3273fb796c3cba55d2ee33ec0d8c63464fbd84500a9a462d",
        "backupMmprojSha256": "6b71c77c50944ec5d058d58ef39b687897567bea2a721ce49def98941db2cb96"
    }
]

added = []
for m in models_4b:
    if m['id'] not in existing_ids:
        models.append(m)
        added.append(m['id'])
    else:
        print('已存在，跳过:', m['id'])

data['presets'] = models
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('新增4B模型:', added)
print('当前共', len(models), '个模型')
for m in models:
    print(' -', m['id'], '|', m.get('parameters', ''), '|', m.get('name', ''))
