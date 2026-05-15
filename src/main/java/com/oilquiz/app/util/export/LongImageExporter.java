package com.oilquiz.app.util.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class LongImageExporter implements Exporter {

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

        // 使用默认配置参数
        int imageWidth = 3840; // 4K图片宽度
        int textSize = 32; // 相应调整文本大小以保持可读性
        String backgroundColor = "#FFFFFF";
        int padding = 40; // 增加内边距以适应更大的图片

        // 计算图片高度
        int imageHeight = calculateImageHeight(questions, imageWidth, textSize, padding);

        // 创建 bitmap
        Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 设置背景颜色
        canvas.drawColor(Color.parseColor(backgroundColor));

        // 创建画笔
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        // 绘制内容
        int y = padding;

        // 绘制标题
        TextPaint titlePaint = new TextPaint();
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(textSize * 2);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        StaticLayout titleLayout = new StaticLayout("导出题目", titlePaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(padding, y);
        titleLayout.draw(canvas);
        canvas.restore();
        y += titleLayout.getHeight() + padding;

        // 绘制导出信息
        TextPaint infoPaint = new TextPaint();
        infoPaint.setColor(Color.GRAY);
        infoPaint.setTextSize(textSize * 0.8f);
        String exportTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        String infoText = "导出时间: " + exportTime + " | 题目数量: " + questions.size();
        StaticLayout infoLayout = new StaticLayout(infoText, infoPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(padding, y);
        infoLayout.draw(canvas);
        canvas.restore();
        y += infoLayout.getHeight() + padding;

        // 绘制分割线
        Paint linePaint = new Paint();
        linePaint.setColor(Color.LTGRAY);
        linePaint.setStrokeWidth(2);
        canvas.drawLine(padding, y, imageWidth - padding, y, linePaint);
        y += padding;

        // 绘制题目
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);

            // 绘制题目编号
            String questionNumberText = "第" + (i + 1) + "题";
            if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
                questionNumberText += " (" + question.getQuestionType() + ")";
            }
            StaticLayout questionNumberLayout = new StaticLayout(questionNumberText, titlePaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
            canvas.save();
            canvas.translate(padding, y);
            questionNumberLayout.draw(canvas);
            canvas.restore();
            y += questionNumberLayout.getHeight() + padding / 2;

            // 绘制题目内容
            if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                StaticLayout questionTextLayout = new StaticLayout(question.getQuestionText(), textPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                canvas.save();
                canvas.translate(padding, y);
                questionTextLayout.draw(canvas);
                canvas.restore();
                y += questionTextLayout.getHeight() + padding / 2;
            }

            // 绘制选项
            if (question.hasOptions()) {
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    StaticLayout optionALayout = new StaticLayout("A. " + question.getOptionA(), textPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                    canvas.save();
                    canvas.translate(padding + 20, y);
                    optionALayout.draw(canvas);
                    canvas.restore();
                    y += optionALayout.getHeight() + padding / 4;
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    StaticLayout optionBLayout = new StaticLayout("B. " + question.getOptionB(), textPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                    canvas.save();
                    canvas.translate(padding + 20, y);
                    optionBLayout.draw(canvas);
                    canvas.restore();
                    y += optionBLayout.getHeight() + padding / 4;
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    StaticLayout optionCLayout = new StaticLayout("C. " + question.getOptionC(), textPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                    canvas.save();
                    canvas.translate(padding + 20, y);
                    optionCLayout.draw(canvas);
                    canvas.restore();
                    y += optionCLayout.getHeight() + padding / 4;
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    StaticLayout optionDLayout = new StaticLayout("D. " + question.getOptionD(), textPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                    canvas.save();
                    canvas.translate(padding + 20, y);
                    optionDLayout.draw(canvas);
                    canvas.restore();
                    y += optionDLayout.getHeight() + padding / 4;
                }
            }

            // 绘制正确答案
            if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                TextPaint answerPaint = new TextPaint();
                answerPaint.setColor(Color.GREEN);
                answerPaint.setTextSize(textSize);
                answerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                StaticLayout answerLayout = new StaticLayout("正确答案: " + question.getCorrectAnswer(), answerPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                canvas.save();
                canvas.translate(padding, y);
                answerLayout.draw(canvas);
                canvas.restore();
                y += answerLayout.getHeight() + padding / 2;
            }

            // 绘制解析
            if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                TextPaint explanationPaint = new TextPaint();
                explanationPaint.setColor(Color.BLUE);
                explanationPaint.setTextSize(textSize);
                explanationPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC));
                StaticLayout explanationLayout = new StaticLayout("解析: " + question.getExplanation(), explanationPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                canvas.save();
                canvas.translate(padding, y);
                explanationLayout.draw(canvas);
                canvas.restore();
                y += explanationLayout.getHeight() + padding / 2;
            }

            // 绘制题目之间的分割线
            if (i < questions.size() - 1) {
                canvas.drawLine(padding, y, imageWidth - padding, y, linePaint);
                y += padding;
            }

            // 更新进度
            if (task.getCallback() != null && i % 10 == 0) {
                int progress = (int) ((i + 1) * 100.0 / questions.size());
                task.getCallback().onExportProgress(progress);
            }
        }

        // 绘制页脚
        StaticLayout footerLayout = new StaticLayout("导出完成", infoPaint, imageWidth - 2 * padding, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(padding, y);
        footerLayout.draw(canvas);
        canvas.restore();

        return bitmap;
    }

    private int calculateImageHeight(List<Question> questions, int imageWidth, int textSize, int padding) {
        int height = 0;

        // 标题高度
        height += padding * 3;

        // 导出信息高度
        height += padding * 2;

        // 分割线高度
        height += padding * 2;

        // 题目高度
        for (Question question : questions) {
            // 题目编号高度
            height += textSize * 3;

            // 题目内容高度
            if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                int lines = (int) Math.ceil((float) question.getQuestionText().length() * textSize / (imageWidth - 2 * padding));
                height += lines * textSize * 1.5;
            }

            // 选项高度
            if (question.hasOptions()) {
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    int lines = (int) Math.ceil((float) (question.getOptionA().length() + 3) * textSize / (imageWidth - 2 * padding - 20));
                    height += lines * textSize * 1.2;
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    int lines = (int) Math.ceil((float) (question.getOptionB().length() + 3) * textSize / (imageWidth - 2 * padding - 20));
                    height += lines * textSize * 1.2;
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    int lines = (int) Math.ceil((float) (question.getOptionC().length() + 3) * textSize / (imageWidth - 2 * padding - 20));
                    height += lines * textSize * 1.2;
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    int lines = (int) Math.ceil((float) (question.getOptionD().length() + 3) * textSize / (imageWidth - 2 * padding - 20));
                    height += lines * textSize * 1.2;
                }
            }

            // 正确答案高度
            height += textSize * 2;

            // 解析高度
            if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                int lines = (int) Math.ceil((float) (question.getExplanation().length() + 4) * textSize / (imageWidth - 2 * padding));
                height += lines * textSize * 1.5;
            }

            // 题目之间的间距
            height += padding * 2;
        }

        // 页脚高度
        height += padding * 3;

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
