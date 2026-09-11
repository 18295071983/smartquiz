# -*- coding: utf-8 -*-
"""拷贝 material-color-utilities 官方 Java 核心类到 smartquiz，并改包名/去 errorprone 注解。"""
import os, re, shutil

SRC = r"D:\qzq\smartquiz\tools\mcu-src\material-color-utilities-main\java"
DST_BASE = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\theme\mcu"
PREFIX = "com.oilquiz.app.theme.mcu"

FILES = [
    "utils/MathUtils.java",
    "utils/ColorUtils.java",
    "hct/Cam16.java",
    "hct/Hct.java",
    "hct/HctSolver.java",
    "hct/ViewingConditions.java",
    "palettes/CorePalette.java",
    "palettes/TonalPalette.java",
    "scheme/Scheme.java",
]

def rewrite(content: str, pkg: str) -> str:
    # 包名
    content = re.sub(r"^package \w+;", "package %s.%s;" % (PREFIX, pkg), content, count=1, flags=re.M)
    # 内部 import：utils. / hct. / palettes.
    content = content.replace("import utils.", "import %s.utils." % PREFIX)
    content = content.replace("import hct.", "import %s.hct." % PREFIX)
    content = content.replace("import palettes.", "import %s.palettes." % PREFIX)
    # 删除 errorprone import 与注解
    content = re.sub(r"^import com\.google\.errorprone[^\n]*\n", "", content, flags=re.M)
    content = re.sub(r"^@(CanIgnoreReturnValue|CheckReturnValue)\n", "", content, flags=re.M)
    # 删除 @Deprecated（保留无妨，但去掉以免误读）
    content = content.replace("@Deprecated\n", "")
    return content

for rel in FILES:
    src = os.path.join(SRC, rel)
    dst = os.path.join(DST_BASE, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, "r", encoding="utf-8") as f:
        content = f.read()
    pkg = os.path.dirname(rel).replace("\\", "/")
    content = rewrite(content, pkg)
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    print("OK", rel)

print("done, total =", len(FILES))
