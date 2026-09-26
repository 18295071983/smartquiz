"""内置 Linux 工具箱的 Python 入口（与 linux_shell 工具共用同一套环境）。

环境由 App 启动时注入：PATH 末尾是内置工具目录，LD_LIBRARY_PATH 指向内置库目录，
所以这里直接 subprocess 就能用 busybox/openssl/ssh/curl/aria2c/rg/jq/sqlite3/... 。

注意：Android 只允许执行 nativeLibraryDir（随 APK 解压）里的文件，所以**不要**
把下载来的二进制写进工作区再执行（会 Permission denied）；脚本可以（sh 路径/脚本.sh）。

用法：
    import android_shell
    r = android_shell.run("curl -sI https://example.com | head -3")
    print(r["stdout"])
    print(android_shell.available())
"""

import os
import subprocess

TOOL_NAMES = ("busybox openssl ssh curl aria2c rg jq sqlite3 zstd zip unzip "
              "file tree ncdu htop ps tmux nano gawk").split()


def run(command, timeout=25, cwd=None):
    """执行 shell 命令，返回 dict(exit_code/stdout/stderr)；超时返回 exit_code=-1。"""
    try:
        p = subprocess.run(command, shell=True, capture_output=True, text=True,
                           timeout=timeout, cwd=cwd)
        return {"exit_code": p.returncode, "stdout": p.stdout, "stderr": p.stderr}
    except subprocess.TimeoutExpired:
        return {"exit_code": -1, "stdout": "", "stderr": "timeout after %ss" % timeout}
    except Exception as e:  # noqa: BLE001
        return {"exit_code": -1, "stdout": "", "stderr": str(e)}


def run_argv(argv, timeout=25, cwd=None):
    """按参数数组执行（不经过 shell），更安全。"""
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd)
        return {"exit_code": p.returncode, "stdout": p.stdout, "stderr": p.stderr}
    except subprocess.TimeoutExpired:
        return {"exit_code": -1, "stdout": "", "stderr": "timeout after %ss" % timeout}
    except Exception as e:  # noqa: BLE001
        return {"exit_code": -1, "stdout": "", "stderr": str(e)}


def available():
    """列出内置工具及其版本。"""
    out = {}
    for name in TOOL_NAMES:
        r = run(name + " --version 2>&1 | head -1", timeout=8)
        out[name] = (r["stdout"] or r["stderr"]).strip() or "(无)"
    return out


def tool_path(name):
    """返回内置工具的可执行文件绝对路径（找不到返回 None）。"""
    r = run("command -v " + name, timeout=5)
    p = (r["stdout"] or "").strip()
    return p or None
