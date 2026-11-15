package com.oilquiz.app.ai.tool;

/**
 * 相对时间文案（2026-11-16）。
 *
 * <p>为什么单独抽出来：连接页的「最近探测 HH:mm:ss」和扩展面板的「已更新 HH:mm:ss」
 * 都是写死的时间戳，页面静止时看起来像卡死（用户原话："看着像时间卡死"）。
 * 改成每秒重算的相对时间后，"刚探测"还是"30 秒没刷新"一眼能看出来。</p>
 */
public final class RemoteTimeText {

    private RemoteTimeText() {
    }

    /**
     * 距今多久的人话描述。
     *
     * @param ageMs 距今毫秒（负数/0 按 0 处理）
     * @return {@code 0 秒前} / {@code 37 秒前} / {@code 3 分钟前}
     */
    public static String ago(long ageMs) {
        long sec = Math.max(0, ageMs / 1000);
        if (sec < 60) {
            return sec + " 秒前";
        }
        return (sec / 60) + " 分钟前";
    }

    /** 连接页探测行：{@code 最近探测 12 秒前（45ms）}。 */
    public static String probeLine(long ageMs, long durationMs) {
        return "最近探测 " + ago(ageMs) + "（" + Math.max(0, durationMs) + "ms）";
    }

    /** 扩展面板状态后缀：{@code 12 秒前}（调用方自己拼接）。 */
    public static String statusSuffix(long ageMs) {
        return ago(ageMs);
    }
}
