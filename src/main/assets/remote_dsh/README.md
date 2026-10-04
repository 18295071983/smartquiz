# 答题宝 · 远程桥接（电脑端）

这个文件夹是「答题宝」手机 App 远程控制电脑所需要的东西：

| 文件 | 作用 |
|---|---|
| `start_dsh_bridge.bat` | Windows 一键启动（双击即可） |
| `start_dsh_bridge.sh` | macOS / Linux 启动（`bash start_dsh_bridge.sh`） |
| `dsh_bridge_server.py` | 桥接服务本体（手机 ↔ 电脑之间的转发层，内部拉起 dsh 的 ACP 会话） |
| `pair_page.html` | 配对网页（浏览器里显示二维码那一页） |
| `qrcodegen.js` | 配对页二维码渲染（脚本会用到） |

## 一次性准备（约 5 分钟）
1. 装 **Node.js 18+**：https://nodejs.org
2. 装 **Python 3.9+**：https://python.org （Windows 安装时务必勾选 *Add Python to PATH*）
3. 装 **dsh** 并按提示登录/配置模型：
   ```
   npm install -g @deepseek-ai/dsh
   dsh
   ```
4. 把这 6 个文件放到电脑上**同一个文件夹**里（放桌面也行，文件缺一不可）。

## 每次使用
1. 双击 `start_dsh_bridge.bat`（macOS/Linux：`bash start_dsh_bridge.sh`）
   - **只弹一个窗口**：桥接服务（8218）。桥接会自己在后台拉起 `dsh --profile acp`
     （ACP over stdio），所以不再有「ACP 服务（7800）」那个窗口，也不需要 ACP token
   - 桥接窗口里会显示 **访问令牌**（`Access token`）与 dsh 版本，并自动打开配对页 `http://127.0.0.1:8218/pair`
2. 手机上打开 **答题宝 → 工具集 → 设置与数据 → 远程连接（电脑）** （或聊天页左侧工具抽屉「🖥️ 远程连接（电脑）」）
3. 点 **扫码配对**，扫电脑屏幕上配对页的二维码 → 自动填好地址与令牌
4. 回到聊天，直接说人话即可，例如：
   - 「用电脑看看 D 盘有哪些项目文件夹」
   - 「在电脑上跑一下 git status，把原始输出贴出来」
   - 「把这份文档在电脑上转成 PDF」

## 手机和电脑不在同一个 Wi-Fi？
先做内网穿透，把电脑的 **8218** 端口映射到公网（花生壳、frp、Tailscale 等都可以），
然后在手机「远程连接（电脑）」里用**手动配置**填：
- 地址：穿透后拿到的地址（如 `https://xxxx.vicp.fun`）
- 令牌：电脑窗口里打印的那个访问令牌

## 连不上怎么排查
- 电脑上那个窗口是否还开着？关掉窗口 = 断开
- 手机和电脑在同一 Wi-Fi 吗？不同网络要按上一节做穿透
- 令牌是否与电脑窗口里打印的一致（配对页上也能看到）
- 手机上点「连接」会显示具体失败原因；「连接」成功后状态里会写 ACP 通道是否可用
- 手机报「ACP 官方通道: 不可用 ✗」时，看桥接窗口的日志：
  - `找不到 dsh 命令` → 第 3 步没装好，或 `dsh` 不在 PATH 里（可用 `--dsh 绝对路径` 启动）
  - `error: unknown option '--host'` → 你用的是**旧版启动脚本**（0.1.5 时代要单独开 7800 端口）；
    用手机 App 里重新「导出电脑端程序」换成本文件夹里的新版脚本即可
  - 手机让它跑命令/写文件，回来说被沙箱挡住（`sandbox-local … ACL`）→ 桥接启动时加
    `--permission-mode danger-full-access`（新版启动脚本默认就是它，旧脚本才会撞上）

## 通道说明（升级排查用）
- v5 起 ACP 走 **dsh 原生 stdio**：桥接 spawn `dsh --profile acp`，用换行分隔 JSON-RPC 通信。
  不装第三方插件、不监听端口、不需要 ACP token；dsh 子进程随桥接启动/退出。
- 0.1.5 时代那条 `dsh --profile acp serve --host 127.0.0.1 --port 7800 --token xxx`
  依赖第三方插件 `dsh-acp-server`，客户端升级到 **0.2.0-rc.2** 后该命令直接报
  `unknown option '--host'`（新版 acp profile 只提供 stdio），所以本代码包已同步改为 stdio 通道。
- 只要 `dsh --version` 能跑、`dsh --profile acp --help` 显示 “Serve automation clients over
  Agent Client Protocol stdio”，本桥接即可正常工作。
- **嵌套 agent 的沙箱模式**：桥接默认给 `dsh` 子进程设 `DSH_PERMISSION_MODE=danger-full-access`
  （等效“不受沙箱限制、审批 never”）——远程控制电脑本来就要能读写文件、跑命令，手机侧已由桥接 token 把关。
  想收紧就用 `--permission-mode workspace-write`（或 `read-only`）启动；**注意 Windows 上 workspace-write
  需要给工作目录 materialize ACL 临时授权，实测常失败**（现象是电脑端回
  `Error: sandbox-local windows-acl temp grant materialization failed`，命令/写文件都跑不了），所以默认没用它。
- 直连命令（手机说「跑一下 xxx 命令」走 `/exec`）不经过 dsh，任何 dsh 版本都不受影响。

## 安全说明
- 只有拿到访问令牌的手机才能连上；令牌只保存在电脑本地 `.bridge_token` 文件里
- ACP 子进程只在本机 stdio 上跑，不监听任何端口；对外只有带令牌的 8218 桥接端口
- 拿到令牌的手机 = 能在电脑上读写文件/跑命令（嵌套 agent 默认不受沙箱限制）；令牌和隧道都要收好，
  别把 8218 直接暴露到公网而不加鉴权/访问控制
- 桥接服务默认只监听本机/局域网，公网访问请走隧道并自行确认映射范围
- 不用时关掉电脑上那个窗口即可（dsh 子进程会一起退出）；手机 App 里的「断开」只影响手机侧（电脑端不受影响）
