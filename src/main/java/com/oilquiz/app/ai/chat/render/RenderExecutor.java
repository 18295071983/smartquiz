package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染执行器：AI 消息主气泡的核心渲染引擎。
 *
 * 职责：
 * 1. 内容类型识别：通过 {@link ContentTypeDetector} 将原始内容切分为多个 {@link ContentSegment}
 * 2. 渲染策略决策：根据片段类型匹配优先级最高的 {@link ContentRenderer}
 * 3. 子组件调度：按顺序调用各渲染器，管理其生命周期
 * 4. 结果拼接：将各片段的渲染结果合并为单一 Spanned
 * 5. 性能优化：LRU 缓存已渲染结果，避免 RecyclerView 滚动时重复渲染
 *
 * 架构：
 * <pre>
 * 原始内容
 *    │
 *    ▼
 * ContentTypeDetector.detect()  ──→  List<ContentSegment>
 *    │
 *    ▼
 * RenderExecutor.execute()
 *    │  ├─ 对每个 segment 匹配 ContentRenderer（按优先级）
 *    │  ├─ 调用 renderer.render() 获得片段 Spanned
 *    │  └─ 拼接所有片段
 *    │
 *    ▼
 * 最终 Spanned（设置到 TextView）
 * </pre>
 *
 * 扩展：
 * - 新增渲染器：实现 {@link ContentRenderer} 接口，在 {@link #initRenderers()} 中注册
 * - 新增内容类型：在 {@link ContentType} 枚举和 {@link ContentTypeDetector} 中添加检测规则
 */
public class RenderExecutor {

    private static volatile RenderExecutor instance;

    /** 按内容类型分组的渲染器列表（已按优先级降序排列） */
    private final Map<ContentType, List<ContentRenderer>> rendererMap = new HashMap<>();

    /** Markdown 兜底渲染器（当没有匹配的专用渲染器时使用） */
    private final ContentRenderer fallbackRenderer = new MarkdownContentRenderer();

    /** 渲染结果 LRU 缓存（key = content hashcode，避免滚动时重复渲染） */
    private static final int CACHE_SIZE = 300;
    private final LruCache<String, Spanned> renderCache = new LruCache<>(CACHE_SIZE);

    private RenderExecutor() {
        initRenderers();
    }

    /** 获取单例实例 */
    public static RenderExecutor getInstance() {
        if (instance == null) {
            synchronized (RenderExecutor.class) {
                if (instance == null) {
                    instance = new RenderExecutor();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化并注册所有渲染器。
     *
     * 注册顺序不影响优先级——优先级由 {@link ContentRenderer#getPriority()} 决定。
     * 同一类型的多个渲染器按优先级降序排列，第一个 canRender() 返回 true 的被选中。
     */
    private void initRenderers() {
        // Mermaid 图表
        registerRenderer(ContentType.MERMAID, new MermaidContentRenderer());

        // 数学公式（块级 + 行内）
        registerRenderer(ContentType.MATH_BLOCK, new MathContentRenderer(true));
        registerRenderer(ContentType.MATH_INLINE, new MathContentRenderer(false));

        // HTML 富文本
        registerRenderer(ContentType.HTML_BLOCK, new HtmlContentRenderer());

        // 标准 Markdown（兜底）
        // 说明（2026-10-09）：不再单独注册 PLAIN_TEXT —— ContentTypeDetector 只在**空内容**时
        // 产出 PLAIN_TEXT（detect() 的空串分支），而空内容在 execute() 里已被
        // `rendered.length() > 0` 过滤，那条注册从未被用到。
        // 万一日后真有 PLAIN_TEXT 片段，它仍会走 fallbackRenderer（MarkdownContentRenderer），
        // 行为与原来一致。
        registerRenderer(ContentType.MARKDOWN, new MarkdownContentRenderer());
    }

    /** 注册渲染器到指定内容类型 */
    private void registerRenderer(ContentType type, ContentRenderer renderer) {
        rendererMap.computeIfAbsent(type, k -> new ArrayList<>()).add(renderer);
        // 按优先级降序排列
        rendererMap.get(type).sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    /**
     * 执行完整渲染流程：内容检测 → 分段 → 匹配渲染器 → 拼接结果。
     *
     * @param content 原始消息内容（Markdown + LaTeX + Mermaid + HTML 混合）
     * @param context Android Context
     * @return 渲染后的 Spanned
     */
    public Spanned execute(String content, Context context) {
        return execute(content, context, 0);
    }

    /**
     * 执行完整渲染流程：内容检测 → 分段 → 匹配渲染器 → 拼接结果。
     *
     * @param content 原始消息内容（Markdown + LaTeX + Mermaid + HTML 混合）
     * @param context Android Context
     * @param availableWidth 实际可用宽度（像素），0表示不限制
     * @return 渲染后的 Spanned
     */
    public Spanned execute(String content, Context context, int availableWidth) {
        if (content == null || content.isEmpty()) {
            return new SpannableStringBuilder("");
        }

        // 1. 检查整条消息缓存（快速路径）
        String cacheKey = content + "_w" + availableWidth;
        Spanned cached = renderCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 内容类型检测与切分
        List<ContentSegment> segments = ContentTypeDetector.detect(content);

        // 3. 分段渲染并拼接
        // SEGMENT-MEMO(2026-10-09)：**已定型的段直接复用渲染结果，不再重算**。
        //
        // 为什么需要：流式对话每 50~200ms 刷新一次（实测一条回复刷了 64 次），而上面那条
        // 整条消息缓存 key 里含完整正文 —— 只要有新 token 就必然 miss，于是**前面所有已定型的
        // 段落每 150ms 被重新解析、span 全部重建**。表格是最大的受害者：它一个段就含几十个
        // TableRowSpan，而 TableRowSpan 是**跨帧有状态**的 ReplacementSpan（宽度在上次 draw
        // 测得、高度在上次 getSize 得出），被整体重建 60+ 次且内容还在变 → 行按旧高度定位、
        // 新内容按新行数绘制 → **表格内文字互相覆盖、越生成越糊**。
        //
        // 按段缓存后：流式追加只改变最后一个段，其余段全部命中缓存；
        // 表格一旦定型就永不重建。计算量从 O(全文×刷新次数) 降到 O(新增)+O(刷新次数)。
        SpannableStringBuilder result = new SpannableStringBuilder();
        for (int si = 0; si < segments.size(); si++) {
            ContentSegment segment = segments.get(si);
            // 最后一个段不缓存：流式追加改的就是它，若把它的每个中间态都写进缓存，
            // 会迅速挤爆 segmentCache，把"已定型段"的条目淘汰掉 —— 反而更慢。
            // 表格这类重结构一旦定型就成了前面的段，稳定命中。
            boolean cacheable = si < segments.size() - 1;
            Spanned rendered = cacheable ? cachedSegment(segment, context, availableWidth) : null;
            if (rendered == null) {
                rendered = renderSegment(segment, context, availableWidth);
                if (cacheable && rendered != null && rendered.length() > 0) {
                    segmentCache.put(segmentCacheKey(segment, availableWidth), rendered);
                }
            }
            if (rendered != null && rendered.length() > 0) {
                result.append(rendered);
            }
        }

        // 4. 缓存结果
        renderCache.put(cacheKey, result);

        return result;
    }

    /** 渲染结果按段缓存（定型段复用；表格/代码块/公式等重结构受益最大） */
    private static final int SEGMENT_CACHE_SIZE = 600;
    private final LruCache<String, Spanned> segmentCache = new LruCache<>(SEGMENT_CACHE_SIZE);

    /** 段缓存 key：类型 + 段文本 + 宽度。宽度并入 key 是必要的 —— Mermaid/公式渲染可能按宽度生成 */
    private static String segmentCacheKey(ContentSegment segment, int availableWidth) {
        return segment.type + "\u0000" + availableWidth + "\u0000"
                + (segment.text == null ? "" : segment.text);
    }

    /** 取已缓存的段渲染结果；未命中返回 null */
    private Spanned cachedSegment(ContentSegment segment, Context context, int availableWidth) {
        if (segment == null || segment.text == null || segment.text.isEmpty()) {
            return null;
        }
        return segmentCache.get(segmentCacheKey(segment, availableWidth));
    }

    /** 清空段缓存（与整条缓存一起清） */
    private void clearSegmentCache() {
        segmentCache.evictAll();
    }

    /**
     * 渲染单个内容片段：匹配优先级最高的渲染器并执行渲染。
     */
    private Spanned renderSegment(ContentSegment segment, Context context) {
        return renderSegment(segment, context, 0);
    }

    /**
     * 渲染单个内容片段：匹配优先级最高的渲染器并执行渲染。
     * @param segment 内容片段
     * @param context Android Context
     * @param availableWidth 实际可用宽度（像素），0表示不限制
     */
    private Spanned renderSegment(ContentSegment segment, Context context, int availableWidth) {
        List<ContentRenderer> renderers = rendererMap.get(segment.type);

        if (renderers != null) {
            for (ContentRenderer renderer : renderers) {
                if (renderer.canRender(segment.text)) {
                    try {
                        return renderer.render(segment.text, context, availableWidth);
                    } catch (Exception e) {
                        // 渲染失败，降级到 fallback
                        break;
                    }
                }
            }
        }

        // 降级：使用 Markdown 兜底渲染器
        try {
            return fallbackRenderer.render(segment.text, context, availableWidth);
        } catch (Exception e) {
            return new SpannableStringBuilder(segment.text);
        }
    }

    /** 清空渲染缓存（在消息被删除或会话切换时调用） */
    public void clearCache() {
        renderCache.evictAll();
        clearSegmentCache();
    }

    /**
     * 获取已注册的所有渲染器信息（用于调试和文档）。
     */
    public String getRendererInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RenderExecutor 注册的渲染器 ===\n");
        for (Map.Entry<ContentType, List<ContentRenderer>> entry : rendererMap.entrySet()) {
            sb.append(entry.getKey()).append(":\n");
            for (ContentRenderer r : entry.getValue()) {
                sb.append("  - ").append(r.getName())
                  .append(" (priority=").append(r.getPriority()).append(")\n");
            }
        }
        return sb.toString();
    }
}
