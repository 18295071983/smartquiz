package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * 聊天页 v2 思考行的标签（demo {@code .think-line > span}）。
 *
 * <p>存在的唯一理由：{@code ChatAdapter} 里那一整套思考区逻辑（约 20 处调用）
 * 只认 {@code thinkingLabel} 这一个 TextView，折叠/展开、有思考/无思考都靠
 * {@code setVisibility} 切它。v2 把标签装进"图标 + 文字 + 箭头"的一行里之后，
 * 只藏文字会留下一行孤零零的图标和箭头，所以这里把可见性转发给整行。
 *
 * <p>不改 {@code ChatAdapter} 的任何调用点，也不给它加 v2 分支。
 */
public class ThinkLineLabel extends AppCompatTextView {

    public ThinkLineLabel(Context context) {
        super(context);
    }

    public ThinkLineLabel(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThinkLineLabel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (getParent() instanceof View) {
            ((View) getParent()).setVisibility(visibility);
        }
    }

    /**
     * 折叠点击挂在整行上（demo 的 {@code .think-line} 就是一整条按钮），
     * 但 {@code ChatAdapter} 是把 listener 设到标签这个 TextView 上的，
     * 所以这里同时转给行容器，让图标和箭头那两块也点得动。
     */
    @Override
    public void setOnClickListener(OnClickListener listener) {
        super.setOnClickListener(listener);
        if (getParent() instanceof View) {
            ((View) getParent()).setOnClickListener(listener);
        }
    }
}
