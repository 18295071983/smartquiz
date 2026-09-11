package com.oilquiz.app.ai.spi;

import java.io.File;

/**
 * 文件目录提供者（SPI，解耦 Context.getCacheDir / getExternalFilesDir）。
 *
 * 逻辑组件通过本接口获取可写目录（录音临时文件等）；
 * 宿主实现决定存储位置（应用缓存 / 外部存储 / 测试临时目录）。
 */
public interface FileDirProvider {

    /** 应用缓存目录（必非 null） */
    File getCacheDir();

    /** 音乐外部目录（可 null，null 时组件回退 getCacheDir） */
    File getExternalMusicDir();
}
