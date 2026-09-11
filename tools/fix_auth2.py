# -*- coding: utf-8 -*-
import io, re

p = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\OnlineInferenceService.java'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()

# 模式：...ProviderConfigManager.get()\n<缩进>applyAuth(...)  → 合并为 applyAuth(...)（get() 行吸收）
pat = re.compile(
    r'com\.oilquiz\.app\.ai\.model\.ProviderConfigManager\.get\(\)\n(\s*)applyAuth\('
)
s2, n = pat.subn(lambda m: m.group(1) + 'applyAuth(', s)
with io.open(p, 'w', encoding='utf-8', newline='') as f:
    f.write(s2)
print('合并修复', n, '处')
