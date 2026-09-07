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
    private MaterialSwitch switchSystemDynamic;
    private LinearLayout linearLayoutThemeColors;
    private LinearLayout linearLayoutThemeSkins;
    private LinearLayout linearLayoutThemePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);

        radioLight = findViewById(R.id.radio_light);
        radioDark = findViewById(R.id.radio_dark);
        radioSystem = findViewById(R.id.radio_system);
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
        findViewById(R.id.card_mode_light).setOnClickListener(lightClick);
        findViewById(R.id.card_mode_dark).setOnClickListener(darkClick);
        findViewById(R.id.card_mode_system).setOnClickListener(systemClick);
        radioLight.setOnClickListener(lightClick);
        radioDark.setOnClickListener(darkClick);
        radioSystem.setOnClickListener(systemClick);

        findViewById(R.id.btn_save_theme).setOnClickListener(v -> finish());
        setupWallpaperModeEntry();
        setupSystemDynamicEntry();
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

    /** 系统壁纸缩略图格：点击=跟随系统壁纸 */
    private void addSystemWallpaperCell(LinearLayout container) {
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
        boolean loaded = false;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            Drawable sys = wm.getDrawable();
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

        new AlertDialog.Builder(this)
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
        if (mode != ThemeManager.getMode(this)) {
            ThemeManager.setMode(this, mode);
            Toast.makeText(this,
                    mode == ThemeManager.THEME_DARK ? "已切换到深色模式"
                            : mode == ThemeManager.THEME_LIGHT ? "已切换到浅色模式"
                            : "已跟随系统主题",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCurrentThemeMode() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt(KEY_THEME, ThemeManager.THEME_SYSTEM);
        if (themeMode == ThemeManager.THEME_DARK) {
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

        // 模拟顶条（Toolbar）：primary 底 + onPrimary 字
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(14), dp(16), dp(14));
        topBar.setBackgroundColor(p.primary);
        TextView backArrow = new TextView(this);
        backArrow.setText("‹");
        backArrow.setTextSize(26);
        backArrow.setTextColor(p.onPrimary);
        backArrow.setPadding(0, 0, dp(12), 0);
        topBar.addView(backArrow);
        TextView barTitle = new TextView(this);
        barTitle.setText(getString(R.string.h_57b7276a));
        barTitle.setTextSize(16);
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
        title.setTextSize(16);
        title.setTextColor(p.onSurface);
        body.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.h_ead71ec7));
        subtitle.setTextSize(13);
        subtitle.setTextColor(p.onSurfaceVariant);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(2), 0, dp(12));
        subtitle.setLayoutParams(subLp);
        body.addView(subtitle);

        // 高亮块：primaryContainer
        TextView highlight = new TextView(this);
        highlight.setText(getString(R.string.h_5464581f));
        highlight.setTextSize(14);
        highlight.setGravity(Gravity.CENTER_VERTICAL);
        highlight.setPadding(dp(12), dp(12), dp(12), dp(12));
        highlight.setBackgroundColor(p.primaryContainer);
        highlight.setTextColor(p.onPrimaryContainer);
        body.addView(highlight);

        // 按钮行：实心 primary + 描边 primary
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, dp(12), 0, dp(12));
        body.addView(buttonRow);

        TextView solidBtn = new TextView(this);
        solidBtn.setText(getString(R.string.h_d9113e34));
        solidBtn.setTextSize(14);
        solidBtn.setGravity(Gravity.CENTER);
        solidBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        solidBtn.setBackgroundColor(p.primary);
        solidBtn.setTextColor(p.onPrimary);
        LinearLayout.LayoutParams solidLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        solidLp.setMargins(0, 0, dp(12), 0);
        solidBtn.setLayoutParams(solidLp);
        buttonRow.addView(solidBtn);

        TextView outlineBtn = new TextView(this);
        outlineBtn.setText(getString(R.string.h_21e2f1a9));
        outlineBtn.setTextSize(14);
        outlineBtn.setGravity(Gravity.CENTER);
        outlineBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        outlineBtn.setTextColor(p.primary);
        GradientDrawable outlineBg = new GradientDrawable();
        outlineBg.setStroke(dp(1), p.primary);
        outlineBg.setCornerRadius(dp(4));
        outlineBtn.setBackground(outlineBg);
        buttonRow.addView(outlineBtn);

        // 选中项行：primary 圆点 + onSurface 文本
        LinearLayout selectedRow = new LinearLayout(this);
        selectedRow.setOrientation(LinearLayout.HORIZONTAL);
        selectedRow.setGravity(Gravity.CENTER_VERTICAL);
        selectedRow.setPadding(0, dp(4), 0, dp(4));
        body.addView(selectedRow);

        View dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.setMargins(0, 0, dp(8), 0);
        dot.setLayoutParams(dotLp);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(p.primary);
        dot.setBackground(dotBg);
        selectedRow.addView(dot);

        TextView selectedText = new TextView(this);
        selectedText.setText(getString(R.string.h_2f9d9bdd));
        selectedText.setTextSize(14);
        selectedText.setTextColor(p.onSurface);
        selectedRow.addView(selectedText);

        // 输入框占位：outline 描边
        TextView inputPlaceholder = new TextView(this);
        inputPlaceholder.setText(getString(R.string.h_55d569d2));
        inputPlaceholder.setTextSize(14);
        inputPlaceholder.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setStroke(dp(1), p.outline);
        inputBg.setCornerRadius(dp(6));
        inputPlaceholder.setBackground(inputBg);
        inputPlaceholder.setTextColor(p.onSurfaceVariant);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, dp(12), 0, 0);
        inputPlaceholder.setLayoutParams(inputLp);
        body.addView(inputPlaceholder);

        // ===== 扩展控件覆盖：开关 / 单选 / 复选 / Chip / 进度条 / Tab（全部取自 HCT 色板）=====

        // 开关（MaterialSwitch 模拟：track=primary + thumb=onPrimary）
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, dp(12), 0, dp(4));
        body.addView(switchRow);
        TextView switchLabel = new TextView(this);
        switchLabel.setText(getString(R.string.h_d38b7fc2));
        switchLabel.setTextSize(13);
        switchLabel.setTextColor(p.onSurface);
        switchLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        switchRow.addView(switchLabel);
        android.widget.FrameLayout swTrack = new android.widget.FrameLayout(this);
        swTrack.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(24)));
        GradientDrawable swTrackBg = new GradientDrawable();
        swTrackBg.setCornerRadius(dp(12));
        swTrackBg.setColor(p.primary);
        swTrack.setBackground(swTrackBg);
        View swThumb = new View(this);
        android.widget.FrameLayout.LayoutParams swThumbLp = new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL | Gravity.END);
        swThumbLp.setMargins(0, 0, dp(2), 0);
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
        radioRow.setPadding(0, dp(8), 0, dp(4));
        body.addView(radioRow);
        android.widget.FrameLayout radioOn = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams radioOnLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        radioOnLp.setMargins(0, 0, dp(8), 0);
        radioOn.setLayoutParams(radioOnLp);
        GradientDrawable radioOuter = new GradientDrawable();
        radioOuter.setShape(GradientDrawable.OVAL);
        radioOuter.setStroke(dp(2), p.primary);
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
        radioLabel.setTextSize(13);
        radioLabel.setTextColor(p.onSurface);
        radioRow.addView(radioLabel);

        // 复选（选中：primary 实心圆角方块 + 白勾）
        LinearLayout checkRow = new LinearLayout(this);
        checkRow.setOrientation(LinearLayout.HORIZONTAL);
        checkRow.setGravity(Gravity.CENTER_VERTICAL);
        checkRow.setPadding(0, dp(8), 0, dp(4));
        body.addView(checkRow);
        TextView checkBox = new TextView(this);
        checkBox.setText("✓");
        checkBox.setTextSize(12);
        checkBox.setGravity(Gravity.CENTER);
        checkBox.setTextColor(p.onPrimary);
        LinearLayout.LayoutParams checkBoxLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        checkBoxLp.setMargins(0, 0, dp(8), 0);
        checkBox.setLayoutParams(checkBoxLp);
        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setCornerRadius(dp(4));
        checkBg.setColor(p.primary);
        checkBox.setBackground(checkBg);
        checkRow.addView(checkBox);
        TextView checkLabel = new TextView(this);
        checkLabel.setText(getString(R.string.h_db98f889));
        checkLabel.setTextSize(13);
        checkLabel.setTextColor(p.onSurface);
        checkRow.addView(checkLabel);

        // Chip（选中：primary 底 + onPrimary 字）
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(8), 0, dp(4));
        body.addView(chipRow);
        TextView chipOn = new TextView(this);
        chipOn.setText(getString(R.string.h_c1dc4db2));
        chipOn.setTextSize(12);
        chipOn.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams chipOnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipOnLp.setMargins(0, 0, dp(8), 0);
        chipOn.setLayoutParams(chipOnLp);
        GradientDrawable chipOnBg = new GradientDrawable();
        chipOnBg.setCornerRadius(dp(16));
        chipOnBg.setColor(p.primary);
        chipOn.setBackground(chipOnBg);
        chipOn.setTextColor(p.onPrimary);
        chipRow.addView(chipOn);
        TextView chipOff = new TextView(this);
        chipOff.setText(getString(R.string.h_8f119321));
        chipOff.setTextSize(12);
        chipOff.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable chipOffBg = new GradientDrawable();
        chipOffBg.setCornerRadius(dp(16));
        chipOffBg.setColor(p.surfaceVariant);
        chipOff.setBackground(chipOffBg);
        chipOff.setTextColor(p.onSurfaceVariant);
        chipRow.addView(chipOff);

        // 进度条（track=surfaceVariant + 填充=primary 60%）
        android.widget.FrameLayout progressTrack = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        progressLp.setMargins(0, dp(10), 0, dp(4));
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
        tabRow.setPadding(0, dp(8), 0, 0);
        body.addView(tabRow);
        LinearLayout tabOne = new LinearLayout(this);
        tabOne.setOrientation(LinearLayout.VERTICAL);
        tabOne.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tabOneText = new TextView(this);
        tabOneText.setText(getString(R.string.h_3c34792e));
        tabOneText.setTextSize(13);
        tabOneText.setGravity(Gravity.CENTER);
        tabOneText.setTextColor(p.primary);
        tabOne.addView(tabOneText);
        View tabOneBar = new View(this);
        tabOneBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        GradientDrawable tabOneBarBg = new GradientDrawable();
        tabOneBarBg.setCornerRadius(dp(2));
        tabOneBarBg.setColor(p.primary);
        tabOneBar.setBackground(tabOneBarBg);
        tabOne.addView(tabOneBar);
        tabRow.addView(tabOne);
        LinearLayout tabTwo = new LinearLayout(this);
        tabTwo.setOrientation(LinearLayout.VERTICAL);
        tabTwo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tabTwoText = new TextView(this);
        tabTwoText.setText(getString(R.string.h_b7abee9d));
        tabTwoText.setTextSize(13);
        tabTwoText.setGravity(Gravity.CENTER);
        tabTwoText.setTextColor(p.onSurfaceVariant);
        tabTwo.addView(tabTwoText);
        View tabTwoBar = new View(this);
        tabTwoBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        GradientDrawable tabTwoBarBg = new GradientDrawable();
        tabTwoBarBg.setCornerRadius(dp(2));
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

        for (final ThemeSkin skin : ThemeManager.getSkins()) {
            CardView cardView = new CardView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 8);
            cardView.setLayoutParams(params);
            cardView.setCardElevation(1);
            cardView.setRadius(8);
            cardView.setCardBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));

            LinearLayout cardLayout = new LinearLayout(this);
            LinearLayout.LayoutParams cardLayoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardLayout.setLayoutParams(cardLayoutParams);
            cardLayout.setOrientation(LinearLayout.HORIZONTAL);
            cardLayout.setPadding(12, 12, 12, 12);

            // 皮肤默认主色圆点
            View colorView = new View(this);
            LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(40, 40);
            colorParams.setMargins(0, 0, 12, 0);
            colorView.setLayoutParams(colorParams);
            colorView.setBackgroundColor(skin.defaultArgb);
            colorView.setClipToOutline(true);

            final RadioButton radioButton = new RadioButton(this);
            LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            radioParams.setMargins(0, 0, 12, 0);
            radioButton.setLayoutParams(radioParams);
            radioButton.setChecked(skin.id.equals(currentSkinId));

            TextView textView = new TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.weight = 1;
            textView.setLayoutParams(textParams);
            textView.setText(skin.displayName);
            textView.setTextSize(16);
            textView.setTextColor(ThemeColors.attr(this, R.attr.colorOnSurface));

            cardLayout.addView(colorView);
            cardLayout.addView(radioButton);
            cardLayout.addView(textView);
            cardView.addView(cardLayout);
            linearLayoutThemeSkins.addView(cardView);

            // 整卡点击 + 圆圈点击都走同一切换逻辑（RadioButton 嵌套在卡片内，
            // 点圆圈会消费事件、不触发卡片监听，必须单独绑定）
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
            radioButton.setOnClickListener(skinClick);
        }
    }

    private void setupThemeColors() {
        final int currentColor = ThemeManager.getThemeColor(this);

        // 遍历当前皮肤预设色注册表：新增预设色只需改 ThemeManager，本页自动渲染
        for (final ThemePreset preset : ThemeManager.getSkin(this).presets) {
            final int colorArgb = preset.argb;

            CardView cardView = new CardView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 8);
            cardView.setLayoutParams(params);
            cardView.setCardElevation(1);
            cardView.setRadius(8);
            cardView.setCardBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));

            LinearLayout cardLayout = new LinearLayout(this);
            LinearLayout.LayoutParams cardLayoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardLayout.setLayoutParams(cardLayoutParams);
            cardLayout.setOrientation(LinearLayout.HORIZONTAL);
            cardLayout.setPadding(12, 12, 12, 12);

            View colorView = new View(this);
            LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(40, 40);
            colorParams.setMargins(0, 0, 12, 0);
            colorView.setLayoutParams(colorParams);
            colorView.setBackgroundColor(colorArgb);
            colorView.setClipToOutline(true);

            final RadioButton radioButton = new RadioButton(this);
            LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            radioParams.setMargins(0, 0, 12, 0);
            radioButton.setLayoutParams(radioParams);
            radioButton.setChecked(colorArgb == currentColor);

            TextView textView = new TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.weight = 1;
            textView.setLayoutParams(textParams);
            textView.setText(preset.displayName);
            textView.setTextSize(16);
            textView.setTextColor(ThemeColors.attr(this, R.attr.colorOnSurface));

            cardLayout.addView(colorView);
            cardLayout.addView(radioButton);
            cardLayout.addView(textView);
            cardView.addView(cardLayout);
            linearLayoutThemeColors.addView(cardView);

            // 整卡点击 + 圆圈点击都走同一换色逻辑（同皮肤卡：圆圈点击不触发卡片监听）
            View.OnClickListener colorClick = v -> {
                ThemeManager.setThemeColor(ThemeActivity.this, colorArgb);
                ThemeManager.clearPaletteCache();
                Toast.makeText(ThemeActivity.this, getString(R.string.h_f08afd1f) + preset.displayName + getString(R.string.h_9970ad07),
                        Toast.LENGTH_SHORT).show();
                // 重建当前页，让全局 overlay 立即生效
                recreate();
            };
            cardView.setOnClickListener(colorClick);
            radioButton.setOnClickListener(colorClick);
        }

        // 自定义颜色入口
        addCustomColorEntry(currentColor);
    }

    /** 追加"自定义颜色"入口：点击弹出 R/G/B 取色对话框 */
    private void addCustomColorEntry(final int currentColor) {
        CardView cardView = new CardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 8);
        cardView.setLayoutParams(params);
        cardView.setCardElevation(1);
        cardView.setRadius(8);
        cardView.setCardBackgroundColor(ThemeColors.attr(this, R.attr.colorSurfaceVariant));

        LinearLayout cardLayout = new LinearLayout(this);
        LinearLayout.LayoutParams cardLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLayout.setLayoutParams(cardLayoutParams);
        cardLayout.setOrientation(LinearLayout.HORIZONTAL);
        cardLayout.setPadding(12, 12, 12, 12);

        View colorView = new View(this);
        LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(40, 40);
        colorParams.setMargins(0, 0, 12, 0);
        colorView.setLayoutParams(colorParams);
        colorView.setBackgroundColor(ThemeManager.isCustomColor(this)
                ? currentColor : ThemeColors.attr(this, R.attr.colorSurfaceVariant));
        colorView.setClipToOutline(true);

        TextView textView = new TextView(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.weight = 1;
        textView.setLayoutParams(textParams);
        textView.setText(getString(R.string.h_255f442c));
        textView.setTextSize(16);
        textView.setTextColor(ThemeColors.attr(this, R.attr.colorOnSurface));

        cardLayout.addView(colorView);
        cardLayout.addView(textView);
        cardView.addView(cardLayout);
        linearLayoutThemeColors.addView(cardView);

        cardView.setOnClickListener(v -> showCustomColorDialog());
    }

    private void showCustomColorDialog() {
        // 初始值取当前实际主题色（自定义色 / 壁纸取色 / 预设色均适用），从当前色开始调整
        int initial = ThemeManager.getThemeColor(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);

        final View preview = new View(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 64
        );
        previewParams.setMargins(0, 0, 0, 16);
        preview.setLayoutParams(previewParams);
        preview.setBackgroundColor(initial);
        preview.setClipToOutline(true);
        layout.addView(preview);

        final int[] rgb = {
                Color.red(initial), Color.green(initial), Color.blue(initial)
        };
        final String[] labels = {"红", "绿", "蓝"};
        for (int c = 0; c < 3; c++) {
            final int channel = c;
            TextView label = new TextView(this);
            label.setText(labels[c] + " " + rgb[channel]);
            label.setTextSize(14);
            layout.addView(label);

            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(255);
            seekBar.setProgress(rgb[channel]);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    rgb[channel] = progress;
                    label.setText(labels[channel] + " " + progress);
                    preview.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
                    // 编辑预览：拖动滑条时用当前 RGB 实时生成 HCT 色板，刷新上方预览区
                    int argb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                    boolean dark = ThemeManager.isDarkTheme(ThemeActivity.this);
                    renderThemePreview(ThemePaletteProvider.generate(argb, dark));
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { }
            });
            layout.addView(seekBar);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_edb9ba13))
                .setView(layout)
                .setNegativeButton(getString(R.string.h_625fb26b), (dialog, which) -> {
                    // 取消：预览区恢复当前实际主题色板
                    boolean dark = ThemeManager.isDarkTheme(ThemeActivity.this);
                    renderThemePreview(ThemeManager.getPalette(ThemeActivity.this, dark));
                })
                .setPositiveButton(getString(R.string.h_5b0520a9), (dialog, which) -> {
                    int argb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                    ThemeManager.setCustomThemeColor(ThemeActivity.this, argb);
                    ThemeManager.clearPaletteCache();
                    Toast.makeText(ThemeActivity.this, getString(R.string.h_e4c42812), Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setOnDismissListener(dialog -> {
                    // 无论应用/取消/点外部关闭，都恢复预览为实际主题（应用场景 recreate 会重建，无需处理）
                    if (!isFinishing()) {
                        boolean dark = ThemeManager.isDarkTheme(ThemeActivity.this);
                        renderThemePreview(ThemeManager.getPalette(ThemeActivity.this, dark));
                    }
                })
                .show();
    }
}
