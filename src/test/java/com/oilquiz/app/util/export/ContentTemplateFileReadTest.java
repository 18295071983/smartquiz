package com.oilquiz.app.util.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内容模板文件的读取（{@link ContentTemplateExporter#readFromFileSystem}）。
 *
 * <p>这条路的旧形状是：只从 {@code assets} 读，读不到就悄悄换成内置默认模板。而用户在模板管理里
 * 存的是绝对路径，{@code assets.open("/data/...")} 必然失败——于是"选了内容模板"和"用了内置模板"
 * 在结果上完全一样，还都报成功。这里钉的就是修好之后的三件事：</p>
 *
 * <ol>
 *   <li>绝对路径的模板文件真被读出来（含中文按 UTF-8 解，不是逐行拼坏）；</li>
 *   <li>相对路径按 filesDir 兜（模板管理里存过相对形态的旧数据）；</li>
 *   <li>不存在 / 是目录 / 读不动 → 返回 null，由调用方决定报错，<b>不再返回默认模板顶替</b>。</li>
 * </ol>
 */
public class ContentTemplateFileReadTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File write(String name, String content) throws Exception {
        File f = new File(folder.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    @Test
    public void readsAbsoluteTemplateFile() throws Exception {
        File tpl = write("quiz.md", "# 题库 {{questionCount}} 道\n\n{{questions}}\n");

        String content = ContentTemplateExporter.readFromFileSystem(tpl.getAbsolutePath(), null);

        assertNotNull("绝对路径的模板必须读得到", content);
        assertTrue(content.contains("{{questionCount}}"));
        assertTrue("结尾按行补 \\n", content.endsWith("\n"));
    }

    @Test
    public void keepsChinesePlaceholdersIntact() throws Exception {
        File tpl = write("quiz.txt", "题目：{{questionText}}　答案：{{correctAnswer}}");

        String content = ContentTemplateExporter.readFromFileSystem(tpl.getAbsolutePath(), null);

        assertNotNull(content);
        // 旧实现用平台默认编码逐行读；中文模板在 GBK 环境的桌面上会解成乱码，这里钉住 UTF-8
        assertTrue(content.contains("题目：{{questionText}}"));
        assertTrue(content.contains("答案：{{correctAnswer}}"));
    }

    @Test
    public void relativePathResolvesAgainstFilesDir() throws Exception {
        // baseDir 是 filesDir 那一层，模板存的是相对它的 templates/quiz.html
        File sub = folder.newFolder("templates");
        File tpl = new File(sub, "quiz.html");
        try (FileOutputStream out = new FileOutputStream(tpl)) {
            out.write("<p>{{questions}}</p>".getBytes(StandardCharsets.UTF_8));
        }
        File base = folder.getRoot();

        String content = ContentTemplateExporter.readFromFileSystem("templates/quiz.html", base);

        assertNotNull("相对路径要按 baseDir 兜住（模板管理里存过相对形态）", content);
        assertEquals("<p>{{questions}}</p>\n", content);
        assertTrue(tpl.isFile());
    }

    @Test
    public void missingFileYieldsNullInsteadOfSilentDefault() {
        String content = ContentTemplateExporter.readFromFileSystem(
                new File(folder.getRoot(), "nope.md").getAbsolutePath(), folder.getRoot());

        assertNull("读不到就是 null——让调用方报错，不许悄悄换成内置默认模板", content);
    }

    @Test
    public void directoryIsNotATemplate() throws Exception {
        File dir = folder.newFolder("templates");

        assertNull(ContentTemplateExporter.readFromFileSystem(dir.getAbsolutePath(), folder.getRoot()));
    }

    @Test
    public void emptyPathIsHandledByCaller() throws Exception {
        File tpl = write("blank.md", "");

        String content = ContentTemplateExporter.readFromFileSystem(tpl.getAbsolutePath(), null);

        // 空文件是"读到了但没内容"，与"读不到"必须可区分：这里返回空串而不是 null
        assertEquals("", content);
    }

    /**
     * 缺 {{questions}} 的模板要能被判出来——导出侧靠它直接失败，而不是产出一份"没有题目的空壳"
     * 还报成功。
     */
    @Test
    public void detectsQuestionsPlaceholder() {
        assertTrue(ContentTemplateExporter.hasQuestionsPlaceholder("# 题库\n\n{{questions}}\n"));
        // 容忍空格与大小写：用户手写模板时这两种写法都很常见
        assertTrue(ContentTemplateExporter.hasQuestionsPlaceholder("{{ Questions }}"));
        assertTrue(ContentTemplateExporter.hasQuestionsPlaceholder("{{questions}}和{{questionCount}}"));
    }

    @Test
    public void missingPlaceholderIsRejected() {
        assertTrue(!ContentTemplateExporter.hasQuestionsPlaceholder("# 题库\n\n{{questionCount}} 道\n"));
        // 只是"看起来像"：没有闭合、或拼错的键名都不算
        assertTrue(!ContentTemplateExporter.hasQuestionsPlaceholder("{{questions"));
        assertTrue(!ContentTemplateExporter.hasQuestionsPlaceholder("{{questionList}}"));
        assertTrue(!ContentTemplateExporter.hasQuestionsPlaceholder(null));
    }
}
