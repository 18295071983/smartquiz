import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])

for m in models:
    # 修复 MiniCPM-V 4.6
    if m['id'] == 'minicpm-v4.6':
        m['mmprojUrl'] = 'https://hf-mirror.com/openbmb/MiniCPM-V-4.6-gguf/resolve/main/mmproj-MiniCPM-V-4.6-F16.gguf'
        m['backupUrl'] = 'https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/MiniCPM-V-4_6-Q4_K_M.gguf'
        print('修复 minicpm-v4.6: mmprojUrl 和 backupUrl')
    
    # 修复 qwen3.8-4b-distill (repo 改为 Ma7ee7)
    if m['id'] == 'qwen3.8-4b-distill':
        m['downloadUrl'] = 'https://hf-mirror.com/Ma7ee7/Qwen3.8_4B_Distilled_GGUF/resolve/main/Qwen3.8_4B_Distilled-Q4_K_M.gguf'
        print('修复 qwen3.8-4b-distill: repo 改为 Ma7ee7')

data['presets'] = models
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('完成')
