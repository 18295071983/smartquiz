# APK 导出器（export_apk）全面测试报告

- 测试日期：2026-09-13
- 测试机：小米17（Android）
- 测试方式：设备端真实导出 + 拆包核验（zipfile 逐条解析 assets / res / resources.arsc）
- 产出位置：`/storage/emulated/0/Download/OilQuiz/agent_workspace/apk_export/`
- 测试素材：`.../agent_workspace/tmp/apktest/`

---

## 一、测试结果总览

| # | 用例 | 输入 | 结果 | 耗时 |
|---|------|------|------|------|
| A | html_file 单文件 | single/index_single.html | ✅ 通过 | 1.61s |
| B | html_dir 目录+自定义图标 | demo/ + icon_custom.png | ✅ 通过 | 1.48s |
| C | html 字符串 | 内联 HTML | ✅ 通过 | 1.71s |
| D | url 远程网页 | https://www.baidu.com | ✅ 通过 | 1.41s |
| E | emoji 图标 | icon_emoji=🚀 icon_bg=#0EA5E9 | ✅ 通过 | 1.78s |
| F | 目录自动取图标 | demo/（不传 icon_path） | ✅ 通过 | 1.56s |
| G4 | 同时传 html_file+html_dir | 两者都传 | ⚠️ 不报错，行为不一致 | 2.49s |
| G6 | 中文 apk_name | 中文名测试 | ✅ 通过 | 2.61s |
| H1 | apk_name 路径穿越 | ../../traversal_probe | ✅ 已净化 | 1.60s |
| H6 | 目录含中文文件名/子目录 | demo_cn/（样式/主.css、图片/图.png） | ✅ 通过 | 2.15s |
| I1 | 中文应用名 7 字（21 字节） | 一二三四五六七 | ✅ 通过 | 1.44s |
| I3 | ASCII 应用名 22 字 | ABCDEFGHIJKLMNOPQRSTUV | ✅ 通过 | 1.41s |
| J | 并发 2 个导出 | 同时发起 | ❌ 1 个失败 | — |

导出体积稳定在 **2.25~2.28 MB**，单次耗时 **1.4~2.6 秒**。

---

## 二、拆包核验（关键证据）

逐个 APK 解包比对，确认打包内容真实正确：

| 核验项 | 结果 |
|--------|------|
| 应用名写入 `resources.arsc` | ✅ 13/13 全部命中 |
| 启动图标槽 `res/RJ.png` | ✅ 自定义/自动/emoji 三类均正确落到该槽 |
| 内容载荷 `assets/dt.jet` | ✅ 体积随源内容变化；同源 B 与 F 完全一致(11856B) |
| 内置库注入 `assets/libs/` | ✅ jquery / vue3 / axios / dayjs / normalize / animate / echarts / katex / lodash / marked |
| 入口声明 `assets/manifest.json` | ✅ `{"main":"index.html","targver":1}` |
| url 模式 | ✅ URL 记入 manifest.json（`{"...","url":"https://www.baidu.com"}`） |
| 路径穿越逃逸 | ✅ 无逃逸，父目录干净 |

图标来源判定（按 MD5 精确比对源文件）：

- 用例 B → `res/RJ.png`(10443B) = `icon_custom.png` ✅
- 用例 F → `res/RJ.png`(8349B) = `demo/icon.png`（目录自动拾取）✅
- 用例 E → 生成 49567B emoji 图标 ✅
- 其余 → 壳默认图标(17232B) ✅

---

## 三、异常处理（报错清晰，均符合预期）

| 输入 | 报错信息 | 评价 |
|------|----------|------|
| html_file 指向不存在文件 | `HTML 文件不存在: .../nope.html` | ✅ 明确 |
| 不带任何来源参数 | `请提供 HTML 来源参数之一：url / html / html_file / html_dir` | ✅ 明确 |
| 应用名超长（22 汉字） | `应用名 UTF-8 长度超占位容量` | ⚠️ 表述含糊（见问题 2） |
| html_dir 缺 index.html | `HTML 目录缺少入口文件 index.html: .../demo/css` | ✅ 明确 |
| url 非 http(s) | `url 必须以 http:// 或 https:// 开头` | ✅ 明确 |
| icon_path 不存在 | `图标文件不存在: .../nope.png` | ✅ 明确 |
| icon_path 非 PNG | `图标必须是 PNG 文件: .../notimg.txt` | ✅ 明确 |

---

## 四、发现的问题（3 处）

### 问题 1：并发导出会互相抢临时目录（重要）

**现象**：同时发起 2 个 `export_apk`，必有 1 个失败，报 `APK 导出失败: 无法创建临时目录`。
**复现**：J 组用例稳定复现（J1 成功、J2 失败）；I 组 3 并发时 2 个失败。
**影响**：批量导出场景会静默丢结果，容易误判为素材有问题。
**建议**：批量导出改为**串行**调用；或工具侧给临时目录加随机/任务 ID 后缀、失败自动重试 1 次。

### 问题 2：应用名限制实际是「UTF-8 字节 ≤22」，不是「22 字符」

**现象**：7 个汉字（21 字节）通过；8 个汉字（24 字节）报错；22 个 ASCII（22 字节）通过。
**结论**：可用长度 ≈ **中文最多 7 字**、英文最多 22 字符。
**影响**：文档写「≤22 字符」有歧义，用户按字数填中文会莫名失败（且报错文案不说具体限制）。
**建议**：文档改为「UTF-8 字节 ≤22（约中文 7 字 / 英文 22 字）」，报错文案补充实际字节数与上限。

### 问题 3：html_file 与 html_dir 同时传入时行为不一致（次要）

**现象**：不报冲突错误；**正文取 html_file**（dt.jet=976 与用例 A 一致），**图标却从 html_dir 取了 icon.png**（与用例 F 一致）。
**影响**：来源优先级不统一，容易产出「内容 A + 图标 B」的混合包。
**建议**：同时传多个来源时直接报错（参数互斥），或统一优先级并在文档写明。

---

## 五、结论与使用建议

**结论**：导出器四条来源路径（单文件 / 目录 / 字符串 / 远程 URL）、三种图标方式（自定义 PNG / 目录 icon.png / emoji）、中文资源文件名、中文 apk_name、路径穿越防护均**工作正常**，异常输入报错清晰。核心功能整体可靠。

**使用建议**：
1. **批量导出务必串行**，不要并行调用（问题 1）。
2. 中文应用名**控制在 7 字以内**（问题 2）。
3. 来源参数**只传一个**，需要自定义图标时用 `icon_path` 明确指定（问题 3）。
4. 目录模式优先用 `html_dir`（相对资源、图标自动拾取、libs 自动注入都最省心）。
5. 产出统一在 `apk_export/` 下，可直接安装或分享。

---

## 附：测试产出清单

- 成功产出的 APK 共 13 个，位于 `apk_export/`：
  test_a_htmlfile / test_b_htmldir / test_c_htmlstring / test_d_url / test_e_emoji /
  test_f_autoicon / test_g4_conflict / test_h6_cn_files / test_i1_cn7 / test_i3_ascii22 /
  test_j1_parallel / 中文名测试 / .._.._traversal_probe

- 测试素材位于 `tmp/apktest/`：single/（单文件）、demo/（多资源目录）、demo_cn/（中文文件名）、icon_custom.png、notimg.txt
