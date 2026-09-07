package com.oilquiz.app.weather.model;

import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
public class AirQualityData {
    public String aqi;
    public String aqiDisplay;
    public String category;
    public String level;
    public String colorRed;
    public String colorGreen;
    public String colorBlue;
    public String colorAlpha;
    public String primaryPollutantCode;
    public String primaryPollutantName;
    public String healthEffect;
    public String healthGeneralAdvice;
    public String healthSensitiveAdvice;
    public String pm2p5;
    public String pm10;
    public String no2;
    public String so2;
    public String co;
    public String o3;
    public String updateTime;
    public String fxLink;
    public String aqiCode;
    public String aqiName;
    public boolean isQAQI;
    public List<PollutantData> pollutants = new ArrayList<>();
    public List<StationData> stations = new ArrayList<>();
    public List<AirQualityIndex> additionalIndexes = new ArrayList<>();

    public boolean hasValidData() {
        return aqi != null && !aqi.isEmpty();
    }

    public String getColorHex() {
        try {
            int r = Integer.parseInt(colorRed != null ? colorRed : "0");
            int g = Integer.parseInt(colorGreen != null ? colorGreen : "0");
            int b = Integer.parseInt(colorBlue != null ? colorBlue : "0");
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return "#00FF00";
        }
    }

    public int getColorInt() {
        try {
            int r = Integer.parseInt(colorRed != null ? colorRed : "0");
            int g = Integer.parseInt(colorGreen != null ? colorGreen : "0");
            int b = Integer.parseInt(colorBlue != null ? colorBlue : "0");
            int a = Integer.parseInt(colorAlpha != null ? colorAlpha : "1");
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (Exception e) {
            return ThemeColors.get(R.color.hc_ff00ff00);
        }
    }

    public static class PollutantData {
        public String code;
        public String name;
        public String fullName;
        public double concentrationValue;
        public String concentrationUnit;
        public List<SubIndexData> subIndexes = new ArrayList<>();

        public String getDisplayConcentration() {
            return String.format("%.1f %s", concentrationValue, concentrationUnit != null ? concentrationUnit : "");
        }
    }

    public static class SubIndexData {
        public String code;
        public double aqi;
        public String aqiDisplay;
    }

    public static class StationData {
        public String id;
        public String name;
    }

    public static class AirQualityIndex {
        public String code;
        public String name;
        public double aqi;
        public String aqiDisplay;
        public String level;
        public String category;
    }
}
