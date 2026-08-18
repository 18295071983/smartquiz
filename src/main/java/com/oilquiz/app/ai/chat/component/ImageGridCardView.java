package com.oilquiz.app.ai.chat.component;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.PhotoView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 图片网格组件：消息内直接渲染图片（不再用灰色占位块）。
 *
 * - 单图：宽度撑满，高度按图片比例自适应（最高 420dp），加载中转圈、失败可点击重试
 * - 多图：网格缩略图（每格加载中转圈、失败可重试），点击全屏查看（PhotoView 缩放）
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "columns": 3,                                   // 可选，默认 3
 *   "images": ["https://.../a.jpg", "content://...", {"url":"..."}]
 * }
 * </pre>
 */
public class ImageGridCardView implements ChatComponent {

    @Override
    public String getType() {
        return "image_grid";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("images") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        int columns = Math.max(1, Math.min(4, p.optInt("columns", 3)));
        JSONArray images = p.optJSONArray("images");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));

        // 收集图片 URL
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < images.length(); i++) {
            Object obj = images.opt(i);
            if (obj instanceof String) {
                urls.add((String) obj);
            } else if (obj instanceof JSONObject) {
                String u = ((JSONObject) obj).optString("url", "");
                if (!u.isEmpty()) urls.add(u);
            }
        }
        if (urls.isEmpty()) return null;

        // 单图：内联大图（宽度撑满，高度按图片比例，加载中/失败有明确反馈）
        if (urls.size() == 1) {
            return createSingleImage(context, urls.get(0));
        }

        // 多图：网格缩略图
        LinearLayout currentRow = null;
        for (int i = 0; i < urls.size(); i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(context);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(currentRow, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final String url = urls.get(i);
            int side = dp(context, 90);
            FrameLayout item = createGridItem(context, url, side);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, side);
            lp.setMargins(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
            lp.weight = 1f;
            currentRow.addView(item, lp);
        }

        return card;
    }

    /** 单图：内联大图，加载中转圈，失败可重试 */
    private View createSingleImage(Context context, String url) {
        FrameLayout root = new FrameLayout(context);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rootLp.setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
        root.setLayoutParams(rootLp);

        ImageView iv = new ImageView(context);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        iv.setLayoutParams(ivLp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);   // 高度按图片宽高比自适应
        iv.setMaxHeight(dp(context, 420));
        iv.setVisibility(View.GONE);
        root.addView(iv);

        ProgressBar loading = new ProgressBar(context);
        FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        loadingLp.topMargin = dp(context, 30);
        root.addView(loading, loadingLp);

        loadWithFeedback(root, iv, loading, url, true);
        return root;
    }

    /** 网格项：缩略图 + 加载中 + 失败重试 */
    private FrameLayout createGridItem(Context context, String url, int side) {
        FrameLayout item = new FrameLayout(context);

        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setVisibility(View.GONE);
        item.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ProgressBar loading = new ProgressBar(context);
        FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(
                dp(context, 20), dp(context, 20), Gravity.CENTER);
        item.addView(loading, loadingLp);

        loadWithFeedback(item, iv, loading, url, false);
        return item;
    }

    /**
     * 本地图片统一解码：file://、/ 开头路径、content:// URI 均走 BitmapFactory（采样防 OOM）。
     * 返回 null 表示非本地源或解码失败（调用方回退 Glide）。
     */
    private static android.graphics.Bitmap decodeLocalImage(Context context, String url) {
        if (url == null) return null;
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            if (url.startsWith("content://")) {
                try (java.io.InputStream is = context.getContentResolver().openInputStream(android.net.Uri.parse(url))) {
                    if (is == null) return null;
                    android.graphics.BitmapFactory.decodeStream(is, null, opts);
                }
            } else if (url.startsWith("file://") || url.startsWith("/")) {
                java.io.File localFile = url.startsWith("file://")
                        ? new java.io.File(android.net.Uri.parse(url).getPath())
                        : new java.io.File(url);
                if (!localFile.exists()) return null;
                android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
            } else {
                return null; // 网络 URL，走 Glide
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = 1;
            while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            if (url.startsWith("content://")) {
                try (java.io.InputStream is = context.getContentResolver().openInputStream(android.net.Uri.parse(url))) {
                    if (is == null) return null;
                    return android.graphics.BitmapFactory.decodeStream(is, null, opts);
                }
            } else {
                java.io.File localFile = url.startsWith("file://")
                        ? new java.io.File(android.net.Uri.parse(url).getPath())
                        : new java.io.File(url);
                return android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
            }
        } catch (Exception e) {
            android.util.Log.w("ImageGridCardView", "decodeLocalImage failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 加载图片：成功显示图片，失败显示可点击重试的提示层。
     * 本地图片（file://、/ 开头、content://）优先 BitmapFactory 直接解码
     * （避免 Glide 对本地 URI 不回调导致转圈）；网络 URL 走 Glide（15s 超时 + 失败提示）。
     */
    private void loadWithFeedback(FrameLayout root, ImageView iv, ProgressBar loading, String url, boolean single) {
        loading.setVisibility(View.VISIBLE);
        iv.setVisibility(View.GONE);
        iv.setImageDrawable(null);

        // 本地图片优先直接解码（file:// / / 开头 / content://）
        if (url != null && (url.startsWith("file://") || url.startsWith("/") || url.startsWith("content://"))) {
            android.graphics.Bitmap bmp = decodeLocalImage(root.getContext(), url);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                loading.setVisibility(View.GONE);
                iv.setVisibility(View.VISIBLE);
                iv.setOnClickListener(v -> showFullImage(root.getContext(), url));
                return;
            }
        }

        RequestListener<android.graphics.drawable.Drawable> listener =
                new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model,
                                                Target<android.graphics.drawable.Drawable> target,
                                                boolean isFirstResource) {
                        loading.setVisibility(View.GONE);
                        showFailedLayer(root, iv, url, single);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                   Object model,
                                                   Target<android.graphics.drawable.Drawable> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        loading.setVisibility(View.GONE);
                        iv.setVisibility(View.VISIBLE);
                        return false;
                    }
                };
        try {
            // 15s 超时：加载挂起（网络慢/URI 无效）时触发 onLoadFailed，避免无限转圈
            Glide.with(root.getContext()).load(url)
                    .timeout(15000)
                    .listener(listener)
                    .into(iv);
        } catch (Exception e) {
            loading.setVisibility(View.GONE);
            showFailedLayer(root, iv, url, single);
        }
        iv.setOnClickListener(v -> showFullImage(root.getContext(), url));
    }

    /** 失败提示层：点击重新加载 */
    private void showFailedLayer(FrameLayout root, ImageView iv, String url, boolean single) {
        // 移除旧的失败层
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) root.removeViewAt(i);
        }
        TextView failed = new TextView(root.getContext());
        failed.setText("⚠ 图片加载失败\n点击重试");
        failed.setTextSize(11);
        failed.setTextColor(0xFF94A3B8);
        failed.setGravity(Gravity.CENTER);
        failed.setBackgroundColor(0xFFF1F5F9);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                single ? dp(root.getContext(), 120) : FrameLayout.LayoutParams.MATCH_PARENT);
        failed.setLayoutParams(lp);
        failed.setClickable(true);
        failed.setOnClickListener(v -> {
            root.removeView(failed);
            loadWithFeedback(root, iv, findLoading(root), url, single);
        });
        root.addView(failed);
    }

    private ProgressBar findLoading(FrameLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ProgressBar) return (ProgressBar) child;
        }
        ProgressBar loading = new ProgressBar(root.getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(root.getContext(), 20), dp(root.getContext(), 20), Gravity.CENTER);
        root.addView(loading, lp);
        return loading;
    }

    /** 全屏查看大图（PhotoView 支持双指缩放） */
    private void showFullImage(Context context, String url) {
        try {
            // Dialog 需要 Activity 上下文；Application context 下无法弹窗
            if (!(context instanceof android.app.Activity)) return;

            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(Color.BLACK);

            PhotoView photoView = new PhotoView(context);
            photoView.setBackgroundColor(Color.BLACK);
            root.addView(photoView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            ProgressBar loading = new ProgressBar(context);
            FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            root.addView(loading, loadingLp);

            dialog.setContentView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            photoView.setOnClickListener(v -> dialog.dismiss());
            // 先 show 确保 window 非 null，再设置背景（避免 getWindow() 空指针）
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            }

            // show 之后再加载（View 已 attach），loading 占位 + 失败提示
            // 本地图片优先 BitmapFactory 直接解码（file:// / / 开头 / content://，避免 Glide 对本地 URI 不回调转圈）
            android.graphics.Bitmap localBmp = decodeLocalImage(context, url);
            if (localBmp != null) {
                photoView.setImageBitmap(localBmp);
                loading.setVisibility(View.GONE);
            } else {
                RequestListener<android.graphics.drawable.Drawable> listener =
                        new RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(GlideException e, Object model,
                                                        Target<android.graphics.drawable.Drawable> target,
                                                        boolean isFirstResource) {
                                loading.setVisibility(View.GONE);
                                android.widget.Toast.makeText(context, "图片加载失败", android.widget.Toast.LENGTH_SHORT).show();
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                           Object model,
                                                           Target<android.graphics.drawable.Drawable> target,
                                                           DataSource dataSource, boolean isFirstResource) {
                                loading.setVisibility(View.GONE);
                                return false;
                            }
                        };
                Glide.with(context).load(url).timeout(15000).listener(listener).into(photoView);
            }
        } catch (Exception e) {
            android.util.Log.w("ImageGridCardView", "showFullImage failed: " + e.getMessage());
        }
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
