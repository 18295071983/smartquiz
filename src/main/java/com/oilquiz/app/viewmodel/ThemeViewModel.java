package com.oilquiz.app.viewmodel;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.manager.ThemeManager;
import com.oilquiz.app.model.Theme;
import com.oilquiz.app.repository.ThemeRepository;

import java.util.List;

public class ThemeViewModel extends ViewModel {

    private ThemeRepository themeRepository;
    private ThemeManager themeManager;

    public void init(Context context) {
        themeRepository = new ThemeRepository(context);
        themeManager = new ThemeManager();
    }

    public List<Theme> getThemes() {
        return themeRepository.getThemes();
    }

    public int getCurrentTheme() {
        return themeRepository.getCurrentTheme();
    }

    public void setTheme(Context context, int themeType) {
        themeRepository.setCurrentTheme(themeType);
        themeManager.applyTheme(context);
    }

    public void applyTheme(Context context) {
        themeManager.applyTheme(context);
    }
}
