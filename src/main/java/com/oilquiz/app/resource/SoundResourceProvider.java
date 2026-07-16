package com.oilquiz.app.resource;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RawRes;

import java.util.HashMap;
import java.util.Map;

/**
 * 音效资源提供者
 * 通过系统资源接口动态获取提示音效
 */
public class SoundResourceProvider {
    
    private static final String TAG = "SoundResourceProvider";
    private static SoundResourceProvider instance;
    private Context context;
    
    // 音效池
    private SoundPool soundPool;
    
    // 音效资源映射表
    private Map<String, Integer> soundResourceMap;
    
    // 已加载的音效ID映射
    private Map<String, Integer> loadedSoundMap;
    
    // 音效加载监听器
    private SoundLoadListener soundLoadListener;
    
    public interface SoundLoadListener {
        void onSoundLoaded(String soundName, int soundId);
        void onSoundLoadError(String soundName, Exception error);
    }
    
    private SoundResourceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.soundResourceMap = new HashMap<>();
        this.loadedSoundMap = new HashMap<>();
        initSoundPool();
        initSoundResources();
    }
    
    public static synchronized SoundResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new SoundResourceProvider(context);
        }
        return instance;
    }
    
    /**
     * 初始化音效池
     */
    private void initSoundPool() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(10)
                    .setAudioAttributes(audioAttributes)
                    .build();
        } else {
            soundPool = new SoundPool(10, android.media.AudioManager.STREAM_NOTIFICATION, 0);
        }
        
        // 设置加载完成监听器
        soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status == 0) {
                Log.d(TAG, "Sound loaded successfully: " + sampleId);
            } else {
                Log.e(TAG, "Error loading sound: " + sampleId + ", status: " + status);
            }
        });
    }
    
    /**
     * 初始化音效资源映射
     * 使用系统默认音效，不依赖自定义音效文件
     */
    private void initSoundResources() {
        // 由于项目没有自定义音效文件，这里不注册任何音效
        // 所有音效都通过系统MediaPlayer播放
    }
    
    /**
     * 设置音效加载监听器
     */
    public void setSoundLoadListener(SoundLoadListener listener) {
        this.soundLoadListener = listener;
    }
    
    /**
     * 加载音效资源
     * @param soundName 音效名称
     * @return 音效ID
     */
    public int loadSound(String soundName) {
        // 检查是否已加载
        if (loadedSoundMap.containsKey(soundName)) {
            return loadedSoundMap.get(soundName);
        }
        
        // 获取资源ID
        Integer soundResId = soundResourceMap.get(soundName);
        if (soundResId == null) {
            Log.e(TAG, "Sound resource not found: " + soundName);
            return -1;
        }
        
        // 加载音效
        try {
            int soundId = soundPool.load(context, soundResId, 1);
            if (soundId > 0) {
                loadedSoundMap.put(soundName, soundId);
                if (soundLoadListener != null) {
                    soundLoadListener.onSoundLoaded(soundName, soundId);
                }
            }
            return soundId;
        } catch (Exception e) {
            Log.e(TAG, "Error loading sound: " + soundName, e);
            return -1;
        }
    }
    
    /**
     * 播放音效
     * @param soundName 音效名称
     */
    public void playSound(String soundName) {
        playSound(soundName, 1.0f, 1.0f, 1, 0, 1.0f);
    }
    
    /**
     * 播放音效（带参数）
     * @param soundName 音效名称
     * @param leftVolume 左声道音量 (0.0 - 1.0)
     * @param rightVolume 右声道音量 (0.0 - 1.0)
     * @param priority 优先级
     * @param loop 循环次数 (-1表示无限循环)
     * @param rate 播放速率 (0.5 - 2.0)
     */
    public void playSound(String soundName, float leftVolume, float rightVolume, 
                         int priority, int loop, float rate) {
        int soundId = loadSound(soundName);
        if (soundId > 0) {
            soundPool.play(soundId, leftVolume, rightVolume, priority, loop, rate);
        } else {
            Log.w(TAG, "Cannot play sound, not loaded: " + soundName);
        }
    }
    
    /**
     * 停止播放音效
     * @param soundName 音效名称
     */
    public void stopSound(String soundName) {
        Integer soundId = loadedSoundMap.get(soundName);
        if (soundId != null) {
            soundPool.stop(soundId);
        }
    }
    
    /**
     * 暂停播放音效
     * @param soundName 音效名称
     */
    public void pauseSound(String soundName) {
        Integer soundId = loadedSoundMap.get(soundName);
        if (soundId != null) {
            soundPool.pause(soundId);
        }
    }
    
    /**
     * 恢复播放音效
     * @param soundName 音效名称
     */
    public void resumeSound(String soundName) {
        Integer soundId = loadedSoundMap.get(soundName);
        if (soundId != null) {
            soundPool.resume(soundId);
        }
    }
    
    /**
     * 卸载音效资源
     * @param soundName 音效名称
     */
    public void unloadSound(String soundName) {
        Integer soundId = loadedSoundMap.get(soundName);
        if (soundId != null) {
            soundPool.unload(soundId);
            loadedSoundMap.remove(soundName);
        }
    }
    
    /**
     * 注册自定义音效资源
     * @param soundName 音效名称
     * @param soundResId 音效资源ID
     */
    public void registerSound(String soundName, @RawRes int soundResId) {
        soundResourceMap.put(soundName, soundResId);
    }
    
    /**
     * 播放系统提示音
     */
    public void playBeep() {
        // 使用系统默认通知音效
        try {
            MediaPlayer mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
            if (mediaPlayer != null) {
                mediaPlayer.start();
                mediaPlayer.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing beep sound", e);
        }
    }
    
    /**
     * 播放成功音效（使用系统音效）
     */
    public void playSuccess() {
        // 使用系统默认通知音效
        try {
            MediaPlayer mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
            if (mediaPlayer != null) {
                mediaPlayer.start();
                mediaPlayer.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing success sound", e);
        }
    }
    
    /**
     * 播放错误音效（使用系统音效）
     */
    public void playError() {
        // 使用系统默认错误音效
        try {
            MediaPlayer mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI);
            if (mediaPlayer != null) {
                mediaPlayer.start();
                mediaPlayer.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing error sound", e);
        }
    }
    
    /**
     * 播放警告音效（使用系统音效）
     */
    public void playWarning() {
        playError(); // 复用错误音效
    }
    
    /**
     * 播放点击音效（使用系统音效）
     */
    public void playClick() {
        // 点击音效通常不需要播放，或者使用系统默认音效
        playBeep();
    }
    
    /**
     * 播放正确答案音效
     */
    public void playCorrect() {
        playSuccess();
    }
    
    /**
     * 播放错误答案音效
     */
    public void playWrong() {
        playError();
    }
    
    /**
     * 播放测验完成音效
     */
    public void playComplete() {
        playSuccess();
    }
    
    /**
     * 播放导入开始音效
     */
    public void playImportStart() {
        playBeep();
    }
    
    /**
     * 播放导入完成音效
     */
    public void playImportComplete() {
        playSuccess();
    }
    
    /**
     * 播放导出开始音效
     */
    public void playExportStart() {
        playBeep();
    }
    
    /**
     * 播放导出完成音效
     */
    public void playExportComplete() {
        playSuccess();
    }
    
    /**
     * 获取所有可用音效名称
     * @return 音效名称数组
     */
    public String[] getAvailableSounds() {
        return soundResourceMap.keySet().toArray(new String[0]);
    }
    
    /**
     * 检查音效是否可用
     * @param soundName 音效名称
     * @return 是否可用
     */
    public boolean isSoundAvailable(String soundName) {
        return soundResourceMap.containsKey(soundName);
    }
    
    /**
     * 检查音效是否已加载
     * @param soundName 音效名称
     * @return 是否已加载
     */
    public boolean isSoundLoaded(String soundName) {
        return loadedSoundMap.containsKey(soundName);
    }
    
    /**
     * 预加载所有音效
     */
    public void preloadAllSounds() {
        for (String soundName : soundResourceMap.keySet()) {
            loadSound(soundName);
        }
    }
    
    /**
     * 释放所有音效资源
     */
    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        loadedSoundMap.clear();
        instance = null;
    }
}
