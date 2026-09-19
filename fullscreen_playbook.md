# 极光时钟 · 全屏（隐藏状态栏）操作手册

> **为什么之前怎么点都没用**：v7 壳没有任何系统栏能力 —— dex 实测无 `onShowCustomView`（HTML5 全屏被 Chromium 直接拒绝），
> 40 个桥里也没有全屏/方向桥；页面侧无权控制宿主窗口。所以只剩两条路：
> **路线 A**：用 adb 从系统层立刻搞定（本文，5 分钟）；**路线 B**：改壳出 v8 永久解决（施工单 v2）。

---

## 路线 A · adb 沉浸全屏（5 分钟，立即生效）

**原理**：`policy_control` 是 Android 自己的沉浸策略，由**系统**去隐藏系统栏，不需要壳配合 —— 所以不改包、不重签名、立刻可用。
代价：包名变了要重设一次。

### A1. 手机侧准备

1. 设置 → 关于手机 → 连续点「版本号」7 次，开启开发者选项
2. 设置 → 系统 → 开发者选项 → 打开「USB 调试」
3. 按品牌补一刀：
   - **小米 / 红米**：再打开「USB 调试（安全设置）」—— 不打开会写不进系统设置
   - **华为 / 荣耀**：同页打开「仅充电模式下允许 ADB 调试」
   - **OPPO / vivo / 一加**：确保 USB 调试已开，并关掉「USB 调试安全设置」类限制

### A2. 电脑侧准备

1. 下载 platform-tools（含 adb）：https://developer.android.com/tools/releases/platform-tools
2. 解压到任意目录（例如 `D:\platform-tools`），在该目录打开 PowerShell 或 CMD
3. 验证：`adb version` 能打印版本号即可

### A3. 连接设备

**USB（推荐）**
```
adb devices
```
- 手机弹出「允许 USB 调试吗」→ 勾选「始终允许」→ 允许
- 输出 `1a2b3c4d  device` = 成功；`unauthorized` = 手机上还没点允许

**无线（Android 11+，不用线）**
```
adb pair 192.168.x.x:配对端口       :: 手机「无线调试 → 使用配对码配对设备」里查看
adb connect 192.168.x.x:调试端口    :: 无线调试页面上显示的端口
```

### A4. 写入全屏策略（核心命令）

```
adb shell settings put global policy_control "immersive.preconfirms=*:immersive.full=com.cjhtmldemo.p09c2477b7a90"
```

| 想要的效果 | 参数写法 |
|---|---|
| 状态栏 + 导航栏都隐藏 | `immersive.full=包名` |
| 只隐藏状态栏 | `immersive.status=包名` |
| 只隐藏导航栏 | `immersive.navigation=包名` |
| 多个包（英文逗号） | `immersive.full=包名1,包名2` |

- 回读当前策略：`adb shell settings get global policy_control`
- 恢复系统默认：`adb shell settings put global policy_control immersive.preconfirms=*`
- 一次列全你装过的所有导出包：`adb shell pm list packages | findstr cjhtmldemo`

### A5. 生效条件（重要）

- **必须把目标 App 从「最近任务」里彻底划掉，再重新打开** —— 策略在窗口创建时应用
- 还不行就重启一次手机
- 生效后状态栏 + 导航栏同时消失，页面独占全屏；从顶部下滑仍可临时唤出（系统行为，正常）

### A6. 报错对照表

| 现象 | 原因 | 解决 |
|---|---|---|
| `adb: command not found` | 不在 platform-tools 目录 / 没配 PATH | `cd D:\platform-tools` 后再执行 |
| `no devices/emulators found` | 数据线只充电、缺驱动 | 手机选「传输文件」模式；装厂商 USB 驱动；换根线 |
| `unauthorized` | 手机上没点「允许」 | 看手机屏幕弹窗；或「撤销 USB 调试授权」后重插 |
| `device offline` | 连接不稳 | `adb kill-server` → `adb start-server` |
| 命令提示成功但状态栏还在 | OEM ROM 阉割了 policy_control | 换 LADB/Shizuku 再试一次；仍无效就走路线 B |
| 状态栏没了，但顶部内容被刘海/挖孔挡住 | 系统栏隐藏后视口上移 | v4 已用 `--cw/--ch` + `syncVV()` 自适应，正常；若错位把截图发我 |
| 其它 App 也变全屏 | 误用了 `immersive.full=*` | 改成具体包名 |
| 想临时取消 | —— | 执行「恢复系统默认」那条，无需卸载 |

### A7. 不想连电脑？

手机上装 **LADB**（本地 adb，需无线调试配对）或 **Shizuku**，在手机里直接执行同一条
`settings put global policy_control ...` 命令即可。

---

## 路线 B · v8 壳（一次做完，永久生效）

壳内用 `WindowInsetsController` 隐藏系统栏，页面点 ⛶ 随时切换，**对所有导出的包生效、不依赖电脑和 adb**。
详细代码、验收清单、已知风险见 `patch_fullscreen_bridge_v2.md`。五步：

```bat
:: 1) 改 apk_shell\src\main\java\com\cjhtmldemo\apk\MainActivity.java
::    （AppBridge 追加 3 个 @JavascriptInterface 方法 + BRIDGE_API 3 -> 4）
cd apk_shell
:: 2) 编译壳
.\gradlew.bat assembleRelease
:: 3) 重建元数据（必须，否则 ApkPacker 找不到图标/占位）
python tools\gen_meta.py build\outputs\apk\release\apk_shell-release-unsigned.apk
:: 4) 回灌模板到主项目 + 重编译（这一步 v1 施工单漏了）
Copy-Item build\outputs\apk\release\apk_shell-release-unsigned.apk ..\src\main\assets\apk_shell\base.apk -Force
cd ..
.\gradlew.bat assembleDebug
:: 5) 装机 → 先用 samples/engine_demo/index.html 自测台全跑一遍，再重导一次极光时钟
```

**页面侧（v4）零改动** —— v8 壳一装上，`hasBridgeFn('enterFullscreen')` 自动变 true，桥分支立刻接管。

---

## 两条路怎么选

| | 路线 A（adb） | 路线 B（v8 壳） |
|---|---|---|
| 见效时间 | 5 分钟 | 改码 + 编译约 30 分钟 |
| 需要什么 | 电脑 + USB 线 + 开调试 | 电脑 + 壳源码工程 + Gradle |
| 持久性 | 换包名要重设一次 | 永久，所有导出包自动生效 |
| 可控性 | 系统级（按包名限定） | 页面内点 ⛶ 随时切换 |
| 依赖 ROM | 少数 OEM 阉割该策略 | 无 |
| 适合 | 现在就想看效果 / 临时演示 | 正式发布、长期使用 |

**建议**：先用 A 立刻验证全屏后时钟的观感（表盘、刘海、横竖屏），确认满意后再做 B 固化下来。
