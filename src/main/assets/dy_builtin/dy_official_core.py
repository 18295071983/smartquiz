# -*- coding: utf-8 -*-
# 抖音本地解析内核 v2.0  (2026-09-25 实测修复版)
#
# 实测踩坑记录（全部已修）：
#  坑1 403 Uifid Not Found     -> query 必须带真实 uifid 参数
#  坑2 403 Signature Not Found -> 受保护接口必须叠 secsdk 的 x-secsdk-web-signature
#  坑3 403 Validate Error      -> uifid 必须真实，随机值无效
#  坑4 msToken 不是必须的       -> 实测随机值/空值均通过（不再依赖）
#  坑5 Cookie 不用全套          -> 最小集 = 服务端下发 ttwid + 真实 UIFID
#  坑6 ttwid 需服务端下发       -> 可用 ttwid 注册接口自取，免浏览器
#  坑7 旧分享页兜底已失效        -> 移除，改为明确错误码
#  坑8 图文帖无 video.play_addr -> 新增 images 图集解析与下载
#  坑9 文件名 emoji/特殊字符     -> 统一清洗
# 坑10 下载无校验               -> .part 临时文件 + 体积/文件头校验 + 重试
import os, re, sys, json, time, random, importlib, shutil, hashlib
from urllib.parse import urlencode, quote, unquote, urlsplit

import requests

WS = "/storage/emulated/0/Download/OilQuiz/agent_workspace"
FILES = os.path.join(WS, "files")
DY_SRC = os.path.join(FILES, "dy_src")
DY_PKG = os.path.join(FILES, "dy_pkg")
COOKIE_FILE = os.path.join(FILES, "dy_webview_cookie.txt")
CACHE_FILE = os.path.join(WS, ".dy_cache.json")
CACHE_TTL = 24 * 3600
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/90.0.4430.212 Safari/537.36")
API_DETAIL = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
TTWID_API = "https://ttwid.bytedance.com/ttwid/union/register/"
REQUIRED = ("ttwid", "UIFID")
OPTIONAL = ("msToken", "odin_tt", "s_v_web_id", "DYMST", "tt_scid")
BASE_Q = [("device_platform", "webapp"), ("aid", "6383"), ("channel", "channel_pc_web"),
          ("pc_client_type", "1"), ("version_code", "170400"), ("version_name", "17.4.0"),
          ("cookie_enabled", "true"), ("screen_width", "1080"), ("screen_height", "2340"),
          ("browser_language", "zh-CN"), ("browser_platform", "Android"),
          ("browser_name", "Mozilla"), ("browser_version", "5.0"), ("browser_online", "true"),
          ("engine_name", "Blink"), ("engine_version", "143.0.0.0"), ("os_name", "Android"),
          ("os_version", "16"), ("cpu_core_num", "8"), ("device_memory", "8"),
          ("platform", "PC"), ("downlink", "10"), ("effective_type", "4g"),
          ("round_trip_time", "50")]
MAGIC = {"mp4": (b"ftyp",), "mp3": (b"ID3", b"\xff\xfb", b"\xff\xf3"),
         "jpg": (b"\xff\xd8",), "png": (b"\x89PNG",), "webp": (b"RIFF",)}
VERSION = "2.0"


class DyError(Exception):
    def __init__(self, code, msg="", detail=None):
        super().__init__("%s: %s" % (code, msg))
        self.code = code
        self.msg = msg
        self.detail = detail or {}


def _repr(e):
    if isinstance(e, DyError):
        return {"code": e.code, "msg": e.msg, "detail": e.detail}
    return {"code": "UNKNOWN", "msg": "%s: %s" % (type(e).__name__, e)}


def _ensure_pkg():
    if not os.path.isdir(DY_PKG):
        os.makedirs(DY_PKG, exist_ok=True)
        for f in ("sm3.py", "aBogus.py", "websign.py", "params.py"):
            src = os.path.join(DY_SRC, f)
            if os.path.exists(src):
                shutil.copy(src, DY_PKG)
    open(os.path.join(DY_PKG, "__init__.py"), "w").write("")
    if FILES not in sys.path:
        sys.path.insert(0, FILES)


def _signers():
    _ensure_pkg()
    ab = importlib.import_module("dy_pkg.aBogus").ABogus
    ws = importlib.import_module("dy_pkg.websign")
    return ab, ws


def get_signer():
    return _signers()[0]


def parse_cookie(text):
    ck = {}
    for part in (text or "").split(";"):
        part = part.strip()
        if "=" in part:
            k, v = part.split("=", 1)
            k = k.strip()
            if k and " " not in k:
                ck[k] = v.strip().strip('"')
    return ck


def save_cookies(ck):
    if os.path.exists(COOKIE_FILE):
        shutil.copy(COOKIE_FILE, COOKIE_FILE + ".bak")
    body = "; ".join("%s=%s" % (k, v) for k, v in ck.items() if not k.startswith("_"))
    open(COOKIE_FILE, "w", encoding="utf-8").write(body)
    return COOKIE_FILE


def refresh_ttwid(ck=None, log=None):
    ck = dict(ck or {})
    try:
        r = requests.post(
            TTWID_API,
            headers={"User-Agent": UA, "Content-Type": "application/json"},
            json={"region": "cn", "aid": 1768, "needFid": False,
                  "service": "www.ixigua.com",
                  "migrate_info": {"ticket": "", "source": "node"},
                  "cbUrlProtocol": "https", "union": True},
            timeout=15)
        got = {k: v for k, v in r.cookies.get_dict().items() if v}
        if got.get("ttwid"):
            ck["ttwid"] = got["ttwid"]
            ck["_ttwid_ts"] = str(int(time.time()))
        for k, v in got.items():
            ck.setdefault(k, v)
        if log is not None:
            log.append("ttwid自取:HTTP%d %s" % (r.status_code, "成功" if got.get("ttwid") else "无返回"))
    except Exception as e:
        if log is not None:
            log.append("ttwid自取失败:%s" % type(e).__name__)
    return ck


def load_cookies(fresh=False):
    ck = {}
    if os.path.exists(COOKIE_FILE):
        ck = parse_cookie(open(COOKIE_FILE, encoding="utf-8", errors="replace").read())
    if ck.get("DYMST") and not ck.get("msToken"):
        ck["msToken"] = unquote(ck["DYMST"])
    if fresh or not ck.get("ttwid"):
        ck = refresh_ttwid(ck)
    return ck


def cookie_status(ck):
    ck = ck or {}
    st = {}
    for k in REQUIRED:
        st[k] = "ok" if ck.get(k) else "缺失"
    for k in OPTIONAL:
        st[k] = "ok" if ck.get(k) else "-"
    st["uifid长度"] = len(unquote(str(ck.get("UIFID", "") or "")))
    return st


def get_uifid(ck):
    return unquote(str((ck or {}).get("UIFID", "") or ""))


def _headers(ck=None, json_mode=True):
    h = {"User-Agent": UA, "Referer": "https://www.douyin.com/",
         "Accept-Language": "zh-CN,zh;q=0.9"}
    h["Accept"] = ("application/json, text/plain, */*" if json_mode
                   else "text/html,application/xhtml+xml,*/*;q=0.8")
    if ck:
        h["Cookie"] = "; ".join("%s=%s" % (k, v) for k, v in ck.items() if not k.startswith("_"))
    return h


def signed_url(aweme_id, ck, api=None, extra=None):
    """完整签名链: 业务query(含uifid) -> a_bogus -> x-secsdk-web-signature"""
    ab_cls, ws = _signers()
    uifid = get_uifid(ck)
    if not uifid:
        raise DyError("NEED_COOKIE", "缺少 UIFID：请用 WebView 打开抖音取一次登录态 Cookie")
    if not re.fullmatch(r"[0-9a-fA-F]{100,}", uifid):
        raise DyError("NEED_COOKIE", "UIFID 格式非法（需浏览器下发的长 hex 值，实测乱填会 403 Validate Error）")
    q = list(BASE_Q) + [("aweme_id", str(aweme_id)), ("ts", str(int(time.time() * 1000)))]
    if extra:
        q += list(extra)
    if (ck or {}).get("msToken"):
        q.append(("msToken", str(ck["msToken"])))
    q.append(("uifid", uifid))
    qs = ws.normalize_query(urlencode(q))
    try:
        bogus = ab_cls(UA).get_value(qs, body="")
        signed = ws.sign(qs + "&a_bogus=" + quote(bogus, safe=""), uifid)[0]
    except Exception as e:
        raise DyError("SIGN_FAIL", "签名计算失败:%s" % type(e).__name__)
    return (api or API_DETAIL) + "?" + signed


def classify(text, status=0):
    t = (text or "")[:400]
    if "Uifid Not Found" in t:
        return "NEED_COOKIE", "服务端未收到有效 uifid"
    if "Signature Not Found" in t:
        return "NEED_COOKIE", "缺少 secsdk 签名"
    if "Validate Error" in t:
        return "NEED_COOKIE", "uifid 校验失败（需真实浏览器值）"
    if "ArgusSecurityPlugin" in t:
        return "RISK_CONTROL", "被风控拦截"
    ts = t.replace(" ", "")
    if '"aweme_detail":null' in ts or "core_dep" in ts:
        return "NOT_FOUND", "作品不存在或已下架"
    if status == 404:
        return "NOT_FOUND", "作品不存在或已删除"
    if not t.strip():
        return "NETWORK", "空响应（ttwid 失效）"
    return "UNKNOWN", t[:80].replace(chr(10), " ").replace(chr(9), " ")


def fetch_detail(aweme_id, ck=None, retry=4, verbose=False):
    ck = dict(ck or load_cookies())
    errors = []
    for i in range(retry):
        try:
            url = signed_url(aweme_id, ck)
            r = requests.get(url, headers=_headers(ck), timeout=20)
            try:
                j = r.json()
            except Exception:
                j = None
            if j and j.get("aweme_detail"):
                return j["aweme_detail"], {"http": r.status_code, "try": i + 1, "bytes": len(r.content)}
            code, msg = classify(r.text, r.status_code)
            errors.append("%s:%s" % (code, msg))
            if verbose:
                print("  第%d次失败 %s %s" % (i + 1, code, msg))
            if code in ("RISK_CONTROL", "NEED_COOKIE", "NETWORK") and i == 0:
                ck = refresh_ttwid(ck, log=[])
                save_cookies(ck)
        except DyError as e:
            if e.code == "NEED_COOKIE":
                raise
            errors.append("%s:%s" % (e.code, e.msg))
        except Exception as e:
            errors.append("NETWORK:%s" % type(e).__name__)
        time.sleep(0.7 + i * 0.5)
    code = "RISK_CONTROL"
    for it in errors:
        if it.startswith("NEED_COOKIE"):
            code = "NEED_COOKIE"
            break
        if it.startswith("NOT_FOUND"):
            code = "NOT_FOUND"
    raise DyError(code, "官方接口重试%d次仍失败" % retry, {"errors": errors})


def extract_item_id(url_or_text):
    s = (url_or_text or "").strip()
    if not s:
        return None
    if s.isdigit() and len(s) >= 15:
        return s
    m = re.search(r"https?://[^ ]+", s)
    if not m:
        m2 = re.search(r"\d{16,20}", s)
        return m2.group(0) if m2 else None
    u = m.group(0).rstrip("，。,.、)）")
    if "v.douyin.com" in u or "iesdouyin.com/share" in u or "douyin.com/share" in u:
        for _ in range(6):
            try:
                r = requests.get(u, headers={"User-Agent": UA}, timeout=12, allow_redirects=False)
                loc = r.headers.get("Location")
                if not loc:
                    break
                u = loc if loc.startswith("http") else "https://www.douyin.com" + loc
                nid = re.search(r"(?:video|note|slides)/(\d{15,20})", u)
                if nid:
                    return nid.group(1)
            except Exception:
                break
    for pat in (r"/(?:video|note|slides)/(\d{15,20})", r"modal_id=(\d{15,20})",
                r"aweme_id=(\d{15,20})", r"/share/video/(\d{15,20})"):
        mm = re.search(pat, u)
        if mm:
            return mm.group(1)
    mm = re.search(r"\d{16,20}", u)
    return mm.group(0) if mm else None


# =========================== 缓存 ===========================

def cache_read(item_id):
    try:
        c = json.load(open(CACHE_FILE, encoding="utf-8"))
    except Exception:
        return None
    it = c.get(str(item_id))
    if not it:
        return None
    age = time.time() - float(it.get("t", 0))
    if age > CACHE_TTL:
        return None
    d = dict(it.get("d") or {})
    d["cache_age"] = int(age)
    return d


def cache_write(item_id, data):
    c = {}
    if os.path.exists(CACHE_FILE):
        try:
            c = json.load(open(CACHE_FILE, encoding="utf-8"))
        except Exception:
            c = {}
    c[str(item_id)] = {"t": time.time(), "d": data}
    if len(c) > 300:
        for k in sorted(c, key=lambda k: c[k].get("t", 0))[:100]:
            c.pop(k, None)
    try:
        json.dump(c, open(CACHE_FILE, "w", encoding="utf-8"), ensure_ascii=False)
    except Exception:
        pass


# =========================== 数据规整 ===========================

def pick_video(v, quality="best"):
    gears = []
    for it in (v.get("bit_rate") or []):
        pa = (it.get("play_addr") or {}).get("url_list") or []
        if pa:
            gears.append({"gear": it.get("gear_name"), "rate": int(it.get("bit_rate") or 0),
                          "url": pa[0], "urls": pa})
    gears.sort(key=lambda x: x["rate"], reverse=True)
    if isinstance(quality, int) and 0 <= quality < len(gears):
        return gears[quality]
    if isinstance(quality, str) and quality not in ("best", ""):
        for g in gears:
            if quality in str(g.get("gear")):
                return g
    if gears:
        return gears[0]
    base = (v.get("play_addr") or {}).get("url_list") or []
    return {"gear": "play_addr", "rate": 0, "url": base[0] if base else None, "urls": base}


def simplify(d, quality="best"):
    v = d.get("video") or {}
    mu = d.get("music") or {}
    st = d.get("statistics") or {}
    au = d.get("author") or {}
    imgs = []
    for im in (d.get("images") or []):
        us = im.get("url_list") or []
        if us:
            imgs.append({"url": us[0], "urls": us,
                         "w": im.get("width"), "h": im.get("height")})
    best = pick_video(v, quality)
    cov = ((v.get("origin_cover") or {}).get("url_list") or
           (v.get("cover") or {}).get("url_list") or [None])[0]
    return {
        "aweme_id": d.get("aweme_id"),
        "type": "图集" if imgs else "视频",
        "title": (d.get("desc") or "").strip(),
        "author": au.get("nickname"), "uid": au.get("uid"), "sec_uid": au.get("sec_uid"),
        "follower": au.get("follower_count"),
        "avatar": ((au.get("avatar_thumb") or {}).get("url_list") or [None])[0],
        "like": st.get("digg_count"), "comment": st.get("comment_count"),
        "share": st.get("share_count"), "collect": st.get("collect_count"),
        "time": d.get("create_time"), "duration_ms": v.get("duration"),
        "cover": cov, "images": imgs, "image_count": len(imgs),
        "url": best.get("url"), "url_list": best.get("urls") or [],
        "quality": best.get("gear"), "rate": best.get("rate"),
        "bit_rate_levels": len(v.get("bit_rate") or []),
        "music": {"id": mu.get("id"), "title": mu.get("title"),
                  "author": mu.get("author"),
                  "url": ((mu.get("play_url") or {}).get("url_list") or [None])[0]},
        "source": "official", "fields": len(d), "kernel": VERSION,
    }


# =========================== 下载 ===========================

def _safe(name, limit=40):
    s = "".join(ch for ch in (name or "") if ch.isprintable() and ch not in '\\/:*?"<>|#')
    s = "".join(ch for ch in s if not (0x1F000 <= ord(ch) <= 0x1FAFF or 0x2600 <= ord(ch) <= 0x27BF))
    s = re.sub(r"\s+", " ", s).strip().replace("#", "")
    return s[:limit].strip() or "未命名"


def _magic_ok(head, kind):
    pats = MAGIC.get(kind)
    if not pats:
        return True
    return any(p in head[:16] for p in pats)


def download(url, path, referer="https://www.douyin.com/", kind=None, tries=2, min_size=1024):
    """坑10：.part 临时文件 + 体积/文件头校验 + 自动重试"""
    last = None
    tmp = path + ".part"
    for i in range(tries):
        try:
            r = requests.get(url, headers={"User-Agent": UA, "Referer": referer},
                             timeout=90, stream=True)
            r.raise_for_status()
            n = 0
            with open(tmp, "wb") as f:
                for chunk in r.iter_content(1024 * 256):
                    if chunk:
                        f.write(chunk)
                        n += len(chunk)
            if n < min_size:
                raise IOError("体积过小(%d字节)" % n)
            if kind:
                with open(tmp, "rb") as f:
                    head = f.read(16)
                if not _magic_ok(head, kind):
                    raise IOError("文件头校验失败")
            os.replace(tmp, path)
            return n
        except Exception as e:
            last = e
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except Exception:
                    pass
            time.sleep(0.5 + i)
    raise DyError("NETWORK", "下载失败:%s" % last)


def save_all(info, save_dir="files", what="all", quality="best"):
    base = os.path.join(WS, save_dir) if not os.path.isabs(save_dir) else save_dir
    os.makedirs(base, exist_ok=True)
    out = []
    tag = "%s_%s" % (_safe(info.get("author"), 16), _safe(info.get("title"), 30))
    iid = str(info.get("aweme_id"))
    imgs = info.get("images") or []

    want_video = what in ("all", "video")
    want_img = what in ("all", "image", "images")

    if want_video and not imgs and info.get("url"):
        fn = "抖音_%s_%s.mp4" % (tag, iid)
        fp = os.path.join(base, fn)
        try:
            if not (os.path.exists(fp) and os.path.getsize(fp) > 0):
                download(info["url"], fp, kind="mp4")
            out.append({"type": "视频(无水印)", "file": fn,
                        "size": os.path.getsize(fp), "quality": info.get("quality")})
        except Exception as e:
            out.append({"type": "视频(无水印)", "file": fn, "error": str(_repr(e))})

    if want_img and imgs:
        for idx, im in enumerate(imgs, 1):
            fn = "图集_%s_%s_%02d.jpg" % (tag, iid, idx)
            fp = os.path.join(base, fn)
            try:
                if not (os.path.exists(fp) and os.path.getsize(fp) > 0):
                    ok = False
                    for u in (im.get("urls") or [im.get("url")]):
                        try:
                            download(u, fp, kind="jpg", min_size=512)
                            ok = True
                            break
                        except Exception:
                            continue
                    if not ok:
                        raise IOError("所有直链均失败")
                out.append({"type": "图集第%d张" % idx, "file": fn, "size": os.path.getsize(fp)})
            except Exception as e:
                out.append({"type": "图集第%d张" % idx, "file": fn, "error": str(_repr(e))})

    if what in ("all", "bgm") and (info.get("music") or {}).get("url"):
        fn = "BGM_%s_%s.mp3" % (_safe((info.get("music") or {}).get("author"), 16), iid)
        fp = os.path.join(base, fn)
        try:
            if not (os.path.exists(fp) and os.path.getsize(fp) > 0):
                download(info["music"]["url"], fp, kind="mp3", min_size=1024)
            out.append({"type": "背景音乐", "file": fn, "size": os.path.getsize(fp)})
        except Exception as e:
            out.append({"type": "背景音乐", "file": fn, "error": str(_repr(e))})

    if what in ("all", "cover", "image") and info.get("cover"):
        fn = "封面_%s.jpg" % iid
        fp = os.path.join(base, fn)
        try:
            if not (os.path.exists(fp) and os.path.getsize(fp) > 0):
                download(info["cover"], fp, kind="jpg", min_size=256)
            out.append({"type": "封面", "file": fn, "size": os.path.getsize(fp)})
        except Exception as e:
            out.append({"type": "封面", "file": fn, "error": str(_repr(e))})

    if what in ("all", "text", "meta"):
        fn = "文案_%s_%s.txt" % (tag, iid)
        fp = os.path.join(base, fn)
        lines = ["标题: " + (info.get("title") or ""),
                 "作者: " + str(info.get("author")),
                 "点赞: %s  评论: %s  分享: %s" % (info.get("like"), info.get("comment"), info.get("share")),
                 "作品ID: " + iid,
                 "类型: " + str(info.get("type")),
                 "链接: https://www.douyin.com/video/" + iid]
        open(fp, "w", encoding="utf-8").write(chr(10).join(lines))
        out.append({"type": "文案", "file": fn, "size": os.path.getsize(fp)})

    return {"dir": base, "files": out}


# =========================== 入口 ===========================

def parse(url_or_id, source="official", use_cache=True, quality="best", debug=False):
    if MODE == "always_fresh":
        use_cache = False
        _apply_fresh()          # 每次强制：换新 ttwid + 重新要一份浏览器 Cookie
    if source not in ("auto", "official"):
        source = "official"
    iid = extract_item_id(url_or_id)
    if not iid:
        return {"ok": False, "error": _repr(DyError("BAD_INPUT", "未识别到作品ID")),
                "diag": {"input": str(url_or_id)[:60]}}
    ck = load_cookies()
    diag = {"item_id": iid, "cookie": cookie_status(ck), "kernel": VERSION}
    if use_cache:
        c = cache_read(iid)
        if c:
            c["cache"] = "hit"
            diag["cache"] = "hit(age=%ss)" % c.get("cache_age")
            return {"ok": True, "data": c, "diag": diag}
    diag["cache"] = "miss"
    try:
        d, meta = fetch_detail(iid, ck, verbose=debug)
        info = simplify(d, quality=quality)
        diag["official"] = meta
        cache_write(iid, info)
        return {"ok": True, "data": info, "diag": diag}
    except Exception as e:
        r = _repr(e)
        if r.get("code") == "NEED_COOKIE":
            r["hint"] = "Cookie 失效：用 WebView 打开 www.douyin.com 取 UIFID 写回 cookie 文件即可"
        return {"ok": False, "error": r, "diag": diag}


def run(script_args):
    a = script_args or {}
    url = a.get("url") or a.get("link") or ""
    what = a.get("what") or "info"
    force = str(a.get("force", "false")).lower() == "true"
    res = parse(url, source=a.get("source") or "official",
                use_cache=not force, quality=a.get("quality") or "best")
    if not res.get("ok"):
        return {"ok": False, "error": res.get("error"), "diag": res.get("diag")}
    info = res["data"]
    out = {"ok": True, "source": "official", "cache": info.get("cache", "miss"),
           "info": info, "diag": res["diag"]}
    if what != "info":
        out["download"] = save_all(info, save_dir=a.get("save_dir") or "files", what=what)
    return out


def batch(targets, what="info", use_cache=True, quality="best"):
    out = []
    for t in (targets or []):
        r = parse(t, use_cache=use_cache, quality=quality)
        out.append({"url": t, "ok": r.get("ok"),
                    "id": (r.get("data") or {}).get("aweme_id") or (r.get("diag") or {}).get("item_id"),
                    "title": (r.get("data") or {}).get("title"),
                    "error": (r.get("error") or {}).get("code") if not r.get("ok") else None})
        time.sleep(random.uniform(0.6, 1.4))
    return {"total": len(out), "ok": sum(1 for x in out if x["ok"]), "items": out}


def diagnose(probe_id="7689132132221248506"):
    """自检：环境/依赖/Cookie/签名/接口全链路"""
    r = {"kernel": VERSION, "steps": []}

    def step(name, ok, msg):
        r["steps"].append({"name": name, "ok": bool(ok), "msg": msg})
        return ok

    try:
        ab, ws = _signers()
        step("依赖包(dy_pkg)", True, "aBogus/websign 加载正常")
    except Exception as e:
        step("依赖包(dy_pkg)", False, "%s: %s" % (type(e).__name__, e))
        return r
    ck = load_cookies()
    st = cookie_status(ck)
    step("Cookie", st.get("ttwid") == "ok" and st.get("UIFID") == "ok",
         "ttwid=%s UIFID=%s(uifid长度%d)" % (st.get("ttwid"), st.get("UIFID"), st.get("uifid长度", 0)))
    try:
        u = signed_url(probe_id, ck)
        step("签名链", True, "URL 长度 %d，含 a_bogus+secsdk=%s" %
             (len(u), ("x-secsdk-web-signature" in u)))
    except Exception as e:
        step("签名链", False, str(_repr(e)))
        return r
    try:
        t0 = time.time()
        d, meta = fetch_detail(probe_id, ck, retry=2)
        step("官方接口", True, "HTTP%s 第%s次 耗时%.2fs %d字节" %
             (meta["http"], meta["try"], time.time() - t0, meta["bytes"]))
    except Exception as e:
        step("官方接口", False, str(_repr(e)))
    r["ok"] = all(s["ok"] for s in r["steps"])
    return r


# =========================== 探活 & 自愈 ===========================

PROBE_ID = "7689132132221248506"


def cookie_health(ck=None, probe_id=PROBE_ID, timeout=15):
    """一次探活：判断当前 Cookie 是否仍然有效"""
    ck = dict(ck or load_cookies())
    t0 = time.time()
    try:
        r = requests.get(signed_url(probe_id, ck), headers=_headers(ck), timeout=timeout)
        try:
            j = r.json()
        except Exception:
            j = None
        if j and j.get("aweme_detail"):
            return {"alive": True, "code": "OK", "msg": "Cookie 有效",
                    "http": r.status_code, "ms": int((time.time() - t0) * 1000)}
        code, msg = classify(r.text, r.status_code)
        return {"alive": False, "code": code, "msg": msg,
                "http": r.status_code, "ms": int((time.time() - t0) * 1000)}
    except Exception as e:
        return {"alive": False, "code": _repr(e).get("code"), "msg": str(e),
                "http": 0, "ms": int((time.time() - t0) * 1000)}


def heal(probe_id=PROBE_ID, log=None):
    """自愈：Cookie 失效时自动换新 ttwid 并重试（UIFID 失效则只能重新抓取）"""
    log = log if log is not None else []
    ck = load_cookies()
    h = cookie_health(ck, probe_id)
    log.append("初检:%s %s" % (h["code"], h["msg"]))
    if not h["alive"] and h["code"] in ("NETWORK", "RISK_CONTROL"):
        ck = refresh_ttwid(ck, log=log)
        save_cookies(ck)
        h = cookie_health(ck, probe_id)
        log.append("换新ttwid后:%s %s" % (h["code"], h["msg"]))
    if not h["alive"] and h["code"] == "NEED_COOKIE":
        log.append("UIFID 已失效 → 需重新从浏览器抓取登录态 Cookie")
    return {"ok": h["alive"], "health": h, "log": log,
            "action": "无需处理" if h["alive"] else ("已换新ttwid" if "换新ttwid后" in " ".join(log) else "需重新抓取UIFID")}


# =========================== Cookie 自动续期（浏览器通道） ===========================

def adopt_webview_cookie(text, keep_ttwid=True, log=None):
    """把 WebView 里取到的 Cookie 原文吸收进来，就地完成续期。
    UIFID 没有公开签发接口，只能由浏览器下发 —— 这里是唯一的自动续期入口。"""
    log = log if log is not None else []
    new = parse_cookie(text)
    if not new:
        log.append("浏览器Cookie为空，放弃更新")
        return {"ok": False, "log": log}
    old = load_cookies()
    ck = dict(old) if keep_ttwid else {}
    for k, v in new.items():
        if k.startswith("_"):
            continue
        if v:
            ck[k] = v
    got_uifid = bool(new.get("UIFID"))
    if not got_uifid and new.get("UIFID_TEMP"):
        # m.douyin.com 下发的临时 UIFID 兜底：无正式 UIFID 时尝试采用（真实浏览器下发值，比随机值有效）
        ck["UIFID"] = new["UIFID_TEMP"]
        got_uifid = True
        log.append("无正式 UIFID，采用 m 站 UIFID_TEMP 兜底（长度%d）" % len(unquote(str(new["UIFID_TEMP"]))))
    got_ttwid = bool(new.get("ttwid"))
    if got_uifid:
        log.append("吸收到新 UIFID（长度%d）" % len(unquote(str(ck.get("UIFID", "")))))
    else:
        log.append("浏览器Cookie里没有 UIFID，保留旧值")
    if not got_ttwid:
        ck = refresh_ttwid(ck, log=log)
    save_cookies(ck)
    st = cookie_status(ck)
    log.append("落盘完成：ttwid=%s UIFID=%s" % (st.get("ttwid"), st.get("UIFID")))
    return {"ok": st.get("UIFID") == "ok" and st.get("ttwid") == "ok", "cookie": ck, "log": log}


def auto_renew(probe_id=PROBE_ID, webview_cookie=None, log=None):
    """全自动续期：先探活 → 失效则换新 ttwid → 仍失效且有浏览器Cookie则吸收 UIFID"""
    log = log if log is not None else []
    h = cookie_health(probe_id=probe_id)
    log.append("探活：%s %s" % (h["code"], h["msg"]))
    if h["alive"]:
        return {"ok": True, "action": "无需续期", "health": h, "log": log}
    ck = refresh_ttwid(load_cookies(), log=log)
    save_cookies(ck)
    h = cookie_health(ck, probe_id)
    log.append("换新ttwid后：%s %s" % (h["code"], h["msg"]))
    if h["alive"]:
        return {"ok": True, "action": "已换新ttwid后恢复", "health": h, "log": log}
    if webview_cookie:
        r = adopt_webview_cookie(webview_cookie, log=log)
        ck = r.get("cookie") or load_cookies()
        h = cookie_health(ck, probe_id)
        log.append("吸收浏览器Cookie后：%s %s" % (h["code"], h["msg"]))
        if h["alive"]:
            return {"ok": True, "action": "已用浏览器Cookie续期恢复", "health": h, "log": log}
    return {"ok": False, "action": "需要人工介入：浏览器登录态失效", "health": h, "log": log}


# =========================== 一键修复 / 重建 ===========================

def reinstall(log=None):
    """重建依赖：清掉已加载的签名包模块并重新载入（文件在即恢复）"""
    import importlib, sys, os
    log = log if log is not None else []
    here = os.path.dirname(os.path.abspath(__file__))
    for r in (here, os.path.dirname(here)):
        if r and r not in sys.path:
            sys.path.insert(0, r)
    killed = []
    for m in [m for m in list(sys.modules) if m.split(".")[0] in ("dy_pkg", "dy_src")]:
        del sys.modules[m]; killed.append(m)
    importlib.invalidate_caches()
    try:
        import dy_pkg
        log.append("依赖包已重建并重新载入（清理 %d 个模块）" % len(killed))
        return True
    except Exception as e:
        log.append("依赖包重建失败：%s" % e)
        return False


def repair(webview_cookie=None, log=None):
    """一键修复：自检 → 哪坏了修哪 → 复测。相当于"重新弄一遍"，但只动坏的部分。"""
    log = log if log is not None else []
    before = diagnose()
    if all(st["ok"] for st in before["steps"]):
        log.append("自检全绿，无需修复")
        return {"ok": True, "before": before, "after": before,
                "actions": ["无需处理"], "log": log}
    actions = []
    for st in before["steps"]:
        if st["ok"]:
            continue
        nm = st["name"]
        if nm.startswith("Cookie"):
            r = auto_renew(webview_cookie=webview_cookie, log=log)
            actions.append("Cookie 处理：%s → %s" % (r["action"], "OK" if r["ok"] else "仍失败"))
        elif nm.startswith("依赖"):
            actions.append("依赖包重建：%s" % ("成功" if reinstall(log=log) else "失败（文件缺失，需重装）"))
        elif nm.startswith("签名"):
            r = reinstall(log=log)
            actions.append("签名链重建：%s" % ("模块已重载，若仍失败说明签名包版本不匹配" if r else "签名包缺失"))
        else:
            r = auto_renew(webview_cookie=webview_cookie, log=log)
            actions.append("接口异常处理：%s" % r["action"])
    after = diagnose()
    return {"ok": all(st["ok"] for st in after["steps"]),
            "before": before, "after": after, "actions": actions, "log": log}


# =========================== 每次重新获取模式 ===========================
# 用户要求：不复用，"每次使用浏览器重新获取、废除旧的"
MODE = "always_fresh"  # 默认：每次都重新获取(用户要求)。想省时间改回 "auto"
_COOKIE_PROVIDER = None


def set_mode(m):
    """切换模式：auto / always_fresh"""
    global MODE
    if m not in ("auto", "always_fresh"):
        raise ValueError("模式只能是 auto 或 always_fresh")
    MODE = m
    return MODE


def set_cookie_provider(fn):
    """注册"每次去浏览器取 Cookie"的函数（返回 cookie 文本）。在 always_fresh 模式下每次调用前会执行它。"""
    global _COOKIE_PROVIDER
    _COOKIE_PROVIDER = fn
    return True


def _apply_fresh(log=None):
    log = log if log is not None else []
    ck = load_cookies()
    old_ttwid = k = ck.get("ttwid", "")
    refresh_ttwid(ck, log=log)                     # 废除旧 ttwid，取新的
    ck = load_cookies()
    log.append("ttwid: %s → %s" % ("新" if ck.get("ttwid") != old_ttwid else "未变", (ck.get("ttwid","") or "")[:14]))
    if _COOKIE_PROVIDER:                            # 重新去浏览器取一份登录态
        try:
            txt = _COOKIE_PROVIDER()
        except Exception as e:
            txt = None
            log.append("浏览器取 Cookie 失败：%s" % e)
        if txt:
            adopt_webview_cookie(txt, keep_ttwid=True, log=log)
            log.append("已用浏览器最新 Cookie 覆盖旧值")
        else:
            log.append("未取到新 Cookie，沿用现有")
    else:
        log.append("未注册浏览器取 Cookie 通道（set_cookie_provider）")
    return load_cookies()
