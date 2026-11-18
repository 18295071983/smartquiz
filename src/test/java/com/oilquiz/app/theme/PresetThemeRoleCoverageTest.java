package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每套预设 overlay 的 M3/语义 role 覆盖护栏（2026-11-18）。
 *
 * <p>背景：预设 overlay 由 {@code tools/GenOverlays.java} 生成。此前每套只写 32 个 role，
 * 缺 success / warning / info / error 这一组（14 个），只能靠基础主题兜底——一旦以后有人
 * 只改预设、或换了主题注入顺序，这些组件会突然掉回默认紫。这里做"生成产物级"校验：</p>
 * <ol>
 *   <li>light / night 两份 overlay 的 style 名字集合一致（各 31 套）；</li>
 *   <li>每套样式都带齐 {@link #REQUIRED_ROLES}（46 个），缺一个就报出样式名与 role 名；</li>
 *   <li>同一套样式内不允许重复定义同一个 item（后写会静默覆盖先写）；</li>
 *   <li>每套都有五档 surfaceContainer（防"容器塌成 2 档"回归）。</li>
 * </ol>
 */
public class PresetThemeRoleCoverageTest {

    private static final Path LIGHT = Paths.get("src/main/res/values/theme_overlays.xml");
    private static final Path NIGHT = Paths.get("src/main/res/values-night/theme_overlays.xml");

    private static final Pattern STYLE = Pattern.compile(
            "<style\\s+name=\"([^\"]+)\"[^>]*>(.*?)</style>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile("<item\\s+name=\"([^\"]+)\"");

    /** 每套预设必须齐的 role（M3 46 个；图表系列色与 App 语义别名不在此列）。 */
    private static final List<String> REQUIRED_ROLES = Arrays.asList(
            "colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer",
            "colorSecondary", "colorOnSecondary", "colorSecondaryContainer", "colorOnSecondaryContainer",
            "colorTertiary", "colorOnTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
            "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
            "colorSurface", "colorOnSurface", "colorSurfaceVariant", "colorOnSurfaceVariant",
            "colorSurfaceDim", "colorSurfaceBright",
            "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
            "colorSurfaceContainerHigh", "colorSurfaceContainerHighest",
            "colorSurfaceTint", "colorOutline", "colorOutlineVariant",
            "colorInverseSurface", "colorInverseOnSurface", "colorInversePrimary",
            "colorScrim",
            "colorSuccess", "colorOnSuccess", "colorSuccessContainer", "colorOnSuccessContainer",
            "colorWarning", "colorOnWarning", "colorWarningContainer", "colorOnWarningContainer",
            "colorInfo", "colorOnInfo", "colorInfoContainer", "colorOnInfoContainer");

    @Test
    public void everyPresetCarriesAllM3AndSemanticRoles() throws IOException {
        Map<String, List<String>> light = parse(LIGHT);
        Map<String, List<String>> night = parse(NIGHT);

        assertEquals("预设数量应与生成器一致（7 预设 + 24 色相网格）", 31, light.size());
        assertEquals("light/night 的预设样式名必须一一对应",
                new HashSet<>(light.keySet()), new HashSet<>(night.keySet()));

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : light.entrySet()) {
            checkStyle(e.getKey(), e.getValue(), "values", problems);
        }
        for (Map.Entry<String, List<String>> e : night.entrySet()) {
            checkStyle(e.getKey(), e.getValue(), "values-night", problems);
        }
        assertTrue("预设 overlay role 覆盖不完整：\n  " + String.join("\n  ", problems),
                problems.isEmpty());
    }

    private static void checkStyle(String style, List<String> items, String dir,
                                   List<String> problems) {
        Set<String> unique = new HashSet<>(items);
        if (unique.size() != items.size()) {
            Map<String, Integer> counts = new HashMap<>();
            for (String item : items) {
                counts.merge(item, 1, Integer::sum);
            }
            List<String> dup = new ArrayList<>();
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                if (c.getValue() > 1) {
                    dup.add(c.getKey() + " x" + c.getValue());
                }
            }
            problems.add(dir + " / " + style + " 重复定义: " + dup);
        }
        List<String> missing = new ArrayList<>();
        for (String role : REQUIRED_ROLES) {
            if (!unique.contains(role)) {
                missing.add(role);
            }
        }
        if (!missing.isEmpty()) {
            problems.add(dir + " / " + style + " 缺 " + missing.size() + " 个 role: " + missing);
        }
        int containers = 0;
        for (String role : unique) {
            if (role.startsWith("colorSurfaceContainer")) {
                containers++;
            }
        }
        if (containers != 5) {
            problems.add(dir + " / " + style + " surfaceContainer 档位 = " + containers + "（应为 5）");
        }
    }

    private static Map<String, List<String>> parse(Path path) throws IOException {
        assertTrue("找不到生成产物: " + path, Files.exists(path));
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        text = text.replaceAll("(?s)<!--.*?-->", "");
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = STYLE.matcher(text);
        while (m.find()) {
            List<String> items = new ArrayList<>();
            Matcher im = ITEM.matcher(m.group(2));
            while (im.find()) {
                items.add(im.group(1));
            }
            out.put(m.group(1), items);
        }
        return out;
    }
}
