import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])

for m in models:
    if m['id'] == 'minicpm-v4.6':
        m['downloadUrl'] = 'https://hf-mirror.com/openbmb/MiniCPM-V-4.6-gguf/resolve/main/MiniCPM-V-4_6-Q4_K_M.gguf'
        m['backupUrl'] = 'https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf/resolve/master/MiniCPM-V-4_6-Q4_K_M.gguf'
        m['mmprojUrl'] = 'https://hf-mirror.com/openbmb/MiniCPM-V-4.6-gguf/resolve/main/mmproj-MiniCPM-V-4_6-f16.gguf'
        m['mmprojSizeMB'] = 300
        print('修正:', m['id'])
        print('  downloadUrl:', m['downloadUrl'])
        print('  mmprojUrl:', m.get('mmprojUrl', ''))

data['presets'] = models
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('完成')
