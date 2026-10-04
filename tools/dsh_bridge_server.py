#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dsh 桥接服务 v5（电脑端）— ACP stdio 通道版
============================================
让手机端"答题宝"App 通过 HTTP 远程调用本机的 DeepSeek dsh（DeepSeek Harness Shell）。

v5 核心变化：ACP 传输层从 **HTTP+7800（第三方插件）** 换成 **dsh 原生 stdio 子进程**：
    * 起因：客户端升级到 0.2.0-rc.2 后，`dsh --profile acp serve --host … --port … --token …`
      直接报 `error: unknown option '--host'` —— 新版 acp profile 只提供 stdio，
      7800 那条 HTTP 通道来自第三方插件 dsh-acp-server（依赖 @deepseek-ai/dsh-* ^0.1.5-rc.2），
      新的 acp profile 不再装它。
    * 现在：桥接自己 spawn `dsh --profile acp`，用换行分隔 JSON-RPC 讲 ACP v1；
      无插件、不占端口、不需要 ACP token，手机端协议与接口完全不变。
    * session/new 创建会话（mcpServers:{}）-> session/prompt ->
      session/update / agent_message_chunk 流式聚合 + result.stopReason 判定回合结束
    * 写文件/跑命令类工具会发 session/request_permission（服务端→客户端请求），
      桥接按 --permission 策略自动应答（不应答会永久挂起）
    * 同一 sessionId 连续 prompt = 多轮会话续接（dsh 侧记忆连续）
    * 本地内存记录会话历史（ACP 无 history RPC）
    * 原有 headless /run 保留为 fallback（ACP 不可用时降级）

用法：
    python dsh_bridge_server.py [--port 8218] [--token xxx] [--dsh dsh]
                                [--dsh-home C:\\...\\dsh-home] [--dsh-profile acp]
                                [--cwd 默认工作目录]

    --token 必填（安全红线）：App 调用时带 Authorization: Bearer <token>。
    --dsh 可指定 dsh 可执行文件/脚本的绝对路径（PATH 里那份不合用时）；默认用 PATH 里的 dsh。
    --acp-base / --acp-token 是 0.1.5 时代 HTTP 通道的遗留参数，现在解析但不再使用（老脚本不会报错）。

端点（除 /health、/pair 外均需 Bearer token）：
    GET  /health            存活检查（免鉴权）
    GET  /pair              扫码配对页（仅 127.0.0.1 可访问）
    GET  /pair.json         配对信息（qr_text/base_url/token，仅本机）
    GET  /status            桥接+ACP 通道可用性
    POST /run               执行 dsh headless 任务（v1 保留，fallback）
    POST /exec              直接执行一条本机命令（不经 LLM，快且输出原样；body: cmd/cwd/shell/timeout）
    POST /session           会话通道（ACP stdio 后端）
                             body:
                               {"action":"start"}                                   -> 创建会话，返回 session_id
                               {"action":"prompt","session_id":"...","text":"..."}  -> 同会话续接，返回 reply
                               {"action":"history","session_id":"...","max":10}     -> 读会话历史（本桥接内存记录）
                               {"action":"get_status"}                              -> ACP 状态
                               {"action":"set_config","key":"cwd","value":"..."}    -> 设置默认工作目录

安全说明：
    * ACP 子进程只在本机跑（stdio，不监听任何端口）；手机只访问本桥接层（token 鉴权）。
    * 必须带 token 访问 /run、/status、/session；启动日志会打印一次 token。
    * 本服务默认监听 0.0.0.0（同一 Wi-Fi 手机可达）；建议仅内网使用，公网请走 Tailscale/FRP 等隧道。
"""
import argparse
import atexit
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
# ACP 通道：0.2.0 的 acp profile 只讲 stdio（HTTP serve 是 0.1.5 时代第三方插件的玩法，升级后已移除）
ACP_PROFILE = "acp"
# 公网/隧道地址（花生壳 http://xxx.vicp.net:12345、Cloudflare https://xxx.trycloudflare.com、frp 等）：
# 设置后会作为额外配对候选出现在配对页，手机在任何网络都能扫它配对
PUBLIC_URL = ""
# ACP 权限请求策略（2026-09-27 实测新增；2026-10-03 换 stdio 后逻辑不变）：
#   allow（默认）= 自动回"允许"类选项；deny = 自动回"拒绝"类选项（只读任务可跑，写/执行类被拒）
# 背景：写文件/跑命令这类工具调用，dsh ACP 会先发 session/request_permission 并**等客户端回答**；
# 客户端不答，session/prompt 就永不返回（手机端只看到"等待回复超时"）。应答记录见 /status 的 permissions。
PERMISSION_POLICY = "allow"
# 嵌套 dsh agent 的沙箱/审批模式（dsh 读 DSH_PERMISSION_MODE）：
#   danger-full-access（默认）= 不受沙箱限制、审批 never（"远程控制电脑"本来就该能读写/跑命令；
#                               手机侧由桥接 token 把关）。read-only / workspace-write 可显式指定，
#   但 note：Windows 上 workspace-write 需要 materialize ACL 临时授权，实测会失败。
PERMISSION_MODE = "danger-full-access"
# 同时在跑的任务上限（headless /run 与直连 /exec 共用），防止手机端猛点把电脑压满
MAX_CONCURRENT_JOBS = 4


def log(msg):
    print("[dsh-bridge %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ---------------------------------------------------------------------------
# ACP 客户端（dsh 原生 stdio；ACP v1 JSON-RPC，换行分隔 JSON）
# ---------------------------------------------------------------------------
class AcpClient:
    """直接 spawn `dsh --profile acp` 子进程，用 stdio 讲 ACP v1。

    为什么从 HTTP+7800 改回 stdio（2026-10-03 实测，客户端升级到 0.2.0-rc.2 后）：
      * `dsh --profile acp serve --host … --port … --token …` 在新版直接报
        `error: unknown option '--host'`：0.2.0 的 acp profile 只提供 stdio
        （`dsh --profile acp --help` → "Serve automation clients over ACP stdio"）。
      * 7800 那条 HTTP 通道来自第三方插件 dsh-acp-server（声明依赖 @deepseek-ai/dsh-* ^0.1.5-rc.2）；
        新 acp profile 的 bundle 只有 dsh-base + dsh-acp-app，不装它。
      * stdio 是官方原生通道：无插件、不占端口、不需要 ACP token，子进程生命周期由本桥接管。
        实测 0.2.0-rc.2：initialize → agentInfo，session/new → sessionId 均正常。

    协议要点：
      initialize {protocolVersion:1, clientCapabilities:{}} -> agentInfo
      session/new {cwd, mcpServers:{}}                      -> sessionId
      session/prompt {sessionId, prompt:[{type:text,text}]} -> result.stopReason
      流式文本走 session/update 通知（update.sessionUpdate == "agent_message_chunk"）
      写文件/跑命令类工具会发 session/request_permission（服务端→客户端请求，必须应答，否则挂死）
    """

    def __init__(self):
        self.proc = None
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self._start_lock = threading.Lock()   # 串行化"拉起子进程"，且**不能**在等 initialize 时持锁
        self.pending = {}          # rid -> {"ev","result","error","chunks","method","sid"}
        self.by_session = {}       # sid -> 当前在跑的 prompt rid
        self.perm_events = []      # 最近的权限应答记录（供 /status 审计）
        self.server_requests = []  # 最近收到的服务端→客户端请求（排错用）
        self.agent_info = None     # initialize 自报的 agentInfo（name/version），供 /status 如实展示
        self.stderr_tail = []      # dsh 子进程 stderr 末尾若干行（排错用）
        self.last_probe = None     # 最近一次探测/启动失败原因，供 /status 的 probe_output 展示
        self._seq = 0

    # ---------- 子进程 ----------
    def _argv(self):
        dsh_path, is_cmd = resolve_dsh_cmd(DSH_CMD)
        if not dsh_path:
            return None
        base = ["cmd", "/c", dsh_path] if is_cmd else [dsh_path]
        return base + ["--profile", ACP_PROFILE]

    def _env(self):
        env = dict(os.environ)
        # 这些是"当前 DSH 会话"的身份/策略变量：桥接若从某个 DSH shell（开发时）里启动，
        # 子 agent 会误以为自己是同一次会话。清掉，只保留显式配置的 DSH_HOME。
        for k in ("DSH_SESSION_ID", "DSH_WEB_URL", "DSH_SHELL", "DSH_PROFILE", "DSH_PROFILE_DIR",
                  "DSH_APPROVAL_POLICY", "DSH_FILE_POLICY", "DSH_SANDBOX_MODE"):
            env.pop(k, None)
        if DSH_HOME:
            env["DSH_HOME"] = DSH_HOME
        # 关键：dsh 的沙箱/审批策略读这个环境变量（acp profile 默认 workspace-write）。
        # Windows 上 workspace-write 会去给工作区materialize ACL 临时授权，实测失败：
        # "sandbox-local windows-acl temp grant materialization failed" → 跑命令/写文件全废。
        # 手机已用 token 通过桥接鉴权，这里按 --permission-mode 显式设置（默认 danger-full-access）。
        if PERMISSION_MODE:
            env["DSH_PERMISSION_MODE"] = PERMISSION_MODE
        return env

    def _teardown(self, why=""):
        """停掉子进程并唤醒所有等待者（子进程死了不能让请求干等）。"""
        with self.lock:
            proc, self.proc = self.proc, None
            self.agent_info = None
            pend, self.pending = self.pending, {}
            self.by_session = {}
        suffix = ("：" + why) if why else ""
        for rec in pend.values():
            if not rec.get("error"):
                rec["error"] = {"message": "dsh 子进程已退出%s" % suffix}
            rec["ev"].set()
        if proc and proc.poll() is None:
            try:
                proc.terminate()
            except Exception:
                pass

    def _note(self, text):
        self.server_requests.append({"time": time.strftime("%H:%M:%S"), "what": text})
        del self.server_requests[:-20]

    def _start(self):
        """拉起 dsh 子进程并完成 ACP initialize。

        注意（2026-10-03 踩到的真 bug）：**不能在持有 self.lock 时等 initialize 响应**——
        读线程收到响应后要拿 self.lock 才能把事件交给等待者，持锁等待会自我死锁，
        现象是"initialize 等待超时(60s)"且 stderr 为空。所以只有 拉起/清理 这两小段持锁。
        """
        with self.lock:
            self._teardown("重启 ACP 通道")
            argv = self._argv()
            if not argv:
                self.last_probe = ("找不到 dsh 命令: %s（装 Node.js 后 npm i -g @deepseek-ai/dsh，"
                                   "或用 --dsh 指定路径）" % DSH_CMD)
                log("ACP(stdio): %s" % self.last_probe)
                return False
            try:
                self.proc = subprocess.Popen(
                    argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    text=True, encoding="utf-8", errors="replace", bufsize=1,
                    env=self._env(), cwd=(DEFAULT_CWD or None),
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
            except Exception as e:
                self.proc = None
                self.last_probe = "启动 dsh 失败: %s" % e
                log("ACP(stdio): %s" % self.last_probe)
                return False
            proc = self.proc
        threading.Thread(target=self._stdout_loop, args=(proc,), daemon=True).start()
        threading.Thread(target=self._stderr_loop, args=(proc,), daemon=True).start()
        # 这段必须在锁外等（读线程要靠 self.lock 投递响应）
        ok, rec, err = self._request("initialize",
                                     {"protocolVersion": 1, "clientCapabilities": {}},
                                     timeout=60)
        if not ok:
            self.last_probe = "initialize 失败: %s" % err
            log("ACP(stdio): %s | dsh stderr 末尾: %s" % (self.last_probe, " | ".join(self.stderr_tail[-3:])))
            self._teardown("initialize 失败")
            return False
        self.agent_info = (rec.get("result") or {}).get("agentInfo") or {}
        self.last_probe = "ok (stdio)"
        log("ACP(stdio) 已连接: %s" % json.dumps(self.agent_info, ensure_ascii=False))
        return True

    def _write(self, obj):
        proc = self.proc
        if not proc or proc.poll() is not None or not proc.stdin:
            return False
        try:
            with self.write_lock:
                proc.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
                proc.stdin.flush()
            return True
        except Exception as e:
            log("ACP 写入失败: %s" % e)
            return False

    def _stdout_loop(self, proc):
        try:
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except Exception:
                    log("ACP 非 JSON 输出（忽略）: %s" % line[:200])
                    continue
                try:
                    self._dispatch(msg)
                except Exception as e:
                    log("ACP 消息处理异常: %s" % e)
        except Exception as e:
            log("ACP stdout 读取结束: %s" % e)
        finally:
            if self.proc is proc:
                code = proc.poll()
                log("ACP dsh 子进程已退出（exit=%s）| stderr 末尾: %s"
                    % (code, " | ".join(self.stderr_tail[-3:])))
                self._teardown("exit=%s" % code)

    def _stderr_loop(self, proc):
        try:
            for line in proc.stderr:
                line = line.rstrip()
                if line:
                    self.stderr_tail.append(line[:300])
                    del self.stderr_tail[:-20]
        except Exception:
            pass

    def _dispatch(self, msg):
        if not isinstance(msg, dict):
            return
        rid = msg.get("id")
        method = msg.get("method")
        if method and rid is not None:
            self._handle_server_request(msg)          # 服务端 → 客户端请求（权限等）
            return
        if method:
            self._handle_notification(msg)            # 通知（流式文本等）
            return
        if rid is None:
            return
        with self.lock:
            rec = self.pending.get(rid)
        if rec is not None:
            rec["result"] = msg.get("result")
            rec["error"] = msg.get("error")
            rec["ev"].set()
        else:
            self._note("stray response id=%s" % rid)

    def _handle_notification(self, msg):
        method = msg.get("method")
        params = msg.get("params") or {}
        if method == "session/update":
            u = params.get("update") or {}
            kind = u.get("sessionUpdate")
            if kind == "agent_message_chunk":
                ct = u.get("content") or {}
                if ct.get("type") == "text" and ct.get("text"):
                    sid = params.get("sessionId")
                    with self.lock:
                        pri = self.by_session.get(sid)
                        rec = self.pending.get(pri) if pri is not None else None
                    if rec is not None:
                        rec["chunks"].append(ct["text"])
            else:
                self._note("update:%s" % kind)
            return
        self._note("notify:%s" % method)

    def _handle_server_request(self, msg):
        """应答 dsh 发来的请求：权限请求必须回答，否则 session/prompt 永不返回。"""
        rid, method, params = msg.get("id"), msg.get("method"), (msg.get("params") or {})
        self._note("request:%s" % method)
        if method == "session/request_permission":
            options = [o for o in (params.get("options") or []) if isinstance(o, dict)]
            want_allow = str(PERMISSION_POLICY).lower() != "deny"
            prefer = ("allow_once", "allow_always") if want_allow else ("reject_once", "reject_always")
            chosen = None
            for want in prefer:                       # 先按策略挑，挑不到再退第一个可用项
                for o in options:
                    oid = o.get("optionId") or o.get("id")
                    if str(o.get("kind") or "") == want and oid:
                        chosen = oid
                        break
                if chosen:
                    break
            if not chosen:
                for o in options:
                    if o.get("optionId") or o.get("id"):
                        chosen = o.get("optionId") or o.get("id")
                        break
            if chosen:
                self._write({"jsonrpc": "2.0", "id": rid,
                             "result": {"outcome": {"outcome": "selected", "optionId": chosen}}})
            else:
                self._write({"jsonrpc": "2.0", "id": rid,
                             "result": {"outcome": {"outcome": "cancelled"}}})
            tc = params.get("toolCall") or {}
            rec = {"time": time.strftime("%H:%M:%S"), "session": params.get("sessionId"),
                   "tool": tc.get("toolCallId") or tc.get("title"),
                   "kind": ((params.get("toolCall") or {}).get("kind")),
                   "option": chosen, "options": [str(o.get("kind")) for o in options]}
            self.perm_events.append(rec)
            del self.perm_events[:-20]
            log("ACP 权限请求 → %s（session=%s, tool=%s, 可选项=%s）"
                % (chosen, rec["session"], rec["tool"], rec["options"]))
            return
        # 未知/不支持的客户端能力请求：明确回错误，别让 dsh 侧干等
        self._write({"jsonrpc": "2.0", "id": rid,
                     "error": {"code": -32601, "message": "bridge 不支持 %s" % method}})

    # ---------- 请求/应答 ----------
    def _next_id(self):
        with self.lock:
            self._seq += 1
            return self._seq

    def _request(self, method, params, timeout=60, sid=None):
        """发一条 JSON-RPC 请求并等响应，返回 (ok, rec, err)；rec 内含 result / chunks。"""
        if not self.proc or self.proc.poll() is not None:
            return False, None, "dsh 子进程未运行"
        rid = self._next_id()
        rec = {"ev": threading.Event(), "result": None, "error": None,
               "chunks": [], "method": method, "sid": sid}
        with self.lock:
            self.pending[rid] = rec
            if sid:
                self.by_session[sid] = rid
        if not self._write({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}):
            with self.lock:
                self.pending.pop(rid, None)
                if sid and self.by_session.get(sid) == rid:
                    self.by_session.pop(sid, None)
            return False, None, "ACP 写入失败（dsh 子进程可能已退出）"
        # 分片等待：子进程一死就立刻报错，别让调用方干等满超时（实测踩到过"等 60s 才说子进程没了"）
        deadline = time.time() + max(1, timeout)
        while not rec["ev"].wait(0.25) and time.time() < deadline:
            if self.proc is None or self.proc.poll() is not None:
                break
        if not rec["ev"].is_set():
            with self.lock:
                self.pending.pop(rid, None)
                if sid and self.by_session.get(sid) == rid:
                    self.by_session.pop(sid, None)
            if self.proc is None or self.proc.poll() is not None:
                tail = " | ".join(self.stderr_tail[-3:])
                return False, None, "%s 未完成：dsh 子进程已退出%s" % (method, ("（" + tail + "）") if tail else "")
            return False, None, "%s 等待超时(%ds)" % (method, timeout)
        with self.lock:
            self.pending.pop(rid, None)
            if sid and self.by_session.get(sid) == rid:
                self.by_session.pop(sid, None)
        if rec["error"]:
            err = rec["error"]
            if isinstance(err, dict):
                return False, rec, err.get("message") or json.dumps(err, ensure_ascii=False)[:300]
            return False, rec, str(err)[:300]
        return True, rec, None

    # ---------- 对外能力 ----------
    def ensure(self):
        """保证有一个活的 ACP 子进程（挂了就重拉一个）；并发调用只拉一次。"""
        with self.lock:
            if self.proc and self.proc.poll() is None and self.agent_info:
                return True
        with self._start_lock:
            with self.lock:
                if self.proc and self.proc.poll() is None and self.agent_info:
                    return True
            return self._start()

    def alive(self):
        """ACP 是否可用（供 /status）。首次查询会顺带把连接建起来（后续会话复用）。"""
        if self.ensure():
            info = self.agent_info or {}
            self.last_probe = "ok (stdio acp, agent=%s %s)" % (info.get("name") or "?", info.get("version") or "?")
            return True
        return False

    def session_new(self, cwd=None):
        if not self.ensure():
            return None, "ACP(stdio) 未连接：%s" % (self.last_probe or "dsh 子进程未启动")
        ok, rec, err = self._request(
            "session/new",
            {"cwd": cwd or DEFAULT_CWD or os.getcwd(), "mcpServers": {}},
            timeout=90)
        if not ok:
            return None, "session/new 失败: %s" % err
        res = rec.get("result") or {}
        sid = res.get("sessionId")
        if not sid:
            return None, "session/new 未返回 sessionId: %s" % json.dumps(res, ensure_ascii=False)[:200]
        return sid, None

    def prompt(self, sid, text, timeout=240):
        if not self.ensure():
            return None, "ACP(stdio) 未连接：%s" % (self.last_probe or "dsh 子进程未启动")
        ok, rec, err = self._request(
            "session/prompt",
            {"sessionId": sid, "prompt": [{"type": "text", "text": text}]},
            timeout=timeout, sid=sid)
        if not ok:
            low = (err or "").lower()
            if "not found" in low or "unknown" in low or "invalid" in low:
                return None, "会话已失效（电脑端 ACP 重启过），可重开会话: %s" % err
            return None, err
        reply = "".join(rec.get("chunks") or [])
        if not reply.strip():
            # 兜底：万一 dsh 只把文本放在 result 里（不同版本形态），也别回空
            res = rec.get("result") or {}
            for key in ("text", "content", "message"):
                v = res.get(key)
                if isinstance(v, str) and v.strip():
                    reply = v
                    break
        return reply, None

    def shutdown(self):
        self._teardown("桥接退出")


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
    """渲染配对页。

    页面本体在 **项目文件 pair_page.html**（2026-09-27 从 Python 内联字符串里抽出来）：
    内联写法让 Python 的转义规则悄悄改页面内容，实测导致 JS 里出现裸换行 → 整段脚本报错 →
    一个二维码都不显示。现在这里只做占位符替换，页面用编辑器改、用 node 测试验。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    qr_js = ""
    qjs = os.path.join(here, "qrcodegen.js")
    try:
        with io.open(qjs, encoding="utf-8") as f:
            qr_js = f.read()
    except Exception as e:
        log("pair_html qrcodegen.js 读取失败: %s (%s)" % (repr(e), qjs))
        qr_js = "// qrcodegen.js 缺失，无法渲染二维码"
    items_json = json.dumps(pair_candidates(port, token), ensure_ascii=False)
    page_path = os.path.join(here, "pair_page.html")
    try:
        with io.open(page_path, encoding="utf-8") as f:
            html = f.read()
    except Exception as e:
        # 页面文件缺失时不 500，直接给"手动配对"信息（token + 候选地址），用户仍能配上
        log("pair_html pair_page.html 读取失败: %s (%s)" % (repr(e), page_path))
        rows = "".join("<li>%s → <code>%s</code></li>" % (c.get("label", ""), c.get("base_url", ""))
                       for c in pair_candidates(port, token))
        return (u"""<!doctype html><html><head><meta charset="utf-8">
<title>答题宝 · 手动配对</title></head><body style="font-family:system-ui;padding:24px">
<h2>配对页文件缺失，请手动配对</h2>
<p>原因：%s</p>
<p>电脑端目录里缺少 <code>pair_page.html</code>（重新从手机 App「远程连接（电脑）→ 怎么用」导出电脑端程序即可恢复）。</p>
<p>手动配对：手机「远程连接（电脑）」→ 手动配置，填下面的地址与令牌：</p>
<ul>%s</ul>
<p>访问令牌：<code>%s</code></p>
</body></html>""" % (e, rows, token)).encode("utf-8")
    return (html.replace("/*__QRCODE_JS__*/", qr_js)
                .replace("__ITEMS__", items_json)).encode("utf-8")

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
            return self._send_json(200, {"ok": True, "service": "dsh-bridge", "version": 5,
                                         "backend": "acp-stdio"})
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
            # ACP 现在是 stdio 子进程：alive() 顺带把连接建起来（第 7800 端口/ACP token 已不需要），
            # 之后的会话直接复用这条连接。
            items, lerr = (acp_session_list() if acp_ok else (None, None))
            with ACP.lock:
                running = [r.get("sid") for r in ACP.pending.values() if r.get("sid")]
            return self._send_json(200, {
                "ok": True, "version": 5, "backend": "acp",
                "channels": {"session_acp": acp_ok, "headless": headless_ok},
                "acp_transport": "stdio",
                "acp_profile": ACP_PROFILE,
                "acp_agent": ACP.agent_info,
                "sessions_count": (len(items) if items is not None else 0),
                "probe_output": probe,
                "active_jobs": len(RUNNING_JOBS),
                "session_streams": sorted([s for s in running if s]),
                "permission_policy": PERMISSION_POLICY,
                "permission_mode": PERMISSION_MODE,
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
    global BRIDGE_PORT, TOKEN, DSH_CMD, DSH_HOME, ACP_PROFILE, DEFAULT_CWD, PUBLIC_URL
    global PERMISSION_POLICY, PERMISSION_MODE
    ap = argparse.ArgumentParser(description="答题宝 · dsh 远程桥接 v5 (ACP stdio 后端)")
    ap.add_argument("--port", type=int, default=8218)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--token", default="")
    ap.add_argument("--dsh", default="dsh", help="dsh 可执行文件（默认用 PATH 里的 dsh；也可给绝对路径）")
    ap.add_argument("--dsh-home", default=os.environ.get("DSH_HOME", ""))
    ap.add_argument("--dsh-profile", default=ACP_PROFILE,
                    help="dsh profile（默认 acp = ACP over stdio；一般不用改）")
    ap.add_argument("--cwd", default="", help="会话默认工作目录（dsh 里跑命令的目录）")
    ap.add_argument("--acp-base", default="",
                    help="[已废弃] 0.1.5 时代 HTTP ACP serve 地址，现在解析后忽略（ACP 走 stdio 子进程）")
    ap.add_argument("--acp-token", default="",
                    help="[已废弃] 0.1.5 时代 ACP bearer token，现在解析后忽略")
    ap.add_argument("--permission", default="allow", choices=["allow", "deny"],
                    help="ACP 权限请求自动应答策略：allow=自动允许（默认，写文件/跑命令可用）；deny=自动拒绝")
    ap.add_argument("--permission-mode", default=PERMISSION_MODE,
                    choices=["read-only", "workspace-write", "danger-full-access"],
                    help="嵌套 dsh agent 的沙箱模式（环境变量 DSH_PERMISSION_MODE）："
                         "danger-full-access=默认，不受沙箱限制（远程控制电脑需要）；"
                         "workspace-write 在 Windows 上实测因 ACL 授权失败会跑不了命令")
    ap.add_argument("--public-url", default="",
                    help="公网/隧道地址（花生壳 http://xxx.vicp.net:12345 / Cloudflare https://xxx.trycloudflare.com 等），"
                         "会作为额外配对候选出现在配对页")
    ap.add_argument("--no-open", action="store_true",
                    help="不要自动打开配对页浏览器（脚本/CI 里跑时用）")
    args = ap.parse_args()
    BRIDGE_PORT, TOKEN = args.port, args.token
    PERMISSION_POLICY = args.permission
    PERMISSION_MODE = args.permission_mode
    DSH_CMD, DSH_HOME = args.dsh, args.dsh_home
    ACP_PROFILE = (args.dsh_profile or "acp").strip()
    PUBLIC_URL = args.public_url.strip()
    DEFAULT_CWD = args.cwd.strip() or None
    if not TOKEN:
        log("未配置 --token：App 将无法鉴权（拒绝所有请求）。请传 --token xxx")
    if args.acp_base or args.acp_token:
        log("提示：--acp-base/--acp-token 已废弃（ACP 现在走 dsh 子进程 stdio），已忽略")
    atexit.register(ACP.shutdown)
    log("dsh 桥接服务 v5 (ACP stdio) 启动: http://%s:%d" % (args.host, args.port))
    log("访问令牌(请复制到 App 配置): %s" % TOKEN)
    log("ACP 通道: `%s --profile %s`（stdio 子进程，无需端口/ACP token）| 默认工作目录: %s"
        % (DSH_CMD, ACP_PROFILE, DEFAULT_CWD or "(未指定)"))
    log("ACP 权限策略: %s（写文件/跑命令类工具需应答，deny 时这类任务会被拒）" % PERMISSION_POLICY)
    log("嵌套 agent 沙箱模式: %s（环境变量 DSH_PERMISSION_MODE；改小可用 --permission-mode）" % PERMISSION_MODE)
    log("配对页(本机浏览器): http://127.0.0.1:%d/pair  (手机扫码=一键配对)" % args.port)
    for c in pair_candidates(args.port, TOKEN):
        log("配对候选: %s   (手机手动输入: %s  token=%s)" % (c["ip"], c["base_url"], TOKEN))
    try:
        if not args.no_open:
            webbrowser.open("http://127.0.0.1:%d/pair" % args.port)
    except Exception:
        pass
    srv = BridgeHTTPServer((args.host, BRIDGE_PORT), Handler)
    log("桥接 v5 (ACP stdio) 监听 http://%s:%d  token=%s" % (args.host, BRIDGE_PORT, TOKEN))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断, 退出")
    finally:
        ACP.shutdown()


if __name__ == "__main__":
    main()
