package com.oilquiz.app.util.render;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 音频渲染引擎：应用内 MediaPlayer 播放（不依赖系统播放器）。
 *
 * 渲染结果返回 {audioPath: 绝对路径}，由 FileRenderActivity 显示播放控制条；
 * 覆盖抖音下载的 BGM（mp3/m4a/flac）及常见音频格式。
 */
public class AudioRenderEngine implements FileRenderEngine {

    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".wav", ".m4a", ".flac", ".ogg", ".opus",
            ".aac", ".amr", ".ape", ".wma", ".mid", ".midi", ".mka"
    };

    @Override
    public boolean canRender(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    @Override
    public String getEngineName() {
        return "应用内音频播放器";
    }

    @Override
    public String getFileTypeDescription(File file) {
        if (file == null) return "音频文件";
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp3")) return "MP3 音频";
        if (name.endsWith(".wav")) return "WAV 音频";
        if (name.endsWith(".m4a")) return "M4A 音频";
        if (name.endsWith(".flac")) return "FLAC 音频";
        if (name.endsWith(".ogg")) return "OGG 音频";
        if (name.endsWith(".opus")) return "Opus 音频";
        if (name.endsWith(".aac")) return "AAC 音频";
        if (name.endsWith(".amr")) return "AMR 音频";
        return "音频文件";
    }

    @Override
    public void render(File file, RenderCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("音频文件不存在");
            return;
        }
        Map<String, Object> content = new HashMap<>();
        content.put("audioPath", file.getAbsolutePath());
        content.put("fileName", file.getName());
        content.put("fileSize", file.length() / 1024 + "KB");
        content.put("fileType", getFileTypeDescription(file));
        callback.onProgress(100);
        callback.onSuccess(content);
    }
}
