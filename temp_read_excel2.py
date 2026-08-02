import zipfile
import xml.etree.ElementTree as ET

path = r"C:\Users\xiaocong\Desktop\全员综合复审题库-气化.xlsx"
zipf = zipfile.ZipFile(path, 'r')

ns = {'main': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}

# 读取共享字符串
shared = []
try:
    sxml = zipf.read('xl/sharedStrings.xml')
    root = ET.fromstring(sxml)
    for si in root.findall('main:si', ns):
        txt = ''
        t = si.find('main:t', ns)
        if t is not None and t.text:
            txt = t.text
        else:
            for r in si.findall('main:r', ns):
                rt = r.find('main:t', ns)
                if rt is not None and rt.text:
                    txt += rt.text
        shared.append(txt)
    print(f"SharedStrings count: {len(shared)}")
except Exception as e:
    print(f"sharedStrings error: {e}")

# 读取所有 sheet
for si in range(1, 4):
    try:
        sheetxml = zipf.read(f'xl/worksheets/sheet{si}.xml')
        root = ET.fromstring(sheetxml)
        rows = root.findall('.//main:row', ns)
        print(f"\n===== Sheet{si}: {len(rows)} rows =====")
        for ri, row in enumerate(rows):
            cells = row.findall('main:c', ns)
            line = f"Row {row.get('r')}: "
            for c in cells:
                ref = c.get('r')
                ctype = c.get('t')
                v = c.find('main:v', ns)
                val = ''
                if v is not None and v.text:
                    if ctype == 's':
                        idx = int(v.text)
                        val = shared[idx] if idx < len(shared) else f'?{idx}'
                    else:
                        val = v.text
                # 只打印前 80 字符
                val_short = val[:80] if val else ''
                line += f"[{ref}]{val_short} | "
            # 只打印前 200 字符
            print(line[:200])
        if si >= 2 and len(rows) > 5:
            # 打印更多行
            pass
    except Exception as e:
        print(f"sheet{si} error: {e}")

zipf.close()
