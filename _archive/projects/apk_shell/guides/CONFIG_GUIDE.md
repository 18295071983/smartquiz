# 直播配置目录与变更指南（v9.15）

> 频道表、台标、应用名/图标都属于**导出数据**，不在壳源码里写死。
> 换频道/换图标只需要改 `html_dir` 里的配置文件再重新导出，不需要改壳代码或重编壳。

## 一、推荐目录

```
html_dir/
├── app.json                     # 应用名 / 图标 / 一键直播配置（可选）
├── index.html                   # 可选：自定义 HTML 首页（一键直播模式不加载）
├── config/
│   ├── channels.m3u             # ★ 频道表（M3U/M3U8）
│   └── logos/                   # ★ 台标目录
│       ├── cctv1.png
│       ├── hunan.png
│       └── ...
└── icon.png                     # 可选：应用图标
```

`app.json` 示例：

```json
{
  "name": "国内电视",
  "icon": "icon.png",
  "live": {
    "url": "config/channels.m3u",
    "name": "国内频道",
    "start": "",
    "autoplay": false
  }
}
```

- `live.url` 可以是包内相对路径（如 `config/channels.m3u`），也可以是远程 `https://.../live.m3u`。
- `live.autoplay=true` 或源里只有一路频道时，加载完直接进播放器。
- 导出参数 `live_url/live_name/live_start/live_autoplay` 优先于 `app.json`。

## 二、频道表格式

```m3u
#EXTM3U
#EXTINF:-1 tvg-name="CCTV-1 综合" tvg-logo="logos/cctv1.png" group-title="央视",CCTV-1 综合
http://example.com/live/cctv1.m3u8
#EXTINF:-1 tvg-name="湖南卫视" tvg-logo="https://example.com/hunan.png" group-title="卫视",湖南卫视
http://example.com/live/hunan.m3u8
```

- 支持 `tvg-name/tvg-logo/group-title`、`#EXTGRP`、`#EXTVLCOPT`（UA/Referer）、`#EXTHTTP`、相对流地址。
- `tvg-logo` 支持：
  - 远程 URL：`https://.../logo.png`
  - 包内相对路径：`logos/cctv1.png`（相对 `channels.m3u` 所在目录解析）
  - `file://` / `content://`（原生播放器可读取时）
- 台标建议 96×96 或 128×128 PNG；加载时会自动采样、圆角裁切并缓存。

## 三、内置自适应台标（没传图标时）

没有 `tvg-logo`、或台标加载失败时，原生首页/播放器会自动使用内置占位：

| 频道类型 | 占位 |
|---|---|
| CCTV-1~17 | 央视红底 + 数字 + 电视小图标 |
| 卫视 | 蓝绿底 + 频道首字 + 卫星小图标 |
| 地方台 | 深绿底 + 首字 + 定位小图标 |
| 新闻 / 体育 / 电影 / 少儿 | 对应分组色 + 首字 + 新闻/球/胶片/星星小图标 |
| 其他 | 按名称哈希取色 + 首字 + 默认电视图标 |

内置小图标是向量资源，不依赖外部图片库，也不占多少体积。真正台标由上面的 `logos/` 目录提供。

## 四、导出时如何指定配置

应用内 `export_apk`：

```text
export_apk(html_dir="D:/.../html_dir",
           live_url="config/channels.m3u",
           live_name="国内频道",
           live_autoplay=false)
```

不传 `live_url` 时自动读 `html_dir/app.json` 的 `live` 配置。

桌面 CLI：

```powershell
java -cp ".;<SDK>\build-tools\35.0.0\lib\apksigner.jar" ExportCli `
  <html_dir> <out.apk> --live-url "config/channels.m3u" --live-name "国内频道"
```

`html2apk/export_apk.ps1`：

```powershell
.\export_apk.ps1 -HtmlDir .\html_dir -LiveUrl "config/channels.m3u" -AppName "国内电视"
```

## 五、常见变更

| 想改什么 | 改哪里 | 是否需要重编壳 |
|---|---|---|
| 增删频道 | `config/channels.m3u` | 否，重新导出即可 |
| 换台标 | 替换 `config/logos/*.png` / 改 `tvg-logo` | 否 |
| 换频道源 | `app.json.live.url` 或导出参数 `live_url` | 否 |
| 改应用名/图标 | `app.json` / `icon.png` | 否 |
| 改播放器/直播首页代码 | 壳源码 | 是 |
