# -*- coding: utf-8 -*-
# 用缓存里的真实数据模拟界面解析（含刚修复的 PM 2.5 / PM 10）
real = """空气质量:
AQI: 67 (等级2, 良)
指数名称: AQI (CN)
指数代码: cn-mee
首要污染物: O3
健康影响: 空气质量可接受，但某些污染物可能对极少数异常敏感人群健康有较弱影响。
一般人群: 一般人群可正常活动。
敏感人群: 极少数异常敏感人群应减少户外活动。

污染物浓度:
PM 2.5: 27.0 μg/m³ (颗粒物（粒径小于等于2.5µm）)
PM 10: 54.0 μg/m³ (颗粒物（粒径小于等于10µm）)
NO2: 11.0 μg/m³ (二氧化氮)
O3: 173.0 μg/m³ (臭氧)
SO2: 11.0 μg/m³ (二氧化硫)
CO: 0.6 mg/m³ (一氧化碳)"""

def clean_air_value(line):
    idx = line.find(":")
    if idx < 0:
        return "--"
    v = line[idx + 1:].strip()
    v = v.replace(" μg/m³", "").replace("μg/m³", "").replace(" mg/m³", "").replace("mg/m³", "").replace(" ug/m3", "").replace("ug/m3", "").strip()
    paren = v.find("(")
    if paren > 0:
        v = v[:paren].strip()
    return v if v else "--"

def extract_full_name(line):
    paren = line.find("(")
    end = line.find(")")
    if paren > 0 and end > paren:
        return line[paren + 1:end].strip()
    return ""

in_air = False
aqi = "--"; category = "暂无数据"; primary = ""
pm25 = pm10 = no2 = so2 = co = o3 = "--"
pm25n = pm10n = no2n = so2n = con = o3n = ""
health = []
for raw in real.split("\n"):
    line = raw.strip()
    if line.startswith("空气质量:"):
        in_air = True
        continue
    if not in_air:
        continue
    if line.startswith("链接:") or line.startswith("污染物浓度:"):
        continue
    if line.startswith("AQI:"):
        rest = line[4:].strip()
        pi = rest.find("(")
        aqi = rest[:pi].strip() if pi > 0 else rest
        if pi > 0:
            inside = rest[pi:]
            ci = inside.find(",")
            category = inside[ci + 1:-1].strip() if ci > 0 else inside[1:-1].strip()
    elif line.startswith("PM2.5:") or line.startswith("PM2p5:") or line.startswith("PM 2.5:"):
        pm25 = clean_air_value(line); pm25n = extract_full_name(line)
    elif line.startswith("PM10:") or line.startswith("PM 10:"):
        pm10 = clean_air_value(line); pm10n = extract_full_name(line)
    elif line.startswith("NO2:"):
        no2 = clean_air_value(line); no2n = extract_full_name(line)
    elif line.startswith("SO2:"):
        so2 = clean_air_value(line); so2n = extract_full_name(line)
    elif line.startswith("CO:"):
        co = clean_air_value(line); con = extract_full_name(line)
    elif line.startswith("O3:"):
        o3 = clean_air_value(line); o3n = extract_full_name(line)
    elif line.startswith("首要污染物:"):
        primary = line[len("首要污染物:"):].strip()
    elif line.startswith("健康影响:"):
        health.append(line[5:].strip())

print("AQI:", aqi, "| 等级/类别:", category)
print("首要污染物:", primary)
print("PM2.5:", pm25, "|", pm25n)
print("PM10:", pm10, "|", pm10n)
print("NO2:", no2, "|", no2n)
print("SO2:", so2, "|", so2n)
print("CO:", co, "|", con)
print("O3:", o3, "|", o3n)
print("健康影响:", health)
