# 壳补丁：沉浸式全屏 + 真屏幕方向（v7 → v8，bridge_api 3 → 4）

> [!IMPORTANT]
> **本文件不是本项目代码/架构的一部分。**
>
> 它是一次性产出物的历史记录 —— 「极光时钟」HTML 经壳 APK 导出时的
> 调试、补丁与验收材料（2026-09-14/15/19）。保留仅供追溯，
> **不随项目维护，也不会被更新**。
>
> 壳能力的**现行权威文档**是 `APK_SOURCE_GUIDE.md`（壳源码指南）与
> `HTML_DESIGN_RULES.md`（HTML 生成规则），当前壳版本 v8.1。

---
> 目的：让页面里的 ⛶「沉浸全屏」和 ⟳「横竖屏」真正生效（隐藏系统状态栏/手势条、物理转屏），
> 并保证**无壳环境（浏览器直开 HTML）自动回落到现有 CSS 伪方案**，不报错、不白屏。
> 适用壳：shell_version=v7 / bridge_api=3 / targetSdk 34 / minSdk 26。
> 改动文件：`apk_shell/src/main/java/com/cjhtmldemo/apk/MainActivity.java`、`AndroidManifest.xml`、页面侧。

---

## 0. 改动一览

| # | 文件 | 改动 | 是否必须 |
|---|---|---|---|
| 1 | `MainActivity.java` | 新增 3 个桥方法 + 1 个私有实现（下表 40 → 43 个） | 必须 |
| 2 | `MainActivity.java` | `BRIDGE_API` 3 → 4；`SHELL_VERSION` v7 → v8 | 必须 |
| 3 | `AndroidManifest.xml` | MainActivity 补 `configChanges`（**不补则转屏会重建 Activity、WebView 重载、时钟状态丢失**） | 必须 |
| 4 | `aurora_clock.html` | 改写 `toggleImmerse()` / `applyOrient()`，优先调桥、失败回落 | 必须 |
| 5 | 文档三处 | 桥清单 40 → 43、`HTML_DESIGN_RULES.md` 第四节、`ExportApkTool` 描述 | 建议 |

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

## 2. 补丁 · AndroidManifest.xml（关键坑）

转屏会让 Activity 走一遍销毁重建，WebView 随之重载页面 —— 时钟设置、当前页面、跑步状态全丢。必须声明自己处理配置变更：

```xml
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density"
    android:exported="true">
```

`configChanges` 命中后，系统只回调 `onConfigurationChanged`，WebView 不重建；页面侧靠已有的 `resize` / `orientationchange` 监听重新排版即可。

---

## 3. 补丁 · 页面侧（aurora_clock.html）

替换原有 `toggleImmerse()` 与 `applyOrient()`（原文件 968–986 行）。核心原则：**有桥走桥，没桥回落到原 CSS 方案**。

```js
/* ---------- 全屏 / 方向：优先壳桥，失败回落 CSS ---------- */
function B(){ return window.AndroidApp || null; }
function hasBridgeFn(n){ var b=B(); return !!(b && typeof b[n] === 'function'); }
var _lastOrient = null;

function toggleImmerse(){
  var on = !document.body.classList.contains('immerse');
  var native = hasBridgeFn('enterFullscreen');
  if (native) { try { B().enterFullscreen(!!on); } catch(e){} }   // 必须传布尔，传字符串会抛
  applyImmerse(on, native);
}

function applyImmerse(on, native){
  document.body.classList.toggle('immerse', !!on);
  var b = $('#btnImmerse'); if (b) b.classList.toggle('on', !!on);
  setTimeout(function(){ FX.resize(); }, 260);
  toast(on ? (native ? '已进入沉浸全屏（点 ⛶ 退出）' : '已进入沉浸模式（点 ⛶ 退出）')
           : '已退出全屏');
}

function cycleOrient(){
  S.orient = S.orient==='auto' ? 'landscape' : (S.orient==='landscape' ? 'portrait' : 'auto');
  applyOrient(true); saveState();
  var sel = $('#oOrient'); if (sel) sel.value = S.orient;
  toast('屏幕方向：'+(S.orient==='auto' ? '跟随系统' : S.orient==='landscape' ? '横屏' : '竖屏'));
}

function applyOrient(notify){
  var vw = window.innerWidth, vh = window.innerHeight;
  var natural = (vw > vh) ? 'landscape' : 'portrait';
  var want = (S.orient==='auto') ? natural : S.orient;
  var st = $('#stage'); if (!st) return;

  /* ① 有 setOrientation 桥 → 交系统真转屏，页面不再自己转 */
  if (hasBridgeFn('setOrientation')) {
    if (_lastOrient !== S.orient) {                 // 防抖：避免与 orientationchange 互踢成死循环
      _lastOrient = S.orient;
      try { B().setOrientation(S.orient==='auto' ? 'auto' : S.orient); } catch(e){}
    }
    st.classList.remove('rot');
    st.style.width = ''; st.style.height = ''; st.style.transform = '';
    document.body.classList.toggle('landscape', want==='landscape');
    setTimeout(function(){ FX.resize(); }, 320);
    if (notify) toast(want==='landscape' ? '横屏（系统转屏）' : '竖屏（系统转屏）');
    return;
  }

  /* ② 无桥回落：原 CSS 伪横屏（画面转 90°） */
  var rot = false, deg = 0;
  if (want==='landscape' && natural==='portrait')       { rot = true; deg =  90; }
  else if (want==='portrait' && natural==='landscape')  { rot = true; deg = -90; }
  if (rot) {
    st.classList.add('rot');
    st.style.width = vh+'px'; st.style.height = vw+'px';
    st.style.transform = 'translate(-50%,-50%) rotate('+deg+'deg)';
  } else {
    st.classList.remove('rot');
    st.style.width = ''; st.style.height = ''; st.style.transform = '';
  }
  document.body.classList.toggle('landscape', want==='landscape');
  setTimeout(function(){ FX.resize(); }, 180);
  if (notify) toast(want==='landscape' ? '横屏布局（页面内）' : '竖屏布局（页面内）');
}

/* ③ 可选：启动时与壳的真实沉浸态对齐（防止页面状态与系统不一致） */
(function syncShellFs(){
  if (!hasBridgeFn('isFullscreen')) return;
  try {
    if (B().isFullscreen() && !document.body.classList.contains('immerse')) applyImmerse(true, true);
  } catch(e){}
})();
```

要点：
- `enterFullscreen(!!on)` 必须传**布尔**，传 `"true"` 字符串桥会抛类型异常。
- `_lastOrient` 防抖是必须的：`setOrientation` 会触发系统 `orientationchange` → 已有监听回掉 `applyOrient(false)`，无防抖会来回踢。
- 页面侧无需删掉 `body.immerse` / `body.landscape` 的 CSS —— 真全屏时它们只负责隐藏页面自己的控件，与系统栏互不干扰。

---

## 4. 构建与验证

```bat
cd apk_shell
.\gradlew.bat assembleRelease                              :: ① 重编壳
python tools\gen_meta.py build\outputs\apk\release\apk_shell-release-unsigned.apk   :: ② 必跑，否则导不出包
Copy-Item build\outputs\apk\release\apk_shell-release-unsigned.apk ..\src\main\assets\apk_shell\base.apk -Force
                                                           :: ③ 替换主项目模板
:: ④ 回主项目 assembleDebug → 装机
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
| 8 | `samples/engine_demo/index.html` | 自测台能枚举出 43 个桥方法 |

## 6. 已知限制

- **WebView 里的 `requestFullscreen()` 依旧无效**（需壳实现 `onShowCustomView`），所以全屏只能走本补丁的桥，纯网页层做不到。
- 沉浸只作用于本 Activity 窗口；`openBrowser` / 分享目标等系统页面不受控。
- `setOrientation('auto')` 在用户系统锁定旋转时仍会跟随系统锁，这是 Android 行为，不是 bug。
- 若壳有 `ForegroundBridgeService` 弹窗类 UI，进入沉浸后其自身窗口不受影响。

## 7. 文档同步（建议）

- `APK_SOURCE_GUIDE.md`：第三节桥清单 40 → **43**，顶部 `shell_version=v8 / bridge_api=4`。
- `HTML_DESIGN_RULES.md` 第四节：补全屏/方向两个常用能力。
- `ExportApkTool` 描述里的方法数同步。
