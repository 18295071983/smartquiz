package com.oilquiz.app.util.render;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 视频渲染引擎：Android VideoView 应用内播放（不依赖系统播放器）。
 *
 * 渲染结果返回 {videoPath: 绝对路径}，由 FileRenderActivity 显示 VideoView 播放；
 * 设备无系统视频播放器时不再打不开（应用内即可播放 mp4/webm 等）。
 */
public class VideoRenderEngine implements FileRenderEngine {

    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".webm", ".3gp", ".mkv", ".mov", ".avi"};

    @Override
    public boolean canRender(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        for (String ext : VIDEO_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    @Override
    public String getEngineName() {
        return "应用内视频播放器";
    }

    @Override
    public String getFileTypeDescription(File file) {
        if (file == null) return "视频文件";
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp4")) return "MP4 视频";
        if (name.endsWith(".webm")) return "WebM 视频";
        if (name.endsWith(".3gp")) return "3GP 视频";
        if (name.endsWith(".mkv")) return "MKV 视频";
        if (name.endsWith(".mov")) return "MOV 视频";
        if (name.endsWith(".avi")) return "AVI 视频";
        return "视频文件";
    }

    @Override
    public void render(File file, RenderCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("视频文件不存在");
            return;
        }
        Map<String, Object> content = new HashMap<>();
        content.put("videoPath", file.getAbsolutePath());
        content.put("fileName", file.getName());
        content.put("fileSize", file.length() / 1024 + "KB");
        content.put("fileType", getFileTypeDescription(file));
        callback.onProgress(100);
        callback.onSuccess(content);
    }
}
