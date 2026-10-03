#!/data/data/com.termux/files/usr/bin/bash
# VNC 诊断：容器内日志 + 进程 + 锁 + socket
echo "===== websockify log ====="
proot-distro login ubuntu -- sh -c 'cat /tmp/quiz_websockify.log 2>/dev/null | tail -30'
echo "===== shell log ====="
proot-distro login ubuntu -- sh -c 'cat /tmp/quiz_shell.log 2>/dev/null | tail -20'
echo "===== 容器内进程 ====="
proot-distro login ubuntu -- sh -c 'ps aux | grep -E "Xvnc|websockify|xfwm4|xfce4-session" | grep -v grep'
echo "===== X 锁与 socket ====="
proot-distro login ubuntu -- sh -c 'ls -la /tmp/.X11-unix /tmp/.X1-lock 2>&1; echo ---; ls -la /tmp/quiz_*.log 2>&1'
echo "===== Termux 侧进程 ====="
ps -A | grep -E "proot|termux" | grep -v grep | head -10
echo "===== 端口 ====="
ss -tlnp 2>/dev/null | grep -E "5900|6080" || netstat -tlnp 2>/dev/null | grep -E "5900|6080"
echo "===== DONE ====="
