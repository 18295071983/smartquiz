package com.oilquiz.app.ai.speech;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 2026-11-04 真机反馈：「我选择本地模型还是弹出了预设的音色」。
 *
 * <p>根因：本地/系统 TTS 下 {@code resolveModelName} 返回的是「SystemTTS(内置)」，注册表按模型名
 * 匹配不到任何音色，于是走到「兜底全量预设」——列表里全是 qwen3 的 Cherry/Moon/Serena，
 * 选哪个都不会生效（系统 TTS 不认这些 id）。音色列表的作用域必须跟"当前选的 TTS 模型"一致：
 * 系统合成 → 只给系统音色；在线模型 → 只给该模型的音色。</p>
 */
public class VoiceListScopingTest {

    private static String read(String rel) throws Exception {
        Path p = Paths.get(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void fetchVoicesSkipsOnlinePresetsForLocalTts() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/speech/TTSService.java");
        assertTrue("本地/系统 TTS 下不能再用在线预设兜底",
                src.contains("if (voices.isEmpty() && !localTts)"));
        assertTrue("必须先把「是否本地 TTS」算出来再决定兜底",
                src.contains("boolean localTts = isLocalTtsConfigured();"));
    }

    @Test
    public void dialogDoesNotFallBackToPresetsForLocalTts() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ui/dialog/TTSVoiceSelectorDialog.java");
        int guard = src.indexOf("speechManager.isLocalTtsConfigured()");
        int preset = src.indexOf("voiceList = TTSService.getPresetVoices()");
        assertTrue("对话框里必须判断本地 TTS", guard > 0);
        assertTrue("兜底预设前必须先拦一道本地判断", preset > guard);
    }
}
