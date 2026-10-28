package com.oilquiz.app.ai.chat.vision;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.attachment.AttachmentPromptBuilder;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.spi.AppServices;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 附件视觉编排器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity 附件+视觉链路抽取的可复用侧：附件过滤（数量/大小）、图片视觉通道
 * 选图（数量/请求体预算）、视觉历史构建、本地/在线视觉可用性决策、在线视觉模型判定。
 * 完整执行流程（落盘→编码→生成→渲染）由宿主组合 {@link VisionInferenceHelper} 与本类决策完成。
 *
 * 单次视觉请求的图片数取各家官方最小值（智谱 GLM-4V 5 张、GLM-4V-Flash 1 张），
 * 请求体预算取交集（Anthropic 单图 base64 ≤5MB、Gemini inline 整请求 ≤20MB），
 * 单图字节预算见 ImagePreprocessUtil.ONLINE_VISION_BUDGET_BYTES。
 */
public class AttachmentVisionPipeline {

    public static final int MAX_ATTACHMENTS = 10;
    public static final long MAX_ATTACHMENT_FILE_SIZE = 5L * 1024 * 1024;
    /** 单次视觉请求最多携带的图片数（各家官方最小值；超出部分并入 OCR 文本通道） */
    public static final int MAX_VISION_IMAGES = 5;
    /** 单次视觉请求所有图片的字节预算（base64 前；base64 膨胀约 1.33 倍） */
    public static final long MAX_VISION_TOTAL_BYTES = 8L * 1024 * 1024;
    /** 在线视觉失败冷却时间 */
    public static final long VISION_FAIL_COOLDOWN_MS = 30_000L;

    private final AttachmentPromptBuilder promptBuilder;
    private final Env env;

    /** 环境事实提供者（宿主注入） */
    public interface Env {
        boolean isOnlineModel();
        OnlineModelManager.OnlineModelConfig getActiveModel();
        String getCurrentModelName();
        boolean isLocalVisionLoaded();   // LlamaHelper.isMultimodalLoaded && isModelInitialized
        /** 本地引擎是否能一次吃多张图（llama.cpp mtmd 是 bitmaps[1] 单图硬限，NPU VLM 支持 List） */
        boolean supportsLocalMultiImage();
        int getModelContextSize();
    }

    public AttachmentVisionPipeline(Context context, Env env) {
        AppServices.ensure(context);
        this.env = env;
        this.promptBuilder = new AttachmentPromptBuilder(context, env::getModelContextSize);
    }

    public AttachmentPromptBuilder getPromptBuilder() { return promptBuilder; }

    // ==================== 附件过滤 ====================

    /** 过滤有效附件（数量/大小限制），返回过滤后列表与跳过原因列表 */
    public static class FilterResult {
        public final List<ChatMessage.Attachment> filtered;
        public final List<String> skipped;
        public FilterResult(List<ChatMessage.Attachment> filtered, List<String> skipped) {
            this.filtered = filtered;
            this.skipped = skipped;
        }
    }

    public FilterResult filterAttachments(List<ChatMessage.Attachment> attachments) {
        List<ChatMessage.Attachment> filtered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (attachments == null) return new FilterResult(filtered, skipped);
        for (ChatMessage.Attachment att : attachments) {
            if (filtered.size() >= MAX_ATTACHMENTS) {
                skipped.add(att.name + "(超出数量限制)");
            } else if (att.size > MAX_ATTACHMENT_FILE_SIZE) {
                skipped.add(att.name + "(" + com.oilquiz.app.ai.chat.file.FileUriUtils.formatFileSize(att.size) + ")");
            } else {
                filtered.add(att);
            }
        }
        return new FilterResult(filtered, skipped);
    }

    /** 是否全部为图片附件 */
    public static boolean allImages(List<ChatMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return false;
        for (ChatMessage.Attachment att : attachments) {
            if (att == null || !"image".equals(att.type)) return false;
        }
        return true;
    }

    // ==================== 视觉追问定位 ====================

    /** 找最近一条带图片的用户消息（不包含末尾当前消息）；返回索引或 -1 */
    public int findLastImageAttachment(List<ChatMessage> history) {
        if (history == null) return -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.type == ChatMessage.MessageType.USER && m.attachments != null) {
                for (ChatMessage.Attachment att : m.attachments) {
                    if (att != null && "image".equals(att.type)) return i;
                }
            }
        }
        return -1;
    }

    /** 该消息是否本身是"末尾带图消息"（不应走追问） */
    public boolean isCurrentImageMessage(int lastImageIndex, List<ChatMessage> history) {
        return lastImageIndex >= 0 && lastImageIndex == history.size() - 1;
    }

    // ==================== 视觉追问频次闸门（2026-10-28） ====================

    /**
     * 追问自动复用图的轮次上限：图片消息之后最多再走这么多个用户回合，更早的图一律不再
     * 自动重新编码上传。超过这个距离还说"看这张图"，请用户重新发图（或等 {@code view_image}
     * 工具落地后由模型显式取图）。
     */
    public static final int VISION_FOLLOW_UP_MAX_TURNS = 4;

    /**
     * 免线索自动复用的轮次数：图片消息之后的第 1~2 个用户回合视为"还在聊这张图"，
     * 不需要用户再说"图"，直接复用。再往后就必须有明确指代（见 {@link #refersToImage}）。
     */
    public static final int VISION_FOLLOW_UP_AUTO_TURNS = 2;

    /**
     * 图片指代线索词（大小写不敏感）。刻意不收单字"图"：地图/图表/图书馆都会误命中。
     * 也不收"这张/那张"：日常里"这张表""那种方式"太常见。
     */
    private static final String[] IMAGE_REFERENCE_CUES = {
            "这张图", "那张图", "这幅图", "那幅图", "这图", "那图", "该图", "此图",
            "上图", "下图", "原图", "大图", "全图", "图中", "图里", "图上", "看图",
            "图片", "图像", "截图", "截屏", "照片", "相片", "画面", "拍照", "拍的照",
            "image", "picture", "photo", "screenshot"
    };

    /**
     * 统计图片消息之后（含当前这轮）的用户消息数。
     *
     * <p>追问闸门的距离口径是"用户回合数"而不是"消息条数"：中间夹多少条 AI 回复/工具消息
     * 都不影响，用户每追问一次才计入一次。</p>
     *
     * @return 图片消息之后的用户消息数；索引无效时返回 0
     */
    public static int userTurnsSinceImage(List<ChatMessage> history, int imageIndex) {
        if (history == null || imageIndex < 0 || imageIndex >= history.size()) return 0;
        int turns = 0;
        for (int i = imageIndex + 1; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m != null && m.type == ChatMessage.MessageType.USER) turns++;
        }
        return turns;
    }

    /** 当前消息是否在指代某张图（线索词匹配） */
    public static boolean refersToImage(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        String text = message.toLowerCase(java.util.Locale.ROOT);
        for (String cue : IMAGE_REFERENCE_CUES) {
            if (text.contains(cue.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * 本轮是否允许"自动复用历史图片"（视觉追问闸门）。
     *
     * <p>旧行为（直到 2026-10-27）：只要历史里出现过图片，之后<b>每一轮</b>纯文字消息都会把
     * 那张图重新编码上传——没有 TTL、没有轮次窗口。真机日志里"你有什么功能""继续"
     * "git clone …"这类与图毫无关系的消息全都被视觉通道接管，模型拿到一张无关的旧图，
     * Agent 工具链同时被提前 return 短路。这既是"发给模型的上下文不对"，也是"图片在
     * 对话流里阴魂不散"的根因。</p>
     *
     * <p>现在的口径（满足其一，且都受 {@link #VISION_FOLLOW_UP_MAX_TURNS} 硬上限约束）：</p>
     * <ul>
     *   <li><b>紧邻轮次</b>：图片消息之后的第 1~{@link #VISION_FOLLOW_UP_AUTO_TURNS} 个用户回合；</li>
     *   <li><b>明确指代</b>：当前消息里有"这张图 / 图中 / 截图 / 照片…"这类线索词。</li>
     * </ul>
     *
     * @param turnsSinceImage {@link #userTurnsSinceImage} 的结果
     * @param currentMessage  当前待发送的纯文字消息
     */
    public static boolean shouldReuseImageForFollowUp(int turnsSinceImage, String currentMessage) {
        if (turnsSinceImage <= 0) return false; // 当前消息自己带图，本轮应走首发图链路，不是追问
        if (turnsSinceImage > VISION_FOLLOW_UP_MAX_TURNS) return false;
        if (turnsSinceImage <= VISION_FOLLOW_UP_AUTO_TURNS) return true;
        return refersToImage(currentMessage);
    }

    /**
     * 给日志用的可读判定：返回 null 表示"允许复用"，否则返回拒绝原因。
     * 判定与 {@link #shouldReuseImageForFollowUp} 完全同源，避免日志和实际行为对不上。
     */
    public static String explainFollowUpReuse(int turnsSinceImage, String currentMessage) {
        if (shouldReuseImageForFollowUp(turnsSinceImage, currentMessage)) return null;
        if (turnsSinceImage <= 0) return "当前消息本身带图，走首发图链路";
        if (turnsSinceImage > VISION_FOLLOW_UP_MAX_TURNS) {
            return "图片已在 " + turnsSinceImage + " 个用户回合之前（上限 "
                    + VISION_FOLLOW_UP_MAX_TURNS + "），不再自动复用";
        }
        return "图片已在 " + turnsSinceImage + " 个用户回合之前，且本轮没有图片指代线索";
    }

    /**
     * 收集一条消息里的全部图片附件（保持用户发送顺序），最多 max 张。
     * 追问场景旧实现只取"最近一张"，多图那轮的上下文会丢；这里按整条消息取图。
     */
    public static List<ChatMessage.Attachment> collectImages(ChatMessage message, int max) {
        List<ChatMessage.Attachment> images = new ArrayList<>();
        if (message == null || message.attachments == null) return images;
        for (ChatMessage.Attachment att : message.attachments) {
            if (att != null && "image".equals(att.type)) {
                if (images.size() >= max) break;
                images.add(att);
            }
        }
        return images;
    }

    /** 构建图片消息之前的视觉历史（USER/AI 文本，最多 max 条） */
    public List<ChatMessage> buildVisionHistoryBefore(List<ChatMessage> history, int lastImageIndex, int max) {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < lastImageIndex; i++) {
            ChatMessage m = history.get(i);
            if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                    && m.content != null && !m.content.isEmpty()) {
                hist.add(m);
            }
        }
        if (hist.size() > max) {
            return new ArrayList<>(hist.subList(hist.size() - max, hist.size()));
        }
        return hist;
    }

    // ==================== 可用性决策 ====================

    /** 本地视觉追问可用 */
    public boolean canFollowUpLocalVision() {
        return env.isLocalVisionLoaded();
    }

    /**
     * 本地视觉本次能吃几张图：0=不可用；1=llama.cpp mtmd 单图硬限；其余按在线同一预算。
     * 本次图片数超过该上限时，宿主先把整批交给在线视觉（若可用），在线也不可用才整批改走
     * OCR 文本 —— 不做"只看第一张、其余静默丢掉"那种取舍。
     */
    public int localVisionImageLimit() {
        if (!env.isLocalVisionLoaded()) return 0;
        return env.supportsLocalMultiImage() ? MAX_VISION_IMAGES : 1;
    }

    /** 在线视觉可用（模型支持 vision + 失败冷却未触发） */
    public boolean canFollowUpOnlineVision(long lastOnlineVisionFailAt) {
        if (!env.isOnlineModel()) return false;
        if (System.currentTimeMillis() - lastOnlineVisionFailAt < VISION_FAIL_COOLDOWN_MS) return false;
        return isOnlineVisionModel();
    }

    /** 在线模型是否支持视觉（配置能力字段优先，模型名关键词兜底） */
    public boolean isOnlineVisionModel() {
        try {
            OnlineModelManager.OnlineModelConfig active = env.getActiveModel();
            if (active != null && active.hasCapability("vision")) return true;
            String modelName = env.getCurrentModelName();
            if ((modelName == null || modelName.isEmpty()) && active != null) modelName = active.modelName;
            if (modelName == null) return false;
            return OnlineModelManager.isVisionModelName(modelName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 视觉通道选图结果：按发送顺序可发的图片，以及被移出视觉通道的文件名与原因。
     * 被移出的图片不丢：宿主把它们交给 OCR 文本通道，附件本身仍在消息里可见。
     */
    public static class VisionImageSelection {
        public final List<File> images = new ArrayList<>();
        public final List<String> skippedNames = new ArrayList<>();
        public final List<String> skipReasons = new ArrayList<>();

        public boolean isEmpty() {
            return images.isEmpty();
        }

        public int count() {
            return images.size();
        }
    }

    /**
     * 从已落盘的图片里挑出可进同一次视觉请求的图集（数量与请求体预算双重约束，按发送顺序贪心）。
     *
     * 旧实现只允许 1 张，两张图就整条链路掉回 OCR 文本；这里放开到 {@link #MAX_VISION_IMAGES}
     * 张，并把"超数量""超预算""文件缺失"三种原因分开回报，避免静默丢图。
     */
    public static VisionImageSelection selectVisionImages(List<File> files, List<String> names) {
        VisionImageSelection selection = new VisionImageSelection();
        if (files == null) return selection;
        long total = 0;
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            String name = (names != null && i < names.size() && names.get(i) != null)
                    ? names.get(i) : (file != null ? file.getName() : "图片" + (i + 1));
            if (file == null || !file.exists() || file.length() == 0) {
                selection.skippedNames.add(name);
                selection.skipReasons.add(name + "（本地文件缺失或为空）");
                continue;
            }
            if (selection.count() >= MAX_VISION_IMAGES) {
                selection.skippedNames.add(name);
                selection.skipReasons.add(name + "（超出单次 " + MAX_VISION_IMAGES + " 张）");
                continue;
            }
            if (total + file.length() > MAX_VISION_TOTAL_BYTES) {
                selection.skippedNames.add(name);
                selection.skipReasons.add(name + "（请求体预算已满）");
                continue;
            }
            selection.images.add(file);
            total += file.length();
        }
        return selection;
    }

    /**
     * 在线/本地视觉的用户文本兜底。
     * 直发图片时消息正文是附件占位文案（"请分析这些附件的内容"），那句是给 OCR/Agent 通道用的，
     * 喂给视觉模型会让它以为要"分析附件"；这里一并换成看图指令。
     */
    public static String visionUserText(String originalMessage) {
        if (originalMessage == null || originalMessage.trim().isEmpty()
                || AttachmentPromptBuilder.DEFAULT_ATTACHMENT_MESSAGE.equals(originalMessage.trim())) {
            return "请描述这些图片的内容";
        }
        return originalMessage;
    }
}
