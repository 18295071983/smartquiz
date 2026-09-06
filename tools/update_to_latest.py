import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])

# 删除旧的 Qwen3 系列和 Qwen2.5-3B（被 Qwen3.5 替代）
remove_ids = {
    'qwen3-0.6b', 'qwen3-1.7b', 'qwen3-4b',
    'qwen3-vl-2b-thinking', 'qwen3-vl-4b-thinking',
    'qwen2.5-3b'
}

kept = []
removed = []
for m in models:
    if m['id'] in remove_ids:
        removed.append(m['id'])
    else:
        kept.append(m)

# 新增最新模型
new_models = [
    {
        "id": "qwen3.5-0.8b",
        "name": "Qwen3.5-0.8B",
        "description": "阿里最新0.8B，原生多模态，Gated DeltaNet架构，Apache2.0",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen3.5-0.8B-Instruct-GGUF/resolve/main/Qwen3.5-0.8B-Instruct-Q4_K_M.gguf",
        "sizeBytes": 559000000,
        "quantization": "Q4_K_M",
        "contextLength": 262144,
        "minRamMB": 1536,
        "recommendedGpuLayers": 2,
        "architecture": "Qwen3.5",
        "parameters": "0.8B",
        "memory": "1-2GB",
        "useCases": "超轻量对话、端侧多模态、低资源设备",
        "sha256": "",
        "backupUrl": "https://modelscope.cn/models/Qwen/Qwen3.5-0.8B-Instruct-GGUF/resolve/master/Qwen3.5-0.8B-Instruct-Q4_K_M.gguf"
    },
    {
        "id": "qwen3.5-2b",
        "name": "Qwen3.5-2B",
        "description": "阿里最新2B，原生多模态，性能超越上一代4B",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen3.5-2B-Instruct-GGUF/resolve/main/Qwen3.5-2B-Instruct-Q4_K_M.gguf",
        "sizeBytes": 1300000000,
        "quantization": "Q4_K_M",
        "contextLength": 262144,
        "minRamMB": 3072,
        "recommendedGpuLayers": 4,
        "architecture": "Qwen3.5",
        "parameters": "2B",
        "memory": "3-4GB",
        "useCases": "均衡对话、端侧多模态、Agent工具调用",
        "sha256": "",
        "backupUrl": "https://modelscope.cn/models/Qwen/Qwen3.5-2B-Instruct-GGUF/resolve/master/Qwen3.5-2B-Instruct-Q4_K_M.gguf"
    },
    {
        "id": "qwen3.5-4b",
        "name": "Qwen3.5-4B（推荐）",
        "description": "阿里最新4B，原生多模态，9B级别性能，本地Agent首选",
        "downloadUrl": "https://hf-mirror.com/Qwen/Qwen3.5-4B-Instruct-GGUF/resolve/main/Qwen3.5-4B-Instruct-Q4_K_M.gguf",
        "sizeBytes": 2400000000,
        "quantization": "Q4_K_M",
        "contextLength": 262144,
        "minRamMB": 5120,
        "recommendedGpuLayers": 8,
        "architecture": "Qwen3.5",
        "parameters": "4B",
        "memory": "5-6GB",
        "useCases": "深度推理、复杂任务、Agent工具调用、多模态",
        "sha256": "",
        "backupUrl": "https://modelscope.cn/models/Qwen/Qwen3.5-4B-Instruct-GGUF/resolve/master/Qwen3.5-4B-Instruct-Q4_K_M.gguf"
    },
    {
        "id": "minicpm5-1b",
        "name": "MiniCPM5-1B",
        "description": "面壁智能最新1B，AA-Index17.9分，全球2B以下最强文本模型，INT4仅0.5GB",
        "downloadUrl": "https://hf-mirror.com/openbmb/MiniCPM5-1B-GGUF/resolve/main/MiniCPM5-1B-Q4_K_M.gguf",
        "sizeBytes": 500000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 1024,
        "recommendedGpuLayers": 2,
        "architecture": "MiniCPM5",
        "parameters": "1B",
        "memory": "1GB",
        "useCases": "超轻量对话、端侧AI、桌宠、低延迟任务",
        "sha256": "",
        "backupUrl": ""
    },
    {
        "id": "minicpm-v4.6",
        "name": "MiniCPM-V 4.6",
        "description": "面壁智能最新多模态1.3B，图像+视频理解，超越Gemma4-E2B，效率优于Qwen3.5-0.8B",
        "downloadUrl": "https://hf-mirror.com/openbmb/MiniCPM-V-4.6-GGUF/resolve/main/MiniCPM-V-4.6-Q4_K_M.gguf",
        "sizeBytes": 800000000,
        "quantization": "Q4_K_M",
        "contextLength": 32768,
        "minRamMB": 2048,
        "recommendedGpuLayers": 4,
        "architecture": "MiniCPM-V",
        "parameters": "1.3B",
        "memory": "2GB",
        "useCases": "图像理解、视频理解、端侧多模态",
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
print('新增最新模型:', added)
print('当前共', len(kept), '个模型')
print('--- 按厂商分类 ---')
vendors = {}
for m in kept:
    arch = m.get('architecture', 'Unknown')
    if arch not in vendors:
        vendors[arch] = []
    vendors[arch].append(m['id'] + '(' + m.get('parameters', '') + ')')
for v, ids in vendors.items():
    print(f'  {v}: {", ".join(ids)}')
