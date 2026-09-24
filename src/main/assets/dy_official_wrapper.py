# -*- coding: utf-8 -*-
# 官方内核执行包装（内置）：加载 assets 内置的 dy_official_core.py
# → 重定向签名包到内置 → 注入 WebView Cookie 通道 → run → 输出 JSON
import os, sys, json, importlib.util

FILES_DIR = "__FILES_DIR__"          # Java 注入：解包目录（应用内部存储）
WEBVIEW_COOKIE = "__WEBVIEW_COOKIE__"  # Java 注入：AppCookieStore 取到的 douyin.com Cookie 原文（可为空）
COOKIE_FILE = "__COOKIE_FILE__"        # Java 注入：内部私有 cookie 文件（替代外部明文文件）

CORE = os.path.join(FILES_DIR, "dy_official_core.py")
SIGN_SRC = os.path.join(FILES_DIR, "dy_src")
SIGN_PKG = os.path.join(FILES_DIR, "dy_pkg")

for p in (FILES_DIR, SIGN_PKG):
    if p and p not in sys.path:
        sys.path.insert(0, p)

try:
    spec = importlib.util.spec_from_file_location("dy_official_core", CORE)
    K = importlib.util.module_from_spec(spec)
    sys.modules["dy_official_core"] = K
    spec.loader.exec_module(K)
except Exception as e:
    out = {"ok": False, "error": {"code": "IMPORT_FAIL",
                                  "msg": "内核加载失败: %s: %s" % (type(e).__name__, e),
                                  "hint": "内置内核缺失或损坏，将自动回退第三方源"}}
    print(json.dumps(out, ensure_ascii=False))
    raise SystemExit(0)

# 重定向签名包来源到内置（外部工作区即使缺失也能自举）
try:
    K.DY_SRC = SIGN_SRC
    K.DY_PKG = SIGN_PKG
except Exception:
    pass

# 安全加固：cookie 落盘从外部公共目录重定向到应用私有目录（外部明文文件退役）
if COOKIE_FILE:
    try:
        K.COOKIE_FILE = COOKIE_FILE
    except Exception:
        pass

# 注入浏览器 Cookie 通道（内核 MODE=always_fresh 时会回调它取最新登录态）
if WEBVIEW_COOKIE:
    _ck_text = WEBVIEW_COOKIE
    try:
        K.set_cookie_provider(lambda: _ck_text)
    except Exception:
        pass

try:
    out = K.run(script_args)
except Exception as e:
    out = {"ok": False, "error": {"code": "UNKNOWN",
                                  "msg": "%s: %s" % (type(e).__name__, e)}}
print(json.dumps(out, ensure_ascii=False))
