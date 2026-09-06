# -*- coding: utf-8 -*-
# 提示词增强：模型信任注入上下文/工具信息，不被训练时间困扰
import io, sys

def patch(path, old, new, label):
    s = io.open(path, "r", encoding="utf-8").read()
    c = s.count(old)
    if c != 1:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8", newline="\n").write(s)
    print("[OK] %s" % label)

# 1) 本地 Agent 环境上下文（AgentLoopEngine）
patch(r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\software\engine\AgentLoopEngine.java",
'''        // 肯定句收尾：删除原"环境信息可能已过时…"警告句——该否定句式会被 2B 小模型
        // 当作思考模板反复复读（实测复读几十次），是"乱思考"的直接触发源。
        // 时间/位置问题直接采用，不再诱导模型怀疑环境信息。
        sb.append("（以上信息已自动获取，回答时间/位置问题时直接采用）");''',
'''        // 肯定句收尾：删除原"环境信息可能已过时…"警告句——该否定句式会被 2B 小模型
        // 当作思考模板反复复读（实测复读几十次），是"乱思考"的直接触发源。
        // 时间/位置问题直接采用，不再诱导模型怀疑环境信息。
        // 信任强化：当前日期/位置是系统实时值，工具返回数据是实时权威，直接采用。
        sb.append("（以上为系统实时获取的当前日期与位置，是当前权威事实；回答今天/现在/最新/几号等问题直接以此为准。");
        sb.append("工具与搜索返回的实时数据（新闻、开奖、行情、政策、天气等）也直接采用，不需要按训练时的旧信息推断）");''',
'local agent env trust')

# 2) 在线 Agent 环境上下文（OnlineAgentEngine）
patch(r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\online\OnlineAgentEngine.java",
'''        sb.append("（以上环境信息已自动获取，回答时可据此理解\\"今天\\"、\\"附近\\"等指代）");''',
'''        sb.append("（以上为系统实时获取的当前日期与位置，是当前权威事实；回答今天/现在/最新/几号等问题以此为准，");
        sb.append("不以训练数据中的旧时间推断。工具与搜索返回的实时数据（新闻、开奖、行情、政策、天气）直接采用其内容，");
        sb.append("不要用训练知识改写或否定。）");''',
'online agent env trust')

# 3) 本地普通对话 buildChatJsonRequest 注入当前时间 system（AIChatViewModel）
patch(r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\chat\viewmodel\AIChatViewModel.java",
'''            org.json.JSONArray msgs = new org.json.JSONArray();
            int historyEnd = currentStreamingMessageIndex >= 0 ? currentStreamingMessageIndex : chatMessages.size();''',
'''            org.json.JSONArray msgs = new org.json.JSONArray();
            // 注入当前日期（权威事实）：防止模型用训练截止时间回答"今天几号/最新"类问题
            try {
                org.json.JSONObject sys = new org.json.JSONObject();
                sys.put("role", "system");
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyy年M月d日 EEEE", java.util.Locale.CHINA);
                sys.put("content", "当前日期：" + sdf.format(new java.util.Date())
                        + "。这是系统实时提供的当前时间，回答今天/几号/当前时间/最新等问题以它为准，不要使用训练数据中的旧时间。");
                msgs.put(sys);
            } catch (Exception ignored) {}
            int historyEnd = currentStreamingMessageIndex >= 0 ? currentStreamingMessageIndex : chatMessages.size();''',
'local chat time inject')

print("PROMPT TRUST OK")
