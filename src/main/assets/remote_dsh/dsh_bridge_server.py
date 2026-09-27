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
    POST /exec              直接执行一条本机命令（不经 LLM，快且输出原样；body: cmd/cwd/shell/timeout）
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
import re
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
# 公网/隧道地址（花生壳 http://xxx.vicp.net:12345、Cloudflare https://xxx.trycloudflare.com、frp 等）：
# 设置后会作为额外配对候选出现在配对页，手机在任何网络都能扫它配对
PUBLIC_URL = ""
# ACP 权限请求策略（2026-09-27 实测新增）：
#   allow（默认）= 自动回 allow-once；deny = 自动回 reject-once（只读任务可跑，写/执行类被拒）
# 背景：写文件/跑命令这类工具调用，dsh ACP 会先发 session/request_permission 并**等客户端回答**；
# 该请求只投递到"会话流"(GET /acp/stream + Acp-Session-Id)，此前桥接只开了连接流、也从不回答，
# 导致这类任务永久挂起直到超时（手机端只看到"等待回复超时"）。应答记录见 /status 的 permissions。
PERMISSION_POLICY = "allow"
# 同时在跑的任务上限（headless /run 与直连 /exec 共用），防止手机端猛点把电脑压满
MAX_CONCURRENT_JOBS = 4
# 每个会话一条常驻 SSE 流，这里给个上限（超出关最旧的，下次用到会自动重开）
MAX_SESSION_STREAMS = 6


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
        # 每会话一条 SSE 流：权限请求只会投递到会话流（见 PERMISSION_POLICY 注释）
        self.session_streams = {}   # sid -> {"stop": Event, "thread": Thread}
        self.perm_events = []       # 最近的权限应答记录（供 /status 审计）
        self.agent_info = None      # ACP initialize 自报的 agentInfo（name/version），供 /status 如实展示

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

    def _on_event(self, ev, label):
        """收事件：入队 + 处理服务端发来的请求（权限请求必须应答，否则 agent 永久等待）。"""
        if ev.get("method") == "session/request_permission":
            self._handle_permission(ev)
        with self.lock:
            self.events.append(ev)

    def _handle_permission(self, ev):
        """回答 dsh 的 session/request_permission（写文件/跑命令类工具会用到）。

        实测（2026-09-27）：该请求**只走会话流**；客户端不回答时 session/prompt 永不 settle，
        手机端表现为"等待回复超时"，用户看到的是"任务跑不了/ACP 有问题"。
        """
        rid = ev.get("id")
        params = ev.get("params") or {}
        sid = params.get("sessionId")
        if rid is None:
            return
        option = "reject-once" if str(PERMISSION_POLICY).lower() == "deny" else "allow-once"
        hdrs = {}
        if self.conn:
            hdrs["Acp-Connection-Id"] = self.conn
        if sid:
            hdrs["Acp-Session-Id"] = sid
        st, body, _ = self._post("/acp", {"jsonrpc": "2.0", "id": rid,
                                          "result": {"outcome": {"outcome": "selected",
                                                                 "optionId": option}}},
                                 headers=hdrs)
        rec = {"time": time.strftime("%H:%M:%S"), "session": sid,
               "tool": ((params.get("toolCall") or {}).get("toolCallId")),
               "option": option, "http": st}
        self.perm_events.append(rec)
        del self.perm_events[:-20]
        log("ACP 权限请求 → %s（HTTP %s, session=%s, tool=%s）"
            % (option, st, sid, rec["tool"]))

    def _sse_loop(self, label="CONN", session_id=None):
        try:
            req = urllib.request.Request(self.base + "/acp/stream")
            req.add_header("Authorization", "Bearer " + self.token)
            req.add_header("Accept", "text/event-stream")
            req.add_header("Acp-Connection-Id", self.conn)
            if session_id:
                req.add_header("Acp-Session-Id", session_id)
            stop = self.session_streams.get(session_id, {}).get("stop") if session_id else self._sse_stop
            with urllib.request.urlopen(req, timeout=600) as r:
                while not (stop and stop.is_set()):
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
                        except Exception:
                            continue
                        self._on_event(ev, label)
        except Exception as e:
            with self.lock:
                self.events.append({"sse_error": str(e), "label": label})
            if session_id:
                # 会话流意外断开：标记，下次 prompt 前重开（不回填事件，只保证后续能收到）
                log("会话流断开(%s): %s" % (session_id, e))

    def open_session_stream(self, sid):
        """为会话开一条 SSE 流（幂等）。权限请求与 agent 消息都只投递到这条流。"""
        if not sid:
            return False
        cur = self.session_streams.get(sid)
        if cur and cur.get("thread") and cur["thread"].is_alive():
            return True
        # 上限：会话流按会话常驻，长期使用会累积（实测一天测试下来 5 条）。
        # 超过上限就关掉最久没碰过的那条；该会话下次 prompt 前会自动重开（open_session_stream 幂等），
        # 期间消息由服务端会话信箱排队，不会丢。
        while len(self.session_streams) >= MAX_SESSION_STREAMS:
            oldest = min(self.session_streams.items(), key=lambda kv: kv[1].get("opened", 0))
            try:
                oldest[1]["stop"].set()
            except Exception:
                pass
            self.session_streams.pop(oldest[0], None)
            log("会话流超上限，已关闭最旧的一条: %s" % oldest[0])
        stop = threading.Event()
        th = threading.Thread(target=self._sse_loop, args=("SESS", sid), daemon=True)
        self.session_streams[sid] = {"stop": stop, "thread": th, "opened": time.time()}
        th.start()
        log("会话流已建立: %s" % sid)
        return True

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
        # 如实记下 ACP 自报的版本（App 的状态里不再写死 "dsh 0.1.5" 这种会误导的版本号）
        try:
            self.agent_info = (json.loads(body) or {}).get("result", {}).get("agentInfo")
        except Exception:
            self.agent_info = None
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
        # 新连接：旧连接的会话流已失效，全部停掉
        for s in list(self.session_streams.values()):
            try:
                s["stop"].set()
            except Exception:
                pass
        self.session_streams.clear()
        self.conn = conn
        self._sse_stop = threading.Event()
        self._sse_thread = threading.Thread(target=self._sse_loop, args=("CONN",), daemon=True)
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
                            # 关键：会话流必须在首次 prompt 前建好，否则权限请求/消息无人接收
                            self.open_session_stream(sid)
                            return sid, None
                    if e.get("id") == rid and e.get("error"):
                        return None, json.dumps(e["error"], ensure_ascii=False)[:300]
            time.sleep(0.5)
        return None, "session/new 超时"

    def prompt(self, sid, text, timeout=240):
        if not self.ensure():
            return None, "ACP 未连接"
        # 会话流可能因网络/超时断过：发 prompt 前确保它在（否则收不到消息与权限请求）
        self.open_session_stream(sid)
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

    # 最近一次探测结果（供 /status 的 probe_output 展示，便于排错）
    last_probe = None

    def alive(self, attempts=2):
        """ACP 是否可用。失败重试一次：实测偶发瞬时探测失败会让 App 误报"ACP 不可用 ✗"。"""
        last = None
        for i in range(max(1, attempts)):
            try:
                req = urllib.request.Request(self.base + "/acp/healthz")
                req.add_header("Authorization", "Bearer " + self.token)
                with urllib.request.urlopen(req, timeout=5) as r:
                    if r.status == 200:
                        self.last_probe = "ok (HTTP 200)"
                        return True
                    last = "HTTP %s" % r.status
            except Exception as e:
                last = "%s: %s" % (type(e).__name__, e)
            if i + 1 < max(1, attempts):
                time.sleep(0.4)
        self.last_probe = last or "unknown"
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
    """所有候选配对信息：手机连哪个网，就扫那个网段的二维码；配了公网/隧道地址时它排第一"""
    cands = []
    if PUBLIC_URL:
        m = re.match(r"^(https?)://([^/:]+)(?::(\d+))?/?$", PUBLIC_URL.strip())
        if not m:
            log("--public-url 无法解析（应形如 http://host:port 或 https://host），已忽略: %s" % PUBLIC_URL)
        else:
            scheme, host, p = m.group(1), m.group(2), m.group(3)
            base = "%s://%s%s" % (scheme, host, (":" + p) if p else "")
            if p:
                # 手机扫码协议是 dshpair://host:port?token=...（只带 host+port），这里能直接扫
                cands.append({"ip": host + ":" + p, "base_url": base,
                              "qr_text": "dshpair://%s:%s?token=%s" % (host, p, token),
                              "label": "公网/隧道（花生壳等）"})
            else:
                # https 默认 443：把 scheme/port 放进 query，App 侧已支持解析（旧版 App 会提示升级）
                cands.append({"ip": host, "base_url": base,
                              "qr_text": "dshpair://%s?scheme=%s&port=443&token=%s" % (host, scheme, token),
                              "label": "公网/隧道（HTTPS）"})
    for ip in local_ips():
        cands.append({"ip": ip, "base_url": "http://%s:%d" % (ip, port),
                      "qr_text": pair_qr_text(port, token, ip), "label": "局域网"})
    return cands


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
.manual{background:#1e293b;color:#fbbf24;padding:14px 16px;border-radius:12px;font-family:ui-monospace,monospace;font-size:12px;white-space:pre-wrap;max-width:260px}
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
    if (!it.manual) {
      var qrbox = document.createElement('div'); qrbox.className = 'qrbox';
      var qr = qrcode(0,'M'); qr.addData(it.qr_text); qr.make();
      qrbox.innerHTML = qr.createImgTag(5,12);
      card.appendChild(qrbox);
    } else {
      var mbox = document.createElement('div'); mbox.className = 'manual';
      mbox.textContent = it.base_url + '  (手动输入该地址；token 见页面下方 URL 行)';
      card.appendChild(mbox);
    }
    var label = document.createElement('div'); label.className = 'ip';
    label.textContent = (it.label ? it.label + ' — ' : '') + it.ip;
    card.appendChild(label); box.appendChild(card);
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


def _powershell_exe():
    for name in ("pwsh", "powershell"):
        if shutil.which(name):
            return name
    return "pwsh"


def run_exec(cmd, timeout, shell="", cwd=None):
    """直接执行一条本机命令（不经 LLM），返回 (ok, output, exit_code, dur_ms)。

    用途：手机让它"跑这条命令并把输出原样贴回来"时，走 /run 要起一个完整 dsh agent（几十秒 + 消耗 token），
    而这里就是一条命令的时间。写/执行类操作在 dsh 里需要用户授权，这里由桥接 token 把关（等价权限）。
    """
    start = time.time()
    sh = (shell or "auto").strip().lower()
    if sh in ("cmd", "cmd.exe"):
        argv = ["cmd", "/c", cmd]
    elif sh in ("bash", "sh"):
        argv = ["bash", "-lc", cmd]
    else:
        exe = _powershell_exe()
        # powershell.exe(5.1) 重定向输出默认走 OEM 代码页，中文会乱码；显式置 UTF-8
        prefix = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " if exe == "powershell" else ""
        argv = [exe, "-NoProfile", "-NonInteractive", "-Command", prefix + cmd]
    try:
        proc = subprocess.run(
            argv, capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=timeout, cwd=(cwd or DEFAULT_CWD),
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        dur = int((time.time() - start) * 1000)
        out = proc.stdout or ""
        if proc.stderr and proc.stderr.strip():
            out += ("\n[stderr]\n" + proc.stderr)
        return proc.returncode == 0, out.strip(), proc.returncode, dur
    except subprocess.TimeoutExpired as e:
        dur = int((time.time() - start) * 1000)
        partial = e.stdout or ""
        if isinstance(partial, bytes):
            partial = partial.decode("utf-8", "replace")
        out = "命令超时（%ds）" % timeout
        if partial and str(partial).strip():
            out += "\n[已产生的输出]\n" + str(partial).strip()
        return False, out, -1, dur
    except FileNotFoundError as e:
        return False, "找不到执行器: %s" % e, -1, 0
    except Exception as e:
        return False, "执行异常: %s" % e, -1, int((time.time() - start) * 1000)


class BridgeHTTPServer(ThreadingHTTPServer):
    """忽略"客户端提前断开"类噪声。

    手机端工具超时/取消会直接掐断 TCP，socketserver 默认把 ConnectionResetError
    打成整页 traceback（实测 07:2x 日志被刷满），既掩盖真问题又难读。
    """
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
            return
        return ThreadingHTTPServer.handle_error(self, request, client_address)


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
        """是否真的是"电脑本机"访问（/pair 与 /pair.json 只允许本机）。

        2026-09-26 实测踩到的漏洞：走 Cloudflare 隧道时，cloudflared 是**从 127.0.0.1 连过来**的，
        只看 client_address 会让公网访客也能打开 /pair.json 并把 token 读走。
        所以额外要求 Host 是回环地址，且没有任何代理/隧道头。
        """
        host_hdr = (self.headers.get("Host") or "").strip().lower()
        host_only = host_hdr.split(":")[0]
        if host_only not in ("127.0.0.1", "localhost", "::1", "[::1]"):
            return False
        for h in ("CF-Connecting-IP", "CF-Ray", "X-Forwarded-For", "X-Real-IP", "Forwarded"):
            if self.headers.get(h):
                return False
        peer = self.client_address[0] if self.client_address else ""
        return peer in ("127.0.0.1", "::1", "localhost")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            return self._send_json(200, {"ok": True, "service": "dsh-bridge", "version": 4,
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
            probe = ACP.last_probe
            # 说明：这里以前在 ACP 探测失败时会跑一次 30 秒的 headless 任务来"验证兜底通道"，
            # 结果是"查个状态"要等 30 秒（App 侧 30s 读超时刚好踩线）。改成不主动跑：
            # headless 是 run 时的降级路径，真的要用时再打，状态里只报 ACP 探测结果。
            if acp_ok and not ACP.agent_info:
                # 首次查询状态时把连接建起来：既拿到 ACP 自报的版本（App 状态里如实展示），
                # 也让之后的第一次 prompt 少一次握手（连接会被复用）。
                try:
                    ACP.ensure()
                except Exception as e:
                    log("状态查询时建立 ACP 连接失败: %s" % e)
            items, lerr = (acp_session_list() if acp_ok else (None, None))
            return self._send_json(200, {
                "ok": True, "version": 4, "backend": "acp",
                "channels": {"session_acp": acp_ok, "headless": headless_ok},
                "acp_base": ACP_BASE,
                "acp_agent": ACP.agent_info,
                "sessions_count": (len(items) if items is not None else 0),
                "probe_output": probe,
                "active_jobs": len(RUNNING_JOBS),
                "session_streams": sorted(ACP.session_streams.keys()),
                "permission_policy": PERMISSION_POLICY,
                "permissions": ACP.perm_events[-5:],
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

        if path == "/exec":
            cmd = str(body.get("cmd", "")).strip()
            if not cmd:
                return self._send_json(400, {"ok": False, "error": "cmd 不能为空"})
            try:
                timeout = max(1, min(600, int(body.get("timeout", 60))))
            except Exception:
                timeout = 60
            if len(RUNNING_JOBS) >= MAX_CONCURRENT_JOBS:
                return self._send_json(429, {"ok": False,
                                             "error": "电脑端并发任务已满(%d)，请稍后再试" % MAX_CONCURRENT_JOBS})
            job_id = uuid.uuid4().hex[:12]
            RUNNING_JOBS[job_id] = {"kind": "exec", "cmd": cmd[:200], "start": time.time()}
            log("直连命令(%ds): %s" % (timeout, cmd[:160]))
            try:
                ok, out, code, dur = run_exec(cmd, timeout, str(body.get("shell") or ""),
                                              str(body.get("cwd") or "").strip() or None)
            finally:
                RUNNING_JOBS.pop(job_id, None)
            log("直连命令完成: ok=%s exit=%s dur=%dms" % (ok, code, dur))
            return self._send_json(200, {"ok": ok, "output": out, "exit_code": code,
                                         "duration_ms": dur, "cmd": cmd, "job_id": job_id})

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
    global BRIDGE_PORT, TOKEN, DSH_CMD, DSH_HOME, ACP_BASE, ACP_TOKEN, DEFAULT_CWD, PUBLIC_URL
    ap = argparse.ArgumentParser(description="答题宝 · dsh 远程桥接 v4 (ACP 后端)")
    ap.add_argument("--port", type=int, default=8218)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--token", default="")
    ap.add_argument("--dsh", default="dsh")
    ap.add_argument("--dsh-home", default=os.environ.get("DSH_HOME", ""))
    ap.add_argument("--cwd", default="", help="会话默认工作目录（dsh 里跑命令的目录）")
    ap.add_argument("--acp-base", default="http://127.0.0.1:7800", help="ACP serve 地址")
    ap.add_argument("--acp-token", default="acp-test-token", help="ACP serve bearer token")
    ap.add_argument("--permission", default="allow", choices=["allow", "deny"],
                    help="ACP 权限请求自动应答策略：allow=自动允许一次（默认，写文件/跑命令可用）；deny=自动拒绝")
    ap.add_argument("--public-url", default="",
                    help="公网/隧道地址（花生壳 http://xxx.vicp.net:12345 / Cloudflare https://xxx.trycloudflare.com 等），"
                         "会作为额外配对候选出现在配对页")
    args = ap.parse_args()
    global PERMISSION_POLICY
    BRIDGE_PORT, TOKEN = args.port, args.token
    PERMISSION_POLICY = args.permission
    DSH_CMD, DSH_HOME = args.dsh, args.dsh_home
    ACP_BASE, ACP_TOKEN = args.acp_base.rstrip("/"), args.acp_token
    PUBLIC_URL = args.public_url.strip()
    DEFAULT_CWD = args.cwd.strip() or None
    if not TOKEN:
        log("未配置 --token：App 将无法鉴权（拒绝所有请求）。请传 --token xxx")
    ACP.base, ACP.token = ACP_BASE, ACP_TOKEN
    log("dsh 桥接服务 v4 (ACP) 启动: http://%s:%d" % (args.host, args.port))
    log("访问令牌(请复制到 App 配置): %s" % TOKEN)
    log("ACP 后端: %s | 默认工作目录: %s" % (ACP_BASE, DEFAULT_CWD or "(未指定)"))
    log("ACP 权限策略: %s（写文件/跑命令类工具需应答，deny 时这类任务会被拒）" % PERMISSION_POLICY)
    log("配对页(本机浏览器): http://127.0.0.1:%d/pair  (手机扫码=一键配对)" % args.port)
    for c in pair_candidates(args.port, TOKEN):
        log("配对候选: %s   (手机手动输入: %s  token=%s)" % (c["ip"], c["base_url"], TOKEN))
    try:
        webbrowser.open("http://127.0.0.1:%d/pair" % args.port)
    except Exception:
        pass
    srv = BridgeHTTPServer((args.host, BRIDGE_PORT), Handler)
    log("桥接 v4 (ACP) 监听 http://%s:%d  token=%s" % (args.host, BRIDGE_PORT, TOKEN))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断, 退出")


if __name__ == "__main__":
    main()
