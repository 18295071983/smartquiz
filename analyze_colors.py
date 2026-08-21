from PIL import Image

im = Image.open(r"D:\qzq\smartquiz\bug_screenshot.jpg").convert("RGB")

def analyze(name, box):
    r = im.crop(box)
    px = list(r.getdata())
    n = len(px)
    # colorfulness: mean max channel spread
    spread = [max(p) - min(p) for p in px]
    colored = [s for s in spread if s > 40]
    # average color of non-white pixels
    nonwhite = [p for p in px if max(p) < 235]
    if nonwhite:
        avg = tuple(sum(c[i] for c in nonwhite) // len(nonwhite) for i in range(3))
    else:
        avg = (255, 255, 255)
    print(f"{name}: box={box} n={n} colored_ratio={len(colored)/n:.2f} avg_nonwhite_rgb={avg}")

# title row area - look for "Agent 执行汇总" title text and status
analyze("title_row", (40, 1770, 1220, 1900))
analyze("title_left", (40, 1780, 500, 1870))
analyze("title_center", (300, 1780, 700, 1870))
analyze("status_emoji", (1000, 1815, 1055, 1870))   # the "0" glyph before 成功
analyze("oao_text", (100, 1830, 150, 1865))          # "0.0"/OAO
analyze("stat_row_icons", (0, 1950, 1220, 2020))     # stat icons row
analyze("icon_col1", (110, 1950, 200, 2020))
analyze("icon_col2", (400, 1950, 520, 2020))
analyze("icon_col3", (700, 1950, 830, 2020))
analyze("icon_col4", (1010, 1950, 1130, 2020))
analyze("val_row", (0, 2020, 1220, 2090))            # stat values row
analyze("label_row", (0, 2090, 1220, 2170))          # stat labels row
# check for "Agent 执行汇总" anywhere in card top half
analyze("card_top_half", (0, 1750, 1220, 1990))

# also the top card emojis
analyze("top_emoji_backtick", (100, 1370, 150, 1420))  # "`" before 调用工具
analyze("top_emoji_brain", (380, 1035, 430, 1080))     # "．" between 次 and 思考
analyze("top_emoji_hong", (405, 1165, 460, 1205))      # "弘" in 缓存命中率
analyze("top_emoji_copy0", (185, 1585, 240, 1640))     # "0" between 复制/朗读
analyze("chip_dialogue", (75, 2255, 135, 2315))        # "．" before 对话
analyze("chip_think", (325, 2255, 385, 2315))          # "．" before 深度思考
