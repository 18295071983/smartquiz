package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * JS 工具注入脚本的回归测试（2026-10-25 实测两个缺陷）。
 *
 * <p>不执行 WebView（JVM 里跑不了 V8），只钉住"生成出来的注入脚本长什么样"这一层——
 * 两个缺陷都恰好是脚本形状问题，断言字符串就能防回归：</p>
 * <ol>
 *   <li><b>url= 真实网页模式全量失败</b>：{@code buildRealPageInjection} 的外层是 IIFE，
 *       原来结尾写的是 {@code window.__run(code);}（没有 return）→ 整段求值 undefined →
 *       evaluateJavascript 回传 "null" → 解析处抛
 *       「Value null of type org.json.JSONObject$1 cannot be converted to JSONObject」。
 *       必须 {@code return window.__run(...)}。</li>
 *   <li><b>顶层 return 报 Illegal return statement</b>：不能靠 Java 侧猜行首
 *       （{@code const a=1; return a} 这类同行多语句会猜漏），必须由 {@code __run}
 *       先 eval 取完成值、捕获 SyntaxError 后改按函数体执行。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class JsExecuteInjectionTest {

    private static String bootstrapHtml() throws Exception {
        Field f = JsExecuteTool.class.getDeclaredField("BOOTSTRAP_HTML");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static String realPageInjection(String quotedCode) throws Exception {
        Method m = JsExecuteTool.class.getDeclaredMethod("buildRealPageInjection", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, quotedCode);
    }

    /** url 模式：外层 IIFE 必须把结果 return 出来，否则引擎回传 null、解析必炸。 */
    @Test
    public void realPageInjectionReturnsRunResult() throws Exception {
        String js = realPageInjection("\"1+1\"");
        assertTrue("真实页面注入必须 return window.__run(...)，否则求值为 undefined",
                js.contains("return window.__run("));
    }

    /** 两份 __run（引导页 + 真实页）都要有"顶层 return 按函数体执行"的兜底，防只改一处漂移。 */
    @Test
    public void bothRunWrappersFallBackToFunctionBody() throws Exception {
        String bootstrap = bootstrapHtml();
        String realPage = realPageInjection("\"x\"");
        for (String js : new String[]{bootstrap, realPage}) {
            assertTrue("__run 要先 eval 取完成值", js.contains("eval(c)"));
            assertTrue("eval 抛 SyntaxError 时要改按函数体执行（顶层 return 合法）",
                    js.contains("instanceof SyntaxError") && js.contains("new Function(c)"));
        }
    }

    /** 引导页与真实页的 __run 必须同源：桥接方法集合一致，避免一条路能跑一条路不能。 */
    @Test
    public void bothContextsExposeSameBridge() throws Exception {
        String bootstrap = bootstrapHtml();
        String realPage = realPageInjection("\"x\"");
        String[] bridges = {"__http", "__fs", "__jslogs", "__enc", "__errMsg", "AndroidBridge.done"};
        for (String b : bridges) {
            assertTrue("引导页缺少 " + b, bootstrap.contains(b));
            assertTrue("真实页注入缺少 " + b, realPage.contains(b));
        }
    }
}
