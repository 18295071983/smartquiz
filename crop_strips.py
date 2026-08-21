from PIL import Image

im = Image.open(r"D:\qzq\smartquiz\bug_screenshot.jpg").convert("RGB")
W, H = im.size

strips = [
    ("s_rowA",    (0, 1770, 1220, 1910), 4),
    ("s_rowA_left", (0, 1770, 500, 1910), 6),
    ("s_rowA_right", (880, 1770, 1220, 1910), 6),
    ("s_rowB",    (0, 1990, 1220, 2120), 4),
    ("s_rowC",    (0, 2060, 1220, 2170), 4),
    ("s_rowD",    (0, 2100, 1220, 2220), 4),
    ("s_bottom_card", (0, 1750, 1220, 2220), 4),
    ("card_area2", (0, 1350, 1220, 1760), 3),
]
outdir = r"D:\qzq\smartquiz\ocr_regions"
for name, box, scale in strips:
    crop = im.crop(box)
    crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
    p = outdir + "\\" + f"strip_{name}.png"
    crop.save(p)
    print("saved", p, crop.size)
