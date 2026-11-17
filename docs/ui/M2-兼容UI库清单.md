# M2 兼容 UI 库清单（2026-11-17）

> 背景：项目主题是 `Theme.MaterialComponents.DayNight.NoActionBar`（M2）+ 自建 token
> （`Tokens` / `ThemeColors` / `ComponentTheme` / `AppDialog`），不是 Compose / Material3。
> 以后加 UI 库，先过下面四条标准，否则换肤、暗色、OLED、高对比都会漏。

## 选型标准

1. **颜色从主题属性取**：`?attr/colorPrimary`、`?attr/colorSurface`、`?attr/colorOnSurface` 等，
   不硬编码 `#RRGGBB`；
2. **支持 DayNight / 运行时换肤**：项目靠 `ThemeOverlay` 切换品牌色，库不能缓存启动时的颜色；
3. **与 `Theme.MaterialComponents.*` 同主题可用**（MDC bridge 或明确声明兼容）；
4. **许可证宽松、维护活跃**（优先 Apache-2.0 / MIT，近两年有提交）。

## 已在用（M2 兼容，保留）

| 库 | 用途 | 备注 |
|---|---|---|
| `com.google.android.material:material:1.12.0` | Material 组件基座 | 同一库也带 M3 主题，但项目固定用 M2 主题 |
| `androidx.appcompat` / `constraintlayout` / `recyclerview` / `cardview` / `gridlayout` | 基础 View 体系 | `CardView` 目前 102 处，迁 `MaterialCardView` 是另一件事 |
| `androidx.swiperefreshlayout` / `preference-ktx` / `webkit` | 下拉刷新 / 设置页 / WebView | 兼容 |
| `com.google.android.flexbox:flexbox` | 流式换行布局 | `NativeLayoutRenderer` 在用 |
| `com.airbnb.android:lottie` | 矢量动画 | 与主题无关 |
| `com.github.bumptech.glide` | 图片加载 | 全项目主力 |
| `io.noties.markwon:*` | Markdown 渲染 | 支持主题色注入 |
| `com.github.chrisbanes:PhotoView` | 图片缩放 | 老库但能用 |
| `com.journeyapps:zxing-android-embedded` | 扫码 | 配对二维码用 |
| `io.coil-kt:coil` / `de.hdodenhof:circleimageview` | 图片 / 圆头像 | 目前只在 `UiKitGalleryActivity` 展示，产品代码零引用 |

## 已引入（2026-11-18，精简后）

只保留两个有长期价值的（`assembleDebug` 与 `:dependencies` 均验证通过）：

| 库 | 版本 | 理由 |
|---|---|---|
| `com.github.PhilJay:MPAndroidChart` | v3.1.0 | 图表事实标准，冻结但稳定；颜色代码传入，接 `ThemeColors` |
| `androidx.core:core-splashscreen` | 1.2.0 | AndroidX 官方、仍在维护；启动页规范 |

## 已移除（2026-11-18）

| 库 | 原因 |
|---|---|
| `com.mikepenz:materialdrawer` 9.0.1 | 项目已有自定义抽屉；`DrawerLayout + NavigationView` 足够；且只发布在 jcenter 镜像 |
| `com.wdullaer:materialdatetimepicker` 4.2.3 | 最后发布 2019-08、2022 后无提交；MDC 自带 `MaterialDatePicker` / `MaterialTimePicker`（M2 样式、官方维护） |
| `com.facebook.shimmer:shimmer` 0.5.0 | 2019 后未更新；骨架屏用 20 行 alpha 动画即可 |
| `com.tapadoo.android:alerter` 7.0.1 | 半冻结、只在 jcenter；MDC 的 `Snackbar` 已覆盖同类需求，且项目有 `UiFx` |

> 背景：M2 **设计规范**停止演进（新组件形态走 M3），但 `com.google.android.material` **库仍维护并继续提供 `Theme.MaterialComponents.*`**；真正冻结的是上面这批第三方库。

## 参考清单（按需再引入）

| 库 | 用途 | M2 兼容性 |
|---|---|---|
| **mikepenz MaterialDrawer** | 侧边抽屉 / 账户切换 | 官方 M2 主题属性（`material_drawer_*`），v6.1+ 专门按 Material 2 调整过，见 [MIGRATION](https://raw.githubusercontent.com/2BWorks/MaterialDrawer/develop/MIGRATION.md) |
| **wdullaer MaterialDateTimePicker** | 日期 / 时间选择器 | M2 风格；**注意** MDC 主题会把 `Button` 全局换成 `MaterialButton`，其 OK/Cancel 会带 accent 底色 —— 官方给出的办法是改用 bridge theme 或覆写按钮样式，见 [README](https://raw.githubusercontent.com/wdullaer/MaterialDateTimePicker/master/README.md) |
| **facebook Shimmer** | 骨架屏 / 加载占位 | 与主题无关，颜色用 `?attr/colorSurfaceVariant` 传 |
| **PhilJay MPAndroidChart** | 图表（成绩/统计） | 颜色全部代码传入，接 `ThemeColors` 即可 |
| **tapadoo Alerter** | 顶部/底部轻提示条 | M2 风格，颜色可用主题属性 |
| **AndroidX SplashScreen** | 启动页 | 官方库，兼容 M2 主题 |

引入前先看 APK 体积（当前 debug ~800 MB，图片/模型是大头，UI 库影响小但要克制）。

## 不兼容 / 处理说明

| 依赖 | 原因 | 当前处理 |
|---|---|---|
| **Compose 全家桶**（`compose-bom 2024.02.02`、`ui`、`ui-graphics`、`ui-tooling`、`material3`、`material-icons-extended`、`activity-compose`、`lifecycle-*-compose`、`navigation-compose`） | Material3 是另一套设计语言（颜色角色/形状/组件规范都不同），和 M2 主题混用会“一半 M2 一半 M3” | **保留**（2026-11-18 决定：以后可能用得上）。现有入口：`ComposeGalleryActivity` / `ComposeHostView.kt`；M2 → Compose 的 token 桥在 `ComposeTheme.kt`。写 Compose 页面时优先用 `ComposeTheme` 提供的 M2 token；纯 M3 组件只放在 Compose 页面内部，别混进 XML 页面 |
| `com.afollestad.material-dialogs` | 最后更新 2020，已归档；且项目已有标准弹层 | 不引入，用 `AppDialog` |
| `Theme.Material3.*` XML 主题 | 与项目 M2 overlay 不兼容 | 不引入（Compose 内的 Material3 不受影响） |
| `androidx.legacy:legacy-support-v4` / glide、flexbox 重复声明 | 历史遗留 | 保持现状（未清理，避免无关改动） |

## 新 UI 代码约定（与 docs/ui/UI-标准-模态框-弹层-容器.md 配套）

- 颜色：`ThemeColors.get(ctx, R.color.xxx)` 或 XML `?attr/colorXxx`，不写十六进制；
- 间距/圆角/字号：`Tokens.spaceMd/radiusMd/...`，不写字面量；
- 弹层：`AppDialog`（`message/confirm/builder`），不直接 `new AlertDialog.Builder`；
- 动效：`UiFx`（click/longPress/breathing/pulseOnce），不各自手写 Animator；
- 新库若只支持 Material3，直接放弃 —— 换肤和暗色会崩。
