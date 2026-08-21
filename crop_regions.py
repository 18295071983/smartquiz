import sys
from PIL import Image

src = r"D:\qzq\smartquiz\bug_screenshot.jpg"
im = Image.open(src).convert("RGB")
W, H = im.size
print("size", W, H)

# regions: name, (left, top, right, bottom), scale
regions = [
    ("full",        (0, 0, W, H), 1),
    ("top_msg",     (0, 340, W, 1760), 1),
    ("card_area",   (0, 1350, W, 1740), 2),
    ("mid_right",   (900, 1750, W, 2220), 3),
    ("mid_left",    (0, 1750, W, 2220), 3),
    ("bottom",      (0, 2200, W, H), 1),
    ("mode_chips",  (0, 2200, W, 2440), 2),
    ("input_area",  (0, 2400, W, H), 1),
]
outdir = r"D:\qzq\smartquiz\ocr_regions"
import os
os.makedirs(outdir, exist_ok=True)

for name, box, scale in regions:
    crop = im.crop(box)
    if scale != 1:
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
    p = os.path.join(outdir, f"region_{name}.png")
    crop.save(p)
    print("saved", p, crop.size)
