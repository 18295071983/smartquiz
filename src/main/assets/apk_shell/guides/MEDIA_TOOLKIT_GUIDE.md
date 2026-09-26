# 本地媒体工具箱指南（media_toolkit）

> 版本：v1.0 · 随 App 打包，**不依赖 ffmpeg 或任何外部二进制；处理过程不需要任何权限、不联网**（读取外部文件仍受 App 已有存储访问限制）
> 相关文件：`files/使用速查表.md`（速查）、`files/核心工具速查.md`（工具清单）、`files/LINUX_TOOLKIT_GUIDE.md`（内置 Linux 工具箱）

## 一、为什么要有它（而不是 ffmpeg）

Android 上「装个 ffmpeg」这条路在本项目里已经试过并**放弃**（见提交 56a8821 的记录）：

1. Termux 版 ffmpeg 依赖大量 stub 库，`libbs2b.so` 之类**没有 .dynamic 段**，加载即失败；
2. 它带的 `libc++_shared.so` / 系统同名库（libz、libssl…）与 App 自己的库**名字与 ABI 冲突**，改别名也压不住；
3. Android **不允许执行写进 App 数据目录的二进制**（`Permission denied`，exit 126），只能随包分发，代价是体积 +90MB。

而「看视频信息 / 截一帧封面 / 抽个音频 / 压一压再发 / 图片改尺寸」这些**高频需求，系统自带的多媒体框架就能做**，
而且是**硬编硬解**（MediaCodec），速度比软件 ffmpeg 还快，体积几乎为零。

所以：**媒体处理优先用 media_toolkit，不要去 shell 里找 ffmpeg（内置工具箱里没有）。**

## 二、怎么调用

| 需求 | 调用 |
|---|---|
| 看媒体信息 | `media_toolkit(action=probe, path=...)` |
| 截一帧当封面 | `media_toolkit(action=frame, path=..., time=3.5)` |
| 按百分比截帧 | `media_toolkit(action=frame, path=..., percent=50)` |
| 抽 9 张做九宫格预览 | `media_toolkit(action=frame, path=..., count=9)` |
| 缩略图 | `media_toolkit(action=thumbnail, path=...)` |
| 无损抽音轨 | `media_toolkit(action=extract_audio, path=...)` |
| 转成 16k WAV 喂识别 | `media_toolkit(action=to_wav, path=...)` |
| 剪掉片头 10 秒 | `media_toolkit(action=trim, path=..., start=10)` |
| 压缩 / 改分辨率 | `media_toolkit(action=transcode, path=..., scale=0.5, bitrate=2000)` |
| 换容器（mkv→mp4，不重编码） | `media_toolkit(action=transcode, path=..., video_mime=keep, audio_mime=keep)` |
| 图片缩放/裁剪/转格式 | `media_toolkit(action=image_ops, path=..., max=1080, format=webp, quality=80)` |
| Python 里用 | `import android_media` → `android_media.probe(path)` |

输入 `path` 支持：**绝对路径 / 工作区相对路径 / `content://` URI**（读取都支持；`transcode` 因为要写文件，要求本地文件）。
不传 `output` 时输出落 **工作区 `files/media/`**，返回的 `file` 是绝对路径，可直接交给 `image` / `video` / `dashboard` 组件渲染。

## 三、参数总表

| 参数 | 用于 | 说明 |
|---|---|---|
| `action` | 全部（必填） | probe / frame / thumbnail / extract_audio / to_wav / trim / transcode / image_ops |
| `path` | 全部（必填） | 输入文件（也接受 `file` / `url` 作为别名） |
| `output` | 全部 | 输出文件；相对路径按工作区解析 |
| `time` | frame/thumbnail | 截帧时间（**秒**，可小数，如 `3.5`） |
| `percent` | frame/thumbnail | 按时长百分比取帧（0–100） |
| `index` | frame | 按帧序号取帧（0 起，走 `getFrameAtIndex`） |
| `count` | frame | 均匀抽 N 张（`_1.._N` 命名） |
| `exact` | frame | `true`=精确帧（`OPTION_CLOSEST`，慢）；默认最近**关键帧** |
| `width` / `height` | frame/thumbnail/image_ops/transcode | 输出或缩放尺寸；只给一边时按原比例补另一边 |
| `max` | image_ops/thumbnail | 最长边上限（等比缩放） |
| `format` | frame/thumbnail/image_ops | `png` / `jpeg` / `webp`（默认 jpg） |
| `quality` | frame/thumbnail/image_ops | jpeg/webp 质量 1–100（默认 90） |
| `crop` | image_ops | `x,y,w,h`（**原图像素**，坐标系=当前显示方向即已按 EXIF 转正；自动夹到图片范围内）。为了裁剪精确，带 crop 时按原分辨率解码（>30MP 的巨图才会降采样并折算坐标） |
| `rotate` | image_ops | 90/180/270 或任意角度 |
| `flip` | image_ops | `h`=水平、`v`=垂直 |
| `gray` | image_ops | `true`=转灰度 |
| `start` / `end` | trim/transcode | 时间区间（**秒**；end 省略=到结尾） |
| `rate` / `channels` | to_wav | 采样率（默认 16000，范围 8000–48000）/ 声道 1 或 2（默认 1） |
| `video_mime` | transcode | `h264` / `h265` / `av1` / `vp9` / `keep`（默认 keep） |
| `audio_mime` | transcode | `aac` / `mp3` / `opus` / `vorbis` / `flac` / `keep` / `none` |
| `bitrate` | transcode | 视频码率 **kbps**（给了就强制重编视频） |
| `scale` | transcode | 等比缩放倍数（`0.5`=宽高减半）。**实际分辨率由设备编码器对齐决定**，返回 `output_width`/`output_height` 是真实值 |
| `remove_audio` | transcode | `true`=去掉音轨（等价 `audio_mime=none`） |
| `timeout` | transcode | 最长等待秒数（默认 180，最大 900，超时自动取消） |

## 四、常见用法示例

### 1. 先看信息再决定怎么处理（推荐第一步）

```json
{"action": "probe", "path": "/sdcard/Download/lesson.mp4"}
```

返回里 `metadata.duration_sec` / `width` / `height` / `bitrate_kbps` / `rotation`，
`tracks[]` 给出每条轨的 mime、编解码器名、采样率、声道、帧率，`type` 是 video/audio/image。

### 2. 封面 / 九宫格预览

```json
{"action": "frame", "path": "files/videos/a.mp4", "percent": 25, "max": 720}
{"action": "frame", "path": "files/videos/a.mp4", "count": 9, "width": 320}
```

### 3. 把视频里的语音喂给语音识别

```json
{"action": "to_wav", "path": "/sdcard/Download/talk.mp4"}
```

得到 `files/media/talk_16k.wav`（16kHz 单声道 PCM16），可直接交给 SenseVoice / 在线 ASR。

### 4. 无损剪切（秒级完成，不重编码）

```json
{"action": "trim", "path": "/sdcard/Download/a.mp4", "start": 12.5, "end": 40}
```

> 起点会**吸附到前一个关键帧**（返回里的 `actual_start_sec` 是真实起点）——无损剪切的必然结果。
> 要帧级精确只能重编码，那就用 `transcode` + `start/end`。

### 5. 压缩到能发微信（重编码 + 降分辨率 + 限码率）

```json
{"action": "transcode", "path": "/sdcard/Download/a.mp4", "video_mime": "h264",
 "scale": 0.5, "bitrate": 2000, "audio_mime": "aac"}
```

### 6. mkv → mp4（只换容器，不重编码，秒级）

```json
{"action": "transcode", "path": "/sdcard/Download/a.mkv", "video_mime": "keep",
 "audio_mime": "keep", "output": "a_from_mkv.mp4"}
```

### 7. 图片批处理风格

```json
{"action": "image_ops", "path": "/sdcard/DCIM/a.jpg", "max": 1080, "format": "webp", "quality": 80}
{"action": "image_ops", "path": "files/media/a.jpg", "crop": "100,80,600,600", "rotate": 90, "gray": true}
```

## 五、能力边界（如实告知用户）

**能做：**

- 读取系统解码器支持的容器：mp4 / m4a / mkv / webm / 3gp / mp3 / aac / flac / wav / ogg 等（**取决于设备**，旗舰机基本都全）；
- 视频截帧、缩略图、九宫格预览图；
- 无损抽音轨、无损剪切、无损换容器（重封装，不重编码，速度快）；
- H.264 / H.265 (/ AV1, 看设备编码器) 视频转码、降分辨率、限码率、去音轨；
- 图片缩放 / 裁剪 / 旋转 / 翻转 / 灰度 / 转格式 / 压缩。

**做不了（需要 ffmpeg，本项目没有）：**

- 复杂滤镜链：时间轴水印、画中画、多路混流、字幕烧录；
- 冷门容器/编码：avi、flv、rmvb、wmv、HEVC 10bit 等（**能否解码完全看设备**，probe 的 `tracks` 里 `codec` 会显示「(无可用解码器)」）；
- 帧级精确剪切（`trim` 是关键帧对齐；要精确必须 `transcode` 重编码，画质与耗时都上去了）；
- 音频重编码链路里的复杂处理（变速变调、降噪）。

> 另外：**能处理什么完全由设备的解码器/编码器决定**，跟我们装没装什么工具无关；本工具能读的文件范围仍受 App 已有存储访问限制（外部文件没授权就是读不到，`content://` 需要对方已授权）。
>
> 遇到做不了的，**直接告诉用户「设备媒体框架不支持这个格式/这件事」**，不要硬编一个能跑但结果是错的路径。

## 六、在 Python 里用同一套能力

```python
import android_media

info = android_media.probe("/sdcard/Download/a.mp4")
print(info["metadata"]["duration_sec"], info["tracks"][0]["codec"])

frames = android_media.frame("/sdcard/Download/a.mp4", count=9, width=320)
for f in frames["files"]:
    print(f["file"], f["time_sec"])

android_media.to_wav("/sdcard/Download/a.mp4")              # 16k 单声道 WAV
android_media.image_ops("files/a.jpg", max_side=1080, format="webp", quality=80)
android_media.transcode("files/a.mkv", video_mime="keep", audio_mime="keep", output="a.mp4")
```

可用函数：`probe / frame / thumbnail / extract_audio / to_wav / trim / transcode / image_ops / available`，
参数与工具完全一致；失败抛 `RuntimeError`（错误信息就是工具的失败原因）。

**纯图片批处理也可以直接用 Pillow**（已预装）—— 不需要走 android_media：

```python
from PIL import Image
for name in os.listdir("/sdcard/DCIM"):
    if name.lower().endswith((".jpg", ".jpeg")):
        im = Image.open("/sdcard/DCIM/" + name)
        im.thumbnail((1080, 1080))
        im.save("/sdcard/Download/thumbs/" + name, quality=80)
```

## 七、内部实现（开发者）

| 动作 | 用的系统 API |
|---|---|
| probe | `MediaMetadataRetriever`（时长/宽高/旋转/码率/帧数）+ `MediaExtractor`（逐轨 format）+ `MediaCodec.createDecoderByType`（编码器名）；图片走 `BitmapFactory` + `ExifInterface` |
| frame / thumbnail | `MediaMetadataRetriever.getScaledFrameAtTime/getFrameAtTime/getFrameAtIndex`（旋转元数据由框架自动应用，实测 Android 16 已转正） |
| extract_audio | `MediaExtractor` + `MediaMuxer`（aac→MP4、opus/vorbis→WebM）；mp3/amr 直接原样拷贝样本 |
| to_wav | `MediaExtractor` + `MediaCodec` 解码 → 自定义 `WavWriter` 边解码边混单声道/线性重采样落盘（**内存与文件长度无关**），最后回填 RIFF/data 长度 |
| trim | `MediaExtractor.seekTo(PREVIOUS_SYNC)` + `MediaMuxer` 拷贝样本、时间戳归零，并保留旋转信息（`setOrientationHint`） |
| transcode | Media3 `Transformer` 1.3.1（`media3-transformer` + `media3-effect`）：`Transformer.Builder` 指定输出 mime/码率（`DefaultEncoderFactory`+`VideoEncoderSettings`）、`Presentation.createForWidthAndHeight` 改分辨率、`Effects` 挂视频效果、`MediaItem.ClippingConfiguration` 做区间；Transformer 必须跑在有 Looper 的线程上，所以内部起了 `HandlerThread` 并用 `CountDownLatch` 等结果（超时 `cancel()`） |
| image_ops | `BitmapFactory`（带 `inSampleSize` 防大图 OOM；**带 crop 时按原分辨率解码**，避免裁剪坐标被降采样带偏——真机踩过）+ `Matrix`/`Canvas` + `ExifInterface` 自动方向 + `Bitmap.compress` |

代码位置：`src/main/java/com/oilquiz/app/ai/tool/MediaToolkitTool.java`（唯一实现，工具与 Python 共用）。

## 八、排错

| 现象 | 原因 / 处理 |
|---|---|
| probe 返回 `"type": "unknown"` | 系统媒体框架不认这个容器（avi/flv/rmvb…），如实告诉用户不支持 |
| `tracks[].codec` = `(无可用解码器)` | 设备没有该编码的解码器（常见于 HEVC 10bit / AV1 老机型） |
| transcode 报 `ERROR_CODE_ENCODER_INIT_FAILED` | 设备编码器不支持该编码/分辨率，改用 `h264` 或更小分辨率（`scale=0.5`） |
| transcode 超时 | 提高 `timeout`（最大 900），或改用 `trim`（无损，秒级） |
| 截帧拿到的是模糊/错位画面 | 关键帧对齐所致，加 `exact=true` 取精确帧 |
| 竖拍视频截出来的帧是横的？ | 本机实测（Android 16 / 25113PN0EC）：`rotation=90` 的 320x240 源，截出来是 240x320，**框架已自动转正**；老系统若不转，用 `image_ops(rotate=90)` 摆正（`probe` 的 `metadata.rotation` 可核对） |
| 输出图片 `format=webp` 体积没变小 | Android 30+ 用的是有损 webp；`quality` 调低即可 |
| transcode 出来的分辨率和要求的不一样 | 正常现象：设备编码器按宏块对齐向上取整（真机实测：要 160x120 实得 170x128）。以返回的 `output_width`/`output_height`（= 文件真实尺寸）为准，`resolution_note` 会说明差异 |
| `to_wav` 得到的 WAV 很小 | 源文件音轨本来就是低码率/或只有几秒，先用 `probe` 看时长 |
