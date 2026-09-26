package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 绘图工具（Pillow 实现，无 matplotlib 依赖）：
 * 柱状图 / 折线图 / 饼图 / 散点图 → PNG 图片文件。
 *
 * 数据格式（data 参数，JSON）：
 * - bar / line：{"labels":["A","B"],"values":[1,2]} 或多系列
 *   {"labels":["A","B"],"series":[{"name":"系列1","values":[1,2]},{"name":"系列2","values":[3,4]}]}
 * - pie：{"labels":["A","B"],"values":[1,2]}
 * - scatter：{"points":[[1,2],[3,4]]}
 *
 * 图片默认保存到 Agent 工作区长期文件区（可用 workspace 工具查看），
 * 也可指定 output_path。中文字体自动探测系统字体。
 */
@Tool(
    value = "python_chart",
    description = "Python绘图工具(Pillow/matplotlib)：数据可视化生成PNG图片。bar=柱状图/line=折线图/pie=饼图/scatter=散点图，传data(JSON)+title即可，输出图片文件。与image_gen(AI生图)不同，本工具画数据图表。环境已装matplotlib/Pillow/numpy/pandas；需要子图/对数轴/热力图等复杂图表时，改用python_execute直接编写matplotlib代码",
    category = "python",
    aliases = {"绘图", "图表", "画图", "chart", "数据可视化", "py_chart"},
    actions = {
        @Action(name = "bar", description = "柱状图"),
        @Action(name = "line", description = "折线图"),
        @Action(name = "pie", description = "饼图"),
        @Action(name = "scatter", description = "散点图")
    },
    params = {
        @Param(name = "action", type = "string", description = "图表类型: bar/line/pie/scatter", required = true),
        @Param(name = "data", type = "object", description = "数据JSON(必填)", required = true),
        @Param(name = "title", type = "string", description = "图表标题(可选)", required = false),
        @Param(name = "width", type = "integer", description = "图片宽度(默认800)", required = false),
        @Param(name = "height", type = "integer", description = "图片高度(默认500)", required = false),
        @Param(name = "output_path", type = "string", description = "保存路径(默认工作区files/xxx.png)；支持相对路径，如 files/chart.png 或 chart.png，会自动解析到工作区", required = false),
        @Param(name = "colors", type = "array", description = "系列颜色数组(可选，默认内置色板)", required = false),
        @Param(name = "show_values", type = "boolean", description = "是否显示数值(默认true)", required = false)
    }
)
public class PythonChartTool extends BaseAITool {
    private static final String TAG = "PythonChartTool";
    private final Context context;
    private final PythonToolManager toolManager;

    public PythonChartTool(Context context) {
        super("python_chart", "Python绘图工具(Pillow/matplotlib)：数据可视化生成PNG图片。环境已装matplotlib/Pillow/numpy/pandas；需要子图/对数轴/热力图等复杂图表时，改用python_execute直接编写matplotlib代码");
        this.context = context.getApplicationContext();
        this.toolManager = PythonToolManager.getInstance(context);
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    return AIToolResult.fail("Python 工具初始化失败，请检查 Chaquopy 配置");
                }
            }
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "bar";
            String data = resolveDataParam(parameters);
            if (data == null) {
                return AIToolResult.fail("缺少参数: data（图表数据JSON）");
            }
            String code = buildChartCode(action, data, parameters);
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, null, 60);
            return formatResult(result, action);
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage(), e);
            return AIToolResult.fail("Python绘图失败: " + errText(e));
        }
    }

    /** data 参数：JSON 对象 / JSON 字符串 / Map 都归一为 JSON 字符串 */
    private String resolveDataParam(Map<String, Object> parameters) {
        Object v = parameters.get("data");
        if (v == null) return null;
        try {
            if (v instanceof Map) {
                org.json.JSONObject obj = new org.json.JSONObject();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    obj.put(String.valueOf(e.getKey()), e.getValue());
                }
                return obj.toString();
            }
            if (v instanceof org.json.JSONObject) {
                return ((org.json.JSONObject) v).toString();
            }
            return String.valueOf(v).trim();
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private String buildChartCode(String action, String data, Map<String, Object> parameters) {
        String title = strParam(parameters, "title", "");
        int width = intParam(parameters, "width", 800);
        int height = intParam(parameters, "height", 500);
        String outputPath = strParam(parameters, "output_path", null);
        String colors = arrayParam(parameters, "colors");
        boolean showValues = boolParam(parameters, "show_values", true);

        // 默认输出：工作区 files/agent_chart_时间戳.png
        String wsFiles = new java.io.File(context.getFilesDir(),
                "agent_workspace" + java.io.File.separator + "files").getAbsolutePath();
        if (outputPath == null || outputPath.isEmpty()) {
            outputPath = wsFiles + java.io.File.separator + "agent_chart_" + System.currentTimeMillis() + ".png";
        } else if (!outputPath.startsWith("/")) {
            // 相对路径统一解析到工作区 files/：Android 进程 cwd 是只读的，
            // 传 "files/x.png" 或 "x.png" 直接 save 会报 [Errno 30] Read-only file system: 'files'
            String rel = outputPath.replace('\\', '/');
            if (rel.startsWith("./")) rel = rel.substring(2);
            if (rel.startsWith("files/")) rel = rel.substring("files/".length());
            outputPath = new java.io.File(wsFiles, rel).getAbsolutePath();
        }

        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import os, json, math\n" +
            "try:\n" +
            "    from PIL import Image, ImageDraw, ImageFont\n" +
            "except Exception as e:\n" +
            "    print('Pillow不可用: ' + str(e))\n" +
            "    raise SystemExit\n" +
            "\n" +
            "action = %s\n" +
            "data = json.loads(%s)\n" +
            "title = %s\n" +
            "W, H = %d, %d\n" +
            "out_path = %s\n" +
            "colors = %s\n" +
            "show_values = %s\n" +
            "\n" +
            "if not colors:\n" +
            "    colors = ['#4E79A7', '#F28E2B', '#59A14F', '#E15759', '#B07AA1', '#76B7B2', '#EDC948', '#FF9DA7']\n" +
            "\n" +
            "def load_font(size):\n" +
            "    for p in ['/system/fonts/DroidSansFallback.ttf', '/system/fonts/NotoSansCJK-Regular.ttc',\n" +
            "              '/system/fonts/NotoSansSC-Regular.otf', '/system/fonts/Roboto-Regular.ttf',\n" +
            "              '/system/fonts/DroidSans.ttf']:\n" +
            "        try:\n" +
            "            return ImageFont.truetype(p, size)\n" +
            "        except Exception:\n" +
            "            continue\n" +
            "    return ImageFont.load_default()\n" +
            "\n" +
            "def truncate(s, n):\n" +
            "    s = str(s)\n" +
            "    return s if len(s) <= n else s[:n-1] + '…'\n" +
            "\n" +
            "def hex_to_rgb(h):\n" +
            "    h = h.lstrip('#')\n" +
            "    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))\n" +
            "\n" +
            "img = Image.new('RGB', (W, H), '#FFFFFF')\n" +
            "draw = ImageDraw.Draw(img)\n" +
            "f_title = load_font(max(18, min(28, W // 30)))\n" +
            "f_axis = load_font(max(11, min(16, W // 55)))\n" +
            "f_label = load_font(max(10, min(14, W // 60)))\n" +
            "\n" +
            "def draw_axis(pad_l, pad_r, pad_t, pad_b, y_min, y_max):\n" +
            "    plot_w = W - pad_l - pad_r\n" +
            "    plot_h = H - pad_t - pad_b\n" +
            "    # 网格线\n" +
            "    for i in range(6):\n" +
            "        y = pad_t + plot_h - plot_h * i / 5\n" +
            "        draw.line([(pad_l, y), (W - pad_r, y)], fill='#E0E0E0', width=1)\n" +
            "        val = y_min + (y_max - y_min) * i / 5\n" +
            "        draw.text((pad_l - 8, y - 8), ('%%g' %% val), fill='#666666', font=f_label, anchor='rm')\n" +
            "    draw.line([(pad_l, pad_t), (pad_l, H - pad_b)], fill='#333333', width=1)\n" +
            "    draw.line([(pad_l, H - pad_b), (W - pad_r, H - pad_b)], fill='#333333', width=1)\n" +
            "    return plot_w, plot_h\n" +
            "\n" +
            "def legend(names, pad_l, pad_t):\n" +
            "    y = pad_t + 6\n" +
            "    x = pad_l\n" +
            "    for i, nm in enumerate(names):\n" +
            "        c = colors[i %% len(colors)]\n" +
            "        draw.rectangle([x, y, x + 14, y + 14], fill=c)\n" +
            "        draw.text((x + 18, y - 2), truncate(nm, 20), fill='#333333', font=f_axis)\n" +
            "        w = draw.textlength(truncate(nm, 20), font=f_axis)\n" +
            "        x += 18 + w + 24\n" +
            "        if x > W - 40:\n" +
            "            x = pad_l\n" +
            "            y += 20\n" +
            "    return y + 18\n" +
            "\n" +
            "def norm_values(vals):\n" +
            "    f = [float(v) if v is not None else 0.0 for v in vals]\n" +
            "    lo = min(f) if f else 0\n" +
            "    hi = max(f) if f else 1\n" +
            "    if hi == lo:\n" +
            "        hi = lo + 1\n" +
            "    return f, lo, hi\n" +
            "\n" +
            "ok = True\n" +
            "try:\n" +
            "    labels = [truncate(x, 12) for x in data.get('labels', [])]\n" +
            "    series = data.get('series')\n" +
            "    if series is None:\n" +
            "        series = [{'name': '数据', 'values': data.get('values', [])}]\n" +
            "    s_names = [s.get('name', '系列' + str(i+1)) for i, s in enumerate(series)]\n" +
            "    all_vals = [float(v) for s in series for v in s.get('values', [])]\n" +
            "    if not all_vals and action == 'scatter':\n" +
            "        # 散点图数据在 points 里，用 y 值计算范围\n" +
            "        all_vals = [float(p[1]) for p in data.get('points', []) if len(p) > 1]\n" +
            "    if not all_vals:\n" +
            "        print('数据为空')\n" +
            "        raise SystemExit\n" +
            "    _, lo, hi = norm_values(all_vals)\n" +
            "\n" +
            "    if action == 'pie':\n" +
            "        values = [float(v) for v in data.get('values', [])]\n" +
            "        if not values or sum(values) <= 0:\n" +
            "            print('饼图数据无效')\n" +
            "            raise SystemExit\n" +
            "        total = sum(values)\n" +
            "        cx, cy, r = W // 2, H // 2 + 10, min(W, H) // 2 - 50\n" +
            "        start = -90.0\n" +
            "        p_labels = [truncate(x, 15) for x in data.get('labels', [])]\n" +
            "        for i, v in enumerate(values):\n" +
            "            ang = 360.0 * v / total\n" +
            "            c = hex_to_rgb(colors[i %% len(colors)])\n" +
            "            draw.pieslice([cx - r, cy - r, cx + r, cy + r], start, start + ang, fill=c, outline='white', width=2)\n" +
            "            mid = (start + ang / 2) * 3.1415926 / 180\n" +
            "            lx = cx + r * 0.62 * math.cos(mid)\n" +
            "            ly = cy + r * 0.62 * math.sin(mid)\n" +
            "            pct = v * 100.0 / total\n" +
            "            if show_values:\n" +
            "                draw.text((lx - 10, ly - 8), ('%%g' %% pct) + '%%', fill='white', font=f_label)\n" +
            "            start += ang\n" +
            "        # 图例\n" +
            "        ly2 = H - 34\n" +
            "        lx2 = 20\n" +
            "        for i, lb in enumerate(p_labels):\n" +
            "            c = colors[i %% len(colors)]\n" +
            "            draw.rectangle([lx2, ly2, lx2 + 12, ly2 + 12], fill=c)\n" +
            "            draw.text((lx2 + 16, ly2 - 2), lb + ('  %%.1f%%%%' %% (values[i] * 100.0 / total)), fill='#333333', font=f_label)\n" +
            "            w = draw.textlength(lb + '  100.0%%', font=f_label)\n" +
            "            lx2 += 16 + w + 18\n" +
            "\n" +
            "    else:\n" +
            "        pad_l, pad_r, pad_t, pad_b = 70, 24, 70, 70\n" +
            "        plot_w, plot_h = draw_axis(pad_l, pad_r, pad_t, pad_b, lo, hi)\n" +
            "        if len(series) > 1:\n" +
            "            pad_t2 = legend(s_names, pad_l, pad_t - 60)\n" +
            "        n = max(1, len(labels))\n" +
            "        slot = plot_w / n\n" +
            "        if action == 'bar':\n" +
            "            group = len(series)\n" +
            "            bw = min(slot * 0.7 / group, 80)\n" +
            "            for si, s in enumerate(series):\n" +
            "                vals, _, _ = norm_values(s.get('values', []))\n" +
            "                c = colors[si %% len(colors)]\n" +
            "                for i in range(len(vals)):\n" +
            "                    if i >= n:\n" +
            "                        break\n" +
            "                    x0 = pad_l + i * slot + slot / 2 - group * bw / 2 + si * bw\n" +
            "                    hgt = plot_h * (vals[i] - lo) / (hi - lo)\n" +
            "                    draw.rectangle([x0, H - pad_b - hgt, x0 + bw - 2, H - pad_b], fill=c)\n" +
            "                    if show_values:\n" +
            "                        draw.text((x0 + bw / 2 - 8, H - pad_b - hgt - 16), ('%%g' %% vals[i]), fill='#333333', font=f_label)\n" +
            "                # x 轴标签（中间系列画）\n" +
            "                if si == group // 2:\n" +
            "                    for i in range(len(labels)):\n" +
            "                        if i >= n:\n" +
            "                            break\n" +
            "                        draw.text((pad_l + i * slot + slot / 2, H - pad_b + 8), labels[i], fill='#333333', font=f_axis, anchor='ma')\n" +
            "        elif action == 'scatter':\n" +
            "            pts = data.get('points', [])\n" +
            "            xs = [float(p[0]) for p in pts if len(p) > 1]\n" +
            "            ys = [float(p[1]) for p in pts if len(p) > 1]\n" +
            "            if xs:\n" +
            "                x_lo, x_hi = min(xs), max(xs)\n" +
            "                if x_hi == x_lo:\n" +
            "                    x_hi += 1\n" +
            "                for p in pts:\n" +
            "                    px = pad_l + plot_w * (float(p[0]) - x_lo) / (x_hi - x_lo)\n" +
            "                    py = H - pad_b - plot_h * (float(p[1]) - lo) / (hi - lo)\n" +
            "                    draw.ellipse([px - 4, py - 4, px + 4, py + 4], fill=colors[0])\n" +
            "        else:  # line\n" +
            "            for si, s in enumerate(series):\n" +
            "                vals, _, _ = norm_values(s.get('values', []))\n" +
            "                c = colors[si %% len(colors)]\n" +
            "                pts = []\n" +
            "                for i in range(len(vals)):\n" +
            "                    if i >= n:\n" +
            "                        break\n" +
            "                    x = pad_l + i * slot + slot / 2\n" +
            "                    y = H - pad_b - plot_h * (vals[i] - lo) / (hi - lo)\n" +
            "                    pts.append((x, y))\n" +
            "                if len(pts) > 1:\n" +
            "                    draw.line(pts, fill=c, width=3)\n" +
            "                for (x, y) in pts:\n" +
            "                    draw.ellipse([x - 4, y - 4, x + 4, y + 4], fill=c)\n" +
            "                if show_values:\n" +
            "                    for i, (x, y) in enumerate(pts):\n" +
            "                        if i < len(vals):\n" +
            "                            draw.text((x - 12, y - 18), ('%%g' %% vals[i]), fill='#333333', font=f_label)\n" +
            "            for i in range(len(labels)):\n" +
            "                draw.text((pad_l + i * slot + slot / 2, H - pad_b + 8), labels[i], fill='#333333', font=f_axis, anchor='ma')\n" +
            "except SystemExit:\n" +
            "    raise\n" +
            "except Exception as e:\n" +
            "    print('绘图失败: ' + str(e))\n" +
            "    ok = False\n" +
            "\n" +
            "if ok:\n" +
            "    try:\n" +
            "        d = os.path.dirname(out_path)\n" +
            "        if d and not os.path.exists(d):\n" +
            "            os.makedirs(d, exist_ok=True)\n" +
            "        img.save(out_path, 'PNG')\n" +
            "        print('图片已保存: ' + out_path)\n" +
            "        print('尺寸: ' + str(W) + 'x' + str(H))\n" +
            "        print('类型: ' + action)\n" +
            "    except Exception as e:\n" +
            "        print('保存失败: ' + str(e))\n",
            quoteString(action), quoteString(data), quoteString(title),
            width, height, quoteString(outputPath), colors, showValues ? "True" : "False"
        );
    }

    private AIToolResult formatResult(PythonToolManager.ExecutionResult result, String action) {
        Map<String, Object> info = new HashMap<>();
        info.put("action", action);
        info.put("attempts", result.attempts);
        if (result.success) {
            String output = result.stdout != null && !result.stdout.isEmpty()
                    ? result.stdout : (result.result != null ? result.result : "");
            info.put("stdout", output);
            return new AIToolResult(output, info, true);
        }
        String error = result.error != null ? result.error
                : (result.stderr != null && !result.stderr.isEmpty() ? result.stderr : "未知错误");
        info.put("error", error);
        return new AIToolResult("Python绘图失败: " + error, info, false);
    }

    // ===== 参数工具 =====

    private String strParam(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean boolParam(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v != null) return Boolean.parseBoolean(String.valueOf(v).trim());
        return def;
    }

    /** 颜色数组 → Python 列表字面量 */
    private String arrayParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) return "None";
        try {
            if (v instanceof org.json.JSONArray) {
                return ((org.json.JSONArray) v).toString();
            }
            if (v instanceof List) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (Object o : (List<?>) v) {
                    arr.put(String.valueOf(o));
                }
                return arr.toString();
            }
            String s = String.valueOf(v).trim();
            if (s.startsWith("[")) {
                new org.json.JSONArray(s);
                return s;
            }
        } catch (Exception ignored) {
        }
        return "None";
    }

    private String quoteString(String s) {
        if (s == null) return "None";
        return "\"" + s.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t") + "\"";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "图表类型: bar(柱状图)/line(折线图)/pie(饼图)/scatter(散点图)");
        params.put("data", "数据JSON(必填)。bar/line: {\"labels\":[\"A\",\"B\"],\"values\":[1,2]}或多系列{\"labels\":[...],\"series\":[{\"name\":\"系列1\",\"values\":[...]}]}；pie: {\"labels\":[...],\"values\":[...]}；scatter: {\"points\":[[1,2],[3,4]]}");
        params.put("title", "图表标题(可选)");
        params.put("width", "图片宽度(默认800)");
        params.put("height", "图片高度(默认500)");
        params.put("output_path", "保存路径(默认工作区files/xxx.png)；相对路径自动解析到工作区(files/chart.png 或 chart.png)");
        params.put("colors", "系列颜色数组(可选，默认内置色板)");
        params.put("show_values", "是否显示数值(默认true)");
        return params;
    }
}
