package com.oilquiz.app.ai.chat.parts;

import android.view.View;
import android.view.ViewGroup;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import java.util.List;

/**
 * 单个 {@link AssistantTurn.Part} 的渲染器（parts 架构升级 P2，2026-10-21）。
 *
 * <p>设计对齐业界 parts 渲染：按类型注册、按稳定 id 复用视图、按内容指纹决定是否重绑。
 * renderer 本身只负责"一类 part ↔ 一个视图"的创建/绑定/释放，增量复用与顺序对齐由
 * {@link TurnPartsHost} 统一调度，renderer 不需要（也不允许）自己去 {@code removeAllViews}。</p>
 *
 * <p>说明：文档 §4.2 给的示例签名是 {@code createView(Context, ViewGroup)}。这里改为
 * {@code createView(PartHost, ViewGroup)}——因为同一类文本 part 走 WebView 还是原生 TextView
 * 取决于宿主开关与可用宽度，创建时就得分派，否则 createView 出来的视图类型与 bindView 期望
 * 不一致。属于对"建议"签名的合理收紧，不改变接口语义。</p>
 */
public interface PartRenderer {

    /** 该 renderer 是否能渲染这个 part（一般按 {@code part.type} 判断）。 */
    boolean canRender(AssistantTurn.Part part);

    /**
     * 为该 part 创建一个视图。实现方应把 LayoutParams 设为宽度 {@code MATCH_PARENT}、
     * 高度 {@code WRAP_CONTENT}；是否用 WebView 由 {@link PartHost#webMarkdownEnabled()} 决定。
     *
     * @param host   渲染环境
     * @param parent 目标容器（addView 由 {@link TurnPartsHost} 负责，这里只用于生成正确的 LayoutParams）
     */
    View createView(PartHost host, ViewGroup parent);

    /** 把 part 的内容绑定到已存在的视图上。仅在内容指纹变化时由引擎调用。 */
    void bindView(View view, AssistantTurn.Part part, PartHost host);

    /** 释放视图持有的重资源（WebView 缓存、播放器等）。删除视图前由引擎调用。 */
    void releaseView(View view);

    /**
     * 该 part 是否"不拥有独立视图"，而是写进某个兄弟 part（anchor）已有的视图里。
     * 返回 anchor 的 part id；返回 null（默认）表示它拥有自己的视图。
     *
     * <p>对应文档 §4.6 工具卡归属：{@code tool_result} 只把结果写进所属 {@code tool_call} 卡、
     * {@code data}/产物卡挂在所属工具卡下，<b>不新建第二张卡</b>。引擎据此把该 part 的
     * {@link #bindView} 指到 anchor 的视图上（view 参数即 anchor 视图），并保证其 anchor 视图
     * 重建时强制重绑。renderer 自己按 {@code orderedParts} 找归属（如向前找最近的同 toolCallId
     * 的 tool_call）。</p>
     *
     * <p>P2 无实际 attached renderer（tool/data 在 P3），但引擎与契约先支持并用假 renderer 单测钉死，
     * 避免 P3 加 tool_result 时才被迫改成双卡或另起机制。</p>
     */
    default String anchorIdFor(AssistantTurn.Part part, List<AssistantTurn.Part> orderedParts) {
        return null;
    }
}
