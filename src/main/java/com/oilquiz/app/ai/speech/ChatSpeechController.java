package com.oilquiz.app.ai.speech;

import android.content.Context;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;

/**
 * 聊天语音朗读控制器（全局可复用）。
 *
 * 从 AIChatActivity 语音/TTS 编排抽取：AI 消息转可朗读文本、朗读启动/停止编排、
 * "同一条消息再次点击则停止"判定、自动朗读状态机。引擎（SpeechManager / TTS 引擎族 /
 * StreamingTtsSpeaker）独立存在，本类只做编排；宿主通过 {@link Host} 注入提示与
 * UI 线程调度。
 *
 * 用法：
 * <pre>
 * ChatSpeechController controller = new ChatSpeechController(context, host);
 * controller.speakMessage(message, messageId);          // 朗读
 * controller.stopIfSpeaking(actionMessageId);            // 同消息再次点击 → 停止
 * </pre>
 */
public class ChatSpeechController {

    /** 宿主回调（UI 侧能力注入） */
    public interface Host {
        /** 弹提示（Activity 侧可套 getString） */
        void onToast(int resId);
        /** 主线程调度 */
        void runOnUi(Runnable r);
    }

    private final Context context;
    private final Host host;

    /** 当前正在朗读的消息 id（null 表示未在朗读） */
    private volatile String speakingMessageId;

    public ChatSpeechController(Context context, Host host) {
        this.context = context.getApplicationContext();
        this.host = host;
    }

    public String getSpeakingMessageId() { return speakingMessageId; }

    public boolean isSpeaking(String messageId) {
        return messageId != null && messageId.equals(speakingMessageId)
                && SpeechManager.getInstance(context).isSpeaking();
    }

    /**
     * 将 AI 回复转换为可朗读文本：去除代码块、表格、Markdown 符号、
     * 工具调用痕迹、链接与 emoji；清洗后为空说明内容不适合朗读。
     */
    public static String toSpeakableText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String t = raw;
        t = t.replaceAll("(?s)```.*?```", "");
        t = t.replaceAll("`([^`]*)`", "$1");
        t = t.replaceAll("(?m)^\\s*\\|.*$\\n?", "");
        t = t.replaceAll("(?m)^\\s*🔧.*$\\n?", "");
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("[*_#>~|\\\\]", "");
        t = t.replaceAll("[\\p{So}\\p{Cn}]", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** 朗读 AI 消息（超长截断 2000 字符） */
    public void speakMessage(ChatMessage message, String messageId) {
        String text = toSpeakableText(message != null ? message.getContent() : null);
        if (text.isEmpty()) {
            host.onToast(R.string.h_6d66ba3b);
            return;
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
            host.onToast(R.string.h_4bc876d2);
        }
        speakTextInternal(text, messageId, false);
    }

    /** 实际朗读入口：silent=true 时不弹任何提示（自动朗读模式） */
    public void speakTextInternal(String text, final String messageId, final boolean silent) {
        speakingMessageId = messageId;
        if (!silent) {
            host.onToast(R.string.h_f3df22c9);
        }
        SpeechManager.getInstance(context).speakLocked(text,
                new TTSService.PlaybackCallback() {
                    @Override
                    public void onStart() {
                        if (!silent) {
                            host.runOnUi(() -> host.onToast(R.string.h_c5d49541));
                        }
                    }

                    @Override
                    public void onComplete() {
                        host.runOnUi(() -> {
                            if (messageId != null && messageId.equals(speakingMessageId)) {
                                speakingMessageId = null;
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        host.runOnUi(() -> {
                            if (messageId != null && messageId.equals(speakingMessageId)) {
                                speakingMessageId = null;
                            }
                            if (!silent) {
                                host.onToast(R.string.h_f9154462);
                            }
                        });
                    }
                });
    }

    /**
     * 处理"朗读"按钮：同一条消息再次点击则停止；否则朗读该消息。
     * 返回 true 表示已处理（停止或开始）；target 为 null 时返回 false 由宿主兜底。
     */
    public boolean handleSpeakAction(ChatMessage.Action action, ChatMessage target) {
        SpeechManager speech = SpeechManager.getInstance(context);
        if (action != null && action.messageId != null && action.messageId.equals(speakingMessageId)
                && speech.isSpeaking()) {
            speech.stopSpeaking();
            speakingMessageId = null;
            host.onToast(R.string.h_715ae415);
            return true;
        }
        if (target == null) {
            if (action == null || action.content == null) return false;
            target = ChatMessage.createAIMessage(action.content);
        }
        speakMessage(target, action != null ? action.messageId : null);
        return true;
    }

    /** 停止当前朗读 */
    public void stopSpeaking() {
        SpeechManager.getInstance(context).stopSpeaking();
        speakingMessageId = null;
    }
}
