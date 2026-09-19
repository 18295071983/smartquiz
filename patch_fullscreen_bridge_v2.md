# 壳升级施工单 v2：沉浸全屏 + 真屏幕方向（v7 → v8，bridge_api 3 → 4）

> **v2 修订说明（基于对壳模板的实测，2026-09-15）**
> 1. 第 2 节 manifest/configChanges → **实测已具备**（MainActivity: configChanges=0xDA0），无需改动；
> 2. 第 3 节 页面侧 → **已就绪**（aurora_clock_v4.html），电脑端不用再改页面；
> 3. 第 4 节新增 **步骤 4-6：模板回灌主项目 + 重编译**（v1 缺失；漏这步新壳不会被 export_apk 采用）。
> 实测依据：40 桥全清单（无全屏/方向桥）→ v8 已扩容至 59 桥（全屏/方向/亮度/TTS/壳内文件/选择器/对话框/系统/应用）；classes.dex 为预编译、AXML 反解 MainActivity 属性、APK_SOURCE_GUIDE 导出链路。



> 目的：让页面里的 ⛶「沉浸全屏」和 ⟳「横竖屏」真正生效（隐藏系统状态栏/手势条、物理转屏），
> 并保证**无壳环境（浏览器直开 HTML）自动回落到现有 CSS 伪方案**，不报错、不白屏。
> 适用壳：shell_version=v7 / bridge_api=3 / targetSdk 34 / minSdk 26。
> 改动文件：`apk_shell/src/main/java/com/cjhtmldemo/apk/MainActivity.java`、`AndroidManifest.xml`、页面侧。

---

## 0. 改动一览

| # | 文件 | 改动 | 是否必须 |
|---|---|---|---|
| 1 | `MainActivity.java` | 新增 19 个桥方法（v8 全量扩容：全屏/方向/亮度/TTS/壳内文件/选择器/对话框/系统/应用；下表 40 → 59 个） | 必须 |
| 2 | `MainActivity.java` | `BRIDGE_API` 3 → 4；`SHELL_VERSION` v7 → v8 | 必须 |
| 3 | ~~`AndroidManifest.xml`~~ | **实测已具备** `configChanges=0xDA0`，无需改动（见第 2 节） | 无需 |
| 4 | ~~`aurora_clock.html`~~ | **已就绪**（`aurora_clock_v4.html`，见第 3 节） | 无需 |
| 5 | 主项目 `src/main/assets/apk_shell/base.apk` | **用新壳模板替换（回灌）** —— v1 漏掉的一步，见第 4 节步骤 4 | 必须 |
| 6 | 文档三处 | 桥清单 40 → 59、`HTML_DESIGN_RULES.md` 第四节、`ExportApkTool` 描述 | 建议 |

新增桥（命名沿用现有驼峰风格）：

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `enterFullscreen(on)` | boolean | — | true=隐藏系统栏进入沉浸；false=恢复 |
| `isFullscreen()` | — | boolean | 当前是否沉浸（供页面初始化对齐） |
| `setOrientation(mode)` | string | — | `landscape` / `portrait` / `auto` |

---

## 1. 补丁 · MainActivity.java（新增桥方法）

在 `AppBridge` 内部类的末尾插入以下代码（**不要**插到 `addJavascriptInterface` 之前）。

### 1.1 新增 import

```java
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
```

### 1.2 桥方法（追加进 AppBridge）

```java
/* ================= bridge_api 4：沉浸全屏 + 屏幕方向 ================= */

/** 沉浸状态是否开启（用于失焦后重新应用） */
private boolean fsImmersive = false;

@JavascriptInterface
public void enterFullscreen(final boolean on) {
    runOnUiThread(new Runnable() {
        @Override public void run() {
            fsImmersive = on;
            applyImmersive(on);
        }
    });
}

@JavascriptInterface
public boolean isFullscreen() {
    return fsImmersive;
}

@JavascriptInterface
public void setOrientation(final String mode) {
    runOnUiThread(new Runnable() {
        @Override public void run() {
            int req;
            if ("landscape".equals(mode))      req = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            else if ("portrait".equals(mode))  req = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            else                               req = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; // auto
            try { setRequestedOrientation(req); } catch (Exception ignored) {}
        }
    });
}

/** 沉浸实现：API 30+ 走 WindowInsetsController，26-29 走老 flag */
private void applyImmersive(boolean on) {
    Window w = getWindow();
    if (w == null) return;
    if (Build.VERSION.SDK_INT >= 30) {
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        }
    } else {
        View dv = w.getDecorView();
        int flags = dv.getSystemUiVisibility();
        if (on) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                   | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                   | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                   | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                   | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                   | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_FULLSCREEN
                     | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                     | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        dv.setSystemUiVisibility(flags);
    }
    // 通知页面（可选）：让页面知道是"真全屏"还是"CSS 伪沉浸"
    try {
        webView.evaluateJavascript(
            "window.__shellFullscreen=" + on + ";"
          + "try{window.dispatchEvent(new Event('shellfullscreenchange'));}catch(e){}", null);
    } catch (Exception ignored) {}
}
```

> ⚠️ 若壳里 WebView 字段名不叫 `webView`（例如 `mWebView`），请同步改最后一段。
> ⚠️ `evaluateJavascript` 必须在 UI 线程调用 —— 本节所有调用点已在 `runOnUiThread` 内。

### 1.3 失焦后重新应用（经典坑，必加）

沉浸式（IMMERSIVE_STICKY）在弹窗、切后台回来、下拉通知栏之后，系统栏会重新出现，需要重新隐藏。

```java
@Override
public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && fsImmersive) applyImmersive(true);
}
```

> ⚠️ 若 MainActivity 里**已存在** `onWindowFocusChanged`，不要新写一个（编译冲突），把 `if (hasFocus && fsImmersive) applyImmersive(true);` 追加进已有方法体即可。

### 1.4 版本号

```java
private static final int    BRIDGE_API    = 4;   // 原 3
private static final String SHELL_VERSION = "v8"; // 原 "v7"
```

`getVersion()` 的 JSON 与 `getBridgeApi()` / `getShellVersion()` 的返回值需同步（若它们读的是上面两个常量则自动生效）。

---

## 2. AndroidManifest.xml → 实测**已具备，无需改动**

v1 曾把这里标为“必须”。反解壳模板 AXML 实测：

```
<activity name=com.cjhtmldemo.apk.MainActivity exported=true configChanges=0xDA0>
```

`0xDA0 = 0x800|0x400|0x100|0x80|0x20` = smallestScreenSize | screenSize | screenLayout | orientation | keyboardHidden —— v1 要求补的 5 个 flag **一个不缺**。转屏时系统只回调 `onConfigurationChanged`，WebView 不重建、页面状态不丢。

→ **本节无需操作**（保留记录，避免后人再补一遍）。

但 v1 的 **1.3「失焦后重新应用」仍必须做** —— 那是窗口焦点变化时系统清除 systemUiFlags 的问题，与 configChanges 无关。

## 3. 页面侧 → **已就绪（v4），无需再改**

v1 这里给的是手动给 aurora_clock.html 加“桥优先 + 回落”的 72 行 js。**这些工作已全部落地并被后续版本取代**：

| 版本 | 做了什么 |
|---|---|
| v2 | 桥优先 + 回落（`hasBridgeFn` / `enterFullscreen` / `setOrientation`，无桥时行为不变） |
| v3 | 尺寸体系重构（`syncVV()` 写 `--cw/--ch`） |
| v4 | ① 修 `syncVV` 尺寸交换失效（原读 `body.rot` 恒 false → 改读 `#stage.rot`）；② 安全区映射（新增 `syncSafe/readSA/curDeg` + 6 处 CSS 改 `var(--sa-*)`）；③ 设置页桥名 `getAppVersion` → `getVersion` |

**页面侧交付物**：`files/aurora_clock_v5.html`（110 KB 单文件，v5）。适配 v8 壳 59 桥：新增 TTS 语音报时 / 同步系统亮度 / 原生确认框重置 / 系统信息与桥数展示；v8 壳就位后自动走桥分支，无桥时对应行自动隐藏。

判据：v8 壳下 `hasBridgeFn('enterFullscreen')` → `true`；v7 壳下 → `false`（走回落，即当前实测状态）。

## 4. 构建与验证

```bat
:: 1) 改源码：MainActivity.java（见第 1 节：AppBridge 追加 3 个方法 + BRIDGE_API 3->4）
:: 2) 编译壳
cd apk_shell
.\gradlew.bat assembleRelease
:: 3) 重建元数据（必须，否则 ApkPacker 找不到图标/占位）
python tools\gen_meta.py build\outputs\apk\release\apk_shell-release-unsigned.apk
:: 4) ★回灌模板到主项目（答题宝）—— v1 漏掉的关键一步
Copy-Item build\outputs\apk\release\apk_shell-release-unsigned.apk ..\src\main\assets\apk_shell\base.apk -Force
:: 5) 重编译主项目
cd ..
.\gradlew.bat assembleDebug
:: 6) 装机 -> 用 samples/engine_demo/index.html 自测台全量跑，再导一次极光时钟验证
```

> 忘了第 ② 步 → `ApkPacker` 定位不到图标/占位，导出异常。

## 5. 验收清单

| # | 场景 | 期望 |
|---|---|---|
| 1 | 装包后调 `getBridgeApi()` | 返回 **4** |
| 2 | 点 ⛶ | 系统状态栏 + 手势条消失，下滑可**短暂**唤出后自动隐藏 |
| 3 | 再点 ⛶ | 系统栏恢复 |
| 4 | 点 ⟳ 切"横屏" | **物理转屏**（状态栏位置翻转），非画面旋转 |
| 5 | 转屏后再看时钟 | 页面**不重载**、设置不丢（验证 configChanges） |
| 6 | 切后台再回来 / 拉通知栏 | 系统栏回来后又自动隐去（验证 onWindowFocusChanged） |
| 7 | 浏览器直开 `aurora_clock.html` | 无桥 → 回落 CSS 伪沉浸，**无报错、无白屏** |
| 8 | `samples/engine_demo/index.html` | 自测台能枚举出 59 个桥方法 |

## 6. 已知限制

- **WebView 里的 `requestFullscreen()` 依旧无效**（需壳实现 `onShowCustomView`），所以全屏只能走本补丁的桥，纯网页层做不到。
- 沉浸只作用于本 Activity 窗口；`openBrowser` / 分享目标等系统页面不受控。
- `setOrientation('auto')` 在用户系统锁定旋转时仍会跟随系统锁，这是 Android 行为，不是 bug。
- 若壳有 `ForegroundBridgeService` 弹窗类 UI，进入沉浸后其自身窗口不受影响。

## 7. 文档同步（建议）

- `APK_SOURCE_GUIDE.md`：第三节桥清单 40 → **59**，顶部 `shell_version=v8 / bridge_api=4`。
- `HTML_DESIGN_RULES.md` 第四节：补全屏/方向两个常用能力。
- `ExportApkTool` 描述里的方法数同步。
