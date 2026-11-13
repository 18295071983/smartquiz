package com.oilquiz.app.ai.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * 历史文件的原子写入（2026-11-13，B10）。
 *
 * <p>旧实现先 `delete` 再 `rename`：中间任何一次进程被杀/断电都会让历史文件彻底消失，
 * 而且没有 fsync、没有备份。这几条用例钉住新行为：</p>
 * <ul>
 *   <li>首次保存后不残留 `.tmp`；</li>
 *   <li>覆盖前留一份 `.bak`，且 `.bak` 是**覆盖前**的内容；</li>
 *   <li>主文件仍是合法 JSON 数组（没被写坏）。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class ChatHistoryManagerAtomicSaveTest {

    private static final String MAIN = "ai_chat_history.json";
    private static final String TMP = MAIN + ".tmp";
    private static final String BAK = MAIN + ".bak";

    private Context context;
    private ChatHistoryManager manager;
    private File mainFile;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        manager = new ChatHistoryManager(context);
        mainFile = new File(context.getFilesDir(), MAIN);
        new File(context.getFilesDir(), TMP).delete();
        new File(context.getFilesDir(), BAK).delete();
        mainFile.delete();
    }

    private static ChatMessage ai(String id, String content) {
        return ChatMessage.createAIMessage(id, content, 1000L, null, 0, 0);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void firstSaveLeavesNoTempFile() {
        manager.saveAIChatHistory(Collections.singletonList(ai("T1-A1", "first")));

        assertTrue("主文件应存在", mainFile.exists());
        assertFalse("保存后不应残留 .tmp", new File(context.getFilesDir(), TMP).exists());
    }

    @Test
    public void overwriteKeepsBackupOfPreviousContent() throws Exception {
        manager.saveAIChatHistory(Collections.singletonList(ai("T1-A1", "first")));
        manager.saveAIChatHistory(Collections.singletonList(ai("T1-A2", "second")));

        File bak = new File(context.getFilesDir(), BAK);
        assertTrue("覆盖前应留一份 .bak（整文件丢失窗口的兜底）", bak.exists());
        assertTrue("备份应是覆盖前的内容", read(bak).contains("first"));
        assertTrue("主文件应是新内容", read(mainFile).contains("second"));
    }

    @Test
    public void mainFileStaysValidJson() throws Exception {
        manager.saveAIChatHistory(Collections.singletonList(ai("T1-A1", "hello")));
        manager.saveAIChatHistory(Collections.singletonList(ai("T1-A2", "hello again")));

        JSONArray arr = new JSONArray(read(mainFile));
        assertTrue("主文件应是合法 JSON 数组且非空", arr.length() >= 1);
    }
}
