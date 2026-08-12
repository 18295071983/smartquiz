package com.oilquiz.app;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.HTMLExporter;
import com.oilquiz.app.util.export.JSONExporter;
import com.oilquiz.app.util.export.MarkdownExporter;
import com.oilquiz.app.util.export.template.Template;
import com.oilquiz.app.util.export.template.TemplateManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 导出模板与导出器全链路验证：
 * 1. TemplateManager 场景模板齐全（8 个）
 * 2. HTML 导出走场景模板字段（修复 ExportManager 回退 Bug）
 * 3. 选项 A~L 循环渲染
 * 4. 难度文字化（简单/中等/困难/未设置）
 * 5. Markdown UTF-8 BOM
 * 6. JSON 字段保序
 */
@RunWith(AndroidJUnit4.class)
public class ExportTemplateTest {

    private Context context;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
    }

    private Question buildQuestion(long id, String text) {
        Question q = new Question();
        q.setId(id);
        q.setQuestionText(text);
        q.setQuestionType("单选题");
        q.setCategory("测试分类");
        q.setDifficulty(Question.DIFFICULTY_MEDIUM);
        q.setKnowledgePoint("知识点X");
        q.setOptionA("选项A");
        q.setOptionB("选项B");
        q.setOptionC("选项C");
        q.setOptionD("选项D");
        q.setOptionE("选项E");
        q.setOptionF("选项F");
        q.setOptionG("选项G");
        q.setCorrectAnswer("E");
        q.setAnswerText("答案：选项E");
        q.setExplanation("解析：选E是因为…");
        q.setAnalysis("详细解析：更进一步说明");
        q.setTags("标签1,标签2");
        q.setHint("提示：看首字");
        q.setSource("测试来源");
        q.setIncorrectCount(2);
        return q;
    }

    private List<Question> buildQuestions() {
        List<Question> list = new ArrayList<>();
        list.add(buildQuestion(1, "题目一：<b>加粗</b> & 特殊\"字符\""));
        list.add(buildQuestion(2, "题目二：第二道题"));
        return list;
    }

    private ExportManager.ExportConfig buildConfig(ExportManager.ExportFormat format, Template template) {
        ExportManager.ExportConfig config = new ExportManager.ExportConfig();
        config.format = format;
        config.fileName = "测试导出_" + System.currentTimeMillis();
        if (template != null) {
            config.templateId = template.getId();
            config.selectedFields = new ArrayList<>(template.getFields());
        }
        return config;
    }

    // ========== 1. 模板体系 ==========

    @Test
    public void testTemplateManagerHasAllScenes() {
        TemplateManager.getInstance().init(context);
        List<Template> templates = TemplateManager.getInstance().getDefaultTemplates();
        List<String> ids = new ArrayList<>();
        for (Template t : templates) {
            ids.add(t.getId());
        }
        assertTrue("标准完整版缺失", ids.contains("scene_standard"));
        assertTrue("刷题训练版缺失", ids.contains("scene_practice"));
        assertTrue("答案解析版缺失", ids.contains("scene_answer"));
        assertTrue("讲义备课版缺失", ids.contains("scene_teaching"));
        assertTrue("记忆卡片版缺失", ids.contains("scene_memory"));
        assertTrue("数据分析版缺失", ids.contains("scene_data"));
        assertTrue("错题本版缺失", ids.contains("scene_mistake"));
        assertTrue("试卷版缺失", ids.contains("scene_exam"));
        assertEquals("场景模板应为 8 个，实际 " + ids, 8, ids.size());
    }

    @Test
    public void testSceneMistakeTemplateContent() {
        TemplateManager.getInstance().init(context);
        Template t = TemplateManager.getInstance().getTemplateById("scene_mistake");
        assertNotNull("错题本版模板不存在", t);
        assertTrue("错题本版应含 answerText 字段", t.getFields().contains("answerText"));
        assertTrue("错题本版应含 incorrectCount 字段", t.getFields().contains("incorrectCount"));
        assertFalse("错题本版不应含不存在的 userAnswer 字段", t.getFields().contains("userAnswer"));
        assertTrue("错题本版应开启答案解析", Boolean.TRUE.equals(t.getConfig().get("includeAnswers"))
                && Boolean.TRUE.equals(t.getConfig().get("includeExplanations")));
    }

    // ========== 2. ExportManager 全链路（HTML + scene 模板） ==========

    @Test
    public void testExportManagerHtmlWithSceneTemplate() throws Exception {
        TemplateManager.getInstance().init(context);
        Template template = TemplateManager.getInstance().getTemplateById("scene_mistake");
        assertNotNull("错题本版模板不存在", template);

        ExportManager.ExportConfig config = buildConfig(ExportManager.ExportFormat.HTML, template);
        config.includeAnswers = true;
        config.includeExplanations = true;

        ExportManager.ExportTask task = new ExportManager.ExportTask();
        task.setContext(context);
        task.setConfig(config);
        task.setQuestions(buildQuestions());

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<File> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        task.setCallback(new ExportManager.ExportCallback() {
            @Override
            public void onExportStart() {
            }

            @Override
            public void onExportProgress(int progress) {
            }

            @Override
            public void onExportComplete(File file) {
                result.set(file);
                latch.countDown();
            }

            @Override
            public void onExportError(String message) {
                error.set(message);
                latch.countDown();
            }
        });

        ExportManager.getInstance().init(context);
        ExportManager.getInstance().startExport(task);
        assertTrue("导出超时", latch.await(30, TimeUnit.SECONDS));
        assertNull("导出失败: " + error.get(), error.get());
        assertNotNull("导出未生成文件", result.get());

        String content = readUtf8(result.get());
        // 场景模板字段生效（而非回退旧讲义模板）
        assertTrue("HTML 应渲染答案文本", content.contains("答案：选项E"));
        assertTrue("HTML 应渲染答错次数", content.contains("答错次数") || content.contains("2"));
        // 选项 A~L 循环
        assertTrue("HTML 应渲染选项E", content.contains("E. 选项E"));
        assertTrue("HTML 应渲染选项F", content.contains("F. 选项F"));
        assertTrue("HTML 应渲染选项G", content.contains("G. 选项G"));
        // 难度文字化
        assertTrue("HTML 难度应为文字", content.contains("中等"));
        // 详细解析
        assertTrue("HTML 应渲染详细解析", content.contains("详细解析：更进一步说明"));
        // 题目内容 HTML 转义（<b> 不应破坏结构）
        assertTrue("HTML 应转义题目内容", content.contains("&lt;b&gt;"));
    }

    @Test
    public void testExportManagerCsvWithSceneMistake() throws Exception {
        TemplateManager.getInstance().init(context);
        Template template = TemplateManager.getInstance().getTemplateById("scene_mistake");
        assertNotNull(template);

        ExportManager.ExportConfig config = buildConfig(ExportManager.ExportFormat.CSV, template);
        config.includeAnswers = true;
        config.includeExplanations = true;

        ExportManager.ExportTask task = new ExportManager.ExportTask();
        task.setContext(context);
        task.setConfig(config);
        task.setQuestions(buildQuestions());

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<File> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        task.setCallback(new ExportManager.ExportCallback() {
            @Override
            public void onExportStart() {
            }

            @Override
            public void onExportProgress(int progress) {
            }

            @Override
            public void onExportComplete(File file) {
                result.set(file);
                latch.countDown();
            }

            @Override
            public void onExportError(String message) {
                error.set(message);
                latch.countDown();
            }
        });

        ExportManager.getInstance().init(context);
        ExportManager.getInstance().startExport(task);
        assertTrue("导出超时", latch.await(30, TimeUnit.SECONDS));
        assertNull("导出失败: " + error.get(), error.get());
        assertNotNull(result.get());

        String content = readUtf8(result.get());
        assertTrue("CSV 应含 answerText 列头", content.contains("answerText") || content.contains("答案文本"));
        assertTrue("CSV 应含正确答案", content.contains("E"));
        assertTrue("CSV 应含选项E", content.contains("选项E"));
    }

    // ========== 3. 各导出器细节 ==========

    @Test
    public void testMarkdownUtf8BomAndOptions() throws Exception {
        ExportManager.ExportConfig config = buildConfig(ExportManager.ExportFormat.MARKDOWN, null);
        config.includeAnswers = true;
        config.includeExplanations = true;

        ExportManager.ExportTask task = new ExportManager.ExportTask();
        task.setContext(context);
        task.setConfig(config);
        task.setQuestions(buildQuestions());

        File file = new MarkdownExporter().export(task);
        assertTrue("Markdown 文件不存在", file.exists());

        // UTF-8 BOM 校验
        try (FileInputStream fis = new FileInputStream(file)) {
            int b0 = fis.read();
            int b1 = fis.read();
            int b2 = fis.read();
            assertEquals("Markdown 应带 UTF-8 BOM", 0xEF, b0);
            assertEquals(0xBB, b1);
            assertEquals(0xBF, b2);
        }

        String content = readUtf8(file);
        assertTrue("Markdown 应渲染选项E", content.contains("- E. 选项E"));
        assertTrue("Markdown 应渲染选项G", content.contains("- G. 选项G"));
        assertTrue("Markdown 难度应为文字", content.contains("中等"));
        assertTrue("Markdown 应渲染答案文本", content.contains("答案：选项E"));
        assertTrue("Markdown 应渲染详细解析", content.contains("详细解析：更进一步说明"));
    }

    @Test
    public void testJsonFieldOrderAndValues() throws Exception {
        ExportManager.ExportConfig config = buildConfig(ExportManager.ExportFormat.JSON, null);
        config.includeAnswers = true;
        config.includeExplanations = true;

        ExportManager.ExportTask task = new ExportManager.ExportTask();
        task.setContext(context);
        task.setConfig(config);
        task.setQuestions(buildQuestions());

        File file = new JSONExporter().export(task);
        String content = readUtf8(file);
        // 字段保序：questionText 在 optionA 之前
        int idxQuestion = content.indexOf("题目一");
        int idxOptionA = content.indexOf("选项A");
        assertTrue("JSON 应包含题目", idxQuestion >= 0);
        assertTrue("JSON 应包含选项A", idxOptionA >= 0);
        assertTrue("JSON 字段应保序（题干在选项前）", idxQuestion < idxOptionA);
        // 选项 E~L 与难度文字
        assertTrue("JSON 应包含选项E", content.contains("选项E"));
        assertTrue("JSON 难度应为文字", content.contains("中等"));
    }

    // ========== 工具 ==========

    private String readUtf8(File file) throws Exception {
        return new String(readAll(file), StandardCharsets.UTF_8);
    }

    private byte[] readAll(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return buf;
        }
    }
}
