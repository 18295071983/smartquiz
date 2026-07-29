package com.oilquiz.app.util;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class QWeatherIconFont {

    private static final String FONT_PATH = "fonts/qweather-icons.ttf";
    private static final String JSON_PATH = "qweather-icons.json";
    private static Typeface sTypeface;
    
    private static final SparseArray<String> CODE_TO_UNICODE = new SparseArray<>();
    private static final SparseArray<String> CODE_TO_FILL_UNICODE = new SparseArray<>();
    private static final Map<String, String> NAME_TO_UNICODE = new HashMap<>();
    private static final Map<String, String> NAME_TO_FILL_UNICODE = new HashMap<>();
    private static boolean sInitialized = false;

    public static synchronized void init(Context context) {
        if (sInitialized) return;
        loadIconsFromJson(context);
        sInitialized = true;
    }

    private static void loadIconsFromJson(Context context) {
        try {
            InputStream is = context.getAssets().open(JSON_PATH);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String jsonStr = new String(buffer, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int unicodeInt = json.getInt(key);
                String unicode = new String(Character.toChars(unicodeInt));

                if (key.endsWith("-fill")) {
                    String baseCode = key.substring(0, key.length() - 6);
                    try {
                        int code = Integer.parseInt(baseCode);
                        CODE_TO_FILL_UNICODE.put(code, unicode);
                    } catch (NumberFormatException e) {
                        NAME_TO_FILL_UNICODE.put(baseCode, unicode);
                    }
                } else {
                    try {
                        int code = Integer.parseInt(key);
                        CODE_TO_UNICODE.put(code, unicode);
                    } catch (NumberFormatException e) {
                        NAME_TO_UNICODE.put(key, unicode);
                    }
                }
            }
        } catch (Exception e) {
            loadFallbackIcons();
        }
    }

    private static void loadFallbackIcons() {
        int[][] mappings = {
            {100, 0xf101}, {101, 0xf102}, {102, 0xf103}, {103, 0xf104}, {104, 0xf105},
            {150, 0xf106}, {151, 0xf107}, {152, 0xf108}, {153, 0xf109},
            {300, 0xf10a}, {301, 0xf10b}, {302, 0xf10c}, {303, 0xf10d}, {304, 0xf10e},
            {305, 0xf10f}, {306, 0xf110}, {307, 0xf111}, {308, 0xf112}, {309, 0xf113},
            {310, 0xf114}, {311, 0xf115}, {312, 0xf116}, {313, 0xf117}, {314, 0xf118},
            {315, 0xf119}, {316, 0xf11a}, {317, 0xf11b}, {318, 0xf11c},
            {350, 0xf11d}, {351, 0xf11e}, {399, 0xf11f},
            {400, 0xf120}, {401, 0xf121}, {402, 0xf122}, {403, 0xf123}, {404, 0xf124},
            {405, 0xf125}, {406, 0xf126}, {407, 0xf127}, {408, 0xf128}, {409, 0xf129},
            {410, 0xf12a}, {456, 0xf12b}, {457, 0xf12c}, {499, 0xf12d},
            {500, 0xf12e}, {501, 0xf12f}, {502, 0xf130}, {503, 0xf131}, {504, 0xf132},
            {507, 0xf133}, {508, 0xf134}, {509, 0xf135}, {510, 0xf136}, {511, 0xf137},
            {512, 0xf138}, {513, 0xf139}, {514, 0xf13a}, {515, 0xf13b},
            {800, 0xf13c}, {801, 0xf13d}, {802, 0xf13e}, {803, 0xf13f}, {804, 0xf140},
            {805, 0xf141}, {806, 0xf142}, {807, 0xf143},
            {900, 0xf144}, {901, 0xf145}, {999, 0xf146},
            {1001, 0xf147}, {1002, 0xf148}, {1003, 0xf149}, {1004, 0xf14a}, {1005, 0xf14b},
            {1006, 0xf14c}, {1007, 0xf14d}, {1008, 0xf14e}, {1009, 0xf14f},
            {1010, 0xf150}, {1011, 0xf151}, {1012, 0xf152}, {1013, 0xf153}, {1014, 0xf154},
            {1015, 0xf155}, {1016, 0xf156}, {1017, 0xf157}, {1018, 0xf158}, {1019, 0xf159},
            {1020, 0xf15a}, {1021, 0xf15b}, {1022, 0xf15c}, {1023, 0xf15d}, {1024, 0xf15e},
            {1025, 0xf15f}, {1026, 0xf160}, {1027, 0xf161}, {1028, 0xf162}, {1029, 0xf163},
            {1030, 0xf164}, {1031, 0xf165}, {1032, 0xf166}, {1033, 0xf167}, {1034, 0xf168},
            {1035, 0xf169}, {1036, 0xf16a}, {1037, 0xf16b}, {1038, 0xf16c}, {1039, 0xf16d},
            {1040, 0xf16e}, {1041, 0xf16f}, {1042, 0xf170}, {1043, 0xf171}, {1044, 0xf172},
            {1045, 0xf173}, {1046, 0xf174}, {1047, 0xf175}, {1048, 0xf176}, {1049, 0xf177},
            {1050, 0xf178}, {1051, 0xf179}, {1052, 0xf17a}, {1053, 0xf17b}, {1054, 0xf17c},
            {1055, 0xf17d}, {1056, 0xf17e}, {1057, 0xf17f}, {1058, 0xf180}, {1059, 0xf181},
            {1060, 0xf182}, {1061, 0xf183}, {1062, 0xf184}, {1063, 0xf185}, {1064, 0xf186},
            {1065, 0xf187}, {1066, 0xf188}, {1067, 0xf189}, {1068, 0xf18a}, {1069, 0xf18b},
            {1071, 0xf18c}, {1072, 0xf18d}, {1073, 0xf18e}, {1074, 0xf18f}, {1075, 0xf190},
            {1076, 0xf191}, {1077, 0xf192}, {1078, 0xf193}, {1079, 0xf194},
            {1080, 0xf195}, {1081, 0xf196}, {1082, 0xf197}, {1084, 0xf198}, {1085, 0xf199},
            {1086, 0xf19a}, {1087, 0xf19b}, {1088, 0xf19c}, {1089, 0xf19d},
            {1201, 0xf1b1}, {1202, 0xf1b2}, {1203, 0xf1b3}, {1204, 0xf1b4}, {1205, 0xf1b5},
            {1206, 0xf1b6}, {1207, 0xf1b7}, {1208, 0xf1b8}, {1209, 0xf1b9}, {1210, 0xf1ba},
            {1211, 0xf1bb}, {1212, 0xf1bc}, {1213, 0xf1bd}, {1214, 0xf1be}, {1215, 0xf1bf},
            {1216, 0xf1c0}, {1217, 0xf1c1}, {1218, 0xf1c2}, {1219, 0xf1c3},
            {9999, 0xf1cb}
        };
        for (int[] mapping : mappings) {
            CODE_TO_UNICODE.put(mapping[0], new String(Character.toChars(mapping[1])));
        }
    }

    public static Typeface getTypeface(Context context) {
        if (!sInitialized) {
            init(context.getApplicationContext());
        }
        if (sTypeface == null) {
            synchronized (QWeatherIconFont.class) {
                if (sTypeface == null) {
                    try {
                        sTypeface = Typeface.createFromAsset(context.getAssets(), FONT_PATH);
                    } catch (Exception e) {
                        sTypeface = Typeface.DEFAULT;
                    }
                }
            }
        }
        return sTypeface;
    }

    public static String getIcon(String iconCode) {
        if (iconCode == null || iconCode.isEmpty()) {
            return getIcon(999);
        }
        
        if (iconCode.endsWith("-fill")) {
            String baseCode = iconCode.substring(0, iconCode.length() - 6);
            try {
                int code = Integer.parseInt(baseCode);
                return getFillIcon(code);
            } catch (NumberFormatException e) {
                String unicode = NAME_TO_FILL_UNICODE.get(baseCode);
                return unicode != null ? unicode : getIcon(999);
            }
        }
        
        try {
            int code = Integer.parseInt(iconCode);
            return getIcon(code);
        } catch (NumberFormatException e) {
            String unicode = NAME_TO_UNICODE.get(iconCode);
            return unicode != null ? unicode : getIcon(999);
        }
    }

    public static String getIcon(int iconCode) {
        String unicode = CODE_TO_UNICODE.get(iconCode);
        return unicode != null ? unicode : getIcon(999);
    }

    public static String getFillIcon(int iconCode) {
        String unicode = CODE_TO_FILL_UNICODE.get(iconCode);
        if (unicode != null) return unicode;
        return getIcon(iconCode);
    }

    public static String getFillIcon(String iconCode) {
        if (iconCode == null || iconCode.isEmpty()) {
            return getFillIcon(999);
        }
        try {
            int code = Integer.parseInt(iconCode);
            return getFillIcon(code);
        } catch (NumberFormatException e) {
            String unicode = NAME_TO_FILL_UNICODE.get(iconCode);
            return unicode != null ? unicode : getIcon(iconCode);
        }
    }

    public static boolean hasIcon(int iconCode) {
        return CODE_TO_UNICODE.get(iconCode) != null || CODE_TO_FILL_UNICODE.get(iconCode) != null;
    }

    public static boolean hasIcon(String iconCode) {
        if (iconCode == null || iconCode.isEmpty()) return false;
        if (iconCode.endsWith("-fill")) {
            try {
                int code = Integer.parseInt(iconCode.substring(0, iconCode.length() - 6));
                return CODE_TO_FILL_UNICODE.get(code) != null || CODE_TO_UNICODE.get(code) != null;
            } catch (NumberFormatException e) {
                return NAME_TO_FILL_UNICODE.containsKey(iconCode.substring(0, iconCode.length() - 6));
            }
        }
        try {
            return hasIcon(Integer.parseInt(iconCode));
        } catch (NumberFormatException e) {
            return NAME_TO_UNICODE.containsKey(iconCode);
        }
    }

    public static boolean hasFillIcon(int iconCode) {
        return CODE_TO_FILL_UNICODE.get(iconCode) != null;
    }

    public static boolean hasFillIcon(String iconCode) {
        if (iconCode == null || iconCode.isEmpty()) return false;
        try {
            return hasFillIcon(Integer.parseInt(iconCode));
        } catch (NumberFormatException e) {
            return NAME_TO_FILL_UNICODE.containsKey(iconCode);
        }
    }
}