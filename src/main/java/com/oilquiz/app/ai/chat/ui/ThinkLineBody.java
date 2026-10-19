package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

import com.oilquiz.app.R;

/**
 * 聊天页 v2 思考正文（demo {@code .think-body}）。
 *
 * <p>{@code ChatAdapter} 的折叠逻辑就是 {@code thinkingContent.setVisibility(...)}，
 * 所以"展开态"这件事在这里是唯一可信的：正文显了，箭头就转 90°；正文藏了，箭头回正。
 * 这样不需要在 ChatAdapter 的每个点击回调里再补一句"顺便转一下箭头"。
 *
 * <p>箭头找不到（复用到别的布局里）时静默跳过，本类不假设自己一定在 think-line 里。
 */
public class ThinkLineBody extends AppCompatTextView {

    private View chevron;
    private boolean chevronResolved;

    public ThinkLineBody(Context context) {
        super(context);
    }

    public ThinkLineBody(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThinkLineBody(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        rotateChevron(visibility == VISIBLE);
    }

    private void rotateChevron(boolean expanded) {
        if (!chevronResolved) {
            chevronResolved = true;
            View row = getParent() instanceof View ? (View) getParent() : null;
            // 正文与折叠行是同一个竖直容器的两个子节点
            View section = row != null && row.getParent() instanceof View ? (View) row.getParent() : null;
            chevron = section == null ? null : section.findViewById(R.id.chat_v2_think_chev);
        }
        if (chevron == null) return;
        chevron.animate()
                .rotation(expanded ? 90f : 0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
