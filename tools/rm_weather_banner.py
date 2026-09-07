# -*- coding: utf-8 -*-
import io

p = r'src\main\res\layout\activity_ai_chat.xml'
with io.open(p, encoding='utf-8') as f:
    lines = f.readlines()

# 直接按行号定位：weather_banner 的 <LinearLayout 起始行，和其结束 </LinearLayout> 行
# banner id 在 177 行（0-based 176），其所属 <LinearLayout 起始在 176（0-based 175）
# 结束：weather_detail_container 关闭后（0-based 362 行附近）
start = 175
end = 363
print('removing lines', start + 1, 'to', end)
blk = '\n'.join(lines[start:end])
assert 'weather_banner' in blk and 'weather_detail_container' in blk, 'block missing key ids'
assert 'weather_pressure' in blk, 'missing tail id'

new = lines[:start] + lines[end:]
with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.writelines(new)
print('removed', end - start, 'lines')
