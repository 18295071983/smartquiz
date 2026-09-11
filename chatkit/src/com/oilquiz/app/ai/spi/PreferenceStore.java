package com.oilquiz.app.ai.spi;

/**
 * 轻量键值持久化（SPI，解耦 SharedPreferences）。
 *
 * 逻辑组件通过本接口读写少量状态（如自动 TTS 开关）；
 * 宿主实现可基于 SharedPreferences / DataStore / 文件 / 内存。
 */
public interface PreferenceStore {

    boolean getBoolean(String key, boolean def);

    void putBoolean(String key, boolean value);
}
