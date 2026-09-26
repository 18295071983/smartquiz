package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证 2026-09-27 修掉的两个「能用，但有些问题」：
 *
 *  ① **工具自声明超时**：remote_dsh 的长任务不再被 OnlineToolManager 的 30s 默认值掐断。
 *     取证：手机端 AI 日志里 remote_dsh 传了 timeout=150，仍在 30003ms 被杀且结果长度 0。
 *  ② **bridge /exec 直连**（action=shell）：跑一条命令毫秒级拿原样输出，不必起电脑端 LLM。
 *     取证：此前"跑这条命令并贴输出"走 /run（完整 agent），几十秒 + 烧 token + 输出可能被改写；
 *     更早一步还会因为 ACP 权限请求无人应答而**永久挂起**（写文件/跑命令类工具）。
 *
 * 运行（电脑端桥接需在线）：
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.RemoteDshTimeoutDeviceTest \
 *       -e publicUrl https://<花生壳域名> -e token <桥接令牌> \
 *       com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class RemoteDshTimeoutDeviceTest {

    /** 传了 -e publicUrl/-e token 就先写配置（与 RemoteDshBridgeDeviceTest 一致，避免用例顺序依赖） */
    private static Context ensureConfigured() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String publicUrl = args.getString("publicUrl");
        String token = args.getString("token");
        if (publicUrl != null && token != null) {
            String host = new java.net.URL(publicUrl).getHost();
            RemoteDshPairBridge.parseAndSave(ctx,
                    "dshpair://" + host + "?scheme=https&port=443&token=" + token);
        }
        return ctx;
    }

    /** action=shell：命令原样执行 + 原样取回输出 */
    @Test
    public void shellRunsDirectlyAndReturnsRawOutput() throws Exception {
        Context ctx = ensureConfigured();
        Map<String, Object> p = new HashMap<>();
        p.put("action", "shell");
        p.put("task", "Write-Output ('SHELL_OK_' + (2 + 3))");
        p.put("timeout", 30);
        long t0 = System.currentTimeMillis();
        AIToolResult r = new RemoteDshTool(ctx).execute(p);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[EXP] shell => success=" + r.isSuccess() + "  " + ms + "ms");
        System.out.println("[EXP]   result=" + r.getResult());
        System.out.println("[EXP]   error=" + r.getErrorMessage());
        assertTrue("shell 应成功: " + r.getErrorMessage(), r.isSuccess());
        assertTrue("结果应包含命令原样输出: " + r.getResult(),
                String.valueOf(r.getResult()).contains("SHELL_OK_5"));
        assertTrue("直连执行应是秒级（实测 ~0.3s）: " + ms + "ms", ms < 15000);
    }

    /** action=shell：非零退出要如实回传（判断"命令失败"靠它） */
    @Test
    public void shellSurfacesNonZeroExit() throws Exception {
        Context ctx = ensureConfigured();
        Map<String, Object> p = new HashMap<>();
        p.put("action", "shell");
        p.put("shell", "cmd");
        p.put("task", "exit 7");
        p.put("timeout", 20);
        AIToolResult r = new RemoteDshTool(ctx).execute(p);
        System.out.println("[EXP] shell(cmd exit 7) => success=" + r.isSuccess()
                + "\n" + r.getResult() + r.getErrorMessage());
        assertFalse("非零退出应判为失败", r.isSuccess());
        assertTrue("失败信息里应带 exit=7: " + r.getErrorMessage(),
                String.valueOf(r.getErrorMessage()).contains("exit=7"));
    }

    /**
     * 长任务（40s）走 OnlineToolManager 真实调用路径。
     * 这里就是 30s 默认超时的所在地：修复前 40s 任务必在 30s 被杀且返回空结果；
     * 修复后由 RemoteDshTool.executionTimeoutMs() 申报 (90+45)s，任务应跑完并拿到输出。
     */
    @Test
    public void longRemoteTaskSurvivesManagerDefaultTimeout() throws Exception {
        Context ctx = ensureConfigured();
        OnlineToolManager mgr = new OnlineToolManager(ctx);
        String args = "{\"action\":\"shell\",\"task\":\"Start-Sleep -Seconds 40; Write-Output LONG_TASK_OK\","
                + "\"timeout\":90}";
        long t0 = System.currentTimeMillis();
        OnlineToolResult res = mgr.executeTool("exp-long-1", "remote_dsh", args);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[EXP] OnlineToolManager 40s 长任务 => success=" + res.success + "  " + ms + "ms");
        System.out.println("[EXP]   result=" + res.result);
        System.out.println("[EXP]   error=" + res.error);
        assertTrue("40s 长任务不应被 30s 默认超时掐断: " + res.error, res.success);
        assertTrue("应拿到任务输出: " + res.result, String.valueOf(res.result).contains("LONG_TASK_OK"));
        assertTrue("实测耗时应超过 30s（证明声明式超时真的生效了）: " + ms + "ms", ms > 30000);
    }
}
