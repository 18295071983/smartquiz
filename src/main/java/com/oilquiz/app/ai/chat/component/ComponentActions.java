package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 组件动作行渲染与执行（actions 按钮）。
 *
 * 组件数据 props.actions 声明可点击按钮：
 * <pre>
 * "actions": [
 *   {"label":"打开","link":"https://..."},   // http/https 应用内打开；file:// 或绝对路径系统打开
 *   {"label":"复制","copy":"要复制的文本"},
 *   {"label":"打开微信","action":"open_app","app":"微信"},              // 或 package:"com.tencent.mm"
 *   {"label":"打开设置","action":"am","value":"com.android.settings"}, // 兼容 am start -n pkg/.Activity
 *   {"label":"拨号","action":"dial","value":"10086"},
 *   {"label":"地图","action":"map","value":"天安门"}
 * ]
 * </pre>
 * 供 AlertCardView / DynamicCardView（自定义组件兜底）等组件共用，
 * 让 Agent 输出的组件按钮真实可点、点击有反应（不再"不能回调"）。
 *
 * <p><b>一条 action 可以同时有两层意思</b>：{@code {label,link,copy,app,package}} 是"给用户做的事"，
 * {@code component_id} 是"给 Agent 回传的地址"。点击先把前者执行掉，再回传后者——
 * 顺序反过来会把带链接的按钮变成一句"已选择"toast（2026-11-06 真机：卡片上"高德 App 直呼"
 * 点下去只弹 toast，高德没起来）。</p>
 *
 * <p>layout 卡片里的 {@code button}/{@code notice} 节点也走同一套语义
 * （{@code NativeLayoutRenderer.wireActionClick} 把节点字段拼成这里的 action 再执行）——
 * 否则聊天流里的布局卡片只有 tag、没有监听器，"打开微信"按钮点下去没反应。</p>
 */
public final class ComponentActions {

    /** 组件结果回调：component_id → 用户操作结果（供 Agent get_result 取回） */
    private static volatile java.util.function.BiConsumer<String, String> resultCallback;

    /**
     * 按 component_id 的增量回调注册表（替代覆盖式 setResultCallback）：
     * 多会话/多组件并发创建时互不覆盖，close 后移除。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.function.BiConsumer<String, String>>
            componentCallbacks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 输入控件收集器注册表：component_id → 该组件渲染的输入控件值收集器（Supplier）。
     * 用于 layout 卡片等容器：卡片内含 input/select/switch 等输入控件，点击提交按钮
     * （action=callback）时除了回传按钮 value，还把容器内输入控件值一并收集回传
     * （修复 "layout 控件输入值无法回传"：此前按钮只回传自身 value，输入值全部丢失）。
     * 用 WeakReference 持有：卡片视图被回收（聊天流滚动/Activity 销毁）后自动失效，
     * 不泄漏 Activity/View。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.lang.ref.WeakReference<java.util.function.Supplier<java.util.Map<String, Object>>>>
            inputCollectors = new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册组件的输入控件值收集器（LayoutCardView 渲染时调用）。 */
    public static void registerInputCollector(String componentId,
                                              java.util.function.Supplier<java.util.Map<String, Object>> collector) {
        if (componentId != null && collector != null) {
            inputCollectors.put(componentId, new java.lang.ref.WeakReference<>(collector));
        }
    }

    /**
     * 组件的回传地址（{@code get_result} 认领的那个 id）：取 {@code props.actions[].component_id}
     * 里第一个非空值；没有则返回 null。
     *
     * <p>这个 id 由 {@code SystemUIComponentTool.create} 在卡片<b>带 actions</b>（＝交互组件）时生成并
     * 注入到每个 action（{@code interactive = props.has("actions")} 时才注册回调）。它**不是**
     * {@code ComponentData.id}（那是 COMPONENT 子 id，如 {@code T1-C1}，只用于会话内定位/更新组件），
     * 拿错会让 {@link #notifyResult} 找不到回调、静默失败。</p>
     *
     * <p>口径原先只写在 {@code LayoutCardView} 的渲染里；抽到这里后，layout 卡与 html 卡的
     * {@code submit} 共用同一条取址逻辑，避免长出第二套约定。</p>
     */
    public static String resolveComponentId(com.oilquiz.app.ai.chat.component.ComponentData data) {
        if (data == null || data.props == null) {
            return null;
        }
        return resolveComponentId(data.props);
    }

    /** {@link #resolveComponentId(ComponentData)} 的 props 版（渲染现场常只有 props）。 */
    public static String resolveComponentId(JSONObject props) {
        if (props == null) {
            return null;
        }
        JSONArray actions = props.optJSONArray("actions");
        if (actions == null) {
            return null;
        }
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String cid = a.optString("component_id", "");
            if (!cid.isEmpty()) {
                return cid;
            }
        }
        return null;
    }

    /** 收集组件的输入控件值；无收集器或已失效返回 null。 */
    public static java.util.Map<String, Object> collectInputs(String componentId) {
        if (componentId == null) return null;
        java.lang.ref.WeakReference<java.util.function.Supplier<java.util.Map<String, Object>>> ref =
                inputCollectors.get(componentId);
        if (ref == null) return null;
        java.util.function.Supplier<java.util.Map<String, Object>> s = ref.get();
        if (s == null) {
            inputCollectors.remove(componentId);
            return null;
        }
        try {
            return s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private ComponentActions() {
    }

    /**
     * 创建交互卡片时**统一归一 actions**（SystemUIComponentTool / PythonToolManager 两条生产者共用）：
     * 每个 action 注入 {@code component_id}（get_result 的地址），只有按钮自己没有任何可执行内容
     * 才默认 {@code action:"callback"}。
     *
     * <p>不要在各生产者里再抄一遍：无条件补 callback 会把 {@code {label,link}} /
     * {@code {label,copy}} 的打开/复制语义吃掉，点击只剩"已选择"toast（2026-11-06 真机）。</p>
     */
    public static void prepareInteractiveActions(org.json.JSONArray actions, String componentId) {
        if (actions == null || componentId == null || componentId.isEmpty()) return;
        for (int i = 0; i < actions.length(); i++) {
            try {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) continue;
                a.put("component_id", componentId);
                if (!a.has("action") && !hasOwnEffect(a)) {
                    a.put("action", "callback");
                }
            } catch (Throwable ignored) {
                // 单条 action 写不进去不影响其余按钮
            }
        }
    }

    /**
     * 这条 action 自己有没有可执行内容（有就不该被当成纯 callback）。
     * 判据与 {@link #actionRunner}/{@link #perform} 一致：link / copy / app / package 任一非空，
     * 或 value 本身是链接/包名。
     */
    public static boolean hasOwnEffect(JSONObject action) {
        if (action == null) return false;
        if (!action.optString("link", "").isEmpty()) return true;
        if (!action.optString("copy", "").isEmpty()) return true;
        if (!action.optString("app", "").isEmpty()) return true;
        if (!action.optString("package", "").isEmpty()) return true;
        String value = action.optString("value", "").trim();
        if (value.isEmpty()) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("am ") || value.startsWith("package:")
                || value.matches("^[A-Za-z][A-Za-z0-9+.\\-]*:.+")
                || value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){2,}");
    }

    /**
     * 按 component_id 注册组件结果回调（推荐）。
     * 回调按 id 独立存放，多个组件同时存在时互不覆盖。
     */
    public static void registerComponentCallback(String componentId,
                                                 java.util.function.BiConsumer<String, String> callback) {
        if (componentId != null && callback != null) {
            componentCallbacks.put(componentId, callback);
        }
    }

    /** 移除指定组件的回调（组件关闭/销毁时调用，避免常驻泄漏） */
    public static void unregisterComponentCallback(String componentId) {
        if (componentId != null) {
            componentCallbacks.remove(componentId);
        }
    }

    /** 确保回调已注册：app 重启/历史会话恢复后静态回调丢失，点击时自动补注册兜底。 */
    private static java.util.function.BiConsumer<String, String> ensureResultCallback(android.content.Context context) {
        java.util.function.BiConsumer<String, String> cb = resultCallback;
        if (cb != null) return cb;
        synchronized (ComponentActions.class) {
            if (resultCallback == null) {
                try {
                    android.content.Context appCtx = context != null
                            ? context.getApplicationContext()
                            : com.oilquiz.app.SmartQuizApplication.getAppContext();
                    if (appCtx == null) return null;
                    com.oilquiz.app.ai.python.PythonToolManager ptm =
                            com.oilquiz.app.ai.python.PythonToolManager.getInstance(appCtx);
                    resultCallback = (cid, value) -> {
                        if (cid != null && value != null) {
                            ptm.notifyChatComponentResult(cid, value);
                        }
                    };
                } catch (Throwable t) {
                    android.util.Log.w("ComponentActions", "兜底注册回调失败: " + t.getMessage());
                }
            }
            return resultCallback;
        }
    }

    /** 通知组件结果（组件按钮 action=callback 时调用）。优先按 component_id 分发，未注册时回退全局回调。 */
    /**
     * 公开入口：向组件结果回调体系分发一次用户操作。
     * 用于非组件按钮来源的用户交互（如 ChatAdapter 操作栏/长按菜单），让 Agent 能通过
     * get_result 取回用户在 UI 上的选择。
     *
     * @param context     用于兜底注册全局回调
     * @param componentId 组件/通道 id；null 或 empty 时分发到全局 resultCallback
     * @param value       用户操作值（如 "like" / "quote:T1-2" / "stop" / "delete:T3"）
     */
    public static void dispatchResult(Context context, String componentId, String value) {
        notifyResult(context, componentId, value);
    }

    /**
     * ChatAdapter 用户交互统一分发：走专用 componentId="chat_ui_actions"，
     * Agent 可通过 get_result("chat_ui_actions") 取到最近一次消息级操作。
     * value 语义：
     *  - like:&lt;msgId&gt; / dislike:&lt;msgId&gt; — 反馈
     *  - speak:&lt;msgId&gt; / stop_speak — 朗读控制
     *  - stop:&lt;msgId&gt; — 停止生成
     *  - quote:&lt;msgId&gt; / edit:&lt;msgId&gt; / delete:&lt;msgId&gt; / detail:&lt;msgId&gt; — 长按菜单
     *  - regen:&lt;msgId&gt; / new_chat — 操作栏
     */
    public static final String CHAT_UI_CHANNEL = "chat_ui_actions";

    public static void dispatchChatAction(Context context, String action) {
        notifyResult(context, CHAT_UI_CHANNEL, action);
    }

    private static void notifyResult(Context context, String componentId, String value) {
        android.util.Log.i("ComponentActions", "notifyResult cid=" + componentId + " value=" + value
                + " cb=" + (resultCallback != null));
        // 1. 按 component_id 精确分发（增量注册表优先）
        if (componentId != null) {
            java.util.function.BiConsumer<String, String> cb = componentCallbacks.get(componentId);
            if (cb != null) {
                try {
                    cb.accept(componentId, value);
                } catch (Throwable ignored) {
                }
                return;
            }
        }
        // 2. 回退全局回调（兼容旧组件/未注册场景）
        java.util.function.BiConsumer<String, String> cb = resultCallback;
        if (cb == null) {
            cb = ensureResultCallback(context);
            android.util.Log.i("ComponentActions", "兜底注册后 cb=" + (cb != null));
        }
        if (cb != null && componentId != null && value != null) {
            try {
                cb.accept(componentId, value);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 在卡片底部渲染动作按钮行（无 actions 或全部无效时返回 false，不添加任何 View）。
     *
     * <p>已接 {@link CardShell} 的组件请改用 {@code shell.addActions(actions)}：
     * 按钮落在卡片 footer 内，不再飘到卡片背景之外。两条链路走同一个
     * {@link #perform} 语义，行为一致。
     */
    public static boolean renderActions(LinearLayout card, Context context, JSONArray actions) {
        if (card == null || context == null || actions == null || actions.length() == 0) {
            return false;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(context, 8), 0, 0);
        boolean added = false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            final Runnable runner = actionRunner(context, a);
            if (runner == null) continue;
            TextView btn = new TextView(context);
            btn.setText(a.optString("label", "打开"));
            btn.setTextSize(12);
            btn.setTextColor(ComponentColors.accent(context));
            btn.setPadding(0, 0, dp(context, 16), 0);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            btn.setClickable(true);
            btn.setOnClickListener(v -> runner.run());
            row.addView(btn);
            added = true;
        }
        if (added) {
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return added;
    }

    /**
     * 把一条 action 编译成点击回调；字段不合法（既无 link 又无 copy 又无 action）返回 null。
     */
    public static Runnable actionRunner(Context context, JSONObject a) {
        if (a == null || context == null) return null;
        final String label = a.optString("label", "打开");
        final String link = a.optString("link", "");
        final String copy = a.optString("copy", "");
        final String action = a.optString("action", "");
        final String componentId = a.optString("component_id", "");
        final String value = a.optString("value", "");
        final String app = a.optString("app", "");
        final String pkg = a.optString("package", "");
        final String text = a.optString("text", "");
        final String subject = a.optString("subject", "");
        if (link.isEmpty() && copy.isEmpty() && action.isEmpty()
                && app.isEmpty() && pkg.isEmpty()
                && !isUsableTarget(value) && !CardActionTarget.looksLikePackage(value)) {
            // 连 value 也不是链接/包名：这条 action 没有任何可执行内容，别渲染成假按钮
            return null;
        }
        return () -> perform(context, label, link, copy, action, componentId, value,
                app, pkg, text, subject);
    }

    /**
     * 动作点击的统一语义（footer 按钮与旧动作行共用）：
     * <b>先做真实动作</b>（link 打开 / copy 复制 / open_app·am 启动应用 / dial·sms·email·map 调系统界面），
     * <b>再回传</b>（callback/refresh，或卡片本身有回传地址时把这次点击交给 Agent 的 get_result）。
     *
     * <p>顺序很重要（2026-11-06 真机）：旧实现把 callback 当互斥分支挡在最前面，而
     * {@code SystemUIComponentTool}/{@code PythonToolManager} 创建卡片时又给每个 action 无条件补
     * {@code action:"callback"}，于是 {@code {label,link:amapuri://…}} 点下去只剩一句
     * "已选择: ① 高德 App 直呼"——用户看到的"弹个 toast 就完事、没有下一步"就是这里。</p>
     */
    public static void perform(Context context, String label, String link, String copy,
                               String action, String componentId, String value) {
        perform(context, label, link, copy, action, componentId, value, "", "", "", "");
    }

    public static void perform(Context context, String label, String link, String copy,
                               String action, String componentId, String value,
                               String app, String pkg, String text, String subject) {
        try {
            boolean refreshOnly = "refresh".equals(action);
            // action 为空时的 value 也可能是模型唯一给的执行目标（链接/包名）。
            // 显式 action=callback 时不动它：那是"回传值"，不是"打开值"。
            boolean valueIsTarget = action.isEmpty()
                    && (isUsableTarget(value) || CardActionTarget.looksLikePackage(value));
            boolean hasRealWork = !refreshOnly && (!link.isEmpty() || !copy.isEmpty()
                    || !app.isEmpty() || !pkg.isEmpty()
                    || (!action.isEmpty() && !"callback".equals(action))
                    || valueIsTarget);
            boolean hasCallbackTarget = componentId != null && !componentId.isEmpty();

            if (hasRealWork) {
                execute(context, link, copy, action, value, app, pkg, text, subject);
            }
            if (!hasCallbackTarget) {
                if (!hasRealWork) {
                    // 既没有可执行内容、又没有回传地址：说清缺什么，别静默
                    execute(context, link, copy, action, value, app, pkg, text, subject);
                }
                return;
            }
            if (refreshOnly) {
                Toast.makeText(context, "已请求刷新: " + label, Toast.LENGTH_SHORT).show();
                notifyResult(context, componentId, "refresh");
                return;
            }
            // 回传给 Agent：真实动作已经做过了就不要再"已选择"糊一次脸，
            // 纯 callback（只有 value、没有 link/copy/应用）才用 toast 确认这一下被记下了。
            String payload = (value == null || value.isEmpty()) ? label : value;
            try {
                java.util.Map<String, Object> inputs = collectInputs(componentId);
                if (inputs != null && !inputs.isEmpty()) {
                    JSONObject merged = new JSONObject();
                    merged.put("action", payload);
                    merged.put("inputs", new JSONObject(
                            new com.google.gson.Gson().toJson(inputs)));
                    payload = merged.toString();
                }
            } catch (Throwable ignored) {
            }
            if (!hasRealWork) {
                Toast.makeText(context, "已选择: " + payload, Toast.LENGTH_SHORT).show();
            }
            notifyResult(context, componentId, payload);
        } catch (Throwable t) {
            Toast.makeText(context, "动作执行失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 执行单个动作（link 打开 / copy 复制 / action 内置动作） */
    public static void execute(Context context, String link, String copy, String action) {
        execute(context, link, copy, action, "", "", "", "", "");
    }

    public static void execute(Context context, String link, String copy, String action,
                               String value, String app, String pkg, String text, String subject) {
        if (context == null) return;
        try {
            if (!copy.isEmpty()) {
                copyToClipboard(context, copy);
                return;
            }
            // callback 但卡片没有回传地址（创建时没带 actions）：如果按钮自己还带 link/copy/
            // app/package，就把 callback 去掉按真实动作执行；什么都没有才明说缺回传地址。
            if ("callback".equals(action)) {
                if (link.isEmpty() && copy.isEmpty() && app.isEmpty() && pkg.isEmpty()) {
                    Toast.makeText(context,
                            "这张卡没有回传地址（创建时没带 actions），点击没法交给 Agent",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                action = "";
            }
            if (!action.isEmpty()) {
                switch (action) {
                    case "copy": {
                        // 动作名叫 copy 却没写 copy 字段是模型常犯的写法；value/text/subject 里
                        // 有内容就照它复制，别回一句"无可用动作"就完事
                        String what = firstNonEmpty(value, text, subject, link);
                        if (what.isEmpty()) {
                            Toast.makeText(context, "这条复制动作没给内容（copy/value 都是空）",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        copyToClipboard(context, what);
                        return;
                    }
                    case "refresh":
                        // 刷写必须有可寻址的组件；到这儿说明这张卡没有回传地址，说清缺什么
                        Toast.makeText(context, "这张卡没有回传地址：创建时带 actions 的卡片才能刷新",
                                Toast.LENGTH_LONG).show();
                        return;
                    case "open_app":
                    case "launch_app":
                    case "app":
                    case "am":
                    case "am_start":
                        launchApp(context, app, pkg, value);
                        return;
                    case "dial":
                    case "call":
                        dial(context, firstNonEmpty(value, app, pkg));
                        return;
                    case "sms":
                        sms(context, firstNonEmpty(value, app, pkg), text);
                        return;
                    case "email":
                        email(context, firstNonEmpty(value, app, pkg), subject, text);
                        return;
                    case "map":
                        openMap(context, firstNonEmpty(value, app, pkg));
                        return;
                    case "open_url":
                    case "url":
                        openLink(context, firstNonEmpty(link, value));
                        return;
                    default: {
                        // 未知动作名不等于死路：动作自己带的 link/value 能办事就照它办
                        String target = firstNonEmpty(link, value);
                        if (isUsableTarget(target)) {
                            openLink(context, target);
                            return;
                        }
                        if (CardActionTarget.looksLikePackage(target)) {
                            launchApp(context, "", target, "");
                            return;
                        }
                        Toast.makeText(context, "未知动作: " + action
                                        + "（可用: link / copy / open_app / am / dial / sms / email / map / open_url）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            }
            if (!link.isEmpty()) {
                openLink(context, link);
                return;
            }
            // 只给了 app/package 没写 action：按打开应用处理
            if (!app.isEmpty() || !pkg.isEmpty()) {
                launchApp(context, app, pkg, value);
                return;
            }
            // 只剩 value：模型常把链接/包名直接写在 value 上，别让它点了没反应
            String target = value == null ? "" : value.trim();
            if (isUsableTarget(target)) {
                openLink(context, target);
            } else if (CardActionTarget.looksLikePackage(target)) {
                launchApp(context, "", target, "");
            } else if (!target.isEmpty()) {
                copyToClipboard(context, target);
            } else {
                Toast.makeText(context, "这条按钮没写可执行内容（link / copy / app / package / action 都是空）",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "动作执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 能不能当链接打开：http/https 或带 scheme 的 URI（amapuri://、file://、mailto:…） */
    public static boolean isUsableTarget(String target) {
        return CardActionTarget.isHttpUrl(target) || CardActionTarget.isSchemeUri(target);
    }

    /**
     * 卡片开应用的**解析结果**（纯函数产出，不碰 Android API，便于单测）。
     * 四个字段互斥优先级：error &gt; url &gt; package/app。
     */
    public static final class LaunchPlan {
        public final String packageName;
        public final String appName;
        public final String url;
        public final String error;

        private LaunchPlan(String packageName, String appName, String url, String error) {
            this.packageName = packageName == null ? "" : packageName;
            this.appName = appName == null ? "" : appName;
            this.url = url == null ? "" : url;
            this.error = error == null ? "" : error;
        }

        static LaunchPlan error(String message) {
            return new LaunchPlan("", "", "", message);
        }

        static LaunchPlan url(String url) {
            return new LaunchPlan("", "", url, "");
        }

        static LaunchPlan of(String packageName, String appName) {
            return new LaunchPlan(packageName, appName, "", "");
        }

        /** 什么都没解析出来：调用方要提示"动作缺少 app/package"，绝不能静默当成功 */
        public boolean isEmpty() {
            return packageName.isEmpty() && appName.isEmpty() && url.isEmpty();
        }

        /** 交给 system_resource 的参数；空计划返回只有 action 的退化参数（调用方先用 isEmpty 挡掉） */
        public Map<String, Object> toToolParams() {
            Map<String, Object> params = new HashMap<>();
            // system_resource.execute 先看 action，缺了会直接回"缺少参数: action"（失败结果），
            // 卡片按钮必须显式声明走 open_app 分支，否则点击永远报参数错、应用起不来。
            params.put("action", "open_app");
            if (!packageName.isEmpty()) params.put("package", packageName);
            if (!appName.isEmpty() && !appName.equals(packageName)) params.put("app", appName);
            return params;
        }
    }

    /**
     * 把卡片上的 {@code app}/{@code package}/{@code value} 归一成启动计划。
     *
     * <p>{@code value} 支持三种写法：{@code "am start -n pkg/.Act"}（Agent 常照 shell 习惯写）、
     * {@code "com.tencent.mm"}（包名）、{@code "微信"}（应用名，交给 system_resource 模糊匹配）；
     * 以及 {@code am start -d "<url>"} —— 任意 scheme 的 URI（含 {@code amapuri://…} 自定义协议、
     * {@code file://…}）都按链接语义交给 {@link #openLink}，不再当包名截断。
     * {@code am} 的非 start 子命令不在卡片里执行——那是 {@code system_resource(shell_command)} 的事，
     * 卡片按钮只做启动，别在 UI 层长出第二个 shell。</p>
     */
    public static LaunchPlan planLaunch(String app, String pkg, String value) {
        String packageName = pkg == null ? "" : pkg.trim();
        String appName = app == null ? "" : app.trim();
        String target = value == null ? "" : value.trim();

        if (packageName.isEmpty() && !target.isEmpty() && target.toLowerCase().startsWith("am ")) {
            if (!target.toLowerCase().startsWith("am start")) {
                return LaunchPlan.error("卡片只支持 am start 启动应用；其他 am 子命令请让 Agent 用 system_resource(shell_command)");
            }
            String parsed = CardActionTarget.parseAmTarget(target);
            if (CardActionTarget.isHttpUrl(parsed) || CardActionTarget.isSchemeUri(parsed)) {
                return LaunchPlan.url(parsed);
            }
            int slash = parsed.indexOf('/');
            if (slash > 0) {
                packageName = parsed.substring(0, slash);
            } else if (CardActionTarget.looksLikePackage(parsed)) {
                packageName = parsed;
            } else if (!parsed.isEmpty()) {
                appName = parsed;
            }
        } else if (packageName.isEmpty() && CardActionTarget.looksLikePackage(target)) {
            packageName = target;
        } else if (packageName.isEmpty() && appName.isEmpty()) {
            appName = target;
        }
        return LaunchPlan.of(packageName, appName);
    }

    /**
     * 卡片开启应用：优先复用 system_resource(open_app) 的包名/模糊应用名/系统选择器逻辑。
     * action=am 时兼容 "am start -n pkg/.Activity" 和 "am start -d https://..."。
     */
    private static void launchApp(Context context, String app, String pkg, String value) {
        LaunchPlan plan = planLaunch(app, pkg, value);
        if (!plan.error.isEmpty()) {
            Toast.makeText(context, plan.error, Toast.LENGTH_LONG).show();
            return;
        }
        if (!plan.url.isEmpty()) {
            openLink(context, plan.url);
            return;
        }
        if (plan.isEmpty()) {
            Toast.makeText(context, "动作缺少 app/package", Toast.LENGTH_SHORT).show();
            return;
        }
        com.oilquiz.app.ai.tool.AIToolResult result =
                new com.oilquiz.app.ai.tool.SystemResourceTool(context).execute(plan.toToolParams());
        if (result == null) {
            Toast.makeText(context, "打开应用失败: 无执行结果", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!result.isSuccess()) {
            Toast.makeText(context,
                    result.getErrorMessage() == null ? "打开应用失败" : result.getErrorMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static void dial(Context context, String number) {
        if (number.isEmpty()) {
            Toast.makeText(context, "缺少电话号码", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(context, intent, "没有能拨号的应用");
    }

    private static void sms(Context context, String number, String text) {
        if (number.isEmpty()) {
            Toast.makeText(context, "缺少电话号码", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
        if (text != null && !text.isEmpty()) intent.putExtra("sms_body", text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(context, intent, "没有能发短信的应用");
    }

    private static void email(Context context, String to, String subject, String text) {
        if (to.isEmpty()) {
            Toast.makeText(context, "缺少收件人邮箱", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + to));
        if (subject != null && !subject.isEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        if (text != null && !text.isEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(context, intent, "没有能发邮件的应用");
    }

    private static void openMap(Context context, String query) {
        if (query.isEmpty()) {
            Toast.makeText(context, "缺少地图查询内容", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(context, intent, "没有能打开地图的应用");
    }

    /** 卡片里的通讯/地图动作：没装对应应用时给提示，不让 ActivityNotFoundException 冒到主线程 */
    private static void safeStart(Context context, Intent intent, String failure) {
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开链接：http/https 应用内 WebView；file:// 或绝对路径系统 Intent 打开 */
    public static void openLink(Context context, String link) {
        try {
            if (link == null || link.isEmpty()) {
                Toast.makeText(context, "链接为空", Toast.LENGTH_SHORT).show();
                return;
            }
            String lower = link.toLowerCase();
            // 兼容 Agent 把启动指令写进 link 的情况：am start -n pkg/.Act / package:pkg
            if (lower.startsWith("am ")) {
                launchApp(context, "", "", link);
                return;
            }
            if (lower.startsWith("package:")) {
                launchApp(context, "", link.substring("package:".length()).trim(), "");
                return;
            }
            if (CardActionTarget.looksLikePackage(link)) {
                try {
                    if (context.getPackageManager().getLaunchIntentForPackage(link) != null) {
                        launchApp(context, "", link, "");
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (link.startsWith("http://") || link.startsWith("https://")) {
                Intent intent = new Intent(context, com.oilquiz.app.WebViewActivity.class);
                intent.putExtra("url", link);
                if (!(context instanceof android.app.Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
                return;
            }
            // 自定义协议（weixin://、magnet:、intent://、tel:…）：卡片链接此前一路落到"按文件路径找"，
            // 报的是"文件不存在"——那是最误导人的一种失败。交同一套协议处置逻辑。
            if (com.oilquiz.app.browser.BrowserSchemeHandler.isSchemeNavigation(link)) {
                com.oilquiz.app.browser.BrowserSchemeHandler.handoff(context, link, false);
                return;
            }
            // file:// 或绝对路径：FileProvider + 系统 Intent（与 SimpleWebViewActivity 打开文件一致）
            String path = link;
            if (link.startsWith("file://")) {
                path = Uri.parse(link).getPath();
            }
            java.io.File f = new java.io.File(path);
            if (!f.exists() || !f.isFile()) {
                // 兜底：路径不存在时按文件名在工作区搜索（模型传 file_list 常给相对/缩写路径）
                try {
                    java.io.File resolved = com.oilquiz.app.ai.agent.online.AgentWorkspace
                            .getInstance(context).resolveExistingFile(path);
                    if (resolved != null && resolved.isFile()) f = resolved;
                } catch (Throwable ignored) {
                }
            }
            if (!f.exists() || !f.isFile()) {
                // 目录暂不支持打开（之前同样提示）
                if (f.exists() && f.isDirectory()) {
                    Toast.makeText(context, "暂不支持打开文件夹: " + f.getName(), Toast.LENGTH_SHORT).show();
                    return;
                }
                // 文件名常被模型缩写/丢字（如漏下划线、截断中文）——模糊搜索工作区相似文件提示，
                // 比"文件不存在"更有操作性
                try {
                    java.util.List<java.io.File> similar = com.oilquiz.app.ai.agent.online.AgentWorkspace
                            .getInstance(context).searchSimilarFiles(path);
                    if (!similar.isEmpty()) {
                        StringBuilder sb = new StringBuilder("未找到 ").append(path)
                                .append("，工作区有相似文件:\n");
                        for (java.io.File sf : similar) {
                            sb.append("· ").append(sf.getName()).append("\n");
                        }
                        Toast.makeText(context, sb.toString().trim(), Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (Throwable ignored) {
                }
                Toast.makeText(context, "文件不存在: " + path, Toast.LENGTH_SHORT).show();
                return;
            }
            // ✅ 优先应用内渲染（FileRenderActivity：TBS/Pdfium/LibreOffice/文本/图片/视频引擎，
            // 不支持的格式有错误页 + "用其他应用打开"兜底），与 FileCardView 打开行为对齐
            try {
                Intent preview = new Intent(context, com.oilquiz.app.ui.activity.FileRenderActivity.class);
                preview.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH,
                        f.getAbsolutePath());
                if (!(context instanceof android.app.Activity)) {
                    preview.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(preview);
                return;
            } catch (Throwable t) {
                android.util.Log.w("ComponentActions", "应用内预览失败，退回系统打开: " + t.getMessage());
            }
            // 退回：FileProvider + 系统 Intent（与 SimpleWebViewActivity 打开文件一致）
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "com.oilquiz.app.fileprovider", f);
            String ext = "";
            int dot = f.getName().lastIndexOf('.');
            if (dot > 0) ext = f.getName().substring(dot + 1).toLowerCase();
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("组件内容", text));
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
