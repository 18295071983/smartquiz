package com.oilquiz.app.ai.chat;

/**
 * 滚动锚点状态（纯逻辑，无 Android 依赖，可 JVM 单测）——2026-10-25。
 *
 * <p>解决的问题：RecyclerView 的 LinearLayoutManager 在布局变化时只按"像素偏移"保位；
 * 视口<b>上方</b>的消息高度变化（思考展开/收起、WebView 异步测高、工具卡展开）会让可见内容
 * 整体上下跳动。Web 有 CSS {@code overflow-anchor}，Android 没有，只能自己记"第一可见项
 * position + top"并在布局后补偿 {@code scrollBy(dy)}。</p>
 *
 * <p>本类只负责"要不要补、补多少"：</p>
 * <ul>
 *   <li>记录锚点：{@link #capture(int, int, int, String)}（position / top / item 总数 / 稳定键）；</li>
 *   <li>判定：{@link #computeDelta(int, int, String)} 返回需要补偿的 dy；item 数变化或
 *       同 position 换了一条消息（稳定键不同）时返回 {@link #RECAPTURE}，调用方重捕；</li>
 *   <li>补偿后 {@link #applyRestore(int)} 同步锚点，避免下一帧重复补偿。</li>
 * </ul>
 *
 * <p>真正的滚动作业在 {@link ScrollAnchorKeeper}，本类保持可单测。</p>
 */
public final class ScrollAnchorState {

    /** 需要重捕锚点（数据形态变了，或还没有锚点）。 */
    public static final int RECAPTURE = Integer.MIN_VALUE;

    private final int tolerancePx;
    private int position = -1;
    private int top;
    private int itemCount = -1;
    private String stableKey;
    private boolean valid;

    public ScrollAnchorState(int tolerancePx) {
        this.tolerancePx = Math.max(0, tolerancePx);
    }

    /** 记录当前锚点（第一可见项）。 */
    public void capture(int position, int top, int itemCount, String stableKey) {
        this.position = position;
        this.top = top;
        this.itemCount = itemCount;
        this.stableKey = stableKey;
        this.valid = position >= 0;
    }

    public void invalidate() {
        valid = false;
    }

    public boolean isValid() {
        return valid;
    }

    public int position() {
        return position;
    }

    public int count() {
        return itemCount;
    }

    public String stableKey() {
        return stableKey;
    }

    public int anchorTop() {
        return top;
    }

    /**
     * 计算本次布局后需要的补偿位移。
     *
     * @param currentTop   同一 position 的当前 {@code getTop()}
     * @param currentCount 当前 item 总数
     * @param currentKey   当前 position 的稳定键（可为 null）
     * @return {@code 0} = 不用补；正/负 = {@code scrollBy(0, dy)} 的 dy；
     *         {@link #RECAPTURE} = 锚点不适用（item 数变了 / 同 position 换了消息 / 尚无锚点）
     */
    public int computeDelta(int currentTop, int currentCount, String currentKey) {
        if (!valid) {
            return RECAPTURE;
        }
        if (currentCount != itemCount) {
            return RECAPTURE;
        }
        boolean hasKey = stableKey != null || currentKey != null;
        if (hasKey && !java.util.Objects.equals(stableKey, currentKey)) {
            return RECAPTURE;
        }
        int delta = currentTop - top;
        return Math.abs(delta) <= tolerancePx ? 0 : delta;
    }

    /**
     * 补偿已执行：调用方按 {@code scrollBy(0, delta)} 后，锚点 top 理论上回到原值。
     * 这里同步状态，避免下一帧把同一次变化再补一遍。
     */
    public void applyRestore(int delta) {
        if (valid) {
            top += delta;
        }
    }
}
