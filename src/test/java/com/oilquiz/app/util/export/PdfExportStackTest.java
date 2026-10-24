package com.oilquiz.app.util.export;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * PDF 导出栈守卫（2026-10-24，iText → PDFBox 迁移）。
 *
 * <p>三件事各钉一条：</p>
 * <ol>
 *   <li><b>不许再出现 iText</b>：iText 7 Core 是 AGPL-3.0，分发即要求整个 App 按 AGPL 开源。
 *       源码级断言，防止以后为了省事又把 {@code com.itextpdf} 加回来。</li>
 *   <li><b>PDFBox 在类路径上</b>：{@code com.tom_roush.pdfbox.pdmodel.PDDocument} 能加载。</li>
 *   <li><b>中文字体能嵌入且能读回</b>：用随包 Noto Sans SC 生成一页 PDF，再用 PDFTextStripper
 *       抽取，断言中文完整——这条同时验证字体许可替换（OFL）没有破坏中文导出。
 *       字体文件不在 Git 里（{@code assets/fonts/} 被 ignore），缺失时 <b>跳过</b>而不是失败。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class PdfExportStackTest {

    private static final String FONT_ASSET = "fonts/NotoSansSC-Regular.ttf";
    private static final String SAMPLE = "题目导出测试 ABC 123";

    @Test
    public void sourcesDoNotReferenceItext() throws Exception {
        File root = new File("src/main/java");
        assumeTrue("需要在模块根目录跑测试", root.isDirectory());
        StringBuilder hits = new StringBuilder();
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (text.contains("com.itextpdf")) {
                        hits.append(p).append('\n');
                    }
                } catch (Exception ignored) {
                }
            });
        }
        assertTrue("PDF 相关代码不许再引用 iText（AGPL-3.0，分发即传染）:\n" + hits,
                hits.length() == 0);
    }

    @Test
    public void pdfboxIsOnClasspath() throws Exception {
        assertNotNull(Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument"));
    }

    @Test
    public void bundledFontEmbedsAndTextRoundTrips() throws Exception {
        assumeTrue("字体文件缺失（跑 scripts/fetch-font.ps1 获取），跳过嵌入验证",
                new File("src/main/assets/" + FONT_ASSET).isFile());

        Context context = ApplicationProvider.getApplicationContext();
        PDFBoxResourceLoader.init(context);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont font;
            try (InputStream in = context.getAssets().open(FONT_ASSET)) {
                font = PDType0Font.load(document, in, true);
            }
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 14);
                stream.newLineAtOffset(50, 700);
                stream.showText(SAMPLE);
                stream.endText();
            }
            document.save(out);
        }

        try (PDDocument reloaded = PDDocument.load(out.toByteArray())) {
            String text = new PDFTextStripper().getText(reloaded);
            // 变量字体里同一个字形可能同时对应「汉字」和「康熙部首」（如 目 U+76EE / ⽬ U+2F6C），
            // PDFBox 生成 ToUnicode 时只会挑其中一个，抽取回来可能是部首变体。
            // 视觉完全一致，只是复制出来的字符落在兼容区；NFKC 会把部首折回统一汉字。
            String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
            assertTrue("导出的 PDF 里读不回中文，字体/编码有问题：[" + text.trim() + "]",
                    normalized.contains("题目导出测试"));
        }
    }
}
