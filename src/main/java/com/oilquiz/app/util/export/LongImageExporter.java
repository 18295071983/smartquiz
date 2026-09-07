package com.oilquiz.app.util.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * 长图片导出器（现代卡片式设计）
 * 导出为带卡片样式的长图，美化排版、清晰层次
 */
public class LongImageExporter implements Exporter {

    // 颜色常量
    private static final int BG_COLOR = ThemeColors.get(R.color.hc_fff8fafc); // 浅灰背景
    private static final int CARD_BG = ThemeColors.get(R.color.hc_ffffffff); // 卡片白底
    private static final int CARD_BORDER = ThemeColors.get(R.color.hc_ffe2e8f0); // 卡片边框
    private static final int TITLE_COLOR = ThemeColors.get(R.color.hc_ff1e293b); // 深蓝标题
    private static final int TEXT_COLOR = ThemeColors.get(R.color.hc_ff334155); // 正文灰
    private static final int TYPE_COLOR = ThemeColors.get(R.color.hc_ff6366f1); // 题型紫色
    private static final int ANSWER_COLOR = ThemeColors.get(R.color.hc_ff10b981); // 答案绿色
    private static final int EXPLANATION_COLOR = ThemeColors.get(R.color.hc_ff0ea5e9); // 解析蓝色
    private static final int FOOTER_COLOR = ThemeColors.get(R.color.hc_ff94a3b8); // 页脚浅灰

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + ".png");

        // 生成长图片
        Bitmap bitmap = generateLongImage(questions, task);

        // 保存图片到文件
        try (FileOutputStream fos = new FileOutputStream(exportFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }

        return exportFile;
    }

    private Bitmap generateLongImage(List<Question> questions, ExportManager.ExportTask task) {
        Context context = task.getContext();
        ExportManager.ExportConfig config = task.getConfig();

        // 尺寸参数
        int imageWidth = 1080; // 高清宽度
        int cardRadius = 24; // 圆角
        int cardPadding = 36; // 卡片内边距
        int cardMargin = 24; // 卡片外边距
        int cardSpacing = 20; // 卡片间距

        // 计算图片高度（与绘制共用同一含答案/解析的高度口径，避免内容截断重叠）
        int imageHeight = calculateImageHeight(questions, task, imageWidth, cardPadding, cardMargin, cardSpacing);

        // 创建 bitmap
        Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 绘制背景
        canvas.drawColor(BG_COLOR);

        // 画笔配置
        TextPaint titlePaint = new TextPaint();
        titlePaint.setColor(TITLE_COLOR);
        titlePaint.setTextSize(40);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titlePaint.setAntiAlias(true);

        TextPaint typePaint = new TextPaint();
        typePaint.setColor(TYPE_COLOR);
        typePaint.setTextSize(24);
        typePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        typePaint.setAntiAlias(true);

        TextPaint textPaint = new TextPaint();
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(30);
        textPaint.setAntiAlias(true);
        textPaint.setLetterSpacing(0.05f);

        TextPaint answerPaint = new TextPaint();
        answerPaint.setColor(ANSWER_COLOR);
        answerPaint.setTextSize(28);
        answerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        answerPaint.setAntiAlias(true);

        TextPaint explanationPaint = new TextPaint();
        explanationPaint.setColor(EXPLANATION_COLOR);
        explanationPaint.setTextSize(26);
        explanationPaint.setAntiAlias(true);

        Paint linePaint = new Paint();
        linePaint.setColor(CARD_BORDER);
        linePaint.setStrokeWidth(2);

        int y = cardMargin + 60; // 顶部留白

        // 绘制标题
        StaticLayout titleLayout = new StaticLayout("题目导出", titlePaint, imageWidth - 2 * cardMargin, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(cardMargin, y);
        titleLayout.draw(canvas);
        canvas.restore();
        y += titleLayout.getHeight() + 20;

        // 绘制导出信息
        TextPaint infoPaint = new TextPaint();
        infoPaint.setColor(FOOTER_COLOR);
        infoPaint.setTextSize(22);
        String exportTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        String infoText = "共 " + questions.size() + " 道题目 · " + exportTime;
        StaticLayout infoLayout = new StaticLayout(infoText, infoPaint, imageWidth - 2 * cardMargin, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(cardMargin, y);
        infoLayout.draw(canvas);
        canvas.restore();
        y += infoLayout.getHeight() + 40;

        // 绘制题目卡片
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);

            // 绘制卡片背景
            RectF cardRect = new RectF(cardMargin, y - cardRadius, imageWidth - cardMargin, y + 0);
            // 先计算卡片高度
            int cardHeight = calculateCardHeight(question, task, imageWidth - 2 * cardPadding);
            canvas.drawRoundRect(
                new RectF(cardMargin, y, imageWidth - cardMargin, y + cardHeight),
                cardRadius, cardRadius,
                new Paint(Paint.ANTI_ALIAS_FLAG) {{ setColor(CARD_BG); }}
            );

            int cardY = y + cardPadding;

            // 绘制题目编号、类型和元信息
            StringBuilder metaText = new StringBuilder();
            if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
                metaText.append(question.getQuestionType());
            }
            if (question.getDifficultyText() != null) {
                if (metaText.length() > 0) metaText.append(" · ");
                metaText.append("难度: ").append(question.getDifficultyText());
            }
            if (question.getCategory() != null && !question.getCategory().isEmpty()) {
                if (metaText.length() > 0) metaText.append(" · ");
                metaText.append(question.getCategory());
            }
            if (question.getSubCategory() != null && !question.getSubCategory().isEmpty()) {
                if (metaText.length() > 0) metaText.append(" · ");
                metaText.append(question.getSubCategory());
            }
            if (question.getKnowledgePoint() != null && !question.getKnowledgePoint().isEmpty()) {
                if (metaText.length() > 0) metaText.append(" · ");
                metaText.append("知识点: ").append(question.getKnowledgePoint());
            }
            
            String typeLabel = (metaText.length() > 0)
                ? "第" + (i + 1) + "题 · " + metaText.toString()
                : "第" + (i + 1) + "题";
            StaticLayout typeLayout = new StaticLayout(typeLabel, typePaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
            canvas.save();
            canvas.translate(cardMargin + cardPadding, cardY);
            typeLayout.draw(canvas);
            canvas.restore();
            cardY += typeLayout.getHeight() + 20;

            // 绘制题目内容
            if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                StaticLayout questionLayout = new StaticLayout(question.getQuestionText(), textPaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.2f, 0.0f, false);
                canvas.save();
                canvas.translate(cardMargin + cardPadding, cardY);
                questionLayout.draw(canvas);
                canvas.restore();
                cardY += questionLayout.getHeight() + 20;
            }

            // 绘制选项
            if (question.hasOptions()) {
                for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                    Object optionValue = ExportUtils.getOptionValue(question, o);
                    if (optionValue == null || optionValue.toString().isEmpty()) continue;
                    
                    TextPaint optionPaint = new TextPaint(textPaint);
                    optionPaint.setColor(ThemeColors.get(R.color.hc_ff475569));
                    StaticLayout optionLayout = new StaticLayout(
                        ExportUtils.OPTION_LABELS[o] + ". " + optionValue,
                        optionPaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.2f, 0.0f, false
                    );
                    canvas.save();
                    canvas.translate(cardMargin + cardPadding + 16, cardY);
                    optionLayout.draw(canvas);
                    canvas.restore();
                    cardY += optionLayout.getHeight() + 8;
                }
            }

            // 绘制分割线
            if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                Paint dividerPaint = new Paint();
                dividerPaint.setColor(ThemeColors.get(R.color.hc_ffe2e8f0));
                dividerPaint.setStrokeWidth(1);
                canvas.drawLine(cardMargin + cardPadding, cardY, imageWidth - cardMargin - cardPadding, cardY, dividerPaint);
                cardY += 20;
            }

            // 绘制正确答案
            if (config.isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                StaticLayout answerLayout = new StaticLayout(
                    "正确答案：" + question.getCorrectAnswer(),
                    answerPaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false
                );
                canvas.save();
                canvas.translate(cardMargin + cardPadding, cardY);
                answerLayout.draw(canvas);
                canvas.restore();
                cardY += answerLayout.getHeight() + 12;
            }

            // 绘制答案文本（填空题/简答题）
            if (config.isIncludeAnswers() && question.getAnswerText() != null && !question.getAnswerText().isEmpty()) {
                StaticLayout answerTextLayout = new StaticLayout(
                    "答案文本：" + question.getAnswerText(),
                    answerPaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false
                );
                canvas.save();
                canvas.translate(cardMargin + cardPadding, cardY);
                answerTextLayout.draw(canvas);
                canvas.restore();
                cardY += answerTextLayout.getHeight() + 12;
            }

            // 绘制解析
            if (config.isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                StaticLayout explanationLayout = new StaticLayout(
                    "解析：" + question.getExplanation(),
                    explanationPaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false
                );
                canvas.save();
                canvas.translate(cardMargin + cardPadding, cardY);
                explanationLayout.draw(canvas);
                canvas.restore();
                cardY += explanationLayout.getHeight() + 12;
            }

            // 绘制详细解析
            if (config.isIncludeExplanations() && question.getAnalysis() != null && !question.getAnalysis().isEmpty()) {
                StaticLayout analysisLayout = new StaticLayout(
                    "详细解析：" + question.getAnalysis(),
                    explanationPaint, imageWidth - 2 * cardPadding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false
                );
                canvas.save();
                canvas.translate(cardMargin + cardPadding, cardY);
                analysisLayout.draw(canvas);
                canvas.restore();
                cardY += analysisLayout.getHeight() + 12;
            }

            y += cardHeight + cardSpacing;

            // 更新进度
            if (task.getCallback() != null && i % 10 == 0) {
                int progress = (int) ((i + 1) * 100.0 / questions.size());
                task.getCallback().onExportProgress(progress);
            }
        }

        // 绘制页脚
        TextPaint footerPaint = new TextPaint();
        footerPaint.setColor(FOOTER_COLOR);
        footerPaint.setTextSize(20);
        StaticLayout footerLayout = new StaticLayout("导出完成", footerPaint, imageWidth - 2 * cardMargin, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(cardMargin, y + 40);
        footerLayout.draw(canvas);
        canvas.restore();

        return bitmap;
    }

    private int calculateCardHeight(Question question, ExportManager.ExportTask task, int availableWidth) {
        return calculateCardHeight(question, task, availableWidth, true, true);
    }

    private int calculateCardHeight(Question question, ExportManager.ExportTask task, int availableWidth, boolean includeAnswers, boolean includeExplanations) {
        int height = 80; // 基础高度（编号 + 类型）
        int textSize = 30;

        // 题目内容高度
        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            int lines = (int) Math.ceil((float) question.getQuestionText().length() * textSize / (availableWidth));
            height += lines * 36 + 20;
        }

        // 选项高度
        if (question.hasOptions()) {
            for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                Object optionValue = ExportUtils.getOptionValue(question, o);
                if (optionValue == null || optionValue.toString().isEmpty()) continue;
                int lines = (int) Math.ceil((float) (optionValue.toString().length() + 3) * 30 / (availableWidth - 16));
                height += lines * 36 + 8;
            }
        }

        // 答案和解析（只在 task 不为 null 且配置为 true 时计算）
        if (task != null && includeAnswers && task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            height += 60;
        }
        if (task != null && includeExplanations && task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            int lines = (int) Math.ceil((float) (question.getExplanation().length() + 2) * 26 / availableWidth);
            height += lines * 32 + 32;
        }

        return height;
    }

    private int calculateImageHeight(List<Question> questions, ExportManager.ExportTask task,
                                     int imageWidth, int cardPadding, int cardMargin, int cardSpacing) {
        // 顶部：标题 + 信息 + 间距
        int height = cardMargin * 2 + 160;

        // 每道卡片及间距（与绘制共用同一高度口径，保证答案/解析不被截断）
        for (int i = 0; i < questions.size(); i++) {
            height += calculateCardHeight(questions.get(i), task, imageWidth - 2 * cardPadding) + cardSpacing;
        }

        // 底部页脚
        height += 100;

        return height;
    }

    @Override
    public String getFormatName() {
        return "Long Image";
    }

    @Override
    public String getFileExtension() {
        return "png";
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
        if (task.getContext() == null) {
            throw new IllegalArgumentException("上下文不能为空");
        }
    }
}
