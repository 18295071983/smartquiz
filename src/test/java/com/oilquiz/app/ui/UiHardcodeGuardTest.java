package com.oilquiz.app.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * UI 硬编码护栏（2026-11-16）。
 *
 * <p><b>为什么需要</b>：对话页的"颜色/尺寸/字号"是花了多轮才收干净的（见
 * {@code docs/ui/交接-UI硬编码与主题收尾-2026-11-16.md}）。没有护栏的话，下一次顺手写
 * {@code 0xFF3C3C3C}、{@code setTextSize(12f)}、{@code android:textColor="#333"} 又会把
 * 换肤/深色/字体缩放打回原形。这里把口径钉在源码上：</p>
 *
 * <ul>
 *   <li>对话页 layout/drawable：禁止字面色（{@code #xxxxxx}）与 {@code @android:color/white|black|…}；
 *       {@code @android:color/transparent} 不算配色，放行。</li>
 *   <li>对话页 Java（{@code ai/chat/**} + {@code AIChatActivity}）：禁止
 *       {@code Color.parseColor}、{@code 0xFFxxxxxx}、{@code setTextSize(数字)}、{@code dpToPx(数字)}。</li>
 * </ul>
 *
 * <p><b>允许清单（有意为之，不是漏网）</b>：</p>
 * <ul>
 *   <li>媒体查看器的纯黑底/白色播放图标（全屏看图、视频浮层）——媒体观察界面本就固定深色；</li>
 *   <li>{@code GuideStepFlowView} 里 {@code 0xFF…} 只作为"主题 attr 缺失时"的兜底常量，
 *       正常路径全部走 {@code ThemeColors.attr}。</li>
 * </ul>
 */
public class UiHardcodeGuardTest {

    /** 对话页布局（换肤/深色/字体缩放的主战场）。 */
    private static final List<String> CHAT_LAYOUTS = Arrays.asList(
            "src/main/res/layout/activity_ai_chat.xml",
            "src/main/res/layout/view_chat_appbar_v2.xml",
            "src/main/res/layout/view_chat_input_bar_v2.xml",
            "src/main/res/layout/view_chat_attach_sheet.xml",
            "src/main/res/layout/view_chat_empty_v2.xml",
            "src/main/res/layout/view_chat_v2_think_line.xml",
            "src/main/res/layout/view_chat_v2_tool_card.xml",
            "src/main/res/layout/item_chat_v2_tool_row.xml",
            "src/main/res/layout/item_chat_v2_recent.xml",
            "src/main/res/layout/item_user_message_v2.xml",
            "src/main/res/layout/item_thinking_message_v2.xml");

    /** XML 字面色：`#RGB`/`#RRGGBB`/`#AARRGGBB`。 */
    private static final Pattern XML_HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{3,8}\\b");
    /** XML 系统固定色（transparent 单列，不算配色）。 */
    private static final Pattern XML_SYSTEM_COLOR = Pattern.compile(
            "@android:color/(white|black|darker_gray|background_light|holo_[a-z_]+)");
    /** Java 字面色：0xAARRGGBB 与 parseColor。 */
    private static final Pattern JAVA_HEX_COLOR = Pattern.compile("0x[Ff][Ff][0-9A-Fa-f]{6}");
    private static final Pattern JAVA_PARSE_COLOR = Pattern.compile("Color\\.parseColor");
    /** 手写动画原语（应统一走 UiFx，见 {@link com.oilquiz.app.ui.UiFx}）。 */
    private static final Pattern JAVA_RAW_ANIMATION = Pattern.compile(
            "new (AlphaAnimation|TranslateAnimation|AnimationSet)\\(|ObjectAnimator\\.of|ValueAnimator\\.of");

    /** Java 字号/间距字面量。 */
    private static final Pattern JAVA_TEXT_SIZE_LITERAL = Pattern.compile("setTextSize\\(\\s*[0-9]");
    private static final Pattern JAVA_DP_LITERAL = Pattern.compile("dpToPx\\(\\s*[0-9]");

    /** 手写动画的允许清单：SegmentScraper 是"刮刀滑入"的唯一实现（历史注释要求保持原形状），不算重复。 */
    private static final String ALLOW_RAW_ANIM_FILE = "SegmentScraper.java";

    /** 清单解释：哪些文件允许 0xFF 兜底（只允许这一个，且必须有主题回退逻辑）。 */
    private static final String ALLOW_0XFF_FILE = "GuideStepFlowView.java";

    @Test
    public void chatLayoutsHaveNoHardcodedColors() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String path : CHAT_LAYOUTS) {
            String xml = stripXmlComments(read(path));
            collect(violations, path, "字面色", XML_HEX_COLOR, xml);
            collect(violations, path, "系统固定色", XML_SYSTEM_COLOR, xml);
        }
        // 对话页的 chat_v2 系列 drawable 同样要求
        for (Path p : listFiles("src/main/res/drawable", "chat_v2_.*\\.xml")) {
            String xml = stripXmlComments(read(p.toString()));
            collect(violations, p.toString(), "字面色", XML_HEX_COLOR, xml);
            collect(violations, p.toString(), "系统固定色", XML_SYSTEM_COLOR, xml);
        }
        assertTrue("对话页布局/drawable 出现硬编码颜色（应改用 ?attr/colorXxx 或 @color/语义令牌）：\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    /** 调试控制台（固定深色工具界面，不跟换肤，但必须只用 debug_* 命名色）。 */
    private static final List<String> DEBUG_CONSOLE_FILES = Arrays.asList(
            "src/main/res/layout/activity_agent_debug.xml",
            "src/main/res/layout/item_agent_debug_line.xml",
            "src/main/res/layout/item_debug_span.xml",
            "src/main/res/layout/dialog_span_detail.xml");

    @Test
    public void debugConsoleUsesNamedPaletteOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String path : DEBUG_CONSOLE_FILES) {
            String xml = stripXmlComments(read(path));
            collect(violations, path, "字面色（应使用 @color/debug_*）", XML_HEX_COLOR, xml);
            collect(violations, path, "系统固定色（应使用 @color/debug_*）", XML_SYSTEM_COLOR, xml);
        }
        assertTrue("调试控制台出现新的魔法色值（统一收敛在 res/values/colors.xml 的 debug_* 里）：\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void chatLayoutsUseDimenTokensForSizes() throws IOException {
        List<String> violations = new ArrayList<>();
        java.util.regex.Pattern dimLiteral = java.util.regex.Pattern.compile(
                "android:([a-zA-Z_]+)=\"([0-9.]+)(dp|sp|dip)\"");
        List<String> files = new ArrayList<>(CHAT_LAYOUTS);
        for (Path p : listFiles("src/main/res/drawable", "chat_v2_.*\\.xml")) {
            files.add(p.toString());
        }
        for (String path : files) {
            Matcher m = dimLiteral.matcher(stripXmlComments(read(path)));
            while (m.find()) {
                double value = Double.parseDouble(m.group(2));
                // 结构性 0dp（layout_width=0 / edge-to-edge inset=0）放行；其余必须走 dimen 令牌
                if (value != 0d) {
                    violations.add(path + " → " + m.group(1) + "=" + m.group(2) + m.group(3));
                }
            }
        }
        assertTrue("对话页布局出现写死的 dp/sp（应改用 @dimen/space_*|size_*|font_* 令牌）：\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void chatJavaHasNoHardcodedColorSizeOrSpacing() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get("src/main/java/com/oilquiz/app/ai/chat"))) {
            javaFiles.addAll(walk.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString).collect(Collectors.toList()));
        }
        javaFiles.add("src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java");

        for (String path : javaFiles) {
            String src = read(path);
            collect(violations, path, "parseColor", JAVA_PARSE_COLOR, src);
            if (!path.endsWith(ALLOW_0XFF_FILE)) {
                collect(violations, path, "0xFF 字面色", JAVA_HEX_COLOR, src);
            }
            collect(violations, path, "setTextSize 字面量", JAVA_TEXT_SIZE_LITERAL, src);
            collect(violations, path, "dpToPx 字面量", JAVA_DP_LITERAL, src);
            if (!path.endsWith(ALLOW_RAW_ANIM_FILE)) {
                collect(violations, path, "手写动画（应走 UiFx）", JAVA_RAW_ANIMATION, src);
            }
        }
        assertTrue("对话页 Java 出现硬编码颜色/字号/间距（颜色走 ThemeColors/ComponentColors，"
                + "字号走 Tokens.textSizeSp，间距走 dimen 令牌或 dimPx）：\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    // ---------------- 工具 ----------------

    private static void collect(List<String> out, String path, String kind,
                                Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(path + " → " + kind + "：" + m.group());
        }
    }

    /** 注释里的示例色（如"旧版是 #B3FFFFFF"）不算硬编码。 */
    private static String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static List<Path> listFiles(String dir, String fileRegex) throws IOException {
        try (Stream<Path> walk = Files.walk(Paths.get(dir))) {
            return walk.filter(p -> p.getFileName().toString().matches(fileRegex))
                    .collect(Collectors.toList());
        }
    }
}
