package com.oilquiz.app.ai.tool;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 控件参数查询工具（control_lookup）：按关键词检索低频控件的详细参数说明。
 *
 * 系统提示词只保留高频控件的完整清单，低频控件（视频/音频/图表/二维码/日期等）
 * 降为关键词索引。Agent 需要构建含低频控件的 UI 时，用本工具按关键词查该控件
 * 的准确参数（type + 字段），避免凭记忆猜字段导致渲染失败。
 *
 * 用法：
 *  - action=search, keyword=控件名或功能（如 video / 二维码 / 图表 / stepper）→ 返回匹配控件及参数
 *  - action=list → 列出全部低频控件（轻量索引）
 *
 * 内置低频控件词库（区别于内置 UI 组件库 ComponentRegistry 的卡片类型）。
 */
public class ControlLookupTool implements AITool {

    private static final String TAG = "ControlLookupTool";
    private final Context context;

    public ControlLookupTool() {
        this.context = null;
    }

    public ControlLookupTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "control_lookup";
    }

    @Override
    public String getDescription() {
        return "控件参数查询：按关键词检索低频 UI 控件的详细参数说明（视频/音频/图表/二维码/日期等）。"
                + "动作:search(按关键词查控件参数)/list(列出全部低频控件)。"
                + "当要构建含低频控件的 layout 但不确定字段时调用，避免凭记忆猜字段。"
                + "高频常用控件（text/button/input/select/table 等）已在系统提示词列出，无需查询。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: search(按关键词查)/list(列出全部低频控件)");
        params.put("keyword", "搜索关键词（search 用）：控件名或功能，如 video/二维码/图表/stepper/marquee");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "list";
            if ("search".equals(action)) {
                String keyword = parameters.get("keyword") != null
                        ? String.valueOf(parameters.get("keyword")).trim().toLowerCase(Locale.ROOT) : "";
                if (keyword.isEmpty()) {
                    return AIToolResult.fail("缺少参数: keyword（要查询的控件名/功能关键词）");
                }
                return search(keyword);
            }
            if ("list".equals(action)) {
                return list();
            }
            return AIToolResult.fail("未知操作: " + action + "（支持 search/list）");
        } catch (Throwable t) {
            return AIToolResult.fail("控件查询失败: " + t.getMessage());
        }
    }

    /** 全部低频控件词库：type → {name, 参数说明}。仅收录系统提示词未详列的控件。 */
    private static final String[][] CONTROLS = {
        {"video", "视频播放", "type=video, url或src=视频地址, title=标题, autoPlay=自动播, loop=循环, speed=倍速, poster=封面;ExoPlayer原生播放"},
        {"audio", "音频播放", "type=audio, url或src=音频地址, title=标题, artist=艺术家, autoplay=自动播"},
        {"html", "富文本", "type=html, html=HTML内容, title=标题, maxHeight=最大高度dp"},
        {"marquee", "跑马灯", "type=marquee, text=滚动文字, speed=0~3(0停/1慢/2中/3快), color, size, repeat=循环次数"},
        {"image", "图片", "type=image, url=图片地址, width, height, radius=圆角dp"},
        {"badge", "徽章", "type=badge, text=文字, color=背景色, size"},
        {"avatar", "头像", "type=avatar, url=头像地址, size=尺寸dp"},
        {"avatar_group", "头像组", "type=avatar_group, urls=[头像地址], size=尺寸dp, overlap=重叠度"},
        {"quote", "引用", "type=quote, text=引用内容, author=作者"},
        {"code", "代码块", "type=code, code=代码, language=语言名, title=标题"},
        {"icon", "图标", "type=icon, size=尺寸, color=颜色"},
        {"line_chart", "折线图", "type=line_chart, categories=[x轴], series=[{name,data:[数值]}], title"},
        {"bar_chart", "柱状图", "type=bar_chart, categories=[x轴], series=[{name,data:[数值]}], title"},
        {"pie_chart", "饼图", "type=pie_chart, data=[{label,value}], title"},
        {"sparkline", "迷你趋势", "type=sparkline, data=[数值]"},
        {"qrcode", "二维码", "type=qrcode, content=内容, size=尺寸"},
        {"barcode", "条形码", "type=barcode, content=内容"},
        {"countdown", "倒计时", "type=countdown, seconds=秒数, label=标签"},
        {"calendar", "日历", "type=calendar, value=yyyy-MM-dd, key"},
        {"breadcrumb", "面包屑", "type=breadcrumb, items=[{label,action}]"},
        {"progress_ring", "环形进度", "type=progress_ring, progress=当前, max=总数"},
        {"stepper", "步进器", "type=stepper, label=标签, min=最小, max=最大, step=步长, key"},
        {"slider_range", "双滑块", "type=slider_range, min, max, low=低值, high=高值, key"},
        {"dropdown", "下拉菜单", "type=dropdown, options=[选项], key, value=默认选中"},
        {"stack", "层叠", "type=stack, children=[子项], 子项用 gravity(如bottom|center|start)定位"},
        {"accordion", "折叠面板", "type=accordion, items=[{title,content}]"},
        {"carousel", "轮播", "type=carousel, images=[图url], autoplay, interval"},
        {"file", "文件选择", "type=file, key, label, value=预填路径"},
        {"otp", "验证码", "type=otp, length=位数(默认6), key"},
        {"search_bar", "搜索条", "type=search_bar, hint, key"},
        {"datetime", "日期时间", "type=datetime, key, value=yyyy-MM-dd HH:mm"},
        {"toggle", "胶囊开关", "type=toggle, options=[选项], value=当前"},
        {"steps", "步骤条", "type=steps, steps=[{title,status:done|current|todo,description}]"},
        {"timeline", "时间线", "type=timeline, items=[{title,time,description}]"},
    };

    private AIToolResult list() {
        JSONObject result = new JSONObject();
        List<JSONObject> arr = new ArrayList<>();
        for (String[] c : CONTROLS) {
            try {
                JSONObject o = new JSONObject();
                o.put("type", c[0]);
                o.put("name", c[1]);
                arr.add(o);
            } catch (Exception ignored) {
            }
        }
        try {
            result.put("count", arr.size());
            result.put("controls", arr.toString());
        } catch (Exception ignored) {
        }
        return AIToolResult.success(result);
    }

    private AIToolResult search(String keyword) {
        List<JSONObject> hits = new ArrayList<>();
        for (String[] c : CONTROLS) {
            if (c[0].contains(keyword) || c[1].toLowerCase(Locale.ROOT).contains(keyword)
                    || keyword.contains(c[0])) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", c[0]);
                    o.put("name", c[1]);
                    o.put("params", c[2]);
                    hits.add(o);
                } catch (Exception ignored) {
                }
            }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("count", hits.size());
            result.put("controls", hits.toString());
            if (hits.isEmpty()) {
                result.put("hint", "未找到匹配控件。常用控件如 text/button/input/select/table 已在系统提示词列出；"
                        + "或用 control_lookup(action=list) 看全部可查控件");
            }
        } catch (Exception ignored) {
        }
        return AIToolResult.success(result);
    }
}
