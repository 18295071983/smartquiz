import com.oilquiz.app.theme.ThemePalette;
import com.oilquiz.app.theme.ThemePaletteProvider;
import com.oilquiz.app.theme.mcu.hct.Hct;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 生成 SmartQuiz 动态主题 overlay（values + values-night）。
 *
 * <p>色板由 Google material-color-utilities（Apache-2.0）的 HCT 算法生成，入口是
 * {@link ThemePaletteProvider}。本文件是 31 套 overlay 的唯一来源（勿手改输出 XML）：</p>
 * <ol>
 *   <li>7 预设精确色（{@code ThemeOverlay.SmartQuiz.{Blue,Green,...}}）；</li>
 *   <li>24 色相网格（{@code ThemeOverlay.SmartQuiz.Hue00~Hue23}，每 15° 一档，
 *       chroma=50 / tone=50），供自定义色 / 皮肤扩展色就近映射。</li>
 * </ol>
 *
 * <p><b>2026-11-18</b>：补齐 M3 角色（表面 8 档 / outlineVariant / inverse / surfaceTint /
 * 状态层 / 图表网格），这样预设主题不再依赖 {@code ThemeOverlay.App.M3Roles} 的
 * 两档兜底；图表系列色改为跟随 App 固定数据可视化色板（overlay 不再覆盖），
 * 避免容器色（tone 90）被当成数据系列色在白底上看不见。</p>
 *
 * <p>运行（在仓库根目录）：</p>
 * <pre>
 * javac -encoding UTF-8 -d out $(find src/main/java/com/oilquiz/app/theme -name '*.java') tools/GenOverlays.java
 * java -cp out GenOverlays src/main/res
 * </pre>
 */
public class GenOverlays {

    /** 色相网格数量：360° / 24 = 每档 15°。 */
    static final int HUE_STEPS = 24;
    /** 色相网格种子色的 chroma / tone（HCT 空间，中等饱和度/明度）。 */
    static final double GRID_CHROMA = 50.0;
    static final double GRID_TONE = 50.0;

    /** 状态层 alpha（M3：hover 8% / focus 12% / pressed 12% / dragged 16%，禁用 4% / 38%）。 */
    static final int STATE_HOVER = 0x14;
    static final int STATE_FOCUS = 0x1F;
    static final int STATE_PRESSED = 0x1F;
    static final int STATE_DRAGGED = 0x29;
    static final int STATE_DISABLED_CONTAINER = 0x0A;
    static final int STATE_DISABLED_CONTENT = 0x61;

    static class Preset {
        final String resName; // style 资源名后缀
        final int argb;

        Preset(String resName, int argb) {
            this.resName = resName;
            this.argb = argb;
        }
    }

    static final Preset[] PRESETS = {
            new Preset("Blue", 0xFF3B82F6),
            new Preset("Green", 0xFF10B981),
            new Preset("Purple", 0xFF8B5CF6),
            new Preset("Orange", 0xFFF97316),
            new Preset("Pink", 0xFFEC4899),
            new Preset("Teal", 0xFF14B8A6),
            new Preset("Indigo", 0xFF6366F1),
    };

    static String hex(int argb) {
        return String.format("#%06X", 0xFFFFFF & argb);
    }

    static String argb(int alpha, int color) {
        return String.format("#%02X%06X", alpha & 0xFF, 0xFFFFFF & color);
    }

    static void item(StringBuilder sb, String name, String value) {
        sb.append("        <item name=\"").append(name).append("\">")
                .append(value).append("</item>\n");
    }

    static void writeStyle(StringBuilder sb, String name, ThemePalette s, boolean dark) {
        sb.append("    <style name=\"").append(name).append("\" parent=\"\">\n");
        // 品牌：主 / 辅 / 第三色，各带 container + on 色
        item(sb, "colorPrimary", hex(s.primary));
        item(sb, "colorPrimaryVariant", hex(s.primary));
        item(sb, "colorOnPrimary", hex(s.onPrimary));
        item(sb, "colorPrimaryContainer", hex(s.primaryContainer));
        item(sb, "colorOnPrimaryContainer", hex(s.onPrimaryContainer));
        item(sb, "colorSecondary", hex(s.secondary));
        item(sb, "colorSecondaryContainer", hex(s.secondaryContainer));
        item(sb, "colorOnSecondary", hex(s.onSecondary));
        item(sb, "colorOnSecondaryContainer", hex(s.onSecondaryContainer));
        item(sb, "colorTertiary", hex(s.tertiary));
        item(sb, "colorTertiaryContainer", hex(s.tertiaryContainer));
        item(sb, "colorOnTertiary", hex(s.onTertiary));
        item(sb, "colorOnTertiaryContainer", hex(s.onTertiaryContainer));

        // 表面 8 档：surface / 五档 container / dim / bright
        item(sb, "colorSurface", hex(s.surface));
        item(sb, "colorOnSurface", hex(s.onSurface));
        item(sb, "colorSurfaceContainerLowest", hex(s.surfaceContainerLowest));
        item(sb, "colorSurfaceContainerLow", hex(s.surfaceContainerLow));
        item(sb, "colorSurfaceContainer", hex(s.surfaceContainer));
        item(sb, "colorSurfaceContainerHigh", hex(s.surfaceContainerHigh));
        item(sb, "colorSurfaceContainerHighest", hex(s.surfaceContainerHighest));
        item(sb, "colorSurfaceVariant", hex(s.surfaceVariant));
        item(sb, "colorOnSurfaceVariant", hex(s.onSurfaceVariant));
        item(sb, "colorSurfaceDim", hex(s.surfaceDim));
        item(sb, "colorSurfaceBright", hex(s.surfaceBright));
        item(sb, "colorSurfaceTint", hex(s.primary));

        // 描边：组件描边 outline / 分隔线 outlineVariant
        item(sb, "colorOutline", hex(s.outline));
        item(sb, "colorOutlineVariant", hex(s.outlineVariant));

        // 反色面（Snackbar / 反色浮层）
        item(sb, "colorInverseSurface", hex(s.inverseSurface));
        item(sb, "colorInverseOnSurface", hex(s.inverseOnSurface));
        item(sb, "colorInversePrimary", hex(s.inversePrimary));

        item(sb, "colorBackground", hex(s.background));
        item(sb, "colorOnBackground", hex(s.onBackground));

        // App 控件色板（控件底色 + 控件文字色）
        item(sb, "colorControlBackground", hex(s.surfaceVariant));
        item(sb, "colorControlText", hex(s.onSurface));
        item(sb, "colorControlTextSecondary", hex(s.onSurfaceVariant));
        item(sb, "colorControlTextTertiary", hex(s.textTertiary));
        item(sb, "colorControlTextHint", hex(s.onSurfaceVariant));
        item(sb, "colorControlTextDisabled", hex(s.outline));
        item(sb, "colorCardBackground", hex(s.surface));
        item(sb, "colorOnCard", hex(s.onSurface));
        item(sb, "colorInputBackground", hex(s.surfaceVariant));
        item(sb, "colorOnInput", hex(s.onSurface));
        item(sb, "colorDivider", hex(s.outlineVariant));
        item(sb, "colorAppBarBackground", hex(s.surface));
        item(sb, "colorOnAppBar", hex(s.onSurface));

        // 语义色（错误/危险）：成功 / 警告 / 提示不随皮肤变，由 App 固定语义色板提供
        item(sb, "colorDanger", hex(s.error));
        item(sb, "colorOnDanger", hex(s.onError));
        item(sb, "colorErrorContainer", hex(s.errorContainer));
        item(sb, "colorOnErrorContainer", hex(s.onErrorContainer));

        // 2026-11-18：把错误/成功/警告/提示这一组也显式写进预设（此前缺 14 个 role，
        // 只能靠基础主题兜底）。成功/警告/提示是 App 固定语义色，不随皮肤变 —— 直接引用
        // @color/* 资源，values-night 会自动切夜间变体；错误色跟随该预设的 error 色板。
        item(sb, "colorError", hex(s.error));
        item(sb, "colorOnError", hex(s.onError));
        item(sb, "colorSuccess", "@color/success");
        item(sb, "colorOnSuccess", "@color/on_success");
        item(sb, "colorSuccessContainer", "@color/success_container");
        item(sb, "colorOnSuccessContainer", "@color/on_success_container");
        item(sb, "colorWarning", "@color/warning");
        item(sb, "colorOnWarning", "@color/on_warning");
        item(sb, "colorWarningContainer", "@color/warning_container");
        item(sb, "colorOnWarningContainer", "@color/on_warning_container");
        item(sb, "colorInfo", "@color/info_color");
        item(sb, "colorOnInfo", "@color/on_info");
        item(sb, "colorInfoContainer", "@color/info_container");
        item(sb, "colorOnInfoContainer", "@color/on_info_container");

        // App 边框语义：border = 弱边框（outlineVariant），borderStrong = 介于描边与分隔线之间的强边框
        item(sb, "colorBorder", hex(s.outlineVariant));
        item(sb, "colorBorderStrong", hex(s.borderStrong));
        // 遮罩统一为 60% / 70% 黑（以前生成的是不透明 #000000，弹层会把底图盖死）
        item(sb, "colorScrim", dark ? "#B3000000" : "#99000000");

        // 状态层：叠在 onSurface 上的 alpha（跟随该主题的 onSurface 色相）
        item(sb, "colorStateHover", argb(STATE_HOVER, s.onSurface));
        item(sb, "colorStateFocus", argb(STATE_FOCUS, s.onSurface));
        item(sb, "colorStatePressed", argb(STATE_PRESSED, s.onSurface));
        item(sb, "colorStateDragged", argb(STATE_DRAGGED, s.onSurface));
        item(sb, "colorStateDisabledContainer", argb(STATE_DISABLED_CONTAINER, s.onSurface));
        item(sb, "colorStateDisabledContent", argb(STATE_DISABLED_CONTENT, s.onSurface));

        // 图表网格 / 轴线 / 标签：与主题中性色同族；系列色由 App 固定数据可视化色板提供
        item(sb, "colorChartGrid", hex(s.outlineVariant));
        item(sb, "colorChartAxis", hex(s.outline));
        item(sb, "colorChartLabel", hex(s.onSurfaceVariant));

        item(sb, "android:statusBarColor", "#00000000");
        item(sb, "android:navigationBarColor", "#00000000");
        item(sb, "android:windowBackground", hex(s.background));
        sb.append("    </style>\n");
    }

    public static void main(String[] args) throws Exception {
        StringBuilder light = new StringBuilder();
        StringBuilder dark = new StringBuilder();
        light.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        light.append("<resources>\n");
        light.append("    <!-- 动态主题 overlay（浅色）— 由 material-color-utilities HCT 算法生成，勿手改；")
                .append("生成器：tools/GenOverlays.java -->\n");
        dark.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        dark.append("<resources>\n");
        dark.append("    <!-- 动态主题 overlay（深色）— 由 material-color-utilities HCT 算法生成，勿手改；")
                .append("生成器：tools/GenOverlays.java -->\n");
        for (Preset p : PRESETS) {
            writeStyle(light, "ThemeOverlay.SmartQuiz." + p.resName,
                    ThemePaletteProvider.light(p.argb), false);
            writeStyle(dark, "ThemeOverlay.SmartQuiz." + p.resName,
                    ThemePaletteProvider.dark(p.argb), true);
        }
        // 24 色相网格（自定义色 / 皮肤扩展色就近映射）
        for (int i = 0; i < HUE_STEPS; i++) {
            String suffix = String.format("Hue%02d", i);
            int seed = Hct.from(i * (360.0 / HUE_STEPS), GRID_CHROMA, GRID_TONE).toInt();
            writeStyle(light, "ThemeOverlay.SmartQuiz." + suffix,
                    ThemePaletteProvider.light(seed), false);
            writeStyle(dark, "ThemeOverlay.SmartQuiz." + suffix,
                    ThemePaletteProvider.dark(seed), true);
        }
        light.append("</resources>\n");
        dark.append("</resources>\n");

        File base = new File(args.length > 0 ? args[0] : ".");
        write(new File(base, "values/theme_overlays.xml"), light.toString());
        write(new File(base, "values-night/theme_overlays.xml"), dark.toString());
        System.out.println("generated: values/theme_overlays.xml + values-night/theme_overlays.xml");
    }

    static void write(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
