# -*- coding: utf-8 -*-
"""Edge headless: 打开城市选择器，输入搜索词，验证搜索框 + 结果列表渲染"""
import subprocess, os, time, json

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
HTML = "file:///D:/qzq/smartquiz/aurora_v5_web/index.html"
OUT = r"D:\qzq\smartquiz\aurora_v5_web\_shots\picker_search.png"
USER_DATA = r"D:\qzq\smartquiz\aurora_v5_web\_shots\_tmp_profile"

# 注入脚本：等待加载 → 调用 WEATHER 内部 pickCity 打开 → 填搜索词 → 截图由 --screenshot 捕获
inject = r"""
window.__test = {};
(function(){
  var tries = 0;
  var iv = setInterval(function(){
    tries++;
    var w = window.WEATHER;
    var cap = document.querySelector('#cmWeather');
    if ((w || tries > 40) && cap) {
      clearInterval(iv);
      try {
        cap.click();                       // 打开天气胶囊 → 城市选择器
      } catch(e) { window.__test.err = String(e); }
      setTimeout(function(){
        var q = document.getElementById('wxQ');
        if (q) {
          q.value = '固原';
          q.dispatchEvent(new Event('input', {bubbles:true}));
        }
        window.__test.done = true;
      }, 600);
    }
  }, 200);
})();
"""

# 用 --dump-dom 或截图前先跑注入：Edge headless 的 --run-all-compositor-stages-before-draw 会等
# 用 virtual-time-budget 让异步跑完
cmd = [EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
       "--window-size=492,1000",
       "--virtual-time-budget=9000",
       "--user-data-dir=" + USER_DATA,
       "--screenshot=" + OUT,
       "--disable-features=Translate",
       HTML]
# Edge 不支持注入脚本参数，改用法：先在控制台执行不行 → 使用临时 html 包装
print("Edge CLI 不支持注入，改用临时包装页")
