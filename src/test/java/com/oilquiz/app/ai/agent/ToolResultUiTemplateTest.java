package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具结果 UI 模板的取键口径（2026-10-23 别名表事故回归）。
 *
 * <p>上一轮把各处手抄的键清单并进 {@code ToolResultFields} 全局表时，顺手把
 * {@code output} 也当成了"路径别名"。可 {@code output} 在 shell / python 这类结果里
 * <b>就是那段标准输出</b>，于是：
 * <ul>
 *   <li>{@code system_resource} 的摘要在正文之后又打一行"📄 文件：&lt;整段 stdout&gt;"；</li>
 *   <li>{@code DynamicToolResultRenderer} 把 {@code {name, output:"已保存3条"}} 归成产物文件，
 *       生成一张 path 指向一段文本的 file_card —— 用户看到的就是"卡片文件链接打不开"。</li>
 * </ul>
 * 修在读取口径本身：全局 {@code PATH}/{@code ARTIFACT} 只收键名就是路径的键，
 * 真正把 {@code output} 当路径的只有写入类工具，那一处改用点名的
 * {@code PATH_FILE_OPS}。本测试两头都钉住：错的不许出现，历史对的不许被一起删掉。</p>
 */
public class ToolResultUiTemplateTest {

    /** shell 结果只有 output（stdout）时，不许再多出一行"文件：" */
    @Test
    public void shellStdoutIsNotRenderedAsFilePath() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", "shell_command");
        r.put("command", "ls -l /storage/emulated/0/Download/OilQuiz/agent_workspace/files/");
        r.put("output", "total 7078\n-rw-rw---- 1 u0_a265 media_rw 16725 APK_SOURCE_GUIDE.md");
        r.put("exit_code", "0");
        r.put("status", "success");

        String ui = ToolResultInterpreter.formatForUi("system_resource", r);

        assertFalse("stdout 又被当成文件路径渲染了一行: " + ui, ui.contains("文件："));
        // 输出本体仍然要出现，而且只出现一次（重复打印就是这次事故的样子）
        assertTrue("命令输出丢了: " + ui, ui.contains("APK_SOURCE_GUIDE.md"));
        assertFalse("输出被重复渲染: " + ui,
                ui.indexOf("APK_SOURCE_GUIDE.md") != ui.lastIndexOf("APK_SOURCE_GUIDE.md"));
    }

    /** 结果里确实有路径键时照旧显示——收紧别名表不能把正常行为一起砍掉 */
    @Test
    public void shellStillShowsRealPathKeys() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", "shell_command");
        r.put("command", "curl -o /sdcard/a.html https://x/a.html");
        r.put("output", "  % Total    % Received");
        r.put("file_path", "/sdcard/a.html");
        r.put("status", "success");

        String ui = ToolResultInterpreter.formatForUi("system_resource", r);
        assertTrue("真路径键不再被显示: " + ui, ui.contains("/sdcard/a.html"));
    }

    /** 写入类工具（file_generator）历史上就用 output 存落盘路径，点名表必须保住这一处 */
    @Test
    public void fileGeneratorStillReadsOutputAsPath() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", "write");
        r.put("status", "success");
        r.put("output", "/storage/emulated/0/Download/OilQuiz/agent_workspace/files/报告.md");
        r.put("bytes_written", "1024");

        String ui = ToolResultInterpreter.formatForUi("file_generator", r);
        assertTrue("PATH_FILE_OPS 没把 output 当路径了: " + ui, ui.contains("报告.md"));
    }

    /** file_reader 只给文件名时仍按名字显示（PATH_OR_NAME 的降级链保留） */
    @Test
    public void fileReaderFallsBackToBareName() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "notes.md");
        r.put("content", "正文内容");
        r.put("total_lines", "3");

        String ui = ToolResultInterpreter.formatForUi("file_reader", r);
        assertTrue("裸文件名不再显示: " + ui, ui.contains("notes.md"));
    }
}
