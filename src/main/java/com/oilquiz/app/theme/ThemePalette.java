package com.oilquiz.app.theme;

import com.oilquiz.app.theme.mcu.palettes.TonalPalette;

/**
 * 完整 Material 3 语义色板（项目自维护引擎，2026-11-06）。
 *
 * <p>不再依赖 vendored 的 {@code Scheme}/{@code CorePalette}：直接用 HCT 的
 * {@link TonalPalette} 按 Material 3 tonal-spot 口径生成。相比旧引擎：
 * <ul>
 *   <li>主色 chroma 从 max(48, 源色) 提到 56，secondary 16→24、tertiary 24→32，
 *       neutral 4→6、neutral-variant 8→10，整体更鲜明；</li>
 *   <li>surface/background 采用新版 M3 的 tone 98（深色 6），不再是 99/10；
 *       outline/inverse 等同步按当前规范取值。</li>
 * </ul>
 * 色值允许变化；31 套 overlay 由 {@code tools/GenOverlays.java} 重跑生成。</p>
 */
public class ThemePalette {

    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    public final int secondary;
    public final int onSecondary;
    public final int secondaryContainer;
    public final int onSecondaryContainer;
    public final int tertiary;
    public final int onTertiary;
    public final int tertiaryContainer;
    public final int onTertiaryContainer;
    public final int error;
    public final int onError;
    public final int errorContainer;
    public final int onErrorContainer;
    public final int background;
    public final int onBackground;
    public final int surface;
    public final int onSurface;
    public final int surfaceVariant;
    public final int onSurfaceVariant;
    public final int outline;
    public final int outlineVariant;
    public final int shadow;
    public final int scrim;
    public final int inverseSurface;
    public final int inverseOnSurface;
    public final int inversePrimary;

    public ThemePalette(TonalPalette a1, TonalPalette a2, TonalPalette a3,
                        TonalPalette n1, TonalPalette n2, TonalPalette error,
                        boolean dark) {
        if (dark) {
            primary = a1.tone(80);
            onPrimary = a1.tone(20);
            primaryContainer = a1.tone(30);
            onPrimaryContainer = a1.tone(90);
            secondary = a2.tone(80);
            onSecondary = a2.tone(20);
            secondaryContainer = a2.tone(30);
            onSecondaryContainer = a2.tone(90);
            tertiary = a3.tone(80);
            onTertiary = a3.tone(20);
            tertiaryContainer = a3.tone(30);
            onTertiaryContainer = a3.tone(90);
            this.error = error.tone(80);
            onError = error.tone(20);
            errorContainer = error.tone(30);
            onErrorContainer = error.tone(80);
            background = n1.tone(6);
            onBackground = n1.tone(90);
            surface = n1.tone(6);
            onSurface = n1.tone(90);
            surfaceVariant = n2.tone(30);
            onSurfaceVariant = n2.tone(80);
            outline = n2.tone(60);
            outlineVariant = n2.tone(30);
            shadow = n1.tone(0);
            scrim = n1.tone(0);
            inverseSurface = n1.tone(90);
            inverseOnSurface = n1.tone(20);
            inversePrimary = a1.tone(40);
        } else {
            primary = a1.tone(40);
            onPrimary = a1.tone(100);
            primaryContainer = a1.tone(90);
            onPrimaryContainer = a1.tone(10);
            secondary = a2.tone(40);
            onSecondary = a2.tone(100);
            secondaryContainer = a2.tone(90);
            onSecondaryContainer = a2.tone(10);
            tertiary = a3.tone(40);
            onTertiary = a3.tone(100);
            tertiaryContainer = a3.tone(90);
            onTertiaryContainer = a3.tone(10);
            this.error = error.tone(40);
            onError = error.tone(100);
            errorContainer = error.tone(90);
            onErrorContainer = error.tone(10);
            background = n1.tone(98);
            onBackground = n1.tone(10);
            surface = n1.tone(98);
            onSurface = n1.tone(10);
            surfaceVariant = n2.tone(90);
            onSurfaceVariant = n2.tone(30);
            outline = n2.tone(50);
            outlineVariant = n2.tone(80);
            shadow = n1.tone(0);
            scrim = n1.tone(0);
            inverseSurface = n1.tone(20);
            inverseOnSurface = n1.tone(95);
            inversePrimary = a1.tone(80);
        }
    }
}
