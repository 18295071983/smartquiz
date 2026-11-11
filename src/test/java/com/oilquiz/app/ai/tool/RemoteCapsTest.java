package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 能力协商契约（2026-11-12）：客户端声明与电脑端 {@code caps.js} 必须同名同义。
 *
 * <p>这个测试的价值在"防漂移"：两边各改各的时，服务端会按它认识的名字裁剪，
 * 客户端却收不到自己以为有的卡 —— 正是这类问题以前只能靠真机猜。这里直接读插件的
 * caps.js 做双向比对（找不到文件时跳过，避免脱离仓库的单测环境红）。</p>
 */
public class RemoteCapsTest {

    @Test
    public void clientCapsAreUniqueAndCsvMatchesAll() {
        Set<String> seen = new HashSet<>();
        for (String cap : RemoteCaps.ALL) {
            assertTrue("能力名重复：" + cap, seen.add(cap));
            assertTrue("能力名不能为空", cap != null && !cap.trim().isEmpty());
        }
        assertEquals(String.join(",", RemoteCaps.ALL), RemoteCaps.csv());
    }

    @Test
    public void helloBodyDeclaresClientKindAndCaps() {
        Map<String, Object> body = RemoteCaps.helloBody("session-1");
        assertEquals("session-1", body.get("session_id"));
        assertEquals(RemoteCaps.csv(), body.get("caps"));
        assertTrue(body.get("client") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) body.get("client");
        assertEquals("android", client.get("kind"));
        assertNotNull(client.get("ver"));
        assertEquals(java.util.Collections.singletonList("v2"), body.get("accept"));
    }

    @Test
    public void clientAndPluginCapsAgreeBothWays() throws Exception {
        File js = findCapsJs();
        if (js == null) return; // 脱仓单测环境：不误报
        String src = new String(Files.readAllBytes(js.toPath()), StandardCharsets.UTF_8);

        for (String cap : RemoteCaps.ALL) {
            assertTrue("电脑端 caps.js 的 SERVER_CAPS 缺少客户端声明：" + cap,
                    src.contains("'" + cap + "'"));
        }

        List<String> serverCaps = extractServerCaps(src);
        assertTrue("没解析到 SERVER_CAPS（格式变了？）", serverCaps.size() >= 5);
        for (String cap : serverCaps) {
            assertTrue("客户端 RemoteCaps 未声明电脑端能力：" + cap,
                    Arrays.asList(RemoteCaps.ALL).contains(cap));
        }
    }

    private static List<String> extractServerCaps(String src) {
        List<String> out = new ArrayList<>();
        int start = src.indexOf("SERVER_CAPS");
        if (start < 0) return out;
        int end = src.indexOf("]);", start);
        if (end < 0) return out;
        Matcher m = Pattern.compile("'([^']+)'").matcher(src.substring(start, end));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** 从测试工作目录向上找插件目录（Gradle 默认 workingDir = 模块根） */
    private static File findCapsJs() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i += 1) {
            File f = new File(dir, "tools/dsh-plugin-smartquiz-remote/caps.js");
            if (f.isFile()) return f;
            dir = dir.getParentFile();
        }
        return null;
    }
}
