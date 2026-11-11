package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程 dsh 工具 v5：通过电脑端 dsh 桥接服务（tools/dsh_bridge_server.py v5）远程调用
 * DeepSeek dsh（DeepSeek Harness Shell），让 AI 远程操作电脑。
 *
 * 通道：**原生插件通道**——由 DSH 宿主直接提供 HTTP 端点（webServer），
 * session/new + session/prompt + session/update 流式；手机端协议/接口自 v3 起未变。
 * 同一 session_id 连续调用 = 多轮会话续接（电脑端 dsh 记忆连续）。插件版本/模型/预设一律由 /status 如实上报，
 * 代码里不写死版本号（写死过 "dsh 0.1.5"，手机端 AI 据此误判"版本不兼容"）。
 *
 * v5 要点（2026-10-03，客户端升级到 0.2.0-rc.2 后实测驱动）：
 *   * 0.2.0 的 acp profile 只提供 stdio，`dsh --profile acp serve --host/--port/--token`
 *     直接报 `unknown option '--host'`（那条 HTTP 通道来自第三方插件 dsh-acp-server，已不再需要）；
 *     桥接改用原生 stdio：无插件、不占 7800 端口、不需要 ACP token。
 *   * 桥接自动应答 session/request_permission（写文件/跑命令类任务不再永久挂起）；
 *   * 本工具按 timeout+45s 向 OnlineToolManager 申报执行超时（不再被其 30s 默认值掐断），
 *     超时时如实说明"等了多久、任务可能仍在电脑上"；
 *   * 新增 shell 动作：把 task 当命令经 bridge /exec 直连执行（不经电脑端 LLM，毫秒级、输出原样）。
 *
 * 架构：
 *   手机 App → HTTP(Bearer token) → 电脑端 DSH 原生插件 smartquiz-remote（HTTP + 设备令牌鉴权）
 *                                                             ├→ /exec 直连命令（shell 动作）
 *                                                             └→ 任务执行：dsh --profile headless（含多轮记忆 --session-id）
 *
 * 动作：
 *   run(默认)  执行任务并自动续接会话：配置里已有 session_id 则直接续接，没有则先自动创建；
 *              task=自然语言任务描述；返回 AI 的回复。
 *   start      显式新建会话（重置电脑端 dsh 记忆），返回新的 session_id。
 *   history    读当前会话最近 N 条历史（文本摘要），检查 dsh 侧记忆。
 *   get_status 检查插件服务与 dsh 通道状态。
 *   set_config 配置 base_url / token / session_id（session_id 通常自动维护，一般无需手填）。
 *
 * 安全红线：
 *   * 桥接服务必须带 token 鉴权（Authorization: Bearer），未配置时不执行任何任务；
 *   * base_url 必须是 http:// 或 https:// 开头，禁止其他协议；
 *   * 插件只在 8218 上暴露自己的接口（Bearer 令牌鉴权），不会把 DSH 的 Web 界面暴露到局域网。
 */
@Tool(
        value = "remote_dsh",
        description = "远程控制电脑（DeepSeek dsh 官方会话通道）：调用电脑上安装的 dsh（DeepSeek Harness Shell）执行任务，"
                + "让 AI 远程操作电脑——读文件/跑命令/查信息/让 DeepSeek agent 干活，支持多轮会话续接（电脑端 dsh 记忆连续）。"
                + "前提：电脑端已安装 DSH 原生插件 smartquiz-remote（HTTP 端点 + /exec 直连），并在本工具配置好电脑地址(base_url)与访问令牌(token)。"
                + "动作：① action=run（默认）在电脑上执行任务并自动续接会话：task 用自然语言，如\"看看D盘有哪些项目文件夹\"；"
                + "② action=shell 直接把 task 当一条命令执行（bridge 直连，不经电脑端 LLM，毫秒级、输出原样）：凡是要\"跑这条命令并把原始输出贴回来\"就用它，如 task=\"git status\"；"
                + "③ action=pair 扫码一键配对（推荐）：扫电脑端配对页二维码，自动保存地址与令牌；"
                + "④ action=start 新建会话（重置电脑端记忆）；⑤ action=history 读当前会话历史（max=条数，默认10）；"
                + "⑥ action=get_status 检查桥接服务与 dsh 是否在线；"
                + "⑦ action=connect 连接电脑 / action=disconnect 临时停用（配置保留，断开后工具拒绝执行）/ "
                + "action=clear_config 清空配置（地址令牌全清；别用 set_config 传假地址来「断开」，那会把配置写坏）；"
                + "⑧ action=set_config 配置/修改电脑地址与令牌：base_url=http://电脑IP:8218，token=桥接服务启动时打印的令牌。"
                + "用户也可以自己在手机上操作：「工具集 → 设置与系统环境 → 远程连接（电脑）」里连接/断开/清除配置/扫码。"
                + "长任务：timeout 真的生效（秒，5~600，默认 120），编译/下载/长命令请给足（如 300）；超时会被中断且拿不到结果。"
                + "未配置或鉴权失败会明确报错，不会静默执行。"
                + "安全：只有配置了正确 token 才能调用；task 描述给电脑端执行，勿让用户代码注入。",
        category = "remote",
        aliases = {"dsh", "远程控制电脑", "电脑操作", "remote_pc"},
        actions = {
                @Action(name = "pair", description = "扫码一键配对：扫描电脑端配对页二维码自动保存地址与令牌"),
                @Action(name = "run", description = "在电脑上执行一个 dsh 任务（自动续接会话，适合自然语言任务）"),
                @Action(name = "shell", description = "把 task 当命令直接在电脑上执行（不经 LLM，毫秒级、输出原样）"),
                @Action(name = "connect", description = "用已保存的地址/令牌连接电脑（探测桥接，通了才允许执行任务）"),
                @Action(name = "disconnect", description = "断开电脑连接（配置保留；断开期间工具拒绝执行）"),
                @Action(name = "clear_config", description = "清空配对配置（地址/令牌/会话全清；要临时停用请用 disconnect）"),
                @Action(name = "start", description = "新建 dsh 会话（重置电脑端记忆），返回新的 session_id"),
                @Action(name = "history", description = "读当前会话最近历史（max=条数，默认10）"),
                @Action(name = "list_sessions", description = "列出电脑上最近的对话（看电脑端在聊什么）"),
                @Action(name = "read_session", description = "只读查看电脑上某条对话（target_session_id 必填）"),
                @Action(name = "attach_session", description = "把电脑上某条对话接到当前会话继续（target_session_id 必填）"),
                @Action(name = "sync_send", description = "把话发进电脑上正在打开的那条会话（真双端同步，target 默认用上次 attach/list 的）"),
                @Action(name = "sync_state", description = "查同步目标那条会话现在是否在电脑上打开（live）"),
                @Action(name = "health", description = "电脑端自检：工作目录/DSH_HOME/预设挂载/执行通道 五项检查（插件 v8）"),
                @Action(name = "view", description = "协议 v2 统一视图：状态卡 + 自检卡 + 会话卡 + 时间线（双端同源）"),
                @Action(name = "session", description = "查/管理电脑端会话身份（session_action: state 默认 / list / attach），id 由电脑签发"),
                @Action(name = "events", description = "拉电脑端事件流（/v2/stream，按 since 增量），看刚才发生了什么；结果可直接 ack"),
                @Action(name = "reconcile", description = "双端对账：拿电脑侧权威摘要（digest/last_seq/未回执条数），和本机状态比"),
                @Action(name = "ack", description = "回执：告诉电脑本机已收到 seq 之前的全部消息（对账水位线，只增不减）"),
                @Action(name = "get_status", description = "检查桥接服务与 dsh 状态"),
                @Action(name = "set_config", description = "配置电脑地址(base_url)与访问令牌(token)")
        },
        params = {
                @Param(name = "action", type = "string", description = "操作: run(默认，自然语言任务) / shell(直接跑命令) / connect(连接电脑) / disconnect(临时停用，保留配置) / clear_config(清空配置) / pair(扫码配对) / register_device(把主令牌换成可单独吊销的设备令牌，补登记用) / start / history / list_sessions(列电脑上的对话) / read_session(只读看某条) / attach_session(接到某条继续) / get_status / set_config / health(电脑端自检) / reconcile(双端对账) / ack(回执水位线)", required = false),
                @Param(name = "task", type = "string", description = "run=任务描述（自然语言）；shell=要执行的命令原文", required = false),
                @Param(name = "shell", type = "string", description = "shell 动作的执行器: auto(默认，优先 pwsh 回退 powershell) / cmd / bash", required = false),
                @Param(name = "max", type = "integer", description = "history/read_session/list_sessions 的条数（默认 10，范围 1~200）", required = false),
                @Param(name = "target_session_id", type = "string", description = "read_session/attach_session 要操作的电脑端会话 id（来自 list_sessions）", required = false),
                @Param(name = "base_url", type = "string", description = "电脑端桥接地址，如 http://192.168.1.100:8218（set_config 用）", required = false),
                @Param(name = "token", type = "string", description = "桥接服务访问令牌（set_config 用，bridge 启动时打印）", required = false),
                @Param(name = "timeout", type = "integer", description = "任务超时秒数（默认 120，范围 5~600）", required = false),
                @Param(name = "seq", type = "integer", description = "ack 用：已确认收到的消息序号（水位线，只增不减）", required = false),
                @Param(name = "code", type = "string", description = "对话码（session 动作用；留空=默认直连对话）。会话 id 永远由电脑签发", required = false),
                @Param(name = "session_action", type = "string", description = "session 动作的子操作: state(默认，查 code 绑到哪条) / list(列电脑会话) / attach(把 target_session_id 接到 code 上)", required = false),
                @Param(name = "since", type = "integer", description = "events 用：从这个序号之后开始拉（默认 0）", required = false),
                @Param(name = "wait_ms", type = "integer", description = "events 用：最多等多久（默认 3000，范围 500~15000）", required = false),
                @Param(name = "session_id", type = "string", description = "health/reconcile/ack 作用的目标会话 id（默认用当前会话）", required = false)
        }
)
public class RemoteDshTool implements AITool {

    private static final String TAG = "RemoteDshTool";
    private static final String PREF = "remote_dsh_config";
    private static final String KEY_URL = "base_url";
    private static final String KEY_TOKEN = "token";
    /**
     * 设备令牌（2026-10-06）：配对时用二维码里的主令牌 POST /pair/claim 换来的**可单独吊销**凭据，
     * 优先级高于主令牌。有它才能在电脑端面板看到"这台设备"，并能只吊销这一台。
     */
    public static final String KEY_DEVICE_TOKEN = "device_token";
    /** 设备 id（dev-xxxx，吊销时用） */
    public static final String KEY_DEVICE_ID = "device_id";
    /** 主令牌：二维码里带的原值。设备令牌换不到或被吊销时退回它，也是重新登记设备的凭据 */
    public static final String KEY_MAIN_TOKEN = "main_token";
    private static final String KEY_SESSION = "session_id";
    /**
     * 两端协商出来的「直连电脑会话 id」（session-xxxx）：由电脑插件落盘并在每次回话里
     * 带回来，手机只负责保存、下次原样带回去。这样手机和电脑说的是**同一条对话**，
     * 而不是各记各的随机 id（旧行为：电脑越用越多会话、两边对不上）。
     */
    private static final String KEY_DSH_SESSION = "dsh_session_id";
    /** 该电脑会话的标题（只用于界面显示，随时可从电脑刷新） */
    private static final String KEY_DSH_TITLE = "dsh_session_title";
    /** 直连对话码：留空 = 默认直连对话；填 work 之类 = 该码的专属对话（两端同一个码） */
    private static final String KEY_DIRECT_CODE = "direct_code";
    /** 连接开关：false = 用户主动断开（配置保留，但工具一律拒绝执行，直到重新连接） */
    private static final String KEY_CONNECTED = "connected";
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int OUTPUT_LIMIT = 20000; // 输出截断，防撑爆对话

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
                + "前提：电脑端已安装 DSH 原生插件 smartquiz-remote(HTTP 端点 + /exec 直连)，并配置好 base_url 与 token。"
                + "动作：run(执行任务+自动续接，自然语言) / shell(把 task 当命令直接跑，不经 LLM、输出原样) / "
                + "connect(连接电脑) / disconnect(临时停用，保留配置) / clear_config(清空配置) / pair(扫码一键配对) / "
                + "start(新建会话) / history(读会话历史) / get_status(检查状态) / set_config(配置)。"
                + "界面入口：工具集 → 设置与系统环境 → 远程连接（电脑）。"
                + "timeout 参数真的生效（秒，最长600），长任务请给足。"
                + "未配置或鉴权失败会明确报错，不会静默执行。安全：只有配置了正确 token 才能调用。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: run(默认，自然语言任务+自动续接) / shell(把 task 当命令直接跑，不经 LLM) / connect(连接电脑) / disconnect(临时停用，保留配置) / clear_config(清空配置) / pair(扫码一键配对) / start(新建会话) / history(读历史) / get_status(检查状态) / set_config(配置)");
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

    String getBaseUrl() {
        return getPrefs().getString(KEY_URL, "");
    }

    /**
     * 当前使用的令牌：**优先设备令牌**（可单独吊销），没有才用主令牌。
     * 兼容老配置：只存过 KEY_TOKEN 的手机走 device_token 为空 → 返回 KEY_TOKEN（原来的行为不变）。
     */
    String getToken() {
        SharedPreferences p = getPrefs();
        String dt = p.getString(KEY_DEVICE_TOKEN, "");
        if (dt != null && !dt.trim().isEmpty()) return dt.trim();
        String mt = p.getString(KEY_MAIN_TOKEN, "");
        if (mt != null && !mt.trim().isEmpty()) return mt.trim();
        return p.getString(KEY_TOKEN, "");
    }

    /** 主令牌（放弃设备令牌时用） */
    private String getMainToken() {
        SharedPreferences p = getPrefs();
        String mt = p.getString(KEY_MAIN_TOKEN, "");
        if (mt != null && !mt.trim().isEmpty()) return mt.trim();
        return p.getString(KEY_TOKEN, "");
    }

    /** 是否在用设备令牌（面板上区分"这台设备已登记"与"还在用主令牌"） */
    public static boolean isUsingDeviceToken(Context c) {
        String dt = prefsOf(c).getString(KEY_DEVICE_TOKEN, "");
        return dt != null && !dt.trim().isEmpty();
    }

    private String getSessionId() {
        return getPrefs().getString(KEY_SESSION, "");
    }

    // ---------- 直连会话（手机专属）与跟随会话（桌面选的那条）分开 ----------
    // 2026-11-12 v2：@direct 只给直连用；选择电脑会话只写 @code:follow。
    private static final String KEY_DIRECT_SESSION = "direct_session";
    private static final String KEY_DIRECT_TITLE = "direct_title";

    public static String getDirectSessionId(Context c) {
        String s = prefsOf(c).getString(KEY_DIRECT_SESSION, "");
        if (s == null || s.trim().isEmpty()) {
            // 旧数据迁移：dsh_session_id 以前可能被选择会话覆盖过，和跟随会话相同就不算直连
            String legacy = prefsOf(c).getString(KEY_DSH_SESSION, "");
            if (legacy != null && !legacy.trim().isEmpty()
                    && !legacy.trim().equals(canonicalSessionId(c))) {
                s = legacy;
            }
        }
        return s == null ? "" : s.trim();
    }

    public static void setDirectSessionId(Context c, String id) {
        String v = id == null ? "" : id.trim();
        android.content.SharedPreferences.Editor ed = prefsOf(c).edit();
        if (v.isEmpty()) {
            ed.remove(KEY_DIRECT_SESSION).remove(KEY_DIRECT_TITLE).remove(KEY_DSH_SESSION);
        } else {
            ed.putString(KEY_DIRECT_SESSION, v);
            ed.putString(KEY_DSH_SESSION, v);   // 兼容旧代码
        }
        ed.apply();
    }

    public static void setDirectSession(Context c, String id, String title) {
        setDirectSessionId(c, id);
        String t = title == null ? "" : title.trim();
        android.content.SharedPreferences.Editor ed = prefsOf(c).edit();
        if (t.isEmpty()) ed.remove(KEY_DIRECT_TITLE).remove(KEY_DSH_TITLE);
        else ed.putString(KEY_DIRECT_TITLE, t).putString(KEY_DSH_TITLE, t);
        ed.apply();
    }

    public static String getDirectTitle(Context c) {
        String s = prefsOf(c).getString(KEY_DIRECT_TITLE, "");
        return s == null ? "" : s.trim();
    }

    public static String getDirectCode(Context c) {
        String s = prefsOf(c).getString(KEY_DIRECT_CODE, "");
        return s == null ? "" : s.trim();
    }

    /** 改对话码 = 换一条电脑对话：本地旧 id 立即作废（再由电脑侧确认新 code 的绑定） */
    public static void setDirectCode(Context c, String code) {
        String v = code == null ? "" : code.trim();
        android.content.SharedPreferences.Editor ed = prefsOf(c).edit();
        if (v.isEmpty()) ed.remove(KEY_DIRECT_CODE); else ed.putString(KEY_DIRECT_CODE, v);
        // 换直连码只作废直连身份；跟随会话（attached/sync）不受影响
        ed.remove(KEY_DIRECT_SESSION).remove(KEY_DIRECT_TITLE)
          .remove(KEY_DSH_SESSION).remove(KEY_DSH_TITLE).apply();
    }

    /**
     * 问电脑"这个对话码对应哪条电脑会话"，并写回本机。
     * 电脑端 `direct_state` 是唯一权威：手机丢了本地 id（重装/清数据）时靠它接回同一条对话。
     */
    public static Map<String, Object> syncDirectState(Context c) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("action", "direct_state");
        String code = getDirectCode(c);
        if (!code.isEmpty()) body.put("session_code", code);
        // 电脑端是权威：手机记得的旧 id 也带上，万一电脑侧绑定丢了可以认回来
        // （不会新建一条把老对话甩掉；真正建会话仍由电脑端在第一条消息时完成）
        String known = getDirectSessionId(c);
        if (!known.isEmpty()) body.put("target_session_id", known);
        String raw = tool.postSessionRaw(body, 15);
        org.json.JSONObject j = new org.json.JSONObject(raw);
        String id = j.optString("direct_session_id", "");
        String title = j.optString("title", "");
        if (!id.isEmpty() && id.equals(canonicalSessionId(c))) {
            // 历史上 @direct 被"选择会话"覆盖过：不再把它当直连会话
            id = "";
            title = "";
        }
        setDirectSession(c, id, title);
        Map<String, Object> out = new HashMap<>();
        out.put("direct_session_id", id);
        out.put("title", title);
        out.put("exists", j.optBoolean("exists", false));
        return out;
    }

    private void saveSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        getPrefs().edit().putString(KEY_SESSION, sessionId.trim()).apply();
    }

    // ---------- 远程会话（不再有"模式"） ----------
    //
    // 旧版有 auto / direct / sync 三种模式，由手机端猜该走哪条路；猜错的代价就是
    // 「同步失败、连接错误」。现在收敛成一条规则，由**电脑端**按事实决定执行位置：
    //   · 会话在桌面端活着 → 宿主内执行（零冷启动、双端同屏）
    //   · 否则             → CLI 续写（桌面没开也能用）
    // 手机只保留"跟哪条对话"（对话码 / sync_target），不再保存 mode。
    private static final String KEY_SYNC_TARGET = "sync_target";
    private static final String KEY_SYNC_TITLE = "sync_title";

    // ===== 单一会话身份（2026-11-12） =====
    //
    // 以前"直连对话（dsh_session_id）"和"同步目标（sync_target）"是两个键，各写各的：
    // 连接页会同时显示两条 id，通道各自 attach，谁也不服谁。现在只有一个权威 id：
    //   attached_session / attached_title
    // 旧键仍保留为"镜像"（旧代码/旧 APK 要读），但读一律先读新键，写一律经 setCanonicalSession。
    private static final String KEY_ATTACHED = "attached_session";
    private static final String KEY_ATTACHED_TITLE = "attached_title";
    private static final String KEY_TRANSPORT_SEQ = "transport_seq_";
    /** 一次性迁移：直连/跟随拆分前，@direct 可能被"选择会话"写过，首次升级要清空 */
    private static final String KEY_DIRECT_SPLIT = "direct_split_v2";

    /** 当前附着的电脑会话 id（唯一权威；旧键自动迁移） */
    public static String canonicalSessionId(Context c) {
        android.content.SharedPreferences p = prefsOf(c);
        String s = p.getString(KEY_ATTACHED, "");
        if (s == null || s.trim().isEmpty()) s = p.getString(KEY_SYNC_TARGET, "");
        if (s == null || s.trim().isEmpty()) s = p.getString(KEY_DSH_SESSION, "");
        return s == null ? "" : s.trim();
    }

    public static String canonicalSessionTitle(Context c) {
        android.content.SharedPreferences p = prefsOf(c);
        String s = p.getString(KEY_ATTACHED_TITLE, "");
        if (s == null || s.trim().isEmpty()) s = p.getString(KEY_SYNC_TITLE, "");
        if (s == null || s.trim().isEmpty()) s = p.getString(KEY_DSH_TITLE, "");
        return s == null ? "" : s.trim();
    }

    /** 写唯一身份；同时镜像到旧键（兼容旧代码/旧 APK） */
    public static void setCanonicalSession(Context c, String id, String title) {
        String v = id == null ? "" : id.trim();
        String t = title == null ? "" : title.trim();
        android.content.SharedPreferences.Editor ed = prefsOf(c).edit();
        if (v.isEmpty()) {
            ed.remove(KEY_ATTACHED).remove(KEY_ATTACHED_TITLE)
              .remove(KEY_SYNC_TARGET).remove(KEY_SYNC_TITLE);
        } else {
            ed.putString(KEY_ATTACHED, v).putString(KEY_SYNC_TARGET, v);
            if (t.isEmpty()) {
                ed.remove(KEY_ATTACHED_TITLE).remove(KEY_SYNC_TITLE);
            } else {
                ed.putString(KEY_ATTACHED_TITLE, t).putString(KEY_SYNC_TITLE, t);
            }
        }
        ed.apply();
    }

    /**
     * 和电脑对齐「跟随会话」（@code:follow）：一次请求，服务端说了算。
     *
     * <ul>
     *   <li>电脑有 follow 绑定 → 以电脑为准（id + 标题），本地旧 id 丢掉；</li>
     *   <li>电脑没有、本地有 → attach 到 follow 码（bind=true），让电脑也认它；</li>
     *   <li>都没有 → 空（还没选/首次发送时建立）。</li>
     * </ul>
     *
     * <p>注意：不再碰 {@code @direct}；直连有自己的 {@link #syncDirectIdentity}。</p>
     *
     * @return 对齐后的跟随会话 id（空 = 还没有）
     */
    public static String syncSessionIdentity(Context c) throws Exception {
        RemoteSdk sdk = new RemoteSdk(c);
        String local = canonicalSessionId(c);
        Map<String, Object> resp = sdk.sessionState("follow");
        String id = RemoteSdk.sessionIdOf(resp);
        String title = titleOf(resp);
        if (!id.isEmpty()) {
            setCanonicalSession(c, id, title);
            return id;
        }
        if (!local.isEmpty()) {
            try {
                sdk.attach(local, "follow", true);
                setCanonicalSession(c, local, canonicalSessionTitle(c));
                return local;
            } catch (Exception e) {
                setCanonicalSession(c, "", "");
                return "";
            }
        }
        return "";
    }

    /**
     * 和电脑对齐「直连会话」（{@code @direct}，手机专属）。
     *
     * <p>迁移：如果 {@code @direct} 与当前跟随会话是同一 id（历史上被"选择会话"覆盖过），
     * 就清掉这个直连绑定 —— 直连改回自己的会话，首次直连时由电脑新建，不再落进桌面会话。</p>
     */
    public static String syncDirectIdentity(Context c) throws Exception {
        RemoteSdk sdk = new RemoteSdk(c);
        String follow = canonicalSessionId(c);
        if (!prefsOf(c).getBoolean(KEY_DIRECT_SPLIT, false)) {
            // 旧版"选择会话"污染过 @direct（它可能等于当前跟随会话，也可能是别的桌面会话）：
            // 拆分后第一次运行统一清掉，直连改由首次直连时新建手机专属会话。
            try {
                sdk.directBind("");
            } catch (Exception ignored) {
                // 清不掉就下次再试；先按空展示
            }
            prefsOf(c).edit().putBoolean(KEY_DIRECT_SPLIT, true).apply();
            setDirectSession(c, "", "");
            return "";
        }
        Map<String, Object> resp = sdk.sessionState("");
        String id = RemoteSdk.sessionIdOf(resp);
        String title = titleOf(resp);
        if (!id.isEmpty() && id.equals(follow)) {
            try {
                sdk.directBind("");
            } catch (Exception ignored) {
                // 清不掉就先按空处理；下次再试
            }
            id = "";
            title = "";
        }
        setDirectSession(c, id, title);
        return id;
    }

    private static String titleOf(Map<String, Object> resp) {
        Object sess = resp == null ? null : resp.get("session");
        if (sess instanceof Map) {
            Object t = ((Map<?, ?>) sess).get("title");
            if (t != null) return String.valueOf(t);
        }
        return "";
    }

    // ===== 传输游标（服务级通道用；与渲染游标分开） =====
    public static int getTransportSeq(Context c, String sid) {
        if (sid == null || sid.isEmpty()) return -1;
        return prefsOf(c).getInt(KEY_TRANSPORT_SEQ + sid, -1);
    }

    public static void setTransportSeq(Context c, String sid, int seq) {
        if (sid == null || sid.isEmpty() || seq < 0) return;
        prefsOf(c).edit().putInt(KEY_TRANSPORT_SEQ + sid, seq).apply();
    }

    /**
     * @deprecated 模式已收敛。仅保留常量与 getMode 供旧调用点编译通过；
     * 现在永远返回 auto —— 执行位置由电脑端按"会话是否 live"决定。
     */
    @Deprecated
    public static final String MODE_AUTO = "auto";

    /** @deprecated 见 {@link #MODE_AUTO} */
    @Deprecated
    public static final String MODE_DIRECT = "direct";

    /** @deprecated 见 {@link #MODE_AUTO} */
    @Deprecated
    public static final String MODE_SYNC = "sync";

    /** @deprecated 模式已收敛，恒为 auto。 */
    @Deprecated
    public static String getMode(Context c) {
        return MODE_AUTO;
    }

    /** @deprecated 模式已收敛：不再落盘，调用无副作用。 */
    @Deprecated
    public static void setMode(Context c, String mode) {
        // no-op：执行位置由服务端决定
    }

    /** 同步目标：= 唯一会话身份（旧名保留给调用点） */
    public static String getSyncTarget(Context c) {
        return canonicalSessionId(c);
    }

    public static void setSyncTarget(Context c, String sessionId) {
        String v = sessionId == null ? "" : sessionId.trim();
        // 换会话时旧标题必须作废，否则界面会把上一条的标题当成本条的（看起来像"硬编码"）
        String old = canonicalSessionId(c);
        setCanonicalSession(c, v, v.equals(old) ? canonicalSessionTitle(c) : "");
    }

    /** 同步目标的标题 */
    public static String getSyncTitle(Context c) {
        return canonicalSessionTitle(c);
    }

    public static void setSyncTitle(Context c, String title) {
        setCanonicalSession(c, canonicalSessionId(c), title);
    }

    /**
     * 轻量列表：只要 id + 最后活动时间（电脑端只 stat 目录，不解码日志）。
     * 给界面做"有没有变化"的轮询用。
     */
    public static List<Map<String, Object>> fetchSessionsLight(Context c, int limit) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        Map<String, Object> resp = tool.httpJson(
                tool.getBaseUrl() + "/sessions.json?light=1&limit=" + Math.max(1, limit), "GET", null, 15);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        Object arr = resp.get("sessions");
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                if (o instanceof Map) {
                    //noinspection unchecked
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    /**
     * 轮询"电脑让我在本机执行的指令"（反向通道）。
     *
     * 只放行插件白名单里的工具（ui_component / ui_component_plugin / task / reminder），
     * 执行仍在本机，结果回执给电脑。
     */
    public static org.json.JSONArray pollCommands(Context c) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("action", "cmd_poll");
        String raw = tool.postSessionRaw(body, 20);
        return new org.json.JSONObject(raw).optJSONArray("commands");
    }

    /** 回执：某条指令在本机执行完了 */
    public static void ackCommand(Context c, String cmdId, boolean ok, String result) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("action", "cmd_ack");
        body.put("cmd_id", cmdId);
        body.put("ok", ok);
        body.put("result", result == null ? "" : result);
        tool.postSessionRaw(body, 20);
    }

    /** 直接 POST /session，返回原始响应（避免 Map 解析丢数组） */
    private String postSessionRaw(org.json.JSONObject body, int timeoutSec) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(getBaseUrl() + "/session").openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout((timeoutSec + 10) * 1000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String token = getToken();
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "{}";
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            in.close();
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 断点续传游标：某条电脑会话，手机已经处理到哪个 seq（-1 = 没记录） */
    public static int getLastSeq(Context c, String target) {
        if (target == null || target.isEmpty()) return -1;
        try {
            return prefsOf(c).getInt("sync_seq_" + target, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void setLastSeq(Context c, String target, int seq) {
        if (target == null || target.isEmpty() || seq < 0) return;
        try {
            prefsOf(c).edit().putInt("sync_seq_" + target, seq).apply();
        } catch (Throwable ignored) {
            // 存不上就下次从头补，不影响主流程
        }
    }

    /** 兼容旧签名：默认 follow（同步进行中的实时订阅） */
    public static void subscribeEvents(Context c, String sid, int since, int timeoutSec, DshEventListener l) {
        subscribeEvents(c, sid, since, timeoutSec, true, 5000, l);
    }

    /** 兼容旧签名：默认缺口阈值 5000 条（超过则由服务端退化成 snapshot） */
    public static void subscribeEvents(Context c, String sid, int since, int timeoutSec,
                                       boolean follow, DshEventListener l) {
        subscribeEvents(c, sid, since, timeoutSec, follow, 5000, l);
    }

    /** DSH 原始事件回调（后台线程触发） */
    public interface DshEventListener {
        void onReady(int seq);

        /** 服务端给的是"历史批"（首次连接/缺口过大）。默认忽略，保持向后兼容 */
        default void onSnapshot(int seq, org.json.JSONArray turns) { }
        void onEvent(org.json.JSONObject event);
        void onEnd();
        void onError(String message);
    }

    /**
     * 只把用户消息注入电脑端会话，立刻返回"注入前的 seq"。
     * 手机随后用 {@link #subscribeEvents} 订阅原始事件流，自己做渲染转换。
     */
    public static int inject(Context c, String target, String text) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        Map<String, Object> body = new HashMap<>();
        body.put("action", "inject");
        body.put("target_session_id", target);
        body.put("text", text);
        Map<String, Object> resp = tool.httpJson(tool.getBaseUrl() + "/session", "POST", body, 40);
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            throw new Exception(String.valueOf(resp.get("error")));
        }
        Object sb = resp.get("seq_before");
        return sb instanceof Number ? ((Number) sb).intValue() : 0;
    }

    /**
     * 订阅插件转发的 **DSH 原始事件流**（SSE，逐条 {type,seq,time,data}）。
     *
     * 插件不做任何 UI 适配；由手机端把事件映射成 ChatMessage / parts。
     */
    public static void subscribeEvents(Context c, String sid, int since, int timeoutSec,
                                       boolean follow, int snapshotThreshold, DshEventListener listener) {
        subscribeEvents(c, sid, since, timeoutSec, follow, snapshotThreshold, null, listener);
    }

    /**
     * @param types 只转发这些事件类型（逗号分隔；null/空 = 全量）。
     *              导入历史时用，能把 9000+ 条事件压到一千多条。
     */
    /** 事件流的"代"：递增即让所有在跑的重连循环退出（页面销毁时调 cancelEventStreams） */
    private static final java.util.concurrent.atomic.AtomicLong STREAM_GEN =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 取消当前所有事件流订阅（下一次 subscribeEvents 会拿到新代，不受影响） */
    public static void cancelEventStreams() {
        STREAM_GEN.incrementAndGet();
    }

    public static void subscribeEvents(Context c, String sid, int since, int timeoutSec,
                                       boolean follow, int snapshotThreshold, String types,
                                       DshEventListener listener) {
        RemoteDshTool tool = new RemoteDshTool(c);
        if (!follow) {
            // 一次性导入历史：保持原行为，读完就结束
            try {
                streamOnce(tool, sid, since, timeoutSec, false, snapshotThreshold, types, listener);
            } catch (Exception e) {
                listener.onError(String.valueOf(e.getMessage()));
            }
            listener.onEnd();
            return;
        }
        /**
         * ★ 2026-11-12：follow 模式改为**自动重连**。
         *
         * 之前一条 SSE 断了就直接 onError/onEnd，手机就"再也不会更新"——
         * 表现就是「连接流不稳定、消息不同步、不补发」。
         * 现在：断线 → 指数退避（1s 起，上限 30s，±50% 抖动）→ 带 since 游标续传，
         * 连续 8 次失败才真正报错；正常收到 end（服务端 600s 超时）也自动接上。
         */
        int cursor = since;
        int failures = 0;
        final long gen = STREAM_GEN.get();
        final long deadline = System.currentTimeMillis() + 30L * 60 * 1000;
        while (System.currentTimeMillis() < deadline && STREAM_GEN.get() == gen) {
            try {
                int next = streamOnce(tool, sid, cursor, timeoutSec, true, snapshotThreshold, types, listener);
                if (next > cursor) cursor = next;
                failures = 0;          // 连上并正常结束 → 重置退避
            } catch (Exception e) {
                failures += 1;
                if (failures >= 8) {
                    listener.onError(String.valueOf(e.getMessage()));
                    break;
                }
            }
            long backoff = Math.min(30000L, 1000L << Math.min(failures, 5));
            backoff = backoff / 2 + (long) (Math.random() * backoff / 2);
            // 分段睡眠：cancelEventStreams() 后最多 200ms 就能退出，不用等满退避
            long slept = 0;
            while (slept < backoff && STREAM_GEN.get() == gen) {
                try {
                    Thread.sleep(Math.min(200, backoff - slept));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    listener.onEnd();
                    return;
                }
                slept += 200;
            }
        }
        listener.onEnd();
    }

    /** 连一次 SSE，返回本次收到的最大 seq（断线后作为 since 续传） */
    private static int streamOnce(RemoteDshTool tool, String sid, int since, int timeoutSec, boolean follow,
                                  int snapshotThreshold, String types, DshEventListener listener) throws Exception {
        HttpURLConnection conn = null;
        int cursor = since;
        try {
            String url = tool.getBaseUrl() + "/events?session_id=" + java.net.URLEncoder.encode(sid, "UTF-8")
                    + "&since=" + since + "&follow=" + (follow ? "1" : "0") + "&timeout=" + Math.max(5, timeoutSec)
                    + "&snapshot_threshold=" + Math.max(50, snapshotThreshold)
                    + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8")
                    + (types == null || types.isEmpty() ? "" : "&types=" + java.net.URLEncoder.encode(types, "UTF-8"));
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout((timeoutSec + 30) * 1000);
            conn.setRequestProperty("Accept", "text/event-stream");
            String token = tool.getToken();
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder data = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            cursor = handleStreamMessage(data.toString(), cursor, follow, listener);
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) data.append(line.substring(5).trim());
                }
                if (data.length() > 0) cursor = handleStreamMessage(data.toString(), cursor, follow, listener);
            }
            return cursor;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 解析一条 SSE 消息并更新游标；返回新的 seq 游标 */
    static int handleStreamMessage(String raw, int cursor, boolean follow, DshEventListener listener) {
        try {
            org.json.JSONObject msg = new org.json.JSONObject(raw);
            String type = msg.optString("type", "");
            if ("ready".equals(type)) {
                listener.onReady(msg.optInt("seq", 0));
                return cursor;
            }
            if ("event".equals(type)) {
                org.json.JSONObject ev = msg.optJSONObject("event");
                if (ev != null) {
                    int s = ev.optInt("seq", cursor);
                    if (s > cursor) cursor = s;
                    listener.onEvent(ev);
                }
                return cursor;
            }
            if ("snapshot".equals(type)) {
                int s = msg.optInt("seq", cursor);
                if (s > cursor) cursor = s;
                listener.onSnapshot(s, msg.optJSONArray("turns"));
                return cursor;
            }
            if ("pulse".equals(type)) {
                // 心跳：只用于判活/测 RTT，不推进游标
                return cursor;
            }
            if ("end".equals(type) || "gone".equals(type)) {
                if (!follow) listener.onEnd();
                return cursor;
            }
            // 插件直接下发的合成事件（不是 event/snapshot 这种控制帧）：
            // approval / question / stream/delta / plan/mode / subagent/activity / schedule/changed…
            // 以前这里直接 return，把它们全吞了（所以"审批只在弹窗里出现过、时间线里没有"）。
            if (!type.isEmpty()) {
                listener.onEvent(msg);
            }
            return cursor;
        } catch (Exception e) {
            return cursor;   // 坏事件跳过
        }
    }

    private static void dispatchDshEvent(String raw, DshEventListener listener) {
        try {
            org.json.JSONObject msg = new org.json.JSONObject(raw);
            String type = msg.optString("type", "");
            if ("ready".equals(type)) {
                listener.onReady(msg.optInt("seq", 0));
            } else if ("event".equals(type)) {
                org.json.JSONObject ev = msg.optJSONObject("event");
                if (ev != null) listener.onEvent(ev);
            } else if ("snapshot".equals(type)) {
                listener.onSnapshot(msg.optInt("seq", -1), msg.optJSONArray("turns"));
            } else if ("end".equals(type) || "gone".equals(type)) {
                listener.onEnd();
            }
        } catch (Exception ignored) {
            // 坏事件跳过
        }
    }

    /**
     * 手机上应答一条审批（先答先赢：电脑端那个弹窗会因 settle 自动收掉）。
     * @param allow true=允许，false=拒绝
     */
    public static boolean answerApproval(Context c, String approvalId, boolean allow) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        Map<String, Object> body = new HashMap<>();
        body.put("action", "approval");
        body.put("approval_id", approvalId);
        body.put("decision", allow ? "allow" : "deny");
        Map<String, Object> resp = tool.httpJson(tool.getBaseUrl() + "/session", "POST", body, 30);
        return Boolean.TRUE.equals(resp.get("ok"));
    }

    /** 同步流式回调（在后台线程触发，调用方自行 post 到 UI） */
    public interface SyncStreamListener {
        void onStart();
        void onThinking(String text);
        void onText(String text);
        void onDone(String reply, String thinking);
        void onError(String message);
    }

    /**
     * 把一句话发进电脑端正在运行的会话，并**流式**接收：思考、正文边跑边回。
     *
     * 之前只有一次性接口（等整轮跑完才响应），手机上就是"发出去要等好一会才见到回复"。
     * 这里走 SSE：插件侧每 150ms 推一次"当前全文"，手机拿到就刷新气泡。
     */
    public static void syncStream(Context c, String target, String text, int timeoutSec, SyncStreamListener listener) {
        RemoteDshTool tool = new RemoteDshTool(c);
        HttpURLConnection conn = null;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("action", "sync");
            body.put("target_session_id", target);
            body.put("text", text);
            body.put("stream", true);
            body.put("timeout", timeoutSec);
            conn = (HttpURLConnection) new URL(tool.getBaseUrl() + "/session").openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout((timeoutSec + 30) * 1000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            String token = tool.getToken();
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                listener.onError("HTTP " + code);
                return;
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder data = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatchStreamEvent(data.toString(), listener);
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) data.append(line.substring(5).trim());
                }
                if (data.length() > 0) dispatchStreamEvent(data.toString(), listener);
            }
        } catch (Exception e) {
            listener.onError(String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void dispatchStreamEvent(String raw, SyncStreamListener listener) {
        try {
            org.json.JSONObject ev = new org.json.JSONObject(raw);
            String type = ev.optString("type", "");
            switch (type) {
                case "start":
                    listener.onStart();
                    break;
                case "thinking":
                    listener.onThinking(ev.optString("text", ""));
                    break;
                case "text":
                    listener.onText(ev.optString("text", ""));
                    break;
                case "done":
                    listener.onDone(ev.optString("reply", ""), ev.optString("thinking", ""));
                    break;
                case "error":
                    listener.onError(ev.optString("error", "未知错误"));
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
            // 单个坏事件不影响整条流
        }
    }

    /**
     * 读电脑端某条会话最近一条 assistant 文本（同步模式"补回复"用）。
     *
     * 场景：手机在等回复时被强杀/断网，电脑那轮照样跑完了；回到聊天页时用它
     * 把"你错过的最后一条回复"补上，避免两端内容不一致。
     */
    public static String fetchLastAssistant(Context c, String sid) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        Map<String, Object> body = new HashMap<>();
        body.put("action", "read");
        body.put("session_id", sid);
        body.put("max", 4);
        Map<String, Object> resp = tool.httpJson(tool.getBaseUrl() + "/session", "POST", body, 40);
        if (!Boolean.TRUE.equals(resp.get("ok"))) return "";
        Object hist = resp.get("history");
        if (hist instanceof List) {
            List<?> list = (List<?>) hist;
            for (int i = list.size() - 1; i >= 0; i -= 1) {
                Object o = list.get(i);
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                if (!"assistant".equals(String.valueOf(m.get("role")))) continue;
                String text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
                if (!text.trim().isEmpty()) return text;
            }
        }
        return "";
    }

    /** 最近一次同步回复里的思考内容：交给聊天界面按 reasoning part 渲染 */
    public static String getLastSyncThinking(Context c) {
        return prefsOf(c).getString("sync_last_thinking", "");
    }

    public static void setLastSyncThinking(Context c, String text) {
        prefsOf(c).edit().putString("sync_last_thinking", text == null ? "" : text).apply();
    }

    /** 最近一次通过同步拿到的回复（用来判重，避免补重复） */
    public static String getLastSyncReply(Context c) {
        return prefsOf(c).getString("sync_last_reply", "");
    }

    public static void setLastSyncReply(Context c, String text) {
        prefsOf(c).edit().putString("sync_last_reply", text == null ? "" : text).apply();
    }

    /**
     * 给界面用的会话列表（「远程连接（电脑）」里的同步目标选择器）。
     * 返回 [{session_id, cwd, last}]，失败抛异常由调用方提示。
     */
    public static List<Map<String, Object>> fetchSessions(Context c, int limit) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        Map<String, Object> resp = tool.httpJson(tool.getBaseUrl() + "/sessions.json?limit=" + Math.max(1, limit),
                "GET", null, 20);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        Object arr = resp.get("sessions");
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                if (o instanceof Map) {
                    //noinspection unchecked
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    /**
     * 给界面用：电脑端自检原始 JSON（插件 v8 的 /health）。
     * 「远程连接（电脑）」页点「自检」时调用，失败抛异常由界面提示。
     */
    public static Map<String, Object> fetchHealth(Context c) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        return t.httpJson(t.getBaseUrl() + "/health", "GET", null, 15);
    }

    /**
     * 给界面用：双端对账（light 模式）原始 JSON（插件 v8 的 /reconcile）。
     * sid 留空则用当前会话 / 直连会话。
     */
    public static Map<String, Object> fetchReconcile(Context c, String sid) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        String s = sid == null ? "" : sid.trim();
        if (s.isEmpty()) s = t.resolveTargetSession(null);
        if (s.isEmpty()) throw new Exception("没有可对账的会话：先发一条消息，或在下方选一个同步目标");
        // 带上本机游标：服务端据此回 match/behind + gap（对账口径只剩 seq，不再各算一份摘要）
        int since = getLastSeq(c, s);
        return t.httpJson(t.getBaseUrl() + "/reconcile?light=1&session_id="
                + java.net.URLEncoder.encode(s, "UTF-8")
                + "&last_seq=" + Math.max(0, since), "GET", null, 15);
    }

    /**
     * 给界面用：协议 v2 的统一视图（服务端派生 cards）。
     * 电脑面板与手机页面渲染同一份数据，不再各自拼文案。
     */
    public static Map<String, Object> fetchView(Context c, String sid) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        String s = sid == null ? "" : sid.trim();
        // 带客户端 caps：电脑端据此裁剪卡片（legacy 客户端不传 → 不裁）
        String q = "?caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8")
                + (s.isEmpty() ? "" : "&session_id=" + java.net.URLEncoder.encode(s, "UTF-8"));
        return t.httpJson(t.getBaseUrl() + "/v2/view" + q, "GET", null, 15);
    }

    /**
     * 回答一次 ask（2026-11-12）：答案批次整批回传。
     *
     * @param batchId 网关 pending 事件 id（阻塞中的问题用它，与电脑端先答先赢）
     * @param callId  ask 的工具调用 id（超时后 continued 的问题用它）
     * @return 电脑端是否受理
     */
    public static boolean answerQuestion(Context c, String sid, String batchId, String callId,
                                         String answerJson) throws Exception {
        org.json.JSONArray answers = null;
        try {
            answers = new org.json.JSONObject(answerJson).optJSONArray("answers");
        } catch (Exception ignored) {
            // 下面按空处理
        }
        if (answers == null || answers.length() == 0) return false;
        Map<String, Object> b = new HashMap<>();
        b.put("session_id", sid == null ? "" : sid);
        if (batchId != null && !batchId.isEmpty()) b.put("id", batchId);
        if (callId != null && !callId.isEmpty()) b.put("call_id", callId);
        b.put("answers", answers);
        b.put("caps", RemoteCaps.csv());
        RemoteDshTool t = new RemoteDshTool(c);
        Map<String, Object> r = t.httpJson(t.getBaseUrl() + "/v2/answer", "POST", b, 25);
        return Boolean.TRUE.equals(r.get("ok"));
    }

    /** 日程目录（宿主级任务，与电脑端面板同一份数据） */
    public static Map<String, Object> fetchSchedule(Context c) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        String q = "?action=catalog&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8");
        return t.httpJson(t.getBaseUrl() + "/v2/schedule" + q, "GET", null, 20);
    }

    /** 日程写操作：run / edit（patch 或删除） */
    public static Map<String, Object> scheduleAction(Context c, String action, String id,
                                                     org.json.JSONObject extra) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        Map<String, Object> b = new HashMap<>();
        b.put("action", action);
        b.put("id", id == null ? "" : id);
        b.put("caps", RemoteCaps.csv());
        if (extra != null) {
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                b.put(k, extra.opt(k));
            }
        }
        return t.httpJson(t.getBaseUrl() + "/v2/schedule", "POST", b, 25);
    }

    /** 计划模式状态（需要会话在电脑端 live） */
    public static Map<String, Object> fetchPlan(Context c, String sid) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        String q = "?session_id=" + java.net.URLEncoder.encode(sid == null ? "" : sid, "UTF-8")
                + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8");
        return t.httpJson(t.getBaseUrl() + "/v2/plan" + q, "GET", null, 15);
    }

    public static Map<String, Object> setPlan(Context c, String sid, boolean active) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        Map<String, Object> b = new HashMap<>();
        b.put("session_id", sid == null ? "" : sid);
        b.put("active", active);
        b.put("caps", RemoteCaps.csv());
        return t.httpJson(t.getBaseUrl() + "/v2/plan", "POST", b, 15);
    }

    /** 子代理目录 + 最近活动 */
    public static Map<String, Object> fetchSubagents(Context c, String sid) throws Exception {
        RemoteDshTool t = new RemoteDshTool(c);
        String q = "?session_id=" + java.net.URLEncoder.encode(sid == null ? "" : sid, "UTF-8")
                + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8");
        return t.httpJson(t.getBaseUrl() + "/v2/subagents" + q, "GET", null, 20);
    }

    // ---------- 连接状态（供「远程连接（电脑）」界面与本工具共用） ----------

    private static SharedPreferences prefsOf(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 是否配对过（地址与令牌都在） */
    public static boolean isConfigured(Context c) {
        SharedPreferences p = prefsOf(c);
        String u = p.getString(KEY_URL, "");
        String t = p.getString(KEY_TOKEN, "");
        return u != null && !u.trim().isEmpty() && t != null && !t.trim().isEmpty();
    }

    /**
     * 是否处于"已连接"状态。
     * 语义：配对/手动连接后为 true；用户点「断开」后为 false（此时工具一律拒绝执行并提示去界面重新连接）。
     * 老配置（没有该标记）视为已连接——升级后不会突然"用不了"。
     */
    public static boolean isConnected(Context c) {
        SharedPreferences p = prefsOf(c);
        return isConfigured(c) && p.getBoolean(KEY_CONNECTED, true);
    }

    public static void setConnected(Context c, boolean connected) {
        prefsOf(c).edit().putBoolean(KEY_CONNECTED, connected).apply();
    }

    /** 断开：标记为断开并清掉本地会话记录；地址/令牌保留（下次点连接即可用） */
    public static void disconnect(Context c) {
        prefsOf(c).edit().putBoolean(KEY_CONNECTED, false).remove(KEY_SESSION).apply();
    }

    /** 清除配置：地址、令牌、会话、连接标记全部清空 */
    public static void clearConfig(Context c) {
        prefsOf(c).edit().clear().apply();
    }

    // ---------- 聊天页顶部状态条开关（用户要求：不要一直显示，要能关） ----------
    // 存在 ai_prefs（界面偏好）而不是 remote_dsh_config：清除配对配置时不该把这个偏好也清掉。

    private static final String PREF_UI = "ai_prefs";
    private static final String KEY_SHOW_BAR = "remote_dsh_bar";

    /**
     * 聊天页顶部「电脑连接」状态条是否显示。
     * **默认关闭**（2026-09-27 按用户要求）：大多数用户不用远程控制电脑，不该在聊天页常驻占一行；
     * 需要的人在「远程连接（电脑）」里打开开关，或配对后自行开启。
     */
    public static boolean isBarEnabled(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_BAR, false);
    }

    public static void setBarEnabled(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOW_BAR, enabled).apply();
    }

    /** 界面用：读原始配置值（token 不在这里打码，由界面决定怎么显示） */
    public static String configValue(Context c, String key) {
        return prefsOf(c).getString(key, "");
    }

    /** 界面用：写入地址+令牌并置为已连接（手动连接）；返回错误信息或 null */
    public static String saveManualConfig(Context c, String baseUrl, String token) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        String t = token == null ? "" : token.trim();
        if (u.isEmpty() || t.isEmpty()) {
            return "地址和令牌都要填（地址形如 http://192.168.1.5:8218 或 https://xxx.vicp.fun）";
        }
        String lower = u.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "地址必须以 http:// 或 https:// 开头";
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        // 只在"换了电脑地址或令牌"时才清会话记忆；重复扫同一张二维码/重复保存配置不再冲掉多轮记忆
        // （此前无条件 remove(KEY_SESSION)：每次扫码或保存配置都会把电脑端会话记忆重置 ✗）
        String prevUrl = prefsOf(c).getString(KEY_URL, "");
        String prevToken = prefsOf(c).getString(KEY_TOKEN, "");
        boolean targetChanged = !u.equals(prevUrl) || !t.equals(prevToken);
        android.content.SharedPreferences.Editor ed = prefsOf(c).edit()
                .putString(KEY_URL, u).putString(KEY_TOKEN, t).putBoolean(KEY_CONNECTED, true);
        // 手动填的是"主令牌"这类原始凭据：清掉自动换来的设备令牌，否则会被优先使用、手工输入不生效。
        // 但不能只删不补——删掉后如果登记又失败，这台手机就既没设备令牌、面板也看不到它，
        // 所以下面立刻在后台重新登记一次设备（失败则继续用主令牌，功能不受影响）。
        if (!t.equals(prefsOf(c).getString(KEY_DEVICE_TOKEN, ""))) {
            ed.remove(KEY_DEVICE_TOKEN).remove(KEY_DEVICE_ID);
        }
        if (targetChanged) {
            ed.remove(KEY_SESSION);
            // 换电脑了：直连会话 id 属于旧电脑，作废（下次由新电脑重新协商）
            ed.remove(KEY_DSH_SESSION).remove(KEY_DSH_TITLE);
        }
        ed.apply();
        // 每次手动保存都登记一次：不要加 targetChanged 条件（2026-10-06 实测踩坑）——
        // 用户把**同样的地址和令牌**再存一遍时 targetChanged=false，登记就被跳过，
        // 于是电脑端一直显示"已配对设备 0 台"，而手机明明能正常对话。
        // 重复登记无副作用：电脑端 /pair/claim 对同名设备会复用旧 id 并替换记录。
        if (!t.isEmpty()) {
            registerDeviceInBackground(c, u, t);
        }
        return null;
    }

    /**
     * 后台把给定凭据登记为设备（换取可单独吊销的设备令牌）。
     * 手动保存配置走这条；扫码配对走 handlePair 里的同步流程（那边本来就在子线程）。
     * 失败只记日志：主令牌仍可用，只是电脑端面板看不到这台设备。
     */
    private static void registerDeviceInBackground(final Context c, final String baseUrl, final String token) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                String err = RemoteDshPairBridge.claimDeviceToken(app, baseUrl, token);
                if (err != null) {
                    Log.w(TAG, "手动保存后登记设备失败: " + err);
                } else {
                    Log.i(TAG, "手动保存后登记设备成功: " + prefsOf(app).getString(KEY_DEVICE_ID, ""));
                }
            } catch (Throwable t) {
                Log.w(TAG, "手动保存后登记设备异常: " + t);
            }
        }, "remote-dsh-claim").start();
    }

    // ---------- 电脑端程序导出（新用户拿不到脚本 = 功能没法用） ----------

    /** 电脑端程序在 assets 里的目录 */
    private static final String ASSET_DIR = "remote_dsh";
    /** 导出给用户的电脑端文件（放到同一个文件夹即可启动） */
    /** DSH 插件文件（新方案：作为 DSH 宿主插件运行，不再需要 Python 桥；导出到 remote_dsh/plugin/） */
    private static final String[] PLUGIN_FILES = {
            "package.json", "index.js", "client.js", "cordis.patch.yml", "icon.svg",
            "pair_page.html", "qrcodegen.js", "安装说明.txt",
    };
    private static final String[] BRIDGE_FILES = {
            "start_dsh_bridge.bat", "start_dsh_bridge.sh",
            "dsh_bridge_server.py", "pair_page.html", "qrcodegen.js", "README.md"
    };

    /**
     * 把电脑端要用的程序导出到公共下载目录 Download/OilQuiz/remote_dsh/。
     * 用户把整个文件夹拷到电脑上，双击 start_dsh_bridge.bat 即可（README 里有完整步骤）。
     *
     * @return 导出目录的绝对路径
     */
    public static String exportBridgeFiles(Context c) throws Exception {
        // MediaStore 写入（无需"所有文件访问"权限）；文件管理器可见，可拷到电脑
        String dirDesc = null;
        for (String name : BRIDGE_FILES) {
            try (java.io.InputStream in = c.getAssets().open(ASSET_DIR + "/" + name)) {
                String rel = com.oilquiz.app.util.PublicStorageWriter.writeStream(
                        c, "remote_dsh", name,
                        com.oilquiz.app.util.PublicStorageWriter.guessMime(name), in);
                if (rel == null) {
                    throw new java.io.IOException("无法导出 " + name + "（公共目录不可写）");
                }
                int idx = rel.lastIndexOf('/');
                dirDesc = idx > 0 ? rel.substring(0, idx) : rel;
            }
        }
        // 新方案：同时导出 DSH 插件（smartquiz-remote）到 remote_dsh/plugin/ 子目录
        for (String name : PLUGIN_FILES) {
            try (java.io.InputStream in = c.getAssets().open(ASSET_DIR + "/plugin/" + name)) {
                String rel = com.oilquiz.app.util.PublicStorageWriter.writeStream(
                        c, "remote_dsh/plugin", name,
                        com.oilquiz.app.util.PublicStorageWriter.guessMime(name), in);
                if (rel == null) {
                    throw new java.io.IOException("无法导出 plugin/" + name + "（公共目录不可写）");
                }
                int idx = rel.lastIndexOf('/');
                dirDesc = idx > 0 ? rel.substring(0, idx) : rel;
            }
        }
        return dirDesc == null ? "内部存储/Download/OilQuiz/remote_dsh" : dirDesc;
    }

    /** 第一次用这个功能时的引导文案（工具给 AI 的提示里也用它指路） */
    public static String firstTimeHint() {
        return "第一次用远程控制电脑：打开「工具集 → 设置与系统环境 → 远程连接（电脑）」→ 点「怎么用 / 电脑端怎么配」看教程（"
                + "那里可以把电脑端程序一键导出到手机 Download/OilQuiz/remote_dsh/，再拷到电脑上双击启动）";
    }

    /**
     * 校验 base_url 是否是"真实可用"的地址；返回错误描述，OK 返回 null。
     * 拦掉 0.0.0.0 / 空 host / 端口 0 / 端口越界 这些"看着像地址、其实连不上"的写法。
     */
    private static String validateBaseUrl(String baseUrl) {
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            String host = u.getHost();
            if (host == null || host.trim().isEmpty()) {
                return "缺少主机名";
            }
            String h = host.trim();
            if (h.equals("0.0.0.0") || h.equals("::") || h.equals("[::]")) {
                return "0.0.0.0 不是可连接的主机（它是「监听所有网卡」的意思）";
            }
            int port = u.getPort();
            if (port == 0) {
                return "端口不能是 0";
            }
            if (port > 65535) {
                return "端口越界: " + port;
            }
            return null;
        } catch (Exception e) {
            return "地址格式不对（" + e.getMessage() + "）";
        }
    }

    /** 统一前置检查：未配置 / 已断开 → 返回失败结果；正常返回 null */
    private AIToolResult precheck() {
        if (getBaseUrl().isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置（还没配对过电脑）。"
                    + "\n" + firstTimeHint()
                    + "\n也可以对 AI 说「远程配对」直接扫码：电脑端跑起桥接后会显示配对页 http://127.0.0.1:8218/pair");
        }
        if (!isConnected(context)) {
            return AIToolResult.fail("已断开电脑连接（配置还在，只是本机停用，电脑端未受影响）。"
                    + "\n重新连接：工具集 → 设置与系统环境 → 远程连接（电脑）→ 点「连接」；或对我说「连接电脑」");
        }
        return null;
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
                case "register_device":
                    return handleRegisterDevice();
                case "set_config":
                    return handleSetConfig(parameters);
                case "clear_config":
                case "reset":
                    return handleClearConfig();
                case "get_status":
                    return handleStatus();
                case "start":
                    return handleStart();
                case "history":
                    return handleHistory(parameters);
                case "list_sessions":
                    return handleListSessions(parameters);
                case "read_session":
                    return handleReadSession(parameters);
                case "attach_session":
                    return handleAttachSession(parameters);
                case "sync_send":
                    return handleSyncSend(parameters);
                case "sync_state":
                    return handleSyncState(parameters);
                case "health":
                    return handleHealth();
                case "view":
                    return handleView(parameters);
                case "session":
                    return handleSession(parameters);
                case "events":
                    return handleEvents(parameters);
                case "reconcile":
                    return handleReconcile(parameters);
                case "ack":
                    return handleAck(parameters);
                case "shell":
                    return handleShell(parameters);
                case "connect":
                    return handleConnect();
                case "disconnect":
                    return handleDisconnect();
                case "run":
                default:
                    return handleRun(parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "execute error: " + e.getMessage(), e);
            return AIToolResult.fail("remote_dsh 执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * action=clear_config：清空配对配置（地址/令牌/会话/连接状态）。
     *
     * 为什么要单独给个动作（2026-09-27 从手机对话里发现的坑）：AI 想"清除/断开"时没有正规入口，
     * 只能瞎试 set_config base_url="" （被当成"查询配置"）、base_url="clear"（被校验拒），
     * 最后竟然写了个假地址 http://0.0.0.0:0 来"断开"——把手机配置污染了。
     */
    private AIToolResult handleClearConfig() {
        String before = getBaseUrl();
        clearConfig(context);
        return AIToolResult.success("已清除电脑连接配置 ✓"
                + (before.isEmpty() ? "（原本就没有配置）" : "\n  原地址: " + before)
                + "\n地址、令牌、会话、连接状态都已清空；要再用请重新扫码配对（工具集 → 设置与系统环境 → 远程连接（电脑）→ 扫码配对）。"
                + "\n提示：只想临时停用（保留配置）请用 action=disconnect。");
    }

    private AIToolResult handleSetConfig(Map<String, Object> parameters) {
        String baseUrl = parameters.get("base_url") != null ? String.valueOf(parameters.get("base_url")).trim() : "";
        String token = parameters.get("token") != null ? String.valueOf(parameters.get("token")).trim() : "";
        String sessionId = parameters.get("session_id") != null ? String.valueOf(parameters.get("session_id")).trim() : "";
        // 明确表达"清除"的写法也接受（AI 会自然地说 base_url=clear/none/-）
        String lowerUrl = baseUrl.toLowerCase();
        if (lowerUrl.equals("clear") || lowerUrl.equals("none") || lowerUrl.equals("null") || lowerUrl.equals("-")
                || lowerUrl.equals("empty")) {
            return handleClearConfig();
        }
        if (baseUrl.isEmpty() && token.isEmpty() && sessionId.isEmpty()) {
            // 未传任何值：返回当前配置（token 打码）
            String cur = getBaseUrl();
            String tok = getToken();
            String sid = getSessionId();
            return AIToolResult.success(
                    "当前 remote_dsh 配置：\nbase_url=" + (cur.isEmpty() ? "(未配置)" : cur)
                            + "\ntoken=" + (tok.isEmpty() ? "(未配置)" : tok.substring(0, Math.min(4, tok.length())) + "***")
                            + "\n凭据类型=" + (isUsingDeviceToken(context) ? "设备令牌（可在电脑端单独吊销）" : "主令牌（未登记为设备）")
                            + "\nsession_id=" + (sid.isEmpty() ? "(未创建)" : sid)
                            + "\n连接状态=" + (cur.isEmpty() ? "(未配置)" : (isConnected(context) ? "已连接" : "已断开"))
                            + "\n\n可视化操作：工具集 → 设置与系统环境 → 远程连接（电脑）（连接/断开/清除配置/扫码）"
                            + "\n或 action=set_config 传 base_url=http://电脑IP:8218 和 token=桥接服务启动时打印的令牌");
        }
        // 校验 base_url（2026-09-27：之前不校验，手机端 AI 用 http://0.0.0.0:0 当"断开"也被收下了）
        if (!baseUrl.isEmpty()) {
            String lower = baseUrl.toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return AIToolResult.fail("base_url 必须以 http:// 或 https:// 开头，如 http://192.168.1.100:8218"
                        + "\n（要清除配置用 action=clear_config；要临时停用用 action=disconnect）");
            }
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String check = validateBaseUrl(baseUrl);
            if (check != null) {
                return AIToolResult.fail("base_url 不可用：" + check
                        + "\n给个真实可达的地址，例如 http://192.168.1.100:8218 或 https://xxx.vicp.fun"
                        + "\n（要清除配置用 action=clear_config；要临时停用用 action=disconnect）");
            }
        }
        SharedPreferences.Editor ed = getPrefs().edit();
        if (!baseUrl.isEmpty()) ed.putString(KEY_URL, baseUrl);
        if (!token.isEmpty()) ed.putString(KEY_TOKEN, token);
        if (!sessionId.isEmpty()) ed.putString(KEY_SESSION, sessionId);
        // 写入配置即视为要使用：自动置为已连接（否则用户手动 set_config 后还得再去点一次连接）
        ed.putBoolean(KEY_CONNECTED, true);
        ed.apply();
        return AIToolResult.success("remote_dsh 配置已保存（已置为已连接）：\nbase_url="
                + (baseUrl.isEmpty() ? getBaseUrl() : baseUrl)
                + "\ntoken=" + (token.isEmpty() ? "(保持原值)" : token.substring(0, Math.min(4, token.length())) + "***")
                + "\nsession_id=" + (sessionId.isEmpty() ? "(保持原值)" : sessionId));
    }

    /**
     * action=register_device：把当前配置里的**主令牌**拿去换设备令牌（补登记）。
     *
     * 用途：老配置（扫码时电脑端插件还不支持凭主令牌 claim，或当时网络失败）只有主令牌，
     * 电脑端面板看不到这台设备。电脑端插件更新后跑一次本动作即可补上，手机无需重新扫码。
     */
    private AIToolResult handleRegisterDevice() {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("还没配对过电脑：先用 action=pair 扫码");
        }
        String mainToken = getMainToken();
        if (mainToken.isEmpty()) {
            return AIToolResult.fail("配置里没有主令牌：请重新扫码配对（二维码里带主令牌）");
        }
        // 先清掉可能已失效的旧设备令牌，避免 claim 失败后仍继续用一把废令牌
        getPrefs().edit().remove(KEY_DEVICE_TOKEN).remove(KEY_DEVICE_ID).apply();
        String err = RemoteDshPairBridge.claimDeviceToken(context, baseUrl, mainToken);
        if (err != null) {
            return AIToolResult.fail("设备登记失败：" + err
                    + "\n（功能仍可用：已退回使用主令牌；请确认电脑端插件已更新并重载）");
        }
        String did = getPrefs().getString(KEY_DEVICE_ID, "");
        return AIToolResult.success("设备登记成功 ✓ 现在电脑端「已配对设备」会显示这台手机"
                + (did.isEmpty() ? "" : "（" + did + "）")
                + "\n之后只带设备令牌访问，电脑端可单独吊销这一台而不影响其他设备。"
                + "\n用 action=get_status 可在电脑端 /devices 里看到它。");
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
        setConnected(context, true);   // 扫码配对 = 连接

        // ---- 换取设备令牌（新）：让电脑端面板能真实显示这台设备、并能单独吊销 ----
        // 本方法跑在工具执行线程（非 UI 线程），可以直接做这一次 HTTP。
        // 失败不影响可用性：退回主令牌继续工作，只是电脑端看不到设备记录。
        String mainToken = getMainToken();
        String claimErr = RemoteDshPairBridge.claimDeviceToken(context, baseUrl, mainToken);
        String tokNow = getToken();
        String tokenKind = isUsingDeviceToken(context)
                ? "设备令牌（可单独吊销）"
                : "主令牌（未登记为设备）";
        String tail = "\n\n设备凭据: " + tokenKind
                + "\n令牌=" + tokNow.substring(0, Math.min(4, tokNow.length())) + "***";
        if (claimErr != null) {
            tail += "\n⚠ 设备登记未完成：" + claimErr
                    + "\n  已退回主令牌，功能正常，但电脑端「已配对设备」不会显示这台手机。"
                    + "\n  电脑端插件更新后，执行一次 action=register_device 即可补登记。";
        }
        return AIToolResult.success("扫码配对成功 ✓（已连接）\nbase_url=" + baseUrl
                + tail
                + "\n\n现在可以对 AI 说：远程控制电脑 / 在电脑上执行..."
                + "\n也可以到「工具集 → 设置与系统环境 → 远程连接（电脑）」手动连接/断开/清除配置");
    }

    private AIToolResult handleStatus() {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：还没配对过电脑。"
                    + "\n" + firstTimeHint());
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
            StringBuilder sb = new StringBuilder(statusText(resp, 0));
            sb.append("连接状态: ").append(isConnected(context) ? "已连接 ✓"
                    : "已断开（工具会拒绝执行；点界面「连接」即可恢复）").append("\n");
            String sid = getSessionId();
            sb.append("当前会话: ").append(sid.isEmpty() ? "(未创建，run 时自动创建)" : sid);
            if (!isConnected(context)) {
                sb.append("\n\n重新连接：工具集 → 设置与系统环境 → 远程连接（电脑）→ 点「连接」");
            }
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("查询状态异常: " + e.getMessage());
        }
    }

    /** 数值宽松解析（HTTP 字段可能是 Integer/Long/String） */
    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o == null ? "" : o).trim()); } catch (Exception e) { return 0; }
    }

    /** 对账目标会话：参数 session_id → 当前会话 → 直连会话 */
    private String resolveTargetSession(Map<String, Object> p) {
        Object o = p == null ? null : p.get("session_id");
        String s = o == null ? "" : String.valueOf(o).trim();
        if (!s.isEmpty()) return s;
        // 视图/事件/对账默认看「跟随会话」，不是直连专属会话
        String follow = getSyncTarget(context);
        if (!follow.isEmpty()) return follow;
        return getSessionId();
    }

    /**
     * 电脑端自检（插件 v8 的 /health，逐项 checks）。
     *
     * 手机上最常踩的坑都能一眼看出来：host_cwd 红 = 宿主工作目录不是真实目录，
     * 所有起子进程的工具都会报 ENOENT（看着像 powershell/rg 丢了，其实是被 cwd 连累）。
     */
    private AIToolResult handleHealth() {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：" + firstTimeHint());
        }
        try {
            Map<String, Object> resp = httpJson(baseUrl + "/health", "GET", null, 30);
            int code = asInt(resp.get("_http_code"));
            if (code >= 400) {
                return AIToolResult.fail("电脑端 /health 返回 HTTP " + code + "：" + resp.get("error"));
            }
            int ver = asInt(resp.get("version"));
            if (ver < 8) {
                return AIToolResult.fail("电脑端插件版本 " + (resp.get("version") == null ? "?" : resp.get("version"))
                        + " 太旧（自检要 ≥ 8）。\n请重启电脑上的 DSH，让 v8 插件加载后再试。");
            }
            StringBuilder sb = new StringBuilder("电脑端自检（插件 v" + ver + "）\n");
            sb.append(Boolean.FALSE.equals(resp.get("ok")) ? "总体: 有错误 ✗" : "总体: 正常 ✓").append("\n");
            Object checks = resp.get("checks");
            if (checks instanceof List) {
                for (Object c : (List<?>) checks) {
                    if (!(c instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) c;
                    boolean ok = Boolean.TRUE.equals(m.get("ok"));
                    String sev = String.valueOf(m.get("severity"));
                    String mark = ok ? "  ✓ " : ("error".equals(sev) ? "  ✗ " : "  ○ ");
                    sb.append(mark).append(m.get("name")).append(": ")
                      .append(m.get("detail")).append("\n");
                    if (!ok && m.get("hint") != null) {
                        sb.append("      → ").append(m.get("hint")).append("\n");
                    }
                }
            }
            Object cwd = resp.get("cwd");
            if (cwd instanceof Map) {
                Object warn = ((Map<?, ?>) cwd).get("warning");
                if (warn instanceof Map) {
                    sb.append("⚠ cwd 降级: 请求 ").append(((Map<?, ?>) warn).get("requested"))
                      .append(" 不可用 → ").append(((Map<?, ?>) warn).get("fallback")).append("\n");
                }
            }
            Object agent = resp.get("agent");
            if (agent instanceof Map) {
                sb.append("预设: ").append(((Map<?, ?>) agent).get("preset"))
                  .append("  会话: ").append(((Map<?, ?>) agent).get("sessions_count")).append("\n");
            }
            return AIToolResult.success(sb.toString().trim());
        } catch (Exception e) {
            return AIToolResult.fail("自检失败: " + e.getMessage());
        }
    }

    /**
     * 协议 v2 统一视图：渲染服务端 cards（状态 / 自检 / 会话 / 时间线）。
     * 与电脑面板、手机页面用的是同一份数据，不存在"两端说法不一致"。
     */
    private AIToolResult handleView(Map<String, Object> parameters) {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：" + firstTimeHint());
        }
        String sid = resolveTargetSession(parameters);
        try {
            Map<String, Object> resp = RemoteDshTool.fetchView(context, sid);
            int code = asInt(resp.get("_http_code"));
            if (code >= 400) {
                return AIToolResult.fail("取视图失败 HTTP " + code + "：" + resp.get("error"));
            }
            if (!(resp.get("cards") instanceof List)) {
                return AIToolResult.fail("电脑端没有返回 cards（插件可能是旧版，需要 ≥ v8/v2）");
            }
            return AIToolResult.success(renderViewText(resp));
        } catch (Exception e) {
            return AIToolResult.fail("视图获取失败: " + e.getMessage());
        }
    }

    /**
     * 把 v2 ViewModel 渲染成文本（**唯一实现**：AI 工具与「远程连接」页共用）。
     * 只认 cards[].kind：status/session(rows) · checks(items) · timeline(messages)。
     */
    public static String renderViewText(Map<String, Object> resp) {
        StringBuilder sb = new StringBuilder("电脑端视图（v2）\n");
        Object header = resp.get("header");
        if (header instanceof Map) {
            Object t = ((Map<?, ?>) header).get("title");
            if (t != null && !String.valueOf(t).trim().isEmpty()) sb.append("会话: ").append(t).append("\n");
        }
        Object cards = resp.get("cards");
        if (cards instanceof List) {
            for (Object co : (List<?>) cards) {
                if (!(co instanceof Map)) continue;
                Map<?, ?> c = (Map<?, ?>) co;
                sb.append("· ").append(c.get("title") == null ? c.get("kind") : c.get("title")).append("\n");
                Object rows = c.get("rows");
                if (rows instanceof List) {
                    for (Object r : (List<?>) rows) {
                        if (!(r instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) r;
                        sb.append("  ").append(m.get("label")).append(": ").append(m.get("value")).append("\n");
                    }
                }
                Object items = c.get("items");
                if (items instanceof List) {
                    for (Object it : (List<?>) items) {
                        if (!(it instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) it;
                        String state = String.valueOf(m.get("state"));
                        String mark = "ok".equals(state) ? "✓ " : ("error".equals(state) ? "✗ " : "○ ");
                        sb.append("  ").append(mark).append(m.get("name")).append(": ").append(m.get("detail")).append("\n");
                    }
                }
                Object msgs = c.get("messages");
                if (msgs instanceof List) {
                    for (Object mo : (List<?>) msgs) {
                        if (!(mo instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) mo;
                        String role;
                        if ("user".equals(String.valueOf(m.get("role")))) {
                            role = "phone".equals(String.valueOf(m.get("origin"))) ? "你(手机)" : "你(电脑)";
                        } else {
                            role = "AI";
                        }
                        String text = String.valueOf(m.get("text") == null ? "" : m.get("text"))
                                .replaceAll("\\s+", " ");
                        if (text.length() > 60) text = text.substring(0, 60) + "…";
                        sb.append("  ").append(role).append(": ").append(text).append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 会话身份管理（协议 v2 / RemoteSdk）：state 查绑定、list 列电脑会话、attach 改绑定。
     * 关键：返回的 id 一律是**电脑签发**的，客户端只保存/展示，绝不自己造。
     */
    private AIToolResult handleSession(Map<String, Object> parameters) {
        RemoteSdk sdk = new RemoteSdk(context);
        if (!sdk.isConfigured()) {
            return AIToolResult.fail("remote_dsh 未配置：" + firstTimeHint());
        }
        String sub = str(parameters.get("session_action")).trim().toLowerCase();
        if (sub.isEmpty()) sub = "state";
        String code = str(parameters.get("code")).trim();
        if (code.isEmpty()) code = getDirectCode(context);
        try {
            if ("list".equals(sub)) {
                Map<String, Object> r = sdk.listSessions(20);
                Object arr = r.get("sessions");
                if (!(arr instanceof List)) {
                    return AIToolResult.fail("电脑端没有返回会话列表（插件需要 ≥ v8/v2）");
                }
                StringBuilder sb = new StringBuilder("电脑上的会话（" + ((List<?>) arr).size() + " 条）\n");
                int i = 0;
                for (Object o : (List<?>) arr) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    sb.append(++i).append(". ")
                      .append(m.get("title") == null || String.valueOf(m.get("title")).isEmpty()
                              ? "(无标题)" : m.get("title"))
                      .append("\n   id: ").append(m.get("id")).append("\n");
                }
                sb.append("\n用 session_action=attach + target_session_id=<id> 接到当前对话码上");
                return AIToolResult.success(sb.toString());
            }
            if ("attach".equals(sub)) {
                String target = str(parameters.get("target_session_id")).trim();
                if (target.isEmpty()) {
                    return AIToolResult.fail("attach 需要 target_session_id（先 session_action=list 查）");
                }
                Map<String, Object> r = sdk.attach(target, code, true);
                String id = RemoteSdk.sessionIdOf(r);
                if (id.isEmpty()) return AIToolResult.fail("绑定失败：" + r.get("error"));
                return AIToolResult.success("已把对话码「" + (code.isEmpty() ? "默认" : code) + "」接到 " + id);
            }
            Map<String, Object> r = sdk.sessionState(code);
            Object sObj = r.get("session");
            StringBuilder sb = new StringBuilder("会话身份（电脑签发）\n");
            sb.append("对话码: ").append(code.isEmpty() ? "(默认直连)" : code).append("\n");
            if (!(sObj instanceof Map)) {
                sb.append("电脑没有返回 session 字段");
                return AIToolResult.success(sb.toString());
            }
            Map<?, ?> s = (Map<?, ?>) sObj;
            String id = RemoteSdk.sessionIdOf(r);
            sb.append("会话 id: ").append(id.isEmpty() ? "(尚未创建，发第一条消息时创建)" : id).append("\n");
            sb.append("已绑定: ").append(Boolean.TRUE.equals(s.get("bound")) ? "是" : "否").append("\n");
            Object ex = r.get("execution");
            if (ex instanceof Map) {
                Object kind = ((Map<?, ?>) ex).get("kind");
                Object profile = ((Map<?, ?>) ex).get("profile");
                sb.append("执行位置: ").append(kind)
                  .append("（").append(profile == null ? "宿主进程内" : profile).append("）\n");
            }
            if (Boolean.TRUE.equals(s.get("needs_first_message"))) {
                sb.append("发一条消息即会自动创建并绑定");
            }
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("会话操作失败: " + e.getMessage());
        }
    }

    /**
     * 事件流（/v2/stream）：拉 since 之后的事件，给 AI 一份"刚才发生了什么"。
     * 事件信封与电脑面板渲染的是同一份，只是这里转成文本。
     */
    private AIToolResult handleEvents(Map<String, Object> parameters) {
        RemoteSdk sdk = new RemoteSdk(context);
        if (!sdk.isConfigured()) {
            return AIToolResult.fail("remote_dsh 未配置：" + firstTimeHint());
        }
        String sid = resolveTargetSession(parameters);
        if (sid.isEmpty()) {
            return AIToolResult.fail("需要会话 id：先 run 一条任务，或用 session_action=list 查一条");
        }
        long since = asInt(parameters.get("since"));
        int wait = asInt(parameters.get("wait_ms"));
        if (wait <= 0) wait = 3000;
        wait = Math.max(500, Math.min(15000, wait));
        try {
            java.util.List<java.util.Map<String, Object>> events = sdk.events(sid, since, wait);
            if (events.isEmpty()) {
                return AIToolResult.success("最近 " + wait + "ms 没有新事件（since=" + since + "，会话 " + sid + "）");
            }
            StringBuilder sb = new StringBuilder("收到 " + events.size() + " 条事件（since=" + since + "）\n");
            long last = since;
            for (java.util.Map<String, Object> e : events) {
                long seq = e.get("seq") instanceof Number ? ((Number) e.get("seq")).longValue() : 0;
                if (seq > last) last = seq;
                String type = String.valueOf(e.get("type"));
                String text = eventText(e);
                sb.append("#").append(seq).append(" ").append(type);
                if (!text.isEmpty()) sb.append("：").append(text);
                sb.append("\n");
            }
            sb.append("\n如需确认收到：action=ack seq=").append(last);
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("读事件流失败: " + e.getMessage());
        }
    }

    /** 从事件 data 里抽一句可读文本（消息、工具卡都尽量给点信息） */
    private static String eventText(java.util.Map<String, Object> e) {
        Object d = e.get("data");
        if (!(d instanceof java.util.Map)) return "";
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) d;
        Object body = m.get("message") instanceof java.util.Map ? m.get("message") : m;
        String text = "";
        if (body instanceof java.util.Map) {
            java.util.Map<?, ?> b = (java.util.Map<?, ?>) body;
            Object t = b.get("text");
            if (t != null) text = String.valueOf(t);
            if (text.isEmpty()) {
                Object c = b.get("content");
                if (c instanceof java.util.List) {
                    StringBuilder sb = new StringBuilder();
                    for (Object p : (java.util.List<?>) c) {
                        if (p instanceof java.util.Map) {
                            Object pt = ((java.util.Map<?, ?>) p).get("text");
                            if (pt != null) sb.append(pt);
                        }
                    }
                    text = sb.toString();
                }
            }
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > 80) text = text.substring(0, 80) + "…";
        return text;
    }

    /**
     * 双端对账（插件 v8 的 /reconcile，light 模式）。
     *
     * 拿电脑侧的权威摘要（digest / last_seq / 消息数）+ 回执水位线，
     * 和本机看到的最后序号对照，回答"我落后没有、还差几条"。
     */
    private AIToolResult handleReconcile(Map<String, Object> parameters) {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：" + firstTimeHint());
        }
        String sid = resolveTargetSession(parameters);
        if (sid.isEmpty()) {
            return AIToolResult.fail("没有可对账的会话：先 run 一条任务，或用 list_sessions 查一条 session_id 再指定");
        }
        try {
            int localSeq = getLastSeq(context, sid);
            String url = baseUrl + "/reconcile?light=1&session_id="
                    + java.net.URLEncoder.encode(sid, "UTF-8")
                    + "&last_seq=" + Math.max(0, localSeq);
            Map<String, Object> resp = httpJson(url, "GET", null, 30);
            int code = asInt(resp.get("_http_code"));
            if (code == 404 || "no_session".equals(resp.get("verdict"))) {
                return AIToolResult.fail("电脑上没有这条会话：" + sid
                        + "\n" + (resp.get("hint") == null ? "" : resp.get("hint")));
            }
            if (code >= 400) {
                return AIToolResult.fail("对账失败 HTTP " + code + "：" + resp.get("error"));
            }
            StringBuilder sb = new StringBuilder("双端对账（电脑侧为准）\n");
            sb.append("会话: ").append(sid).append("\n");
            sb.append("结论: ").append(verdictText(resp)).append("\n");
            Object gap = resp.get("gap");
            if (gap != null) sb.append("落后: ").append(gap).append(" 条事件\n");
            Object host = resp.get("host");
            if (host instanceof Map) {
                Map<?, ?> h = (Map<?, ?>) host;
                sb.append("电脑端: 消息 ").append(h.get("message_count"))
                  .append(" 条，事件 ").append(h.get("event_count"))
                  .append("，last_seq=").append(h.get("last_seq")).append("\n");
                sb.append("摘要: ").append(h.get("digest")).append("\n");
            }
            Object ack = resp.get("ack");
            if (ack instanceof Map) {
                Map<?, ?> a = (Map<?, ?>) ack;
                sb.append("已回执到: seq=").append(a.get("acked_seq"))
                  .append("（未回执 ").append(a.get("unacked_count")).append(" 条）\n");
            }
            sb.append("\n若本机已完整收到，执行 action=ack seq=<电脑端 last_seq> 推进水位线。");
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("对账异常: " + e.getMessage());
        }
    }

    /** 对账结论的人话（新服务端只回 match/behind；旧版可能回 summary/diverged） */
    private static String verdictText(Map<String, Object> resp) {
        Object v = resp == null ? null : resp.get("verdict");
        if ("match".equals(v)) return "一致（本机游标已到电脑端最新）";
        if ("behind".equals(v)) return "落后（需要补拉）";
        if ("diverged".equals(v)) return "不一致（按摘要比对）";
        return "未判定（服务端旧版，没带 last_seq）";
    }

    /** 回执：告诉电脑本机已收到 seq 之前的全部消息（水位线只增不减，落盘不丢） */
    private AIToolResult handleAck(Map<String, Object> parameters) {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            return AIToolResult.fail("remote_dsh 未配置：" + firstTimeHint());
        }
        String sid = resolveTargetSession(parameters);
        if (sid.isEmpty()) {
            return AIToolResult.fail("ack 需要会话：先 run 一条任务，或指定 session_id");
        }
        int seq = asInt(parameters == null ? null : parameters.get("seq"));
        if (seq <= 0) {
            return AIToolResult.fail("ack 需要 seq（已确认收到的最后一条序号）；"
                    + "可先 action=reconcile 看电脑端的 last_seq");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("session_id", sid);
            body.put("seq", seq);
            Map<String, Object> resp = httpJson(baseUrl + "/ack", "POST", body, 30);
            int code = asInt(resp.get("_http_code"));
            if (code >= 400) {
                return AIToolResult.fail("回执失败 HTTP " + code + "：" + resp.get("error"));
            }
            Object ack = resp.get("ack");
            if (ack instanceof Map) {
                return AIToolResult.success("回执已记录：seq=" + ((Map<?, ?>) ack).get("acked_seq")
                        + "，累计回执 id " + ((Map<?, ?>) ack).get("acked_count") + " 个");
            }
            return AIToolResult.success("回执已记录");
        } catch (Exception e) {
            return AIToolResult.fail("回执异常: " + e.getMessage());
        }
    }

    /**
     * 状态文本（handleStatus 与「远程连接（电脑）」界面的探测共用）。
     * 版本号一律来自 ACP 自报（agentInfo），不写死——之前写死过 "dsh 0.1.5"，
     * 手机端 AI 据此判断"版本不兼容"，而实际运行的是另一个版本，属于误导。
     */
    private static String statusText(Map<String, Object> resp, long ms) {
        StringBuilder sb = new StringBuilder();
        Object ver = resp.get("version");
        sb.append("桥接服务在线 ✓（ACP 通道，桥接 v").append(ver != null ? ver : "?").append("）");
        if (ms > 0) sb.append("  耗时 ").append(ms).append("ms");
        sb.append("\n");
        Object channels = resp.get("channels");
        boolean acpOk = channels != null && Boolean.TRUE.equals(((Map<?, ?>) channels).get("session_acp"));
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
        if (pp != null) sb.append("权限自动应答: ").append(pp).append("（allow=写文件/跑命令自动放行一次）\n");
        Object streams = resp.get("session_streams");
        if (streams instanceof java.util.List) {
            sb.append("活跃会话流: ").append(((java.util.List<?>) streams).size()).append("\n");
        }
        // 计数类文案由服务端给（text_lines），两端不再各拼一套；旧服务端没有这个字段时退回本地拼
        Object lines = resp.get("text_lines");
        if (lines instanceof List && !((List<?>) lines).isEmpty()) {
            for (Object l : (List<?>) lines) sb.append(l).append("\n");
        } else {
            Object cnt = resp.get("sessions_count");
            Object pluginSessions = resp.get("plugin_sessions");
            if (cnt != null) {
                sb.append("电脑端会话: ").append(cnt).append(" 条");
                if (pluginSessions != null) sb.append("（插件在管 ").append(pluginSessions).append(" 条）");
                sb.append("\n");
            }
            Object jobs = resp.get("active_jobs");
            if (jobs != null) sb.append("插件在跑任务: ").append(jobs).append(" 个（0 = 空闲）\n");
        }
        // 插件（smartquiz-remote）信息：隧道域名 / 服务端口 / 已配对设备数（来自 /status ✓）
        Object pubUrl = resp.get("public_url");
        if (pubUrl != null && !String.valueOf(pubUrl).trim().isEmpty()) {
            sb.append("隧道域名: ").append(pubUrl).append("（配对页会多出一张公网二维码）\n");
        } else {
            sb.append("隧道域名: 未设置（只有局域网二维码；可在电脑 DSH 的「手机远程」面板里填写）\n");
        }
        Object lanPort = resp.get("lan_port");
        if (lanPort != null) sb.append("插件服务端口: ").append(lanPort).append("\n");
        Object devCount = resp.get("devices");
        if (devCount != null) sb.append("已配对设备: ").append(devCount).append(" 台（可在电脑插件面板吊销）\n");
        // 手机还在用主令牌时 devices 会是 0（主令牌不登记设备记录），必须说清，否则用户会以为"没人连着"。
        // 但判断要收紧（2026-10-06 实测误报）：只要电脑端已经登记了设备，就不该再提示——
        // 探测页面时可能刚好落在"扫码成功、设备令牌还没落盘"那一两秒内，之前会打出
        // "已配对设备 1 台" 和 "当前连接在用主令牌" 两句自相矛盾的话。
        long devNum = 0;
        try { devNum = Long.parseLong(String.valueOf(devCount)); } catch (NumberFormatException ignored) { /* 老版本无该字段 */ }
        Object st = resp.get("main_token_last_seen");
        long seen = 0;
        try { seen = Long.parseLong(String.valueOf(st)); } catch (NumberFormatException ignored) { /* 老版本插件没有该字段 */ }
        boolean mainTokenUsedRecently = seen > 0 && (System.currentTimeMillis() - seen) < 5 * 60 * 1000L;
        if (devNum <= 0 && mainTokenUsedRecently) {
            sb.append("⚠ 当前连接在用主令牌（")
              .append(Math.max(0, (System.currentTimeMillis() - seen) / 60000)).append(" 分钟前使用过）")
              .append("；主令牌不登记设备记录，所以上面台数为 0。")
              .append("\n  重新扫一次码（或在下面手动保存）会自动登记为设备，之后即可单独吊销。")
              .append("\n");
        }
        return sb.toString();
    }

    /** 供「远程连接（电脑）」界面用：探测一次桥接状态并返回可读文本；未配置/连不上抛异常 */
    public static String probeStatusText(Context context) throws Exception {
        RemoteDshTool t = new RemoteDshTool(context);
        String baseUrl = t.getBaseUrl();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("还没配置电脑地址");
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> resp = t.httpJson(baseUrl + "/status", "GET", null, 15);
        long ms = System.currentTimeMillis() - t0;
        if (resp == null) {
            throw new java.io.IOException("电脑端无响应");
        }
        Object code = resp.get("_http_code");
        if (code != null && !String.valueOf(code).startsWith("2")) {
            throw new java.io.IOException("HTTP " + code + " " + resp.get("error"));
        }
        return statusText(resp, ms);
    }

    /** action=connect：用当前配置探测桥接，通了就置为"已连接" */
    private AIToolResult handleConnect() {
        if (getBaseUrl().isEmpty()) {
            return AIToolResult.fail("还没配对过电脑：action=pair 扫码，或到「工具集 → 设置与系统环境 → 远程连接（电脑）」扫码/手填地址+令牌");
        }
        try {
            String text = probeStatusText(context);
            setConnected(context, true);
            return AIToolResult.success("已连接电脑 ✓\n" + text
                    + "\n现在可以直接让我在电脑上干活（run 执行任务 / shell 跑命令）。");
        } catch (Exception e) {
            setConnected(context, false);
            return AIToolResult.fail("连接失败：" + e.getMessage()
                    + "\n地址: " + getBaseUrl()
                    + "\n排查：① 电脑端已安装 DSH 插件且 DSH 正在运行"
                    + " ② 手机与电脑同网或隧道可用 ③ token 是否与电脑端打印的一致"
                    + "\n第一次配电脑端？看教程：工具集 → 设置与系统环境 → 远程连接（电脑）→「怎么用 / 电脑端怎么配」");
        }
    }

    /** action=disconnect：本机断开（配置保留，电脑端不受影响；本地会话记录清掉） */
    private AIToolResult handleDisconnect() {
        boolean wasConnected = isConnected(context);
        disconnect(context);
        return AIToolResult.success("已断开电脑连接 ✓" + (wasConnected ? "" : "（原本就是断开状态）")
                + "\n配置与令牌保留，电脑端桥接/会话不受影响；断开期间工具会拒绝执行。"
                + "\n重新连接：工具集 → 设置与系统环境 → 远程连接（电脑）→ 点「连接」，或对我说「连接电脑」");
    }

    private AIToolResult handleStart() {
        AIToolResult gate = precheck();
        if (gate != null) {
            return gate;
        }
        String baseUrl = getBaseUrl();
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
        AIToolResult gate = precheck();
        if (gate != null) {
            return gate;
        }
        String baseUrl = getBaseUrl();
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
        AIToolResult gate = precheck();
        if (gate != null) {
            return gate;
        }
        String baseUrl = getBaseUrl();
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
                // ★ 两端协商的关键一步：电脑把"这次实际写的是哪条会话"回传，存下来下次带回去。
                //   即使这一轮失败（超时/中断），只要电脑报了 id 也要存 —— 否则下次又新建一条。
                Object dshId = resp.get("dsh_session_id");
                if (dshId != null && !String.valueOf(dshId).trim().isEmpty()) {
                    setDirectSessionId(context, String.valueOf(dshId));
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
                sb.append("\n提示：电脑端 dsh 可能没装好/未登录（ACP 子进程起不来），或该任务超时；"
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
     * action=list_sessions：列出电脑上最近的对话（只读）。
     *
     * 手机端只能看到"自己那条"历史，看不到用户在电脑上正聊的内容。这个动作补上另一半：
     * 先列出来（id + 工作区 + 最后一句），再用 read_session 看详情、attach_session 接着聊。
     */
    private AIToolResult handleListSessions(Map<String, Object> parameters) {
        AIToolResult gate = precheck();
        if (gate != null) return gate;
        int limit = resolveMax(parameters, 10);
        try {
            Map<String, Object> resp = httpJson(getBaseUrl() + "/sessions.json?limit=" + limit, "GET", null, 30);
            Object arr = resp.get("sessions");
            StringBuilder sb = new StringBuilder("电脑上最近的对话：\n");
            int shown = 0;
            if (arr instanceof List) {
                int i = 0;
                for (Object o : (List<?>) arr) {
                    i++;
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String sid = m.get("session_id") == null ? "" : String.valueOf(m.get("session_id"));
                    if (sid.isEmpty()) continue;
                    shown++;
                    String title = m.get("title") == null ? "" : String.valueOf(m.get("title")).trim();
                    sb.append(i).append(". ").append(title.isEmpty() ? sid : title);
                    if (!title.isEmpty()) sb.append("\n   id: ").append(sid);
                    String cwd = m.get("cwd") == null ? "" : String.valueOf(m.get("cwd"));
                    if (!cwd.isEmpty()) sb.append("  @ ").append(baseName(cwd));
                    Object last = m.get("last");
                    if (last != null && !String.valueOf(last).isEmpty()) {
                        sb.append("\n   …").append(String.valueOf(last));
                    }
                    sb.append('\n');
                }
            }
            if (shown == 0) return AIToolResult.success("电脑上没有可列的对话（可能还没聊过，或插件版本太旧）");
            sb.append("\n用 read_session 看某条（target_session_id=上面的 id），或 attach_session 接到当前会话继续。");
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("列电脑端会话失败: " + e.getMessage());
        }
    }

    /** action=read_session：只读查看电脑上某条对话（不会改动它） */
    private AIToolResult handleReadSession(Map<String, Object> parameters) {
        AIToolResult gate = precheck();
        if (gate != null) return gate;
        String target = str(parameters.get("target_session_id"));
        if (target.isEmpty()) return AIToolResult.fail("read_session 需要 target_session_id（先 action=list_sessions 拿到 id）");
        int max = resolveMax(parameters, 20);
        Map<String, Object> body = new HashMap<>();
        body.put("action", "read");
        body.put("session_id", target);
        body.put("max", max);
        try {
            Map<String, Object> resp = httpJson(getBaseUrl() + "/session", "POST", body, 60);
            if (!Boolean.TRUE.equals(resp.get("ok"))) {
                return AIToolResult.fail("读会话失败: " + (resp.get("error") == null ? "未知错误" : resp.get("error")));
            }
            String text = resp.get("text") == null ? "" : String.valueOf(resp.get("text"));
            if (text.isEmpty()) return AIToolResult.success("这条会话里没有可读的文字消息: " + target);
            return AIToolResult.success("电脑端会话 " + target + "（只读）：\n" + text);
        } catch (Exception e) {
            return AIToolResult.fail("读会话失败: " + e.getMessage());
        }
    }

    /** action=attach_session：把电脑上那条对话的上下文接到当前会话，下一条消息接着聊 */
    private AIToolResult handleAttachSession(Map<String, Object> parameters) {
        AIToolResult gate = precheck();
        if (gate != null) return gate;
        String target = str(parameters.get("target_session_id"));
        if (target.isEmpty()) return AIToolResult.fail("attach_session 需要 target_session_id（先 action=list_sessions 拿到 id）");
        String sid = getSessionId();
        if (sid.isEmpty()) {
            return AIToolResult.fail("还没有手机会话：先 action=start 建一条，再 attach_session");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("action", "attach");
        body.put("session_id", sid);
        body.put("target_session_id", target);
        body.put("max", resolveMax(parameters, 20));
        try {
            Map<String, Object> resp = httpJson(getBaseUrl() + "/session", "POST", body, 60);
            if (!Boolean.TRUE.equals(resp.get("ok"))) {
                return AIToolResult.fail("接续失败: " + (resp.get("error") == null ? "未知错误" : resp.get("error")));
            }
            setSyncTarget(context, target);            // 之后 sync_send 默认就用这条
            Object hist = resp.get("history");
            int n = hist instanceof List ? ((List<?>) hist).size() : 0;
            return AIToolResult.success("已把电脑端会话 " + target + " 接到当前会话（带过来 " + n + " 条记录）。"
                    + "\n接下来直接说你要做什么就行——会带着那段上下文继续（电脑端那条不会被改动，两边从此各自演化）。");
        } catch (Exception e) {
            return AIToolResult.fail("接续失败: " + e.getMessage());
        }
    }

    /**
     * action=sync_send：把这句话注入电脑上**正在打开**的那条会话。
     *
     * 与 run 的区别：run 走 CLI 子进程、结果是另一条独立会话；sync_send 走宿主里的 live agent，
     * 电脑端界面上会实时出现你说的话和回复 —— 这才是同一条会话的双端同步。
     */
    private AIToolResult handleSyncSend(Map<String, Object> parameters) {
        AIToolResult gate = precheck();
        if (gate != null) return gate;
        String text = str(parameters.get("task"));
        if (text.isEmpty()) text = str(parameters.get("text"));
        if (text.isEmpty()) return AIToolResult.fail("sync_send 需要 task（要发到电脑上的那句话）");
        String target = str(parameters.get("target_session_id"));
        if (target.isEmpty()) target = getSyncTarget(context);
        if (target.isEmpty()) {
            return AIToolResult.fail("还不知道要同步到哪条会话：先 action=list_sessions 选一条，"
                    + "再 action=attach_session（或直接带 target_session_id）");
        }
        int timeout = resolveTimeout(parameters);
        Map<String, Object> body = new HashMap<>();
        body.put("action", "sync");
        body.put("target_session_id", target);
        body.put("text", text);
        body.put("timeout", timeout);
        try {
            Map<String, Object> resp = httpJson(getBaseUrl() + "/session", "POST", body, timeout + 30);
            if (!Boolean.TRUE.equals(resp.get("ok"))) {
                String err = resp.get("error") == null ? "未知错误" : String.valueOf(resp.get("error"));
                String hint = resp.get("hint") == null ? "" : String.valueOf(resp.get("hint"));
                return AIToolResult.fail("同步失败: " + err + (hint.isEmpty() ? "" : "\n" + hint));
            }
            setSyncTarget(context, target);
            String reply = str(resp.get("reply"));
            setLastSyncReply(context, reply);
            String thinking = str(resp.get("thinking"));
            boolean timedOut = Boolean.TRUE.equals(resp.get("timed_out"));
            // 思考内容**不拼进正文**：交给聊天界面写进 thinkingContent，
            // 由 ReasoningPartRenderer 渲染成可折叠的「已深度思考」段落（和电脑端一致）。
            setLastSyncThinking(context, thinking);
            StringBuilder sb = new StringBuilder();
            sb.append("💻 同步 · ").append(shortId(target))
                    .append(timedOut ? "（这一轮还没跑完，可能在等审批）" : "");
            sb.append("\n").append(reply.isEmpty() ? "（这一轮没有文字回复）" : reply);
            return AIToolResult.success(sb.toString());
        } catch (Exception e) {
            return AIToolResult.fail("同步失败: " + e.getMessage());
        }
    }

    /** action=sync_state：那条会话现在是否在电脑上打开（live 才能同步） */
    private AIToolResult handleSyncState(Map<String, Object> parameters) {
        AIToolResult gate = precheck();
        if (gate != null) return gate;
        String target = str(parameters.get("target_session_id"));
        if (target.isEmpty()) target = getSyncTarget(context);
        if (target.isEmpty()) return AIToolResult.fail("还没有同步目标：先用 list_sessions + attach_session 选一条");
        Map<String, Object> body = new HashMap<>();
        body.put("action", "sync_state");
        body.put("target_session_id", target);
        try {
            Map<String, Object> resp = httpJson(getBaseUrl() + "/session", "POST", body, 30);
            boolean live = Boolean.TRUE.equals(resp.get("live"));
            return AIToolResult.success((live ? "可以同步 ✓ " : "当前不能同步 ✗ ") + target
                    + (live ? "\n这条会话在电脑上开着，sync_send 的消息会直接出现在那个对话里。"
                            : "\n这条会话没在电脑上打开：先在电脑上打开它；或改用 attach_session（复制上下文另起一条）。"));
        } catch (Exception e) {
            return AIToolResult.fail("查询同步状态失败: " + e.getMessage());
        }
    }

    /** 参数 max：1~200，默认 fallback */
    private static int resolveMax(Map<String, Object> parameters, int fallback) {
        Object m = parameters != null ? parameters.get("max") : null;
        if (m == null) return fallback;
        try {
            return (int) Math.min(200, Math.max(1, Double.parseDouble(String.valueOf(m))));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 会话 id 太长：文案里只留尾部，够认就行 */
    private static String shortId(String id) {
        String s = str(id);
        return s.length() <= 12 ? s : "…" + s.substring(s.length() - 10);
    }

    /** 路径最后一段，用于把 cwd 显示短一点 */
    private static String baseName(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /**
     * 声明本工具单次执行需要的超时（毫秒）。
     * 工具管理器默认只给 30s，但这里的任务是"在电脑上真跑"（起 dsh agent/跑命令，几十秒很常见）：
     * 实测传 timeout=150 仍在 30s 被掐断、模型只拿到空结果。故按 timeout + 45s 余量申报。
     * 硬上限与"基线 vs 申报取较大者"的裁定都在 {@link ToolTimeouts}，两条执行路径共用。
     */
    @Override
    public long executionTimeoutMs(Map<String, Object> args) {
        int t = resolveTimeout(args);
        return Math.min(ToolTimeouts.MAX_TOOL_TIMEOUT_MS, (t + 45L) * 1000L);
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
        AIToolResult gate = precheck();
        if (gate != null) {
            return gate;
        }
        String baseUrl = getBaseUrl();
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
        // 幂等键：手机重试/重连补发时带同一个 id，电脑端据此去重（避免同一条消息被执行两次）
        body.put("client_msg_id", java.util.UUID.randomUUID().toString());
        // 两端协商：把电脑上次给的会话 id / 对话码带回去，电脑据此续同一条对话
        String direct = getDirectSessionId(context);
        if (!direct.isEmpty()) body.put("target_session_id", direct);
        String code = getDirectCode(context);
        if (!code.isEmpty()) body.put("session_code", code);
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
    Map<String, Object> httpJson(String url, String method, Map<String, Object> body, int timeoutSec) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // 公网/隧道（花生壳、Cloudflare 等）首包握手 + TLS 在蜂窝网络下常超过 10s（实测 10000ms 超时 → 探测失败 ✗）
            // 局域网仍很快，这里放大只是给隧道留余量，不影响本机连接的手感
            conn.setConnectTimeout(30000);
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
