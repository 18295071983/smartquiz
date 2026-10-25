# -*- coding: utf-8 -*-
"""Termux 完整 Linux 环境的 Python 入口（显式引擎，与 linux_shell engine=termux 同一条 Java 实现）。

为什么需要它：答题宝的 Python 跑在 App 进程内（Chaquopy），subprocess 用的是 App 沙箱环境——
cwd 可能是只读 /、没有 apt/pip/git、拿不到 loadavg、无法 tmux 长跑。需要**真 Linux**（可写文件系统、
完整包管理、tmux 托管长任务、独立 Python）时，用本模块把命令交给**已安装的 Termux**执行。

前置条件（缺一不可，都在答题宝「工具集 → 设置与系统环境 → 完整 Python 环境」调试配置页完成）：
    ① 手机已安装 Termux；
    ② 答题宝已被授予 com.termux.permission.RUN_COMMAND 权限；
    ③ Termux 侧 ~/.termux/termux.properties 里 allow-external-apps=true。
通道没就绪时，这里不会把 Termux 界面抢到前台，只返回可操作的错误说明（如实转告用户即可）。

用法：
    import android_termux
    print(android_termux.available())          # 通道自检：装没装/授权没/进程活没活/通道通不通
    android_termux.wake()                       # 后台唤醒 Termux 服务（不打开界面），长跑前建议先调
    r = android_termux.run("uname -a; pwd")     # 在 Termux 里执行，返回 dict
    print(r["exit_code"], r["stdout"])
    # 需要 Ubuntu 容器里的完整环境（apt/pip/pandas/matplotlib）时加 container=True（一句调用，不用每次手写 ~/ubuntu -- …）：
    # android_termux.run("python3 -c 'import pandas; print(pandas.__version__)'", container=True)
    # android_termux.submit("python3 ~/big.py", "job1", container=True)   # 容器里跑长任务 + 活动卡

超过 20 秒的长任务：run() 只在 20 秒内等回执，且 Termux RUN_COMMAND 无 cancel——
到点后命令仍在 Termux 里跑到自然结束，那一次的返回值拿不到结果。要"捞回结果"必须
把任务 detached 出去、让它把进度/结果写文件，之后用一条秒回命令去读（submit/poll/wait）：

    h = android_termux.submit("python3 ~/big.py", "bigstats")   # tmux detached，立即返回 job_id
    while True:
        p = android_termux.poll("bigstats")        # tail 日志 + 判完成，秒回
        print(p["output"], "running" if p["running"] else ("done rc=%s" % p["exit_code"]))
        if p["done"]:
            break
        time.sleep(5)
    # 或一步到位（注意 total 要小于当前 python_execute 的执行预算，否则这次调用自己先超时）：
    # android_termux.wait("bigstats", total=90, interval=5)
    # 用完清理（作业日志在公共 /sdcard/Download/OilQuiz/jobs，需显式 purge 才不留垃圾）：
    # android_termux.list_jobs()   # 列出所有作业及状态
    # android_termux.purge("bigstats")  # 删除该作业的 log/rc/done/sh 并关 tmux 会话

返回 dict 字段：run → exit_code / stdout / stderr / status(success|failed) / environment(termux) / error；
submit → job_id / launcher(tmux|nohup) / log；poll/wait → running / done / output / exit_code；
list_jobs → jobs=[{id,done,exit_code?}]；purge → status。
job_id 只允许字母数字和 . _ -（拼进路径与 tmux 会话名，避免注入）。run 单条默认 20 秒超时。
"""

import time as _time
import shlex as _shlex

_JAVA_CLASS = "com.oilquiz.app.ai.tool.TermuxEnvInstaller"
# runInTermuxAndWait 的 ChannelResult 不回传退出码，用尾标记从 stdout 里捞回来
_EXIT_MARKER = "__OZ_EXIT__"
# 异步作业：日志/退出码/完成标记落在**公共下载目录** /sdcard/Download/OilQuiz/jobs/，
# 这样答题宝 App 侧能直接 PublicStorageWriter.readTail(ctx, "jobs", ...) 轻量读（与 setup_log.txt
# 监控同源），活动卡刷新不必每次再发一条 RUN_COMMAND cat。Termux 写公共目录需先 termux-setup-storage。
# （App 读 File 失败时 PublicStorageWriter 会自动回退 MediaStore 查询。）
_JOBS_DIR = "/sdcard/Download/OilQuiz/jobs"
_RC_MARKER = "__OZ_RC__"
_DONE_MARKER = "__OZ_DONE__"
_LAUNCH_TMUX = "__OZ_LAUNCH__tmux"
_LAUNCH_NOHUP = "__OZ_LAUNCH__nohup"


def _sanitize_job_id(job_id):
    """job_id 会拼进文件路径与 tmux 会话名：只放行 [A-Za-z0-9._-]，其余返回空串（调用方判失败）。"""
    if job_id is None:
        return ""
    s = str(job_id)
    return s if s and all(c.isalnum() or c in "._-" for c in s) else ""


def _in_container(cmd):
    """把命令包成"在 Ubuntu 容器里跑"：~/ubuntu 是 proot-distro login ubuntu 的入口脚本，
    再用 /bin/bash -lc 起容器登录 shell（读容器自己的 /etc/profile），绕开 Termux PATH 被继承
    导致裸 git/gcc 命中 Termux 二进制的问题。需环境已按「完整 Python 环境」向导备好容器。"""
    return "~/ubuntu -- /bin/bash -lc " + _shlex.quote(str(cmd))


def _context():
    """拿 Android Application（Chaquopy 标准做法，取不到再退回 ActivityThread）。"""
    try:
        from com.chaquo.python import Python
        app = Python.getPlatform().getApplication()
        if app is not None:
            return app
    except Exception:
        pass
    try:
        from java import jclass
        return jclass("android.app.ActivityThread").currentApplication()
    except Exception:
        return None


def _installer():
    from java import jclass
    return jclass(_JAVA_CLASS)


def _s(v):
    """Java String / None -> Python str。"""
    return "" if v is None else str(v)


def available():
    """Termux 通道自检，返回 dict（不抛异常，供脚本判断能不能用 Termux）。

    字段：installed / version / run_command_permission / process_alive / storage_permission /
          channel_ok / diagnose(stdout) / error。
    """
    out = {"environment": "termux", "installed": False, "channel_ok": False}
    ctx = _context()
    if ctx is None:
        out["error"] = "拿不到 Android Context，无法调用 Termux"
        return out
    try:
        inst = _installer()
        ver = inst.termuxVersion(ctx)
        out["installed"] = ver is not None
        out["version"] = _s(ver) or None
        out["run_command_permission"] = bool(inst.hasRunCommandPermission(ctx))
        out["process_alive"] = bool(inst.termuxProcessAlive(ctx))
        out["storage_permission"] = bool(inst.termuxHasStoragePermission(ctx))
        if ver is None:
            out["error"] = "Termux 未安装：去「完整 Python 环境」调试配置页按向导安装"
            return out
        ch = inst.runInTermuxAndWait(ctx, inst.buildDiagnoseCommand(), 20)
        out["channel_ok"] = bool(ch.ok)
        out["diagnose"] = _s(ch.stdout)
        if not ch.ok:
            out["error"] = _s(ch.error) or "Termux 通道不通（多为 allow-external-apps 未开）"
    except Exception as e:  # noqa: BLE001
        out["error"] = str(e)
    return out


def wake():
    """后台唤醒 Termux 服务进程（**不打开 Termux 界面**）。返回 dict(status/message|error)。

    长跑任务前先调一次，确保 Termux 进程活着、并持有 wake-lock 减少被系统休眠掐断。
    """
    ctx = _context()
    if ctx is None:
        return {"status": "failed", "error": "拿不到 Android Context，无法唤醒 Termux",
                "environment": "termux"}
    try:
        err = _installer().wakeServiceInBackground(ctx)
        if err is None:
            return {"status": "success", "environment": "termux",
                    "message": "Termux 服务已在后台唤醒/已在运行（未打开界面），可用 run() 执行命令"}
        return {"status": "failed", "environment": "termux", "error": _s(err)}
    except Exception as e:  # noqa: BLE001
        return {"status": "failed", "environment": "termux", "error": str(e)}


def run(command, timeout=20, container=False):
    """在 Termux 里执行一条命令（完整 Linux 环境）。返回 dict。

    command: 交给 Termux 的 bash -lc 执行的命令串。
    timeout: 等待回执的秒数（默认 20；这是 Termux app-shell 启动+执行的现实上限，
             更久的任务请用 submit()/tmux 托管：submit("python3 big.py", "job1")）。
    container: True 时把命令包进 Ubuntu 容器（~/ubuntu -- /bin/bash -lc …）跑——需要 apt/pip/
             pandas/matplotlib 等容器里的完整环境时用它；需环境已按「完整 Python 环境」向导备好。
    """
    ctx = _context()
    if ctx is None:
        return {"exit_code": -1, "stdout": "", "stderr": "", "status": "failed",
                "environment": "termux", "error": "拿不到 Android Context，无法调用 Termux"}
    if not command or not str(command).strip():
        return {"exit_code": -1, "stdout": "", "stderr": "", "status": "failed",
                "environment": "termux", "error": "command 不能为空"}
    # 追加尾标记以回收真实退出码（ChannelResult 不带 exit code）
    cmd = _in_container(command) if container else str(command)
    wrapped = cmd + "; printf '\\n" + _EXIT_MARKER + "%d' $?"
    try:
        ch = _installer().runInTermuxAndWait(ctx, wrapped, int(timeout))
        ok = bool(ch.ok)
        stdout = _s(ch.stdout)
        stderr = _s(ch.stderr)
        exit_code = 0 if ok else 1
        # 从 stdout 尾部剥出退出码标记
        idx = stdout.rfind(_EXIT_MARKER)
        if idx >= 0:
            tail = stdout[idx + len(_EXIT_MARKER):].strip()
            stdout = stdout[:idx].rstrip("\n")
            try:
                exit_code = int(tail.split()[0]) if tail else exit_code
            except Exception:  # noqa: BLE001
                pass
        result = {
            "exit_code": exit_code,
            "stdout": stdout,
            "stderr": stderr,
            "status": "success" if (ok and exit_code == 0) else "failed",
            "environment": "termux",
        }
        if not ok:
            result["error"] = _s(ch.error) or "Termux 通道未回执"
        return result
    except Exception as e:  # noqa: BLE001
        return {"exit_code": -1, "stdout": "", "stderr": str(e), "status": "failed",
                "environment": "termux", "error": str(e)}


def submit(cmd, job_id, container=False):
    """把超过 20 秒的长任务 detached 到后台跑（tmux，没装则 nohup 兜底），立即返回，不阻塞。

    任务自己把 stdout/stderr 写公共目录 /sdcard/Download/OilQuiz/jobs/<id>.log，结束时把退出码写
    <id>.rc、并 touch <id>.done。之后 poll()/wait() 读回来，答题宝 App 的活动卡也能直接 File 读同一目录。
    （写公共目录需 Termux 已 termux-setup-storage；失败会返回明确报错。）

    cmd: 要跑的长命令。
    job_id: 作业名，只允许 [A-Za-z0-9._-]（拼进路径与 tmux 会话名）。
    container: True 时把命令包进 Ubuntu 容器（~/ubuntu -- /bin/bash -lc …）跑——跑 pandas/matplotlib
             等容器里的完整环境时用；需环境已按「完整 Python 环境」向导备好容器。

    注意：cmd 里若出现单独一行 'OZ_JOB_EOF'（heredoc 结束符）会破坏脚本，极罕见，避免即可。
    返回 dict(status/job_id/launcher/log 或 error)。
    """
    jid = _sanitize_job_id(job_id)
    if not jid:
        return {"status": "failed", "environment": "termux",
                "error": "job_id 只能含字母数字和 . _ -（当前值非法）"}
    if not cmd or not str(cmd).strip():
        return {"status": "failed", "environment": "termux", "error": "cmd 不能为空"}
    body = _in_container(cmd) if container else str(cmd)
    d = _JOBS_DIR
    runner = d + "/" + jid + ".sh"
    log = d + "/" + jid + ".log"
    rc = d + "/" + jid + ".rc"
    done = d + "/" + jid + ".done"
    # ① 可写预检（公共目录没授权/挂载会失败，给出明确指向）+ 落 runner 脚本
    #   （quoted heredoc，$? 原样写入、执行时才展开）+ 清旧标记
    setup = (
        "mkdir -p " + d + " 2>/dev/null; touch " + d + "/.wprobe 2>/dev/null "
        "|| { echo __OZ_NOWRITE__; exit 1; }; rm -f " + d + "/.wprobe; "
        "rm -f " + done + " " + rc + " " + log + "; "
        "cat > " + runner + " <<'OZ_JOB_EOF'\n"
        "#!/data/data/com.termux/files/usr/bin/bash\n"
        + body + "\n"
        "printf '" + _RC_MARKER + "%s\\n' \"$?\" > " + rc + "\n"
        "touch " + done + "\n"
        "OZ_JOB_EOF\n"
        "chmod +x " + runner
    )
    r = run(setup, timeout=20)
    if r["status"] != "success" or "__OZ_NOWRITE__" in r["stdout"]:
        return {"status": "failed", "environment": "termux",
                "error": "作业目录不可写: " + d + "。先在 Termux 里执行 termux-setup-storage 并点「允许」"
                         "（或在答题宝「完整 Python 环境」调试配置页授予存储权限），再重试。"}
    # ② detached 起：优先 tmux（脱离本命令进程树、跨调用续命），没装则 nohup 兜底
    start = (
        "if command -v tmux >/dev/null 2>&1; then "
        "tmux new-session -d -s oz_" + jid + " 'bash " + runner + " > " + log + " 2>&1'; "
        "echo " + _LAUNCH_TMUX + "; "
        "else nohup bash " + runner + " > " + log + " 2>&1 & echo " + _LAUNCH_NOHUP + "; fi"
    )
    r2 = run(start, timeout=20)
    launcher = None
    if _LAUNCH_TMUX in r2["stdout"]:
        launcher = "tmux"
    elif _LAUNCH_NOHUP in r2["stdout"]:
        launcher = "nohup"
    if launcher is None:
        return {"status": "failed", "environment": "termux",
                "error": "启动 detached 任务失败: " + (r2.get("error") or r2["stderr"] or r2["stdout"])}
    return {"status": "success", "environment": "termux", "job_id": jid,
            "launcher": launcher, "log": log}


def poll(job_id):
    """非阻塞查一次作业状态：tail 日志 + 判 .done 标记，本身是秒回命令。

    返回 dict(running/done/output/exit_code)：done=True 时 exit_code 为任务退出码（0=成功）；
    还没结束则 running=True、done=False、exit_code=None、output 为当前日志尾部（最长 4000 字符）。
    """
    jid = _sanitize_job_id(job_id)
    if not jid:
        return {"status": "failed", "environment": "termux", "error": "job_id 非法",
                "running": False, "done": False, "output": "", "exit_code": None}
    d = _JOBS_DIR
    cmd = (
        "tail -c 4000 " + d + "/" + jid + ".log 2>/dev/null; "
        "if [ -f " + d + "/" + jid + ".done ]; then cat " + d + "/" + jid + ".rc 2>/dev/null; "
        "echo " + _DONE_MARKER + "; fi"
    )
    r = run(cmd, timeout=20)
    out = r["stdout"]
    done = _DONE_MARKER in out
    exit_code = None
    output = out
    idx = out.rfind(_RC_MARKER)
    if idx >= 0:
        after = out[idx + len(_RC_MARKER):]
        try:
            exit_code = int(after.strip().split()[0]) if after.strip() else None
        except Exception:  # noqa: BLE001
            exit_code = None
        output = out[:idx].rstrip("\n")   # 去掉 rc/done 标记尾巴，只留日志
    return {
        "status": "success" if r["status"] == "success" else "failed",
        "environment": "termux", "job_id": jid,
        "running": not done and r["status"] == "success",
        "done": done, "output": output, "exit_code": exit_code,
    }


def wait(job_id, total=60, interval=5):
    """轮询直到作业完成或用完 total 秒，返回最后一次 poll() 的 dict（多带 timed_out 键）。

    ⚠️ total 是**这次 Python 调用自己要花的墙钟时间**，必须小于当前 python_execute 的执行预算，
    否则这次调用自己先超时、wait 也白等。真正的长跑建议用 submit + 跨多次调用 poll，而不是在这
    一次里 wait 很久。
    """
    deadline = _time.time() + max(1, int(total))
    last = poll(job_id)
    while not last.get("done") and _time.time() < deadline:
        _time.sleep(max(1, int(interval)))
        last = poll(job_id)
    last = dict(last)
    last["timed_out"] = not last.get("done")
    return last


def purge(job_id):
    """清理一个作业：删除公共 jobs/ 下 <id>.{log,rc,done,sh} 并关掉可能残留的 tmux 会话 oz_<id>。

    submit 只在同 id 重提交时清旧标记，不同 id 的作业会一直堆在 Termux home（App 侧 clearTmp 够不到
    Termux 私有目录），所以用完应显式 purge。
    返回 dict(status/output/environment)。
    """
    jid = _sanitize_job_id(job_id)
    if not jid:
        return {"status": "failed", "environment": "termux", "error": "job_id 非法"}
    d = _JOBS_DIR
    # tmux kill-session 对不存在的会话会报错，用 2>/dev/null 吞掉；rm -f 幂等
    cmd = (
        "(tmux kill-session -t oz_" + jid + " 2>/dev/null || true); "
        "rm -f " + d + "/" + jid + ".log " + d + "/" + jid + ".rc " + d + "/" + jid + ".done "
        + d + "/" + jid + ".sh; echo __OZ_PURGED__"
    )
    r = run(cmd, timeout=20)
    ok = r["status"] == "success" and "__OZ_PURGED__" in r["stdout"]
    return {"status": "success" if ok else "failed", "environment": "termux", "job_id": jid,
            "stdout": r["stdout"], "error": None if ok else (r.get("error") or r["stderr"])}


def list_jobs():
    """列出公共 jobs/ 目录下的作业及其状态（是否已完成）。返回 dict(jobs=[{id,done,exit_code?}])。"""
    cmd = (
        "for f in " + _JOBS_DIR + "/*.done; do [ -e \"$f\" ] || continue; "
        "id=$(basename \"$f\" .done); rc=$(cat " + _JOBS_DIR + "/$id.rc 2>/dev/null); "
        "echo \"__OZ_JOB__$id done $rc\"; done; "
        "for f in " + _JOBS_DIR + "/*.log; do [ -e \"$f\" ] || continue; "
        "id=$(basename \"$f\" .log); [ -f " + _JOBS_DIR + "/$id.done ] || echo \"__OZ_JOB__$id running\"; done"
    )
    r = run(cmd, timeout=20)
    jobs = []
    for line in r["stdout"].splitlines():
        line = line.strip()
        if not line.startswith("__OZ_JOB__"):
            continue
        parts = line[len("__OZ_JOB__"):].split()
        if not parts:
            continue
        item = {"id": parts[0], "done": len(parts) > 1 and parts[1] == "done"}
        if item["done"] and len(parts) > 2:
            try:
                item["exit_code"] = int(parts[2].replace(_RC_MARKER, "").strip())
            except Exception:  # noqa: BLE001
                item["exit_code"] = None
        jobs.append(item)
    return {"status": "success" if r["status"] == "success" else "failed",
            "environment": "termux", "jobs": jobs, "error": r.get("error")}
