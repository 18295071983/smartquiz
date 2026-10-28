package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 一键准备的分阶段脚本清单不变量。
 *
 * <p>为什么要钉住：{@code STEP_NAMES} 与 {@code buildStepScripts()} 返回的列表是**按下标配对**写盘的
 * （{@code writeTextFile(STEP_NAMES[i], steps.get(i))}），中间插一步时只改一边就会把脚本名字装错内容——
 * 编译和单测都不会报错，只有真机跑准备流程时才表现为"某步跑了别的步骤"。2026-10-28 加 step2b（tmux）时
 * 就是这种改法，所以把对齐关系钉成测试。
 */
public class TermuxStepScriptsAlignmentTest {

    @SuppressWarnings("unchecked")
    private static String[] stepNames() throws Exception {
        Field f = TermuxEnvInstaller.class.getDeclaredField("STEP_NAMES");
        f.setAccessible(true);
        return (String[]) f.get(null);
    }

    @Test
    public void stepNamesAndScriptsStayAligned() throws Exception {
        String[] names = stepNames();
        List<String> scripts = TermuxEnvInstaller.buildStepScripts(
                "/sdcard/Download/OilQuiz/termux_env/ubuntu-base.tar.gz",
                "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main");
        assertEquals("STEP_NAMES 与脚本列表条数不一致（插步骤时漏改了一边）",
                names.length, scripts.size());
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            String script = scripts.get(i);
            // 每个脚本都是独立可执行的 step 脚本：shebang + 幂等助手 ok()/bad()
            assertTrue(name + " 缺 Termux shebang",
                    script.startsWith("#!/data/data/com.termux/files/usr/bin/bash"));
            assertTrue(name + " 缺 bad() 失败即停助手", script.contains("bad(){"));
            // 名字里的步骤号必须与它自己写的状态标记一致（step2_proot.sh ↔ step2=ok）
            String tag = name.split("_")[0];
            assertTrue(name + " 的状态标记不是 " + tag, script.contains("echo \"" + tag + "=ok "));
        }
    }

    /** tmux 必须"脚本会装 + 仓库真带了 deb"两边都有，否则 termux_submit 永远只能退回 nohup */
    @Test
    public void cliStepMatchesBundledTmuxDebs() throws Exception {
        String[] names = stepNames();
        int idx = -1;
        for (int i = 0; i < names.length; i++) {
            if (names[i].contains("cli")) {
                idx = i;
            }
        }
        assertTrue("没有装 CLI 工具的 step（tmux 无人安装）", idx >= 0);
        String script = TermuxEnvInstaller.buildStepScripts("r", "t").get(idx);
        assertTrue("CLI 步骤没装 tmux", script.contains("tmux_*.deb"));
        assertTrue("CLI 步骤没有联网兜底", script.contains("pkg install -y tmux"));

        File pkgs = new File("src/main/assets/termux_pkgs");
        assertTrue("内置离线 deb 目录不存在", pkgs.isDirectory());
        for (String need : new String[]{"tmux_", "libevent_", "utf8proc_"}) {
            String hit = firstStartingWith(pkgs, need);
            assertNotNull("assets/termux_pkgs 里没有 " + need + "*.deb（脚本会装不到 tmux）", hit);
        }
    }

    /** 配置页按钮与一键准备共用同一份脚本体，别复制两份 */
    @Test
    public void cliButtonScriptSharesTheSameBody() throws Exception {
        String standalone = TermuxEnvInstaller.buildCliInstallScript();
        assertTrue("独立脚本没走 dpkg 离线安装", standalone.contains("dpkg -i libevent_*.deb"));
        assertTrue("独立脚本没做幂等前置检查", standalone.contains("command -v tmux"));
    }

    private static String firstStartingWith(File dir, String prefix) {
        String[] kids = dir.list();
        if (kids == null) {
            return null;
        }
        for (String k : kids) {
            if (k.startsWith(prefix) && k.endsWith(".deb")) {
                return k;
            }
        }
        return null;
    }
}
