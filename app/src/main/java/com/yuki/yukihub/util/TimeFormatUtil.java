package com.yuki.yukihub.util;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TimeFormatUtil {
    private static final DecimalFormat HOURS_FORMAT = new DecimalFormat("0.0");

    public static String playTime(long millis) {
        if (millis <= 0) return "0h";
        double hours = millis / 3600000.0;
        if (hours < 1.0) {
            long minutes = Math.max(1L, Math.round(millis / 60000.0));
            return minutes + "m";
        }
        return HOURS_FORMAT.format(hours) + "h";
    }

    public static String date(long time) {
        if (time <= 0) return "从未游玩";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(time));
    }
}