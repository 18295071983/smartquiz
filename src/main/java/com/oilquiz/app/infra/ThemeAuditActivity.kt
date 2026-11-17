package com.oilquiz.app.infra

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.oilquiz.app.R
import com.oilquiz.app.ai.chat.component.ComponentTheme
import com.oilquiz.app.theme.ThemeColors
import java.util.Locale

/**
 * 主题自检页（2026-11-16，UI 基础设施第一层）。
 *
 * <p>为什么要有这一页：[ThemeColors] 以前是 17 条手写白名单，313 个颜色资源里绝大多数
 * 换主题时不变，而且**没有任何地方能看出来**。这页把 `R.color` 的全部字段列出来，
 * 每个颜色标清「跟随主题 / 固定」，一眼看出漏接的颜色 —— 这就是后续「颜色语义化」
 * 那一步的工作清单。</p>
 *
 * <p>数据全部来自 [ThemeColors.isThemeAware]，着色走 [ThemeColors.get]（与本页外的控件同一口径），
 * 所以显示出来的色块就是真实渲染色，不是另抄一份常量。</p>
 *
 * <p>入口：AI 服务状态 → 开发者调试通道 → 主题自检；
 * adb：`am start -n com.oilquiz.app/.infra.ThemeAuditActivity`。</p>
 */
class ThemeAuditActivity : AppCompatActivity() {

    private lateinit var statsView: TextView
    private lateinit var listView: LinearLayout
    private lateinit var searchView: EditText
    private var rows: List<Row> = emptyList()
    private var total = 0
    private var themeAware = 0

    /** 一个颜色资源对应的一行（字段名即资源名，反射自 R.color）。 */
    private data class Row(val name: String, val resId: Int, val aware: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rows = collectRows()
        total = rows.size
        themeAware = rows.count { it.aware }

        setContentView(buildRoot())
        renderRows(rows)
    }

    // ---------------- UI 搭建 ----------------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            setBackgroundColor(ThemeColors.get(this@ThemeAuditActivity, R.color.background))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.theme_audit_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_primary))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.theme_audit_subtitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(12))
        })

        statsView = TextView(this).apply {
            text = statsText()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_primary))
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(statsView)

        searchView = EditText(this).apply {
            hint = getString(R.string.theme_audit_search_hint)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_primary))
            setHintTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_tertiary))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
            })
        }
        root.addView(searchView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)
        return root
    }

    private fun statsText(): String {
        val rate = if (total == 0) 0.0 else themeAware * 100.0 / total
        return getString(
            R.string.theme_audit_stats, total, themeAware, total - themeAware,
            String.format(Locale.getDefault(), "%.1f", rate)
        )
    }

    private fun applyFilter(q: String) {
        val key = q.trim().lowercase(Locale.ROOT)
        renderRows(
            if (key.isEmpty()) rows
            else rows.filter {
                it.name.lowercase(Locale.ROOT).contains(key) ||
                    hex(ThemeColors.get(this, it.resId)).contains(key)
            }
        )
    }

    private fun renderRows(list: List<Row>) {
        listView.removeAllViews()
        list.forEach { row -> listView.addView(rowView(row)) }
        if (list.isEmpty()) {
            listView.addView(TextView(this).apply {
                text = getString(R.string.theme_audit_empty)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_tertiary))
                setPadding(0, dp(12), 0, 0)
            })
        }
    }

    private fun rowView(row: Row): View {
        val color = ThemeColors.get(this, row.resId)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        // 色块：用真实取色结果，不另抄常量
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), ThemeColors.get(this@ThemeAuditActivity, R.color.outline))
            }
        }
        line.addView(swatch, LinearLayout.LayoutParams(dp(36), dp(36)))

        val name = TextView(this).apply {
            text = row.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ThemeColors.get(this@ThemeAuditActivity, R.color.text_primary))
            setPadding(dp(10), 0, dp(8), 0)
        }
        line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this).apply {
            text = if (row.aware) getString(R.string.theme_audit_aware)
                   else getString(R.string.theme_audit_fixed)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(
                ThemeColors.get(
                    this@ThemeAuditActivity,
                    if (row.aware) R.color.text_primary else R.color.text_tertiary
                )
            )
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                setColor(ThemeColors.get(this@ThemeAuditActivity, R.color.surface_variant))
                cornerRadius = dp(10).toFloat()
            }
        }
        line.addView(badge)

        line.setOnClickListener { copy("${row.name} = ${hex(color)}") }
        line.setOnLongClickListener {
            copy(rows.joinToString("\n") { "${it.name} = ${hex(ThemeColors.get(this, it.resId))}" })
            true
        }
        return line
    }

    // ---------------- 数据 ----------------

    /** 反射遍历 R.color 全部字段（按名字排序，便于排查）。 */
    private fun collectRows(): List<Row> {
        val fields = R.color::class.java.fields ?: return emptyList()
        return fields.mapNotNull { f ->
            val id = try {
                f.getInt(null)
            } catch (t: Throwable) {
                return@mapNotNull null
            }
            Row(f.name, id, ThemeColors.isThemeAware(id))
        }.sortedBy { it.name }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("theme-audit", text))
        Toast.makeText(this, getString(R.string.theme_audit_copied), Toast.LENGTH_SHORT).show()
    }

    private fun hex(color: Int): String =
        String.format(Locale.ROOT, "#%08X", color)

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
