#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dsh 桥接服务 v3（电脑端）— ACP 官方通道版
============================================
让手机端"答题宝"App 通过 HTTP 远程调用本机的 DeepSeek dsh（DeepSeek Harness Shell）。

v3 核心变化：后端切换为 **ACP 官方通道**（dsh --profile acp serve，127.0.0.1:7800）：
    * 原因：dsh 0.1.5 起 web 通道 API 重构（/api/session/* 移除、cookie 鉴权），
            官方远程标准为 ACP v1（JSON-RPC + SSE），本桥接直接对接。
    * session/new 创建会话（mcpServers:{}）-> session/prompt（blocks 数组）-> SSE
      agent_message_chunk 流式聚合 + result.stopReason 判定回合结束
    * 同一 sessionId 连续 prompt = 多轮会话续接（dsh 侧记忆连续）
    * 本地内存记录会话历史（ACP 无 history RPC）
    * 原有 headless /run 保留为 fallback（ACP 不可用时降级）

用法：
    python dsh_bridge_server.py [--port 8218] [--token xxx] [--dsh dsh]
                                [--dsh-home C:\\...\\dsh-home] [--cwd 默认工作目录]
                                [--acp-base http://127.0.0.1:7800] [--acp-token acp-test-token]

    --token 必填（安全红线）：App 调用时带 Authorization: Bearer <token>。
    --acp-token 需与电脑端 `dsh --profile acp serve --token xxx` 一致。

端点（除 /health、/pair 外均需 Bearer token）：
    GET  /health            存活检查（免鉴权）
    GET  /pair              扫码配对页（仅 127.0.0.1 可访问）
    GET  /pair.json         配对信息（qr_text/base_url/token，仅本机）
    GET  /status            桥接+ACP 通道可用性
    POST /run               执行 dsh headless 任务（v1 保留，fallback）
    POST /session           官方会话通道（v3 = ACP 后端）
                             body:
                               {"action":"start"}                                   -> 创建会话，返回 session_id
                               {"action":"prompt","session_id":"...","text":"..."}  -> 同会话续接，返回 reply
                               {"action":"history","session_id":"...","max":10}     -> 读会话历史（本桥接内存记录）
                               {"action":"get_status"}                              -> ACP 状态
                               {"action":"set_config","key":"cwd","value":"..."}    -> 设置默认工作目录

安全说明：
    * ACP serve 由电脑端 dsh 提供（--profile acp serve --host 0.0.0.0 --port 7800 --token xxx，
      bearer 鉴权）；手机只访问本桥接层（token 鉴权），不直接接触 7800。
    * 必须带 token 访问 /run、/status、/session；启动日志会打印一次 token。
    * 本服务默认监听 0.0.0.0（同一 Wi-Fi 手机可达）；建议仅内网使用，公网请走 Tailscale/FRP 等隧道。
"""
import argparse
import io
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = None
DSH_CMD = "dsh"
DSH_HOME = None
BRIDGE_PORT = 8218
DEFAULT_CWD = None
RUNNING_JOBS = {}
SESSION_HISTORY = {}          # sid -> [{role, text}]
ACP_BASE = "http://127.0.0.1:7800"
ACP_TOKEN = "acp-test-token"


def log(msg):
    print("[dsh-bridge %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ---------------------------------------------------------------------------
# ACP 客户端（dsh-acp-server HTTP serve，ACP v1 JSON-RPC + SSE）
# ---------------------------------------------------------------------------
class AcpClient:
    def __init__(self):
        self.base = ACP_BASE
        self.token = ACP_TOKEN
        self.conn = None          # acp-connection-id
        self.events = []
        self.lock = threading.Lock()
        self._sse_stop = threading.Event()
        self._sse_thread = None

    def _post(self, path, body, headers=None, timeout=30):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method="POST")
        req.add_header("Content-Type", "application/json; charset=utf-8")
        req.add_header("Authorization", "Bearer " + self.token)
        if self.conn and headers is None:
            req.add_header("Acp-Connection-Id", self.conn)
        if headers:
            for k, v in headers.items():
                req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read().decode("utf-8"), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")[:300], dict(e.headers)
        except Exception as e:
            return 0, "request failed: %s" % e, {}

    def _sse_loop(self):
        try:
            req = urllib.request.Request(self.base + "/acp/stream")
            req.add_header("Authorization", "Bearer " + self.token)
            req.add_header("Acp-Connection-Id", self.conn)
            with urllib.request.urlopen(req, timeout=600) as r:
                while not self._sse_stop.is_set():
                    line = r.readline()
                    if not line:
                        break
                    t = line.decode("utf-8", "replace").strip()
                    if t.startswith("data:"):
                        raw = t[5:].strip()
                        if not raw:
                            continue
                        try:
                            ev = json.loads(raw)
                            with self.lock:
                                self.events.append(ev)
                        except Exception:
                            pass
        except Exception as e:
            with self.lock:
                self.events.append({"sse_error": str(e)})

    def ensure(self):
        if self.conn:
            return True
        st, body, hdrs = self._post("/acp", {"jsonrpc": "2.0", "id": "1",
                                             "method": "initialize",
                                             "params": {"protocolVersion": 1,
                                                        "clientCapabilities": {}}})
        if st != 200:
            log("ACP initialize 失败: %s %s" % (st, body[:200]))
            return False
        conn = hdrs.get("Acp-Connection-Id") or hdrs.get("acp-connection-id")
        if not conn:
            # urllib 的 dict(r.headers) 键大小写可能被归一化，兜底遍历
            for k, v in hdrs.items():
                if k.lower() == "acp-connection-id":
                    conn = v
                    break
        if not conn:
            log("ACP initialize 无连接头（%s）" % json.dumps(hdrs)[:200])
            return False
        self.conn = conn
        self._sse_stop = threading.Event()
        self._sse_thread = threading.Thread(target=self._sse_loop, daemon=True)
        self._sse_thread.start()
        time.sleep(1)
        log("ACP 连接建立: %s" % conn)
        return True

    def session_new(self, cwd=None):
        if not self.ensure():
            return None, "ACP 未连接（请确认电脑端 dsh --profile acp serve 已在 7800 运行）"
        params = {"cwd": cwd or DEFAULT_CWD or ".", "mode": "default",
                  "mpker": None, "mcpServers": {}, "clientSessionConfig": {}}
        rid = str(int(time.time() * 1000) % 100000) + "-n"
        st, body, _ = self._post("/acp", {"jsonrpc": "2.0", "id": rid, "method": "session/new",
                                          "params": params})
        if st not in (200, 202):
            return None, "session/new HTTP %d: %s" % (st, body[:200])
        t0 = time.time()
        while time.time() - t0 < 60:
            with self.lock:
                for e in self.events:
                    if e.get("id") == rid and isinstance(e.get("result"), dict):
                        sid = e["result"].get("sessionId")
                        if sid:
                            return sid, None
                    if e.get("id") == rid and e.get("error"):
                        return None, json.dumps(e["error"], ensure_ascii=False)[:300]
            time.sleep(0.5)
        return None, "session/new 超时"

    def prompt(self, sid, text, timeout=240):
        if not self.ensure():
            return None, "ACP 未连接"
        rid = str(int(time.time() * 1000) % 100000) + "-p"
        st, body, _ = self._post("/acp", {"jsonrpc": "2.0", "id": rid, "method": "session/prompt",
                                          "params": {"sessionId": sid,
                                                     "prompt": [{"type": "text", "text": text}]}})
        if st not in (200, 202):
            return None, "session/prompt HTTP %d: %s" % (st, body[:200])
        t0 = time.time()
        chunks = []
        while time.time() - t0 < timeout:
            with self.lock:
                done = [e for e in self.events
                        if str(e.get("id")) == rid and isinstance(e.get("result"), dict)
                        and e["result"].get("stopReason")]
                if done:
                    for e2 in self.events:
                        p = e2.get("params") or {}
                        u = p.get("update") or {}
                        if u.get("sessionUpdate") == "agent_message_chunk" \
                                and p.get("sessionId") == sid:
                            ct = u.get("content") or {}
                            if ct.get("type") == "text" and ct.get("text"):
                                chunks.append(ct["text"])
                    return "".join(chunks), None
                err = [e for e in self.events
                       if str(e.get("id")) == rid and e.get("error")]
                if err:
                    return None, json.dumps(err[-1]["error"], ensure_ascii=False)[:300]
            time.sleep(0.8)
        return None, "等待回复超时(%ds)" % timeout

    def alive(self):
        try:
            req = urllib.request.Request(self.base + "/acp/healthz")
            req.add_header("Authorization", "Bearer " + self.token)
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status == 200
        except Exception:
            return False


ACP = AcpClient()


def acp_session_new(cwd=None):
    sid, err = ACP.session_new(cwd)
    if sid:
        SESSION_HISTORY[sid] = []
    return sid, err


def acp_session_prompt(session_id, text, timeout=240):
    reply, err = ACP.prompt(session_id, text, timeout=timeout)
    if err is None:
        hist = SESSION_HISTORY.setdefault(session_id, [])
        hist.append({"role": "user", "text": text[:2000]})
        hist.append({"role": "assistant", "text": (reply or "")[:2000]})
        if len(hist) > 200:
            del hist[: len(hist) - 200]
    return reply, err


def acp_session_history(session_id, max_msgs=10):
    hist = SESSION_HISTORY.get(session_id)
    if not hist:
        return None, "ACP 会话无本地历史（会话需在本桥接创建过）"
    return list(hist[-max(1, min(200, max_msgs)):]), None


def acp_session_list():
    return list(SESSION_HISTORY.keys()), None


# ---------------------------------------------------------------------------
# v1 headless 通道（保留为 fallback）
# ---------------------------------------------------------------------------
def _default_route_ip():
    """默认路由出口 IP（UDP connect 小技巧，不发包）"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return None


def local_ips():
    """本机所有可供手机访问的候选 IPv4（默认路由 IP 排第一）。

    为什么不能只取一个：这台电脑同时接了以太网(192.168.1.5) 和 Wi-Fi(192.168.101.20)，
    而手机在哪个网段是未知的 —— 只报默认路由那个，手机在另一个网段时就会"IP 对不上"（实测踩到）。
    另外注意：socket.gethostname() 解析在本机只返回 192.168.1.5，拿不到第二张网卡，
    所以 Windows 下用 PowerShell 枚举网卡地址。
    """
    found = []
    default = _default_route_ip()
    if os.name == "nt":
        try:
            # 只取"首选(Preferred)"地址：网卡断开后 Windows 仍会留着 Deprecated 的旧地址，
            # 之前就是把已断开的 Wi-Fi 192.168.101.20 当候选报给手机 → 手机 ARP 不到、报 IP 对不上（实测）
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 "Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred | ForEach-Object { $_.IPAddress }"],
                capture_output=True, text=True, timeout=10).stdout
            found.extend([ln.strip() for ln in out.splitlines() if ln.strip()])
        except Exception as e:
            log("枚举本机网卡地址失败（退回默认路由 IP）: %s" % e)
    if not found:
        try:
            found.extend([a[4][0] for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)])
        except Exception:
            pass

    ips = []
    for ip in ([default] if default else []) + found:
        if not ip or ip.startswith("127.") or ip.startswith("169.254."):
            continue
        if ip not in ips:
            ips.append(ip)
    return ips or ["127.0.0.1"]


def local_ip():
    """默认（第一个）候选 IP —— 供只认单个地址的老逻辑使用"""
    return local_ips()[0]


def pair_qr_text(port, token, ip=None):
    return "dshpair://%s:%d?token=%s" % (ip or local_ip(), port, token)


def pair_candidates(port, token):
    """所有候选配对信息：手机连哪个网，就扫那个网段的二维码"""
    return [{"ip": ip, "base_url": "http://%s:%d" % (ip, port), "qr_text": pair_qr_text(port, token, ip)}
            for ip in local_ips()]


def pair_html(port, token):
    qr_js = ""
    qjs = os.path.join(os.path.dirname(os.path.abspath(__file__)), "qrcodegen.js")
    try:
        with io.open(qjs, encoding="utf-8") as f:
            qr_js = f.read()
    except Exception as e:
        log("pair_html qrcodegen.js 读取失败: %s (%s)" % (repr(e), qjs))
        qr_js = "// qrcodegen.js 缺失，无法渲染二维码"
    items_json = json.dumps(pair_candidates(port, token), ensure_ascii=False)
    html = u"""<!doctype html>
<html><head><meta charset="utf-8"><title>答题宝 · dsh 远程配对</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{font-family:system-ui,-apple-system,sans-serif;display:flex;flex-direction:column;align-items:center;
background:#0f172a;color:#e2e8f0;min-height:100vh;margin:0;padding:24px;box-sizing:border-box}
h1{font-size:20px;margin:0 0 8px} h2{font-size:14px;color:#94a3b8;font-weight:normal;margin:0 0 16px}
#qrs{display:flex;flex-wrap:wrap;gap:20px;justify-content:center;margin:8px 0}
.card{display:flex;flex-direction:column;align-items:center}
.qrbox{background:#fff;padding:14px;border-radius:12px;box-shadow:0 8px 24px rgba(0,0,0,.4)}
.ip{font-family:ui-monospace,monospace;font-size:13px;color:#7dd3fc;margin-top:8px}
.url{font-family:ui-monospace,monospace;font-size:12px;word-break:break-all;background:#1e293b;
padding:10px 12px;border-radius:8px;max-width:92vw;color:#7dd3fc}
.steps{max-width:430px;font-size:14px;line-height:1.8;color:#94a3b8;margin-top:16px}
.steps b{color:#e2e8f0} code{background:#1e293b;padding:2px 6px;border-radius:4px;color:#7dd3fc}
</style></head><body>
<h1>答题宝 · 远程 dsh 配对</h1><h2>手机连的是哪个网，就扫那个网段的二维码</h2>
<div id="qrs"></div>
<div class="url" id="url"></div>
<div class="steps">
<b>配对步骤：</b><br>
1. 手机打开「答题宝」→ AI 对话<br>
2. 对 AI 说「<b>远程控制电脑 / 远程配对</b>」（会调用 remote_dsh 的 pair 动作）<br>
3. 用手机扫描上方二维码 → 自动保存电脑地址与令牌<br>
4. 完成，之后可以直接让 AI 远程控制电脑（支持多轮会话续接）<br><br>
<b>扫码后连不上 / 提示"同一网络、IP 对不上"？</b>说明手机和电脑不在同一个网段 —— 换上面另一个二维码扫即可
（本机有多个网卡时会列出多个候选地址，例如以太网 192.168.1.x 与 Wi-Fi 192.168.101.x）。<br><br>
<b>安全：</b>本页面仅电脑本机（127.0.0.1）可访问；二维码里的令牌不会暴露给局域网其他设备。
</div>
<script>
""" + qr_js + """
(function(){
  var items = __ITEMS__;
  var box = document.getElementById('qrs');
  items.forEach(function(it){
    var card = document.createElement('div'); card.className = 'card';
    var qrbox = document.createElement('div'); qrbox.className = 'qrbox';
    var qr = qrcode(0,'M'); qr.addData(it.qr_text); qr.make();
    qrbox.innerHTML = qr.createImgTag(5,12);
    var label = document.createElement('div'); label.className = 'ip';
    label.textContent = it.ip + '  →  ' + it.base_url;
    card.appendChild(qrbox); card.appendChild(label); box.appendChild(card);
  });
  document.getElementById('url').textContent = items.length ? items[0].qr_text : '';
})();
</script></body></html>"""
    return html.replace("__ITEMS__", items_json).encode("utf-8")


def resolve_dsh_cmd(name):
    if os.path.sep in name or (os.path.altsep and os.path.altsep in name):
        return name, name.lower().endswith((".cmd", ".bat"))
    found = shutil.which(name)
    if not found:
        for cand in (
            os.path.expanduser(r"~\.workbuddy\binaries\node\versions\22.22.2\dsh.cmd"),
        ):
            if os.path.isfile(cand):
                found = cand
                break
    if not found:
        return name, False
    return found, found.lower().endswith((".cmd", ".bat"))


def run_dsh(task, timeout):
    env = dict(os.environ)
    if DSH_HOME:
        env["DSH_HOME"] = DSH_HOME
    dsh_path, is_cmd = resolve_dsh_cmd(DSH_CMD)
    if is_cmd:
        cmd = ["cmd", "/c", dsh_path, "--profile", "headless", task]
    else:
        cmd = [dsh_path, "--profile", "headless", task]
    start = time.time()
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout, env=env,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        dur = int((time.time() - start) * 1000)
        out = (proc.stdout or "") + (("\n[stderr]\n" + proc.stderr) if proc.stderr and proc.stderr.strip() else "")
        return proc.returncode == 0, out.strip(), proc.returncode, dur
    except subprocess.TimeoutExpired:
        dur = int((time.time() - start) * 1000)
        return False, "任务超时（%ds）" % timeout, -1, dur
    except FileNotFoundError as e:
        return False, "找不到 dsh 命令: %s" % e, -1, 0
    except Exception as e:
        dur = int((time.time() - start) * 1000)
        return False, "执行异常: %s" % e, -1, dur


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return secrets.compare_digest(auth[len("Bearer "):].strip(), TOKEN)
        x = self.headers.get("X-Auth-Token", "")
        return bool(x) and secrets.compare_digest(x.strip(), TOKEN)

    def _read_json(self):
        try:
            ln = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(ln) if ln else b"{}"
            return json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            return {}

    def log_message(self, fmt, *args):
        log("%s %s" % (self.address_string(), fmt % args))

    def _is_loopback(self):
        host = self.client_address[0] if self.client_address else ""
        return host in ("127.0.0.1", "::1", "localhost")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            return self._send_json(200, {"ok": True, "service": "dsh-bridge", "version": 3,
                                         "backend": "acp"})
        if path == "/pair":
            if not self._is_loopback():
                return self._send_json(403, {"ok": False, "error": "pair page is local-only"})
            html = pair_html(BRIDGE_PORT, TOKEN)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(html)
            return
        if path == "/pair.json":
            if not self._is_loopback():
                return self._send_json(403, {"ok": False, "error": "pair info is local-only"})
            cands = pair_candidates(BRIDGE_PORT, TOKEN)
            return self._send_json(200, {
                "ok": True,
                # 兼容老字段：默认（第一个候选）地址
                "qr_text": cands[0]["qr_text"] if cands else "",
                "base_url": cands[0]["base_url"] if cands else "http://127.0.0.1:%d" % BRIDGE_PORT,
                "token": TOKEN,
                # 多网卡候选：手机在哪个网段就扫哪个
                "candidates": cands,
            })
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "unauthorized"})
        if path == "/status":
            acp_ok = ACP.alive()
            headless_ok = None
            probe = None
            if not acp_ok:
                ok, out, code, dur = run_dsh("只回复两个字：OK", 30)
                headless_ok, probe = ok, out[:200]
            items, lerr = (acp_session_list() if acp_ok else (None, None))
            return self._send_json(200, {
                "ok": True, "version": 3, "backend": "acp",
                "channels": {"session_acp": acp_ok, "headless": headless_ok},
                "acp_base": ACP_BASE,
                "sessions_count": (len(items) if items is not None else 0),
                "probe_output": probe,
                "active_jobs": len(RUNNING_JOBS),
            })
        return self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "unauthorized"})
        path = self.path.split("?")[0]
        body = self._read_json()

        if path == "/run":
            task = str(body.get("task", "")).strip()
            if not task:
                return self._send_json(400, {"ok": False, "error": "task 不能为空"})
            try:
                timeout = max(5, min(600, int(body.get("timeout", 120))))
            except Exception:
                timeout = 120
            log("headless 任务(%ds): %s" % (timeout, task[:120]))
            ok, out, code, dur = run_dsh(task, timeout)
            log("headless 完成: ok=%s exit=%s dur=%dms" % (ok, code, dur))
            return self._send_json(200, {
                "ok": ok, "output": out, "exit_code": code, "duration_ms": dur, "task": task,
            })

        if path == "/session":
            return self._handle_session(body)
        return self._send_json(404, {"ok": False, "error": "not found"})

    def _handle_session(self, body):
        action = str(body.get("action", "")).strip()
        t0 = time.time()

        if action == "start":
            sid, err = acp_session_new(str(body.get("cwd") or "").strip() or None)
            if err:
                return self._send_json(200, {"ok": False, "action": "start", "error": err,
                                             "fallback": "headless"})
            return self._send_json(200, {"ok": True, "action": "start", "session_id": sid,
                                         "duration_ms": int((time.time() - t0) * 1000)})

        if action == "prompt":
            sid = str(body.get("session_id") or "").strip()
            text = str(body.get("text") or "").strip()
            if not sid:
                return self._send_json(400, {"ok": False, "action": "prompt",
                                             "error": "session_id 不能为空（先 action=start 创建）"})
            if not text:
                return self._send_json(400, {"ok": False, "action": "prompt", "error": "text 不能为空"})
            try:
                timeout = max(30, min(600, int(body.get("timeout", 240))))
            except Exception:
                timeout = 240
            log("会话任务(%ds): sid=%s text=%s" % (timeout, sid, text[:120]))
            reply, err = acp_session_prompt(sid, text, timeout=timeout)
            dur = int((time.time() - t0) * 1000)
            if err:
                log("会话失败: %s" % err)
                return self._send_json(200, {"ok": False, "action": "prompt", "session_id": sid,
                                             "error": err, "duration_ms": dur})
            log("会话完成: dur=%dms reply=%s" % (dur, (reply or "")[:80]))
            return self._send_json(200, {"ok": True, "action": "prompt", "session_id": sid,
                                         "reply": reply or "", "duration_ms": dur})

        if action == "history":
            sid = str(body.get("session_id") or "").strip()
            try:
                max_msgs = max(1, min(200, int(body.get("max", 10))))
            except Exception:
                max_msgs = 10
            if not sid:
                return self._send_json(400, {"ok": False, "action": "history", "error": "session_id 不能为空"})
            msgs, err = acp_session_history(sid, max_msgs)
            if err:
                return self._send_json(200, {"ok": False, "action": "history", "error": err})
            # 文本化（App 端轻量 JSON 解析不支持嵌套数组）
            lines = []
            for i, m in enumerate(msgs, 1):
                role = "用户" if m.get("role") == "user" else "AI"
                lines.append("%d. [%s] %s" % (i, role, m.get("text", "")))
            return self._send_json(200, {"ok": True, "action": "history", "session_id": sid,
                                         "text": "\n".join(lines) or "(空)", "count": len(msgs)})

        if action == "get_status":
            return self._send_json(200, {
                "ok": True, "action": "get_status", "backend": "acp",
                "acp_alive": ACP.alive(), "sessions": list(SESSION_HISTORY.keys()),
            })

        if action == "set_config":
            key = str(body.get("key") or "").strip()
            value = str(body.get("value") or "").strip()
            if key == "cwd":
                global DEFAULT_CWD
                DEFAULT_CWD = value or None
                return self._send_json(200, {"ok": True, "action": "set_config",
                                             "key": key, "value": DEFAULT_CWD})
            return self._send_json(400, {"ok": False, "action": "set_config",
                                         "error": "仅支持 cwd 配置"})

        return self._send_json(400, {"ok": False, "action": action, "error": "未知动作"})


def main():
    global BRIDGE_PORT, TOKEN, DSH_CMD, DSH_HOME, ACP_BASE, ACP_TOKEN, DEFAULT_CWD
    ap = argparse.ArgumentParser(description="答题宝 · dsh 远程桥接 v3 (ACP 后端)")
    ap.add_argument("--port", type=int, default=8218)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--token", default="")
    ap.add_argument("--dsh", default="dsh")
    ap.add_argument("--dsh-home", default=os.environ.get("DSH_HOME", ""))
    ap.add_argument("--cwd", default="", help="会话默认工作目录（dsh 里跑命令的目录）")
    ap.add_argument("--acp-base", default="http://127.0.0.1:7800", help="ACP serve 地址")
    ap.add_argument("--acp-token", default="acp-test-token", help="ACP serve bearer token")
    args = ap.parse_args()
    BRIDGE_PORT, TOKEN = args.port, args.token
    DSH_CMD, DSH_HOME = args.dsh, args.dsh_home
    ACP_BASE, ACP_TOKEN = args.acp_base.rstrip("/"), args.acp_token
    DEFAULT_CWD = args.cwd.strip() or None
    if not TOKEN:
        log("未配置 --token：App 将无法鉴权（拒绝所有请求）。请传 --token xxx")
    ACP.base, ACP.token = ACP_BASE, ACP_TOKEN
    log("dsh 桥接服务 v3 (ACP) 启动: http://%s:%d" % (args.host, args.port))
    log("访问令牌(请复制到 App 配置): %s" % TOKEN)
    log("ACP 后端: %s | 默认工作目录: %s" % (ACP_BASE, DEFAULT_CWD or "(未指定)"))
    log("配对页(本机浏览器): http://127.0.0.1:%d/pair  (手机扫码=一键配对)" % args.port)
    for c in pair_candidates(args.port, TOKEN):
        log("配对候选: %s   (手机手动输入: %s  token=%s)" % (c["ip"], c["base_url"], TOKEN))
    try:
        webbrowser.open("http://127.0.0.1:%d/pair" % args.port)
    except Exception:
        pass
    srv = ThreadingHTTPServer((args.host, BRIDGE_PORT), Handler)
    log("桥接 v3 (ACP) 监听 http://%s:%d  token=%s" % (args.host, BRIDGE_PORT, TOKEN))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断, 退出")


if __name__ == "__main__":
    main()
