#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dsh 桥接服务 v2（电脑端）— 官方会话通道版
============================================
让手机端"答题宝"App 通过 HTTP 远程调用本机的 DeepSeek dsh（DeepSeek Harness Shell）。

v2 核心变化：新增**官方会话通道**（dsh web API，127.0.0.1:3080）：
    * session.create 创建会话 -> session.prompt 异步入队 -> 轮询 session.history 取回合结果
    * 同一 sessionId 连续 prompt = 多轮会话续接（dsh 侧记忆连续，事件溯源日志持久）
    * 原有 headless /run 保留为 fallback（web 通道不可用时自动降级）

用法：
    python dsh_bridge_server.py [--port 8218] [--token xxx] [--dsh dsh]
                                [--dsh-home C:\\...\\dsh-home] [--web-port 3080] [--cwd 默认工作目录]

    --token 必填（安全红线）：App 调用时带 Authorization: Bearer <token>。
            未提供时自动生成一个随机 token 并打印，复制到 App 配置即可。

端点（除 /health 外均需 Bearer token）：
    GET  /health            存活检查（免鉴权）
    GET  /status            桥接+dsh 双通道可用性
    POST /run               执行 dsh headless 任务（v1 保留，fallback）
                             body: {"task":"...", "timeout": 120}
    POST /session           官方会话通道（v2 新增）
                             body:
                               {"action":"start"}                                   -> 创建会话，返回 session_id
                               {"action":"prompt","session_id":"...","text":"..."}  -> 同会话续接，返回 reply
                               {"action":"history","session_id":"...","max":10}     -> 读会话历史（user/assistant 纯文本）
                               {"action":"list"}                                   -> 会话列表
                             resp: {"ok":bool,"session_id":"...","reply":"...","duration_ms":1234,
                                    "error":"...","fallback":"headless"|null}

安全说明：
    * dsh web 只监听 127.0.0.1:3080，永不直接暴露给手机；手机只访问本桥接层（token 鉴权）。
    * 必须带 token 访问 /run、/status、/session；启动日志会打印一次 token。
    * 本服务默认监听 0.0.0.0（同一 Wi-Fi 手机可达）；建议仅内网使用，公网请走 Tailscale/FRP 等隧道。
"""
import argparse
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = None
DSH_CMD = "dsh"
DSH_HOME = None
WEB_BASE = "http://127.0.0.1:3080"
DEFAULT_CWD = None
RUNNING_JOBS = {}


def log(msg):
    print("[dsh-bridge %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ---------------------------------------------------------------------------
# 官方会话通道（dsh web API，HTTP POST /api/<method>，RPC envelope）
# ---------------------------------------------------------------------------
def dsh_api(method, payload, timeout=30):
    """调用 dsh web 官方 API。返回解析后的 dict，失败时返回 {"error": ...}。"""
    body = json.dumps({
        "type": "client-request",
        "rpcId": str(uuid.uuid4()),
        "method": method,
        "payload": payload,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(WEB_BASE + "/api/" + method, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"error": "HTTP %d" % e.code, "body": e.read().decode("utf-8", "replace")[:200]}
    except Exception as e:
        return {"error": str(e)}


def web_alive():
    """探测 dsh web 通道是否可用（session.list 快速探测）。"""
    r = dsh_api("session.list", {}, timeout=5)
    return r.get("result", {}).get("ok") is True


def web_session_create(cwd=None):
    """创建官方会话。cwd 缺省用 DEFAULT_CWD。"""
    payload = {}
    if cwd:
        payload["cwd"] = cwd
    elif DEFAULT_CWD:
        payload["cwd"] = DEFAULT_CWD
    r = dsh_api("session.create", payload, timeout=15)
    if "error" in r:
        return None, r["error"]
    res = r.get("result", {})
    if not res.get("ok"):
        return None, json.dumps(res.get("error", r), ensure_ascii=False)[:300]
    return res.get("value", {}).get("sessionId"), None


def web_session_prompt(session_id, text, timeout=240, poll=2):
    """向会话发消息（异步入队）并轮询历史直到回合结束，返回 (reply, error, turn)。"""
    r = dsh_api("session.prompt", {
        "sessionId": session_id, "mode": "queue",
        "content": [{"type": "text", "text": text}],
    }, timeout=15)
    if "error" in r:
        return None, "prompt 调用失败: %s" % r["error"], None
    res = r.get("result", {})
    if not res.get("ok"):
        return None, "prompt 被拒绝: %s" % json.dumps(res.get("error", r), ensure_ascii=False)[:300], None

    # 记录 prompt 前的最新 seq，之后只看增量
    before = _history_tail_seq(session_id)
    t0 = time.time()
    last_reply = None
    last_turn = None
    while time.time() - t0 < timeout:
        h = dsh_api("session.history", {"sessionId": session_id, "maxMessages": 50}, timeout=15)
        if "error" not in h:
            events = h.get("result", {}).get("value", {}).get("events", [])
            turn_done = None
            reply = None
            for e in events:
                ev = e.get("event", {})
                seq = ev.get("seq", 0)
                if seq <= before:
                    continue
                et = ev.get("type", "")
                if et == "turn/end":
                    turn_done = ev.get("data", {}).get("turn")
                elif et == "assistant/message":
                    msg = (ev.get("data") or {}).get("message") or {}
                    if msg.get("role") == "assistant":
                        reply = _content_to_text(msg.get("content"))
                        last_turn = (ev.get("data") or {}).get("turn", last_turn)
            if reply:
                last_reply = reply
            if turn_done is not None and last_reply is not None:
                return last_reply, None, last_turn or turn_done
            if turn_done is not None:
                # 回合结束但没有 assistant 文本（空回复/异常）
                return last_reply, None, turn_done
        time.sleep(poll)
    if last_reply is not None:
        return last_reply, None, last_turn
    return None, "等待回复超时(%ds)" % timeout, last_turn


def _history_tail_seq(session_id):
    """读 history 尾部事件的最大 seq（用于增量判断）。"""
    h = dsh_api("session.history", {"sessionId": session_id, "maxMessages": 10}, timeout=10)
    try:
        events = h["result"]["value"]["events"]
        return max(ev.get("event", {}).get("seq", 0) for ev in events)
    except Exception:
        return 0


def _content_to_text(content):
    """assistant message content -> 纯文本。"""
    if not content:
        return ""
    parts = []
    for block in content:
        if not isinstance(block, dict):
            continue
        t = block.get("type")
        if t == "text":
            parts.append(block.get("text", ""))
        elif t in ("thinking", "tool_use", "tool_result"):
            parts.append("[%s]" % t)
    return "\n".join(x for x in parts if x).strip()


def web_session_history(session_id, max_msgs=10):
    """读会话历史，文本化为 user/assistant 消息列表。"""
    h = dsh_api("session.history", {"sessionId": session_id, "maxMessages": max(1, min(200, max_msgs))}, timeout=15)
    if "error" in h:
        return None, h["error"]
    value = h.get("result", {}).get("value", {})
    msgs = []
    for e in value.get("events", []):
        ev = e.get("event", {})
        et = ev.get("type", "")
        if et in ("user/message", "assistant/message"):
            data = ev.get("data") or {}
            if et == "assistant/message":
                role = "assistant"
                text = _content_to_text((data.get("message") or {}).get("content"))
            else:
                role = "user"
                text = _content_to_text(data.get("content"))
            if text:
                msgs.append({"role": role, "text": text[:2000]})
    return msgs, None


def web_session_list():
    r = dsh_api("session.list", {}, timeout=10)
    if "error" in r:
        return None, r["error"]
    value = r.get("result", {}).get("value", {})
    return value.get("items", []), None


# ---------------------------------------------------------------------------
# v1 headless 通道（保留为 fallback）
# ---------------------------------------------------------------------------
def resolve_dsh_cmd(name):
    """解析 dsh 可执行文件：优先 shutil.which（遵循 PATHEXT 可找到 .cmd/.bat），
    Windows 上 .cmd/.bat 需经 cmd /c 执行，普通 exe 直接执行。"""
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
    """调 dsh headless 跑一个任务，返回 (ok, output, exit_code, duration_ms)"""
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

    # ---------- 工具方法 ----------
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

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            return self._send_json(200, {"ok": True, "service": "dsh-bridge", "version": 2})
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "unauthorized"})
        if path == "/status":
            web = web_alive()
            headless_ok = None
            probe = None
            if not web:
                ok, out, code, dur = run_dsh("只回复两个字：OK", 30)
                headless_ok, probe = ok, out[:200]
            items, lerr = (web_session_list() if web else (None, None))
            return self._send_json(200, {
                "ok": True, "version": 2,
                "channels": {"session_web": web, "headless": headless_ok},
                "web_base": WEB_BASE,
                "sessions_count": (len(items) if items is not None else 0),
                "probe_output": probe,
                "active_jobs": len(RUNNING_JOBS),
            })
        return self._send_json(404, {"ok": False, "error": "not found"})

    # ---------- POST ----------
    def do_POST(self):
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "unauthorized"})
        path = self.path.split("?")[0]
        body = self._read_json()

        # ---- /run（headless fallback）----
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

        # ---- /session（官方会话通道）----
        if path == "/session":
            return self._handle_session(body)
        return self._send_json(404, {"ok": False, "error": "not found"})

    def _handle_session(self, body):
        action = str(body.get("action", "")).strip()
        t0 = time.time()

        if action == "start":
            sid, err = web_session_create(str(body.get("cwd") or "").strip() or None)
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
            reply, err, turn = web_session_prompt(sid, text, timeout=timeout)
            dur = int((time.time() - t0) * 1000)
            if err:
                log("会话失败: %s" % err)
                return self._send_json(200, {"ok": False, "action": "prompt", "session_id": sid,
                                             "error": err, "duration_ms": dur})
            log("会话完成: turn=%s dur=%dms reply=%s" % (turn, dur, (reply or "")[:80]))
            return self._send_json(200, {"ok": True, "action": "prompt", "session_id": sid,
                                         "reply": reply or "", "turn": turn, "duration_ms": dur})

        if action == "history":
            sid = str(body.get("session_id") or "").strip()
            try:
                mx = max(1, min(200, int(body.get("max", 10))))
            except Exception:
                mx = 10
            if not sid:
                return self._send_json(400, {"ok": False, "action": "history", "error": "session_id 不能为空"})
            msgs, err = web_session_history(sid, mx)
            if err:
                return self._send_json(200, {"ok": False, "action": "history", "error": err})
            # 文本化（App 端轻量 JSON 解析不支持嵌套数组）
            lines = []
            for i, m in enumerate(msgs, 1):
                role = "用户" if m["role"] == "user" else "AI"
                lines.append("%d. [%s] %s" % (i, role, m["text"]))
            return self._send_json(200, {"ok": True, "action": "history", "session_id": sid,
                                         "text": "\n".join(lines) or "(空)", "count": len(msgs)})

        if action == "list":
            items, err = web_session_list()
            if err:
                return self._send_json(200, {"ok": False, "action": "list", "error": err})
            return self._send_json(200, {"ok": True, "action": "list", "sessions": items})

        return self._send_json(400, {"ok": False, "error": "未知 action: %s" % action})


def main():
    global TOKEN, DSH_CMD, DSH_HOME, WEB_BASE, DEFAULT_CWD
    ap = argparse.ArgumentParser(description="dsh 桥接服务 v2（电脑端，官方会话通道）")
    ap.add_argument("--port", type=int, default=8218)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--token", default="")
    ap.add_argument("--dsh", default="dsh")
    ap.add_argument("--dsh-home", default=os.environ.get("DSH_HOME", ""))
    ap.add_argument("--web-port", type=int, default=3080)
    ap.add_argument("--cwd", default="", help="会话默认工作目录（dsh 里跑命令的目录）")
    args = ap.parse_args()

    TOKEN = args.token.strip() or secrets.token_urlsafe(24)
    DSH_CMD = args.dsh
    DSH_HOME = args.dsh_home
    WEB_BASE = "http://127.0.0.1:%d" % args.web_port
    DEFAULT_CWD = args.cwd.strip() or None

    log("dsh 桥接服务 v2 启动: http://%s:%d" % (args.host, args.port))
    log("访问令牌(请复制到 App 配置): %s" % TOKEN)
    log("dsh web 通道: %s | 默认工作目录: %s" % (WEB_BASE, DEFAULT_CWD or "(未指定)"))
    log("headless 命令: %s | DSH_HOME=%s" % (DSH_CMD, DSH_HOME or "(系统默认)"))
    log("警告: 服务未加密, 请仅在内网使用")

    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断, 退出")


if __name__ == "__main__":
    main()
