package com.oilquiz.app.ui.activity;
import android.app.WallpaperManager;
import android.graphics.drawable.Drawable;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.ThemeManager;
import com.oilquiz.app.theme.ThemePalette;
import com.oilquiz.app.theme.ThemePaletteProvider;
import com.oilquiz.app.theme.ThemePreset;
import com.oilquiz.app.theme.AppWallpaperManager;
import com.oilquiz.app.theme.ThemeSkin;
import com.oilquiz.app.theme.WallpaperStore;

import java.io.File;
import java.util.List;

import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;
/**
 * 主题设置页（重构版）：
 * <ul>
 *   <li>深浅模式三档：写入 ThemeManager 并由 AppCompat 自动重建生效；</li>
 *   <li>皮肤包：遍历 ThemeManager.SKINS 渲染（多品牌皮肤，切换重置为皮肤默认主色）；</li>
 *   <li>主题色：遍历当前皮肤预设色注册表渲染（新增预设色无需改本页）；</li>
 *   <li>自定义色：SeekBar 取色 + 顶部预览区实时联动（编辑预览），HCT 色板全局生效；</li>
 *   <li>壁纸取色：开关跟随系统壁纸自动生成主题（Android 12+ Material You）。</li>
 * </ul>
 */
public class ThemeActivity extends AppCompatActivity {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME = "current_theme";

    private RadioButton radioLight;
    private RadioButton radioDark;
    private RadioButton radioSystem;
    private RadioButton radioOled;

    /** 壁纸添加：PhotoPicker（系统相册/文件选择，免存储权限） */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickWallpaperLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) {
                    return;
                }
                File added = WallpaperStore.addFromUri(ThemeActivity.this, uri);
                Toast.makeText(ThemeActivity.this,
                        added != null ? "壁纸已添加，点击可设为系统壁纸" : "添加壁纸失败",
                        Toast.LENGTH_SHORT).show();
                showWallpaperManagerDialog();
            });

    private RadioButton rbWallpaperSystem;
    private RadioButton rbWallpaperLibrary;
    private RadioButton rbWallpaperOff;
    private androidx.appcompat.widget.SwitchCompat switchSystemDynamic;
    private LinearLayout linearLayoutThemeColors;
    private LinearLayout linearLayoutThemeSkins;
    private LinearLayout linearLayoutThemePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);
        // 2026-11-16：窗口背景显式铺成主题底色。全局壁纸模式下窗口背景是壁纸，
        // 页面之间会露出壁纸（OLED 纯黑档尤其明显：卡片是黑的、缝隙是壁纸，看着像没做）。
        // 本页是"设置"类页面，不跟壁纸，固定用主题背景色。
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                ThemeColors.get(this, R.color.background)));
        // 窗口背景会被全局「壁纸同步」覆盖（EdgeToEdgeHelper.syncWindowBackground 把窗口底设成壁纸），
        // 只设 window 背景不够 —— 内容根容器也必须是不透明的，否则卡片之间透出壁纸。
        View contentRoot = findViewById(android.R.id.content);
        if (contentRoot != null) {
            contentRoot.setBackgroundColor(ThemeColors.get(this, R.color.background));
        }

        radioLight = findViewById(R.id.radio_light);
        radioDark = findViewById(R.id.radio_dark);
        radioSystem = findViewById(R.id.radio_system);
        radioOled = findViewById(R.id.radio_oled);
        linearLayoutThemeColors = findViewById(R.id.linearLayoutThemeColors);
        linearLayoutThemeSkins = findViewById(R.id.linearLayoutThemeSkins);
        linearLayoutThemePreview = findViewById(R.id.linearLayoutThemePreview);

        // 返回箭头
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        loadCurrentThemeMode();
        setupSkins();
        setupThemeColors();
        // 初始渲染当前主题预览
        renderThemePreview(ThemeManager.getPalette(this, ThemeManager.isDarkTheme(this)));

        // 模式选择：卡片整卡可点 + 圆圈可点，都走同一选择逻辑。
        // RadioButton 嵌套在卡片内，RadioGroup 管不了互斥/监听，选中态由 selectModeCard 手动互斥。
        View.OnClickListener lightClick = v -> selectModeCard(radioLight, ThemeManager.THEME_LIGHT);
        View.OnClickListener darkClick = v -> selectModeCard(radioDark, ThemeManager.THEME_DARK);
        View.OnClickListener systemClick = v -> selectModeCard(radioSystem, ThemeManager.THEME_SYSTEM);
        View.OnClickListener oledClick = v -> selectModeCard(radioOled, ThemeManager.THEME_OLED);
        findViewById(R.id.card_mode_light).setOnClickListener(lightClick);
        findViewById(R.id.card_mode_dark).setOnClickListener(darkClick);
        findViewById(R.id.card_mode_system).setOnClickListener(systemClick);
        findViewById(R.id.card_mode_oled).setOnClickListener(oledClick);
        radioLight.setOnClickListener(lightClick);
        radioDark.setOnClickListener(darkClick);
        radioSystem.setOnClickListener(systemClick);
        radioOled.setOnClickListener(oledClick);

        findViewById(R.id.btn_save_theme).setOnClickListener(v -> finish());
        setupWallpaperModeEntry();
        setupSystemDynamicEntry();
        // 调试入口：直接弹自定义色板（adb 用 --ez open_custom_color true），
        // 省得为了点一个按钮先滚动+坐标点击。
        if (getIntent() != null && getIntent().getBooleanExtra("open_custom_color", false)) {
            linearLayoutThemeColors.post(this::showCustomColorDialog);
        }
    }

    /** 系统动态色开关：开启后控件配色由系统 Material You 接管（跟随系统壁纸），自定义色/预设色置灰 */
    private void setupSystemDynamicEntry() {
        switchSystemDynamic = findViewById(R.id.switchSystemDynamic);
        switchSystemDynamic.setChecked(ThemeManager.isSystemDynamicColor(this));
        switchSystemDynamic.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ThemeManager.setSystemDynamicColor(ThemeActivity.this, isChecked);
            Toast.makeText(ThemeActivity.this,
                    isChecked ? "已启用系统动态色，控件配色跟随系统壁纸" : "已关闭系统动态色，恢复自定义配色",
                    Toast.LENGTH_SHORT).show();
            recreate();
        });
        boolean dyn = ThemeManager.isSystemDynamicColor(this);
        View colorCard = findViewById(R.id.card_theme_color);
        View skinsCard = findViewById(R.id.card_theme_skin);
        if (skinsCard != null) {
            skinsCard.setEnabled(!dyn);
            skinsCard.setAlpha(dyn ? 0.4f : 1f);
        }
        if (colorCard != null) {
            colorCard.setEnabled(!dyn);
            colorCard.setAlpha(dyn ? 0.4f : 1f);
        }
    }

    /** 应用壁纸三档：跟随系统壁纸 / 使用壁纸库图片 / 关闭（手动互斥，同主题模式卡） */
    private void setupWallpaperModeEntry() {
        rbWallpaperSystem = findViewById(R.id.rb_wallpaper_system);
        rbWallpaperLibrary = findViewById(R.id.rb_wallpaper_library);
        rbWallpaperOff = findViewById(R.id.rb_wallpaper_off);
        int mode = AppWallpaperManager.getMode(this);
        rbWallpaperSystem.setChecked(mode == AppWallpaperManager.MODE_FOLLOW_SYSTEM);
        rbWallpaperLibrary.setChecked(mode == AppWallpaperManager.MODE_LIBRARY);
        rbWallpaperOff.setChecked(mode == AppWallpaperManager.MODE_OFF);
        View.OnClickListener l = v -> {
            int id = v.getId();
            int m = id == R.id.rb_wallpaper_system ? AppWallpaperManager.MODE_FOLLOW_SYSTEM
                    : id == R.id.rb_wallpaper_library ? AppWallpaperManager.MODE_LIBRARY
                    : AppWallpaperManager.MODE_OFF;
            if (AppWallpaperManager.getMode(this) == m) {
                return;
            }
            if (m == AppWallpaperManager.MODE_FOLLOW_SYSTEM && !ensureAllFilesAccess()) {
                return; // 未开启「所有文件访问」权限：弹窗引导，本次不切换
            }
            AppWallpaperManager.setMode(this, m);
            rbWallpaperSystem.setChecked(m == AppWallpaperManager.MODE_FOLLOW_SYSTEM);
            rbWallpaperLibrary.setChecked(m == AppWallpaperManager.MODE_LIBRARY);
            rbWallpaperOff.setChecked(m == AppWallpaperManager.MODE_OFF);
            Toast.makeText(this, m == AppWallpaperManager.MODE_FOLLOW_SYSTEM ? getString(R.string.h_cfda29cd) : m == AppWallpaperManager.MODE_LIBRARY ? getString(R.string.h_d3b2836a) : getString(R.string.h_a5e67109), Toast.LENGTH_SHORT).show();
            recreate();
        };
        rbWallpaperSystem.setOnClickListener(l);
        rbWallpaperLibrary.setOnClickListener(l);
        rbWallpaperOff.setOnClickListener(l);
        findViewById(R.id.row_wallpaper_library).setOnClickListener(v -> showWallpaperManagerDialog());
        fillWallpaperPicker();
    }

    /**
     * 检测「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE）。
     * 小米 HyperOS 读系统壁纸需要该权限；未开启时弹窗引导去系统设置页开启。
     * @return true=已开启（可正常使用跟随系统壁纸）
     */
    private boolean ensureAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) {
            return true;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_a543ed9a))
                .setMessage(getString(R.string.h_9e455d55))
                .setPositiveButton(getString(R.string.h_5e213ddb), (d, w) -> {
                    try {
                        android.content.Intent it = new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(it);
                    } catch (Throwable ignored) {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored2) {
                            android.widget.Toast.makeText(this, getString(R.string.h_64c5386f),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(getString(R.string.h_625fb26b), null)
                .show();
        return false;
    }

    /** 系统壁纸缩略图格：点击=跟随系统壁纸（tag 化，供壁纸变化时动态刷新缩略图） */
    private void addSystemWallpaperCell(LinearLayout container) {
        LinearLayout cell = new LinearLayout(this);
        cell.setTag("cell_system_wallpaper");
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT);
        cellLp.setMargins(dp(2), dp(2), dp(6), dp(2));
        cell.setLayoutParams(cellLp);

        ImageView thumb = new ImageView(this);
        thumb.setTag("thumb_system_wallpaper");
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(72), dp(96));
        thumb.setLayoutParams(thumbLp);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean loaded = false;
        try {
            // 文件直读优先（getWallpaperFile/binder），避免 WallpaperManager.getDrawable 缓存返回旧壁纸
            Drawable sys = AppWallpaperManager.readSystemWallpaperDrawable(this);
            if (sys != null) {
                Bitmap sysBmp = Bitmap.createBitmap(dp(72), dp(96), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(sysBmp);
                sys.setBounds(0, 0, dp(72), dp(96));
                sys.draw(cv);
                thumb.setImageBitmap(sysBmp);
                loaded = true;
            }
        } catch (Exception ignored) {
        }
        if (!loaded) {
            thumb.setImageResource(R.drawable.ic_home_theme);
        }
        thumb.setBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));
        GradientDrawable thumbClip = new GradientDrawable();
        thumbClip.setCornerRadius(dp(8));
        thumb.setClipToOutline(true);
        cell.addView(thumb);

        boolean isCurrent = AppWallpaperManager.getMode(this) == AppWallpaperManager.MODE_FOLLOW_SYSTEM;
        TextView name = new TextView(this);
        name.setTag("name_system_wallpaper");
        name.setText(isCurrent ? getString(R.string.h_e640477f) : getString(R.string.h_ec41a592));
        name.setTextSize(10);
        name.setMaxLines(1);
        name.setGravity(Gravity.CENTER);
        name.setTextColor(isCurrent
                ? ThemeColors.attr(this, R.attr.colorPrimary)
                : ThemeColors.attr(this, R.attr.colorOnSurface));
        name.setPadding(0, dp(2), 0, 0);
        cell.addView(name);

        cell.setOnClickListener(v -> {
            AppWallpaperManager.setMode(ThemeActivity.this, AppWallpaperManager.MODE_FOLLOW_SYSTEM);
            Toast.makeText(ThemeActivity.this, getString(R.string.h_cfda29cd), Toast.LENGTH_SHORT).show();
            recreate();
        });
        container.addView(cell, 0);
    }

    /**
     * 动态探测系统壁纸：重新读取系统壁纸并刷新「系统壁纸」格子缩略图与"当前"态。
     * 驱动源：① onResume（从系统设置换壁纸返回立即更新）② OnColorsChangedListener（Android 12+ 壁纸颜色事件）
     * ③ ACTION_WALLPAPER_CHANGED 广播（小米等 ROM 颜色事件不可靠时的兜底通道）。
     */
    private void refreshSystemWallpaperCell() {
        try {
            LinearLayout container = findViewById(R.id.wallpaper_picker_container);
            if (container == null) {
                return;
            }
            LinearLayout cell = container.findViewWithTag("cell_system_wallpaper");
            if (cell == null) {
                return;
            }
            ImageView thumb = cell.findViewWithTag("thumb_system_wallpaper");
            TextView name = cell.findViewWithTag("name_system_wallpaper");
            boolean loaded = false;
            try {
                // 文件直读优先（getWallpaperFile/binder），避免 WallpaperManager.getDrawable 缓存返回旧壁纸
                Drawable sys = AppWallpaperManager.readSystemWallpaperDrawable(this);
                if (sys != null && thumb != null) {
                    Bitmap bmp = Bitmap.createBitmap(dp(72), dp(96), Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                    sys.setBounds(0, 0, dp(72), dp(96));
                    sys.draw(cv);
                    thumb.setImageBitmap(bmp);
                    loaded = true;
                }
            } catch (Throwable ignored) {
            }
            if (!loaded && thumb != null) {
                thumb.setImageResource(R.drawable.ic_home_theme);
            }
            boolean isCurrent = AppWallpaperManager.getMode(this) == AppWallpaperManager.MODE_FOLLOW_SYSTEM;
            if (name != null) {
                String label = isCurrent ? getString(R.string.h_e640477f) : getString(R.string.h_ec41a592);
                if (isCurrent && AppWallpaperManager.isLiveWallpaper(this)) {
                    // 动态壁纸无静态壁纸文件：系统不提供当前帧，页面回退内置壁纸，此处明确标记
                    label += " · 动态壁纸";
                }
                name.setText(label);
                name.setTextColor(isCurrent
                        ? ThemeColors.attr(this, R.attr.colorPrimary)
                        : ThemeColors.attr(this, R.attr.colorOnSurface));
            }
        } catch (Throwable t) {
            android.util.Log.w("ThemeWallpaper", "refresh system wallpaper cell failed: " + t.getMessage());
        }
    }

    private android.app.WallpaperManager.OnColorsChangedListener colorsListener;
    private android.content.BroadcastReceiver wallpaperReceiver;

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统壁纸选择/系统设置返回：立即动态探测系统壁纸并刷新缩略图
        refreshSystemWallpaperCell();
        // 延迟再刷一次：覆盖系统壁纸服务文件落盘竞态（立即读取可能仍在切换窗口）
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::refreshSystemWallpaperCell, 600);
        registerWallpaperListeners();
    }

    @Override
    protected void onDestroy() {
        unregisterWallpaperListeners();
        super.onDestroy();
    }

    /** 注册壁纸变化双通道监听：颜色事件（Android 12+）+ ACTION_WALLPAPER_CHANGED 广播（全版本兜底） */
    @SuppressWarnings("deprecation") // 壁纸变更广播仍是平台唯一可靠信号
    private void registerWallpaperListeners() {
        try {
            if (colorsListener == null) {
                colorsListener = (listener, which) ->
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::refreshSystemWallpaperCell);
                WallpaperManager wm = WallpaperManager.getInstance(this);
                wm.addOnColorsChangedListener(colorsListener,
                        new android.os.Handler(android.os.Looper.getMainLooper()));
            }
            if (wallpaperReceiver == null) {
                wallpaperReceiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context c, android.content.Intent intent) {
                        refreshSystemWallpaperCell();
                    }
                };
                android.content.IntentFilter filter =
                        new android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(wallpaperReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(wallpaperReceiver, filter);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("ThemeWallpaper", "register wallpaper listeners failed: " + t.getMessage());
        }
    }

    /** 注销壁纸监听（防泄漏；Activity 重建/销毁时调用） */
    private void unregisterWallpaperListeners() {
        try {
            if (colorsListener != null) {
                WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener);
                colorsListener = null;
            }
            if (wallpaperReceiver != null) {
                unregisterReceiver(wallpaperReceiver);
                wallpaperReceiver = null;
            }
        } catch (Throwable t) {
            android.util.Log.w("ThemeWallpaper", "unregister wallpaper listeners failed: " + t.getMessage());
        }
    }

    /** 内嵌壁纸选择条：背景图直接点选应用 */
    private void fillWallpaperPicker() {
        LinearLayout container = findViewById(R.id.wallpaper_picker_container);
        if (container == null) {
            return;
        }
        container.removeAllViews();
        addSystemWallpaperCell(container);
        List<File> wallpapers = WallpaperStore.listAll(this);
        for (final File w : wallpapers) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT);
            cellLp.setMargins(dp(2), dp(2), dp(6), dp(2));
            cell.setLayoutParams(cellLp);

            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(72), dp(96));
            thumb.setLayoutParams(thumbLp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(w.getAbsolutePath(), opt);
            opt.inJustDecodeBounds = false;
            int sample = 1;
            while (opt.outWidth / sample > 480) {
                sample *= 2;
            }
            opt.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(w.getAbsolutePath(), opt);
            if (bmp != null) {
                thumb.setImageBitmap(bmp);
            }
            thumb.setBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));
            GradientDrawable thumbClip = new GradientDrawable();
            thumbClip.setCornerRadius(dp(8));
            thumb.setClipToOutline(true);
            cell.addView(thumb);

            TextView name = new TextView(this);
            name.setText(WallpaperStore.displayName(w));
            name.setTextSize(10);
            name.setMaxLines(1);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(ThemeColors.attr(this, R.attr.colorOnSurface));
            name.setPadding(0, dp(2), 0, 0);
            cell.addView(name);

            cell.setOnClickListener(v -> {
                AppWallpaperManager.setLibraryPath(ThemeActivity.this, w.getAbsolutePath());
                AppWallpaperManager.setMode(ThemeActivity.this, AppWallpaperManager.MODE_LIBRARY);
                Toast.makeText(ThemeActivity.this, getString(R.string.h_a99c80c2), Toast.LENGTH_SHORT).show();
                recreate();
            });
            cell.setOnLongClickListener(v -> {
                if (WallpaperStore.isBuiltin(w)) {
                    Toast.makeText(ThemeActivity.this, getString(R.string.h_2b1acef5), Toast.LENGTH_SHORT).show();
                } else if (WallpaperStore.delete(ThemeActivity.this, w)) {
                    Toast.makeText(ThemeActivity.this, getString(R.string.h_89df9e25), Toast.LENGTH_SHORT).show();
                    fillWallpaperPicker();
                }
                return true;
            });
            container.addView(cell);
        }
    }

    /**
     * 壁纸库对话框：3 列网格展示壁纸库（内置渐变 + 用户添加）。
     * 点击=选为应用壁纸（模式切到「使用壁纸库图片」）；长按=删除（仅用户壁纸）；
     * 「添加壁纸」走系统 PhotoPicker，免存储权限。
     */
    private void showWallpaperManagerDialog() {
        final List<File> wallpapers = WallpaperStore.listAll(this);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setPadding(dp(16), dp(16), dp(16), 0);

        for (final File w : wallpapers) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            GridLayout.LayoutParams cellLp = new GridLayout.LayoutParams();
            cellLp.width = 0;
            cellLp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            cellLp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cellLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            cell.setLayoutParams(cellLp);

            // 缩略图（先解码尺寸采样，避免大图 OOM）
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(96));
            thumb.setLayoutParams(thumbLp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(w.getAbsolutePath(), opt);
            opt.inJustDecodeBounds = false;
            int sample = 1;
            while (opt.outWidth / sample > 480) {
                sample *= 2;
            }
            opt.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(w.getAbsolutePath(), opt);
            if (bmp != null) {
                thumb.setImageBitmap(bmp);
                thumb.setBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));
            } else {
                thumb.setBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));
            }
            GradientDrawable thumbClip = new GradientDrawable();
            thumbClip.setCornerRadius(dp(8));
            thumb.setClipToOutline(true);
            cell.addView(thumb);

            TextView name = new TextView(this);
            name.setText(WallpaperStore.displayName(w));
            name.setTextSize(11);
            name.setMaxLines(1);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(ThemeColors.attr(this, R.attr.colorOnSurface));
            name.setPadding(0, dp(4), 0, 0);
            cell.addView(name);

            cell.setOnClickListener(v -> {
                AppWallpaperManager.setLibraryPath(ThemeActivity.this, w.getAbsolutePath());
                AppWallpaperManager.setMode(ThemeActivity.this, AppWallpaperManager.MODE_LIBRARY);
                Toast.makeText(ThemeActivity.this, getString(R.string.h_a99c80c2), Toast.LENGTH_SHORT).show();
                recreate();
            });
            cell.setOnLongClickListener(v -> {
                if (WallpaperStore.isBuiltin(w)) {
                    Toast.makeText(ThemeActivity.this, getString(R.string.h_2b1acef5), Toast.LENGTH_SHORT).show();
                } else if (WallpaperStore.delete(ThemeActivity.this, w)) {
                    Toast.makeText(ThemeActivity.this, getString(R.string.h_89df9e25), Toast.LENGTH_SHORT).show();
                    showWallpaperManagerDialog();
                }
                return true;
            });
            grid.addView(cell);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(grid);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_502eb6f3))
                .setView(scrollView)
                .setPositiveButton(getString(R.string.h_155a119b), (d, which) -> pickWallpaperLauncher.launch(
                        new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build()))
                .setNegativeButton(getString(R.string.h_b15d9127), null)
                .show();
    }

    /**
     * 模式单选核心：手动互斥三颗圆圈 + 写入模式 + AppCompat 自动重建全局生效。
     * 同时被整卡点击和圆圈点击调用。
     */
    private void selectModeCard(RadioButton target, int mode) {
        radioLight.setChecked(target == radioLight);
        radioDark.setChecked(target == radioDark);
        radioSystem.setChecked(target == radioSystem);
        if (radioOled != null) radioOled.setChecked(target == radioOled);
        if (mode != ThemeManager.getMode(this)) {
            ThemeManager.setMode(this, mode);
            Toast.makeText(this,
                    mode == ThemeManager.THEME_OLED ? "已切换到纯黑模式（OLED）"
                            : mode == ThemeManager.THEME_DARK ? "已切换到深色模式"
                            : mode == ThemeManager.THEME_LIGHT ? "已切换到浅色模式"
                            : "已跟随系统主题",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCurrentThemeMode() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt(KEY_THEME, ThemeManager.THEME_SYSTEM);
        if (themeMode == ThemeManager.THEME_OLED) {
            radioOled.setChecked(true);
        } else if (themeMode == ThemeManager.THEME_DARK) {
            radioDark.setChecked(true);
        } else if (themeMode == ThemeManager.THEME_SYSTEM) {
            radioSystem.setChecked(true);
        } else {
            radioLight.setChecked(true);
        }
    }


    /**
     * 渲染主题预览区：用给定色板实时绘制一组模拟组件（顶条/高亮块/按钮/选中项/文本），
     * 用于"编辑预览"——取色拖动时即时反馈整套配色效果。
     */
    private void renderThemePreview(ThemePalette p) {
        linearLayoutThemePreview.removeAllViews();
        // 2026-11-16：预览区自己裁圆角，否则顶条/高亮块的直角会顶破外层卡片（截图里的"破角"）。
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_LG));
        // 纯黑档下卡片和预览区都是黑的，加一圈 1dp 描边才看得出边界
        previewBg.setStroke(Tokens.space(this, Tokens.SPACE_HAIRLINE) > 0 ? dp(1) : 1, p.outline);
        previewBg.setColor(p.surface);
        linearLayoutThemePreview.setClipToOutline(true);

        // 模拟顶条（Toolbar）：primary 底 + onPrimary 字
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Tokens.space(this, Tokens.SPACE_XL), dp(14), Tokens.space(this, Tokens.SPACE_XL), dp(14));
        topBar.setBackgroundColor(p.primary);
        TextView backArrow = new TextView(this);
        backArrow.setText("‹");
        backArrow.setTextSize(26);
        backArrow.setTextColor(p.onPrimary);
        backArrow.setPadding(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        topBar.addView(backArrow);
        TextView barTitle = new TextView(this);
        barTitle.setText(getString(R.string.h_57b7276a));
        barTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY_LG));
        barTitle.setTextColor(p.onPrimary);
        topBar.addView(barTitle);
        linearLayoutThemePreview.addView(topBar);

        // 内容区：surface 底
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        body.setBackgroundColor(p.surface);
        linearLayoutThemePreview.addView(body);

        // 标题 + 次要文本
        TextView title = new TextView(this);
        title.setText(getString(R.string.h_841bfb11));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY_LG));
        title.setTextColor(p.onSurface);
        body.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.h_ead71ec7));
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_LABEL));
        subtitle.setTextColor(p.onSurfaceVariant);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, Tokens.space(this, Tokens.SPACE_HAIRLINE), 0, Tokens.space(this, Tokens.SPACE_LG));
        subtitle.setLayoutParams(subLp);
        body.addView(subtitle);

        // 高亮块：primaryContainer
        TextView highlight = new TextView(this);
        highlight.setText(getString(R.string.h_5464581f));
        highlight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY));
        highlight.setGravity(Gravity.CENTER_VERTICAL);
        highlight.setPadding(Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_LG));
        highlight.setBackgroundColor(p.primaryContainer);
        highlight.setTextColor(p.onPrimaryContainer);
        body.addView(highlight);

        // 按钮行：实心 primary + 描边 primary
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, Tokens.space(this, Tokens.SPACE_LG), 0, Tokens.space(this, Tokens.SPACE_LG));
        body.addView(buttonRow);

        TextView solidBtn = new TextView(this);
        solidBtn.setText(getString(R.string.h_d9113e34));
        solidBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY));
        solidBtn.setGravity(Gravity.CENTER);
        solidBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        solidBtn.setBackgroundColor(p.primary);
        solidBtn.setTextColor(p.onPrimary);
        LinearLayout.LayoutParams solidLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        solidLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        solidBtn.setLayoutParams(solidLp);
        buttonRow.addView(solidBtn);

        TextView outlineBtn = new TextView(this);
        outlineBtn.setText(getString(R.string.h_21e2f1a9));
        outlineBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY));
        outlineBtn.setGravity(Gravity.CENTER);
        outlineBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        outlineBtn.setTextColor(p.primary);
        GradientDrawable outlineBg = new GradientDrawable();
        outlineBg.setStroke(dp(1), p.primary);
        outlineBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_XS));
        outlineBtn.setBackground(outlineBg);
        buttonRow.addView(outlineBtn);

        // 选中项行：primary 圆点 + onSurface 文本
        LinearLayout selectedRow = new LinearLayout(this);
        selectedRow.setOrientation(LinearLayout.HORIZONTAL);
        selectedRow.setGravity(Gravity.CENTER_VERTICAL);
        selectedRow.setPadding(0, Tokens.space(this, Tokens.SPACE_XS), 0, Tokens.space(this, Tokens.SPACE_XS));
        body.addView(selectedRow);

        View dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_MD), 0);
        dot.setLayoutParams(dotLp);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(p.primary);
        dot.setBackground(dotBg);
        selectedRow.addView(dot);

        TextView selectedText = new TextView(this);
        selectedText.setText(getString(R.string.h_2f9d9bdd));
        selectedText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY));
        selectedText.setTextColor(p.onSurface);
        selectedRow.addView(selectedText);

        // 输入框占位：outline 描边
        TextView inputPlaceholder = new TextView(this);
        inputPlaceholder.setText(getString(R.string.h_55d569d2));
        inputPlaceholder.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_BODY));
        inputPlaceholder.setPadding(Tokens.space(this, Tokens.SPACE_LG), dp(10), Tokens.space(this, Tokens.SPACE_LG), dp(10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setStroke(dp(1), p.outline);
        inputBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_SM));
        inputPlaceholder.setBackground(inputBg);
        inputPlaceholder.setTextColor(p.onSurfaceVariant);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, Tokens.space(this, Tokens.SPACE_LG), 0, 0);
        inputPlaceholder.setLayoutParams(inputLp);
        body.addView(inputPlaceholder);

        // ===== 扩展控件覆盖：开关 / 单选 / 复选 / Chip / 进度条 / Tab（全部取自 HCT 色板）=====

        // 开关（MaterialSwitch 模拟：track=primary + thumb=onPrimary）
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, Tokens.space(this, Tokens.SPACE_LG), 0, Tokens.space(this, Tokens.SPACE_XS));
        body.addView(switchRow);
        TextView switchLabel = new TextView(this);
        switchLabel.setText(getString(R.string.h_d38b7fc2));
        switchLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_LABEL));
        switchLabel.setTextColor(p.onSurface);
        switchLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        switchRow.addView(switchLabel);
        android.widget.FrameLayout swTrack = new android.widget.FrameLayout(this);
        swTrack.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(24)));
        GradientDrawable swTrackBg = new GradientDrawable();
        swTrackBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_LG));
        swTrackBg.setColor(p.primary);
        swTrack.setBackground(swTrackBg);
        View swThumb = new View(this);
        android.widget.FrameLayout.LayoutParams swThumbLp = new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL | Gravity.END);
        swThumbLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_HAIRLINE), 0);
        swThumb.setLayoutParams(swThumbLp);
        GradientDrawable swThumbBg = new GradientDrawable();
        swThumbBg.setShape(GradientDrawable.OVAL);
        swThumbBg.setColor(p.onPrimary);
        swThumb.setBackground(swThumbBg);
        swTrack.addView(swThumb);
        switchRow.addView(swTrack);

        // 单选（选中：外环 primary + 中心实心）
        LinearLayout radioRow = new LinearLayout(this);
        radioRow.setOrientation(LinearLayout.HORIZONTAL);
        radioRow.setGravity(Gravity.CENTER_VERTICAL);
        radioRow.setPadding(0, Tokens.space(this, Tokens.SPACE_MD), 0, Tokens.space(this, Tokens.SPACE_XS));
        body.addView(radioRow);
        android.widget.FrameLayout radioOn = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams radioOnLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        radioOnLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_MD), 0);
        radioOn.setLayoutParams(radioOnLp);
        GradientDrawable radioOuter = new GradientDrawable();
        radioOuter.setShape(GradientDrawable.OVAL);
        radioOuter.setStroke(Tokens.space(this, Tokens.SPACE_HAIRLINE), p.primary);
        radioOuter.setColor(p.surface);
        radioOn.setBackground(radioOuter);
        View radioDot = new View(this);
        android.widget.FrameLayout.LayoutParams radioDotLp = new android.widget.FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER);
        radioDot.setLayoutParams(radioDotLp);
        GradientDrawable radioDotBg = new GradientDrawable();
        radioDotBg.setShape(GradientDrawable.OVAL);
        radioDotBg.setColor(p.primary);
        radioDot.setBackground(radioDotBg);
        radioOn.addView(radioDot);
        radioRow.addView(radioOn);
        TextView radioLabel = new TextView(this);
        radioLabel.setText(getString(R.string.h_f96dabbc));
        radioLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_LABEL));
        radioLabel.setTextColor(p.onSurface);
        radioRow.addView(radioLabel);

        // 复选（选中：primary 实心圆角方块 + 白勾）
        LinearLayout checkRow = new LinearLayout(this);
        checkRow.setOrientation(LinearLayout.HORIZONTAL);
        checkRow.setGravity(Gravity.CENTER_VERTICAL);
        checkRow.setPadding(0, Tokens.space(this, Tokens.SPACE_MD), 0, Tokens.space(this, Tokens.SPACE_XS));
        body.addView(checkRow);
        TextView checkBox = new TextView(this);
        checkBox.setText("✓");
        checkBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_FOOTNOTE));
        checkBox.setGravity(Gravity.CENTER);
        checkBox.setTextColor(p.onPrimary);
        LinearLayout.LayoutParams checkBoxLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        checkBoxLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_MD), 0);
        checkBox.setLayoutParams(checkBoxLp);
        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_XS));
        checkBg.setColor(p.primary);
        checkBox.setBackground(checkBg);
        checkRow.addView(checkBox);
        TextView checkLabel = new TextView(this);
        checkLabel.setText(getString(R.string.h_db98f889));
        checkLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_LABEL));
        checkLabel.setTextColor(p.onSurface);
        checkRow.addView(checkLabel);

        // Chip（选中：primary 底 + onPrimary 字）
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, Tokens.space(this, Tokens.SPACE_MD), 0, Tokens.space(this, Tokens.SPACE_XS));
        body.addView(chipRow);
        TextView chipOn = new TextView(this);
        chipOn.setText(getString(R.string.h_c1dc4db2));
        chipOn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_FOOTNOTE));
        chipOn.setPadding(Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_SM), Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_SM));
        LinearLayout.LayoutParams chipOnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipOnLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_MD), 0);
        chipOn.setLayoutParams(chipOnLp);
        GradientDrawable chipOnBg = new GradientDrawable();
        chipOnBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_XL));
        chipOnBg.setColor(p.primary);
        chipOn.setBackground(chipOnBg);
        chipOn.setTextColor(p.onPrimary);
        chipRow.addView(chipOn);
        TextView chipOff = new TextView(this);
        chipOff.setText(getString(R.string.h_8f119321));
        chipOff.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_FOOTNOTE));
        chipOff.setPadding(Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_SM), Tokens.space(this, Tokens.SPACE_LG), Tokens.space(this, Tokens.SPACE_SM));
        GradientDrawable chipOffBg = new GradientDrawable();
        chipOffBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_XL));
        chipOffBg.setColor(p.surfaceVariant);
        chipOff.setBackground(chipOffBg);
        chipOff.setTextColor(p.onSurfaceVariant);
        chipRow.addView(chipOff);

        // 进度条（track=surfaceVariant + 填充=primary 60%）
        android.widget.FrameLayout progressTrack = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Tokens.space(this, Tokens.SPACE_SM));
        progressLp.setMargins(0, dp(10), 0, Tokens.space(this, Tokens.SPACE_XS));
        progressTrack.setLayoutParams(progressLp);
        GradientDrawable progressTrackBg = new GradientDrawable();
        progressTrackBg.setCornerRadius(dp(3));
        progressTrackBg.setColor(p.surfaceVariant);
        progressTrack.setBackground(progressTrackBg);
        View progressFill = new View(this);
        android.widget.FrameLayout.LayoutParams progressFillLp = new android.widget.FrameLayout.LayoutParams(0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL);
        progressFillLp.width = 0;
        progressFill.setLayoutParams(progressFillLp);
        GradientDrawable progressFillBg = new GradientDrawable();
        progressFillBg.setCornerRadius(dp(3));
        progressFillBg.setColor(p.primary);
        progressFill.setBackground(progressFillBg);
        progressTrack.addView(progressFill);
        body.addView(progressTrack);
        // 用 post 设置 60% 宽度（等布局完成）
        progressTrack.post(() -> {
            android.widget.FrameLayout.LayoutParams fp = (android.widget.FrameLayout.LayoutParams) progressFill.getLayoutParams();
            fp.width = (int) (progressTrack.getWidth() * 0.6f);
            progressFill.setLayoutParams(fp);
        });

        // Tab（选中项 primary 字 + 底部指示条）
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, Tokens.space(this, Tokens.SPACE_MD), 0, 0);
        body.addView(tabRow);
        LinearLayout tabOne = new LinearLayout(this);
        tabOne.setOrientation(LinearLayout.VERTICAL);
        tabOne.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tabOneText = new TextView(this);
        tabOneText.setText(getString(R.string.h_3c34792e));
        tabOneText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_LABEL));
        tabOneText.setGravity(Gravity.CENTER);
        tabOneText.setTextColor(p.primary);
        tabOne.addView(tabOneText);
        View tabOneBar = new View(this);
        tabOneBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        GradientDrawable tabOneBarBg = new GradientDrawable();
        tabOneBarBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_HAIRLINE));
        tabOneBarBg.setColor(p.primary);
        tabOneBar.setBackground(tabOneBarBg);
        tabOne.addView(tabOneBar);
        tabRow.addView(tabOne);
        LinearLayout tabTwo = new LinearLayout(this);
        tabTwo.setOrientation(LinearLayout.VERTICAL);
        tabTwo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tabTwoText = new TextView(this);
        tabTwoText.setText(getString(R.string.h_b7abee9d));
        tabTwoText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Tokens.textSizePx(this, Tokens.TEXT_LABEL));
        tabTwoText.setGravity(Gravity.CENTER);
        tabTwoText.setTextColor(p.onSurfaceVariant);
        tabTwo.addView(tabTwoText);
        View tabTwoBar = new View(this);
        tabTwoBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        GradientDrawable tabTwoBarBg = new GradientDrawable();
        tabTwoBarBg.setCornerRadius(Tokens.space(this, Tokens.SPACE_HAIRLINE));
        tabTwoBarBg.setColor(p.surfaceVariant);
        tabTwoBar.setBackground(tabTwoBarBg);
        tabTwo.addView(tabTwoBar);
        tabRow.addView(tabTwo);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 渲染皮肤包列表（多品牌皮肤）：遍历 ThemeManager.SKINS，切换即重置默认主色并重建 */
    private void setupSkins() {
        final String currentSkinId = ThemeManager.getSkin(this).id;

        // 2026-11-16：与预设色行共用 buildPresetRow（token 化，去掉重复构建代码）。
        for (final ThemeSkin skin : ThemeManager.getSkins()) {
            com.google.android.material.card.MaterialCardView cardView =
                    buildPresetRow(skin.defaultArgb, skin.displayName,
                            skin.id.equals(currentSkinId));
            linearLayoutThemeSkins.addView(cardView);
View.OnClickListener skinClick = v -> {
                if (!skin.id.equals(ThemeManager.getSkin(ThemeActivity.this).id)) {
                    ThemeManager.setSkin(ThemeActivity.this, skin.id);
                    Toast.makeText(ThemeActivity.this, getString(R.string.h_ae4833a1) + skin.displayName + getString(R.string.h_c6fe31dc),
                            Toast.LENGTH_SHORT).show();
                    // 重建当前页：主色重置为该皮肤默认色，全局 overlay 立即生效
                    recreate();
                }
            };
            cardView.setOnClickListener(skinClick);
        }
    }

    /**
     * 预设色/皮肤通用行（2026-11-16）：色块 + 单选 + 名称，全部走 token。
     *
     * <p>原来 {@code setupThemeColors} 和 {@code setupSkins} 各写一份几乎一样的构建代码
     * （圆角 8、内边距 12、色块 40、字号 16 全是字面量），改一处忘另一处。
     * 抽成一个方法后两处共用，样式只有一份真值。</p>
     */
    private com.google.android.material.card.MaterialCardView buildPresetRow(
            int argb, String label, boolean checked) {
        com.google.android.material.card.MaterialCardView cardView =
                new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_MD));
        cardView.setLayoutParams(params);
        cardView.setCardElevation(0);
        cardView.setRadius(Tokens.radius(this, Tokens.RADIUS_MD));
        cardView.setCardBackgroundColor(ThemeColors.get(this, R.color.card_background));
        cardView.setStrokeWidth(dp(1));
        cardView.setStrokeColor(ThemeColors.get(this, R.color.border));

        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Tokens.space(this, Tokens.SPACE_LG);
        row.setPadding(pad, pad, pad, pad);

        View swatchView = new View(this);
        LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        colorParams.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        swatchView.setLayoutParams(colorParams);
        GradientDrawable swatch = new GradientDrawable();
        swatch.setColor(argb);
        swatch.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_SM));
        swatch.setStroke(dp(1), ThemeColors.get(this, R.color.border));
        swatchView.setBackground(swatch);

        RadioButton radio = new RadioButton(this);
        LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        radioParams.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_SM), 0);
        radio.setLayoutParams(radioParams);
        radio.setChecked(checked);
        // 点击交给整行（卡片）处理：RadioButton 默认会吃掉点击，导致点在圆圈上没反应。
        radio.setClickable(false);
        radio.setFocusable(false);

        TextView text = new TextView(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.weight = 1;
        text.setLayoutParams(textParams);
        text.setText(label);
        Tokens.applyText(text, Tokens.TEXT_BODY_LG, Tokens.Weight.MEDIUM,
                ThemeColors.get(this, R.color.text_primary));

        row.addView(swatchView);
        row.addView(radio);
        row.addView(text);
        cardView.addView(row);
        return cardView;
    }
    private void setupThemeColors() {
        final int currentColor = ThemeManager.getThemeColor(this);

        // 遍历当前皮肤预设色注册表：新增预设色只需改 ThemeManager，本页自动渲染。
        // 2026-11-16 接 token：行距/内边距/圆角/字号全部走 Tokens，行底色用 colorCardBackground
        // 与卡片本体区分（以前同色，看起来连成一片）。
        for (final ThemePreset preset : ThemeManager.getSkin(this).presets) {
            final int colorArgb = preset.argb;

            final com.google.android.material.card.MaterialCardView cardView =
                    buildPresetRow(colorArgb, preset.displayName, colorArgb == currentColor);
            linearLayoutThemeColors.addView(cardView);
            View.OnClickListener colorClick = v -> {
                ThemeManager.setThemeColor(ThemeActivity.this, colorArgb);
                ThemeManager.clearPaletteCache();
                Toast.makeText(ThemeActivity.this, getString(R.string.h_f08afd1f) + preset.displayName + getString(R.string.h_9970ad07),
                        Toast.LENGTH_SHORT).show();
                // 重建当前页，让全局 overlay 立即生效
                recreate();
            };
            cardView.setOnClickListener(colorClick);
            // 整行点击已覆盖圆圈（radio 在 buildPresetRow 里设成不可点）
        }

        // 自定义颜色入口
        addCustomColorEntry(currentColor);
    }

    private void addCustomColorEntry(final int currentColor) {
        // 2026-11-16：与上面 7 个预设行保持同一套 token（以前是 surfaceVariant 平底条，
        // 和卡片本体同色，看起来像"断掉的一行"）。
        com.google.android.material.card.MaterialCardView cardView =
                new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_MD));
        cardView.setLayoutParams(params);
        cardView.setCardElevation(0);
        cardView.setRadius(Tokens.radius(this, Tokens.RADIUS_MD));
        cardView.setCardBackgroundColor(ThemeColors.get(this, R.color.card_background));
        cardView.setStrokeWidth(dp(1));
        cardView.setStrokeColor(ThemeColors.get(this, R.color.border));

        LinearLayout cardLayout = new LinearLayout(this);
        cardLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        cardLayout.setOrientation(LinearLayout.HORIZONTAL);
        cardLayout.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Tokens.space(this, Tokens.SPACE_LG);
        cardLayout.setPadding(pad, pad, pad, pad);

        View colorView = new View(this);
        LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        colorParams.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        colorView.setLayoutParams(colorParams);
        GradientDrawable swatch = new GradientDrawable();
        swatch.setColor(ThemeManager.isCustomColor(this)
                ? currentColor : ThemeColors.get(this, R.color.surface_variant));
        swatch.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_SM));
        swatch.setStroke(dp(1), ThemeColors.get(this, R.color.border));
        colorView.setBackground(swatch);

        TextView textView = new TextView(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.weight = 1;
        textView.setLayoutParams(textParams);
        textView.setText(getString(R.string.h_255f442c));
        Tokens.applyText(textView, Tokens.TEXT_BODY_LG, Tokens.Weight.MEDIUM,
                ThemeColors.get(this, R.color.text_primary));

        cardLayout.addView(colorView);
        cardLayout.addView(textView);
        cardView.addView(cardLayout);
        linearLayoutThemeColors.addView(cardView);

        cardView.setOnClickListener(v -> showCustomColorDialog());
    }

    /**
     * 自定义颜色对话框（2026-11-16 重写）。
     *
     * <p><b>为什么会崩</b>：原来用的是 {@code MaterialAlertDialogBuilder}（Material3 对话框），
     * 但 App 主题是 {@code Theme.MaterialComponents.*}（Material2）。M3 对话框布局
     * {@code m3_alert_dialog} 里的属性（colorSurfaceContainerHigh、shapeAppearance…）在 M2
     * 主题下解析不到，直接 {@code UnsupportedOperationException: Failed to resolve attribute}
     * 把整页打崩 —— 不是色板控件的问题，是"对话框壳"和主题版本不匹配。</p>
     *
     * <p><b>现在怎么做</b>：AppCompat 的 AlertDialog（M2 原生支持）+ 自绘内容：
     * 12 个常用色块（点一下即选）+ 十六进制值 + RGB 三条滑条 + 实时预览。
     * 圆角与底色由自己设 window 背景，不依赖任何 M3 属性。</p>
     */
    private void showCustomColorDialog() {
        final int initial = ThemeManager.getThemeColor(this);
        final int[] rgb = {Color.red(initial), Color.green(initial), Color.blue(initial)};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padX = Tokens.space(this, Tokens.SPACE_2XL);
        int padY = Tokens.space(this, Tokens.SPACE_XL);
        content.setPadding(padX, padY, padX, Tokens.space(this, Tokens.SPACE_SM));

        // ---- 十六进制值 ----
        final TextView hexLabel = new TextView(this);
        Tokens.applyText(hexLabel, Tokens.TEXT_BODY, Tokens.Weight.MEDIUM,
                ThemeColors.get(this, R.color.text_primary));
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_SM));
        hexLabel.setLayoutParams(hexLp);
        content.addView(hexLabel);

        // ---- 实时预览条 ----
        final View preview = new View(this);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Tokens.space(this, Tokens.SPACE_3XL) * 2);
        previewLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_XL));
        preview.setLayoutParams(previewLp);
        content.addView(preview);

        // ---- 三条 RGB 滑条（先建，色块点击要能反向刷新它们）----
        final String[] names = {"红", "绿", "蓝"};
        final TextView[] labels = new TextView[3];
        final SeekBar[] bars = new SeekBar[3];

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                int argb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(Tokens.radius(ThemeActivity.this, Tokens.RADIUS_MD));
                bg.setColor(argb);
                preview.setBackground(bg);
                hexLabel.setText(String.format(java.util.Locale.US, "#%02X%02X%02X",
                        rgb[0], rgb[1], rgb[2]));
                for (int i = 0; i < 3; i++) {
                    if (labels[i] != null) {
                        labels[i].setText(names[i] + " " + rgb[i]);
                    }
                    if (bars[i] != null && bars[i].getProgress() != rgb[i]) {
                        bars[i].setProgress(rgb[i]);
                    }
                }
                boolean dark = ThemeManager.isDarkTheme(ThemeActivity.this);
                renderThemePreview(ThemePaletteProvider.generate(argb, dark));
            }
        };

        // ---- 常用色块网格：点一下直接选中 ----
        final int[] swatches = {
                0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFFB8C00, 0xFFE53935, 0xFF00ACC1,
                0xFF3949AB, 0xFF6D4C41, 0xFF546E7A, 0xFF00897B, 0xFFD81B60, 0xFF5E35B1
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_XL));
        grid.setLayoutParams(gridLp);
        int swatchSize = Tokens.space(this, Tokens.SPACE_2XL) + Tokens.space(this, Tokens.SPACE_MD);
        int gap = Tokens.space(this, Tokens.SPACE_SM);
        LinearLayout swatchRow = null;
        for (int i = 0; i < swatches.length; i++) {
            if (i % 6 == 0) {
                swatchRow = new LinearLayout(this);
                swatchRow.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(swatchRow);
            }
            final int color = swatches[i];
            View sw = new View(this);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
            swLp.setMargins(0, 0, gap, gap);
            sw.setLayoutParams(swLp);
            GradientDrawable swBg = new GradientDrawable();
            swBg.setColor(color);
            swBg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_SM));
            swBg.setStroke(dp(1), ThemeColors.get(this, R.color.border));
            sw.setBackground(swBg);
            sw.setOnClickListener(v -> {
                rgb[0] = Color.red(color);
                rgb[1] = Color.green(color);
                rgb[2] = Color.blue(color);
                refresh.run();
            });
            if (swatchRow != null) {
                swatchRow.addView(sw);
            }
        }
        content.addView(grid);

        // ---- RGB 滑条 ----
        for (int c = 0; c < 3; c++) {
            final int channel = c;
            labels[c] = new TextView(this);
            Tokens.applyText(labels[c], Tokens.TEXT_LABEL, Tokens.Weight.REGULAR,
                    ThemeColors.get(this, R.color.text_secondary));
            content.addView(labels[c]);

            bars[c] = new SeekBar(this);
            bars[c].setMax(255);
            bars[c].setProgress(rgb[c]);
            final int idx = c;
            bars[c].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    rgb[idx] = progress;
                    refresh.run();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { }
            });
            content.addView(bars[c]);
        }
        refresh.run();

        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_edb9ba13))
                .setView(content)
                .setNegativeButton(getString(R.string.h_625fb26b), (d, w) -> restorePreviewAfterDialog())
                .setPositiveButton(getString(R.string.h_5b0520a9), (d, w) -> applyCustomColor(rgb))
                .setOnDismissListener(d -> restorePreviewAfterDialog())
                .create();
        dialog.setOnShowListener(d -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_LG));
            bg.setColor(ThemeColors.get(this, R.color.card_background));
            bg.setStroke(dp(1), ThemeColors.get(this, R.color.border));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(bg);
            }
        });
        dialog.show();
    }

    /** 应用自定义色：写偏好 → 清色板缓存 → 重建页面。 */
    private void applyCustomColor(int[] rgb) {
        int argb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        ThemeManager.setCustomThemeColor(this, argb);
        ThemeManager.clearPaletteCache();
        Toast.makeText(this, getString(R.string.h_e4c42812), Toast.LENGTH_SHORT).show();
        recreate();
    }

    /** 取消/关闭对话框后把主题预览恢复成当前实际主题色板。 */
    private void restorePreviewAfterDialog() {
        if (isFinishing()) {
            return;
        }
        boolean dark = ThemeManager.isDarkTheme(this);
        renderThemePreview(ThemeManager.getPalette(this, dark));
    }

}
