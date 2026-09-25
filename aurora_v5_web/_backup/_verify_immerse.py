# -*- coding: utf-8 -*-
"""临时验证：极光时钟沉浸模式天气显示"""
import sys, time
from playwright.sync_api import sync_playwright

URL = "file:///D:/qzq/smartquiz/aurora_v5_web/index.html"
OUT = "D:/qzq/smartquiz/aurora_v5_web/_shots/"

def shoot(page, name):
    page.screenshot(path=OUT + name, full_page=False)
    print("saved", name)

with sync_playwright() as p:
    try:
        browser = p.chromium.launch()
    except Exception as e:
        print("chromium launch fail:", e)
        sys.exit(1)
    ctx = browser.new_context(viewport={"width": 390, "height": 844})
    page = ctx.new_page()
    page.goto(URL)
    page.wait_for_timeout(4000)  # 等天气加载

    # 竖屏沉浸
    page.evaluate("document.body.classList.add('immerse')")
    page.wait_for_timeout(800)
    shoot(page, "immerse_portrait.png")

    # 横屏沉浸（模拟旋转：加 landscape 类）
    page.evaluate("document.body.classList.remove('immerse'); document.body.classList.add('landscape'); document.body.classList.add('immerse')")
    page.wait_for_timeout(800)
    shoot(page, "immerse_landscape.png")

    browser.close()
