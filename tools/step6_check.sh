#!/data/data/com.termux/files/usr/bin/bash
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"; export PREFIX
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
STATUS="$HOME_DIR/.quiz_env_setup.status"
ok(){ echo "✅ $1"; }
bad(){ echo "❌ $1"; echo "step6=fail $(date '+%F %T')" >> "$STATUS"; exit 1; }
cat > "$HOME_DIR/ubuntu" <<'QUIZ_UBUNTU_EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec proot-distro login ubuntu -- "$@"
QUIZ_UBUNTU_EOF
chmod +x "$HOME_DIR/ubuntu"
GSRC=""
for c in /sdcard/Download/OilQuiz/termux_env/ubuntu-gui.sh "$HOME_DIR/storage/downloads/OilQuiz/termux_env/ubuntu-gui.sh"; do
  [ -f "$c" ] && GSRC="$c" && break
done
if [ -n "$GSRC" ]; then
  cp "$GSRC" "$HOME_DIR/ubuntu-gui" && chmod 700 "$HOME_DIR/ubuntu-gui"
  echo "✅ ubuntu-gui 已就位（$GSRC）"
else
  echo "⚠️  读不到 App 写好的 ubuntu-gui.sh（存储权限未授予？），本次跳过图形界面入口"
fi
if "$HOME_DIR/ubuntu" python3 -c 'import tkinter, curses, readline, sqlite3, ssl, lzma, multiprocessing; print("完整体 Python 验证通过", __import__("sys").version.split()[0])'; then
  echo "✅ 最终验证通过：~/ubuntu 里的 Python 完整"
else
  bad "最终验证失败：~/ubuntu 里缺 tkinter 或其它模块"
fi
echo "step6=ok $(date '+%F %T')" >> "$STATUS"
