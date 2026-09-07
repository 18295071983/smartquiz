import com.oilquiz.app.theme.mcu.hct.Hct;
import com.oilquiz.app.theme.mcu.scheme.Scheme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 生成 SmartQuiz 动态主题 overlay（values + values-night）。
 * 色板由 Google material-color-utilities (Apache-2.0) 的 HCT 算法生成。
 *
 * <p>两类 overlay：
 * <ol>
 *   <li>7 预设精确色（ThemeOverlay.SmartQuiz.{Blue,Green,...}）；</li>
 *   <li>24 色相网格（ThemeOverlay.SmartQuiz.Hue00~Hue23，每 15° 一档，chroma=50/tone=50），
 *       供自定义色 / 皮肤扩展色就近映射，实现 XML 体系全量变色。</li>
 * </ol>
 *
 * 运行: javac -encoding UTF-8 -d out &lt;mcu 源码&gt; GenOverlays.java && java -cp out GenOverlays &lt;res 根目录&gt;
 */
public class GenOverlays {

    /** 色相网格数量：360° / 24 = 每档 15° */
    static final int HUE_STEPS = 24;
    /** 色相网格种子色的 chroma / tone（HCT 空间，中等饱和度/明度） */
    static final double GRID_CHROMA = 50.0;
    static final double GRID_TONE = 50.0;

    static class Preset {
        final String resName; // style 资源名后缀
        final int argb;
        Preset(String resName, int argb) { this.resName = resName; this.argb = argb; }
    }

    static final Preset[] PRESETS = {
            new Preset("Blue",   0xFF3B82F6),
            new Preset("Green",  0xFF10B981),
            new Preset("Purple", 0xFF8B5CF6),
            new Preset("Orange", 0xFFF97316),
            new Preset("Pink",   0xFFEC4899),
            new Preset("Teal",   0xFF14B8A6),
            new Preset("Indigo", 0xFF6366F1),
    };

    static String hex(int argb) {
        return String.format("#%06X", 0xFFFFFF & argb);
    }

    static void writeStyle(StringBuilder sb, String name, Scheme s) {
        sb.append("    <style name=\"").append(name).append("\" parent=\"\">\n");
        sb.append("        <item name=\"colorPrimary\">").append(hex(s.getPrimary())).append("</item>\n");
        sb.append("        <item name=\"colorPrimaryVariant\">").append(hex(s.getPrimary())).append("</item>\n");
        sb.append("        <item name=\"colorOnPrimary\">").append(hex(s.getOnPrimary())).append("</item>\n");
        sb.append("        <item name=\"colorPrimaryContainer\">").append(hex(s.getPrimaryContainer())).append("</item>\n");
        sb.append("        <item name=\"colorOnPrimaryContainer\">").append(hex(s.getOnPrimaryContainer())).append("</item>\n");
        sb.append("        <item name=\"colorSecondary\">").append(hex(s.getSecondary())).append("</item>\n");
        sb.append("        <item name=\"colorSecondaryContainer\">").append(hex(s.getSecondaryContainer())).append("</item>\n");
        sb.append("        <item name=\"colorOnSecondary\">").append(hex(s.getOnSecondary())).append("</item>\n");
        sb.append("        <item name=\"colorOnSecondaryContainer\">").append(hex(s.getOnSecondaryContainer())).append("</item>\n");
        sb.append("        <item name=\"colorTertiary\">").append(hex(s.getTertiary())).append("</item>\n");
        sb.append("        <item name=\"colorTertiaryContainer\">").append(hex(s.getTertiaryContainer())).append("</item>\n");
        sb.append("        <item name=\"colorOnTertiary\">").append(hex(s.getOnTertiary())).append("</item>\n");
        sb.append("        <item name=\"colorOnTertiaryContainer\">").append(hex(s.getOnTertiaryContainer())).append("</item>\n");
        sb.append("        <item name=\"colorSurface\">").append(hex(s.getSurface())).append("</item>\n");
        sb.append("        <item name=\"colorOnSurface\">").append(hex(s.getOnSurface())).append("</item>\n");
        sb.append("        <item name=\"colorSurfaceVariant\">").append(hex(s.getSurfaceVariant())).append("</item>\n");
        sb.append("        <item name=\"colorOnSurfaceVariant\">").append(hex(s.getOnSurfaceVariant())).append("</item>\n");
        sb.append("        <item name=\"colorOutline\">").append(hex(s.getOutline())).append("</item>\n");
        sb.append("        <item name=\"colorBackground\">").append(hex(s.getBackground())).append("</item>\n");
        sb.append("        <item name=\"colorOnBackground\">").append(hex(s.getOnBackground())).append("</item>\n");
        sb.append("        <item name=\"android:statusBarColor\">").append(hex(s.getPrimary())).append("</item>\n");
        sb.append("        <item name=\"android:navigationBarColor\">").append(hex(s.getSurface())).append("</item>\n");
        sb.append("    </style>\n");
    }

    public static void main(String[] args) throws Exception {
        StringBuilder light = new StringBuilder();
        StringBuilder dark = new StringBuilder();
        light.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        light.append("<resources>\n");
        light.append("    <!-- 动态主题 overlay（浅色）— 由 material-color-utilities HCT 算法生成，勿手改 -->\n");
        dark.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        dark.append("<resources>\n");
        dark.append("    <!-- 动态主题 overlay（深色）— 由 material-color-utilities HCT 算法生成，勿手改 -->\n");
        for (Preset p : PRESETS) {
            writeStyle(light, "ThemeOverlay.SmartQuiz." + p.resName, Scheme.light(p.argb));
            writeStyle(dark, "ThemeOverlay.SmartQuiz." + p.resName, Scheme.dark(p.argb));
        }
        // 24 色相网格（自定义色 / 皮肤扩展色就近映射）
        for (int i = 0; i < HUE_STEPS; i++) {
            String suffix = String.format("Hue%02d", i);
            int seed = Hct.from(i * (360.0 / HUE_STEPS), GRID_CHROMA, GRID_TONE).toInt();
            writeStyle(light, "ThemeOverlay.SmartQuiz." + suffix, Scheme.light(seed));
            writeStyle(dark, "ThemeOverlay.SmartQuiz." + suffix, Scheme.dark(seed));
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
