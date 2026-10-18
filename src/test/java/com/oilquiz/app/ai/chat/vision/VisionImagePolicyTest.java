package com.oilquiz.app.ai.chat.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.util.ImageFormatSniffer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 图片视觉通道的纯逻辑决策：编码格式嗅探 + 单次请求选图（数量/请求体预算）。
 * 这些判定决定"哪几张图真的进了模型"，必须可回归。
 */
public class VisionImagePolicyTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ==================== MIME 嗅探（按真实字节，不信扩展名） ====================

    @Test
    public void sniffsPortableFormatsFromMagicBytes() {
        assertEquals(ImageFormatSniffer.JPEG, ImageFormatSniffer.sniffMimeType(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 4));
        assertEquals(ImageFormatSniffer.PNG, ImageFormatSniffer.sniffMimeType(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 8));
        assertEquals(ImageFormatSniffer.GIF, ImageFormatSniffer.sniffMimeType(
                new byte[]{'G', 'I', 'F', '8', '9', 'a'}, 6));
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertEquals(ImageFormatSniffer.WEBP, ImageFormatSniffer.sniffMimeType(webp, webp.length));
        assertEquals(ImageFormatSniffer.BMP, ImageFormatSniffer.sniffMimeType(
                new byte[]{'B', 'M', 0, 0}, 4));
    }

    @Test
    public void sniffsHeicAndAvifFromIsoBmffBrand() {
        byte[] heic = brand("ftyp", "heic");
        assertEquals(ImageFormatSniffer.HEIC, ImageFormatSniffer.sniffMimeType(heic, heic.length));
        byte[] avif = brand("ftyp", "avif");
        assertEquals(ImageFormatSniffer.AVIF, ImageFormatSniffer.sniffMimeType(avif, avif.length));
    }

    @Test
    public void unknownBytesAreNotClaimedAsImage() {
        assertEquals(ImageFormatSniffer.UNKNOWN, ImageFormatSniffer.sniffMimeType(
                "%PDF-1.7".getBytes(), 8));
        assertEquals(ImageFormatSniffer.UNKNOWN, ImageFormatSniffer.sniffMimeType(new byte[0], 0));
    }

    @Test
    public void heicIsNotPortableMustBeTranscoded() {
        assertFalse(ImageFormatSniffer.isProviderAgnosticFormat(ImageFormatSniffer.HEIC));
        assertFalse(ImageFormatSniffer.isProviderAgnosticFormat(ImageFormatSniffer.UNKNOWN));
        assertTrue(ImageFormatSniffer.isProviderAgnosticFormat(ImageFormatSniffer.JPEG));
        assertTrue(ImageFormatSniffer.isProviderAgnosticFormat(ImageFormatSniffer.PNG));
    }

    // ==================== 单次视觉请求选图 ====================

    @Test
    public void keepsSendOrderAndAllImagesWithinLimits() throws Exception {
        List<File> files = Arrays.asList(png("a.png", 1000), png("b.png", 1000));
        AttachmentVisionPipeline.VisionImageSelection selection =
                AttachmentVisionPipeline.selectVisionImages(files, Arrays.asList("a.png", "b.png"));
        assertEquals(2, selection.count());
        assertEquals("a.png", selection.images.get(0).getName());
        assertEquals("b.png", selection.images.get(1).getName());
        assertTrue(selection.skipReasons.isEmpty());
    }

    /** 旧实现是"只有 1 张图才走视觉"，两张就被整批降级成 OCR 文本 */
    @Test
    public void allowsMultipleImagesUpToProviderMinimumCap() throws Exception {
        List<File> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < AttachmentVisionPipeline.MAX_VISION_IMAGES; i++) {
            files.add(png("img" + i + ".png", 1024));
            names.add("img" + i + ".png");
        }
        AttachmentVisionPipeline.VisionImageSelection selection =
                AttachmentVisionPipeline.selectVisionImages(files, names);
        assertEquals(AttachmentVisionPipeline.MAX_VISION_IMAGES, selection.count());
    }

    @Test
    public void overflowBeyondImageCountGoesToOcrWithReason() throws Exception {
        List<File> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= AttachmentVisionPipeline.MAX_VISION_IMAGES; i++) {
            files.add(png("over" + i + ".png", 512));
            names.add("over" + i + ".png");
        }
        AttachmentVisionPipeline.VisionImageSelection selection =
                AttachmentVisionPipeline.selectVisionImages(files, names);
        assertEquals(AttachmentVisionPipeline.MAX_VISION_IMAGES, selection.count());
        assertEquals(1, selection.skipReasons.size());
        assertTrue(selection.skipReasons.get(0), selection.skipReasons.get(0).contains("超出单次"));
    }

    @Test
    public void stopsAddingWhenRequestByteBudgetExhausted() throws Exception {
        long perImage = com.oilquiz.app.ai.util.ImagePreprocessUtil.ONLINE_VISION_BUDGET_BYTES;
        int n = (int) (AttachmentVisionPipeline.MAX_VISION_TOTAL_BYTES / perImage) + 2;
        List<File> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            files.add(png("big" + i + ".png", (int) perImage));
            names.add("big" + i + ".png");
        }
        AttachmentVisionPipeline.VisionImageSelection selection =
                AttachmentVisionPipeline.selectVisionImages(files, names);
        assertTrue("预算内至少应放行 1 张", selection.count() >= 1);
        assertTrue("放行张数受请求体预算约束",
                selection.count() < n);
        assertFalse(selection.skipReasons.isEmpty());
    }

    @Test
    public void missingFilesAreReportedNotSilentlyDropped() throws Exception {
        File gone = new File(tmp.getRoot(), "gone.png");
        AttachmentVisionPipeline.VisionImageSelection selection =
                AttachmentVisionPipeline.selectVisionImages(
                        Arrays.asList(gone, png("ok.png", 512)),
                        Arrays.asList("gone.png", "ok.png"));
        assertEquals(1, selection.count());
        assertEquals("ok.png", selection.images.get(0).getName());
        assertTrue(selection.skipReasons.get(0), selection.skipReasons.get(0).contains("缺失"));
    }

    @Test
    public void emptyMessageTextGetsVisionFallbackPrompt() {
        assertEquals("请描述这些图片的内容",
                AttachmentVisionPipeline.visionUserText("   "));
        assertEquals("图里有几道题？", AttachmentVisionPipeline.visionUserText("图里有几道题？"));
    }

    /** 直发图片时正文是附件占位文案，那是给 OCR/Agent 通道的话，喂视觉模型要换成看图指令 */
    @Test
    public void attachmentPlaceholderTextIsReplacedForVision() {
        assertEquals("请描述这些图片的内容", AttachmentVisionPipeline.visionUserText(
                com.oilquiz.app.ai.chat.attachment.AttachmentPromptBuilder.DEFAULT_ATTACHMENT_MESSAGE));
    }

    /** data URI 前缀必须等于真实 media_type；三条链路（chat/Responses/Anthropic）共用这一出口 */
    @Test
    public void dataUrlCarriesRealMimeType() {
        com.oilquiz.app.ai.util.ImagePreprocessUtil.PreparedVisionImage png =
                com.oilquiz.app.ai.util.ImagePreprocessUtil.PreparedVisionImage
                        .ofBase64("AAAA", "image/png");
        assertEquals("data:image/png;base64,AAAA", png.dataUrl());

        com.oilquiz.app.ai.util.ImagePreprocessUtil.PreparedVisionImage missingMime =
                com.oilquiz.app.ai.util.ImagePreprocessUtil.PreparedVisionImage
                        .ofBase64("AAAA", null);
        assertEquals("data:image/jpeg;base64,AAAA", missingMime.dataUrl());
    }

    private static byte[] brand(String ftyp, String brand) {
        byte[] out = new byte[16];
        for (int i = 0; i < 4; i++) out[i] = 0;
        System.arraycopy(ftyp.getBytes(), 0, out, 4, 4);
        System.arraycopy(brand.getBytes(), 0, out, 8, 4);
        return out;
    }

    private File png(String name, int size) throws Exception {
        File file = tmp.newFile(name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] bytes = new byte[size];
            // 头部填 PNG magic，让"文件存在且非空"与真实字节同时成立
            byte[] magic = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            System.arraycopy(magic, 0, bytes, 0, magic.length);
            fos.write(bytes);
        }
        return file;
    }
}
