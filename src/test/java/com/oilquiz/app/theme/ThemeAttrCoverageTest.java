package com.oilquiz.app.theme;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 主题属性覆盖护栏（2026-11-16）。
 *
 * <p><b>为什么需要</b>：布局/drawable 现在大量用 {@code ?attr/colorXxx}（这是跟随换肤的唯一方式），
 * 但 attr 一旦写错或没在主题里定义，运行期会静默解析成 0（透明）或在 inflate 时抛
 * "Failed to resolve attribute"——两个都是真机才暴露的坑（本次会话就踩过
 * {@code R.attr.colorPrimaryVariant} 在 app R 里不存在的问题）。这个测试在 JVM 里把
 * "布局引用的每个 color attr 都必须在主题里定义"钉住。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>扫描 {@code res/layout}、{@code res/drawable}、{@code res/drawable-night} 里所有
 *       {@code ?attr/colorXxx} 引用；</li>
 *   <li>在 {@code res/values/styles.xml}（基础主题，含 {@code Theme.OilQuizApp.Base} 与
 *       {@code Theme.SmartQuiz} 等）里找 {@code <item name="colorXxx">} 定义；</li>
 *   <li>AppCompat/Material 自带的 attr（如 {@code colorPrimaryVariant}）也算已定义——
 *       它们由依赖库声明；</li>
 *   <li>任何"既不在 app 主题、也不是已知库 attr"的引用都算失败。</li>
 * </ul>
 */
public class ThemeAttrCoverageTest {

    /** 布局/drawable 里的 `?attr/colorXxx` 引用。 */
    private static final Pattern ATTR_REF = Pattern.compile("\\?attr/(color[A-Za-z0-9_]+)");

    /** 主题里的 attr 定义。 */
    private static final Pattern ATTR_DEF = Pattern.compile("<item name=\"(color[A-Za-z0-9_]+)\">");

    /**
     * AppCompat / Material 组件库声明的 color attr（不在 app 的 styles.xml 里定义，
     * 但主题里可用；这里按已知集合放行，新增时要写下为什么）。
     */
    private static final Set<String> LIBRARY_ATTRS = new LinkedHashSet<>(java.util.Arrays.asList(
            "colorPrimaryVariant", "colorSecondaryVariant", "colorOnSecondary",
            "colorSurface", "colorOnSurface", "colorSurfaceVariant", "colorOnSurfaceVariant",
            "colorOutline", "colorError", "colorOnError",
            "colorPrimaryContainer", "colorOnPrimaryContainer",
            "colorSecondaryContainer", "colorOnSecondaryContainer",
            "colorTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
            // AppCompat 控件色（AppCompat 主题自带）：矢量图 tint 常用
            "colorControlNormal"));

    @Test
    public void everyColorAttrUsedInResourcesIsDefinedInTheme() throws IOException {
        Set<String> defined = new TreeSet<>();
        defined.addAll(parse(Paths.get("src/main/res/values/styles.xml"), ATTR_DEF));
        Path nightStyles = Paths.get("src/main/res/values-night/styles.xml");
        if (Files.exists(nightStyles)) {
            defined.addAll(parse(nightStyles, ATTR_DEF));
        }

        Set<String> used = new TreeSet<>();
        for (String dir : new String[]{"src/main/res/layout", "src/main/res/drawable",
                "src/main/res/drawable-night", "src/main/res/menu"}) {
            Path root = Paths.get(dir);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(x -> x.toString().endsWith(".xml")).collect(Collectors.toList())) {
                    used.addAll(parse(p, ATTR_REF));
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String attr : used) {
            if (!defined.contains(attr) && !LIBRARY_ATTRS.contains(attr)) {
                missing.add(attr);
            }
        }
        assertTrue("布局/drawable 引用了主题没定义的 color attr（运行期会解析成 0/透明，"
                        + "或 inflate 抛 Failed to resolve attribute）：\n  缺定义: " + missing
                        + "\n  app 主题已定义: " + defined,
                missing.isEmpty());
    }

    private static Set<String> parse(Path file, Pattern pattern) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
