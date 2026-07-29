package com.oilquiz.app.weather.model;

import java.util.ArrayList;
import java.util.List;

public class WeatherIndicesData {
    public List<IndexItem> indices = new ArrayList<>();
    public String fxLink;
    public String updateTime;

    public boolean hasValidData() {
        return indices != null && !indices.isEmpty();
    }

    public IndexItem getIndexByType(String type) {
        if (indices == null) return null;
        for (IndexItem item : indices) {
            if (type.equals(item.type)) return item;
        }
        return null;
    }

    public List<IndexItem> getIndicesByTypes(String... types) {
        List<IndexItem> result = new ArrayList<>();
        if (indices == null) return result;
        for (String type : types) {
            IndexItem item = getIndexByType(type);
            if (item != null) result.add(item);
        }
        return result;
    }

    public static class IndexItem {
        public String date;
        public String type;
        public String name;
        public String level;
        public String category;
        public String text;

        public boolean hasValidData() {
            return name != null && !name.isEmpty();
        }

        public int getLevelInt() {
            try {
                return Integer.parseInt(level);
            } catch (Exception e) {
                return 0;
            }
        }
    }

    public static class IndexType {
        public static final String ALL = "0";
        public static final String SPORT = "1";
        public static final String CAR_WASH = "2";
        public static final String CLOTHING = "3";
        public static final String FISHING = "4";
        public static final String UV = "5";
        public static final String TRAVEL = "6";
        public static final String POLLEN = "7";
        public static final String COMFORT = "8";
        public static final String FLU = "9";
        public static final String AIR_POLLUTION = "10";
        public static final String AIR_CONDITIONING = "11";
        public static final String SUNGLASSES = "12";
        public static final String MAKEUP = "13";
        public static final String DRYING = "14";
        public static final String TRAFFIC = "15";
        public static final String SUN_PROTECTION = "16";

        public static String getName(String type) {
            switch (type) {
                case ALL: return "全部天气指数";
                case SPORT: return "运动指数";
                case CAR_WASH: return "洗车指数";
                case CLOTHING: return "穿衣指数";
                case FISHING: return "钓鱼指数";
                case UV: return "紫外线指数";
                case TRAVEL: return "旅游指数";
                case POLLEN: return "花粉过敏指数";
                case COMFORT: return "舒适度指数";
                case FLU: return "感冒指数";
                case AIR_POLLUTION: return "空气污染扩散条件指数";
                case AIR_CONDITIONING: return "空调开启指数";
                case SUNGLASSES: return "太阳镜指数";
                case MAKEUP: return "化妆指数";
                case DRYING: return "晾晒指数";
                case TRAFFIC: return "交通指数";
                case SUN_PROTECTION: return "防晒指数";
                default: return "未知指数";
            }
        }

        public static String getCategoryByLevel(String type, int level) {
            switch (type) {
                case SPORT:
                    switch (level) {
                        case 1: return "适宜";
                        case 2: return "较适宜";
                        case 3: return "较不宜";
                        default: return "未知";
                    }
                case CAR_WASH:
                    switch (level) {
                        case 1: return "适宜";
                        case 2: return "较适宜";
                        case 3: return "较不宜";
                        case 4: return "不宜";
                        default: return "未知";
                    }
                case CLOTHING:
                    switch (level) {
                        case 1: return "寒冷";
                        case 2: return "冷";
                        case 3: return "较冷";
                        case 4: return "较舒适";
                        case 5: return "舒适";
                        case 6: return "热";
                        case 7: return "炎热";
                        default: return "未知";
                    }
                case UV:
                    switch (level) {
                        case 1: return "最弱";
                        case 2: return "弱";
                        case 3: return "中等";
                        case 4: return "强";
                        case 5: return "很强";
                        default: return "未知";
                    }
                case FLU:
                    switch (level) {
                        case 1: return "少发";
                        case 2: return "较易发";
                        case 3: return "易发";
                        case 4: return "极易发";
                        default: return "未知";
                    }
                default:
                    return "等级" + level;
            }
        }
    }
}
