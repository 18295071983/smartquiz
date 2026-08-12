package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayDeque;

/**
 * 流式按句语音合成播放器：AI 文本边生成边朗读
 *
 * 工作原理：
 * 1. {@link #feed(String)} 接收流式文本片段（任意线程），累积到缓冲区
 * 2. 按句界符（。！？，；、!?;\n…）切分出完整片段，清洗 Markdown 后入队
 * 3. 【流水线预合成】队首片段立即开始合成（不等上一句播完），
 *    播放与下一句合成并行进行，句间几乎无等待
 * 4. 缓冲超过阈值仍无句界符时强制切分，避免长时间无声
 * 5. {@link #finish()} 冲刷剩余缓冲；{@link #reset()} 停止并清空（新一轮生成/关闭开关时调用）
 */
public class StreamingTtsSpeaker {

    private static final String TAG = "StreamingTtsSpeaker";
    /** 缓冲超过该长度仍无句界符时强制切分，避免长时间无声 */
    private static final int MAX_PENDING_CHARS = 60;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 待合成文本队列 */
    private final ArrayDeque<String> textQueue = new ArrayDeque<>();
    /** 已合成待播放音频队列 */
    private final ArrayDeque<File> audioQueue = new ArrayDeque<>();
    private final StringBuilder pending = new StringBuilder();
    private boolean speaking = false;
    /** 是否正在预合成下一句 */
    private boolean synthesizing = false;
    /** 会话令牌：reset 时自增，过期回调直接丢弃 */
    private int session = 0;
    /** 本轮会话锁定的引擎决策（首句时确定，整轮不变，避免句间音色切换） */
    private Boolean sessionOnline = null;

    public StreamingTtsSpeaker(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 追加流式文本片段（可在任意线程调用，内部切主线程）
     */
    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        mainHandler.post(() -> {
            pending.append(chunk);
            extractSentences(false);
        });
    }

    /**
     * 流结束：冲刷剩余缓冲为最后一句
     */
    public void finish() {
        mainHandler.post(() -> extractSentences(true));
    }

    /**
     * 停止播放并清空队列/缓冲（新一轮生成开始或关闭自动朗读时调用，主线程调用）
     */
    public void reset() {
        mainHandler.post(() -> {
            session++;
            textQueue.clear();
            pending.setLength(0);
            for (File f : audioQueue) {
                deleteQuietly(f);
            }
            audioQueue.clear();
            speaking = false;
            synthesizing = false;
            sessionOnline = null;
            try {
                SpeechManager.getInstance(context).stopSpeaking();
            } catch (Exception ignored) {
            }
        });
    }

    /** 是否还有未播完的内容（队列或缓冲） */
    public boolean hasPendingContent() {
        return !textQueue.isEmpty() || !audioQueue.isEmpty()
                || pending.length() > 0 || speaking || synthesizing;
    }

    /** 从缓冲中切出完整片段入队；flushAll=true 时把剩余全部作为最后一片 */
    private void extractSentences(boolean flushAll) {
        String text = pending.toString();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 含逗号/顿号：更早切分，首句更快出声
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n' || c == '…'
                    || c == '，' || c == ',' || c == '、') {
                enqueueSentence(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        String remain = text.substring(start);
        // 超长无标点强制切分，避免长时间无声
        while (remain.length() > MAX_PENDING_CHARS) {
            enqueueSentence(remain.substring(0, MAX_PENDING_CHARS));
            remain = remain.substring(MAX_PENDING_CHARS);
        }
        pending.setLength(0);
        if (flushAll && !remain.isEmpty()) {
            enqueueSentence(remain);
        } else {
            pending.append(remain);
        }
        pumpSynthesis();
        pumpPlayback();
    }

    /** 清洗后入队（过短/纯符号的片段丢弃） */
    private void enqueueSentence(String raw) {
        String s = cleanForSpeech(raw);
        if (s.length() < 2) return;
        textQueue.add(s);
    }

    /**
     * 流水线合成：只要没有正在合成的任务且有待合成文本，立即开始合成下一句
     * （与当前播放并行，消除句间网络等待）
     *
     * 引擎锁定：在线模型可用时全程强制在线（单句失败则跳过，不回退系统TTS），
     * 否则全程系统TTS——保证同一条回复内音色始终一致
     */
    private void pumpSynthesis() {
        if (synthesizing) return;
        String sentence = textQueue.poll();
        if (sentence == null) return;
        synthesizing = true;
        final int mySession = session;
        // 本轮首句确定引擎后锁定，整轮不变，保证音色一致
        // （选择了系统TTS音色时全程系统引擎，不强制在线）
        if (sessionOnline == null) {
            SpeechManager sm = SpeechManager.getInstance(context);
            sessionOnline = sm.isOnlineTtsAvailable() && !sm.isCurrentTtsVoiceSystem();
        }
        final boolean onlineOnly = sessionOnline;
        AILogger.d(TAG, "流式朗读预合成(" + (onlineOnly ? "在线" : "系统") + "): "
                + (sentence.length() > 40 ? sentence.substring(0, 40) + "..." : sentence));
        SpeechManager.getInstance(context).synthesizeSpeech(sentence, onlineOnly)
                .whenComplete((result, throwable) -> mainHandler.post(() -> {
                    if (mySession != session) return; // 已被 reset，丢弃
                    synthesizing = false;
                    if (throwable == null && result != null && result.audioFile != null) {
                        audioQueue.add(result.audioFile);
                    } else {
                        AILogger.w(TAG, "流式朗读单句合成失败，跳过");
                    }
                    pumpSynthesis(); // 继续合成下一句
                    pumpPlayback(); // 有音频就播
                }));
    }

    /** 播放已合成的音频；播完自动接下一段 */
    private void pumpPlayback() {
        if (speaking) return;
        File audio = audioQueue.poll();
        if (audio == null) return;
        speaking = true;
        final int mySession = session;
        SpeechManager.getInstance(context).playAudioFile(audio, new TTSService.PlaybackCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onComplete() {
                mainHandler.post(() -> {
                    deleteQuietly(audio);
                    if (mySession != session) return; // 已被 reset，丢弃
                    speaking = false;
                    pumpPlayback();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    deleteQuietly(audio);
                    if (mySession != session) return;
                    AILogger.w(TAG, "流式朗读播放失败，跳过: " + error);
                    speaking = false;
                    pumpPlayback();
                });
            }
        });
    }

    private void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** 片段级轻量清洗：去代码标记/链接/Markdown符号/emoji */
    private String cleanForSpeech(String raw) {
        String t = raw;
        t = t.replaceAll("(?s)```.*?```", "");
        t = t.replaceAll("`", "");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("[*_#>~|]", "");
        t = t.replaceAll("[\\p{So}\\p{Cn}]", "");
        return t.trim();
    }
}
