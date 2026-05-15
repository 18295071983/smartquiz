package com.oilquiz.app.model;

public class Theme {

    private int id;
    private String name;
    private int themeType;
    private boolean isSelected;

    public Theme(int id, String name, int themeType, boolean isSelected) {
        this.id = id;
        this.name = name;
        this.themeType = themeType;
        this.isSelected = isSelected;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getThemeType() {
        return themeType;
    }

    public void setThemeType(int themeType) {
        this.themeType = themeType;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }
}
