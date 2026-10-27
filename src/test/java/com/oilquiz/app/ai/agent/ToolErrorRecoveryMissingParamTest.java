package com.oilquiz.app.ai.agent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 缺参报错 → 恢复链路的<b>参数名解析</b>。
 *
 * <p><b>为什么要测</b>：{@link ToolErrorRecovery#analyzeMissingParams} 从错误文本里猜缺失参数名，
 * 工具侧这些文案被当作协议在用。此前它按"第一个空格"截断，而工具写的是
 * {@code "缺少参数: timestamp（秒级时间戳）"}——全角括号里没有空格，于是整段
 * {@code "timestamp（秒级时间戳）"} 被当成 key，按 key 找工具参数永远找不到，
 * 这条自动补参链路等于静默失效，还没有任何报错。现在工具侧允许在参数名后用全角括号补
 * "哪个 action 需要它"，解析侧就必须在非标识符字符处截断。</p>
 */
public class ToolErrorRecoveryMissingParamTest {

    private static List<String> keys(String tool, String msg) {
        List<String> out = new ArrayList<>();
        for (ToolErrorRecovery.MissingParam p
                : ToolErrorRecovery.analyzeMissingParams(tool, msg, Collections.emptyMap())) {
            out.add(p.key);
        }
        return out;
    }

    @Test
    public void chineseMissingParamMessageYieldsCleanKey() {
        // 与 TimeDateTool / WorkspaceTool 实际抛出的文案逐字一致
        assertTrue(keys("time_date",
                "缺少参数: timestamp（秒级时间戳；action=timestamp_to_date 时必填）").contains("timestamp"));
        assertTrue(keys("time_date",
                "缺少参数: date（格式 yyyy-MM-dd HH:mm:ss；action=date_to_timestamp 时必填）").contains("date"));
        List<String> ws = keys("workspace",
                "缺少参数: fileName（action=read 需要，工作区内相对名）");
        assertTrue(ws.contains("fileName"));
        // 反向约束：key 里不能混进说明文字（旧 bug 的形态）
        for (String k : ws) {
            assertFalse("key 里混进了说明文字: " + k, k.contains("（") || k.contains(" "));
        }
    }

    @Test
    public void englishAndNonAsciiFallbacksStillWork() {
        // 英文形态不变
        assertTrue(keys("python_execute", "parameter code is required").contains("code"));
        // 中文参数名（非 ASCII 开头）：退回"截到第一个空格"的旧行为，不能因为新规则失去它
        assertTrue(keys("weather", "缺少参数: 城市 名称").contains("城市"));
    }
}
