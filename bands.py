from PIL import Image, ImageOps

ocr = r"D:\qzq\smartquiz\ocr_regions"
sb = Image.open(ocr + r"\strip_s_bottom_card.png").convert("L")  # 4x of (0,1750,1220,2220)

bands = [
    ("b_title",   (0, 1750, 1220, 1940)),
    ("b_stat",    (0, 1940, 1220, 2090)),
    ("b_label",   (0, 2060, 1220, 2200)),
]
for name, box in bands:
    b = sb.crop(((box[0]-0)*4, (box[1]-1750)*4, (box[2]-0)*4, (box[3]-1750)*4))
    b2 = b.resize((b.width*2, b.height*2), Image.LANCZOS)
    ImageOps.autocontrast(b2).save(ocr + f"\\{name}_8x.png")
    print(name, b2.size)

# also full-width title band at 10x from region_full (1x)
f = Image.open(ocr + r"\region_full.png").convert("L")
b = f.crop((0, 1760, 1220, 1920))
b3 = b.resize((b.width*3, b.height*3), Image.LANCZOS)
ImageOps.autocontrast(b3).save(ocr + r"\b_title_3x_from_full.png")
print("title10x", b3.size)
