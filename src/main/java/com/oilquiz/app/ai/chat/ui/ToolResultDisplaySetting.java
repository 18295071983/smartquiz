package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.oilquiz.app.ai.chat.component.ComponentDisplay;

/**
 * 「工具结果显示」设置项（2026-10-20）：把 {@link ComponentDisplay} 的默认档
 * （{@code ai_chat_prefs.tool_result_display_default}）露到聊天页工具抽屉里。
 *
 * <p>为什么单独成类：文案、档位顺序、写 pref、弹单选框这些是独立的一块，
 * {@code AIChatActivity} 只需要"按钮文字 + 点击调用"两行薄接线——
 * 与 {@code ChatUiV2Chrome}/{@code ChatV2ActionSheet}/{@code DraggableFloatingBall}
 * 一个路子，不再往那个 God Object 里堆内联 UI 代码。
 *
 * <p>档位口径只在 {@link ComponentDisplay} 一处定义，这里绝不复制一份枚举，
 * 避免设置页与渲染端各说各话。
 */
public final class ToolResultDisplaySetting {

    /** 抽屉按钮前缀图标：沿用同一行既有按钮的写法（"🔇 语音"这类 emoji + 短标签） */
    private static final String BUTTON_ICON = "🖼️";

    private ToolResultDisplaySetting() {
    }

    /** 弹窗里的档位顺序：从"信息最全"到"最克制" */
    public static ComponentDisplay.Mode[] choices() {
        return new ComponentDisplay.Mode[]{
                ComponentDisplay.Mode.INLINE,
                ComponentDisplay.Mode.PEEK,
                ComponentDisplay.Mode.HIDDEN};
    }

    /** 档位名（设置页与 Toast 共用，别在别处再写一遍中文字面量） */
    public static String label(ComponentDisplay.Mode mode) {
        switch (mode) {
            case INLINE: return "整卡展开";
            case HIDDEN: return "不显示卡片";
            case PEEK:
            default: return "摘要一行";
        }
    }

    /** 档位说明：把代价/后果讲清，尤其 hidden 会连模型主动创建的产物卡一起收起 */
    public static String description(ComponentDisplay.Mode mode) {
        switch (mode) {
            case INLINE:
                return "每次工具结果都整卡摊开（2026-10-20 之前的老样子）：长表格、图集会把正文顶出屏幕";
            case HIDDEN:
                return "产物卡都不挂载，只留工具过程与结果文字；交互类（按钮/表单/进度）与正文类仍强制显示";
            case PEEK:
            default:
                return "卡片收成一行摘要（标题+关键值），点开才是完整卡；交互类与正文类不受影响";
        }
    }

    /** 当前默认档 */
    public static ComponentDisplay.Mode current(Context context) {
        return ComponentDisplay.defaultTier(context);
    }

    /** 抽屉按钮文字："🖼️ 摘要一行" */
    public static String buttonLabel(Context context) {
        return BUTTON_ICON + " " + label(current(context));
    }

    /**
     * 弹单选框选档位；写完 pref 后回调 {@code onPicked}（调用方负责刷新按钮文字与列表重绑）。
     *
     * @return 是否发生了变化（false = 用户选了当前档或什么都没做）
     */
    public static boolean showPicker(final Activity activity, final Runnable onPicked) {
        if (activity == null) return false;
        final ComponentDisplay.Mode[] modes = choices();
        final ComponentDisplay.Mode now = current(activity);
        String[] labels = new String[modes.length];
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            labels[i] = label(modes[i]) + "（" + ComponentDisplay.name(modes[i]) + "）\n"
                    + description(modes[i]);
            if (modes[i] == now) checked = i;
        }
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("工具结果显示")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    ComponentDisplay.Mode target = modes[which];
                    boolean changed = target != now;
                    ComponentDisplay.setDefaultTier(activity, ComponentDisplay.name(target));
                    if (changed && onPicked != null) onPicked.run();
                    Toast.makeText(activity, "工具结果：" + label(target), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }
}
