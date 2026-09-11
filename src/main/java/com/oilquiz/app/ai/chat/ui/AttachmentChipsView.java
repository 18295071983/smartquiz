package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.file.FileUriUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 附件预览条（全局可复用 View 组件，新建通用设计）。
 *
 * 覆盖对话页输入区附件展示的通用形态：横向胶囊卡片（图片缩略图 /
 * 文件图标 + 名称 + 大小）+ 右上角删除角标。数据与文件处理复用
 * {@link FileUriUtils}。
 *
 * <pre>
 * AttachmentChipsView chips = new AttachmentChipsView(context);
 * chips.setItems(attachmentList, localPathMap);
 * chips.setOnRemove(att -> { ... });
 * </pre>
 */
public class AttachmentChipsView extends HorizontalScrollView {

    public interface OnRemove { void onRemove(ChatMessage.Attachment att); }
    public interface ThumbnailProvider { String getThumbnailPath(ChatMessage.Attachment att); }

    private final LinearLayout container;
    private final List<ChatMessage.Attachment> items = new ArrayList<>();
    private OnRemove onRemove;
    private ThumbnailProvider thumbnailProvider;

    public AttachmentChipsView(Context context) {
        this(context, null);
    }

    /** XML 布局 inflate 构造（委托单参构造） */
    public AttachmentChipsView(Context context, @androidx.annotation.Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(2), dp(4), dp(2), dp(4));
        addView(container, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setOnRemove(OnRemove l) { this.onRemove = l; }

    /** 缩略图来源注入（如 AttachmentFactory 的 image→thumbnailPath） */
    public void setThumbnailProvider(ThumbnailProvider p) { this.thumbnailProvider = p; }

    public List<ChatMessage.Attachment> getItems() { return items; }

    public void setItems(List<ChatMessage.Attachment> attachments) {
        items.clear();
        if (attachments != null) items.addAll(attachments);
        rebuild();
    }

    public void addItem(ChatMessage.Attachment att) {
        items.add(att);
        rebuild();
    }

    public void removeItem(ChatMessage.Attachment att) {
        items.remove(att);
        rebuild();
    }

    public boolean isEmpty() { return items.isEmpty(); }

    private void rebuild() {
        container.removeAllViews();
        for (final ChatMessage.Attachment att : items) {
            container.addView(buildChip(att));
        }
        setVisibility(items.isEmpty() ? GONE : VISIBLE);
    }

    private View buildChip(final ChatMessage.Attachment att) {
        Context c = getContext();
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF3F4F6);
        bg.setCornerRadius(dp(14));
        chip.setBackground(bg);
        chip.setPadding(dp(8), dp(6), dp(10), dp(6));
        int margin = dp(6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, margin, 0);
        chip.setLayoutParams(lp);

        FrameLayout iconWrap = new FrameLayout(c);
        ImageView icon = new ImageView(c);
        boolean isImage = "image".equals(att.type);
        if (isImage && thumbnailProvider != null) {
            String thumb = thumbnailProvider.getThumbnailPath(att);
            if (thumb != null) {
                try {
                    icon.setImageURI(android.net.Uri.fromFile(new java.io.File(thumb)));
                } catch (Throwable ignored) {}
            }
        }
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(40), dp(40));
        icon.setLayoutParams(iconLp);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(0xFFE5E7EB);
        iconBg.setCornerRadius(dp(10));
        icon.setBackground(iconBg);
        iconWrap.addView(icon);

        TextView name = new TextView(c);
        name.setText(att.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTextColor(0xFF374151);
        name.setMaxWidth(dp(110));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        nameLp.setMargins(dp(8), 0, 0, 0);
        name.setLayoutParams(nameLp);

        TextView size = null;
        if (att.size > 0) {
            size = new TextView(c);
            size.setText(FileUriUtils.formatFileSize(att.size));
            size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            size.setTextColor(0xFF9CA3AF);
        }

        TextView del = new TextView(c);
        del.setText("✕");
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        del.setTextColor(0xFF9CA3AF);
        del.setGravity(Gravity.CENTER);
        GradientDrawable delBg = new GradientDrawable();
        delBg.setColor(0xFFE5E7EB);
        delBg.setCornerRadius(dp(9));
        del.setBackground(delBg);
        FrameLayout.LayoutParams delLp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
        iconWrap.addView(del, delLp);
        del.setOnClickListener(v -> {
            if (onRemove != null) onRemove.onRemove(att);
            removeItem(att);
        });

        LinearLayout textWrap = new LinearLayout(c);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setGravity(Gravity.CENTER_VERTICAL);
        textWrap.addView(name);
        if (size != null) textWrap.addView(size);
        chip.addView(textWrap);
        return chip;
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
}
