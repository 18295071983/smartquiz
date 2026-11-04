package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态工具执行器：把模型写的执行逻辑按 DSL / JavaScript / Python 三种语言跑起来。
 *
 * <p>两个刻意的设计点：
 * <ul>
 *   <li><b>结果带成败标志。</b>旧版只回 {@code String}，引擎失败（JS 超时、Python 语法错、
 *       DSL 某行"语法错误: …"、{@code call_tool} 子调用失败）都变成一段普通文本，
 *       上层 {@link DynamicAITool} 无条件包成 {@code AIToolResult.success}。于是模型看到
 *       "Python 执行失败: …"会当成工具的<em>正常输出</em>去复述给用户。现在用
 *       {@link Outcome} 把成败还回去，文本仍然带（诊断信息模型要看）。</li>
 *   <li><b>本类不用 AILogger。</b>AILogger 的静态初始化链里有 {@code Handler} 字段，
 *       纯 JVM 单测下会炸；而 DSL 是可以在 JVM 里直接跑的行为。所以这里保留
 *       {@code android.util.Log}（单测 {@code returnDefaultValues=true} 时等价于无副作用），
 *       用户可见的成败一律通过 {@link Outcome} 交给 {@link DynamicAITool} 记审计日志。</li>
 * </ul>
 */
public class DynamicToolExecutor {
    private static final String TAG = "DynamicToolExecutor";

    /** 嵌套 call_tool 最大深度（防动态工具递归互调死循环） */
    private static final int MAX_CALL_DEPTH = 5;
    /** 当前线程的 call_tool 嵌套深度（ThreadLocal：并发调用互不干扰） */
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 嵌套 call_tool 的基线超时（毫秒）。这条路径以前<b>没有任何超时</b>，
     * 见 docs/tool-execution-parity-2026-10-23.md 第五节。
     */
    private static final int NESTED_BASE_TIMEOUT_MS = 30_000;
    /** 嵌套超时的上限：外层还有一道预算在管，嵌套只需要"够用但不无限" */
    private static final int NESTED_MAX_TIMEOUT_MS = 60_000;

    private final Context context;

    public DynamicToolExecutor(Context context) {
        this.context = context;
    }

    /**
     * 一次执行的结论。
     *
     * <p>{@code output} 在失败时也带：里面是"已经跑出来的部分"，
     * 模型需要看到前几行的结果才知道往哪儿改，不能只丢一句 error。
     */
    public static final class Outcome {
        public final boolean success;
        public final String output;
        public final String error;
        /** 实际使用的引擎：dsl / js / python / none */
        public final String engine;

        private Outcome(boolean success, String output, String error, String engine) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.engine = engine;
        }

        public static Outcome ok(String engine, String output) {
            return new Outcome(true, output, null, engine);
        }

        public static Outcome fail(String engine, String error, String partial) {
            return new Outcome(false, partial, error, engine);
        }
    }

    /**
     * 执行一段逻辑。
     *
     * <p>时限由 {@link DynamicToolBudget} 统一给：DSL 逐行自检剩余时间（能真停），
     * JS/Python 把剩余时间下发成引擎超时（到点由引擎自己收）。返回 {@link Outcome}
     * 而不是字符串，是为了让上层能把失败报成失败。
     */
    public Outcome run(String logic, Map<String, Object> parameters) {
        if (logic == null || logic.trim().isEmpty()) {
            return Outcome.fail("none", "未提供执行逻辑", null);
        }
        long startedAt = System.currentTimeMillis();

        // JavaScript 脚本支持：优先于 Python 判定（JS 的 return/赋值会命中 Python 特征，必须先识别）
        if (looksLikeJavaScript(logic)) {
            return executeAsJavaScript(logic, parameters, startedAt);
        }

        // Python 脚本支持：逻辑是 Python 脚本时交给 Python 执行引擎（脚本内用 script_args 取参数）
        if (looksLikePython(logic)) {
            return executeAsPython(logic, parameters, startedAt);
        }

        return executeDsl(logic, parameters, startedAt);
    }

    // ==================== DSL ====================

    /**
     * DSL 逐行执行。
     *
     * <p>行为变更（有意的）：某一行出错时<b>整次执行判为失败并停下</b>，而不是像旧版那样
     * 把 "语法错误: …" / "工具不存在: …" / "安全限制: …" 当成这一行的<em>输出</em>继续往下跑。
     * 理由：这类行是脚本自身写错了，后面的行通常还要用它的结果，继续跑只会产出一份
     * 混着错误文本的"成功输出"——那正是模型把报错念成答案的原因。已跑出的前几行
     * 仍然随 {@link Outcome#output} 返回，诊断信息不丢。
     */
    private Outcome executeDsl(String logic, Map<String, Object> parameters, long startedAt) {
        StringBuilder result = new StringBuilder();
        String[] lines = logic.split("\n");
        // 变量表是<b>每次调用</b>的局部状态：旧版挂在实例上，于是上一次调用 set 的值
        // 会在下一次调用里被 ${x} 替换出来（跨轮、跨会话残留），行为不可复现。
        Map<String, Object> variables = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (DynamicToolBudget.exhausted(startedAt)) {
                return Outcome.fail("dsl",
                        "动态工具超过 " + (DynamicToolBudget.SCRIPT_BUDGET_MS / 1000) + "s 未跑完，"
                                + "停在第 " + (i + 1) + " 行（共 " + lines.length + " 行）；"
                                + "请把这个工具拆小，或改成一次只做一件事",
                        result.length() > 0 ? result.toString() : null);
            }
            try {
                String lineResult = executeLine(line, parameters, variables,
                        DynamicToolBudget.remainingMs(startedAt));
                if (lineResult != null && !lineResult.isEmpty()) {
                    if (result.length() > 0) {
                        result.append("\n");
                    }
                    result.append(lineResult);
                }
            } catch (DslLineError e) {
                return Outcome.fail("dsl", "第 " + (i + 1) + " 行失败: " + e.getMessage(),
                        result.length() > 0 ? result.toString() : null);
            }
        }

        return Outcome.ok("dsl", result.length() > 0 ? result.toString() : "执行完成");
    }

    /** DSL 某一行出错（不再"当成输出继续跑"） */
    private static final class DslLineError extends Exception {
        DslLineError(String message) {
            super(message);
        }
    }

    // ==================== JavaScript 脚本支持 ====================

    /**
     * 判断执行逻辑是否为 JavaScript 脚本：
     * 1. 显式标记：```javascript / ```js / js: / // js / // javascript / 单独一行 javascript
     * 2. 强 JS 特征：箭头函数 =>、const/let/var/function/async 行首、console.、JSON.parse/stringify、
     *    document./window.、数组方法 .map(/.filter(/.forEach(/.reduce(
     * 必须在 Python 判定之前调用：JS 的 return / 变量赋值会命中 Python 特征，若后判会被误吞。
     * Python 的 async def 是 async 特征排除项；DSL 命令（echo/set/if 等）不命中任何 JS 特征。
     */
    private static boolean looksLikeJavaScript(String logic) {
        String[] lines = logic.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("```javascript") || lower.startsWith("```js")
                    || lower.equals("javascript") || lower.equals("js")
                    || lower.startsWith("// js") || lower.startsWith("// javascript")
                    || lower.startsWith("js:")) {
                return true;
            }
            if (line.startsWith("async ") && !line.startsWith("async def ")) return true;
            if (line.startsWith("const ") || line.startsWith("let ")
                    || line.startsWith("var ") || line.startsWith("function ")) return true;
            if (line.contains("=>") || line.startsWith("console.")) return true;
            if (line.contains("JSON.stringify(") || line.contains("JSON.parse(")
                    || line.contains("document.") || line.contains("window.")) return true;
            if (line.contains(".map(") || line.contains(".filter(")
                    || line.contains(".forEach(") || line.contains(".reduce(")) return true;
        }
        return false;
    }

    /**
     * 用 JsExecuteTool（WebView/V8 内核）运行脚本。参数以 script_args 对象注入，
     * 脚本内用 script_args.参数名 或 script_args['参数名'] 读取；console.log 输出与返回值合并返回。
     *
     * <p>超时改从 {@link DynamicToolBudget} 拿：旧版在这里硬编码 {@code timeout=10}，
     * 比引擎上限（30s）少 3 倍，把"脚本里要等异步"的情况一律误杀成超时；
     * 而且引擎的失败文本当时是当返回值往上交的，于是超时被报成成功。
     */
    private Outcome executeAsJavaScript(String logic, Map<String, Object> parameters, long startedAt) {
        try {
            String script = buildJsScript(logic, parameters);
            Map<String, Object> p = new HashMap<>();
            p.put("code", script);
            p.put("timeout", DynamicToolBudget.engineTimeoutSeconds(
                    DynamicToolBudget.remainingMs(startedAt), DynamicToolBudget.JS_ENGINE_MAX_SECONDS));
            AIToolResult r = new JsExecuteTool(context).execute(p);
            if (r == null) {
                return Outcome.fail("js", "JS 引擎无返回（结果桥接未回传）", null);
            }
            if (!r.isSuccess()) {
                String reason = r.getErrorMessage() != null ? r.getErrorMessage()
                        : (r.getResult() != null ? String.valueOf(r.getResult()) : null);
                return Outcome.fail("js", reason != null && !reason.trim().isEmpty()
                        ? reason.trim() : "JS 执行失败（引擎未给出原因）", null);
            }
            String out = r.getResult() != null ? r.getResult().toString().trim() : "";
            return Outcome.ok("js", out.isEmpty() ? "JS 执行完成，无返回值" : out);
        } catch (Throwable t) {
            Log.e(TAG, "JS 动态工具执行异常: " + t.getMessage(), t);
            return Outcome.fail("js", "JS 执行异常: " + t.getMessage(), null);
        }
    }

    /** 组装 JS：注入 script_args 参数对象 + 用户逻辑（JSON 字面量注入）。
     *  若脚本含顶层 return（JS 顶层 return 是语法错误 Illegal return statement），
     *  自动包进立即执行函数 IIFE，并**显式把 IIFE 返回值赋给 __js_ret 再作为最后表达式**——
     *  不依赖 eval/WebView 对多语句代码"最后一个表达式"完成值的隐式语义（部分实现不可靠）。 */
    private static String buildJsScript(String logic, Map<String, Object> parameters) {
        StringBuilder sb = new StringBuilder();
        // 参数注入与 ai_create_tool 那条路共用同一个权威（ScriptArgs），不再各写一份
        sb.append(ScriptArgs.jsPrelude(parameters));
        if (hasTopLevelReturn(logic)) {
            sb.append("const __js_ret = (() => {\n").append(logic).append("\n})();\n");
            sb.append("__js_ret;\n");
        } else {
            sb.append(logic);
            // 无 return 时 eval 取最后一个表达式的值：末尾追加一个引用已声明变量的空操作不可行，
            // 保持用户代码原样（用户可用末尾表达式或 console.log 输出结果）
        }
        return sb.toString();
    }

    /** 粗略检测脚本是否在顶层用了 return（行首 return 且不在函数体内）。
     *  启发式足够覆盖常见写法：脚本末尾 `return xxx;` / `return xxx`。 */
    private static boolean hasTopLevelReturn(String logic) {
        String[] lines = logic.split("\n");
        int braceDepth = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*")
                    || line.startsWith("*")) {
                continue;
            }
            braceDepth += countChar(line, '{');
            braceDepth -= countChar(line, '}');
            if (braceDepth == 0 && line.startsWith("return")) {
                return true;
            }
        }
        return false;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    // ==================== Python 脚本支持 ====================

    /**
     * 判断执行逻辑是否为 Python 脚本：
     * 1. 显式标记：首行 python / ```python / # python
     * 2. 语法特征：def/import/from/class 行首、# -*- coding、行首缩进的代码块、print( 等
     * DSL 命令（echo/set/if 等）不会被误判为 Python。
     */
    private static boolean looksLikePython(String logic) {
        String[] lines = logic.split("\n");
        boolean hasIndented = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("```python") || lower.startsWith("python:")
                    || lower.equals("python") || lower.startsWith("# python")
                    || lower.contains("coding:")) {
                return true;
            }
            if (line.startsWith("def ") || line.startsWith("import ")
                    || line.startsWith("from ") || line.startsWith("class ")) {
                return true;
            }
            // DSL 命令行 → 不是 Python 块
            if (isDslCommand(line)) {
                if (hasIndented) return true; // 前面已有缩进代码 → 整体 Python
                continue;
            }
            // 非命令行：有缩进 → Python 特征
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                hasIndented = true;
            }
            // print( / return 等 Python 调用特征（DSL 的 print 是裸 print 空格）
            if (line.contains("print(") || line.contains("return ")
                    || line.contains("==") || line.contains("lambda ")
                    || line.matches("[A-Za-z_][\\w]*\\s*=.*")) {
                if (!lower.startsWith("set ") && !lower.startsWith("if ")) {
                    hasIndented = true;
                }
            }
        }
        return hasIndented;
    }

    private static boolean isDslCommand(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("echo ") || lower.startsWith("print ")
                || lower.startsWith("log ") || lower.startsWith("set ")
                || lower.startsWith("if ") || lower.startsWith("concat ")
                || lower.startsWith("length ") || lower.startsWith("upper ")
                || lower.startsWith("lower ") || lower.startsWith("trim ")
                || lower.startsWith("replace ") || lower.startsWith("split ")
                || lower.startsWith("join ") || lower.startsWith("call_tool ");
    }

    /**
     * 用 Python 执行引擎运行脚本。参数以 script_args dict 注入，
     * 脚本可通过 script_args['参数名'] 读取；print 输出与返回值合并返回。
     *
     * <p>两处收口：失败必须是失败（引擎 {@code r.success==false} 时旧版把
     * "Python 执行失败: …" 当输出返回，上层无条件包成成功）；超时在这里显式下发
     * （以前吃引擎默认 30s，外层等待却是另一个数，两边对不上只能靠猜）。
     */
    private Outcome executeAsPython(String logic, Map<String, Object> parameters, long startedAt) {
        try {
            com.oilquiz.app.ai.python.PythonToolManager ptm =
                    com.oilquiz.app.ai.python.PythonToolManager.getInstance(context);
            if (!ptm.isInitialized()) {
                if (!ptm.initialize()) {
                    return Outcome.fail("python", "Python 环境初始化失败，无法执行 Python 脚本", null);
                }
            }
            String fullCode = ScriptArgs.fullPythonScript(logic, parameters);
            int timeoutSeconds = DynamicToolBudget.engineTimeoutSeconds(
                    DynamicToolBudget.remainingMs(startedAt), DynamicToolBudget.PYTHON_ENGINE_MAX_SECONDS);

            com.oilquiz.app.ai.python.PythonToolManager.ExecutionResult r =
                    ptm.executeCode(fullCode, null, timeoutSeconds);
            if (r == null) {
                return Outcome.fail("python", "Python 执行无返回结果", null);
            }
            if (r.success) {
                StringBuilder out = new StringBuilder();
                if (r.stdout != null && !r.stdout.trim().isEmpty()) {
                    out.append(r.stdout.trim());
                }
                if (r.result != null && !r.result.trim().isEmpty()) {
                    if (out.length() > 0) out.append("\n");
                    out.append(r.result.trim());
                }
                return Outcome.ok("python", out.length() > 0 ? out.toString() : "执行完成");
            }
            StringBuilder err = new StringBuilder("Python 执行失败");
            if (r.error != null && !r.error.isEmpty()) err.append(": ").append(r.error);
            if (r.stderr != null && !r.stderr.trim().isEmpty()) {
                err.append("\n").append(r.stderr.trim());
            }
            return Outcome.fail("python", err.toString(), null);
        } catch (Throwable t) {
            Log.e(TAG, "Python 动态工具执行异常: " + t.getMessage(), t);
            return Outcome.fail("python", "Python 执行异常: " + t.getMessage(), null);
        }
    }

    private String executeLine(String line, Map<String, Object> parameters,
                               Map<String, Object> variables, long remainingMs) throws DslLineError {
        line = substituteVariables(line, parameters, variables);

        if (line.startsWith("echo ")) {
            return line.substring(5);
        }

        if (line.startsWith("print ")) {
            return line.substring(6);
        }

        if (line.startsWith("log ")) {
            Log.i(TAG, line.substring(4));
            return null;
        }

        if (line.startsWith("set ")) {
            return handleSet(line, variables);
        }

        if (line.startsWith("if ")) {
            return handleIf(line, parameters, variables, remainingMs);
        }

        if (line.startsWith("concat ")) {
            return handleConcat(line);
        }

        if (line.startsWith("length ")) {
            return handleLength(line);
        }

        if (line.startsWith("upper ")) {
            return line.substring(6).toUpperCase();
        }

        if (line.startsWith("lower ")) {
            return line.substring(6).toLowerCase();
        }

        if (line.startsWith("trim ")) {
            return line.substring(5).trim();
        }

        if (line.startsWith("replace ")) {
            return handleReplace(line);
        }

        if (line.startsWith("split ")) {
            return handleSplit(line);
        }

        if (line.startsWith("join ")) {
            return handleJoin(line);
        }

        if (line.startsWith("call_tool ")) {
            return handleCallTool(line, remainingMs);
        }

        return line;
    }

    /**
     * {@code ${名字}} 替换：先查本次参数，再查本次调用内的变量表。
     *
     * <p>这里补了 {@link Matcher#quoteReplacement}：以前直接把值塞进 appendReplacement，
     * 值里的 {@code \} 或 {@code $1} 会被当成正则替换语法 —— 传一个带反斜杠的路径能让
     * 这行变乱码，传一个含 {@code $} 的值会抛 IllegalArgumentException（Illegal group
     * reference），报出来是一条看不出源头的框架错。
     */
    private String substituteVariables(String line, Map<String, Object> parameters,
                                       Map<String, Object> variables) {
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(line);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = null;

            if (parameters != null && parameters.containsKey(varName)) {
                value = parameters.get(varName);
            } else if (variables.containsKey(varName)) {
                value = variables.get(varName);
            }

            matcher.appendReplacement(sb,
                    value != null ? Matcher.quoteReplacement(String.valueOf(value)) : "");
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String handleSet(String line, Map<String, Object> variables) throws DslLineError {
        String content = line.substring(4);
        int equalsIndex = content.indexOf('=');
        if (equalsIndex > 0) {
            String varName = content.substring(0, equalsIndex).trim();
            String varValue = content.substring(equalsIndex + 1).trim();
            variables.put(varName, varValue);
            return null;
        }
        throw new DslLineError("语法错误: set 变量名=值（等号左边要有变量名）");
    }

    private String handleIf(String line, Map<String, Object> parameters,
                            Map<String, Object> variables, long remainingMs) throws DslLineError {
        String condition = line.substring(3).trim();
        Pattern ifPattern = Pattern.compile("\\(([^)]+)\\)\\s*then\\s+(.+)");
        Matcher matcher = ifPattern.matcher(condition);

        if (matcher.matches()) {
            String cond = matcher.group(1).trim();
            String thenPart = matcher.group(2).trim();

            if (evaluateCondition(cond, parameters, variables)) {
                return executeLine(thenPart, parameters, variables, remainingMs);
            }
            return null;
        }
        throw new DslLineError("语法错误: if (条件) then 命令");
    }

    private boolean evaluateCondition(String condition, Map<String, Object> parameters,
                                      Map<String, Object> variables) {
        try {
            condition = substituteVariables(condition, parameters, variables);

            if (condition.contains("==")) {
                String[] parts = condition.split("==", 2);
                return parts[0].trim().equals(parts[1].trim());
            }
            
            if (condition.contains("!=")) {
                String[] parts = condition.split("!=", 2);
                return !parts[0].trim().equals(parts[1].trim());
            }
            
            if (condition.contains(">")) {
                String[] parts = condition.split(">", 2);
                try {
                    double left = Double.parseDouble(parts[0].trim());
                    double right = Double.parseDouble(parts[1].trim());
                    return left > right;
                } catch (Exception e) {
                    return false;
                }
            }
            
            if (condition.contains("<")) {
                String[] parts = condition.split("<", 2);
                try {
                    double left = Double.parseDouble(parts[0].trim());
                    double right = Double.parseDouble(parts[1].trim());
                    return left < right;
                } catch (Exception e) {
                    return false;
                }
            }
            
            return Boolean.parseBoolean(condition);
        } catch (Exception e) {
            Log.e(TAG, "Error evaluating condition: " + e.getMessage());
            return false;
        }
    }
    
    private String handleConcat(String line) {
        String content = line.substring(7);
        String[] parts = content.split(",");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            result.append(part.trim());
        }
        return result.toString();
    }
    
    private String handleLength(String line) {
        return String.valueOf(line.substring(7).trim().length());
    }
    
    private String handleReplace(String line) throws DslLineError {
        String content = line.substring(8);
        String[] parts = content.split(",", 3);
        if (parts.length == 3) {
            return parts[0].trim().replace(parts[1].trim(), parts[2].trim());
        }
        throw new DslLineError("语法错误: replace 原字符串,旧内容,新内容");
    }
    
    private String handleSplit(String line) throws DslLineError {
        String content = line.substring(6);
        String[] parts = content.split(",", 2);
        if (parts.length != 2) {
            throw new DslLineError("语法错误: split 文本,分隔符");
        }
        String text = parts[0].trim();
        String delimiter = parts[1].trim();
        String[] splitResult = text.split(Pattern.quote(delimiter));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < splitResult.length; i++) {
            if (i > 0) result.append("\n");
            result.append(i + 1).append(": ").append(splitResult[i]);
        }
        return result.toString();
    }
    
    private String handleJoin(String line) throws DslLineError {
        String content = line.substring(5);
        String[] parts = content.split(",", 2);
        if (parts.length != 2) {
            throw new DslLineError("语法错误: join 分隔符,列表");
        }
        String delimiter = parts[0].trim();
        String listStr = parts[1].trim();
        if (listStr.startsWith("[") && listStr.endsWith("]")) {
            listStr = listStr.substring(1, listStr.length() - 1);
        }
        String[] items = listStr.split(",");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) result.append(delimiter);
            result.append(items[i].trim());
        }
        return result.toString();
    }
    
    private String handleCallTool(String line, long remainingMs) throws DslLineError {
        String content = line.substring(10);
        String[] parts = content.split(",", 2);

        if (parts.length < 1) {
            throw new DslLineError("语法错误: call_tool 工具名,[参数JSON]");
        }

        String toolName = parts[0].trim();
        Map<String, Object> toolParams = new LinkedHashMap<>();

        if (parts.length == 2) {
            toolParams = parseSimpleJson(parts[1].trim());
        }

        // ===== 递归/嵌套调用防护 =====
        // 1. 禁止调用元工具（动态工具创建自身/互调会造成无限递归）
        if ("create_dynamic_tool".equals(toolName) || "ai_create_tool".equals(toolName)) {
            throw new DslLineError("安全限制: 动态工具禁止调用 " + toolName + "（避免递归创建）");
        }
        // 2. 嵌套调用深度限制（动态工具 A call_tool 动态工具 B 再 call_tool...）
        int depth = CALL_DEPTH.get();
        if (depth >= MAX_CALL_DEPTH) {
            throw new DslLineError("安全限制: 工具嵌套调用超过 " + MAX_CALL_DEPTH + " 层，已终止");
        }

        AIToolManager toolManager = AIToolManager.getInstance(context);
        if (!toolManager.hasTool(toolName)) {
            throw new DslLineError("工具不存在: " + toolName
                    + "（先用 tool_registry(action=search) 确认工具名，不要凭记忆写）");
        }
        CALL_DEPTH.set(depth + 1);
        try {
            return runNestedWithTimeout(toolManager, toolName, toolParams, depth + 1, remainingMs);
        } finally {
            CALL_DEPTH.set(depth);
        }
    }

    /**
     * 带超时上限地执行一次嵌套 {@code call_tool}，返回给动态脚本看的文本。
     *
     * <p>为什么必须加：这条路径以前<b>完全没有超时</b>——动态工具里一句 {@code call_tool}
     * 可以无限吊住整轮；外层（在线/对话页路径）到点放弃之后，被调工具还在跑，
     * 而它的结果永远不会有人再读。预算与两条主路径同源
     * （{@link ToolTimeouts#effectiveMs}），只把上限压到 {@link #NESTED_MAX_TIMEOUT_MS}，
     * 再压到本次调用的剩余预算（{@link DynamicToolBudget}）：外层还有一道时限在管，
     * 嵌套只需要"够用但不无限、且不能把整次调用拖过线"。
     *
     * <p>两个 ThreadLocal 必须<b>显式带进工作线程</b>，否则"挪个线程"就等于弄丢两道保护：
     * {@link #CALL_DEPTH}（不传就每层重置成 0，A→B→A 的递归失去 MAX_CALL_DEPTH 上限）和
     * {@code ToolResultCardBinder} 的卡片抑制（不传就会给子调用挂卡，同一结果出现多张卡）。
     * 线程池线程会被复用，所以退出时必须清理而不是只设置。
     *
     * <p>子调用不成功时抛 {@link DslLineError}：旧版把失败文本当这一行的<em>输出</em>返回，
     * 于是"嵌套工具 X 失败"被拼进一次成功的工具结果里；那条静默说谎也一起没了——
     * 旧实现里 {@code getResult()} 为 null 会回落到字符串"工具执行完成"，把失败报成完成。
     */
    private String runNestedWithTimeout(final AIToolManager toolManager, final String toolName,
                                        final Map<String, Object> toolParams, final int nestedDepth,
                                        final long remainingMs) throws DslLineError {
        final int budgetMs = (int) Math.max(1L, Math.min(
                Math.min(NESTED_MAX_TIMEOUT_MS, ToolTimeouts.effectiveMs(
                        toolManager.getTool(toolName), toolParams, NESTED_BASE_TIMEOUT_MS)),
                remainingMs));
        CompletableFuture<AIToolResult> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                CALL_DEPTH.set(nestedDepth);
                com.oilquiz.app.ai.agent.ToolResultCardBinder.BridgeScope uiScope =
                        com.oilquiz.app.ai.agent.ToolResultCardBinder.suppress();
                try {
                    return toolManager.executeTool(toolName, toolParams);
                } finally {
                    try {
                        uiScope.close();
                    } finally {
                        // 复用线程必须清，否则深度会串到下一个任务上
                        CALL_DEPTH.remove();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "嵌套调用起线程失败(" + toolName + "): " + e.getMessage());
            throw new DslLineError("调用工具失败: "
                    + (e.getMessage() != null ? e.getMessage() : "无法启动子调用"));
        }

        try {
            AIToolResult result = future.get(budgetMs, TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new DslLineError("嵌套工具 " + toolName + " 返回空结果");
            }
            if (!result.isSuccess()) {
                throw new DslLineError("嵌套工具 " + toolName + " 失败: "
                        + (result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误"));
            }
            return result.getResult() != null ? result.getResult().toString() : "工具执行完成";
        } catch (TimeoutException e) {
            // cancel 只能中断等待，被调工具本身停不下来 —— 必须如实说明，
            // 否则动态脚本以为没发生，重跑一遍有副作用的调用
            future.cancel(true);
            throw new DslLineError("嵌套工具 " + toolName + " 超过 " + (budgetMs / 1000)
                    + "s 未回传，已停止等待；它可能仍在执行（不会自动重试，也不会替你重跑）。"
                    + "需要更稳的结果请把这次子调用缩小范围。");
        } catch (DslLineError e) {
            throw e;
        } catch (Exception e) {
            future.cancel(true);
            Log.e(TAG, "嵌套调用失败(" + toolName + "): " + e.getMessage());
            throw new DslLineError("调用工具失败: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
    
    private Map<String, Object> parseSimpleJson(String jsonStr) {
        // LinkedHashMap：同一句脚本每次跑都得到同样的参数顺序；HashMap 的顺序会变，
        // 于是"子工具看到的参数"不可复现（调试时对不上号）
        Map<String, Object> result = new LinkedHashMap<>();
        
        try {
            jsonStr = jsonStr.trim();
            if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
                jsonStr = jsonStr.substring(1, jsonStr.length() - 1);
            }
            
            Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
            Matcher matcher = kvPattern.matcher(jsonStr);
            
            while (matcher.find()) {
                String key = matcher.group(1);
                String value = matcher.group(2);
                result.put(key, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing simple JSON: " + e.getMessage());
        }
        
        return result;
    }
}
