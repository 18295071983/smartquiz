package com.oilquiz.app.ai.tool;

import org.junit.Test;

import java.util.Map;

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
}
