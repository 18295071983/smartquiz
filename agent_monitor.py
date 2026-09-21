import subprocess
import time
import os

DEVICE = "279b6c51"
REMOTE_DIR = "/sdcard/Android/data/com.oilquiz.app/files/agent_bridge"
LOCAL_LOG = r"D:\qzq\smartquiz\agent_log.txt"
TEMP_FILE = r"D:\qzq\smartquiz\_tmp_log.txt"

print("=" * 40)
print("  Agent 监控脚本启动")
print("=" * 40)
print(f"设备: {DEVICE}")
print(f"输出: {LOCAL_LOG}")
print("按 Ctrl+C 停止")
print()

# 清空日志
with open(LOCAL_LOG, "w", encoding="utf-8") as f:
    f.write("")

last_file = ""

while True:
    try:
        # 找最新的结果文件
        result = subprocess.run(
            ["adb", "-s", DEVICE, "shell", f"ls -t {REMOTE_DIR}/result_*.txt"],
            capture_output=True, text=True, encoding="utf-8"
        )
        files = result.stdout.strip().split("\n")
        latest = files[0].strip() if files and files[0].strip() else ""

        if latest and latest != last_file:
            print(f"[新任务] {latest}")
            last_file = latest

        if last_file:
            # 用 adb pull 拉文件
            subprocess.run(
                ["adb", "-s", DEVICE, "pull", last_file, TEMP_FILE],
                capture_output=True
            )

            # 读取并写到本地日志（UTF-8 无 BOM）
            with open(TEMP_FILE, "r", encoding="utf-8") as f:
                content = f.read()
            with open(LOCAL_LOG, "w", encoding="utf-8", newline="") as f:
                f.write(content)

            # 检查状态
            result = subprocess.run(
                ["adb", "-s", DEVICE, "shell", f"cat {REMOTE_DIR}/status.json"],
                capture_output=True, text=True, encoding="utf-8"
            )
            import json
            try:
                status = json.loads(result.stdout)
                if status.get("status") in ("done", "error"):
                    print(f"[完成] {status['status']}")
            except:
                pass

    except Exception as e:
        print(f"[错误] {e}")

    time.sleep(2)
