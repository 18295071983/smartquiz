package com.oilquiz.app.infra

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp

/**
 * 组件画廊 · Compose 试点（2026-11-16）。
 *
 * <p>项目里 Compose 全家（BOM/material3/icons/activity-compose）一直是悬空依赖：全项目只有
 * 2 个 .kt、0 个 @Composable。这里用最小成本把它跑通，同时把"动作类组件"用 Compose 画一遍，
 * 作为后续新页面选型的对照样板。</p>
 *
 * <p>为什么用 {@link AbstractComposeView} 而不是直接 setContent：现有页面全是 View 体系，
 * 这个类可以被 XML/Java 当作普通 View 塞进任意布局，是 Compose 与 View 体系的最小接缝。</p>
 */
class ComposeHostView(context: android.content.Context) : AbstractComposeView(context) {

    @Composable
    override fun Content() {
        ActionGallery()
    }
}

@Composable
private fun ActionGallery() {
    // 与 App 的深浅档一致：系统深色时用 darkColorScheme，否则 lightColorScheme
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 按钮四态（对应 View 体系的 canonical Primary/Secondary/Text/Danger）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("主要", style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = {}) { Text("次要", style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = {}) { Text("文字", style = MaterialTheme.typography.bodyMedium) }
            }

            // 开关 / 单选 / 复选（带状态，确认交互真的能跑）
            var switchOn by remember { mutableStateOf(true) }
            var radioOn by remember { mutableStateOf(true) }
            var checkOn by remember { mutableStateOf(true) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Switch(checked = switchOn, onCheckedChange = { switchOn = it })
                RadioButton(selected = radioOn, onClick = { radioOn = !radioOn })
                Checkbox(checked = checkOn, onCheckedChange = { checkOn = it })
                Text(if (switchOn) "开关开" else "开关关")
            }

            // 滑条 + 进度
            var progress by remember { mutableStateOf(0.6f) }
            Slider(value = progress, onValueChange = { progress = it })
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

            // Chip（筛选态 / 辅助态）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("标签已选") })
                AssistChip(onClick = {}, label = { Text("辅助标签") })
            }

            Text(
                "以上组件全部由 Compose 渲染（Material3），与 View 体系同屏共存",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(280.dp)
            )
        }
    }
}
