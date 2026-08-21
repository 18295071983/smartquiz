from PIL import Image, ImageOps

ocr = r"D:\qzq\smartquiz\ocr_regions"

# A. "0.0" glyph: from strip_s_bottom_card.png (4x of (0,1750,1220,2220))
im = Image.open(ocr + r"\strip_s_bottom_card.png").convert("L")
g = im.crop((95*4, (1825-1750)*4, 190*4, (1880-1750)*4))
g6 = g.resize((g.width*6, g.height*6), Image.LANCZOS)
ImageOps.autocontrast(g6).save(ocr + r"\glyph_00_12x.png")
print("glyph saved", g6.size)

# B. top token block: crop from region_top_msg.png (1x of (0,340,1220,1760)) -> original y 360..620
tm = Image.open(ocr + r"\region_top_msg.png").convert("RGB")
b = tm.crop((140, 360-340, 1220, 620-340))
b4 = b.resize((b.width*4, b.height*4), Image.LANCZOS)
b4.save(ocr + r"\top_token_block_4x.png")
print("top block saved", b4.size)

# C. chip glyph colors: region_mode_chips.png = 2x of (0,2200,1220,2440)
mc = Image.open(ocr + r"\region_mode_chips.png").convert("RGB")
def probe(img, ox, oy, sc, label, box):
    b = ((box[0]-ox)*sc, (box[1]-oy)*sc, (box[2]-ox)*sc, (box[3]-oy)*sc)
    r = img.crop(b); px = list(r.getdata()); n = len(px) or 1
    bright = sum(1 for p in px if max(p) > 150)/n
    colorful = sum(1 for p in px if (max(p)-min(p)) > 50)/n
    print(f"{label}: light={bright:.2f} colorful={colorful:.2f}")
probe(mc, (0,2200), 2, "chip1_glyph(82,2265,125,2305)", (82,2265,125,2305))
probe(mc, (0,2200), 2, "chip2_glyph(330,2268,375,2303)", (330,2268,375,2303))
probe(mc, (0,2200), 2, "chip2_text(392,2265,551,2303)", (392,2265,551,2303))
probe(mc, (0,2200), 2, "chip_row_after(560,2255,1220,2320)", (560,2255,1220,2320))

# D. horizontal scan of bottom card to find borders/divider: sample row y=1870..1990 (gap) and y=2010 (value row)
sb = Image.open(ocr + r"\strip_s_bottom_card.png").convert("L")
def scan_y(label, y):
    row = [sb.getpixel((x*4, (y-1750)*4)) for x in range(0, 1220, 10)]
    bright = sum(1 for v in row if v > 150)
    print(f"scan {label}: bright_px={bright}/122")
scan_y("y1870", 1870)
scan_y("y1900", 1900)
scan_y("y1950", 1950)
scan_y("y1980", 1980)
scan_y("y2010", 2010)
scan_y("y2070", 2070)
scan_y("y2120", 2120)
scan_y("y2180", 2180)
scan_y("y2200", 2200)
