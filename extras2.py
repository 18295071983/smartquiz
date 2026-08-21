from PIL import Image

ocr = r"D:\qzq\smartquiz\ocr_regions"
mc = Image.open(ocr + r"\region_mode_chips.png").convert("RGB")
sb = Image.open(ocr + r"\strip_s_bottom_card.png").convert("L")

def probe(img, origin, scale, label, box):
    ox, oy = origin
    b = ((box[0]-ox)*scale, (box[1]-oy)*scale, (box[2]-ox)*scale, (box[3]-oy)*scale)
    r = img.crop(b); px = list(r.getdata()); n = len(px) or 1
    bright = sum(1 for p in px if max(p) > 150)/n
    colorful = sum(1 for p in px if (max(p)-min(p)) > 50)/n
    print(f"{label}: light={bright:.2f} colorful={colorful:.2f}")

probe(mc, (0,2200), 2, "chip1_glyph", (82,2265,125,2305))
probe(mc, (0,2200), 2, "chip2_glyph", (330,2268,375,2303))
probe(mc, (0,2200), 2, "chip2_text", (392,2265,551,2303))
probe(mc, (0,2200), 2, "chip_row_after", (560,2255,1220,2320))
probe(mc, (0,2200), 2, "chip_row_full", (0,2240,1220,2340))

def scan_y(label, y):
    row = [sb.getpixel((x*4, (y-1750)*4)) for x in range(0, 1220, 10)]
    bright = sum(1 for v in row if v > 150)
    print(f"scan {label}: bright={bright}/122")

for y in (1800, 1850, 1870, 1900, 1950, 1980, 2010, 2050, 2070, 2100, 2120, 2160, 2180, 2200, 2215):
    scan_y(f"y{y}", y)
