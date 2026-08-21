from PIL import Image, ImageOps, ImageEnhance

src = r"D:\qzq\smartquiz\ocr_regions\strip_s_bottom_card.png"  # 4x of (0,1750,1220,2220)
out = r"D:\qzq\smartquiz\ocr_regions"

im = Image.open(src).convert("L")
w, h = im.size
print("src", im.size)

# 1. autocontrast grayscale
a = ImageOps.autocontrast(im)
a.save(out + r"\enh_bottom_auto.png")

# 2. inverted autocontrast
i = ImageOps.invert(a)
i.save(out + r"\enh_bottom_invert.png")

# 3. binarize at adaptive-ish threshold (Otsu approx via histogram split at mean)
import statistics
vals = list(im.getdata())
m = statistics.mean(vals)
bw = im.point(lambda p: 255 if p > m else 0)
bw.save(out + r"\enh_bottom_bw.png")

# 4. bigger scale: upscale 4x image by 2 more -> 8x total
big = im.resize((w*2, h*2), Image.LANCZOS)
bga = ImageOps.autocontrast(big)
bga.save(out + r"\enh_bottom_8x_auto.png")

# 5. the "0.0" glyph region: original coords (95,1825,180,1875) -> in 4x crop: ((95)*4, (1825-1750)*4, 180*4, (1875-1750)*4)
g = im.crop((95*4, (1825-1750)*4, 180*4, (1875-1750)*4))
print("glyph crop", g.size)
g2 = g.resize((g.width*3, g.height*3), Image.LANCZOS)
ImageOps.autocontrast(g2).save(out + r"\enh_glyph_0_0.png")
ImageOps.invert(ImageOps.autocontrast(g2)).save(out + r"\enh_glyph_0_0_inv.png")
g3 = g.resize((g.width*6, g.height*6), Image.LANCZOS)
ImageOps.autocontrast(g3).save(out + r"\enh_glyph_0_0_6x.png")

# 6. status emoji region (1000,1820,1060,1875)
g = im.crop((1000*4, (1820-1750)*4, 1060*4, (1875-1750)*4))
g2 = g.resize((g.width*3, g.height*3), Image.LANCZOS)
g2.save(out + r"\enh_status_emoji.png")

# 7. title row full strip (40,1770)-(1220,1910) 6x
g = im.crop((40*4, (1770-1750)*4, 1220*4, (1910-1750)*4))
g6 = g.resize((g.width*2, g.height*2), Image.LANCZOS)
ImageOps.autocontrast(g6).save(out + r"\enh_title_row.png")

# 8. stat rows (0,1990)-(1220,2180) 6x
g = im.crop((0*4, (1990-1750)*4, 1220*4, (2180-1750)*4))
g6 = g.resize((g.width*2, g.height*2), Image.LANCZOS)
ImageOps.autocontrast(g6).save(out + r"\enh_stat_rows.png")
print("done")
