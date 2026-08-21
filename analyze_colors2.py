from PIL import Image

def analyze(img_path, scale, label, box):
    """box in ORIGINAL image coords; scale = crop upscale factor"""
    im = Image.open(img_path).convert("RGB")
    b = tuple(int(v * scale) for v in box)
    r = im.crop(b)
    px = list(r.getdata())
    n = len(px) or 1
    spread = [max(p) - min(p) for p in px]
    colored = [s for s in spread if s > 40]
    nonwhite = [p for p in px if max(p) < 235]
    avg = tuple(sum(c[i] for c in nonwhite) // len(nonwhite) for i in range(3)) if nonwhite else (255,255,255)
    print(f"{label}: colored_ratio={len(colored)/n:.2f} avg_nonwhite={avg}")

# region_mid_left.png = 3x crop of (0,1750,1220,2220)
ml = r"D:\qzq\smartquiz\ocr_regions\region_mid_left.png"
analyze(ml, 3, "oao_text(107,1837,141,1853)", (107,1837,141,1853))
analyze(ml, 3, "status_glyph(1007,1827,1047,1867)", (1007,1827,1047,1867))
analyze(ml, 3, "chenggong(1062,1830,1160,1862)", (1062,1830,1160,1862))
analyze(ml, 3, "dur29787217m(106,2033,299,2062)", (106,2033,299,2062))
analyze(ml, 3, "token_label(961,2081,1035,2103)", (961,2081,1035,2103))
analyze(ml, 3, "haoshi_label(193,2131,247,2157)", (193,2131,247,2157))
analyze(ml, 3, "card_bg(600,1800,700,2150)", (600,1800,700,2150))
# strip_s_rowA.png = 4x crop of (0,1770,1220,1910)
sa = r"D:\qzq\smartquiz\ocr_regions\strip_s_rowA.png"
analyze(sa, 4, "rowA_left(107,1837,141,1853)", (107,1837,141,1853))
analyze(sa, 4, "rowA_status(1007,1827,1047,1867)", (1007,1827,1047,1867))
analyze(sa, 4, "rowA_bg(500,1780,900,1900)", (500,1780,900,1900))
# strip_s_bottom_card.png = 4x crop of (0,1750,1220,2220)
sb = r"D:\qzq\smartquiz\ocr_regions\strip_s_bottom_card.png"
analyze(sb, 4, "bottom_whole_bg(0,1790,1220,1890)", (0,1790,1220,1890))
