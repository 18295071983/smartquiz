package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.text.Spanned;

/**
 * 内容渲染器统一接口。
 *
 * 所有渲染子组件实现此接口，由 RenderExecutor 统一调度。
 * 实现类通过 {@link #canRender(String)} 判断是否能处理指定内容，
 * 通过 {@link #render(String, Context)} 执行实际渲染。
 */
public interface ContentRenderer {

    /** 渲染优先级（数值越大优先级越高，先匹配） */
    int getPriority();

    /**
     * 判断此渲染器是否能处理给定内容。
     * RenderExecutor 按优先级从高到低依次调用，第一个返回 true 的渲染器被选中。
     *
     * @param segment 内容片段（已由 ContentTypeDetector 切分）
     * @return true 表示可以渲染此内容
     */
    boolean canRender(String segment);

    /**
     * 渲染内容为 Spanned。
     *
     * @param segment 内容片段
     * @param context Android Context（用于资源访问）
     * @return 渲染后的 Spanned 文本
     */
    Spanned render(String segment, Context context);

    /** 渲染器标识名称（用于日志和调试） */
    String getName();
}
