package com.oilquiz.app.ai.chat.component;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * 卡片"放大档"宿主：把气泡里那张卡换成一个全屏窗口再渲一遍。
 *
 * <p>动机（2026-10-24 真机）：html 卡里的 {@code <input>} 在消息列表里是"借住"——列表会因工具卡
 * 重绑重建产物、条目会因内容量高改容器高度、聊天页的键盘监听还会把焦点抢回输入框，任何一处都能
 * 把正在打字的 WebView 打回起点。这些各自都修了，但只要卡片还长在 RecyclerView 里，这类打断就有
 * 可能再来一次。放大档是**结构性**的那一层：脱离列表，输入焦点/滚动/播放进度不再被列表节奏牵连。</p>
 *
 * <p>三条实现约束（都是这个仓库里踩过坑的地方）：</p>
 * <ol>
 *   <li><b>必须渲副本</b>：{@code ComponentData.props} 是活的 JSONObject，且整棵会随会话落盘
 *       （{@code ChatHistoryManager} 的 ComponentDataAdapter）。往原对象 put 任何宿主专用键，
 *       都会污染历史数据并被 registry 当 style/actions 误消费 → 一律 {@link #focusClone} 深拷。</li>
 *   <li><b>关闭必 release</b>：{@link ComponentLifecycle#release} 递归找到 {@code Releasable}
 *       （{@code HtmlWebView} 会 destroy 掉原生渲染进程）。{@code PythonToolManager} 那两处
 *       AlertDialog 就是渲完不释放，WebView 内存随开合线性堆——别照抄。</li>
 *   <li><b>只走应用内宿主</b>：绝不能图省事跳 {@code WebViewActivity} 或系统浏览器——
 *       那条路上没有 {@code Android.submit} 桥、也没有 componentId 绑定，
 *       用户点得动、字也打得进去，但结果永远回不到 Agent（回传链会静默断）。</li>
 * </ol>
 *
 * <p>回传链与宿主无关：{@code component_id} 从副本的 {@code props.actions} 里解析出来，
 * 与气泡那份是同一个 id，两边提交写的是引擎侧同一个结果槽（后写覆盖先写）。</p>
 */
public final class ComponentFocusHost {

    /**
     * 宿主专用键：告诉卡片"你在全屏窗口里，自己撑满、别再排量高"。
     * 只允许出现在 {@link #focusClone} 出来的副本上——原始 props 会落盘。
     */
    public static final String PROP_FILL_HEIGHT = "fillHeight";

    /** 防叠窗：同一时刻只留一个放大档（弱引用，不拦 Activity 销毁）。 */
    private static WeakReference<Dialog> sOpen;

    private ComponentFocusHost() {
    }

    /** 能不能放大：必须有前台 Activity 上下文（非 Activity 弹不出窗）。 */
    public static boolean canOpen(Context ctx) {
        return ctx instanceof Activity && !((Activity) ctx).isFinishing();
    }

    /**
     * 造一份只给放大档用的组件数据：props 深拷（原对象一个键都不动）、id/type/内部模板标记照搬，
     * 再打上 {@link #PROP_FILL_HEIGHT}。
     *
     * <p>{@code internalTemplate} 是 transient 但<b>必须带过去</b>：沙箱按它判定"这是我们自己拼的
     * 可信模板"（mermaid/katex 那类），丢了副本就掉到不可信档，脚本与桥全关——放大后图渲不出来。</p>
     */
    static ComponentData focusClone(ComponentData src) {
        if (src == null) {
            return null;
        }
        JSONObject copy;
        try {
            copy = src.props != null ? new JSONObject(src.props.toString()) : new JSONObject();
        } catch (Throwable t) {
            copy = new JSONObject();
        }
        try {
            copy.put(PROP_FILL_HEIGHT, true);
        } catch (Throwable ignored) {
        }
        ComponentData out = new ComponentData(src.type, copy);
        out.id = src.id;
        out.internalTemplate = src.internalTemplate;
        return out;
    }

    /**
     * 打开放大档。{@code data} 传气泡里那份即可，内部会自行克隆。
     *
     * @return 成功弹出返回 true；拿不到 Activity、或组件渲染失败返回 false（调用方可降级）
     */
    public static boolean open(Context ctx, ComponentData data) {
        if (!canOpen(ctx) || data == null) {
            return false;
        }
        final Activity act = (Activity) ctx;
        View card;
        try {
            card = ComponentRegistry.getInstance().render(act, focusClone(data));
        } catch (Throwable t) {
            card = null;
        }
        if (card == null) {
            Toast.makeText(act, R.string.card_html_expand_unavailable, Toast.LENGTH_SHORT).show();
            return false;
        }
        final View focusCard = card;   // lambda 要引用，上面的 try/catch 让 card 不是"实际最终"

        dismissExisting();

        final Dialog dialog = new Dialog(act);
        try {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout column = new LinearLayout(act);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(buildCloseBar(act, dialog), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 卡片吃掉剩余高度：WebView 自己滚，不让外层再套一层滚动容器抢手势
            column.addView(focusCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            dialog.setContentView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dialog.setCancelable(true);
            dialog.setOnDismissListener(d -> {
                sOpen = null;
                // 关键一步：全屏那张卡带着 WebView，关窗不释放就是白漏一个原生渲染进程
                ComponentLifecycle.release(focusCard);
            });
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new ColorDrawable(themeBackground(act)));
                // 键盘弹起时压缩窗口而不是盖住输入框——放大档的主要用途就是打字
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            dialog.show();
            sOpen = new WeakReference<>(dialog);
        } catch (Throwable t) {
            sOpen = null;
            ComponentLifecycle.release(card);
            return false;
        }
        return true;
    }

    /** 顶部一条"关闭"栏（返回键也能关，这里给一个看得见的出口）。 */
    private static View buildCloseBar(Activity act, Dialog dialog) {
        TextView close = new TextView(act);
        close.setText(R.string.card_html_expand_close);
        close.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        close.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10));
        close.setTextSize(14f);
        close.setOnClickListener(v -> {
            // 走 dismiss：清理统一在 onDismiss 里，别在这里再写一份
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        });
        return close;
    }

    /** 背景色按主题取（项目口径：不新增硬编码色），夜间模式下自动跟着换。 */
    private static int themeBackground(Context ctx) {
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)
                    && tv.resourceId != 0) {
                return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return 0xFFFFFFFF;
    }

    private static void dismissExisting() {
        try {
            Dialog prev = sOpen != null ? sOpen.get() : null;
            if (prev != null && prev.isShowing()) {
                prev.dismiss();   // 顺带 release 掉上一张卡的 WebView
            }
        } catch (Throwable ignored) {
        }
        sOpen = null;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
