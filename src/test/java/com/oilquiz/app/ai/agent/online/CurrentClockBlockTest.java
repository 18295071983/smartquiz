package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 2026-11-04 真机事故的回归：环境上下文只有日期、没有时刻，却把「现在」写进权威范围，
 * 模型于是编了个「现在 17:49」（实际 15:09），还据此推出「距天黑不足一小时」。
 *
 * <p>防复发要两件事同时成立，少一件都会重演：
 * ① 上下文里声明「没有时刻」，并指路 time_date；
 * ② 请求尾部真的带上一块可读的当前时刻（前缀缓存不动，只动尾部）。
 */
public class CurrentClockBlockTest {

    @Test
    public void clockTextCarriesTimeZoneAndAuthority() {
        String text = OnlineAgentEngine.buildCurrentClockText();
        assertTrue("缺时刻: " + text,
                Pattern.compile("\\d{4}年\\d{1,2}月\\d{1,2}日 \\d{2}:\\d{2}:\\d{2} 星期[一二三四五六日]")
                        .matcher(text).find());
        assertTrue("缺时区，模型会自己换算再编一次: " + text, text.contains("UTC+")
                || text.contains("UTC-"));
        assertTrue("必须说明以本条为准: " + text, text.contains("以本条为准"));
        assertTrue("块首标记缺失，模型不容易把这条当实时事实: " + text, text.startsWith("【当前时刻】"));
    }

    @Test
    public void environmentContextNoLongerClaimsToKnowThePresent() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/agent/online/OnlineAgentEngine.java");
        assertFalse("「现在」又回到日期段的权威范围里了",
                src.contains("回答今天/现在/最新/几号"));
        assertTrue("必须显式声明只有日期没有时刻", src.contains("本上下文只有日期、没有时刻"));
        assertTrue("必须指路 time_date", src.contains("time_date（action=now）"));
    }

    @Test
    public void clockBlockIsAppendedToOutgoingRequest() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/agent/online/OnlineAgentEngine.java");
        assertTrue("请求尾部没接上时刻块",
                src.contains("clockMsg.addProperty(\"content\", buildCurrentClockText())"));
    }

    private static String read(String rel) throws Exception {
        Path p = Paths.get(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
