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
        registerRenderer(ContentType.MARKDOWN, new MarkdownContentRenderer());
        registerRenderer(ContentType.PLAIN_TEXT, new MarkdownContentRenderer());
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

        // 1. 检查缓存（包含宽度作为缓存key的一部分）
        String cacheKey = content + "_w" + availableWidth;
        Spanned cached = renderCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 内容类型检测与切分
        List<ContentSegment> segments = ContentTypeDetector.detect(content);

        // 3. 分段渲染并拼接
        SpannableStringBuilder result = new SpannableStringBuilder();
        for (ContentSegment segment : segments) {
            Spanned rendered = renderSegment(segment, context, availableWidth);
            if (rendered != null && rendered.length() > 0) {
                result.append(rendered);
            }
        }

        // 4. 缓存结果
        renderCache.put(cacheKey, result);

        return result;
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
