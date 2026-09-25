#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dsh 桥接服务（电脑端）
========================
让手机端"答题宝"App 通过 HTTP 远程调用本机的 DeepSeek dsh（DeepSeek Harness Shell）。

用法：
    python dsh_bridge_server.py [--port 8218] [--token xxx] [--dsh dsh] [--dsh-home C:\\...\\dsh-home]

    --token 必填（安全红线）：App 调用时带 Authorization: Bearer <token>。
            未提供时自动生成一个随机 token 并打印，复制到 App 配置即可。

端点：
    GET  /health           存活检查（返回 {"ok":true}，免鉴权，无敏感信息）
    GET  /status           桥接+dsh 可用性（需鉴权）
    POST /run              执行 dsh headless 任务（需鉴权）
                           body: {"task":"...", "timeout": 120}
                           resp: {"ok":bool,"output":"...","exit_code":0,"duration_ms":1234,
                                  "error":"..."}

安全说明：
    * 必须带 token 访问 /run 与 /status；启动日志会打印一次 token。
    * task 以参数列表形式传给 dsh（不经 shell），无命令注入面。
    * 仅监听本机/局域网：--host 默认 0.0.0.0（同一 Wi-Fi 手机可达）。
    * 建议仅在内网使用；如需公网，请用 Tailscale/FRP 等带鉴权的隧道。
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = None
DSH_CMD = "dsh"
DSH_HOME = None
RUNNING_JOBS = {}


def log(msg):
    print("[dsh-bridge %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def resolve_dsh_cmd(name):
    """解析 dsh 可执行文件：优先 shutil.which（遵循 PATHEXT 可找到 .cmd/.bat），
    Windows 上 .cmd/.bat 需经 cmd /c 执行，普通 exe 直接执行。"""
    if os.path.sep in name or (os.path.altsep and os.path.altsep in name):
        return name, name.lower().endswith((".cmd", ".bat"))
    found = shutil.which(name)
    if not found:
        # PATH 无结果时兜底常见安装位置
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

    # ---------- 端点 ----------
    def do_GET(self):
        if self.path.split("?")[0] == "/health":
            return self._send_json(200, {"ok": True, "service": "dsh-bridge"})
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "unauthorized"})
        if self.path.split("?")[0] == "/status":
            ok, out, code, dur = run_dsh("只回复两个字：OK", 30)
            return self._send_json(200, {
                "ok": True, "dsh_ok": ok, "probe_output": out[:200],
                "probe_duration_ms": dur, "active_jobs": len(RUNNING_JOBS),
            })
        return self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "unauthorized"})
        path = self.path.split("?")[0]
        if path != "/run":
            return self._send_json(404, {"ok": False, "error": "not found"})
        body = self._read_json()
        task = str(body.get("task", "")).strip()
        if not task:
            return self._send_json(400, {"ok": False, "error": "task 不能为空"})
        try:
            timeout = max(5, min(600, int(body.get("timeout", 120))))
        except Exception:
            timeout = 120
        log("收到任务(%ds 超时): %s" % (timeout, task[:120]))
        ok, out, code, dur = run_dsh(task, timeout)
        log("任务完成: ok=%s exit=%s dur=%dms" % (ok, code, dur))
        return self._send_json(200, {
            "ok": ok, "output": out, "exit_code": code, "duration_ms": dur,
            "task": task,
        })


def main():
    global TOKEN, DSH_CMD, DSH_HOME
    ap = argparse.ArgumentParser(description="dsh 桥接服务（电脑端）")
    ap.add_argument("--port", type=int, default=8218)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--token", default="")
    ap.add_argument("--dsh", default="dsh")
    ap.add_argument("--dsh-home", default=os.environ.get("DSH_HOME", ""))
    args = ap.parse_args()

    TOKEN = args.token.strip() or secrets.token_urlsafe(24)
    DSH_CMD = args.dsh
    DSH_HOME = args.dsh_home

    log("dsh 桥接服务启动: http://%s:%d" % (args.host, args.port))
    log("访问令牌(请复制到 App 配置): %s" % TOKEN)
    log("dsh 命令: %s | DSH_HOME=%s" % (DSH_CMD, DSH_HOME or "(系统默认)"))
    log("警告: 服务未加密, 请仅在内网使用")

    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断, 退出")


if __name__ == "__main__":
    main()
