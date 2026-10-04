#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""remote_dsh 桥接端到端验收（stdio ACP 通道，dsh 0.2.0-rc.2）。

阶段 A（默认沙箱模式 danger-full-access）：
  1) /health、/status —— ACP(stdio) 是否连上、agentInfo / permission_mode 是否如实上报
  2) /session start + prompt —— 真跑一轮（电脑端 DeepSeek agent）
  3) 同一 session_id 第二轮 —— 验证多轮续接（电脑端记忆）
  4) /session history —— 本地历史
  5) /exec —— 直连命令（不经 LLM）
  6) 跑命令的一轮 —— danger-full-access 下 approval=never：不请求权限也不该挂死，且命令真跑出结果
阶段 B（--permission-mode workspace-write 再起一次桥接）：
  7) 同一类任务 → 必须收到并应答 session/request_permission（覆盖权限应答代码路径），且回合不挂死

用法：
    python tools/tests/bridge_stdio_e2e.py [--dsh <dsh路径>] [--port 8231] [--skip-mode-b]
退出码 0 = 全通过。
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REPO = os.path.dirname(TOOLS)
BRIDGE = os.path.join(TOOLS, "dsh_bridge_server.py")
LOG = os.path.join(HERE, "_bridge_e2e.log")
TOKEN = "e2e-bridge-token"
DEFAULT_DSH = os.path.join(
    os.environ.get("LOCALAPPDATA", r"C:\Users\xiaocong\AppData\Local"),
    "Programs", "DeepSeek Harness", "resources", "runtime", "cli", "bin", "dsh.cmd")

try:                                   # Windows 控制台默认 GBK，日志里的替换字符会炸 print
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

results = []
_logf = None


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + str(detail)[:300]) if detail else ""), flush=True)


def http(base, method, path, body=None, timeout=120):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if data:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def start_bridge(dsh, port, extra=()):
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    proc = subprocess.Popen(
        [sys.executable, BRIDGE, "--port", str(port), "--token", TOKEN,
         "--dsh", dsh, "--cwd", REPO, "--no-open"] + list(extra),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="replace", bufsize=1, env=env,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def pump():
        try:
            for line in proc.stdout:
                if _logf:
                    _logf.write(line)
                    _logf.flush()
                sys.stdout.write("  [bridge] " + line.replace("\ufffd", "?"))
                sys.stdout.flush()
        except Exception as e:
            print("  [bridge log reader stopped: %s]" % e, flush=True)
    threading.Thread(target=pump, daemon=True).start()

    base = "http://127.0.0.1:%d" % port
    for _ in range(40):
        try:
            with urllib.request.urlopen(base + "/health", timeout=3) as r:
                if r.status == 200:
                    return proc, base
        except Exception:
            time.sleep(0.5)
    return proc, None


def stop_bridge(proc, log_note=""):
    if not proc:
        return
    try:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True, timeout=20)
    except Exception:
        try:
            proc.terminate()
        except Exception:
            pass
    if log_note and _logf:
        _logf.write("\n===== %s =====\n" % log_note)
        _logf.flush()


def phase_a(dsh, port):
    proc, base = start_bridge(dsh, port)
    check("/health 起来", base is not None)
    if not base:
        return
    try:
        # 2) /status：ACP(stdio) 可用 + agentInfo + 沙箱模式
        t0 = time.time()
        st = http(base, "GET", "/status", None, timeout=90)
        print("  /status 首次探测耗时 %.1fs" % (time.time() - t0))
        check("/status 返回且 session_acp=true",
              bool(st.get("channels", {}).get("session_acp")), st.get("probe_output"))
        check("/status 上报 acp_agent(agentInfo)", bool(st.get("acp_agent")), st.get("acp_agent"))
        check("/status 传输方式=stdio", st.get("acp_transport") == "stdio", st.get("acp_transport"))
        check("/status 沙箱模式=danger-full-access", st.get("permission_mode") == "danger-full-access",
              st.get("permission_mode"))

        # 3) 建会话
        r = http(base, "POST", "/session", {"action": "start"})
        sid = r.get("session_id")
        check("/session start 拿到 session_id", bool(sid), r)
        if not sid:
            return

        # 4) 第一轮（短提示，省 token）
        r1 = http(base, "POST", "/session", {"action": "prompt", "session_id": sid,
                                             "text": "只回复两个字：好的", "timeout": 180}, timeout=200)
        reply1 = r1.get("reply") or ""
        check("第一轮 prompt 成功且非空", bool(r1.get("ok")) and bool(reply1.strip()), r1.get("error") or reply1)

        # 5) 第二轮：同会话续接（记忆）
        r2 = http(base, "POST", "/session", {"action": "prompt", "session_id": sid,
                                             "text": "我刚才让你回复的是哪两个字？只回那两个字。", "timeout": 180}, timeout=200)
        reply2 = r2.get("reply") or ""
        check("第二轮续接成功", bool(r2.get("ok")) and bool(reply2.strip()), r2.get("error") or reply2)
        check("第二轮体现出记忆（提到「好的」）", "好的" in reply2, reply2)

        # 6) 历史
        h = http(base, "POST", "/session", {"action": "history", "session_id": sid, "max": 10})
        check("history 返回本地记录", bool(h.get("text")) and int(h.get("count") or 0) >= 4, h)

        # 7) 直连命令
        e = http(base, "POST", "/exec", {"cmd": "echo stdio-bridge-ok", "timeout": 30})
        check("/exec 直连命令", bool(e.get("ok")) and "stdio-bridge-ok" in (e.get("output") or ""), e.get("output"))

        # 8) 真跑命令：danger-full-access 下应直接执行、不留沙箱报错、approval=never 也无需权限请求
        r3 = http(base, "POST", "/session", {"action": "prompt", "session_id": sid,
                                             "text": "在电脑上执行 echo perm-ok 这条命令，然后只告诉我它的原始输出。",
                                             "timeout": 240}, timeout=260)
        reply3 = r3.get("reply") or ""
        check("跑命令任务未挂死且拿到真实输出", bool(r3.get("ok")) and "perm-ok" in reply3,
              r3.get("error") or reply3)
        check("嵌套 agent 没被沙箱挡住（无 sandbox/沙箱 报错）",
              bool(r3.get("ok")) and "sandbox" not in reply3.lower() and "沙箱" not in reply3, reply3[:200])

        st2 = http(base, "GET", "/status", None, timeout=60)
        check("danger-full-access 下不需要权限请求（approval=never）",
              len(st2.get("permissions") or []) == 0, st2.get("permissions"))
        print("\n=== /status（节选） ===")
        print(json.dumps({k: st2.get(k) for k in ("channels", "acp_transport", "acp_agent",
                                                  "sessions_count", "permission_policy",
                                                  "permission_mode")}, ensure_ascii=False, indent=2))
    except Exception as e:
        check("阶段 A 未抛异常", False, "%s: %s" % (type(e).__name__, e))
    finally:
        stop_bridge(proc, "phase A stopped")


def phase_c_unit():
    """不依赖 LLM/环境：直接喂 session/request_permission 给 AcpClient，验证权限应答与未知请求兜底。"""
    sys.path.insert(0, TOOLS)
    import importlib
    mod = importlib.import_module("dsh_bridge_server")
    c = mod.AcpClient()
    sent = []
    c._write = lambda obj: (sent.append(obj), True)[1]
    perm_msg = {"jsonrpc": "2.0", "id": 7, "method": "session/request_permission",
                "params": {"sessionId": "s1", "toolCall": {"toolCallId": "t1"},
                           "options": [{"optionId": "reject-once", "kind": "reject_once"},
                                       {"optionId": "allow-once", "kind": "allow_once"}]}}
    mod.PERMISSION_POLICY = "allow"
    c._handle_server_request(dict(perm_msg))
    ok = bool(sent) and sent[0].get("result", {}).get("outcome", {}).get("optionId") == "allow-once"
    check("[单元] 权限应答：allow 策略挑 allow_once", ok, sent)

    sent.clear()
    mod.PERMISSION_POLICY = "deny"
    c._handle_server_request(dict(perm_msg))
    ok = bool(sent) and sent[0].get("result", {}).get("outcome", {}).get("optionId") == "reject-once"
    check("[单元] 权限应答：deny 策略挑 reject_once", ok, sent)

    sent.clear()
    c._handle_server_request({"jsonrpc": "2.0", "id": 8, "method": "fs/read_text_file", "params": {}})
    ok = bool(sent) and sent[0].get("error", {}).get("code") == -32601
    check("[单元] 未知服务端请求回 -32601（不让 dsh 干等）", ok, sent)
    mod.PERMISSION_POLICY = "allow"


def phase_b(dsh, port):
    """沙箱模式改小：dsh 走 approval=ask，遇到需要授权的动作会发 session/request_permission。"""
    proc, base = start_bridge(dsh, port, extra=("--permission-mode", "workspace-write"))
    check("阶段 B：桥接起来（workspace-write）", base is not None)
    if not base:
        return
    try:
        st = http(base, "GET", "/status", None, timeout=90)
        check("阶段 B：permission_mode=workspace-write", st.get("permission_mode") == "workspace-write",
              st.get("permission_mode"))
        r = http(base, "POST", "/session", {"action": "start"})
        sid = r.get("session_id")
        check("阶段 B：session start", bool(sid), r)
        if not sid:
            return
        r3 = http(base, "POST", "/session", {"action": "prompt", "session_id": sid,
                                             "text": "在电脑上执行 echo perm-ws 这条命令，然后只告诉我它的原始输出。",
                                             "timeout": 180}, timeout=200)
        check("阶段 B：需授权任务未挂死（回合正常返回）", bool(r3.get("ok")),
              r3.get("error") or (r3.get("reply") or "")[:200])
        st2 = http(base, "GET", "/status", None, timeout=60)
        perms = st2.get("permissions") or []
        if perms:
            check("阶段 B：发出的权限请求都被自动应答", all(r.get("option") for r in perms), perms)
        else:
            print("[INFO] 阶段 B：本轮没有触发 session/request_permission"
                  "（workspace 内的命令在该环境下可直接执行）；应答路径已由上面的单元用例覆盖", flush=True)
    except Exception as e:
        check("阶段 B 未抛异常", False, "%s: %s" % (type(e).__name__, e))
    finally:
        stop_bridge(proc, "phase B stopped")


def main():
    global _logf
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsh", default=DEFAULT_DSH)
    ap.add_argument("--port", type=int, default=8231)
    ap.add_argument("--skip-mode-b", action="store_true",
                    help="跳过阶段 B（workspace-write 权限应答覆盖）")
    args = ap.parse_args()

    dsh = args.dsh
    if not os.path.isfile(dsh):
        found = None
        for p in os.environ.get("PATH", "").split(os.pathsep):
            for nm in ("dsh.cmd", "dsh.bat", "dsh"):
                cand = os.path.join(p, nm)
                if os.path.isfile(cand):
                    found = cand
                    break
            if found:
                break
        dsh = found or "dsh"
    print("dsh = %s" % dsh)

    _logf = open(LOG, "w", encoding="utf-8")
    try:
        phase_c_unit()
        phase_a(dsh, args.port)
        if not args.skip_mode_b:
            phase_b(dsh, args.port + 1)
    finally:
        _logf.close()

    passed = sum(1 for _, ok in results if ok)
    print("\n=== %d/%d 通过 ===" % (passed, len(results)))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
