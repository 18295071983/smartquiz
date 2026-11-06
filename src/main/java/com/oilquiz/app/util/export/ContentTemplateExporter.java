package com.oilquiz.app.util.export;

import android.content.Context;
import android.content.res.AssetManager;

import com.oilquiz.app.model.Question;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class ContentTemplateExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        
        // 根据选择的导出格式确定文件扩展名
        String fileExtension = getFileExtensionForFormat(task.getConfig().getFormat());
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + fileExtension);

        ExportManager.ExportFormat format = task.getConfig().getFormat();
        if (format == ExportManager.ExportFormat.EXCEL || format == ExportManager.ExportFormat.WORD
                || format == ExportManager.ExportFormat.PDF) {
            // 内容模板是"文本模板 + 占位符"这一套；Excel/Word/PDF 成品需要的是二进制文档结构，
            // 而原来这里照样走到 FileWriter 写正文、文件名却是 .xlsx/.docx/.pdf——产的是打不开的文件。
            // 与其给一个坏文件，不如现在就说不支持，并指回能用的那条路（导出模板模式）。
            throw new IOException("内容模板模式目前只支持文本类成品（CSV / HTML / Markdown / JSON）；"
                    + format.name() + " 请改用导出模板，或选文本类格式");
        }
        // 读取内容模板文件（读不到就抛，不再用内置默认模板顶替）
        String templateContent = readTemplateFile(task.getConfig().getContentTemplateFilePath(), task.getContext(), format);

        // 生成内容
        String content = generateContent(templateContent, questions, task);

        // 写入文件
        if (task.getConfig().getFormat() == ExportManager.ExportFormat.LONG_IMAGE) {
            // 对于长图片格式，需要特殊处理
            // 这里应该使用LongImageExporter来处理
            LongImageExporter imageExporter = new LongImageExporter();
            return imageExporter.export(task);
        } else {
            // 对于其他格式，使用FileWriter
            try (FileWriter writer = new FileWriter(exportFile)) {
                writer.write(content);
            }
            return exportFile;
        }
    }
    
    private String getFileExtensionForFormat(ExportManager.ExportFormat format) {
        switch (format) {
            case CSV:
                return "csv";
            case EXCEL:
                return "xlsx";
            case PDF:
                return "pdf";
            case WORD:
                return "docx";
            case HTML:
            case ENHANCED_HTML:
                return "html";
            case MARKDOWN:
                return "md";
            case JSON:
                return "json";
            case LONG_IMAGE:
                return "png";
            default:
                return "html";
        }
    }

    /**
     * 取内容模板正文：先按文件系统路径读，再退到 assets，两条都不成就<b>抛</b>。
     *
     * <p>原来只走 {@code context.getAssets().open(templateFilePath)}，而内容模板的路径是用户在
     * 模板管理里填的绝对路径（{@code EditTemplateActivity} 存进 {@code Template.filePath}）——
     * {@code assets.open("/data/user/0/...")} 必然 IOException，于是每次都落到
     * 于是过去每次都退到内置默认模板，导出的其实是默认模板，却照样报成功：
     * 用户挑的模板从头到尾没被读过。这一版把"读不到用户文件"当成失败，不再用默认模板遮。</p>
     */
    private String readTemplateFile(String templateFilePath, Context context,
                                    ExportManager.ExportFormat format) throws IOException {
        if (templateFilePath == null || templateFilePath.trim().isEmpty()) {
            throw new IOException("没有指定内容模板文件");
        }
        String fromFs = readFromFileSystem(templateFilePath.trim(), context.getFilesDir());
        if (fromFs != null) {
            return fromFs;
        }
        // assets 里那批内置模板仍可用（路径带不带 assets/ 前缀都认）
        String assetPath = templateFilePath.replace("assets/", "").replaceFirst("^/+", "");
        try (InputStream in = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException assetMiss) {
            throw new IOException("内容模板读不到：既不是可访问的文件路径，也不在 assets 里（"
                    + templateFilePath + "）—— 上次失败原因：" + assetMiss.getMessage());
        }
    }

    /**
     * 按文件系统读模板：绝对路径直接读；相对路径按应用 filesDir 兜一遍（模板管理里存的
     * 可能是相对形态）。读不到返回 null，由调用方决定是退 assets 还是报错。
     */
    /**
     * 模板里有没有"题目列表"占位符：大小写不敏感，容忍 {@code {{ questions }}} 这种带空格的写法。
     * 导出前与向导页都用它做预检。
     */
    public static boolean hasQuestionsPlaceholder(String template) {
        if (template == null) return false;
        // 用手写的字符扫描而不是正则：占位符只有 {{questions}} 一种形状，正则在这里既难读
        // 又要过两层转义（`\\{{` 在 Java 字符串里是 `\{{`，遇到 `\\s` 还会被判成文本块），
        // 出过一次错就不想再靠转义过日子。
        String s = template.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (true) {
            int open = s.indexOf("{{", from);
            if (open < 0) return false;
            int close = s.indexOf("}}", open + 2);
            if (close < 0) return false;
            if (s.substring(open + 2, close).trim().equals("questions")) return true;
            from = close + 2;
        }
    }

    /** 按文件系统读模板（向导页预检也用这条，保证判据和导出时一致） */
    public static String readFromFileSystem(String templateFilePath, File baseDir) {
        File f = new File(templateFilePath);
        if (!f.isAbsolute() && baseDir != null) {
            f = new File(baseDir, templateFilePath);
        }
        if (!f.isFile() || !f.canRead()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException readFailed) {
            // 存在但读不动（权限/被占）与"不存在"不同种，这里都交回 null，
            // 由调用方那句"读不到就失败"统一报，不再演成"用默认模板继续"。
            android.util.Log.w("ContentTemplateExporter",
                    "模板文件读失败: " + f.getAbsolutePath() + " —— " + readFailed.getMessage());
            return null;
        }
    }

    // getDefaultTemplate(...) 已删（原 153 行起，内置 Markdown/JSON/CSV/HTML 四套默认模板正文）。
    // 它以前的作用是：assets.open 一 IOException 就顶上内置模板——而内容模板路径是用户在模板管理里
    // 填的绝对路径，那次 open 注定失败，于是"选了模板"永远等于"用默认模板"，还照样报成功。
    // 现在读不到就抛。要恢复"默认模板"这一档，得让它以一个真实的模板文件出现，不再当兜底。

    /**
     * 填充：把 {@code {{questions}}} 换成按格式生成的题目块。
     *
     * <p><b>没有这个占位符就不导出</b>：内容模板唯一的作用就是往这个位置灌题，缺了它
     * 产出的是"整份模板 + 一道题都没有"的空壳——以前照样返回成功，用户拿到文件还以为
     * 模板生效了。这里直接抛，并指出缺的是哪个占位符；模板读不到是另一条错误，也已单独报。</p>
     */
    private String generateContent(String template, List<Question> questions,
                                   ExportManager.ExportTask task) throws java.io.IOException {
        if (!hasQuestionsPlaceholder(template)) {
            throw new java.io.IOException("内容模板里没有 {{questions}} 占位符，导出得到的是没有题目的空壳；"
                    + "请在模板里加 {{questions}}（另可用 {{questionCount}} / {{exportTime}}）");
        }
        if (questions == null || questions.isEmpty()) {
            throw new java.io.IOException("没有可导出的题目（内容模板至少要有一道题才有意义）");
        }
        // 替换模板中的占位符
        String content = template;

        // 替换题目数量
        content = content.replace("{{questionCount}}", String.valueOf(questions.size()));

        // 替换导出时间
        String exportTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        content = content.replace("{{exportTime}}", exportTime);

        // 生成题目列表
        StringBuilder questionsContent = new StringBuilder();
        int questionNumber = 1;
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            questionsContent.append(generateQuestionContent(question, questionNumber++, task.getConfig().getFormat()));

            // 更新进度
            if (task.getCallback() != null && i % 10 == 0) {
                int progress = (int) ((i + 1) * 100.0 / questions.size());
                task.getCallback().onExportProgress(progress);
            }
        }

        // 替换题目列表
        content = content.replace("{{questions}}", questionsContent.toString());

        return content;
    }

    private String generateQuestionContent(Question question, int questionNumber, ExportManager.ExportFormat format) {
        switch (format) {
            case MARKDOWN:
                return generateQuestionMarkdown(question, questionNumber);
            case JSON:
                return generateQuestionJson(question, questionNumber);
            case CSV:
                return generateQuestionCsv(question, questionNumber);
            default:
                // 默认生成HTML格式
                return generateQuestionHtml(question, questionNumber);
        }
    }

    private String generateQuestionHtml(Question question, int questionNumber) {
        StringBuilder html = new StringBuilder();

        html.append("<div class=\"question\">\n");
        html.append("<h3>第").append(questionNumber).append("题")
                .append(question.getQuestionType() != null ? " (" + question.getQuestionType() + ")" : "").append("</h3>\n");

        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            html.append("<p class=\"question-text\">").append(question.getQuestionText()).append("</p>\n");
        }

        if (question.hasOptions()) {
            html.append("<div class=\"options\">\n");
            if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                html.append("<p>A. " + question.getOptionA() + "</p>\n");
            }
            if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                html.append("<p>B. " + question.getOptionB() + "</p>\n");
            }
            if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                html.append("<p>C. " + question.getOptionC() + "</p>\n");
            }
            if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                html.append("<p>D. " + question.getOptionD() + "</p>\n");
            }
            html.append("</div>\n");
        }

        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            html.append("<p class=\"correct-answer\">正确答案: " + question.getCorrectAnswer() + "</p>\n");
        }

        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            html.append("<p class=\"explanation\">解析: " + question.getExplanation() + "</p>\n");
        }

        html.append("</div>\n\n");

        return html.toString();
    }

    private String generateQuestionMarkdown(Question question, int questionNumber) {
        StringBuilder markdown = new StringBuilder();

        markdown.append("### 第 " + questionNumber + " 题\n\n");

        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            markdown.append("**题目内容:** " + question.getQuestionText() + "\n\n");
        }

        if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
            markdown.append("**题目类型:** " + question.getQuestionType() + "\n\n");
        }

        if (question.hasOptions()) {
            markdown.append("**选项:**\n\n");
            if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                markdown.append("- A. " + question.getOptionA() + "\n");
            }
            if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                markdown.append("- B. " + question.getOptionB() + "\n");
            }
            if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                markdown.append("- C. " + question.getOptionC() + "\n");
            }
            if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                markdown.append("- D. " + question.getOptionD() + "\n");
            }
            markdown.append("\n");
        }

        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            markdown.append("**正确答案:** " + question.getCorrectAnswer() + "\n\n");
        }

        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            markdown.append("**解析:**\n\n" + question.getExplanation() + "\n\n");
        }

        markdown.append("---\n\n");

        return markdown.toString();
    }

    private String generateQuestionJson(Question question, int questionNumber) {
        StringBuilder json = new StringBuilder();

        json.append("    {\n");
        json.append("      \"questionNumber\": " + questionNumber + ",\n");
        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            json.append("      \"questionText\": \"" + question.getQuestionText().replace("\"", "\\\"") + "\",\n");
        }
        if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
            json.append("      \"questionType\": \"" + question.getQuestionType() + "\",\n");
        }
        if (question.hasOptions()) {
            json.append("      \"options\": {\n");
            if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                json.append("        \"A\": \"" + question.getOptionA().replace("\"", "\\\"") + "\",\n");
            }
            if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                json.append("        \"B\": \"" + question.getOptionB().replace("\"", "\\\"") + "\",\n");
            }
            if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                json.append("        \"C\": \"" + question.getOptionC().replace("\"", "\\\"") + "\",\n");
            }
            if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                json.append("        \"D\": \"" + question.getOptionD().replace("\"", "\\\"") + "\"\n");
            }
            json.append("      },\n");
        }
        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            json.append("      \"correctAnswer\": \"" + question.getCorrectAnswer() + "\",\n");
        }
        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            json.append("      \"explanation\": \"" + question.getExplanation().replace("\"", "\\\"") + "\"\n");
        }
        json.append("    },\n");

        return json.toString();
    }

    private String generateQuestionCsv(Question question, int questionNumber) {
        StringBuilder csv = new StringBuilder();

        // 题目编号
        csv.append(questionNumber).append(",");

        // 题目类型
        if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
            csv.append("\"").append(question.getQuestionType().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 题目内容
        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            csv.append("\"").append(question.getQuestionText().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项A
        if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
            csv.append("\"").append(question.getOptionA().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项B
        if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
            csv.append("\"").append(question.getOptionB().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项C
        if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
            csv.append("\"").append(question.getOptionC().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项D
        if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
            csv.append("\"").append(question.getOptionD().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 正确答案
        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            csv.append("\"").append(question.getCorrectAnswer().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 解析
        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            csv.append("\"").append(question.getExplanation().replace("\"", "\"\"").replace("\n", " ")).append("\"");
        }

        csv.append("\n");

        return csv.toString();
    }

    @Override
    public String getFormatName() {
        return "Content Template";
    }

    @Override
    public String getFileExtension() {
        return "html";
    }

    @Override
    public void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException {
        if (task == null) {
            throw new IllegalArgumentException("导出任务不能为空");
        }

        if (task.getConfig() == null) {
            throw new IllegalArgumentException("导出配置不能为空");
        }

        if (task.getQuestions() == null || task.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("没有问题可导出");
        }

        if (!task.getConfig().isContentTemplateMode()) {
            throw new IllegalArgumentException("此导出器仅用于内容模板");
        }

        if (task.getConfig().getContentTemplateFilePath() == null || task.getConfig().getContentTemplateFilePath().isEmpty()) {
            throw new IllegalArgumentException("内容模板文件路径不能为空");
        }
    }
}
