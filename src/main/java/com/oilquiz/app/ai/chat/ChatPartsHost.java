package com.oilquiz.app.ai.chat;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.parts.PartHost;
import com.oilquiz.app.theme.ThemeColors;

import java.util.Locale;

/**
 * {@link PartHost} 的 ChatAdapter 侧实现（parts 架构升级 P3a，2026-10-21）。
 *
 * <p>把 parts 渲染器需要的环境映射到本 App 既有能力，全部走复用、不新造渲染：</p>
 * <ul>
 *   <li>{@link #renderMarkdown} → {@code ChatAdapter.renderInto}（既有后台渲染：Markwon +
 *       LinkMovementMethod + Glide 异步图片），renderer 不自己 setText；</li>
 *   <li>{@link #dark()} / {@link #markdownThemeVars()} → {@link ThemeColors} 语义 token，
 *       与 {@code ChatAdapter.buildMarkdownThemeVars} 同口径；</li>
 *   <li>审批决策 / 尺寸变化经 {@link Callback} 回投宿主（ChatAdapter/holder）。</li>
 * </ul>
 *
 * <p>放在 {@code com.oilquiz.app.ai.chat} 包内，是因为要调用 ChatAdapter 的包级静态
 * {@code renderInto}。实例按 holder 复用：{@link #setMessage}/{@link #setAvailableWidth}/
 * {@link #setWebMarkdownEnabled} 每次绑定刷新。</p>
 */
public final class ChatPartsHost implements PartHost {

    /** 宿主回调：审批决策与内容尺寸变化。 */
    public interface Callback {
        void onApprovalDecision(String partId, String toolCallId, boolean approved);

        void onPartsContentChanged();
    }

    private final Context ctx;
    private final Callback callback;
    private ChatMessage message;
    private int availableWidth;
    private boolean webMarkdownEnabled;

    public ChatPartsHost(Context ctx, Callback callback) {
        this.ctx = ctx;
        this.callback = callback;
    }

    public void setMessage(ChatMessage message) {
        this.message = message;
    }

    public void setAvailableWidth(int px) {
        this.availableWidth = px;
    }

    public void setWebMarkdownEnabled(boolean enabled) {
        this.webMarkdownEnabled = enabled;
    }

    @Override
    public Context context() {
        return ctx;
    }

    @Override
    public int availableWidth() {
        return availableWidth;
    }

    @Override
    public ChatMessage message() {
        return message;
    }

    @Override
    public boolean webMarkdownEnabled() {
        return webMarkdownEnabled;
    }

    @Override
    public boolean dark() {
        try {
            int m = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return m == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String markdownThemeVars() {
        try {
            int fg = ThemeColors.attr(ctx, R.attr.colorOnSurface);
            int link = ThemeColors.attr(ctx, R.attr.colorPrimary);
            int muted = ThemeColors.attr(ctx, R.attr.colorControlTextSecondary);
            return "--fg:" + toCss(fg) + ";--link:" + toCss(link) + ";--muted:" + toCss(muted);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void renderMarkdown(TextView target, String markdown) {
        // 复用 ChatAdapter 既有后台渲染原语（含代次守卫），绝不在主线程 setText。
        ChatAdapter.renderInto(target, markdown, availableWidth);
    }

    @Override
    public void onApprovalDecision(String partId, String toolCallId, boolean approved) {
        if (callback != null) {
            callback.onApprovalDecision(partId, toolCallId, approved);
        }
    }

    @Override
    public void invalidateSize() {
        if (callback != null) {
            callback.onPartsContentChanged();
        }
    }

    private static String toCss(int argb) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & argb);
    }
}
