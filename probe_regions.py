from PIL import Image

def probe(img_path, origin, scale, label, box):
    im = Image.open(img_path).convert("RGB")
    ox, oy = origin
    b = ((box[0]-ox)*scale, (box[1]-oy)*scale, (box[2]-ox)*scale, (box[3]-oy)*scale)
    r = im.crop(b)
    px = list(r.getdata())
    n = len(px) or 1
    bright = sum(1 for p in px if max(p) > 150) / n
    colorful = sum(1 for p in px if (max(p)-min(p)) > 50) / n
    mid = sum(sum(p) for p in px) / (3*n)
    print(f"{label}: mean={mid:.0f} light={bright:.2f} colorful={colorful:.2f}")

ml = r"D:\qzq\smartquiz\ocr_regions\region_mid_left.png"   # 3x of (0,1750,1220,2220)
sb = r"D:\qzq\smartquiz\ocr_regions\strip_s_bottom_card.png"  # 4x of (0,1750,1220,2220)

# title center zone
probe(ml, (0,1750), 3, "title_center_500_900", (500,1780,900,1870))
probe(ml, (0,1750), 3, "title_full_40_1160", (40,1780,1160,1870))
# stat icons row (icon 16sp ~44px) - look around y1950-2040 at col centers
for col, xc in [(1,152),(2,457),(3,762),(4,1067)]:
    probe(ml, (0,1750), 3, f"icon_col{col}", (xc-55,1940,xc+55,2040))
# value row y2020-2080 across columns
for col, xc in [(1,152),(2,457),(3,762),(4,1067)]:
    probe(ml, (0,1750), 3, f"val_col{col}", (xc-80,2015,xc+80,2075))
# label row y2090-2160
for col, xc in [(1,152),(2,457),(3,762),(4,1067)]:
    probe(ml, (0,1750), 3, f"label_col{col}", (xc-80,2085,xc+80,2160))
# the "0.0" glyph - bigger box
probe(ml, (0,1750), 3, "oao_big", (95,1825,180,1875))
probe(ml, (0,1750), 3, "between_oao_and_status", (200,1800,950,1880))
