package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * file_generator 的参数契约与"假成功"回归（2026-10-29，源自真机对话历史实测）。
 *
 * <p>三件事在这里钉住：</p>
 * <ol>
 *   <li><b>参数名只有一套权威</b>：{@code @Tool} 注解与在线 {@code ToolDefinition} 必须完全一致。
 *       此前在线表写着 {@code json_data}/{@code source_path} 而实现读的是 {@code data}/{@code source}，
 *       模型照 schema 填参必然落空——它当时的原话是"又发现两处签名差异"。</li>
 *   <li><b>桥把 object/array 序列化成字符串也要能用</b>：{@code data} 传 JSON 字符串时
 *       过去直接 {@code String cannot be cast to Map}。</li>
 *   <li><b>缺正文不许成功</b>：{@code report}/{@code markdown}/{@code config} 过去会落一份
 *       只有标题+时间戳的空文件并返回"成功"，{@code default} 分支更是把未知 action 降级成 create。
 *       假成功比报错危险——模型看到成功就不会再补数据。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class FileGeneratorParamContractTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "fgcontract-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------- 1. 参数名单一权威 ----------

    @Test
    public void annotationAndOnlineSchemaDeclareTheSameParams() {
        Tool ann = FileGeneratorTool.class.getAnnotation(Tool.class);
        assertNotNull_(ann != null, "file_generator 应有 @Tool 注解");
        List<String> fromAnnotation = new ArrayList<>();
        for (Param p : ann.params()) fromAnnotation.add(p.name());
        List<String> fromOnline = new ArrayList<>();
        ToolDefinition def = AIToolManager.getInstance(context).getToolDefinition("file_generator");
        assertNotNull_(def != null, "在线 schema 应能取到 file_generator");
        for (com.oilquiz.app.ai.tool.openai.ParamDefinition p : def.getParameters()) {
            fromOnline.add(p.getName());
        }
        Collections.sort(fromAnnotation);
        Collections.sort(fromOnline);
        assertEquals("注解与在线 ToolDefinition 的参数集必须一字不差（漂移过一次就再漂一次）",
                fromAnnotation, fromOnline);
        // 这些是模型会照着填的名字，缺一个就会拿到 null 再"假成功"
        for (String key : new String[]{"data", "config", "sections", "source", "destination",
                "old_text", "new_text", "replace_all", "file_path_b", "pretty", "type", "encoding"}) {
            assertTrue("参数表缺 " + key + "，实际=" + fromOnline, fromOnline.contains(key));
        }
        assertFalse("format 从未被实现读取，不该再出现在 schema 里", fromOnline.contains("format"));
        assertFalse("json_data 是实现不读的历史错名", fromOnline.contains("json_data"));
        assertFalse("source_path 是实现不读的历史错名", fromOnline.contains("source_path"));
    }

    @Test
    public void actionEnumIsExposedOnline() {
        ToolDefinition def = AIToolManager.getInstance(context).getToolDefinition("file_generator");
        List<String> enums = null;
        for (com.oilquiz.app.ai.tool.openai.ParamDefinition p : def.getParameters()) {
            if ("action".equals(p.getName())) enums = p.getEnumValues();
        }
        assertNotNull_(enums != null && !enums.isEmpty(), "action 必须有枚举，不能只活在描述文案里");
        assertTrue(enums.containsAll(java.util.Arrays.asList(
                "create", "write", "append", "edit", "diff", "json", "config",
                "markdown", "template", "report", "copy", "delete")));
        // 注解里的动作名要与枚举一致
        Tool ann = FileGeneratorTool.class.getAnnotation(Tool.class);
        List<String> actionNames = new ArrayList<>();
        for (Action a : ann.actions()) actionNames.add(a.name());
        Collections.sort(actionNames);
        List<String> sorted = new ArrayList<>(enums);
        Collections.sort(sorted);
        assertEquals("@Action 列表与在线 action 枚举必须同源", actionNames, sorted);
    }

    // ---------- 2. 容器参数容错 ----------

    @Test
    public void jsonActionAcceptsDataSerialisedAsString() throws Exception {
        File out = new File(dir, "cfg.json");
        Map<String, Object> p = base("json", out);
        p.put("data", "{\"pump\":\"P-2101B\",\"vibration\":2.8}");   // 桥常把对象变成字符串
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertTrue("data 传 JSON 字符串应能用，实际: " + r.getErrorMessage(), r.isSuccess());
        String written = new String(Files.readAllBytes(out.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(written.contains("P-2101B"));
        assertTrue(written.contains("2.8"));
    }

    @Test
    public void copyUsesSourceAndDestination() throws Exception {
        File src = new File(dir, "src.txt");
        Files.write(src.toPath(), "内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File dst = new File(dir, "dst.txt");
        Map<String, Object> p = new HashMap<>();
        p.put("action", "copy");
        p.put("source", src.getAbsolutePath());
        p.put("destination", dst.getAbsolutePath());
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertTrue("copy 用 source/destination 应成功，实际: " + r.getErrorMessage(), r.isSuccess());
        assertTrue(dst.exists());
    }

    // ---------- 3. 缺正文不许"假成功" ----------

    @Test
    public void reportWithoutDataFailsInsteadOfWritingEmptyReport() {
        File out = new File(dir, "report.md");
        Map<String, Object> p = base("report", out);
        p.put("title", "机泵振动周报");        // 只给标题：过去会落一份只有标题+时间戳的"成功"报告
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertFalse("缺 data 不许返回成功", r.isSuccess());
        assertTrue(r.getErrorMessage().contains("data"));
        assertFalse("更不许把空报告落盘", out.exists());
    }

    @Test
    public void markdownWithoutBodyFails() {
        File out = new File(dir, "doc.md");
        Map<String, Object> p = base("markdown", out);
        p.put("title", "只有标题");
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertFalse("markdown 既无 content 又无 sections 时应报错", r.isSuccess());
        assertTrue(r.getErrorMessage().contains("sections"));
        assertFalse(out.exists());
    }

    @Test
    public void configWithoutPairsFails() {
        File out = new File(dir, "app.properties");
        Map<String, Object> p = base("config", out);
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertFalse("config 缺失不该落一个只有表头的空配置文件", r.isSuccess());
        assertTrue(r.getErrorMessage().contains("config"));
        assertFalse(out.exists());
    }

    @Test
    public void unknownActionFailsInsteadOfSilentlyCreatingFile() {
        File out = new File(dir, "surprise.txt");
        Map<String, Object> p = base("generate_report_typo", out);   // 拼错的动作名
        p.put("content", "正文");
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertFalse("未知 action 过去被 default 分支降级成 create 并返回成功", r.isSuccess());
        assertTrue("报错要列出可用动作", r.getErrorMessage().contains("create"));
        assertFalse("更不能因此写盘", out.exists());
    }

    /** 长动作名（历史别名）仍然能用——旧会话缓存里的 tool_calls 还在按老名字调 */
    @Test
    public void legacyLongActionNamesStillWork() {
        File out = new File(dir, "legacy.json");
        Map<String, Object> p = base("write_json", out);
        Map<String, Object> data = new HashMap<>();
        data.put("k", "v");
        p.put("data", data);
        AIToolResult r = new FileGeneratorTool(context).execute(p);
        assertTrue("write_json 长名要继续可用: " + r.getErrorMessage(), r.isSuccess());
    }

    // ---------- helpers ----------

    private Map<String, Object> base(String action, File target) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", action);
        p.put("file_path", target.getAbsolutePath());
        return p;
    }

    private static void assertNotNull_(boolean cond, String msg) {
        assertTrue(msg, cond);
    }
}
