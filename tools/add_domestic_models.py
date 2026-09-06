import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])
existing_ids = {m['id'] for m in models}

new_models = [
    {
        'id': 'internlm2.5-1.8b',
        'name': 'InternLM2.5-1.8B',
        'description': '上海AI实验室书生浦语，1.8B中文对话',
        'downloadUrl': 'https://hf-mirror.com/internlm/internlm2_5-1_8b-chat-gguf/resolve/main/internlm2_5-1_8b-chat-q4_k_m.gguf',
        'sizeBytes': 1200000000,
        'quantization': 'Q4_K_M',
        'contextLength': 32768,
        'minRamMB': 2048,
        'recommendedGpuLayers': 4,
        'architecture': 'InternLM2.5',
        'parameters': '1.8B',
        'memory': '2GB',
        'useCases': '中文对话、轻量问答',
        'sha256': '',
        'backupUrl': ''
    },
    {
        'id': 'minicpm-2b',
        'name': 'MiniCPM-2B',
        'description': '面壁智能端侧模型，2B高效推理',
        'downloadUrl': 'https://hf-mirror.com/openbmb/MiniCPM-2B-sft-gguf/resolve/main/MiniCPM-2B-sft-q4_k_m.gguf',
        'sizeBytes': 1400000000,
        'quantization': 'Q4_K_M',
        'contextLength': 4096,
        'minRamMB': 2048,
        'recommendedGpuLayers': 4,
        'architecture': 'MiniCPM',
        'parameters': '2B',
        'memory': '2GB',
        'useCases': '端侧对话、高效推理',
        'sha256': '',
        'backupUrl': ''
    },
    {
        'id': 'qwen2.5-coder-3b',
        'name': 'Qwen2.5-Coder-3B',
        'description': '阿里代码专用模型，3B编程助手',
        'downloadUrl': 'https://hf-mirror.com/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf',
        'sizeBytes': 1900000000,
        'quantization': 'Q4_K_M',
        'contextLength': 32768,
        'minRamMB': 3072,
        'recommendedGpuLayers': 6,
        'architecture': 'Qwen2.5',
        'parameters': '3B',
        'memory': '3GB',
        'useCases': '代码生成、编程问答',
        'sha256': '',
        'backupUrl': ''
    }
]

added = []
for m in new_models:
    if m['id'] not in existing_ids:
        models.append(m)
        added.append(m['id'])
    else:
        print('已存在，跳过:', m['id'])

data['presets'] = models
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('新增:', added)
print('当前共', len(models), '个模型')
print('全部:', [m['id'] for m in models])
