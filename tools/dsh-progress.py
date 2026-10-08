#!/usr/bin/env python3
"""dsh-progress - 驱动 DSH 进度条插件（宿主侧 /dsh-progress/* 端点）。

用法
----
    # 开始/更新一条进度（value/total 可省略，省略时保持原值）
    python tools/dsh-progress.py set "编译原生库" 40 100 "正在编译 ggml-hexagon"
    python tools/dsh-progress.py set "编译原生库" 60          # 只推进数值
    python tools/dsh-progress.py set "编译原生库" --detail "链接中"

    # 完成 / 失败
    python tools/dsh-progress.py done  "编译原生库" "编译完成"
    python tools/dsh-progress.py fail  "编译原生库" "编译失败：见 build.log"

    # 查看 / 清空
    python tools/dsh-progress.py state
    python tools/dsh-progress.py clear            # 清空全部
    python tools/dsh-progress.py clear "编译原生库"  # 只清一条

参数
----
    --id ID        任务标识（默认取 label，用于区分多条并存进度）
    --ttl 秒       多久没更新就视为失效（默认 300）
    --base URL     端点基址（默认 http://127.0.0.1:19387）

退出码：0 成功；1 端点不可达或参数错误（方便脚本判断 DSH 是否在跑）。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = "http://127.0.0.1:19387"


def post(base, path, payload, timeout=5.0):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base + path, data=data,
        headers={"content-type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get(base, path, timeout=5.0):
    with urllib.request.urlopen(base + path, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main(argv=None):
    p = argparse.ArgumentParser(prog="dsh-progress", add_help=True,
                               description="驱动 DSH 进度条插件")
    p.add_argument("action", choices=["set", "done", "fail", "state", "clear"])
    p.add_argument("label", nargs="?", default="", help="任务名（set/done/fail 必填）")
    p.add_argument("value", nargs="?", type=float, default=None, help="当前进度值")
    p.add_argument("total", nargs="?", type=float, default=None, help="总量（默认 100）")
    p.add_argument("detail_pos", nargs="?", default=None, help=argparse.SUPPRESS)
    p.add_argument("--detail", default=None, help="细节文本")
    p.add_argument("--id", default=None, help="任务标识（默认用 label）")
    p.add_argument("--ttl", type=float, default=None, help="失效秒数（默认 300）")
    p.add_argument("--base", default=DEFAULT_BASE, help="端点基址")
    args = p.parse_args(argv)

    detail = args.detail if args.detail is not None else args.detail_pos

    try:
        if args.action == "state":
            out = get(args.base, "/dsh-progress/state")
            tasks = out.get("tasks", [])
            if not tasks:
                print("(无进行中的进度)")
                return 0
            for t in tasks:
                pct = (t["value"] / t["total"] * 100) if t.get("total") else 0
                print("%-24s %6.1f%%  %s/%s  [%s]  %ss 前更新  %s" % (
                    t.get("label", t.get("id")), pct, t.get("value"), t.get("total"),
                    t.get("state"), round(t.get("ageMs", 0) / 1000), t.get("detail", "")))
            return 0

        if args.action == "clear":
            out = post(args.base, "/dsh-progress/clear",
                       {"id": args.id or (args.label or None)})
            print("已清空:", out.get("cleared"), "剩余", out.get("remaining"))
            return 0

        if not args.label:
            print("set/done/fail 需要任务名，例如: set \"编译\" 40 100", file=sys.stderr)
            return 1

        payload = {"id": args.id or args.label, "label": args.label}
        if args.value is not None:
            payload["value"] = args.value
        if args.total is not None:
            payload["total"] = args.total
        if detail:
            payload["detail"] = detail
        if args.ttl is not None:
            payload["ttlMs"] = int(args.ttl * 1000)

        if args.action == "done":
            payload["state"] = "done"
            payload.setdefault("value", 1)
            payload.setdefault("total", 1)     # 未给数值时按 100% 收尾
        elif args.action == "fail":
            payload["state"] = "error"
        else:
            payload.setdefault("state", "running")

        out = post(args.base, "/dsh-progress/set", payload)
        t = out.get("task", {})
        pct = (t.get("value", 0) / t.get("total", 1) * 100) if t.get("total") else 0
        print("%s [%s] %.1f%%  %s" % (t.get("label"), t.get("state"), pct, t.get("detail", "")))
        return 0

    except urllib.error.URLError as e:
        print("端点不可达（DSH 是否在运行？插件是否已安装并重启？）: %s" % e, file=sys.stderr)
        return 1
    except Exception as e:                     # noqa: BLE001
        print("失败: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
