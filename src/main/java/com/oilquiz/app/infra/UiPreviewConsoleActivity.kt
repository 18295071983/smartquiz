package com.oilquiz.app.infra

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.oilquiz.app.R
import com.oilquiz.app.ai.chat.component.ComponentData
import com.oilquiz.app.ai.chat.component.ComponentRegistry
import com.oilquiz.app.ai.chat.component.ComponentTheme
import com.oilquiz.app.ai.python.NativeLayoutRenderer
import org.json.JSONObject
import java.io.File

/**
 * UI 预览台（《UI 技术选型讨论纪要》7.1 梯队 1.1）。
 *
 * <p>把原来的 adb 注入雏形升级成可视化预览台：
 * - 样例列表：内置 layout 树与卡片样例，全部放在 `assets/ui_preview/`（index.json + 单个 JSON），
 *   加样例只需加文件 + 一行索引，不用改代码；
 * - 编辑区：等宽 JSON 文本框，点「渲染」立即重渲；
 * - 报错：解析失败在编辑区下方常驻显示「第 N 行第 M 列 + 错误信息 + 出错行原文」；
 * - 渲染区：滚动容器，layout 走 [NativeLayoutRenderer.render]，卡片走
 *   [ComponentRegistry.render]，不新建渲染逻辑；
 * - 复制 / 分享导出：把手机上调好的 JSON 带回电脑（FileProvider + ACTION_SEND）。
 *
 * <p>深浅色跟随 App 主题：主题 overlay 由 SmartQuizApplication.onActivityPreCreated 注入，
 * 本页颜色全部取 [ComponentTheme] / 主题 attr，不新增任何色值。
 *
 * <p>入口：工具集 → AI服务状态 → 开发者调试通道 → UI 预览台；
 * adb：`am start -n com.oilquiz.app/.infra.DebugLayoutInjectActivity --es mode preview`
 * （可加 `--es sample <id>` 直接定位样例，或 `--es json_b64 <base64>` 预填一段 JSON）。
 * release 包内自动 finish（与 DebugLayoutInjectActivity 同一道防线）。
 */
class UiPreviewConsoleActivity : AppCompatActivity() {

    private data class Sample(
        val id: String,
        val title: String,
        val kind: String,
        val file: String
    )

    private lateinit var content: LinearLayout
    private lateinit var spinner: Spinner
    private lateinit var editor: EditText
    private lateinit var errorText: TextView
    private lateinit var statusText: TextView
    private lateinit var renderContainer: LinearLayout
    private lateinit var renderScroll: ScrollView

    private val samples = mutableListOf<Sample>()

    /** 已经渲染进编辑区的样例下标；用于吞掉 Spinner 初始 selection 的迟到回调，避免重复渲染/覆盖预填 JSON。 */
    private var loadedSampleIndex = -1

    /** IME inset 的基线 padding（onCreate 时 XML 里的值，避免与 EdgeToEdgeHelper 的 padding 互相累加） */
    private var contentBaseBottomPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isDebuggable()) {
            Log.w(TAG, "release 构建不允许使用 UI 预览台，finish()")
            finish()
            return
        }
        setContentView(R.layout.activity_ui_preview_console)
        setTitle(R.string.ui_preview_console_title)

        content = findViewById(R.id.preview_content)
        spinner = findViewById(R.id.spinner_samples)
        editor = findViewById(R.id.et_json)
        errorText = findViewById(R.id.tv_error)
        statusText = findViewById(R.id.tv_render_status)
        renderContainer = findViewById(R.id.render_container)
        renderScroll = findViewById(R.id.sv_render)

        contentBaseBottomPadding = content.paddingBottom
        applyThemeStyles()
        wireImeInsets()

        if (!loadSamples()) return
        setupSpinner()
        wireActions()

        val restoredJson = savedInstanceState?.getString(KEY_EDITOR_JSON)
        val restoredIndex = savedInstanceState?.getInt(KEY_SAMPLE_INDEX, 0) ?: 0
        val requestedIndex = intent.getStringExtra(EXTRA_SAMPLE)
            ?.let { id -> samples.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val startIndex = (if (savedInstanceState != null) restoredIndex else requestedIndex)
            .coerceIn(0, samples.size - 1)
        spinner.setSelection(startIndex, false)

        val presetJson = if (savedInstanceState == null) readPresetJson(intent) else null
        when {
            restoredJson != null -> {
                loadedSampleIndex = startIndex
                editor.setText(restoredJson)
                renderCurrent()
            }

            presetJson != null -> {
                loadedSampleIndex = startIndex
                editor.setText(presetJson)
                editor.setSelection(0)
                renderCurrent()
            }

            else -> loadSample(startIndex)
        }

        // 初始化完成后再监听下拉；用 loadedSampleIndex 吞掉 Spinner 初始 selection 的迟到回调
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position != loadedSampleIndex) loadSample(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EDITOR_JSON, editor.text?.toString().orEmpty())
        outState.putInt(KEY_SAMPLE_INDEX, spinner.selectedItemPosition)
    }

    // ------------------------------------------------------------------ 初始化

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun loadSamples(): Boolean {
        return try {
            val root = JSONObject(readAsset("ui_preview/index.json"))
            val array = root.optJSONArray("samples")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val file = item.optString("file")
                    if (file.isEmpty()) continue
                    samples.add(
                        Sample(
                            id = item.optString("id", file),
                            title = item.optString("title", file),
                            kind = item.optString("kind", "layout"),
                            file = file
                        )
                    )
                }
            }
            if (samples.isEmpty()) {
                showError("样例索引为空：assets/ui_preview/index.json")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "读取样例索引失败", t)
            showError("读取样例索引失败：${t.message}")
            statusText.text = "样例加载失败"
            statusText.setTextColor(ComponentTheme.danger(this))
            false
        }
    }

    private fun setupSpinner() {
        val labels = samples.map { sample ->
            val prefix = if (sample.kind == "card") "卡片" else "布局"
            "$prefix · ${sample.title}"
        }
        spinner.adapter = SampleAdapter(this, labels)
        val count = findViewById<TextView>(R.id.tv_sample_count)
        count.text = "${samples.size} 个样例"
    }

    private fun wireActions() {
        findViewById<MaterialButton>(R.id.btn_render).setOnClickListener {
            hideKeyboard()
            renderCurrent()
        }
        findViewById<MaterialButton>(R.id.btn_copy).setOnClickListener { copyJson() }
        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { shareJson() }
    }

    /** 颜色统一走 ComponentTheme / 主题 attr，不在本页写死任何色值。 */
    private fun applyThemeStyles() {
        val danger = ComponentTheme.danger(this)
        errorText.setTextColor(danger)
        val bg = GradientDrawable()
        bg.setColor(ComponentTheme.withAlpha(danger, 0x1A))
        bg.setStroke(ComponentTheme.dp(this, 1f), ComponentTheme.withAlpha(danger, 0x33))
        bg.cornerRadius = ComponentTheme.dp(this, 8f).toFloat()
        errorText.background = bg
        statusText.setTextColor(ComponentTheme.textSecondary(this))
    }

    /** 键盘弹出时把 IME inset 补成内容底部 padding，保证「渲染 / 复制 / 分享」不被挡住。 */
    private fun wireImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            val extra = (ime - bars).coerceAtLeast(0)
            view.updatePadding(bottom = contentBaseBottomPadding + extra)
            insets
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun loadSample(index: Int) {
        val sample = samples.getOrNull(index) ?: return
        loadedSampleIndex = index
        try {
            val json = readAsset("ui_preview/${sample.file}")
            editor.setText(json)
            editor.setSelection(0)
            renderCurrent()
        } catch (t: Throwable) {
            Log.e(TAG, "读取样例失败: ${sample.file}", t)
            statusText.text = "读取样例失败 · ${sample.file}"
            statusText.setTextColor(ComponentTheme.danger(this))
            showError("读取样例失败：${t.message}")
        }
    }

    private fun renderCurrent() {
        val raw = editor.text?.toString().orEmpty()
        errorText.visibility = View.GONE
        renderContainer.removeAllViews()

        if (raw.isBlank()) {
            statusText.text = "渲染失败 · JSON 为空"
            statusText.setTextColor(ComponentTheme.danger(this))
            showError("第 1 行：JSON 为空，请先选择样例或粘贴一段 JSON。")
            return
        }
        if (raw.trimStart().startsWith("[")) {
            statusText.text = "渲染失败 · 顶层类型不支持"
            statusText.setTextColor(ComponentTheme.danger(this))
            showError(
                "第 1 行：顶层必须是 JSON 对象 —— " +
                    "layout 树写 {\"root\":{…}}，卡片写 {\"type\":\"…\",\"props\":{…}}。"
            )
            return
        }

        val layout: JSONObject = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            reportParseError(raw, t)
            return
        }

        val started = SystemClock.elapsedRealtime()
        try {
            val view: View
            val label: String
            if (looksLikeCard(layout)) {
                val type = layout.optString("type")
                val data = ComponentData.of(type, cardProps(layout))
                val rendered = ComponentRegistry.getInstance().render(this, data)
                    ?: ComponentRegistry.getInstance().renderFallback(this, data)
                view = rendered
                label = "卡片 · $type"
            } else {
                val rendered = NativeLayoutRenderer.render(
                    this, layout, null, HashMap<String, Any>()
                )
                if (rendered == null) {
                    throw IllegalStateException("NativeLayoutRenderer.render 返回 null")
                }
                view = rendered
                label = "layout 树 · ${countNodes(layout)} 个节点"
            }
            renderContainer.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val cost = SystemClock.elapsedRealtime() - started
            statusText.text = "渲染成功 · $label · ${cost}ms"
            statusText.setTextColor(ComponentTheme.textSecondary(this))
            renderScroll.post { renderScroll.scrollTo(0, 0) }
            Log.i(TAG, "render ok: $label (${cost}ms, ${raw.length} chars)")
        } catch (t: Throwable) {
            Log.e(TAG, "渲染器抛异常", t)
            statusText.text = "渲染失败 · 渲染器抛异常"
            statusText.setTextColor(ComponentTheme.danger(this))
            showError("渲染失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 卡片 JSON 判定：优先「type+props」，也接受 props 直接写在顶层的旧写法；带 root/children 的按 layout 树。 */
    private fun looksLikeCard(obj: JSONObject): Boolean {
        if (!obj.has("type")) return false
        if (obj.has("root") || obj.has("children") || obj.has("use") || obj.has("define")) {
            return false
        }
        if (obj.has("props")) return true
        val type = obj.optString("type")
        return ComponentRegistry.getInstance().componentFor(type) != null
    }

    /** 卡片 props：优先 props 对象；没有则把顶层字段（除 type）当作 props，与 layout 嵌套卡片同口径。 */
    private fun cardProps(obj: JSONObject): JSONObject {
        obj.optJSONObject("props")?.let { return it }
        val props = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "type") continue
            props.put(key, obj.opt(key))
        }
        return props
    }

    /** 统计 layout 树节点数（顶层可能是 {"root":{…}}，也可能直接是节点）。 */
    private fun countNodes(layout: JSONObject): Int =
        countNode(layout.optJSONObject("root") ?: layout)

    private fun countNode(node: JSONObject): Int {
        var count = 1
        val children = node.optJSONArray("children") ?: return count
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            count += countNode(child)
        }
        return count
    }

    // ------------------------------------------------------------------ 报错

    /**
     * org.json 的 JSONException 形如 `Expected ',' or ']' after … at character 128 of {…}`。
     * 这里从 character 偏移换算出「第 N 行第 M 列」，并附上出错行原文；不显示整段 payload。
     */
    private fun reportParseError(raw: String, t: Throwable) {
        val message = t.message ?: t.javaClass.simpleName
        val offset = Regex("at character (\\d+)").find(message)
            ?.groupValues?.get(1)?.toIntOrNull()
        var line = 1
        var column = 1
        if (offset != null) {
            val pos = offset.coerceIn(0, raw.length)
            line = 1 + raw.substring(0, pos).count { it == '\n' }
            val lineStart = raw.lastIndexOf('\n', pos - 1) + 1
            column = pos - lineStart + 1
        }
        val clean = message
            .replace(Regex("\\s+of\\s+[\\s\\S]*$"), "")
            .trim()
        val lineText = raw.lineSequence()
            .drop(line - 1)
            .firstOrNull()
            ?.trim()
            ?.take(80)
            .orEmpty()
        val detail = buildString {
            append("第 ").append(line).append(" 行")
            if (offset != null) append("第 ").append(column).append(" 列")
            append("：").append(clean)
            if (lineText.isNotEmpty()) append("\n› ").append(lineText)
        }
        showError(detail)
        statusText.text = "渲染失败 · JSON 解析错误（第 $line 行）"
        statusText.setTextColor(ComponentTheme.danger(this))
        Log.w(TAG, "json parse error: line=$line col=$column msg=$clean")
    }

    private fun showError(text: String) {
        errorText.text = text
        errorText.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ 复制 / 分享

    private fun copyJson() {
        val json = editor.text?.toString().orEmpty()
        if (json.isBlank()) {
            toast("没有可复制的 JSON")
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast("剪贴板不可用")
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("ui-preview-json", json))
        toast("JSON 已复制（${json.length} 字符）")
    }

    private fun shareJson() {
        val json = editor.text?.toString().orEmpty()
        if (json.isBlank()) {
            toast("没有可分享的 JSON")
            return
        }
        try {
            val dir = File(cacheDir, "ui_preview")
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建缓存目录")
            val safeId = (samples.getOrNull(spinner.selectedItemPosition)?.id ?: "custom")
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(dir, "ui-preview-$safeId.json")
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "UI 预览台 JSON（$safeId）")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, json)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享 / 导出当前 JSON"))
            Log.i(TAG, "分享导出 JSON: ${file.absolutePath}（${file.length()} 字节）")
        } catch (t: Throwable) {
            Log.w(TAG, "分享文件失败，回退为文本分享: ${t.message}")
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "UI 预览台 JSON")
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                startActivity(Intent.createChooser(send, "分享 JSON 文本"))
            } catch (t2: Throwable) {
                toast("分享失败：${t2.message}")
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun readPresetJson(intent: Intent?): String? {
        if (intent == null) return null
        val base64 = intent.getStringExtra(EXTRA_JSON_B64)
        if (!base64.isNullOrEmpty()) {
            return try {
                String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "json_b64 解码失败: ${t.message}")
                null
            }
        }
        return intent.getStringExtra(EXTRA_JSON)?.takeIf { it.isNotEmpty() }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        renderScroll.requestFocus()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** 下拉文字颜色跟随主题（默认 simple_spinner_item 在深色下对比度不稳）。 */
    private inner class SampleAdapter(
        context: Context,
        labels: List<String>
    ) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            view.setTextColor(ComponentTheme.textPrimary(this@UiPreviewConsoleActivity))
            view.textSize = 13f
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getDropDownView(position, convertView, parent) as TextView
            view.setTextColor(ComponentTheme.textPrimary(this@UiPreviewConsoleActivity))
            view.textSize = 13f
            val pad = ComponentTheme.dp(this@UiPreviewConsoleActivity, 12f)
            view.setPadding(pad, pad, pad, pad)
            return view
        }
    }

    private companion object {
        const val TAG = "UiPreviewConsole"
        const val EXTRA_SAMPLE = "sample"
        const val EXTRA_JSON = "json"
        const val EXTRA_JSON_B64 = "json_b64"
        const val KEY_EDITOR_JSON = "editor_json"
        const val KEY_SAMPLE_INDEX = "sample_index"
    }
}
