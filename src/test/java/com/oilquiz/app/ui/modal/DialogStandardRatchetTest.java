package com.oilquiz.app.ui.modal;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模态框/弹层标准化的"棘轮"护栏（2026-11-16）。
 *
 * <p><b>为什么需要</b>：全站手写了 90+ 个 {@code new AlertDialog.Builder}、
 * 十几个 {@code MaterialAlertDialogBuilder} 和若干 {@code BottomSheetDialog}，
 * 每个页面自己定样式与行为。本轮立了标准层（{@link AppDialog} / {@link AppSheet} / {@link AppCard}），
 * 但一次性迁移 100+ 处不现实。棘轮的做法是：</p>
 * <ul>
 *   <li>把当前"手写数量"钉住（只能减、不能增）；</li>
 *   <li>新增代码必须用标准层，否则这里立刻失败；</li>
 *   <li>每迁移一处，就把钉住的值往下调一格，逐步收敛。</li>
 * </ul>
 */
public class DialogStandardRatchetTest {

    /** 当前手写数量（2026-11-16 基线）。迁移后请下调，禁止上调。 */
    private static final int MAX_RAW_ALERT_DIALOG = 62;      // new AlertDialog.Builder(
    private static final int MAX_RAW_MATERIAL_DIALOG = 15;   // MaterialAlertDialogBuilder(
    private static final int MAX_RAW_BOTTOM_SHEET = 2;       // new BottomSheetDialog(
    private static final int MAX_RAW_DIALOG = 6;             // new Dialog(

    @Test
    public void rawDialogConstructionDoesNotGrow() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(Paths.get("src/main/java"))) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        List<String> all = new ArrayList<>();
        for (Path p : sources) {
            all.add(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        }
        String joined = String.join("\n", all);

        assertAtMost("new AlertDialog.Builder(", count(joined, "new AlertDialog.Builder("),
                MAX_RAW_ALERT_DIALOG, "AppDialog.builder(...)");
        assertAtMost("MaterialAlertDialogBuilder(", count(joined, "MaterialAlertDialogBuilder("),
                MAX_RAW_MATERIAL_DIALOG, "AppDialog.builder(...)");
        assertAtMost("new BottomSheetDialog(", count(joined, "new BottomSheetDialog("),
                MAX_RAW_BOTTOM_SHEET, "AppSheet.builder(...)");
        assertAtMost("new Dialog(", count(joined, "new Dialog("),
                MAX_RAW_DIALOG, "AppDialog / AppSheet");

        // 标准层必须真被用着（防止"建了没人用"）
        assertTrue("标准模态层应至少被一处使用（AppDialog.*）", count(joined, "AppDialog.") >= 1);
        assertTrue("标准弹层应至少被一处使用（AppSheet.*）", count(joined, "AppSheet.") >= 1);
    }

    private static void assertAtMost(String what, int actual, int max, String standard) {
        assertTrue("手写 " + what + ") 的数量从 " + max + " 涨到了 " + actual
                + "：新增代码请用标准层 " + standard + "，并把已迁移处从这里的基线下调。", actual <= max);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
