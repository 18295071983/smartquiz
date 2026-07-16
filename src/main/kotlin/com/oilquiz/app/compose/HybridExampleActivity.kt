package com.oilquiz.app.compose

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.oilquiz.app.R

class HybridExampleActivity : AppCompatActivity() {
    
    private var traditionalCount = 0
    private var composeCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hybrid_example)

        // 设置 Toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // 传统按钮点击事件
        findViewById<MaterialButton>(R.id.traditionalButton)?.setOnClickListener {
            traditionalCount++
            Toast.makeText(this, "传统按钮被点击了 $traditionalCount 次!", Toast.LENGTH_SHORT).show()
        }

        // 返回按钮
        findViewById<MaterialButton>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        // 下一步按钮
        findViewById<MaterialButton>(R.id.btnNext)?.setOnClickListener {
            Toast.makeText(this, "前往下一步!", Toast.LENGTH_SHORT).show()
        }

        // 这里是关键！配置 ComposeView
        val composeView = findViewById<ComposeView>(R.id.composeView)
        composeView.setContent {
            SmartQuizTheme {
                HybridComposeContent(
                    onComposeButtonClick = {
                        composeCount++
                        Toast.makeText(this, "Compose 按钮被点击了 $composeCount 次!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridComposeContent(
    onComposeButtonClick: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var sliderValue by remember { mutableStateOf(0.5f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "这是 Jetpack Compose 组件",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = "这些组件是用 Jetpack Compose 写的，但它们嵌入在传统的 XML 布局中！",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Divider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Compose 输入框
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Compose 输入框") },
                placeholder = { Text("在这里输入...") },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null)
                },
                singleLine = true
            )

            // Compose 按钮
            Button(
                onClick = onComposeButtonClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Compose 按钮")
            }

            // Compose 滑块
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Compose 滑块: ${(sliderValue * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..1f
                )
            }

            Divider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = "下面是嵌入在 Compose 中的传统 Android View！",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 在 Compose 中嵌入传统 View！
            AndroidView(
                factory = { context ->
                    // 创建传统的 TextInputLayout
                    TextInputLayout(context).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                        hint = "传统 EditText (在 Compose 中)"
                        
                        val editText = TextInputEditText(context)
                        editText.hint = "在这里输入传统文本..."
                        addView(editText)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            AndroidView(
                factory = { context ->
                    // 创建传统的 MaterialButton
                    MaterialButton(context).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        text = "传统按钮 (在 Compose 中)"
                        setOnClickListener {
                            Toast.makeText(context, "传统按钮在 Compose 中被点击了!", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Column {
                        Text(
                            text = "双向互操作！",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "XML 中可以嵌入 Compose，Compose 中也可以嵌入传统 View！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}
