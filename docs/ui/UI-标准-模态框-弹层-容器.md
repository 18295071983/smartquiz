# UI 标准：模态框 / 弹层 / 卡片容器（2026-11-16）

> 起因：全站手写了 **90+ 处 `new AlertDialog.Builder`、15 处 `MaterialAlertDialogBuilder`、
> 若干 `BottomSheetDialog` / `new Dialog`**，每个页面自己定内边距、圆角、按钮色、能否点外部关闭。
> 结果同类确认框长得不一样，深色/换肤下有的跟主题、有的写死白底。
>
> 这份文档定义标准层，**新代码一律用标准层**；旧代码按文件逐步迁移，数量由
> `DialogStandardRatchetTest` 的棘轮值兜住（只能减不能增）。

## 1. 标准层 API（`com.oilquiz.app.ui.modal`）

### AppDialog —— 标准模态框

```java
// 提示
AppDialog.message(this, "提示", "内容");

// 二次确认
AppDialog.confirm(this, "删除会话", "删除后不可恢复，确定？", "删除", (d, w) -> doDelete());

// 完全控制
AppDialog.builder(this)
        .title("工具详情")
        .message(details)
        .content(customView)        // 自动加 token 内边距
        .items(options, listener)   // 列表选择
        .positive("复制", null)
        .negative("关闭", null)
        .cancelable(true)
        .show();
```

- 外观：Material 对话框主题（`materialAlertDialogTheme` 已接 ThemeOverlay），深色/换肤自动跟随；
- 自定义内容：统一 `Tokens.space2xl` 内边距，页面不再各自 `setPadding`；
- 返回值就是 `androidx.appcompat.app.AlertDialog`，需要时可直接 `dismiss()`。

### AppSheet —— 标准底部弹层

```java
AppSheet.builder(this)
        .title("选择操作")
        .subtitle("可选副标题")
        .content(rowView)                 // 内容区放任意 View
        .action("确定", true, v -> {...}) // 主按钮：实心 colorPrimary
        .action("取消", false, null)      // 次按钮：文字色 colorPrimary
        .cancelable(true)
        .show();
```

- 顶部圆角 24dp、底色 `?attr/colorSurface`、拖拽条 `colorControlTextHint`；
- 标题 `Tokens.TEXT_SUBTITLE` + SEMIBOLD，副标题 `TEXT_FOOTNOTE` + 次要色；
- `AppSheet` 实例可 `dismiss()` / `isShowing()` / `dialog()`（需要 BottomSheetDialog 时）。

### AppCard —— 标准卡片容器

```java
container.addView(AppCard.wrap(this, rowView));      // 包一层标准卡（含内边距）
container.addView(AppCard.wrap(this, rowView, true));// 紧凑：内容自己已有 padding
MaterialCardView card = AppCard.create(this);        // 空卡，自己 addView
```

- 圆角 `Tokens.radiusLg`、底色 `?attr/colorSurface`、描边 `?attr/colorBorder`、内边距 `Tokens.spaceLg`；
- 不允许再手写 `GradientDrawable` 白底卡（深色/换肤会失配）。

## 2. 硬性规则

1. **新代码禁止** `new AlertDialog.Builder(...)` / `new BottomSheetDialog(...)` / `new Dialog(...)`；
   一律 `AppDialog` / `AppSheet`。
2. 需要特殊外观时**扩展标准层**（加参数/加 Builder 方法），不要在调用处写一份。
3. 迁移旧代码时：先 `AppDialog` 替换，跑 `DialogStandardRatchetTest`，把对应基线数字下调。
4. `AppSheet` 的内容区（行/列表）可以自定义，但**外壳**（圆角/拖拽条/标题/按钮）不要重写。

## 3. 当前基线与验证

| 项 | 值 |
|---|---|
| `new AlertDialog.Builder(` | 62（基线，只减不增；92 → 62：McpManagerActivity 21 + DatabaseManagementActivity 9 + ChatDialogHelper 5 等已迁移） |
| `MaterialAlertDialogBuilder(` | 15 |
| `new BottomSheetDialog(` | 2 |
| `new Dialog(` | 6 |
| 已迁移 | `ChatV2ActionSheet` → `AppSheet`；`ChatDialogHelper` 5 处 → `AppDialog`；`McpManagerActivity` 21 处、`DatabaseManagementActivity` 9 处 → `AppDialog`；删除空壳 `ChatBottomSheet` |

- 测试：`src/test/java/com/oilquiz/app/ui/modal/AppModalStandardTest.java`（标准层行为）、
  `DialogStandardRatchetTest.java`（棘轮：手写数量不增长 + 标准层确实被使用）。
- 每批提交前跑：`.\gradlew.bat assembleDebug testDebugUnitTest --rerun`。

## 4. 附：动效标准（2026-11-16 归一）

对话页三处手写动画已收进 `UiFx`：

| 场景 | 标准 API | 口径 |
|---|---|---|
| 状态点/生成点脉冲 | `UiFx.pulse / stopPulse` | 1.1s、alpha 1→0.3→1、tag 防重复、stop 复位 |
| 消息方向性入场 | `UiFx.slideInMessage(view, fromRight)` | 8dp / 180ms，用户从右、AI 从左 |
| 多 part 错峰入场 | `UiFx.staggerIn(host, 35, 220)` | 淡入 + 上移 6dp，最多 6 档 |
| 折叠箭头 | `UiFx.rotateChevron(view, expanded)` | 90°/0°、180ms |
| 内容淡入/淡出 | `UiFx.fadeIn / fadeOutGone` | 200 / 150ms |

规则：

1. 新动效一律走 `UiFx`；对话页 Java 已由 `UiHardcodeGuardTest` 禁止 `new AlphaAnimation/TranslateAnimation/AnimationSet`、`ObjectAnimator.of`、`ValueAnimator.of`、`.animate()`；
2. **动效原语只管形状、不管配色**：颜色由被动画的 View 按 `?attr` 取（`SegmentScraper` 的位移/时长是冻结口径，其配色完全来自 `tv_gen_phase` 等视图的 `?attr/colorControlTextSecondary`）；`motionPrimitivesAreColorAgnostic` 钉住这一点；
3. `SegmentScraper`（刮刀滑入）是唯一实现、形状冻结；要改观感必须单独评审，不允许顺手优化。

## 6. 附：IME（软键盘）标准（2026-11-16）

审计发现：`EdgeToEdgeHelper` 对所有非 AI 对话页开启 `setDecorFitsSystemWindows(false)`，
但底部 inset 只算了 `systemBars + systemGestures`，**没算 `Type.ime()`**；而 edge-to-edge 后
系统不再自动给键盘让位，manifest 里也只有 2 个 Activity 写了 `adjustResize` ——
等于**大部分表单页键盘会盖住输入框**。只有 `AIChatActivity` 自己处理了 IME（它被 helper 跳过）。

标准：

1. 主题统一 `android:windowSoftInputMode=adjustResize`（IME insets 的分发前提）；
2. `EdgeToEdgeHelper.bottomInset()`：底部 inset 取 `systemBars / systemGestures / ime` 最大值，
   所有 Activity 根容器自动上推；
3. 贴边 `Dialog`（`applyDialog`）同样并入 IME，并设 `SOFT_INPUT_ADJUST_RESIZE`；
4. `AppDialog.show()` 统一设 `ADJUST_RESIZE`，自定义 `content` 里的 `EditText` 不被键盘盖住；
5. `AIChatActivity` 自管（mainContent 监听已含 `Type.ime()`），不在全局 helper 里重复处理；
6. 护栏：`EdgeToEdgeImeTest` 钉住"键盘最大 / 键盘收起 / 无 inset / 主题 adjustResize"四种口径。

## 7. 附：列表项与子布局颜色深度标准（2026-11-16）

| 层级 | 用途 | 语义色 |
|---|---|---|
| L0 | 页面底 | `colorBackground` |
| L1 | 列表项 / 卡片 | `colorSurface`（或 `colorCardBackground`） |
| L2 | 项内分区（展开区/工具栏/次级块） | `colorSurfaceVariant`（或 `colorControlBackground`） |
| L3 | 标签/芯片 | `colorControlBackground` + `colorBorder` |

禁止在 `item_*` 布局里出现 `@color/white|black|gray_*|purple_*|teal_*` 这类固定 chrome 色
（白字压在主题面上，深色/换肤后不可读）；图片浮层/天气等级色等"颜色由内容决定"的除外。
护栏：`UiHardcodeGuardTest.itemLayoutsUseSurfaceDepthAttrs`（允许清单含 4 个媒体/天气布局）。
本批已修：`item_ai_model`（按钮语义色 + 展开区 L2）、`item_backup`、`item_index`、
`item_column_header`、`item_optimization_suggestion`、`item_mapping_field`，并把
`rounded_corners_gray` 接主题。