# 抖音下载工具（douyin_downloader）内置版 v3.2 文档

> 2026-09-25 更新。工具已从"AI 动态创建"固化为**应用内置工具**，内核随 APK 发布，设备上任何文件丢失都能自愈重建，**不再需要回灌源码**。

## 一、一句话能力

输入抖音分享链接 / 整段分享文案 / 纯作品 ID，一次返回并下载：**无水印视频（无码率压缩）、背景音乐 BGM、封面图、图集多图、文案**，附标题 / 作者 / 点赞 / 发布时间 / 文件大小。

## 二、双通道架构（自动切换）

```
                  ┌─ 通道一：官方内核（首选，无限次、稳定）
用户链接 ──→ 工具 ─┤      assets/dy_builtin 内置（随 APK 发布，~1.06MB）
                  │      本地计算签名 → 直连抖音官方 aweme/v1/web/aweme/detail 接口
                  │      缺 UIFID → 自动开内置 WebView 抓取（自愈）
                  │      官方失败 → 自动重试一次 → 仍失败回退
                  └─ 通道二：第三方源 suxun（兜底，每日限 20 次 + 24h 缓存）
```

| 通道 | 来源 | 限制 | 触发条件 |
|---|---|---|---|
| 官方内核 | APK 内置（assets/dy_builtin） | 无（本地签名直连） | 默认首选；`source=official` 强制 |
| 第三方源 suxun | APK 内置脚本 | 每日约 20 次、24h 缓存 | 官方失败自动回退；`source=suxun` 强制 |

## 三、官方内核机制（v3.2 核心）

1. **内置随包**：`dy_official_core.py`（32KB）+ `dy_src/` 签名包（16 项）+ `dy_pkg/` 精简包（6 项），全部打进 APK `assets/dy_builtin/`。
2. **自解包落袋**：首次调用自动解包到应用私有目录 `files/dy_builtin/`（幂等：完整则跳过；半解包/损坏自动清理重解）。Python 加载内核时生成 `__pycache__/dy_official_core.cpython-310.pyc`。
3. **签名直连**：本地用 aBogus/websign 算签名，直连抖音官方接口，不走第三方解析服务。
4. **UIFID 自愈闭环**：
   - 官方 detail 接口强制要求真实 UIFID（只有浏览器能下发）；
   - 无正式 UIFID → 工具自动驱动内置 WebView 加载 `www.douyin.com` → 捕获回 AppCookieStore；
   - 官方报 `NEED_COOKIE`（登录态失效）→ 强制重新抓 UIFID → 自动重试官方一次；
   - 仍失败 → 回退 suxun。全程一次工具调用内完成。
5. **Cookie 安全**：
   - 内核 cookie 落盘在**应用私有目录** `files/dy_webview_cookie.txt`（外部明文文件已退役）；
   - 读取合并 `www.douyin.com` + `m.douyin.com` 两域已存登录态（含 `UIFID_TEMP` 兜底）。

## 四、参数说明

| 参数 | 说明 | 默认 |
|---|---|---|
| `url` | 分享链接 / 整段分享文案（自动提取 http 链接）/ 纯 aweme_id | 必填 |
| `what` | `all`=视频+BGM+封面+图集+文案；`video`=视频+BGM；`bgm`=仅 BGM；`image`=仅封面/图集 | `all` |
| `source` | `auto`=官方优先自动回退；`official`=仅官方内核；`suxun`=仅第三方源 | `auto` |
| `quality` | `best` / `sd` / `hd` | `best` |
| `force` | `true`=忽略本地缓存强制重新解析 | `false` |
| `save_dir` | 保存目录（工作区相对路径） | `files` |

## 五、工具级行为保障

- **AI 核心集常驻**：`douyin_downloader` 已加入 OnlineAgentEngine 核心工具集（CORE_TOOLS），AI 每轮直接可见、可直接调用，**无需 tool_registry 发现**。
- **超时放宽**：工具级超时 30s → 120s（首次执行含 UIFID 自愈抓取 15~17s，避免误杀）。
- **注册固化**：AIToolManager 内置注册 `douyin_downloader`；同名动态工具自动跳过（只留内置一份）。

## 六、存储与生命周期

| 项 | 路径 | 说明 |
|---|---|---|
| 内置内核+签名包 | APK `assets/dy_builtin/`（26 条目，1.06MB） | 随包发布，不可篡改 |
| 解包产物 | 私有 `files/dy_builtin/` | 首次调用自动生成；损坏自动重建 |
| 内核 cookie | 私有 `files/dy_webview_cookie.txt` | 安全落盘，外部明文已删 |
| 登录态（UIFID 等） | `AppCookieStore`（SharedPreferences MODE_PRIVATE） | 内置 WebView 自动捕获/续期 |
| 下载产物 | 工作区 `files/`（可指定 save_dir） | 视频/BGM/封面/图集/文案 |

## 七、使用示例

```
用户：下载这个抖音视频 https://v.douyin.com/xxxxx/
AI  ：douyin_downloader(url="https://v.douyin.com/xxxxx/", what="all", source="auto")

用户：只要这首歌的 BGM
AI  ：douyin_downloader(url="...", what="bgm")

用户：强制只走官方内核
AI  ：douyin_downloader(url="...", source="official")
```

## 八、排查指引

- 返回 `官方内核：NEED_COOKIE` → 工具会自动抓 UIFID 重试；仍失败则说明抖音 PC 端未发放正式 UIFID，自动回退 suxun（结果可用，非故障）。
- 返回 `官方内核脚本执行失败` → 看设备 logcat `DouyinDownloaderTool` / `PythonToolManager` 关键字；内核文件损坏会自动重建。
- 首次调用偶发慢（≤30s）属正常：含解包 + UIFID 抓取；此后秒级返回（缓存 + 已有 UIFID）。
