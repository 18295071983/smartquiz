package com.oilquiz.app.ai.speech.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 2026-11-04 真机：语音链路上有两处「走到才炸」的约定，日志里各出现过一次就再没人看：
 *
 * <ol>
 *   <li>{@code SpeechHttpClient.openPost} 会**立即 connect**（为了尽早暴露 TLS 握手错误），
 *       调用方拿到连接后再 {@code setRequestProperty} 会抛
 *       "Cannot set request property after connection is made" —— 真机表现是
 *       「获取 Qwen-TTS/CosyVoice 自定义音色失败」，自定义音色列表永远是空的；</li>
 *   <li>{@code OpenAiAsrEngine} 的 multipart 请求压根没带 Content-Type（注释还写着
 *       "必须在 connect 之前设置，但 openPost 已连接"），whisper 一类端点只能拒收。</li>
 * </ol>
 *
 * <p>两条都是"少一个参数、不报错、功能静默消失"，所以钉成源码契约：改回去就红。</p>
 */
public class SpeechHttpContractTest {

    private static String read(String rel) throws Exception {
        Path p = Paths.get(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void postWithContentTypeMustExist() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/speech/core/SpeechHttpClient.java");
        assertTrue("缺 Content-Type 重载：调用方只能在 connect 之后 set，必炸",
                src.contains("openPost(String urlStr, String apiKey, String contentType)"));
        assertTrue("重载必须把 contentType 传进 openConnection",
                src.contains("openConnection(urlStr, apiKey, \"POST\", true, false, contentType)"));
    }

    @Test
    public void customVoiceFetchSetsContentTypeAtOpenTime() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/speech/SpeechModelRegistry.java");
        assertTrue("自定义音色请求必须在建连前带上 Content-Type",
                src.contains("openPost(endpoint, config.apiKey, \"application/json\")"));
        assertFalse("connect 之后再 setRequestProperty 会抛异常",
                src.contains("conn.setRequestProperty(\"Content-Type\""));
    }

    @Test
    public void openAiCompatibleAsrSendsMultipartContentType() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/speech/asr/OpenAiAsrEngine.java");
        assertTrue("multipart 必须带 boundary，且在建连前设置",
                src.contains("multipart/form-data; boundary=\" + boundary"));
        assertTrue("必须走带 Content-Type 的 openPost 重载",
                src.contains("openPost(fullUrl, config.apiKey,"));
    }

    @Test
    public void voiceNamesWithSpacesGetASecondChance() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/speech/tts/DashScopeTtsEngine.java");
        assertTrue("音色名要先按空格/连字符转下划线再试一次枚举",
                src.contains("replace(' ', '_').replace('-', '_')"));
    }
}
