package com.oilquiz.app.theme;

import com.oilquiz.app.theme.mcu.scheme.Scheme;

/**
 * 完整 Material 3 语义色板（由 Google material-color-utilities 的 HCT 算法生成）。
 * 字段与 mcu Scheme 一一对应，供 XML overlay 生成、Compose ColorScheme、代码取色共用。
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

    public ThemePalette(Scheme s) {
        this.primary = s.getPrimary();
        this.onPrimary = s.getOnPrimary();
        this.primaryContainer = s.getPrimaryContainer();
        this.onPrimaryContainer = s.getOnPrimaryContainer();
        this.secondary = s.getSecondary();
        this.onSecondary = s.getOnSecondary();
        this.secondaryContainer = s.getSecondaryContainer();
        this.onSecondaryContainer = s.getOnSecondaryContainer();
        this.tertiary = s.getTertiary();
        this.onTertiary = s.getOnTertiary();
        this.tertiaryContainer = s.getTertiaryContainer();
        this.onTertiaryContainer = s.getOnTertiaryContainer();
        this.error = s.getError();
        this.onError = s.getOnError();
        this.errorContainer = s.getErrorContainer();
        this.onErrorContainer = s.getOnErrorContainer();
        this.background = s.getBackground();
        this.onBackground = s.getOnBackground();
        this.surface = s.getSurface();
        this.onSurface = s.getOnSurface();
        this.surfaceVariant = s.getSurfaceVariant();
        this.onSurfaceVariant = s.getOnSurfaceVariant();
        this.outline = s.getOutline();
        this.outlineVariant = s.getOutlineVariant();
        this.shadow = s.getShadow();
        this.scrim = s.getScrim();
        this.inverseSurface = s.getInverseSurface();
        this.inverseOnSurface = s.getInverseOnSurface();
        this.inversePrimary = s.getInversePrimary();
    }
}
