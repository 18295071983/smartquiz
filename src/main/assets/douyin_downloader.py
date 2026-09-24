# -*- coding: utf-8 -*-
# 抖音分享链接解析下载器 v2.4（内置版，由 douyin_downloader 动态工具固化而来）
# 输入分享链接/整段分享文案/纯aweme_id，自动下载：①无水印视频 ②背景音乐BGM ③封面图 ④图集多图
# 返回标题/作者/点赞/发布时间/文件大小。短链先跟踪 302 重定向直接抠出 item_id，
# 一旦拿到 id 立即停止跟踪，再用标准长链 https://www.douyin.com/video/{id} 送解析源。
# 解析源失败自动重试(最多3次递增间隔)对 5xx/空响应容错；全流程异常兜底返回结构化 JSON；
# 缓存落在工作区根目录 .dy_cache.json，TTL 24 小时并支持过期兜底（源每日限20次）；
# 直链下载失败换 snssdk 通道重试；文件已存在秒跳过；兼容 url/cover/music 多形态与图文帖；
# 非抖音链接快速拒绝；force="true" 忽略缓存强制重解析；返回 cache_dbg 诊断缓存命中情况。
import requests, re, json, os, time, datetime
from urllib.parse import quote

WS = '/storage/emulated/0/Download/OilQuiz/agent_workspace'
CACHE = os.path.join(WS, '.dy_cache.json')
OLD_CACHE = os.path.join(WS, 'tmp', 'dy_parse_cache.json')
TTL = 24 * 3600
UA_D = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
UA_M = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'
DH = {'User-Agent': UA_M, 'Referer': 'https://www.douyin.com/'}
AH = {'User-Agent': UA_D, 'Referer': 'https://www.douyin.com/'}
RE_ID = r'/(?:video|note|slides)/(\d{15,25})'
DBG = []


def clean(s, n=26):
    s = re.sub(r'[\\/:*?"<>|\r\n\t]+', ' ', str(s or ''))
    s = re.sub(r'\s+', ' ', s).strip()
    return s[:n].strip() or 'douyin'


def pick(d, keys):
    if not isinstance(d, dict):
        return ''
    for k in keys:
        v = d.get(k)
        if isinstance(v, list) and v:
            v = v[0]
        if isinstance(v, dict):
            v = v.get('url') or v.get('url_list') or v.get('download_addr') or ''
            if isinstance(v, list) and v:
                v = v[0]
        if v:
            return str(v)
    return ''


def _read_cache(path):
    try:
        f = open(path, encoding='utf-8')
        c = json.load(f)
        f.close()
        return c if isinstance(c, dict) else {}
    except Exception as e:
        DBG.append('read %s: %s' % (os.path.basename(path), type(e).__name__))
        return {}


def dl(url, path, mini=2048, tries=2):
    if not url:
        return None
    try:
        if os.path.exists(path) and os.path.getsize(path) >= mini:
            return path
    except Exception:
        pass
    for _ in range(max(1, tries)):
        tmp = path + '.part'
        try:
            r = requests.get(url, headers=DH, stream=True, timeout=(20, 240), verify=False)
            if r.status_code != 200:
                time.sleep(1)
                continue
            n = 0
            with open(tmp, 'wb') as f:
                for c in r.iter_content(262144):
                    if c:
                        f.write(c)
                        n += len(c)
            if n < mini:
                try:
                    os.remove(tmp)
                except Exception:
                    pass
                continue
            os.replace(tmp, path)
            return path
        except Exception:
            try:
                os.remove(tmp)
            except Exception:
                pass
            time.sleep(1)
    return None


def dl_snssdk(uri):
    try:
        u = 'https://aweme.snssdk.com/aweme/v1/play/?video_id=' + uri + '&ratio=1080p&line=0'
        r = requests.get(u, headers=DH, timeout=25, verify=False, allow_redirects=False)
        loc = r.headers.get('Location')
        if loc and 'http' in str(loc):
            return str(loc).replace('&amp;', '&')
        mm = re.search(r'href="([^"]+?)"', r.text or '')
        if mm:
            return mm.group(1).replace('&amp;', '&')
    except Exception:
        pass
    return None


def parse_suxun(u, tries=3):
    last = ''
    for i in range(tries):
        try:
            api = 'https://api.suxun.site/api/douyin?url=' + quote(u, safe='')
            rr = requests.get(api, headers=AH, timeout=25, verify=False)
            last = 'http %s' % rr.status_code
            if rr.status_code == 200 and (rr.text or '').strip():
                j = rr.json()
                if str(j.get('code')) == '200' and isinstance(j.get('data'), dict) and j['data']:
                    return j['data'], j.get('tips') or ''
                last = str(j.get('msg') or 'code=%s' % j.get('code'))
        except Exception as e:
            last = '%s: %s' % (type(e).__name__, str(e)[:80])
        time.sleep(1.5 * (i + 1))
    return None, last


def cache_load():
    c = _read_cache(CACHE)
    if not c:
        old = _read_cache(OLD_CACHE)
        for k, v in old.items():
            c.setdefault(k, v)
        DBG.append('migrate_old=%d' % len(old))
    return c


def cache_get(k, ttl=TTL):
    c = cache_load()
    e = c.get(k)
    if not e:
        DBG.append('miss key=%s n=%d' % (k, len(c)))
        return None, False
    d = e.get('d')
    if not isinstance(d, dict) or not d:
        DBG.append('bad_entry')
        return None, False
    age = time.time() - float(e.get('t', 0))
    DBG.append('hit age_h=%.2f' % (age / 3600.0))
    return d, (age < ttl)


def cache_put(k, d):
    try:
        c = _read_cache(CACHE)
        c[k] = {'t': time.time(), 'd': d}
        f = open(CACHE, 'w', encoding='utf-8')
        json.dump(c, f, ensure_ascii=False)
        f.close()
        DBG.append('put ok size=%d' % os.path.getsize(CACHE))
    except Exception as e:
        DBG.append('put FAIL %s' % type(e).__name__)


def resolve(link):
    iid = None
    final = link
    html = ''
    mm = re.search(RE_ID, link)
    if mm:
        return mm.group(1), link, ''
    try:
        s = requests.Session()
        u = link
        for _ in range(6):
            r = s.get(u, headers=DH, timeout=15, verify=False, allow_redirects=False)
            loc = r.headers.get('Location')
            if r.status_code in (301, 302, 303, 307, 308) and loc:
                loc = str(loc)
                nxt = loc if loc.startswith('http') else requests.compat.urljoin(u, loc)
                m = re.search(RE_ID, nxt)
                if m:
                    iid = m.group(1)
                    final = nxt
                    break
                u = nxt
                continue
            final = u
            html = r.text or ''
            break
    except Exception:
        pass
    if not iid:
        for s_ in (final, link, html[:40000]):
            m = re.search(RE_ID, s_)
            if m:
                iid = m.group(1)
                break
            m = re.search(r'(?:aweme_id|itemId|item_ids|vid)["\']?[=:]\s*["\']?(\d{15,25})', s_)
            if m:
                iid = m.group(1)
                break
    if not iid:
        m = re.search(r'(\d{19})', final)
        iid = m.group(1) if m else None
    return iid, final, html


def grab(data, what, OUT, res):
    vurl = pick(data, ('url', 'video_url', 'play_url', 'nwm_url', 'vm_url', 'hd_url'))
    uri = str(data.get('uri') or '')
    music = data.get('music')
    if isinstance(music, list):
        music = music[0] if music else {}
    if not isinstance(music, dict):
        music = {}
    murl = pick(music, ('url', 'play_url', 'music_url')) or pick(data, ('music_url',))
    cover = pick(data, ('cover', 'origin_cover', 'dynamic_cover', 'static_cover'))
    is_video = ('video_mp4' in vurl) or ('.mp4' in vurl) or ('douyinvod' in vurl)
    if (not is_video) and uri and len(uri) < 60:
        fb = dl_snssdk(uri)
        if fb:
            vurl, is_video = fb, True
    res['type'] = 'video' if is_video else 'images'
    res['bgm'] = {'author': music.get('author'), 'has_direct_url': bool(murl)}
    if murl:
        mm = re.search(r'/ies-music/(\d+)', murl)
        if mm:
            res['bgm']['music_id'] = mm.group(1)
    tag = clean(data.get('author'), 14) + '_' + clean(data.get('title'), 20)
    base = str(res.get('item_id') or 'douyin')

    def mark(key, got):
        if got:
            res['files'][key] = os.path.relpath(got, WS)
            try:
                res.setdefault('size_mb', {})[key] = round(os.path.getsize(got) / 1048576.0, 1)
            except Exception:
                pass

    if what in ('all', 'video') and is_video and vurl:
        got = dl(vurl, os.path.join(OUT, '抖音_' + tag + '.mp4'), 20000)
        if not got and uri:
            fb = dl_snssdk(uri)
            if fb:
                got = dl(fb, os.path.join(OUT, '抖音_' + tag + '.mp4'), 20000)
        mark('video', got)
    if what in ('all', 'video', 'bgm', 'music') and murl:
        ext = '.mp3' if '.mp3' in murl else '.m4a'
        mark('bgm', dl(murl, os.path.join(OUT, 'BGM_' + clean(music.get('author'), 12) + '_' + base + ext), 20000))
    if what in ('all', 'image', 'cover') and cover:
        ext = '.webp' if '.webp' in cover else '.jpg'
        mark('cover', dl(cover, os.path.join(OUT, '封面_' + base + ext), 512))
    # v2.4：图集多图下载（images 列表逐张，命名 图集_<tag>_NN.jpg/webp）
    if what in ('all', 'image', 'images') and not is_video:
        imgs = data.get('images')
        if isinstance(imgs, list) and imgs:
            got_imgs = []
            for idx, it in enumerate(imgs, 1):
                iu = ''
                if isinstance(it, dict):
                    iu = it.get('url') or (it.get('urls') or [None])[0] or ''
                elif isinstance(it, str):
                    iu = it
                iu = str(iu or '')
                if not iu:
                    continue
                iext = '.webp' if '.webp' in iu else '.jpg'
                got = dl(iu, os.path.join(OUT, '图集_' + tag + '_%02d' % idx + iext), 512)
                if got:
                    got_imgs.append(os.path.relpath(got, WS))
            if got_imgs:
                res['files']['images'] = got_imgs
                try:
                    res['image_count_dl'] = len(got_imgs)
                except Exception:
                    pass
    return res


def main():
    A = script_args if isinstance(script_args, dict) else {}
    raw = str(A.get('url') or '').strip()
    what = (str(A.get('what') or 'all').strip().lower()) or 'all'
    sub = (str(A.get('save_dir') or 'files').strip().strip('/')) or 'files'
    force = str(A.get('force') or '').strip().lower() in ('1', 'true', 'yes', 'on')
    OUT = os.path.join(WS, sub)
    os.makedirs(OUT, exist_ok=True)
    res = {'ok': False, 'what': what, 'files': {}, 'tool_version': '2.4'}

    if not raw:
        res['error'] = '请提供抖音分享链接或分享文案'
        return res
    m = re.search(r'https?://[^\s\u4e00-\u9fff，,。；;、）)】]+', raw)
    link = m.group(0).rstrip('。，,.') if m else raw
    if not link.lower().startswith('http'):
        if re.fullmatch(r'\d{15,25}', link):
            link = 'https://www.douyin.com/video/' + link
        else:
            res['error'] = '未识别到有效链接，请粘贴抖音分享链接或分享文案'
            return res
    host = re.sub(r'^https?://', '', link).split('/')[0].lower()
    if 'douyin.com' not in host and 'iesdouyin' not in host:
        res['error'] = '这看起来不是抖音链接（%s），本工具只支持 douyin.com 分享链接' % host
        return res
    res['input_link'] = link

    t0 = time.time()
    iid, final, html = resolve(link)
    res['item_id'] = iid
    res['std_link'] = ('https://www.douyin.com/video/' + iid) if iid else None
    std = res['std_link']
    res['link_resolve'] = 'shortlink_302->id' if (iid and iid not in link) else ('direct_id' if iid else 'failed')
    res['final_url'] = final

    cands = []
    for c in (std, link, final):
        if c and c not in cands:
            cands.append(c)
    ck = iid or link

    data, fresh = (None, False) if force else cache_get(ck)
    if data:
        res['source'] = 'cache' if fresh else 'cache(stale)'
    else:
        errs = []
        for u in cands:
            data, msg = parse_suxun(u)
            if data:
                res['source'] = 'suxun:' + ('item_id' if u == std else 'direct')
                cache_put(ck, data)
                break
            errs.append(msg)
        if not data:
            stale, _ = cache_get(ck, ttl=-1)
            if stale:
                data = stale
                res['source'] = 'cache(stale)'
                res['note'] = '解析源临时不可用，已用本地过期缓存兜底（直链可能已失效，若下载失败请稍后重试）'
            else:
                res['error'] = '解析失败（源限流或临时故障）：' + '; '.join([e for e in errs if e][:3])
                res['tip'] = '该免费源每日限20次调用且偶发限流，建议稍等几分钟重试；若作品已解析过会直接走缓存。'
                res['cache_dbg'] = DBG
                return res

    res['author'] = data.get('author')
    res['title'] = data.get('title')
    res['like'] = data.get('like')
    if data.get('time'):
        try:
            res['publish_time'] = datetime.datetime.fromtimestamp(int(data['time'])).strftime('%Y-%m-%d')
        except Exception:
            pass

    grab(data, what, OUT, res)

    if (not res['files']) and res.get('source') in ('cache', 'cache(stale)') and not force:
        for u in cands:
            d2, _m = parse_suxun(u, tries=2)
            if d2:
                res['source'] = 'suxun:' + ('item_id' if u == std else 'direct')
                cache_put(ck, d2)
                grab(d2, what, OUT, res)
                break

    res['ok'] = bool(res['files'])
    res['elapsed_s'] = round(time.time() - t0, 1)
    if not res['ok']:
        res['error'] = '已解析到作品信息，但媒体文件没下下来（直链可能已过期或 CDN 拒绝），可稍后重试'
    if res.get('type') == 'images':
        dl_imgs = len(res.get('files', {}).get('images', []))
        if dl_imgs:
            res['note'] = '该作品是图文/图集帖：已下载 %d 张图片' % dl_imgs + ('，配乐已获取' if 'bgm' in res['files'] else '')
        else:
            res['note'] = '该作品是图文/图集帖：本身没有视频文件；封面' + ('与配乐' if 'bgm' in res['files'] else '') + '已获取。'
    res['cache_dbg'] = DBG
    res['tip'] = '解析源每日限20次，24小时内同一作品走本地缓存不再消耗额度。'
    return res


try:
    _r = main()
except Exception as _e:
    _r = {'ok': False, 'error': '工具内部异常：%s: %s' % (type(_e).__name__, str(_e)[:200]), 'cache_dbg': DBG}
print(json.dumps(_r, ensure_ascii=False, indent=1))
