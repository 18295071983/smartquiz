import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])
existing_ids = {m['id'] for m in models}

# 新增的小模型（3B及以下）
small_models = [
    {
        "id": "qwen2.5-3b",
        "name": "Qwen2.5-3B",
        "description": "阿里3B中文模型，速度快，Qwen3无3B版本时的最佳选择",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        "sizeBytes": 1900000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 3072,
        "recommendedGpuLayers": 6,
        "architecture": "Qwen2.5",
        "parameters": "3B",
        "memory": "3GB",
        "useCases": "快速中文对话、日常问答",
        "sha256": "",
        "backupUrl": "https://modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf"
    },
    {
        "id": "qwen2.5-0.5b",
        "name": "Qwen2.5-0.5B",
        "description": "超轻量0.5B，极速响应，适合简单任务",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "sizeBytes": 468000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 1024,
        "recommendedGpuLayers": 2,
        "architecture": "Qwen2.5",
        "parameters": "0.5B",
        "memory": "1GB",
        "useCases": "超快速简单问答、低资源设备",
        "sha256": "",
        "backupUrl": ""
    },
    {
        "id": "minicpm-2b",
        "name": "MiniCPM-2B",
        "description": "面壁智能端侧模型，2B高效推理",
        "downloadUrl": "https://hf-mirror.com/openbmb/MiniCPM-2B-sft-gguf/resolve/main/MiniCPM-2B-sft-q4_k_m.gguf",
        "sizeBytes": 1400000000,
        "quantization": "Q4_K_M",
        "contextLength": 4096,
        "minRamMB": 2048,
        "recommendedGpuLayers": 4,
        "architecture": "MiniCPM",
        "parameters": "2B",
        "memory": "2GB",
        "useCases": "端侧对话、高效推理",
        "sha256": "",
        "backupUrl": ""
    },
    {
        "id": "qwen2.5-coder-3b",
        "name": "Qwen2.5-Coder-3B",
        "description": "阿里代码专用模型，3B编程助手，速度快",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        "sizeBytes": 1900000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 3072,
        "recommendedGpuLayers": 6,
        "architecture": "Qwen2.5",
        "parameters": "3B",
        "memory": "3GB",
        "useCases": "代码生成、编程问答",
        "sha256": "",
        "backupUrl": ""
    }
]

added = []
for m in small_models:
    if m['id'] not in existing_ids:
        models.append(m)
        added.append(m['id'])
    else:
        print('已存在，跳过:', m['id'])

data['presets'] = models
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('新增小模型:', added)
print('当前共', len(models), '个模型')
print('--- 3B及以下 ---')
for m in models:
    p = m.get('parameters', '')
    import re
    match = re.search(r'(\d+(?:\.\d+)?)\s*B', p)
    size = float(match.group(1)) if match else 999
    if size <= 3:
        print(' -', m['id'], '|', p, '|', m.get('name', ''))
print('--- 4B ---')
for m in models:
    p = m.get('parameters', '')
    import re
    match = re.search(r'(\d+(?:\.\d+)?)\s*B', p)
    size = float(match.group(1)) if match else 0
    if size == 4:
        print(' -', m['id'], '|', p, '|', m.get('name', ''))
