# 变更日志

## [2026-09-27] 修复「完整 Python 环境」一键准备在"已装过的机器"上必然失败（幂等 + 全程日志 + 真实取证）

用户报「Termux 有个问题」。Termux 自身没有 crash_logs、logcat 无 FATAL，终端文字既不在 logcat 也不在无障碍树，
于是**截图 + 电脑端 Windows OCR** 取证（本模型不能看图，OCR 是唯一读屏通道），拿到真实报错：

```
⚠️  未授予存储权限：读不到本地包 /sdcard/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz
Error: container 'ubuntu' already exists. Specify a different name with --name NAME
[Process completed (code 1)]
```

1. **根因（实测钉死）**：`proot-distro 5.9.0` 的 `list` 把人类可读列表打到 **stderr**（实测 stdout 0 字节 / stderr 76 字节）。
   旧守卫 `proot-distro list 2>/dev/null | grep -q ubuntu` 丢掉 stderr → 永远判成"没装" → 再 `install` →
   `container already exists` → `set -e` 当场退出 1。同机实测 `proot-distro list -q` 输出 7 字节在 stdout，
   这才是 5.9 唯一机器可读的形态；另实测 5.9 的容器真实路径是 `$PREFIX/var/lib/proot-distro/containers/<name>`
   （旧代码猜的 `installed-rootfs/` 在新版已不存在）。
2. **三处修正**：
   · **幂等判定三重**：容器目录 + `list -q` + `list 2>&1`，命中即跳过下载与安装（不再撞 already exists）；
   · **去掉 `set -e`**：逐步骤记 `❌`，结尾统一 `exit $FAIL`；容器内 Python 先探测、已完整就跳过 apt（省 2~4 分钟）；
   · 容器内安装用 `-n ubuntu` 显式命名，`~/ubuntu` 入口改用 heredoc 写入（避免嵌套引号）。
3. **可排障**：整段输出 `tee` 到 Termux 的 `~/.quiz_env_setup.log`，并写 `~/.quiz_env_setup.status`（末行 `fail=0/1`）。
   以后"看看 Termux 日志"就有确定文件可看。
4. **存储权限如实说明**：实测 Termux 未授权时 `/sdcard` 读拒绝、`~/storage` 不存在，本地 29MB 包用不上；
   界面状态区新增「Termux 存储权限」一行，脚本明确提示"执行 `termux-setup-storage` 并点允许，再点一次一键准备"，
   本次则联网下 30MB（清华镜像）。
5. **真机验证（机械抽取源码里的模板去真跑，不是字符串断言）**：
   ```
   ---- 3/5 Ubuntu 容器 ----
   ✅ 容器 ubuntu 已存在，跳过下载与安装（重复运行不会破坏已有环境）
   ---- 4/5 容器内完整 Python ----
   ✅ 容器内 Python 已完整，跳过 apt（省 2~4 分钟）
   ===== 全部完成 ✅ =====
   EXITCODE=0   （.quiz_env_setup.status: 2026-09-27 12:00:15 fail=0）
   ```
   同一场景旧脚本退出 1、新脚本退出 0。`TermuxEnvSetupDeviceTest` 增 9 条回归断言（含"不得只看 stdout""不得用 set -e"）
   + 1 个真实下发用例。
6. 附带澄清：`locks/ubuntu.lock` 残留**无害**（实测并发第二个 `proot-distro login` 正常成功、退出 0）。
## [2026-09-27] 新增「完整 Python 环境（Termux + Ubuntu）」一键准备（内置官方安装包）
1. 用户要求把完整 Python 环境**内置**。已实现：App 内置两个**官方产物**（`assets/termux_env/`），
   并提供「工具集 → 设置与数据 → 完整 Python 环境」界面三步走：
   · ① 安装 Termux（内置 APK，系统安装器确认一次）；
   · ② 导出 Ubuntu 根文件系统到 `Download/OilQuiz/termux_env/`；
   · ③ 在 Termux 里准备容器（装 proot-distro → 用导出的本地 tar.gz 建容器 → 容器内换清华源并 apt 装
     `python3-full python3-tk python3-venv python3-pip python3-setuptools` → 建 `~/ubuntu` 入口并打印验证）。
2. **内置的是官方包，不是网上随便找的**（用户此前自己下载的是 2020 年的旧包，安装被拒：`INSTALL_FAILED_VERSION_DOWNGRADE: 101 older than current 1002`）：
   · `termux-0.118.3-fdroid.apk` **108.6 MB**，来自 F-Droid 官方仓库（清华镜像 com.termux_1002.apk），**官方签名、非 debuggable**，GPLv3；
   · `ubuntu-base-24.04.5-base-arm64.tar.gz` **28.5 MB**，Ubuntu 官方 ubuntu-cdimage。
   我原先提案用 33.5MB 的 GitHub arm64 debug 包，最终改用 108.6MB 官方包——理由：debug 包是 debug 签名且 debuggable，
   用户以后**无法从 F-Droid 正常升级**（签名不同）且安全性更低。APK 因此从 617.7MB 增至约 755MB；
   若需要瘦身，删掉这两个 asset 即可，界面会提示「未内置」（代码仍可走下载通道扩展）。
3. **为什么不把 Termux 合并进 App**：Android 只允许 `targetSdk < 29` 的应用执行自己私有目录里的二进制，
   proot/apt 正是靠这一点才能跑（Termux 故意把 targetSdk 钉在 28）；答题宝是 `targetSdk 35`（build.gradle 里就有这条注释），
   所以只能「内置安装包 + 引导安装」。Manifest 里 `REQUEST_INSTALL_PACKAGES` 与 `com.termux.permission.RUN_COMMAND` 早已声明，FileProvider 也已配好。
4. 自动化程度：第 ③ 步优先用 Termux 的 `RUN_COMMAND` 自动下发（打开可见会话显示进度）；
   需要用户**手点一次**「允许」（小米禁止 adb 代授 `pm grant`，实测抛 SecurityException），
   且 Termux 侧 `allow-external-apps=true` 默认关闭——脚本第 0 步会自己写这个配置，
   但**首次**必须由用户手动跑一次（界面提供「复制手动命令」兜底，粘进 Termux 即可，脚本一次性把两件事都做掉）。
5. 许可合规：两份产物均非本项目代码，已在 `docs/THIRD_PARTY_NOTICES.md` 与资产副本中新增声明
   （Termux GPLv3 + 源码地址、Ubuntu 镜像许可与来源），并在 assets 里附 `README.txt` 说明来源/版本/用途。
6. 真机用例 `TermuxEnvSetupDeviceTest`（5 例）：内置包大小校验（>100MB / >25MB）、导出到公共目录、
   **准备脚本用 App 自带 busybox 的 `ash -n` 做真语法检查**、未授权时下发返回明确原因、布局控件齐全。
7. 实现过程中踩到/自查出的三件事（都已修）：
   · **AGP 会自动解包 assets 里 `.gz` 结尾的文件**：实测打包后条目从 `ubuntu-base-….tar.gz` 变成 `ubuntu-base-….tar`（101.8MB），
     按原名读资产直接失败（导出 0 字节）。改法：资产用中性后缀 `.targz.bin`，**导出给用户时还原成标准 `.tar.gz`**（proot-distro 按扩展名识别归档格式）。
   · **又抓到一次假绿**：`PythonEnvAuditDeviceTest` 里我加的断言被写成 `""ssl""`（双引号，Java 语法错）→ 该文件其实一直编译不过，
     而上一次「OK (1 test)」跑的是旧测试包。已修正，并在后续构建里**明确核对 `BUILD SUCCESSFUL` 字样**。
   · 准备脚本加了健壮性分支：Termux 若没有存储权限、读不到导出的本地包，**自动回退清华镜像下载**（实测下载仅 4.8 秒）。
8. 真机验证（`TermuxEnvSetupDeviceTest` **6/6 通过**）：
   · 内置包：`[EXP] 内置 Termux APK = 108 MB, Ubuntu rootfs = 28 MB`；
   · 导出：`[EXP] 导出: /storage/emulated/0/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz  29936675 字节`；
   · 脚本合法性：`[EXP] ash -n exit=0`（用 App 自带 busybox 做真语法检查，不是字符串断言之）；
   · **App→Termux 下发链路（一键准备的命脉）**：下发被接受 → adb 侧核对 Termux 私有目录 `_quiz_push.txt` 内容 = `PUSH_OK` ✓；
   · 界面布局 7 个控件齐全；未授权时下发会明确返回「需授予权限」。
9. APK 体积：617.7MB → **754.3MB**（+136.6MB = Termux 108.6 + rootfs 28.5，压缩后约 +137MB）。
   要瘦身只需删掉 `src/main/assets/termux_env/` 里两个大文件，界面会显示「未内置」（脚本仍有镜像回退分支，功能不受影响，只是回到联网下载）。

## [2026-09-27] 在真机上走通「完整 Python」路线：Termux + proot-distro Ubuntu（含自动化方法与三处坑）
1. 用户问「怎么走通」。目标：拿到带 tkinter/curses/readline 的真 Linux Python（App 内置的是 Chaquopy，Android 上永远没有这几样）。
2. 真机执行步骤（全部已在本机完成并验证）：
   · 装 **Termux 0.118.3（GitHub debug 包，targetSdk 28）**：选 debug 包是因为它 `android:debuggable=true`，
     可用 `adb shell run-as com.termux` **直接驱动**，绕开小米封掉的 `pm grant` 与输入注入（实测 `pm grant com.oilquiz.app com.termux.permission.RUN_COMMAND` 抛 SecurityException）；
     首次启动后 bootstrap 解压到 `files/usr`，实测 `run-as .../bash -lc 'echo EXEC_OK'` → EXEC_OK，证明 **Android 16 下 targetSdk 28 应用仍可执行私有目录二进制**（这是整条路线的前提）。
   · `pkg update`（自动选清华镜像）→ `pkg install -y proot-distro`（5.9.0，带 proot/clang/llvm）。
   · **坑①**：proot-distro 5.9 默认从 **Docker Hub** 拉镜像（`install ubuntu`）→ 国内卡死（进程挂在设备上、容器名被占，登录会报 `container is busy (PID: install)`）。
     解法：杀掉进程 + 清 `containers/ubuntu` 与 `locks`，改用 **URL 装根文件系统**：`proot-distro install -n ubuntu https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz` → **4.8 秒**（28.5MiB）。
   · 容器内换清华源 → `apt-get update && apt-get install -y --no-install-recommends python3-full python3-tk python3-venv ca-certificates python3-pip python3-setuptools`。
   · **坑②**：Ubuntu 24.04 是 **Python 3.12**，`distutils` 已被移除（想 import 它必失败，别把它当「缺失」）；用 `setuptools` 替代。
   · **坑③**：给容器写脚本时**不要嵌套引号**（我从 PowerShell 拼 `proot-distro login ubuntu -- bash -c '...'` 踩了两次：JSON 转义把换行变成字面 `\n`）。
     可靠做法：**本地写好脚本 → base64 → 设备端 `base64 -d` 落盘 → 让容器 `bash /path/script.sh` 执行**。
3. 结果（真机实测）：Ubuntu **24.04.5 LTS**，Python **3.12.3**，