#!/usr/bin/env bash
# ==============================================================
#  SmartQuiz 远程桥接 - 电脑端一键启动（macOS / Linux）
#  用法：cd 到本文件夹，执行  bash start_dsh_bridge.sh
#  前提：已装 Python 3.9+ 与 Node.js 18+，并已安装 dsh（npm i -g @deepseek-ai/dsh）
#  说明：只需这一个窗口 —— 桥接会自己拉起 `dsh --profile acp`（stdio ACP），
#        不再需要单独开 ACP 服务窗口，也不占用 7800 端口/ACP token。
#  停止：Ctrl+C
# ==============================================================
set -e
cd "$(dirname "$0")"

if [ ! -f .bridge_token ]; then
  LC_ALL=C tr -dc 'a-z0-9' < /dev/urandom | head -c 32 > .bridge_token
fi
TOKEN="$(cat .bridge_token)"

echo
echo "  访问令牌 : $TOKEN"
echo "  配对页   : http://127.0.0.1:8218/pair"
echo "  工作目录 : $(pwd)"
echo

command -v dsh >/dev/null 2>&1 || { echo "[错误] 没找到 dsh：先装 Node.js，再 npm install -g @deepseek-ai/dsh"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "[错误] 没找到 python3：请先安装 Python 3.9+"; exit 1; }
echo "  dsh 版本 : $(dsh --version 2>/dev/null || echo '?')"
echo

echo "[1/1] 启动桥接服务（0.0.0.0:8218，会自动拉起 dsh ACP 子进程）..."
python3 dsh_bridge_server.py --port 8218 --token "$TOKEN" --cwd "$(pwd)"
