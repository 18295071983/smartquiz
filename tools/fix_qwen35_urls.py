import json

with open('src/main/assets/models_presets.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
models = data.get('presets', [])

# 修正 Qwen3.5 系列的链接
for m in models:
    if m['id'] == 'qwen3.5-0.8b':
        m['downloadUrl'] = 'https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf'
        m['backupUrl'] = 'https://modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/master/Qwen3.5-0.8B-Q4_K_M.gguf'
        print('修正:', m['id'])
    elif m['id'] == 'qwen3.5-2b':
        m['downloadUrl'] = 'https://hf-mirror.com/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf'
        m['backupUrl'] = 'https://modelscope.cn/models/unsloth/Qwen3.5-2B-GGUF/resolve/master/Qwen3.5-2B-Q4_K_M.gguf'
        print('修正:', m['id'])
    elif m['id'] == 'qwen3.5-4b':
        m['downloadUrl'] = 'https://hf-mirror.com/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf'
        m['backupUrl'] = 'https://modelscope.cn/models/unsloth/Qwen3.5-4B-GGUF/resolve/master/Qwen3.5-4B-Q4_K_M.gguf'
        print('修正:', m['id'])

data['presets'] = models
with open('src/main/assets/models_presets.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print('完成，当前模型:')
for m in models:
    print(' -', m['id'], '|', m.get('downloadUrl', '')[:80])
