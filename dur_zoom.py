from PIL import Image, ImageOps

ocr = r"D:\qzq\smartquiz\ocr_regions"
sb = Image.open(ocr + r"\strip_s_bottom_card.png").convert("L")  # 4x of (0,1750,1220,2220)

# value box: orig (95,2020,360,2085) -> 4x strip coords
g = sb.crop((95*4, (2020-1750)*4, 360*4, (2085-1750)*4))
g3 = g.resize((g.width*3, g.height*3), Image.LANCZOS)
ImageOps.autocontrast(g3).save(ocr + r"\dur_value_24x.png")
print("dur box", g.size, "->", g3.size)

# tail check: orig (300,2020,420,2075)
t = sb.crop((300*4, (2020-1750)*4, 420*4, (2075-1750)*4))
t3 = t.resize((t.width*4, t.height*4), Image.LANCZOS)
ImageOps.autocontrast(t3).save(ocr + r"\dur_tail_16x.png")
print("tail", t.size, "->", t3.size)

# also whole value+label col1: orig (60,2000,340,2170)
c = sb.crop((60*4, (2000-1750)*4, 340*4, (2170-1750)*4))
c2 = c.resize((c.width*2, c.height*2), Image.LANCZOS)
ImageOps.autocontrast(c2).save(ocr + r"\col1_full_8x.png")
print("col1", c2.size)
