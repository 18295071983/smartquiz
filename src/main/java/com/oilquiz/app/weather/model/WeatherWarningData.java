package com.oilquiz.app.weather.model;

import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
public class WeatherWarningData {
    public List<WarningItem> warnings = new ArrayList<>();
    public String fxLink;
    public String updateTime;

    public boolean hasValidData() {
        return warnings != null && !warnings.isEmpty();
    }

    public WarningItem getPrimaryWarning() {
        if (warnings == null || warnings.isEmpty()) return null;
        WarningItem primary = null;
        for (WarningItem item : warnings) {
            if (primary == null || item.isMoreSevereThan(primary)) {
                primary = item;
            }
        }
        return primary;
    }

    public static class WarningItem {
        public String id;
        public String sender;
        public String pubTime;
        public String title;
        public String startTime;
        public String endTime;
        public String status;
        public String level;
        public String severity;
        public String severityColor;
        public String type;
        public String typeName;
        public String urgency;
        public String certainty;
        public String text;
        public String related;

        public boolean isActive() {
            return "active".equalsIgnoreCase(status);
        }

        public boolean isMoreSevereThan(WarningItem other) {
            if (other == null) return true;
            return getSeverityOrder() > other.getSeverityOrder();
        }

        public int getSeverityOrder() {
            switch (severity != null ? severity.toLowerCase() : "") {
                case "extreme": return 5;
                case "severe": return 4;
                case "moderate": return 3;
                case "minor": return 2;
                case "unknown": return 1;
                default: return 0;
            }
        }

        public int getUrgencyOrder() {
            switch (urgency != null ? urgency.toLowerCase() : "") {
                case "immediate": return 4;
                case "expected": return 3;
                case "future": return 2;
                case "past": return 1;
                case "unknown": return 0;
                default: return 0;
            }
        }

        public int getCertaintyOrder() {
            switch (certainty != null ? certainty.toLowerCase() : "") {
                case "observed": return 5;
                case "likely": return 4;
                case "possible": return 3;
                case "unlikely": return 2;
                case "unknown": return 1;
                default: return 0;
            }
        }

        public int getColorResource() {
            switch (severityColor != null ? severityColor.toLowerCase() : "") {
                case "red": return ThemeColors.get(R.color.hc_ffff0000);
                case "orange": return ThemeColors.get(R.color.hc_ffffa500);
                case "amber": return ThemeColors.get(R.color.hc_ffffbf00);
                case "yellow": return ThemeColors.get(R.color.hc_ffffff00);
                case "blue": return ThemeColors.get(R.color.hc_ff0000ff);
                case "green": return ThemeColors.get(R.color.hc_ff00ff00);
                case "purple": return ThemeColors.get(R.color.hc_ff800080);
                case "gray": return ThemeColors.get(R.color.hc_ff808080);
                case "black": return ThemeColors.get(R.color.hc_ff000000);
                case "white": return ThemeColors.get(R.color.hc_ffffffff);
                default: return ThemeColors.get(R.color.hc_ffff0000);
            }
        }

        public String getSeverityDisplayName() {
            switch (severity != null ? severity.toLowerCase() : "") {
                case "extreme": return "极端";
                case "severe": return "严重";
                case "moderate": return "中等";
                case "minor": return "轻微";
                case "unknown": return "未知";
                default: return severity;
            }
        }

        public String getUrgencyDisplayName() {
            switch (urgency != null ? urgency.toLowerCase() : "") {
                case "immediate": return "立即";
                case "expected": return "尽快";
                case "future": return "近期";
                case "past": return "已发生";
                case "unknown": return "未知";
                default: return urgency;
            }
        }
    }
}
