package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ai.python.PythonToolManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证 pip_install 修复（2026-09-27，来自手机端 AI 的检测记录）：
 *
 * 修复前实测：装 pyfiglet 会拿到 **预发布版 1.0.0rc1**，且下载 URL 直接拼
 * "镜像base + ../../packages/…" → HTTP 404，最后"提示安装完成、实际什么都没装"。
 * 真因（我复核后与手机 AI 的结论有两处不同）：
 *   ① wheel tag 过滤太严：只认 "-py3-none-any.whl"，把 "py2.py3-none-any"（Python 3 可用）拒了
 *      → 索引里只剩 1.0.0rc1 能过 → 只能装到 rc（不是"没比较版本"：代码里有 compareVersions）；
 *   ② 相对 href 没做 URI 归一化 → /simple/../../packages/… → 404（不是 UA 403：实测我们的 UA 是 200）。
 *
 * 本用例验证：不带版本约束装 pyfiglet → 成功、**不是预发布版**、真的落到 runtime_packages/、且能 import。
 */
@RunWith(AndroidJUnit4.class)
public class PipInstallDeviceTest {

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void installsPurePythonWheelWithoutPrerelease() throws Exception {
        Context c = ctx();

        Map<String, Object> p = new HashMap<>();
        p.put("action", "install");
        p.put("package", "pyfiglet");
        p.put("timeout", 180);
        AIToolResult r = new PipInstallTool(c).execute(p);
        String report = String.valueOf(r.getResult()) + String.valueOf(r.getErrorMessage());
        System.out.println("[EXP] pip_install pyfiglet => success=" + r.isSuccess());
        System.out.println("[EXP] 报告: " + report.replace("\n", " | "));
        assertTrue("安装应成功: " + report, r.isSuccess());

        // 修复前这里会是 1.0.0rc1
        assertFalse("不应安装预发布版: " + report, report.contains("1.0.0rc1"));

        // 真的落盘（修复前 runtime_packages 从没被创建过）
        File dir = new File(c.getFilesDir(), "runtime_packages");
        assertTrue("runtime_packages 应存在", dir.isDirectory());
        File[] infos = dir.listFiles((d, f) ->
                f.toLowerCase().startsWith("pyfiglet") && f.toLowerCase().endsWith(".dist-info"));
        assertNotNull("应有 pyfiglet 的 dist-info", infos);
        assertTrue("应有 pyfiglet 的 dist-info（实际 " + infos.length + " 个）", infos.length > 0);
        System.out.println("[EXP] 落盘: " + infos[0].getName());

        // 装完就能 import（sys.path 注入 + Python 侧真实验证）
        PythonToolManager ptm = PythonToolManager.getInstance(c);
        assertTrue("Python 应能初始化", ptm.initialize());
        ptm.ensureRuntimePackagesInPath();
        PythonToolManager.ExecutionResult er = ptm.executeCode(
                "import pyfiglet\nprint('PYFIGLET_OK', getattr(pyfiglet, '__version__', '?'))",
                new HashMap<String, Object>());
        System.out.println("[EXP] import 验证 => success=" + er.success + " stdout=" + er.stdout + " err=" + er.stderr + " error=" + er.error);
        assertTrue("安装后应能 import pyfiglet: " + er.error + er.stderr, er.success);
        assertTrue("stdout 应含 PYFIGLET_OK: " + er.stdout, String.valueOf(er.stdout).contains("PYFIGLET_OK"));
    }

    /**
     * 指定版本时要走"升级"，不能因为同名就误报"已安装（跳过）"；
     * 镜像（阿里云）没有该版本时还要能回退官方源。
     * 实测：阿里云索引里 pyfiglet 只有 0.8.post1 与 1.0.0rc1；官方有 1.0.4。
     */
    @Test
    public void upgradesWhenVersionConstraintDiffers() throws Exception {
        Context c = ctx();
        Map<String, Object> p = new HashMap<>();
        p.put("action", "install");
        p.put("package", "pyfiglet");
        p.put("timeout", 180);
        new PipInstallTool(c).execute(p);   // 先确保已有旧版本

        p.put("package", "pyfiglet==1.0.4");
        AIToolResult r = new PipInstallTool(c).execute(p);
        String report = String.valueOf(r.getResult()) + String.valueOf(r.getErrorMessage());
        System.out.println("[EXP] 升级到 1.0.4 => success=" + r.isSuccess() + " | " + report.replace("\n", " | "));
        assertTrue("应升级成功: " + report, r.isSuccess());
        assertTrue("报告里应出现 1.0.4: " + report, report.contains("1.0.4"));
        assertFalse("不应因同名就跳过: " + report, report.contains("跳过"));

        File dir = new File(c.getFilesDir(), "runtime_packages");
        File[] infos = dir.listFiles((d, f) ->
                f.toLowerCase().startsWith("pyfiglet") && f.toLowerCase().endsWith(".dist-info"));
        assertNotNull(infos);
        boolean hasNew = false;
        for (File fi : infos) {
            if (fi.getName().contains("1.0.4")) hasNew = true;
        }
        System.out.println("[EXP] 安装目录里的 pyfiglet dist-info: " + infos.length + " 个，含 1.0.4 = " + hasNew);
        assertTrue("应有 1.0.4 的 dist-info", hasNew);
        assertTrue("旧版 dist-info 应被清理（只留一个）", infos.length == 1);
    }

}