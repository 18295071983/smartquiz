package com.oilquiz.app.util.export;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.model.Question;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 导出（2026-10-24 从 iText 7 迁移到 PDFBox）。
 *
 * <p><b>为什么换</b>：iText 7 Core 是 <b>AGPL-3.0</b>——对外分发就要求整个 App 按 AGPL 开源，
 * 否则得买商业许可。PDFBox-Android 是 Apache-2.0，能力等价（文字 + 中文字体嵌入），
 * 不需要为"导出 PDF"这一个功能背上 AGPL。</p>
 *
 * <p>字体：优先用 assets 里随包的 <b>Noto Sans SC</b>（OFL-1.1，可自由分发/嵌入）；
 * 缺失时退回系统 CJK 字体（DroidSansFallback）；再没有就明确报错，而不是导出一堆方框。
 * 原来的 simhei.ttf 是专有字体（中易），已从包里移除。</p>
 */
public class PDFExporter implements Exporter {

    private static final String TAG = "PDFExporter";

    /** 随包 OFL 中文字体（见 BINARY_ASSETS.md；字体文件不进 Git，构建前需就位） */
    private static final String FONT_ASSET = "fonts/NotoSansSC-Regular.ttf";

    /** 内置字体缺失时的系统兜底（都是 TrueType；PDFBox 2.x 不能读 .ttc/.otf-CFF） */
    private static final String[] SYSTEM_FONT_FALLBACKS = {
            "/system/fonts/DroidSansFallbackFull.ttf",
            "/system/fonts/DroidSansFallback.ttf",
    };

    private static final float MARGIN = 36f;
    private static final int ALIGN_LEFT = 0;
    private static final int ALIGN_CENTER = 1;
    private static final int ALIGN_RIGHT = 2;

    private static volatile boolean pdfBoxInited = false;

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        java.util.List<Question> questions = task.getQuestions();
        // 模板生效字段列表（场景模板/自定义字段），决定导出内容
        java.util.List<String> fields = ExportUtils.getEffectiveFields(task.getConfig());
        boolean includeAnswers = task.getConfig().isIncludeAnswers();
        boolean includeExplanations = task.getConfig().isIncludeExplanations();

        // 按题型分组并排序
        java.util.Map<String, java.util.List<Question>> questionsByType = new java.util.HashMap<>();
        for (Question question : questions) {
            String type = question.getQuestionType() != null ? question.getQuestionType() : "未分类";
            if (!questionsByType.containsKey(type)) {
                questionsByType.put(type, new java.util.ArrayList<>());
            }
            questionsByType.get(type).add(question);
        }
        java.util.List<java.util.Map.Entry<String, java.util.List<Question>>> sortedTypes =
                new java.util.ArrayList<>(questionsByType.entrySet());
        sortedTypes.sort((a, b) -> a.getKey().compareTo(b.getKey()));

        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            fileName = "导出题目_" + sdf.format(new java.util.Date());
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()),
                fileName + "." + getFileExtension());

        PDDocument document = new PDDocument();
        try {
            PDFont font = loadFont(document, task.getContext());
            Layout layout = new Layout(document, font);

            // 标题
            layout.text("导出题目", 20, ALIGN_CENTER, 0);
            layout.gap(12);
            layout.text("导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date()), 10, ALIGN_RIGHT, 0);
            layout.text("导出题目总数: " + questions.size(), 10, ALIGN_RIGHT, 0);
            layout.gap(12);

            int total = questions.size();
            int processed = 0;
            int typeIndex = 1;
            for (java.util.Map.Entry<String, java.util.List<Question>> entry : sortedTypes) {
                String type = entry.getKey();
                java.util.List<Question> typeQuestions = entry.getValue();

                // 题型标题
                layout.gap(6);
                layout.text(typeIndex + "、" + type + " (" + typeQuestions.size() + "题)",
                        16, ALIGN_LEFT, 0);
                layout.gap(6);
                typeIndex++;

                int typeQuestionNumber = 1;
                for (Question question : typeQuestions) {
                    // 问题标题
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                        layout.text("第" + typeQuestionNumber++ + "题: " + question.getQuestionText(),
                                12, ALIGN_LEFT, 0);
                    } else {
                        typeQuestionNumber++;
                    }

                    // 题目元信息行（题型/难度/分类/子分类/知识点/分值等，按模板字段）
                    StringBuilder metaInfo = new StringBuilder();
                    if (ExportUtils.hasField(fields, "questionType") && question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
                        metaInfo.append("题型: ").append(question.getQuestionType()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "difficulty")) {
                        String diffText = question.getDifficultyText();
                        metaInfo.append("难度: ").append(diffText != null ? diffText : "未知").append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "category") && question.getCategory() != null && !question.getCategory().isEmpty()) {
                        metaInfo.append("分类: ").append(question.getCategory()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "subCategory") && question.getSubCategory() != null && !question.getSubCategory().isEmpty()) {
                        metaInfo.append("子分类: ").append(question.getSubCategory()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "knowledgePoint") && question.getKnowledgePoint() != null && !question.getKnowledgePoint().isEmpty()) {
                        metaInfo.append("知识点: ").append(question.getKnowledgePoint()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "points")) {
                        metaInfo.append("分值: ").append(question.getPoints()).append(" | ");
                    }
                    if (metaInfo.length() > 0) {
                        layout.text(metaInfo.substring(0, metaInfo.length() - 3), 9, ALIGN_LEFT, 136);
                    }

                    // 选项（A~L 动态渲染，只输出模板选中且非空的选项）
                    if (question.hasOptions()) {
                        for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                            if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                            Object optionValue = ExportUtils.getOptionValue(question, o);
                            if (optionValue == null || optionValue.toString().isEmpty()) continue;
                            layout.text(ExportUtils.OPTION_LABELS[o] + ". " + optionValue,
                                    12, ALIGN_LEFT, 0);
                        }
                    }

                    // 根据配置决定是否包含答案（正确答案 + 答案文本）
                    if (includeAnswers) {
                        if (ExportUtils.hasField(fields, "correctAnswer") && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                            layout.text("正确答案: " + question.getCorrectAnswer(), 12, ALIGN_LEFT, 0);
                        }
                        if (ExportUtils.hasField(fields, "answerText") && question.getAnswerText() != null && !question.getAnswerText().isEmpty()) {
                            layout.text("答案文本: " + question.getAnswerText(), 12, ALIGN_LEFT, 0);
                        }
                    }

                    // 根据配置决定是否包含解析（解析 + 详细解析）
                    if (includeExplanations && ExportUtils.hasField(fields, "explanation")
                            && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                        layout.text("解析: " + question.getExplanation(), 12, ALIGN_LEFT, 0);
                    }
                    if (includeExplanations && ExportUtils.hasField(fields, "analysis")
                            && question.getAnalysis() != null && !question.getAnalysis().isEmpty()) {
                        layout.text("详细解析: " + question.getAnalysis(), 12, ALIGN_LEFT, 0);
                    }

                    // 其他元信息（标签/提示/来源/作者/备注/相关题目）
                    StringBuilder extraInfo = new StringBuilder();
                    if (ExportUtils.hasField(fields, "tags") && question.getTags() != null && !question.getTags().isEmpty()) {
                        extraInfo.append("标签: ").append(question.getTags()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "hint") && question.getHint() != null && !question.getHint().isEmpty()) {
                        extraInfo.append("提示: ").append(question.getHint()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "source") && question.getSource() != null && !question.getSource().isEmpty()) {
                        extraInfo.append("来源: ").append(question.getSource()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "author") && question.getAuthor() != null && !question.getAuthor().isEmpty()) {
                        extraInfo.append("作者: ").append(question.getAuthor()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "comment") && question.getComment() != null && !question.getComment().isEmpty()) {
                        extraInfo.append("备注: ").append(question.getComment()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "relatedQuestion")
                            && question.getRelatedQuestion() != null && !question.getRelatedQuestion().isEmpty()) {
                        extraInfo.append("相关题目: ").append(question.getRelatedQuestion()).append("  ");
                    }
                    if (extraInfo.length() > 0) {
                        layout.text(extraInfo.toString().trim(), 9, ALIGN_LEFT, 102);
                    }

                    layout.gap(6);

                    // 更新进度
                    processed++;
                    if (task.getCallback() != null && processed % 10 == 0) {
                        int progress = (int) (processed * 100.0 / total);
                        task.getCallback().onExportProgress(progress);
                    }
                }
            }

            layout.close();
            document.save(exportFile);
        } finally {
            try {
                document.close();
            } catch (Exception ignored) {
            }
        }

        if (task.getCallback() != null) {
            task.getCallback().onExportProgress(100);
        }
        return exportFile;
    }

    // ==================== 字体 ====================

    private static void initPdfBox(Context context) {
        if (pdfBoxInited) return;
        synchronized (PDFExporter.class) {
            if (pdfBoxInited) return;
            try {
                PDFBoxResourceLoader.init(context.getApplicationContext());
                pdfBoxInited = true;
            } catch (Throwable t) {
                Log.w(TAG, "PDFBoxResourceLoader 初始化失败: " + t);
            }
        }
    }

    /**
     * 加载中文字体：assets 里的 Noto Sans SC → 系统 CJK 兜底 → 明确报错。
     * 不再像旧实现那样"字体缺失就用默认字体"——那样导出来的中文全是空框，比报错更难查。
     */
    private static PDFont loadFont(PDDocument document, Context context) throws IOException {
        initPdfBox(context);
        if (context != null) {
            try (InputStream in = context.getAssets().open(FONT_ASSET)) {
                return PDType0Font.load(document, in, true);
            } catch (Exception e) {
                Log.w(TAG, "内置字体不可用(" + FONT_ASSET + ")，尝试系统字体: " + e.getMessage());
            }
        }
        for (String path : SYSTEM_FONT_FALLBACKS) {
            File f = new File(path);
            if (!f.isFile()) continue;
            try {
                return PDType0Font.load(document, f);
            } catch (Exception e) {
                Log.w(TAG, "系统字体不可用(" + path + "): " + e.getMessage());
            }
        }
        throw new IOException("缺少中文字体：请确认 assets/" + FONT_ASSET
                + " 已打包（获取方式见 BINARY_ASSETS.md）");
    }

    // ==================== 版式 ====================

    /** 极简版式：逐行写文本、按宽度自动换行、到底自动翻页。 */
    private static final class Layout implements AutoCloseable {
        private final PDDocument document;
        private final PDFont font;
        private final float pageWidth = PDRectangle.A4.getWidth();
        private final float pageHeight = PDRectangle.A4.getHeight();
        private PDPageContentStream stream;
        private float y;

        Layout(PDDocument document, PDFont font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = pageHeight - MARGIN;
        }

        void gap(float height) {
            y -= height;
        }

        void text(String text, float size, int align, int gray) throws IOException {
            String safe = sanitize(text);
            if (safe.isEmpty()) return;
            float lineHeight = size * 1.45f;
            for (String line : wrap(safe, size)) {
                if (y - lineHeight < MARGIN) newPage();
                float w = widthOf(line, size);
                float x = MARGIN;
                if (align == ALIGN_CENTER) {
                    x = Math.max(MARGIN, (pageWidth - w) / 2f);
                } else if (align == ALIGN_RIGHT) {
                    x = pageWidth - MARGIN - w;
                }
                if (x < MARGIN) x = MARGIN;
                stream.beginText();
                stream.setFont(font, size);
                stream.setNonStrokingColor(gray, gray, gray);
                stream.newLineAtOffset(x, y - size);
                stream.showText(line);
                stream.endText();
                y -= lineHeight;
            }
        }

        private float widthOf(String s, float size) throws IOException {
            return font.getStringWidth(s) / 1000f * size;
        }

        /** 按可用宽度贪心断行（中文按字断，英文单词会被拆开，但题库场景足够） */
        private List<String> wrap(String text, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            float maxWidth = pageWidth - 2 * MARGIN;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lines.add(line.toString());
                    line.setLength(0);
                    continue;
                }
                line.append(c);
                if (widthOf(line.toString(), size) > maxWidth && line.length() > 1) {
                    line.setLength(line.length() - 1);
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(c);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }

    /** 去掉字体里必然没有的字符（emoji/控制符），避免 showText 抛异常把整篇导出打断 */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c)) continue;
            if (c < 0x20 && c != '\n' && c != '\t') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String getFormatName() {
        return "PDF";
    }

    @Override
    public String getFileExtension() {
        return "pdf";
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
    }
}
