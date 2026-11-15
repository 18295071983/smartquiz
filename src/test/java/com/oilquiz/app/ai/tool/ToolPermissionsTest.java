package com.oilquiz.app.ai.tool;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ToolPermissions 单元测试（2026-11-16）：分级是安全默认值，必须有测试钉住。
 */
public class ToolPermissionsTest {

    @Test
    public void readOnlyToolsNeedNoApproval() {
        for (String t : new String[]{"time_date", "file_reader", "network_search", "calculator"}) {
            assertEquals(t + " 应为只读", ToolPermissions.Level.READ_ONLY,
                    ToolPermissions.levelOf(t));
            assertFalse(t + " 不需要确认", ToolPermissions.requiresApproval(t));
        }
    }

    @Test
    public void execAndNetworkAskEveryTime() {
        for (String t : new String[]{"linux_shell", "js_execute", "dashscope_media"}) {
            assertTrue(t + " 需要确认", ToolPermissions.requiresApproval(t));
            assertTrue(t + " 每次都要问", ToolPermissions.requiresEveryTime(t));
        }
    }

    @Test
    public void dangerousToolsRequireAudit() {
        for (String t : new String[]{"git", "export_apk", "permission_manager"}) {
            assertEquals(ToolPermissions.Level.DANGEROUS, ToolPermissions.levelOf(t));
            assertTrue(t + " 必须审计", ToolPermissions.requiresAudit(t));
        }
    }

    @Test
    public void unknownToolDefaultsToExec() {
        assertEquals("未知工具应保守按 EXEC", ToolPermissions.Level.EXEC,
                ToolPermissions.levelOf("some_future_tool"));
        assertTrue(ToolPermissions.requiresEveryTime("some_future_tool"));
    }

    @Test
    public void fileToolsAreWriteLocal() {
        for (String t : new String[]{"file_generator", "workspace", "rule_engine", "excel"}) {
            assertEquals(t + " 应为本地写", ToolPermissions.Level.WRITE_LOCAL,
                    ToolPermissions.levelOf(t));
        }
    }

    @Test
    public void dynamicToolCreationIsDangerous() {
        assertEquals("造新工具等于给 Agent 加能力，必须按危险归类",
                ToolPermissions.Level.DANGEROUS, ToolPermissions.levelOf("create_dynamic_tool"));
        assertTrue(ToolPermissions.requiresAudit("create_dynamic_tool"));
        // 造出来的动态工具名不在分级表里 → 保守 EXEC
        assertEquals(ToolPermissions.Level.EXEC, ToolPermissions.levelOf("my_runtime_tool"));
    }

    @Test
    public void allLevelsArePopulated() {
        Map<String, ToolPermissions.Level> all = ToolPermissions.allLevels();
        assertTrue("分级表不应为空", all.size() >= 40);
        for (ToolPermissions.Level level : ToolPermissions.Level.values()) {
            assertFalse("级别 " + level + " 不应为空",
                    ToolPermissions.toolsOf(level).isEmpty());
        }
    }

    /**
     * 动态工具在设置页可见（R3 的验收点）。
     *
     * <p>动态工具不在静态分级表里，{@code levelOf} 兜底按 EXEC；设置页原来只遍历静态表，
     * 于是模型自建的工具永远不显示。合并交给 {@code toolsOf(level, dynamic)}：只并进 EXEC 档，
     * 不并进只读/危险档（"未分级"不能被伪装成"已分级"），且同名不重复。
     */
    @Test
    public void dynamicToolsShowUpUnderExecOnly() {
        java.util.List<String> dynamic = java.util.Arrays.asList("my_runtime_tool", "  spaced_tool  ");

        Set<String> exec = ToolPermissions.toolsOf(ToolPermissions.Level.EXEC, dynamic);
        assertTrue("动态工具必须出现在执行档（否则设置页看不到）", exec.contains("my_runtime_tool"));
        assertTrue("动态工具名要去掉首尾空白", exec.contains("spaced_tool"));
        assertEquals("动态工具的等级就是兜底的 EXEC", ToolPermissions.Level.EXEC,
                ToolPermissions.levelOf("my_runtime_tool"));

        // 其它档位不接受动态工具：不能把"未分级"说成只读/危险
        assertFalse("动态工具不能混进只读档",
                ToolPermissions.toolsOf(ToolPermissions.Level.READ_ONLY, dynamic)
                        .contains("my_runtime_tool"));
        assertFalse("动态工具不能混进危险档",
                ToolPermissions.toolsOf(ToolPermissions.Level.DANGEROUS, dynamic)
                        .contains("my_runtime_tool"));

        // 与静态工具同名时不重复、不改变其归档
        Set<String> withDup = ToolPermissions.toolsOf(ToolPermissions.Level.EXEC,
                java.util.Arrays.asList("linux_shell", "linux_shell"));
        assertEquals("同名动态工具应去重", 1,
                java.util.Collections.frequency(withDup, "linux_shell"));
        assertFalse("按静态表归危险的工具不能被并进执行档",
                ToolPermissions.toolsOf(ToolPermissions.Level.EXEC, java.util.Arrays.asList("git"))
                        .contains("git"));

        // null / 空集合不能把静态表弄坏
        assertEquals(ToolPermissions.toolsOf(ToolPermissions.Level.EXEC).size(),
                ToolPermissions.toolsOf(ToolPermissions.Level.EXEC, null).size());
    }
}
