package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AIToolManager} 单元测试。
 *
 * <p>这一版按<b>现行契约</b>重写，因为原前提已经不成立：旧用例全部假设
 * "测试环境里没有注册任何工具"，于是断言 {@code getTool(...) == null}、
 * {@code hasTool(...) == false}、描述列表为空。实际上构造 AIToolManager 时就会
 * {@code registerToolFactories()} 注册 database / network_search / file_reader /
 * ui_component … 一批工具工厂，而 {@code getTool(name)} 走 {@code getOrCreateTool}
 * 懒加载出实例 —— 所以 {@code getTool("network_search")} 返回真对象，旧断言必红。
 *
 * <p>另外去掉了原来满篇的 {@code if (toolManager != null) {...}}：
 * 那种写法在初始化失败时会"空跑通过"，等于没测。现在 setUp 直接断言非空。
 *
 * <p>单例是静态字段、跨用例共享，所以"未初始化时 getInstance() 抛
 * IllegalStateException"这条只能在专用用例里用反射临时清空、结束后还原，
 * 不能靠测试顺序碰运气。
 */
@RunWith(MockitoJUnitRunner.class)
public class AIToolManagerTest {

    @Mock
    private Context context;

    private AIToolManager toolManager;

    @Before
    public void setUp() {
        toolManager = AIToolManager.getInstance(context);
        assertNotNull("AIToolManager.getInstance(context) 不应返回 null", toolManager);
    }

    @Test
    public void testGetInstanceIsSingleton() {
        assertSame("带 context 重复取应命中同一个静态单例",
                toolManager, AIToolManager.getInstance(context));
        assertSame("无参 getInstance() 在已初始化后返回同一个实例",
                toolManager, AIToolManager.getInstance());
    }

    /** 未初始化路径：静态单例跨用例共享，只能反射临时清掉再还原 */
    @Test
    public void testGetInstanceWithoutPriorInitializationThrows() throws Exception {
        Object saved = readInstanceField();
        try {
            writeInstanceField(null);
            try {
                AIToolManager.getInstance();
                fail("未初始化时 getInstance() 应抛 IllegalStateException");
            } catch (IllegalStateException expected) {
                // 预期路径
            }
        } finally {
            writeInstanceField(saved);
        }
    }

    @Test
    public void testGetToolsReturnsRegisteredTools() {
        List<AITool> tools = toolManager.getTools();
        assertNotNull("工具列表不应为 null", tools);
        assertFalse("构造时就注册了一批工具工厂，列表不应为空", tools.isEmpty());
    }

    /**
     * getTool 与 hasTool 不是一回事，别混着断言：
     * <ul>
     *   <li>{@code hasTool(name)} 只查注册表（工厂是否存在），不构造工具；</li>
     *   <li>{@code getTool(name)} 会真的 {@code factory.create(context)}，
     *       构造失败会被吞掉并返回 null —— 所以"能不能拿到实例"取决于
     *       该工具在纯 JVM 环境下能否建起来（database 要建 Room，拿不到实例，
     *       但它确实注册着）。</li>
     * </ul>
     * 因此这里用 hasTool 断言注册事实，用 getTool 只断言能干净构造的工具。
     */
    @Test
    public void testGetTool() {
        assertNotNull("network_search 在此环境能实例化，不应为 null",
                toolManager.getTool("network_search"));
        assertNull("无法解析的名字应返回 null",
                toolManager.getTool("non_existent_tool"));
    }

    @Test
    public void testHasTool() {
        assertTrue(toolManager.hasTool("network_search"));
        assertTrue("database 注册着工厂（能否实例化是 getTool 的事）",
                toolManager.hasTool("database"));
        assertFalse(toolManager.hasTool("non_existent_tool"));
    }

    @Test
    public void testExecuteNonExistentToolFailsWithoutThrowing() {
        AIToolResult result = toolManager.executeTool("non_existent_tool", new HashMap<>());
        assertNotNull("执行结果不应为 null（失败也要有结构化返回）", result);
        assertFalse("不存在的工具不应报成功", result.isSuccess());
    }

    /**
     * 工具名模糊解析（模型会把 file_reader 猜成 file）：只钉住"有结构化返回、不抛异常"。
     * 不断言成败——纯 JVM 环境下真实文件操作必然失败，把"失败"写进断言等于绑死环境。
     */
    @Test
    public void testExecuteToolReturnsStructuredResult() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("action", "get_file_info");
        parameters.put("file_path", "/test/file.txt");

        assertNotNull(toolManager.executeTool("file", parameters));
    }

    @Test
    public void testGetToolDescriptions() {
        List<Map<String, Object>> descriptions = toolManager.getToolDescriptions();
        assertNotNull(descriptions);
        assertFalse("注册工具都应能产出给模型看的描述", descriptions.isEmpty());
    }

    @Test
    public void testReleaseDoesNotThrow() {
        toolManager.release();
    }

    private Object readInstanceField() throws Exception {
        Field field = AIToolManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        return field.get(null);
    }

    private void writeInstanceField(Object value) throws Exception {
        Field field = AIToolManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, value);
    }
}
