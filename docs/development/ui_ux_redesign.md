# 智能题库应用 - UI/UX 重新设计方案

**文档版本**: 1.0  
**创建日期**: 2026-03-31  
**状态**: 进行中

---

## 目录

1. [设计目标](#1-设计目标)
2. [设计原则](#2-设计原则)
3. [色彩系统](#3-色彩系统)
4. [字体系统](#4-字体系统)
5. [组件规范](#5-组件规范)
6. [界面设计](#6-界面设计)
7. [交互设计](#7-交互设计)
8. [响应式设计](#8-响应式设计)
9. [无障碍设计](#9-无障碍设计)
10. [实施计划](#10-实施计划)

---

## 1. 设计目标

### 1.1 核心目标
- **提升可用性**: 简化操作流程，降低学习成本
- **增强一致性**: 统一视觉风格，建立品牌认知
- **优化性能**: 减少加载时间，提升响应速度
- **改善体验**: 关注细节，创造愉悦的使用感受

### 1.2 具体指标
- 用户任务完成率提升 20%
- 界面操作步骤减少 30%
- 页面加载时间减少 50%
- 用户满意度评分达到 4.5/5

---

## 2. 设计原则

### 2.1 简洁清晰
- 减少视觉噪音，突出核心内容
- 使用留白创造呼吸感
- 信息层级分明，一目了然

### 2.2 一致性
- 统一组件样式和交互方式
- 保持术语和操作逻辑一致
- 遵循 Material Design 3 设计规范

### 2.3 反馈及时
- 操作后立即给予反馈
- 进度可视化，消除不确定性
- 错误提示友好且有帮助

### 2.4 容错设计
- 预防错误发生
- 提供撤销操作
- 清晰的错误恢复路径

---

## 3. 色彩系统

### 3.1 主色调
```
Primary: #2196F3 (蓝色)
- 用途: 主要按钮、链接、选中状态
- 含义: 专业、可信、平静

Primary Dark: #1976D2
- 用途: 状态栏、深色背景

Primary Light: #BBDEFB
- 用途: 背景、悬停状态
```

### 3.2 辅助色
```
Secondary: #FF9800 (橙色)
- 用途: 强调、警告、重要提示

Success: #4CAF50 (绿色)
- 用途: 成功状态、正确提示

Error: #F44336 (红色)
- 用途: 错误状态、删除操作

Warning: #FFC107 (黄色)
- 用途: 警告提示、注意事项

Info: #2196F3 (蓝色)
- 用途: 信息提示、说明文字
```

### 3.3 中性色
```
Background: #F5F5F5 (浅灰)
Surface: #FFFFFF (白色)
On Surface: #212121 (深灰)
On Background: #212121 (深灰)

Divider: #E0E0E0
Disabled: #9E9E9E
Placeholder: #BDBDBD
```

### 3.4 色彩应用规范

| 元素 | 颜色 | 说明 |
|------|------|------|
| 主按钮 | Primary | 填充背景，白色文字 |
| 次按钮 | Surface | 白色背景，Primary 文字 |
| 文字按钮 | Primary | 透明背景，Primary 文字 |
| 危险按钮 | Error | 填充背景，白色文字 |
| 背景 | Background | 页面背景色 |
| 卡片 | Surface | 卡片背景色 |
| 主要文字 | On Surface | 标题、正文 |
| 次要文字 | On Surface 60% | 说明、提示 |
| 禁用文字 | Disabled | 不可操作状态 |

---

## 4. 字体系统

### 4.1 字体选择
```
中文: Noto Sans SC (思源黑体)
英文: Roboto
数字: Roboto Mono
```

### 4.2 字体层级

| 样式 | 大小 | 字重 | 行高 | 用途 |
|------|------|------|------|------|
| H1 | 32sp | Bold | 40sp | 页面大标题 |
| H2 | 24sp | Bold | 32sp | 页面标题 |
| H3 | 20sp | Medium | 28sp | 区块标题 |
| H4 | 18sp | Medium | 26sp | 卡片标题 |
| Body 1 | 16sp | Regular | 24sp | 主要正文 |
| Body 2 | 14sp | Regular | 22sp | 次要正文 |
| Caption | 12sp | Regular | 16sp | 说明文字 |
| Overline | 10sp | Medium | 16sp | 标签、时间 |
| Button | 14sp | Medium | 16sp | 按钮文字 |

### 4.3 字体颜色
- 主要文字: On Surface (87% 不透明度)
- 次要文字: On Surface (60% 不透明度)
- 禁用文字: On Surface (38% 不透明度)
- 链接文字: Primary

---

## 5. 组件规范

### 5.1 按钮

#### 主按钮 (Filled Button)
```xml
<com.google.android.material.button.MaterialButton
    android:layout_width="wrap_content"
    android:layout_height="48dp"
    android:text="主按钮"
    android:textSize="14sp"
    android:textColor="@color/white"
    app:cornerRadius="8dp"
    app:backgroundTint="@color/primary" />
```

#### 次按钮 (Outlined Button)
```xml
<com.google.android.material.button.MaterialButton
    android:layout_width="wrap_content"
    android:layout_height="48dp"
    android:text="次按钮"
    android:textSize="14sp"
    android:textColor="@color/primary"
    style="@style/Widget.MaterialComponents.Button.OutlinedButton"
    app:cornerRadius="8dp"
    app:strokeColor="@color/primary" />
```

#### 文字按钮 (Text Button)
```xml
<com.google.android.material.button.MaterialButton
    android:layout_width="wrap_content"
    android:layout_height="48dp"
    android:text="文字按钮"
    android:textSize="14sp"
    android:textColor="@color/primary"
    style="@style/Widget.MaterialComponents.Button.TextButton" />
```

### 5.2 卡片

#### 标准卡片
```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="12dp"
    app:cardElevation="2dp"
    app:cardBackgroundColor="@color/surface">
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">
        <!-- 内容 -->
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

#### 可点击卡片
```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="12dp"
    app:cardElevation="2dp"
    android:clickable="true"
    android:focusable="true"
    app:rippleColor="@color/primary_light">
    <!-- 内容 -->
</com.google.android.material.card.MaterialCardView>
```

### 5.3 输入框

#### 标准输入框
```xml
<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="请输入内容"
    app:boxCornerRadiusTopStart="8dp"
    app:boxCornerRadiusTopEnd="8dp"
    app:boxCornerRadiusBottomStart="8dp"
    app:boxCornerRadiusBottomEnd="8dp"
    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox">
    
    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="16sp" />
</com.google.android.material.textfield.TextInputLayout>
```

### 5.4 列表项

#### 标准列表项
```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="72dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="16dp"
    android:paddingEnd="16dp">
    
    <ImageView
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:src="@drawable/ic_icon"
        android:tint="@color/primary" />
    
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical"
        android:layout_marginStart="16dp">
        
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="标题"
            android:textSize="16sp"
            android:textColor="@color/on_surface" />
        
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="描述"
            android:textSize="14sp"
            android:textColor="@color/on_surface_60" />
    </LinearLayout>
    
    <ImageView
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:src="@drawable/ic_chevron_right"
        android:tint="@color/on_surface_38" />
</LinearLayout>
```

---

## 6. 界面设计

### 6.1 主界面

#### 布局结构
```
┌─────────────────────────────┐
│  Toolbar (标题 + 操作按钮)    │
├─────────────────────────────┤
│                             │
│  快捷操作卡片 (横向滚动)       │
│  ┌─────┐ ┌─────┐ ┌─────┐   │
│  │导入 │ │添加 │ │批量 │   │
│  └─────┘ └─────┘ └─────┘   │
│                             │
├─────────────────────────────┤
│  筛选栏                      │
│  [分类 ▼] [搜索 🔍]          │
├─────────────────────────────┤
│                             │
│  题目列表                    │
│  ┌───────────────────────┐  │
│  │ 题目 1                │  │
│  │ 内容预览...           │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │ 题目 2                │  │
│  │ 内容预览...           │  │
│  └───────────────────────┘  │
│                             │
└─────────────────────────────┘
```

#### 设计要点
- 使用卡片式布局展示快捷操作
- 题目列表使用圆角卡片，增加视觉层次
- 添加悬浮操作按钮 (FAB) 用于快速添加

### 6.2 题目详情界面

#### 布局结构
```
┌─────────────────────────────┐
│  ←  Toolbar (返回 + 标题)     │
├─────────────────────────────┤
│                             │
│  题目卡片                    │
│  ┌───────────────────────┐  │
│  │ 难度: ★★★☆☆           │  │
│  │ 分类: 数学 > 代数       │  │
│  │                       │  │
│  │ 题目内容...            │  │
│  │                       │  │
│  │ A. 选项一              │  │
│  │ B. 选项二              │  │
│  │ C. 选项三              │  │
│  │ D. 选项四              │  │
│  └───────────────────────┘  │
│                             │
│  操作按钮                    │
│  [编辑] [删除] [分享]        │
│                             │
│  解析卡片 (可展开)            │
│  ┌───────────────────────┐  │
│  │ 答案: B               │  │
│  │ 解析: ...             │  │
│  └───────────────────────┘  │
│                             │
└─────────────────────────────┘
```

### 6.3 练习界面

#### 布局结构
```
┌─────────────────────────────┐
│  进度: 5/20  时间: 12:34     │
├─────────────────────────────┤
│                             │
│  题目卡片                    │
│  ┌───────────────────────┐  │
│  │                       │  │
│  │ 题目内容...            │  │
│  │                       │  │
│  │ ○ A. 选项一           │  │
│  │ ○ B. 选项二           │  │
│  │ ○ C. 选项三           │  │
│  │ ○ D. 选项四           │  │
│  │                       │  │
│  └───────────────────────┘  │
│                             │
│  [上一题]        [下一题]    │
│                             │
└─────────────────────────────┘
```

### 6.4 统计界面

#### 布局结构
```
┌─────────────────────────────┐
│  Toolbar (学习统计)          │
├─────────────────────────────┤
│  时间筛选: [本周] [本月] [全部]│
├─────────────────────────────┤
│                             │
│  概览卡片                    │
│  ┌─────────┬─────────┐     │
│  │ 答题数  │ 正确率  │     │
│  │  128    │  85%    │     │
│  └─────────┴─────────┘     │
│                             │
│  趋势图表                    │
│  ┌───────────────────────┐  │
│  │    📈 折线图          │  │
│  └───────────────────────┘  │
│                             │
│  知识点掌握                  │
│  ┌───────────────────────┐  │
│  │ 代数 ████████░░ 80%   │  │
│  │ 几何 ██████░░░░ 60%   │  │
│  │ 统计 █████████░ 90%   │  │
│  └───────────────────────┘  │
│                             │
└─────────────────────────────┘
```

---

## 7. 交互设计

### 7.1 导航设计

#### 底部导航栏
```
┌─────────────────────────────────────┐
│  🏠      📚      📊      ⚙️      │
│ 首页    题库    统计    设置    │
└─────────────────────────────────────┘
```

#### 导航规范
- 始终显示底部导航栏
- 当前页面图标高亮显示
- 点击图标切换页面，无动画延迟
- 支持手势返回

### 7.2 操作反馈

#### 加载状态
- 使用 CircularProgressIndicator
- 显示加载提示文字
- 支持取消操作

#### 成功提示
- 使用 Snackbar 显示成功消息
- 自动消失，时长 2 秒
- 支持撤销操作

#### 错误提示
- 使用 Snackbar 显示错误消息
- 提供重试按钮
- 详细错误可点击查看

### 7.3 动画规范

#### 转场动画
- 页面切换: 淡入淡出，时长 200ms
- 返回操作: 从左侧滑入，时长 200ms
- 模态框: 从底部滑入，时长 300ms

#### 微交互
- 按钮点击: 涟漪效果
- 列表项: 按压效果
- 卡片: 轻微阴影变化

---

## 8. 响应式设计

### 8.1 断点定义

| 设备类型 | 宽度范围 | 布局调整 |
|---------|---------|---------|
| 手机 (小) | < 360dp | 单列布局，紧凑间距 |
| 手机 (标准) | 360dp - 400dp | 单列布局，标准间距 |
| 手机 (大) | 400dp - 600dp | 单列布局，宽松间距 |
| 平板 | 600dp - 840dp | 双列布局 |
| 大屏设备 | > 840dp | 三列布局 |

### 8.2 适配策略

#### 手机适配
- 单列布局
- 底部导航栏
- 全屏模态框
- 紧凑的间距 (8dp, 12dp, 16dp)

#### 平板适配
- 双列布局 (列表 + 详情)
- 侧边导航栏
- 对话框模态框
- 宽松的间距 (16dp, 24dp, 32dp)

---

## 9. 无障碍设计

### 9.1 视觉无障碍
- 支持系统字体大小调整
- 支持深色模式
- 颜色对比度符合 WCAG 2.1 AA 标准
- 图标配合文字标签

### 9.2 操作无障碍
- 支持 TalkBack 屏幕阅读器
- 所有可点击元素支持焦点
- 提供内容描述 (contentDescription)
- 支持键盘导航

### 9.3 听力无障碍
- 重要信息不仅通过声音传达
- 提供视觉反馈替代声音提示

---

## 10. 实施计划

### 10.1 实施阶段

#### 阶段一: 基础规范 (1 周)
- [ ] 定义色彩系统
- [ ] 定义字体系统
- [ ] 创建基础组件库
- [ ] 编写组件使用文档

#### 阶段二: 核心界面 (2 周)
- [ ] 重新设计主界面
- [ ] 重新设计题目详情
- [ ] 重新设计练习界面
- [ ] 重新设计统计界面

#### 阶段三: 辅助界面 (1 周)
- [ ] 重新设计设置界面
- [ ] 重新设计导入导出界面
- [ ] 重新设计用户界面
- [ ] 统一所有界面风格

#### 阶段四: 交互优化 (1 周)
- [ ] 添加过渡动画
- [ ] 优化操作反馈
- [ ] 完善加载状态
- [ ] 测试交互流畅度

#### 阶段五: 无障碍适配 (1 周)
- [ ] 添加内容描述
- [ ] 测试屏幕阅读器
- [ ] 优化键盘导航
- [ ] 验证对比度

### 10.2 验收标准

- [ ] 所有界面符合设计规范
- [ ] 通过视觉一致性检查
- [ ] 交互流畅，无卡顿
- [ ] 支持无障碍访问
- [ ] 用户测试满意度 > 4.0/5

---

## 附录

### A. 设计资源

#### 图标库
- Material Design Icons
- 自定义图标 (根据业务需求)

#### 图片资源
- 使用矢量图 (Vector Drawable)
- 支持多分辨率
- 使用 WebP 格式优化体积

### B. 工具推荐

- **设计工具**: Figma / Sketch
- **原型工具**: Figma / Principle
- **标注工具**: Figma / Zeplin
- **协作工具**: Figma / Notion

### C. 参考资源

- [Material Design 3](https://m3.material.io/)
- [Android 设计规范](https://developer.android.com/design)
- [WCAG 2.1 无障碍指南](https://www.w3.org/WAI/WCAG21/quickref/)

---

**文档维护**: 设计团队  
**最后更新**: 2026-03-31  
**审核状态**: 待审核
