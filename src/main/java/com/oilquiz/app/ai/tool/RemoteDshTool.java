package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 远程 dsh 工具 v4：通过电脑端 dsh 桥接服务（tools/dsh_bridge_server.py v4）远程调用
 * DeepSeek dsh（DeepSeek Harness Shell），让 AI 远程操作电脑。
 *
 * 通道：**ACP 官方通道**（dsh --profile acp serve，127.0.0.1:7800；session/new + session/prompt + SSE 流式），
 * 同一 session_id 连续调用 = 多轮会话续接（电脑端 dsh 记忆连续）。桥接版本/ACP 版本一律由 /status 如实上报，
 * 代码里不写死版本号（写死过 "dsh 0.1.5"，手机端 AI 据此误判"版本不兼容"）。
 *
 * v4 要点（2026-09-27，实测驱动）：
 *   * 桥接为每个会话建立会话流并自动应答 session/request_permission —— 此前写文件/跑命令类任务会永久挂起；
 *   * 本工具按 timeout+45s 向 OnlineToolManager 申报执行超时（不再被其 30s 默认值掐断），
 *     超时时如实说明"等了多久、任务可能仍在电脑上"；
 *   * 新增 shell 动作：把 task 当命令经 bridge /exec 直连执行（不经电脑端 LLM，毫秒级、输出原样）。
 *
 * 架构：
 *   手机 App → HTTP(Bearer token) → 电脑端 dsh_bridge_server v4 → ACP serve(127.0.0.1:7800)
 *                                                             ├→ /exec 直连命令（shell 动作）
 *                                                             └→ headless(仅 ACP 不可用时 fallback)
 *
 * 动作：
 *   run(默认)  执行任务并自动续接会话：配置里已有 session_id 则直接续接，没有则先自动创建；
 *              task=自然语言任务描述；返回 AI 的回复。
 *   start      显式新建会话（重置电脑端 dsh 记忆），返回新的 session_id。
 *   history    读当前会话最近 N 条历史（文本摘要），检查 dsh 侧记忆。
 *   get_status 检查桥接服务与 dsh ACP 通道状态。
 *   set_config 配置 base_url / token / session_id（session_id 通常自动维护，一般无需手填）。
 *
 * 安全红线：
 *   * 桥接服务必须带 token 鉴权（Authorization: Bearer），未配置时不执行任何任务；
 *   * base_url 必须是 http:// 或 https:// 开头，禁止其他协议；
 *   * ACP serve(7800) 由电脑端 dsh 提供（bearer 鉴权），手机永远只访问带 token 的桥接层(8218)。
 */
@Tool(
        value = "remote_dsh",
        description = "远程控制电脑（DeepSeek dsh 官方会话通道）：调用电脑上安装的 dsh（DeepSeek Harness Shell）执行任务，"
                + "让 AI 远程操作电脑——读文件/跑命令/查信息/让 DeepSeek agent 干活，支持多轮会话续接（电脑端 dsh 记忆连续）。"
                + "前提：电脑端已启动 tools/dsh_bridge_server.py（ACP 官方通道 + /exec 直连）桥接服务，并在本工具配置好电脑地址(base_url)与访问令牌(token)。"
                + "动作：① action=run（默认）在电脑上执行任务并自动续接会话：task 用自然语言，如\"看看D盘有哪些项目文件夹\"；"
                + "② action=shell 直接把 task 当一条命令执行（bridge 直连，不经电脑端 LLM，毫秒级、输出原样）：凡是要\"跑这条命令并把原始输出贴回来\"就用它，如 task=\"git status\"；"
                + "③ action=pair 扫码一键配对（推荐）：扫电脑端配对页二维码，自动保存地址与令牌；"
                + "④ action=start 新建会话（重置电脑端记忆）；⑤ action=history 读当前会话历史（max=条数，默认10）；"
                + "⑥ action=get_status 检查桥接服务与 dsh 是否在线；"
                + "⑦ action=set_config 配置/修改电脑地址与令牌：base_url=http://电脑IP:8218，token=桥接服务启动时打印的令牌。"
                + "长任务：timeout 真的生效（秒，5~600，默认 120），编译/下载/长命令请给足（如 300）；超时会被中断且拿不到结果。"
                + "未配置或鉴权失败会明确报错，不会静默执行。"
                + "安全：只有配置了正确 token 才能调用；task 描述给电脑端执行，勿让用户代码注入。",
        category = "remote",
        aliases = {"dsh", "远程控制电脑", "电脑操作", "remote_pc"},
        actions = {
                @Action(name = "pair", description = "扫码一键配对：扫描电脑端配对页二维码自动保存地址与令牌"),
                @Action(name = "run", description = "在电脑上执行一个 dsh 任务（自动续接会话，适合自然语言任务）"),
                @Action(name = "shell", description = "把 task 当命令直接在电脑上执行（不经 LLM，毫秒级、输出原样）"),
                @Action(name = "start", description = "新建 dsh 会话（重置电脑端记忆），返回新的 session_id"),
                @Action(name = "history", description = "读当前会话最近历史（max=条数，默认10）"),
                @Action(name = "get_status", description = "检查桥接服务与 dsh 状态"),
                @Action(name = "set_config", description = "配置电脑地址(base_url)与访问令牌(token)")
        },
        params = {
                @Param(name = "action", type = "string", description = "操作: run(默认，自然语言任务) / shell(直接跑命令) / pair(扫码配对) / start / history / get_status / set_config", required = false),
                @Param(name = "task", type = "string", description = "run=任务描述（自然语言）；shell=要执行的命令原文", required = false),
                @Param(name = "shell", type = "string", description = "shell 动作的执行器: auto(默认，优先 pwsh 回退 powershell) / cmd / bash", required = false),
                @Param(name = "max", type = "integer", description = "history 读取条数（默认 10，范围 1~200）", required = false),
                @Param(name = "base_url", type = "string", description = "电脑端桥接地址，如 http://192.168.1.100:8218（set_config 用）", required = false),
                @Param(name = "token", type = "string", description = "桥接服务访问令牌（set_config 用，bridge 启动时打印）", required = false),
                @Param(name = "timeout", type = "integer", description = "任务超时秒数（默认 120，范围 5~600）", required = false)
        }
)
public class RemoteDshTool implements AITool {

    private static final String TAG = "RemoteDshTool";
    private static final String PREF = "remote_dsh_config";
    private static final String KEY_URL = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_SESSION = "session_id";
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int OUTPUT_LIMIT = 20000; // 输出截断，防撑爆对话
    /** 工具自声明超时的硬上限，与 OnlineToolManager.MAX_TOOL_TIMEOUT_MS 对齐 */
    private static final long MAX_TOOL_TIMEOUT_MS = 660_000L;

    private final Context context;

    public RemoteDshTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "remote_dsh";
    }

    @Override
    public String getDescription() {
        return "远程控制电脑（DeepSeek dsh 官方会话通道）：调用电脑上的 dsh（DeepSeek Harness Shell）执行任务，"
                + "让 AI 远程操作电脑——读文件/跑命令/查信息/让 DeepSeek agent 干活，支持多轮会话续接。"
                + "前提：电脑端已启动 tools/dsh_bridge_server.py(ACP 官方通道 + /exec 直连) 桥接服务，并配置好 base_url 与 token。"
                + "动作：run(执行任务+自动续接，自然语言) / shell(把 task 当命令直接跑，不经 LLM、输出原样) / "
                + "pair(扫码一键配对) / start(新建会话) / history(读会话历史) / get_status(检查状态) / set_config(配置)。"
                + "timeout 参数真的生效（秒，最长600），长任务请给足。"
                + "未配置或鉴权失败会明确报错，不会静默执行。安全：只有配置了正确 token 才能调用。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: run(默认，自然语言任务+自动续接) / shell(把 task 当命令直接跑，不经 LLM) / pair(扫码一键配对，推荐) / start(新建会话) / history(读历史) / get_status(检查状态) / set_config(配置)");
        params.put("task", "run=任务描述（自然语言，如\"看看D盘有哪些项目文件夹\"）；shell=要执行的命令原文（如 git status）");
        params.put("shell", "shell 动作的执行器: auto(默认) / cmd / bash");
        params.put("max", "history 读取条数（默认 10，范围 1~200）");
        params.put("base_url", "电脑端桥接地址，如 http://192.168.1.100:8218（set_config 用）");
        params.put("token", "桥接服务访问令牌（set_config 用，桥接服务启动时打印）");
        params.put("timeout", "任务超时秒数（默认 120，范围 5~600；长任务请给足，超时会中断且拿不到结果）");
        return params;
    }

    // ---------- 配置 ----------
    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private String getBaseUrl() {
        return getPrefs().getString(KEY_URL, "");
    }

    private String getToken() {
        return getPrefs().getString(KEY_TOKEN, "");
    }

    private String getSessionId() {
        return getPrefs().getString(KEY_SESSION, "");
    }

    private void saveSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        getPrefs().edit().putString(KEY_SESSION, sessionId.trim()).apply();
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = "run";
        Object actObj = parameters.get("action");
        if (actObj != null && !String.valueOf(actObj).trim().isEmpty()) {
            action = String.valueOf(actObj).trim().toLowerCase();
        }
        try {
            switch (action) {
                case "pair":
                    return handlePair();
                case "set_config":
                    return handleSetConfig(parameters);
                case "get_status":
                    return handleStatus();
                case "start":
                    return handleStart();
                case "history":
                    return handleHistory(parameters);
                case "shell":
                    return handleShell(parameters);
                case "run":
                default:
                    return handleRun(parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "execute error: " + e.getMessage(), e);
            return AIToolResult.fail("remote_dsh 执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private AIToolResult handleSetConfig(Map<String, Object> parameters) {
        String baseUrl = parameters.get("base_url") != null ? String.valueOf(parameters.get("base_url")).trim() : "";
        String token = parameters.get("token") != null ? String.valueOf(parameters.get("token")).trim() : "";
        String sessionId = parameters.get("session_id") != null ? String.valueOf(parameters.get("session_id")).trim() : "";
        if (baseUrl.isEmpty() && token.isEmpty() && sessionId.isEmpty()) {
            // 未传任何值：返回当前配置（token 打码）
            String cur = getBaseUrl();
            String tok = getToken();
            String sid = getSessionId();
            return AIToolResult.success(
                    "当前 remote_dsh 配置：\nbase_url=" + (cur.isEmpty() ? "(未配置)" : cur)
                            + "\ntoken=" + (tok.isEmpty() ? "(未配置)" : tok.substring(0, Math.min(4, tok.length())) + "***")
                            + "\nsession_id=" + (sid.isEmpty() ? "(未创建)" : sid)
                            + "\n\n配置方法：action=set_config 传 base_url=http://电脑IP:8218 和 token=桥接服务启动时打印的令牌");
        }
        // 校验 base_url
        if (!baseUrl.isEmpty()) {
            String lower = baseUrl.toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return AIToolResult.fail("base_url 必须以 http:// 或 https:// 开头，如 http://192.168.1.100:8218");
            }
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
        SharedPreferences.Editor ed = getPrefs().edit();
        if (!baseUrl.isEmpty()) ed.putString(KEY_URL, baseUrl);
        if (!token.isEmpty()) ed.putString(KEY_TOKEN, token);
        if (!sessionId.isEmpty()) ed.putString(KEY_SESSION, sessionId);
        ed.apply();
        return AIToolResult.success("remote_dsh 配置已保存：\nbase_url="
                + (baseUrl.isEmpty() ? getBaseUrl() : baseUrl)
                + "\ntoken=" + (token.isEmpty() ? "(保持原值)" : token.substring(0, Math.min(4, token.length())) + "***")
                + "\nsession_id=" + (sessionId.isEmpty() ? "(保持原值)" : sessionId));
    }

    private AIToolResult handlePair() {
        RemoteDshPairBridge.lastResult = null;
        try {
            Intent intent = new Intent(context, RemoteDshPairScanActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "start scan activity: " + e.getMessage(), e);
            return AIToolResult.fail("无法打开扫码页: " + e.getMessage());
        }
        // 等待扫码结果（最多 120 秒）
        long deadline = System.currentTimeMillis() + 120_000;
        String r;
        while ((r = RemoteDshPairBridge.lastResult) == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return AIToolResult.fail("扫码被中断");
            }
        }
        if (r == null) {
            return AIToolResult.fail("等待扫码超时（120秒）：请确认手机相机已对准电脑屏幕上的配对二维码。"
                    + "\n备选：action=set_config 手动填 base_url=http://电脑IP:8218 和 token=桥接服务启动时打印的令牌");
        }
        String baseUrl = getBaseUrl();
        String tok = getToken();
        if (baseUrl.isEmpty() || tok.isEmpty()) {
            return AIToolResult.fail("扫码完成但配置未生效（base_url/token 为空），请重试或手动 set_config");
        }
        return AIToolResult.success("扫码配对成功 ✓\nbase_url=" + baseUrl
                + "\ntoken=" + tok.substring(0, Math.min(4, tok.length())) + "***"
                + "\n\n现在可以对 AI 说：远程控制电脑 / 在电脑上执行...");
    }

    private AIToolResult handleStatus() {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：请先 action=set_config 设置 base_url(电脑地址) 和 token(访问令牌)。"
                    + "\n电脑端需先启动: python tools/dsh_bridge_server.py --token 你的令牌");
        }
        try {
            Map<String, Object> resp = httpJson(baseUrl + "/status", "GET", null, 30);
            if (resp == null) {
                return AIToolResult.fail("无法连接电脑端桥接服务: " + baseUrl
                        + "\n请确认：① 电脑端服务已启动 ② 手机与电脑同一网络 ③ 地址端口正确");
            }
            Object err = resp.get("error");
            if (err != null && "unauthorized".equals(err)) {
                return AIToolResult.fail("鉴权失败(401)：token 不正确，请 action=set_config 重新配置 token");
            }
            Object ver = resp.get("version");
            StringBuilder sb = new StringBuilder("桥接服务在线 ✓（ACP 通道，桥接 v"
                    + (ver != null ? ver : "?") + "）\n");
            Object channels = resp.get("channels");
            boolean acpOk = channels != null && Boolean.TRUE.equals(
                    ((Map<?, ?>) channels).get("session_acp"));
            // 版本号一律来自 ACP 自报（agentInfo），不再写死——之前写死 "dsh 0.1.5"，
            // 手机端 AI 据此判断"版本不兼容"，实际运行的是另一个版本，属于误导。
            String acpVer = "";
            Object agent = resp.get("acp_agent");
            if (agent instanceof Map) {
                Object n = ((Map<?, ?>) agent).get("name");
                Object v = ((Map<?, ?>) agent).get("version");
                if (n != null || v != null) {
                    acpVer = "（" + (n != null ? n : "?") + (v != null ? " " + v : "") + "）";
                }
            }
            sb.append("ACP 官方通道").append(acpVer).append(": ")
                    .append(acpOk ? "可用 ✓" : "不可用 ✗").append("\n");
            Object pp = resp.get("permission_policy");
            if (pp != null) {
                sb.append("权限自动应答: ").append(pp).append("（allow=写文件/跑命令自动放行一次）\n");
            }
            Object streams = resp.get("session_streams");
            if (streams instanceof java.util.List) {
                sb.append("活跃会话流: ").append(((java.util.List<?>) streams).size()).append("\n");
            }
            Object cnt = resp.get("sessions_count");
            if (cnt != null) sb.append("电脑端 dsh 会话数: ").append(cnt).append("\n");
            String sid = getSessionId();
            sb.append("当前会话: ").append(sid.isEmpty() ? "(未创建，run 时自动创建)" : sid);
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("查询状态异常: " + e.getMessage());
        }
    }

    private AIToolResult handleStart() {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置（还没配对过电脑）。"
                    + "\n最简单：对 AI 说「远程配对」→ 打开相机扫电脑配对页的二维码（电脑端先双击 tools\\start_dsh_bridge.bat，"
                    + "配对页 http://127.0.0.1:8218/pair 会自动打开，扫第一个码即可）"
                    + "\n也可以手动：action=set_config base_url=<电脑地址> token=<令牌>");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("action", "start");
            Map<String, Object> resp = httpJson(baseUrl + "/session", "POST", body, 30);
            if (resp == null) {
                return AIToolResult.fail("无法连接电脑端桥接服务: " + baseUrl
                        + "\n请确认：① 电脑端服务已启动 ② 手机与电脑同一网络 ③ 地址端口正确");
            }
            Object err = resp.get("error");
            if (err != null && "unauthorized".equals(err)) {
                return AIToolResult.fail("鉴权失败(401)：token 不正确，请 action=set_config 重新配置 token");
            }
            Object sid = resp.get("session_id");
            if (sid == null || String.valueOf(sid).trim().isEmpty()) {
                return AIToolResult.fail("新建会话失败: " + resp);
            }
            saveSessionId(String.valueOf(sid));
            return AIToolResult.success("已新建 dsh 会话 ✓（电脑端记忆已重置）\nsession_id=" + sid);
        } catch (Exception e) {
            return AIToolResult.fail("新建会话异常: " + e.getMessage());
        }
    }

    private AIToolResult handleHistory(Map<String, Object> parameters) {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：请先 action=set_config 设置 base_url 和 token。");
        }
        String sid = getSessionId();
        if (sid.isEmpty()) {
            return AIToolResult.fail("还没有会话：请先 action=run 执行任务（会自动创建会话）或 action=start 新建会话");
        }
        int max = 10;
        Object mObj = parameters.get("max");
        if (mObj != null) {
            try {
                max = (int) Math.min(200, Math.max(1, Double.parseDouble(String.valueOf(mObj))));
            } catch (Exception ignored) {
            }
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("action", "history");
            body.put("session_id", sid);
            body.put("max", max);
            Map<String, Object> resp = httpJson(baseUrl + "/session", "POST", body, 30);
            if (resp == null) {
                return AIToolResult.fail("无法连接电脑端桥接服务: " + baseUrl);
            }
            Object err = resp.get("error");
            if (err != null && "unauthorized".equals(err)) {
                return AIToolResult.fail("鉴权失败(401)：token 不正确，请 action=set_config 重新配置 token");
            }
            Object cnt = resp.get("count");
            Object text = resp.get("text");
            if (text == null) {
                // 会话失效/取自失败时，把原因如实带出来（以前只显示"(空)"，看不出为什么）
                return AIToolResult.fail("读取会话历史失败"
                        + (err != null ? (": " + err) : "（bridge 未返回 text，可能是会话已失效，可 action=start 重开）"));
            }
            return AIToolResult.success("会话历史（最近 " + (cnt != null ? cnt : max) + " 条）：\n"
                    + String.valueOf(text));
        } catch (Exception e) {
            return AIToolResult.fail("读取历史异常: " + e.getMessage());
        }
    }

    private AIToolResult handleRun(Map<String, Object> parameters) {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置（还没配对过电脑）。"
                    + "\n最简单：对 AI 说「远程配对」→ 打开相机扫电脑配对页的二维码（电脑端先双击 tools\\start_dsh_bridge.bat，"
                    + "配对页 http://127.0.0.1:8218/pair 会自动打开，扫第一个码即可）"
                    + "\n也可以手动：action=set_config base_url=<电脑地址> token=<令牌>");
        }
        String task = parameters.get("task") != null ? String.valueOf(parameters.get("task")).trim() : "";
        if (task.isEmpty()) {
            return AIToolResult.fail("缺少参数: task（告诉电脑干什么，自然语言即可）");
        }
        int timeout = resolveTimeout(parameters);
        try {
            String sid = getSessionId();
            // 会话模式：没有 session_id 先自动创建
            if (sid.isEmpty()) {
                sid = createSession(baseUrl);
                if (sid == null) {
                    return AIToolResult.fail("自动创建会话失败（bridge/ACP 未启动？）：" + baseUrl);
                }
                saveSessionId(sid);
            }

            // 发任务；若会话已失效（bridge/ACP 重启、电脑端会话被清理）自动重建会话并重试一次
            Map<String, Object> resp = null;
            Object ok = null, reply = null, dur = null, turn = null;
            String errText = "";
            for (int attempt = 0; attempt < 2; attempt++) {
                resp = sendPrompt(baseUrl, sid, task, timeout);
                if (resp == null) {
                    return AIToolResult.fail("无法连接电脑端桥接服务: " + baseUrl
                            + "\n请确认：① 电脑端服务已启动 ② 手机与电脑同网（或隧道/映射可用） ③ 地址端口正确");
                }
                Object err = resp.get("error");
                if (err != null && "unauthorized".equals(err)) {
                    return AIToolResult.fail("鉴权失败(401)：token 不正确，请 action=set_config 重新配置 token");
                }
                ok = resp.get("ok");
                reply = resp.get("reply");
                dur = resp.get("duration_ms");
                turn = resp.get("turn");
                errText = err == null ? "" : String.valueOf(err);
                if (Boolean.TRUE.equals(ok) || attempt > 0 || !isDeadSessionError(errText)) {
                    break;
                }
                // 会话失效 → 重建后重试（不打扰用户）
                Log.w(TAG, "会话失效，自动重建: " + errText);
                String nsid = createSession(baseUrl);
                if (nsid == null) break;
                sid = nsid;
                saveSessionId(nsid);
            }

            String out = reply != null ? String.valueOf(reply) : "";
            if (out.length() > OUTPUT_LIMIT) {
                out = out.substring(0, OUTPUT_LIMIT) + "\n...[输出过长已截断]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(Boolean.TRUE.equals(ok) ? "电脑任务完成 ✓" : "电脑任务失败 ✗");
            sb.append("（会话续接模式）");
            if (turn != null) sb.append(" 第").append(turn).append("轮");
            if (dur != null) sb.append(" 耗时 ").append(dur).append("ms");
            if (!Boolean.TRUE.equals(ok)) {
                // 关键：把 bridge/ACP 给的失败原因如实带出来，否则用户只看到空输出无从判断
                if (!errText.isEmpty()) sb.append("\n原因: ").append(errText);
                sb.append("\n提示：电脑端 dsh 可能没装好/未登录（ACP serve 不可用），或该任务超时；"
                        + "可用 action=get_status 看通道状态，或 action=start 重开会话");
            }
            sb.append("\n").append(out);
            return Boolean.TRUE.equals(ok)
                    ? AIToolResult.success(sb.toString())
                    : AIToolResult.fail(sb.toString());
        } catch (java.net.SocketTimeoutException te) {
            return AIToolResult.fail("电脑端在 " + timeout + "s 内没有返回（HTTP 读超时）：任务可能仍在电脑上执行。"
                    + "\n建议：① 调大 timeout（最长 600s）后重试；② 若只是要跑一条命令拿输出，改用 action=shell（不经 LLM，快得多）");
        } catch (java.io.IOException io) {
            return AIToolResult.fail("无法连接电脑端桥接服务: " + baseUrl
                    + "\n请确认：① 电脑端服务已启动（双击 tools\\start_dsh_bridge.bat）② 手机与电脑同网/隧道可用 ③ 地址端口正确"
                    + "\n原因: " + io.getMessage());
        } catch (Exception e) {
            return AIToolResult.fail("远程调用异常: " + e.getMessage());
        }
    }

    /**
     * 声明本工具单次执行需要的超时（毫秒）。
     * 工具管理器默认只给 30s，但这里的任务是"在电脑上真跑"（起 dsh agent/跑命令，几十秒很常见）：
     * 实测传 timeout=150 仍在 30s 被掐断、模型只拿到空结果。故按 timeout + 45s 余量申报。
     */
    @Override
    public long executionTimeoutMs(Map<String, Object> args) {
        int t = resolveTimeout(args);
        return Math.min(MAX_TOOL_TIMEOUT_MS, (t + 45L) * 1000L);
    }

    /** 解析 timeout 参数（秒；5~600，默认 120） */
    private static int resolveTimeout(Map<String, Object> parameters) {
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        Object tObj = parameters != null ? parameters.get("timeout") : null;
        if (tObj != null) {
            try {
                timeout = (int) Math.min(MAX_TIMEOUT_SECONDS, Math.max(5, Double.parseDouble(String.valueOf(tObj))));
            } catch (Exception ignored) {
            }
        }
        return timeout;
    }

    /**
     * action=shell：把 task 当成一条命令，直接在电脑上执行（bridge POST /exec，不经 LLM）。
     * 适合"跑这条命令并把输出原样贴回来"：毫秒级返回，输出不被模型改写或省略。
     */
    private AIToolResult handleShell(Map<String, Object> parameters) {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置（还没配对过电脑）。"
                    + "\n最简单：对 AI 说「远程配对」→ 打开相机扫电脑配对页的二维码"
                    + "（电脑端先双击 tools\\start_dsh_bridge.bat）"
                    + "\n也可以手动：action=set_config base_url=<电脑地址> token=<令牌>");
        }
        String cmd = parameters.get("task") != null ? String.valueOf(parameters.get("task")).trim() : "";
        if (cmd.isEmpty() && parameters.get("cmd") != null) {
            cmd = String.valueOf(parameters.get("cmd")).trim();
        }
        if (cmd.isEmpty()) {
            return AIToolResult.fail("缺少参数: task（要执行的命令，如 Get-Date / cmd /c dir / git status）");
        }
        int timeout = resolveTimeout(parameters);
        Map<String, Object> body = new HashMap<>();
        body.put("cmd", cmd);
        body.put("timeout", timeout);
        Object sh = parameters.get("shell");
        if (sh != null && !String.valueOf(sh).trim().isEmpty()) {
            body.put("shell", String.valueOf(sh).trim());
        }
        try {
            Map<String, Object> resp = httpJson(baseUrl + "/exec", "POST", body, timeout + 20);
            if (resp == null) {
                return AIToolResult.fail("无法连接电脑端桥接服务: " + baseUrl
                        + "\n请确认：① 电脑端服务已启动（tools\\start_dsh_bridge.bat）② 手机与电脑同网/隧道可用 ③ 地址端口正确");
            }
            Object err = resp.get("error");
            if (err != null && "unauthorized".equals(err)) {
                return AIToolResult.fail("鉴权失败(401)：token 不正确，请 action=set_config 重新配置 token");
            }
            if (err != null) {
                return AIToolResult.fail("电脑端执行失败: " + err);
            }
            String out = resp.get("output") != null ? String.valueOf(resp.get("output")) : "";
            if (out.length() > OUTPUT_LIMIT) {
                out = out.substring(0, OUTPUT_LIMIT) + "\n...[输出过长已截断]";
            }
            boolean ok = Boolean.TRUE.equals(resp.get("ok"));
            StringBuilder sb = new StringBuilder();
            sb.append(ok ? "电脑命令执行完成 ✓" : "电脑命令执行失败 ✗（非零退出）");
            if (resp.get("exit_code") != null) sb.append(" exit=").append(resp.get("exit_code"));
            if (resp.get("duration_ms") != null) sb.append(" 耗时 ").append(resp.get("duration_ms")).append("ms");
            sb.append("\n").append(out.isEmpty() ? "(无输出)" : out);
            return ok ? AIToolResult.success(sb.toString()) : AIToolResult.fail(sb.toString());
        } catch (java.net.SocketTimeoutException te) {
            return AIToolResult.fail("电脑端命令在 " + timeout + "s 内没有返回（HTTP 读超时）");
        } catch (Exception e) {
            return AIToolResult.fail("命令执行异常: " + e.getMessage());
        }
    }

    /** 新建会话，返回 session_id（失败返回 null） */
    private String createSession(String baseUrl) {
        Map<String, Object> startBody = new HashMap<>();
        startBody.put("action", "start");
        try {
            Map<String, Object> startResp = httpJson(baseUrl + "/session", "POST", startBody, 30);
            Object newSid = startResp != null ? startResp.get("session_id") : null;
            if (newSid == null || String.valueOf(newSid).trim().isEmpty()) {
                Log.w(TAG, "创建会话失败: " + startResp);
                return null;
            }
            return String.valueOf(newSid);
        } catch (Exception e) {
            Log.w(TAG, "创建会话异常: " + e.getMessage());
            return null;
        }
    }

    /** 发一轮任务到桥接（prompt） */
    private Map<String, Object> sendPrompt(String baseUrl, String sid, String task, int timeout) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "prompt");
        body.put("session_id", sid);
        body.put("text", task);
        body.put("timeout", timeout);
        // 不在这里吞异常：读超时（电脑端迟迟不回）与连不上是两种问题，要让 handleRun 分辨并给出不同提示
        return httpJson(baseUrl + "/session", "POST", body, timeout + 30);
    }

    /**
     * 判断错误是否属于"会话已失效"（bridge/ACP 重启后旧 sessionId 就废了）。
     * 命中则自动开新会话重试一次，用户无感。
     */
    private static boolean isDeadSessionError(String err) {
        if (err == null) return false;
        String e = err.toLowerCase();
        return e.contains("session") || e.contains("not found") || e.contains("不存在")
                || e.contains("invalid") || e.contains("expired") || e.contains("失效")
                || e.contains("unknown") || e.contains("no such");
    }

    // ---------- HTTP ----------
    /**
     * 发送 HTTP 请求并解析 JSON 响应。
     * @return 解析后的 Map；连接失败返回 null；HTTP 错误也尝试解析 body。
     */
    private Map<String, Object> httpJson(String url, String method, Map<String, Object> body, int timeoutSec) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            String token = getToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = jsonEncode(body).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in != null ? readAll(in) : "";
            Map<String, Object> parsed = jsonDecode(text);
            if (parsed == null) parsed = new HashMap<>();
            parsed.put("_http_code", code);
            return parsed;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 请求体编码。改用 org.json：手写版会把 Boolean 变成字符串 "true"、也不支持嵌套对象/数组
     * （响应侧早就换成 org.json 了，这里保持一致）；org.json 失败时退回手写编码。
     */
    private static String jsonEncode(Map<String, Object> map) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            return o.toString();
        } catch (Exception ex) {
            Log.w(TAG, "请求体编码失败，退回手写编码: " + ex.getMessage());
            return jsonEncodeLoose(map);
        }
    }

    /** 手写兜底编码（escape 已覆盖引号/反斜杠/换行/控制字符） */
    private static String jsonEncodeLoose(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * JSON 响应 → Map。
     *
     * <p>优先用 org.json 递归解析：旧的手写轻量解析器**只认顶层值、不认嵌套对象**，
     * 于是 /status 里嵌套的 {@code channels.session_acp} 永远取不到 →
     * 明明 ACP 通道可用，App 也一律显示"ACP 官方通道: 不可用 ✗"（真机实测踩到）。
     * 手写版本保留为兜底（org.json 解析失败时使用）。
     */
    private static Map<String, Object> jsonDecode(String text) {
        Map<String, Object> map = new HashMap<>();
        if (text == null) return map;
        String s = text.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return map;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, jsonToJava(o.opt(k)));
            }
            return map;
        } catch (Throwable t) {
            Log.w(TAG, "org.json 解析失败，退回轻量解析: " + t.getMessage());
        }
        return jsonDecodeLoose(s);
    }

    /** org.json 值 → 纯 Java 值（嵌套对象转 Map、数组转 List，递归） */
    private static Object jsonToJava(Object v) {
        if (v instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) v;
            Map<String, Object> m = new HashMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, jsonToJava(o.opt(k)));
            }
            return m;
        }
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            java.util.List<Object> l = new java.util.ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(jsonToJava(a.opt(i)));
            return l;
        }
        return v;   // String / Integer / Long / Double / Boolean / null
    }

    private static Map<String, Object> jsonDecodeLoose(String s) {
        // 轻量 JSON 对象解析（仅一层 string/number/bool 值，兜底用）
        Map<String, Object> map = new HashMap<>();
        if (s == null) return map;
        if (s.isEmpty() || s.charAt(0) != '{') return map;
        int i = 1;
        int n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
            if (i >= n || s.charAt(i) == '}') break;
            if (s.charAt(i) != '"') { i++; continue; }
            int keyEnd = s.indexOf('"', i + 1);
            if (keyEnd < 0) break;
            String key = s.substring(i + 1, keyEnd);
            i = s.indexOf(':', keyEnd);
            if (i < 0) break;
            i++;
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
            if (i >= n) break;
            char c = s.charAt(i);
            if (c == '"') {
                int valEnd = i + 1;
                StringBuilder val = new StringBuilder();
                while (valEnd < n) {
                    char vc = s.charAt(valEnd);
                    if (vc == '\\' && valEnd + 1 < n) {
                        char nx = s.charAt(valEnd + 1);
                        switch (nx) {
                            case 'n': val.append('\n'); break;
                            case 'r': val.append('\r'); break;
                            case 't': val.append('\t'); break;
                            case '"': val.append('"'); break;
                            case '\\': val.append('\\'); break;
                            default: val.append(nx);
                        }
                        valEnd += 2;
                        continue;
                    }
                    if (vc == '"') break;
                    val.append(vc);
                    valEnd++;
                }
                map.put(key, val.toString());
                i = valEnd + 1;
            } else if (c == 't') { map.put(key, Boolean.TRUE); i += 4; }
            else if (c == 'f') { map.put(key, Boolean.FALSE); i += 5; }
            else if (c == 'n') { map.put(key, null); i += 4; }
            else if (c == '-' || (c >= '0' && c <= '9')) {
                int j = i;
                while (j < n && (s.charAt(j) == '-' || s.charAt(j) == '+' || s.charAt(j) == '.' || s.charAt(j) == 'e' || s.charAt(j) == 'E' || Character.isDigit(s.charAt(j)))) j++;
                String num = s.substring(i, j);
                try {
                    if (num.contains(".") || num.contains("e") || num.contains("E")) map.put(key, Double.parseDouble(num));
                    else map.put(key, Long.parseLong(num));
                } catch (NumberFormatException ignored) {
                    map.put(key, num);
                }
                i = j;
            } else { i++; }
        }
        return map;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
