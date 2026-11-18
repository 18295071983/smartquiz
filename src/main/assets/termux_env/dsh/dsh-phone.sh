#!/data/data/com.termux/files/usr/bin/bash
# dsh-phone — 带 smartquiz-remote 桥接的 DSH 守护脚本 (Android/Termux)
#   网页 127.0.0.1:7788（要 token）   桥接 127.0.0.1:8218（手机 App 连这个）
#   用法: dsh-phone {start|stop|restart|status|url|open|log [n]}
NODE_BIN=/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
PORT=${DSH_PORT:-7788}
HOST=${DSH_HOST:-127.0.0.1}
BRIDGE=${DSH_BRIDGE_PORT:-8218}
SESSION=${DSH_TMUX:-oz_dshphone}
LOG=$HOME/dsh-phone.log
URLFILE=$HOME/.dsh/web-url

running_pid() { pgrep -f "[b]in\.js phone" 2>/dev/null | head -1; }
code() { curl -s -o /dev/null -w "%{http_code}" --max-time 4 "http://$HOST:$1/" 2>/dev/null; }
wakelock() { command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock; }

report() {
  pid=$(running_pid)
  echo "PID=${pid:-none}"
  echo "WEB  $PORT -> $(code $PORT)  (401=活着，要 token)"
  echo "BRIDGE $BRIDGE -> $(code $BRIDGE)  (404=在线，路径不存在才是 404)"
  [ -f "$URLFILE" ] && echo "URL=$(cat "$URLFILE")"
}

case "$1" in
  start)
    if [ -n "$(running_pid)" ]; then echo "ALREADY_RUNNING"; report; exit 0; fi
    mkdir -p "$HOME/.dsh"; : > "$LOG"
    wakelock
    if command -v tmux >/dev/null 2>&1; then
      tmux kill-session -t "$SESSION" 2>/dev/null
      tmux new-session -d -s "$SESSION" "exec node --expose-internals '$NODE_BIN' phone --no-open --host $HOST --port $PORT >> '$LOG' 2>&1"
    else
      setsid bash -c "exec node --expose-internals '$NODE_BIN' phone --no-open --host $HOST --port $PORT" >> "$LOG" 2>&1 < /dev/null &
    fi
    i=0
    while [ $i -lt 40 ]; do
      sleep 1
      u=$(grep -o "http://[^ ]*token=[A-Za-z0-9_-]*" "$LOG" 2>/dev/null | tail -1)
      if [ -n "$u" ] && [ "$(code $BRIDGE)" != "000" ]; then
        echo "$u" > "$URLFILE"
        echo "STARTED"; report; exit 0
      fi
      i=$((i+1))
    done
    echo "START_TIMEOUT"; tail -10 "$LOG"; exit 1
    ;;
  stop)
    pid=$(running_pid)
    tmux kill-session -t "$SESSION" 2>/dev/null
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
    sleep 1
    pid2=$(running_pid)
    if [ -n "$pid2" ]; then kill -9 "$pid2" 2>/dev/null; sleep 1; fi
    echo "STOPPED"; report
    ;;
  restart) "$0" stop >/dev/null 2>&1; sleep 1; "$0" start ;;
  status) report ;;
  url) cat "$URLFILE" 2>/dev/null || echo NO_URL ;;
  open) u=$(cat "$URLFILE" 2>/dev/null); [ -n "$u" ] && termux-open-url "$u" || echo NO_URL ;;
  log) tail -${2:-20} "$LOG" ;;
  *) echo "usage: dsh-phone {start|stop|restart|status|url|open|log [n]}" ;;
esac
