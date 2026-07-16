# SmartQuiz 项目资源调用方式重构指南

## 概述

本项目已完成系统性重构，将硬编码资源调用方式修改为通过系统资源接口动态获取。重构涵盖以下方面：

1. **字体样式** - 通过 `FontResourceProvider` 动态获取
2. **文本颜色** - 通过 `ColorResourceProvider` 动态获取
3. **提示音效** - 通过 `SoundResourceProvider` 动态获取
4. **服务框架配置** - 通过 `ConfigResourceProvider` 动态获取
5. **应用权限管理** - 通过 `PermissionResourceProvider` 动态获取
6. **WebView组件** - 通过 `WebViewResourceProvider` 动态获取
7. **文件渲染引擎** - 通过 `FileResourceProvider` 动态获取
8. **文件导入导出功能** - 通过 `FileResourceProvider` 动态获取

## 架构设计

### 核心组件

```
com.oilquiz.app.resource/
├── AppResourceManager.java          # 统一资源管理入口
├── ResourceManager.java             # 基础资源管理器
├── FontResourceProvider.java        # 字体资源提供者
├── ColorResourceProvider.java       # 颜色资源提供者
├── SoundResourceProvider.java       # 音效资源提供者
├── ConfigResourceProvider.java      # 配置资源提供者
├── PermissionResourceProvider.java  # 权限资源提供者
├── WebViewResourceProvider.java     # WebView资源提供者
└── FileResourceProvider.java        # 文件资源提供者
```

## 使用指南

### 1. 统一入口 - AppResourceManager

```java
// 获取实例
AppResourceManager resources = AppResourceManager.getInstance(context);

// 字体资源
Typeface font = resources.getFont("title");
Typeface defaultFont = resources.getDefaultFont();

// 颜色资源
int primaryColor = resources.getPrimaryColor();
int customColor = resources.getColor("success");

// 音效资源
resources.playSuccess();
resources.playError();
resources.playSound("custom_sound");

// 配置资源
String appName = resources.getConfigString("app_name", "SmartQuiz");
boolean soundEnabled = resources.getConfigBoolean("enable_sound_effects", true);

// 权限检查
if (resources.hasStoragePermission()) {
    // 执行文件操作
}

// 文件操作
File exportDir = resources.files().getExportDirectory();
```

### 2. 字体资源使用

```java
FontResourceProvider fonts = FontResourceProvider.getInstance(context);

// 获取特定字体
Typeface titleFont = fonts.getFont("title");
Typeface bodyFont = fonts.getFont("body");
Typeface chineseFont = fonts.getChineseFont();

// 在TextView中应用
textView.setTypeface(fonts.getTitleFont());

// 注册自定义字体
fonts.registerFont("custom_font", R.font.custom_font);
```

### 3. 颜色资源使用

```java
ColorResourceProvider colors = ColorResourceProvider.getInstance(context);

// 获取颜色
int primary = colors.getPrimaryColor();
int background = colors.getBackgroundColor();
int textColor = colors.getTextPrimaryColor();

// 获取难度颜色
int easyColor = colors.getDifficultyColor("easy");

// 调整透明度
int semiTransparent = colors.adjustAlpha(primary, 128);

// 混合颜色
int blended = colors.blendColors(color1, color2, 0.5f);
```

### 4. 音效资源使用

```java
SoundResourceProvider sounds = SoundResourceProvider.getInstance(context);

// 播放内置音效
sounds.playSuccess();
sounds.playError();
sounds.playWarning();
sounds.playClick();
sounds.playCorrect();
sounds.playWrong();

// 播放自定义音效
sounds.playSound("custom_sound");

// 导入导出相关
sounds.playImportStart();
sounds.playImportComplete();
sounds.playExportStart();
sounds.playExportComplete();

// 预加载所有音效
sounds.preloadAllSounds();
```

### 5. 配置资源使用

```java
ConfigResourceProvider config = ConfigResourceProvider.getInstance(context);

// 获取配置
String quizMode = config.getDefaultQuizMode();
int questionCount = config.getDefaultQuestionCount();
boolean autoBackup = config.isAutoBackupEnabled();

// 设置配置
config.setConfig("custom_key", "value");
config.setConfig("custom_number", 42);

// 监听配置变更
config.setConfigChangeListener((key, value) -> {
    Log.d("Config", "Changed: " + key + " = " + value);
});
```

### 6. 权限资源使用

```java
PermissionResourceProvider permissions = PermissionResourceProvider.getInstance(context);

// 检查权限
if (permissions.hasStoragePermission()) {
    // 执行操作
}

// 请求权限
permissions.requestStoragePermission(activity);
permissions.requestCameraPermission(activity);

// 批量请求
permissions.requestPermissionGroup(activity, "storage");

// 处理权限结果
@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    permissionProvider.onRequestPermissionsResult(requestCode, permissions, grantResults);
}
```

### 7. WebView资源使用

```java
WebViewResourceProvider webViewRes = WebViewResourceProvider.getInstance(context);

// 配置WebView
WebView webView = new WebView(context);
webViewRes.configureWebSettings(webView.getSettings());

// 加载HTML
String html = webViewRes.loadHtmlFromAssets("about.html");
webView.loadData(html, "text/html", "UTF-8");

// 注册JS接口
webViewRes.registerJavascriptInterface("Android", new JsInterface());
webViewRes.applyJavascriptInterfaces(webView);
```

### 8. 文件资源使用

```java
FileResourceProvider files = FileResourceProvider.getInstance(context);

// 获取目录
File importDir = files.getImportDirectory();
File exportDir = files.getExportDirectory();

// 创建文件
File tempFile = files.createTempFile("export", ".xlsx");
File exportFile = files.createExportFile("questions.xlsx");

// 文件操作
files.copyFile(sourceFile, destFile);
files.copyFromUri(uri, destFile);

// 获取文件信息
String mimeType = files.getMimeType(fileName);
boolean isSupported = files.isSupportedImportFormat(fileName);
String formattedSize = files.getFormattedFileSize(fileSize);
```

## 重构迁移指南

### 旧代码 → 新代码

#### 字体样式
```java
// 旧方式（硬编码）
textView.setTypeface(Typeface.DEFAULT_BOLD);

// 新方式（资源接口）
textView.setTypeface(AppResourceManager.getInstance(context).getFont("title_bold"));
```

#### 文本颜色
```java
// 旧方式（硬编码）
view.setBackgroundColor(Color.parseColor("#3B82F6"));

// 新方式（资源接口）
view.setBackgroundColor(AppResourceManager.getInstance(context).getPrimaryColor());
```

#### 提示音效
```java
// 旧方式（硬编码）
MediaPlayer.create(context, R.raw.beep).start();

// 新方式（资源接口）
AppResourceManager.getInstance(context).playSuccess();
```

#### 权限检查
```java
// 旧方式（硬编码）
if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) 
        == PackageManager.PERMISSION_GRANTED) {
    // ...
}

// 新方式（资源接口）
if (AppResourceManager.getInstance(context).hasStoragePermission()) {
    // ...
}
```

## 性能优化

### 1. 资源缓存
- 字体资源：自动缓存，避免重复加载
- 颜色资源：缓存解析后的颜色值
- 音效资源：预加载常用音效

### 2. 延迟加载
- 配置资源：按需从SharedPreferences加载
- 权限状态：实时检查，不缓存

### 3. 内存管理
```java
// 清除缓存
AppResourceManager.getInstance(context).clearAllCaches();

// 释放资源
AppResourceManager.getInstance(context).releaseAll();
```

## 测试验证

### 1. 单元测试
```java
@Test
public void testFontResourceProvider() {
    FontResourceProvider provider = FontResourceProvider.getInstance(context);
    assertNotNull(provider.getFont("default"));
    assertTrue(provider.isFontAvailable("title"));
}

@Test
public void testColorResourceProvider() {
    ColorResourceProvider provider = ColorResourceProvider.getInstance(context);
    assertNotEquals(0, provider.getPrimaryColor());
    assertTrue(provider.isColorAvailable("success"));
}
```

### 2. 集成测试
- 测试所有资源提供者的初始化
- 测试资源变更监听
- 测试配置持久化

### 3. 性能测试
- 测试资源加载时间
- 测试内存占用
- 测试缓存命中率

## 最佳实践

1. **始终使用 AppResourceManager 作为入口**
   - 保持代码一致性
   - 便于后续维护和扩展

2. **避免在循环中频繁获取资源**
   - 在循环外部获取资源引用
   - 利用缓存机制

3. **正确处理权限请求**
   - 在Activity中处理回调
   - 提供用户友好的权限说明

4. **及时释放资源**
   - 在Activity onDestroy中释放
   - 避免内存泄漏

5. **使用配置监听器响应变更**
   - 主题变更时更新UI
   - 配置变更时刷新数据

## 兼容性说明

- **最低SDK**: 26 (Android 8.0)
- **目标SDK**: 34 (Android 14)
- **支持语言**: 中文、英文、繁体中文

## 后续优化建议

1. 添加更多字体资源支持
2. 实现主题动态切换
3. 添加更多音效资源
4. 优化文件操作性能
5. 添加资源使用统计

## 文档维护

本文档应与代码同步更新。如有变更，请及时更新此文档。

最后更新: 2026-03-29
