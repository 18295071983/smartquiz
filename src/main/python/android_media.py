# -*- coding: utf-8 -*-
"""本地媒体工具箱的 Python 入口（与 media_toolkit 工具共用同一套 Java 实现）。

能力（系统硬解硬编，无需 ffmpeg、无需权限、不联网）：
    probe          媒体信息（时长/分辨率/帧率/码率/轨道/编码器）
    frame          按时间/百分比/帧序号截帧，count=N 抽 N 张
    thumbnail      缩略图（默认 10% 处、最长边 512）
    extract_audio  无损抽音轨（aac->m4a、mp3->mp3）
    to_wav         解码成 WAV（默认 16kHz 单声道，可直接喂语音识别）
    trim           无损剪切（start/end 秒，关键帧对齐）
    transcode      转码/压缩/改分辨率/换容器（H.264/H.265/AAC）
    image_ops      图片缩放/裁剪/旋转/翻转/灰度/转格式/压缩

用法：
    import android_media
    info = android_media.probe("/sdcard/Download/a.mp4")
    print(info["metadata"]["duration_sec"])
    frames = android_media.frame("/sdcard/Download/a.mp4", count=9)
    android_media.to_wav("/sdcard/Download/a.mp4")

输入路径：绝对路径 / 工作区相对路径 / content:// URI；输出默认落工作区 files/media/。
"""

import json as _json

_JAVA_CLASS = "com.oilquiz.app.ai.tool.MediaToolkitTool"


def _context():
    """拿 Android Application（Chaquopy 标准做法，取不到再退回 ActivityThread）。"""
    try:
        from com.chaquo.python import Python
        app = Python.getPlatform().getApplication()
        if app is not None:
            return app
    except Exception:
        pass
    try:
        from java import jclass
        return jclass("android.app.ActivityThread").currentApplication()
    except Exception:
        return None


def _call(action, **kw):
    """调用底层实现，返回解析后的 dict（或原始字符串）。失败抛 RuntimeError。"""
    from java import jclass
    from java.util import HashMap

    ctx = _context()
    if ctx is None:
        raise RuntimeError("android_media: 拿不到 Android Context，无法调用媒体工具箱")

    params = HashMap()
    params.put("action", action)
    for key, value in kw.items():
        if value is None:
            continue
        if isinstance(value, bool):
            params.put(key, "true" if value else "false")
        elif isinstance(value, (int, float)):
            params.put(key, value)
        else:
            params.put(key, str(value))

    raw = str(jclass(_JAVA_CLASS).runJson(ctx, params))
    data = _json.loads(raw)
    if not data.get("success"):
        raise RuntimeError(data.get("error") or ("android_media 执行失败: " + action))
    res = data.get("result")
    if isinstance(res, str) and res.strip().startswith("{"):
        try:
            return _json.loads(res)
        except Exception:
            return res
    return res


def available():
    """默认的 java 类是否可加载（做能力自检用）。"""
    try:
        from java import jclass
        jclass(_JAVA_CLASS)
        return _context() is not None
    except Exception:
        return False


def probe(path):
    """媒体信息：返回 dict（type/tracks/metadata/image 等）。"""
    return _call("probe", path=path)


def frame(path, time=None, percent=None, index=None, count=None, exact=None,
          width=None, height=None, format=None, quality=None, output=None):
    """截帧。time=秒 / percent=0-100 / index=帧序号 / count=N 抽 N 张。返回 dict(files=[...])。"""
    return _call("frame", path=path, time=time, percent=percent, index=index, count=count,
                 exact=exact, width=width, height=height, format=format,
                 quality=quality, output=output)


def thumbnail(path, time=None, percent=None, max_side=512, format=None, output=None):
    """缩略图（默认 10% 处、最长边 512）。"""
    return _call("thumbnail", path=path, time=time, percent=percent, max=max_side,
                 format=format, output=output)


def extract_audio(path, output=None):
    """无损抽取音轨（不重新编码）：aac->m4a、mp3->mp3。"""
    return _call("extract_audio", path=path, output=output)


def to_wav(path, rate=16000, channels=1, output=None):
    """解码为 WAV（默认 16kHz 单声道 16bit，可直接喂语音识别）。"""
    return _call("to_wav", path=path, rate=rate, channels=channels, output=output)


def trim(path, start=0, end=None, output=None):
    """无损剪切（关键帧对齐，不重新编码，输出 MP4）。"""
    return _call("trim", path=path, start=start, end=end, output=output)


def transcode(path, video_mime="keep", audio_mime="aac", width=None, height=None,
              scale=None, bitrate=None, remove_audio=None, start=None, end=None,
              output=None, timeout=None):
    """转码/压缩/改分辨率/换容器。video_mime=keep 且无缩放/码率时等价于纯重封装（换容器）。"""
    return _call("transcode", path=path, video_mime=video_mime, audio_mime=audio_mime,
                 width=width, height=height, scale=scale, bitrate=bitrate,
                 remove_audio=remove_audio, start=start, end=end, output=output,
                 timeout=timeout)


def image_ops(path, width=None, height=None, max_side=None, crop=None, rotate=None,
              flip=None, gray=None, format=None, quality=None, output=None):
    """图片处理：缩放/裁剪(crop="x,y,w,h")/旋转/翻转(h|v)/灰度/转格式/压缩。"""
    return _call("image_ops", path=path, width=width, height=height, max=max_side,
                 crop=crop, rotate=rotate, flip=flip, gray=gray, format=format,
                 quality=quality, output=output)
