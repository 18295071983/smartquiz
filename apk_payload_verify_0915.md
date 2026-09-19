# APK 载荷核验报告

日期：2026-09-15　范围：apk_export/ 下极光时钟系列 APK

## 一、结论（一句话）

两个关键 APK 的**真正嵌进去的页面**已解密核对，与工作区源文件**逐字节一致**：

| APK | 内嵌 index.html | 对照源文件 | 结果 |
|---|---|---|---|
| aurora_clock_v4.apk | 110788 B | files/aurora_clock_v4.html | ✓ sha256 一致（684399a2…） |
| aurora_clock_v4_mocktest.apk | 118040 B | files/aurora_clock_v4_mocktest.html | ✓ sha256 一致（b344a446…） |

- mock 包：确认含 `MOCK 壳桥注入` 块；v4 包：确认**不含**。
- 排除"导了陈旧文件 / 装错包 / 被覆盖"三类风险。

## 二、为什么要查

上一轮导包只拿到工具返回"成功"，无法证明**嵌进去的是当次页面**。设备端出包是黑盒，必须补一次产物级核验。

## 三、方法

1. 页面不在 `assets/*.html`，而是打包在 **`assets/dt.jet`**（全包 38 个 assets 中唯一承载页面者）。
2. 格式依据 `files/APK_SOURCE_GUIDE.md` 第 36 行：
   `HTML → ZIP → AES-128-CBC 加密（key=MyHtmlEditorKey1，IV=文件头 16 字节）→ dt.jet`
3. 设备无 pycryptodome / cryptography / openssl → **手写纯 Python AES-128-CBC**（S 盒程序生成，避免手打错）。
4. 格式自洽性校验：dt.jet 前 16B 为 IV，余下密文长度均为 16 的整数倍
   - v4：32656 ÷ 16 = 2041 ✓
   - mock：35120 ÷ 16 = 2195 ✓
5. 解密后均为合法 ZIP（头 4 字节 `PK\x03\x04`），内含单文件 `index.html`。

## 四、载荷尺寸时间线（assets/dt.jet 解压前大小）

| 产物 | 时间 | dt.jet B | 推断 |
|---|---|---|---|
| aurora_clock.apk | 09-14 21:04 | 29776 | v1 |
| 极光时钟.apk | 09-14 20:53 | 29776 | 同页重导（与上行 payload 相同） |
| dynamic_clock.apk | 09-14 20:53 | 3248 | 另一支小型页面 |
| aurora_clock_v2.apk | 09-15 05:29 | 30192 | v1 +416 |
| aurora_clock_v3.apk | 09-15 05:39 | 30464 | v2 +272 |
| aurora_clock_v3_mocktest.apk | 09-15 13:10 | 32992 | v3 + 注入 2528 |
| aurora_clock_v4.apk | 09-15 06:34 | 32672 | v4 基线（比 v3 大 2208） |
| aurora_clock_v4_mocktest.apk | 09-15 13:14 | 35136 | v4 + 注入 2464 |

尺寸随页面迭代单调变化；v4_mocktest(35136) ≠ v3_mocktest(32992)，证明"v3 基线 → v4 基线"的升级**确实打进包了**。

## 五、内嵌页面核对（解密后）

| APK | 内嵌字节 | 行数 | renderMeta | orientSettle | minmax(0,1.35fr) | #cmGreet | MOCK 块 |
|---|---|---|---|---|---|---|---|
| aurora_clock_v4.apk | 106862 | 1596 | ✓ | ✓ | ✓ | ✓ | ✗ |
| aurora_clock_v4_mocktest.apk | 113858 | 1689 | ✓ | ✓ | ✓ | ✓ | ✓ |

## 六、附带发现

1. `极光时钟.apk` 与 `aurora_clock.apk` 的 dt.jet 完全相同（均 29776）——同一页面导了两次仅换应用名，可删其一省空间。
2. **全部 8 个包均为 shell v7 / bridge_api 3**，无任何 v8 —— 再次佐证"设备端无法产出 v8 壳"。
3. `classes.dex` 内出现 `<html` / `MOCK` 字样，是壳自带示例页（samples/engine_demo）与库字符串，**与内嵌页面无关**（排查中的红鲱鱼）。

## 七、口径订正

- mock 桥数：**40 个基础桥 + 3 个 v8 桥 = 43（v8 模式）／40（v7 模式）**。
- 此前口述的「43+3」有误，应为「40+3=43」，特此订正。

## 八、遗留

- Mock 预演包待真机跑测：面板切 v8 → 桥数应显 43、`getBridgeApi` 应回 4、点 ⛶ 应走 `enterFullscreen(x)` 日志。
- v8 真壳仍需电脑端五步（见 files/patch_fullscreen_bridge_v2.md）。
