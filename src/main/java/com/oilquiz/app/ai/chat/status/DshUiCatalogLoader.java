package com.oilquiz.app.ai.chat.status;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 甲板配置表（2026-11-13，M3 / P3）。
 *
 * <p>以前 {@code renderDockSlots} 把上限硬编码成 1/2，改一个数字要发版。这里读一份
 * 可覆盖的 JSON（{@code filesDir/dsh_ui_catalog.json}），解析失败/字段缺失一律回退默认值
 * ——配置坏掉绝不能把甲板搞没。</p>
 *
 * <p>格式：</p>
 * <pre>{@code
 * { "version": 1,
 *   "deck": { "max_rows_with_progress": 1, "max_rows_without_progress": 2 } }
 * }</pre>
 */
public final class DshUiCatalogLoader {

    /** 覆盖文件名（放 filesDir 下即生效；不需要发版） */
    public static final String FILE_NAME = "dsh_ui_catalog.json";

    public static final int DEFAULT_MAX_WITH_PROGRESS = 2;
    public static final int DEFAULT_MAX_WITHOUT_PROGRESS = 5;

    /** 硬上限：再多会把聊天区挤没（与既有护栏一致） */
    public static final int HARD_MAX = 6;

    private final int maxRowsWithProgress;
    private final int maxRowsWithoutProgress;
    private final String source;

    private DshUiCatalogLoader(int with, int without, String source) {
        this.maxRowsWithProgress = with;
        this.maxRowsWithoutProgress = without;
        this.source = source;
    }

    public int maxRowsWithProgress() {
        return maxRowsWithProgress;
    }

    public int maxRowsWithoutProgress() {
        return maxRowsWithoutProgress;
    }

    /** 来源描述（"默认值" / 文件路径），放进自检页便于排查 */
    public String source() {
        return source;
    }

    public static DshUiCatalogLoader defaults() {
        return new DshUiCatalogLoader(DEFAULT_MAX_WITH_PROGRESS, DEFAULT_MAX_WITHOUT_PROGRESS, "默认值");
    }

    /** 读覆盖文件；不存在/损坏/字段非法都回退默认值 */
    public static DshUiCatalogLoader load(File file) {
        if (file == null || !file.isFile()) return defaults();
        String json = null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int n = in.read(buf);
            if (n > 0) json = new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new DshUiCatalogLoader(DEFAULT_MAX_WITH_PROGRESS, DEFAULT_MAX_WITHOUT_PROGRESS,
                    "读取失败，已回退默认值: " + e.getMessage());
        }
        DshUiCatalogLoader parsed = parse(json);
        return new DshUiCatalogLoader(parsed.maxRowsWithProgress, parsed.maxRowsWithoutProgress,
                file.getName() + "（" + parsed.source + "）");
    }

    /** 纯解析（单测直接调）：非法一律回退默认值，绝不抛 */
    public static DshUiCatalogLoader parse(String json) {
        if (json == null || json.trim().isEmpty()) return defaults();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject deck = root.optJSONObject("deck");
            if (deck == null) return defaults();
            int with = clamp(deck.optInt("max_rows_with_progress", DEFAULT_MAX_WITH_PROGRESS));
            int without = clamp(deck.optInt("max_rows_without_progress", DEFAULT_MAX_WITHOUT_PROGRESS));
            return new DshUiCatalogLoader(with, without, "配置 v"
                    + root.optInt("version", 1));
        } catch (Exception e) {
            return new DshUiCatalogLoader(DEFAULT_MAX_WITH_PROGRESS, DEFAULT_MAX_WITHOUT_PROGRESS,
                    "解析失败，已回退默认值: " + e.getMessage());
        }
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        return Math.min(v, HARD_MAX);
    }
}
