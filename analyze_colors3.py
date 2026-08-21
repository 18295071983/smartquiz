from PIL import Image

def analyze(img_path, origin, scale, label, box):
    """box in ORIGINAL image coords; origin=(ox,oy) of the crop; scale=upscale"""
    im = Image.open(img_path).convert("RGB")
    ox, oy = origin
    b = ((box[0]-ox)*scale, (box[1]-oy)*scale, (box[2]-ox)*scale, (box[3]-oy)*scale)
    r = im.crop(b)
    px = list(r.getdata())
    n = len(px) or 1
    bright = sum(1 for p in px if max(p) > 150) / n
    colorful = sum(1 for p in px if (max(p)-min(p)) > 50) / n
    mid = sum(sum(p) for p in px) / (3*n)
    # dominant non-background color: average of pixels farthest from median
    print(f"{label}: mean_brightness={mid:.0f} light_ratio={bright:.2f} colorful_ratio={colorful:.2f}")

# region_mid_left.png = 3x crop of (0,1750,1220,2220)
ml = r"D:\qzq\smartquiz\ocr_regions\region_mid_left.png"
analyze(ml, (0,1750), 3, "oao_text", (107,1837,141,1853))
analyze(ml, (0,1750), 3, "status_glyph", (1007,1827,1047,1867))
analyze(ml, (0,1750), 3, "chenggong_text", (1062,1830,1160,1862))
analyze(ml, (0,1750), 3, "dur_29787217m", (106,2033,299,2062))
analyze(ml, (0,1750), 3, "token_label", (961,2081,1035,2103))
analyze(ml, (0,1750), 3, "haoshi_label", (193,2131,247,2157))
analyze(ml, (0,1750), 3, "card_bg_mid", (600,1800,700,2150))
analyze(ml, (0,1750), 3, "title_zone", (60,1780,500,1870))

# strip_s_rowA.png = 4x crop of (0,1770,1220,1910)
sa = r"D:\qzq\smartquiz\ocr_regions\strip_s_rowA.png"
analyze(sa, (0,1770), 4, "rowA_oao", (107,1837,141,1853))
analyze(sa, (0,1770), 4, "rowA_status", (1007,1827,1047,1867))
analyze(sa, (0,1770), 4, "rowA_bg", (300,1780,900,1900))

# strip_s_bottom_card.png = 4x crop of (0,1750,1220,2220)
sb = r"D:\qzq\smartquiz\ocr_regions\strip_s_bottom_card.png"
analyze(sb, (0,1750), 4, "bottom_bg_row1", (60,1780,500,1870))
analyze(sb, (0,1750), 4, "bottom_bg_row2", (300,1950,900,2160))
