package com.oilquiz.app.model;

public class ThemeColor {

    private int id;
    private String name;
    private int colorRes;
    private boolean isSelected;

    public ThemeColor(int id, String name, int colorRes, boolean isSelected) {
        this.id = id;
        this.name = name;
        this.colorRes = colorRes;
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

    public int getColorRes() {
        return colorRes;
    }

    public void setColorRes(int colorRes) {
        this.colorRes = colorRes;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }
}
