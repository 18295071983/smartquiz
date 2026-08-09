# -*- coding: utf-8 -*-
import re
import io
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

xml = io.open(r'd:\qzq\smartquiz\ui_check.xml', encoding='utf-8').read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t = m.group(1)
    if t:
        x1, y1, x2, y2 = int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
        cx = (x1 + x2) // 2
        cy = (y1 + y2) // 2
        print('%s | %d,%d' % (t, cx, cy))
