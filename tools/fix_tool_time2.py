# -*- coding: utf-8 -*-
# ToolResultInterpreter.java: 补打网页 pub 相对时间标注
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\ToolResultInterpreter.java"
s = io.open(path, "r", encoding="utf-8").read()
old = '''        if (author != null || pub != null) {
            sb.append("\\u270d\\ufe0f ");
            if (author != null) sb.append(author).append("  ");
            if (pub != null) sb.append(pub);
            sb.append("\\n");
        }'''
new = '''        if (author != null || pub != null) {
            sb.append("\\u270d\\ufe0f ");
            if (author != null) sb.append(author).append("  ");
            if (pub != null) {
                sb.append(pub);
                String rel = formatRelativeDate(pub);
                if (rel != null && !rel.isEmpty()) {
                    sb.append(" ").append(rel);
                }
            }
            sb.append("\\n");
        }'''
# 用 \u 转义实际字符构造锚点
old = old.replace("\\u270d\\ufe0f", "\u270d\ufe0f")
new = new.replace("\\u270d\\ufe0f", "\u270d\ufe0f")
# old/new 里 "\\n" 应保持为字面 \n（Java 转义）
c = s.count(old)
if c != 1:
    print("FAIL count=%d" % c)
    import re
    m = re.search(r'if \(author != null \|\| pub != null\)', s)
    print("anchor found at:", m.start() if m else "NOT FOUND")
    sys.exit(1)
s = s.replace(old, new)
io.open(path, "w", encoding="utf-8", newline="\n").write(s)
print("WEBPAGE PUB OK")
