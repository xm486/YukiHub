package com.yuki.yukihub.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AiReviewHistoryStore {
    private AiReviewHistoryStore() { }

    private static final String KEY_HISTORY = "ai_review_history_v1";
    private static final int MAX_HISTORY = 20;

    public static class Entry {
        public long createdAt;
        public long periodStart;
        public long periodEnd;
        public long totalDuration;
        public int gameCount;
        public int sessionCount;
        public String provider = "";
        public String model = "";
        public String persona = "";
        public AiReviewResult result;

        public String displayTitle() {
            String title = result == null ? "AI 周点评" : result.title;
            String date = createdAt > 0 ? new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(createdAt)) : "未知时间";
            return date + " · " + title;
        }

        public String displaySummary() {
            StringBuilder sb = new StringBuilder();
            if (periodStart > 0 && periodEnd > 0) {
                sb.append(new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(periodStart)))
                        .append(" ~ ")
                        .append(new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(periodEnd)))
                        .append(" · ");
            }
            sb.append(gameCount).append(" 款 · ").append(sessionCount).append(" 次");
            if (provider != null && !provider.isEmpty()) sb.append(" · ").append(AiReviewSettings.providerLabel(provider));
            if (model != null && !model.isEmpty()) sb.append("/").append(model);
            if (persona != null && !persona.isEmpty()) sb.append(" · ").append(AiReviewSettings.personaLabel(persona));
            return sb.toString();
        }
    }

    public static void save(Context context, WeeklyPlayStats stats, AiReviewSettings settings, AiReviewResult result) {
        if (context == null || result == null) return;
        try {
            JSONArray arr = readArray(context);
            JSONObject o = new JSONObject();
            o.put("createdAt", System.currentTimeMillis());
            if (stats != null) {
                o.put("periodStart", stats.startTime);
                o.put("periodEnd", stats.endTime);
                o.put("totalDuration", stats.totalDuration);
                o.put("gameCount", stats.gameCount());
                o.put("sessionCount", stats.sessionCount);
            }
            if (settings != null) {
                settings.normalize();
                o.put("provider", settings.provider);
                o.put("model", settings.model);
                o.put("persona", settings.personaPreset);
            }
            o.put("result", result.toJson());
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < MAX_HISTORY; i++) {
                JSONObject old = arr.optJSONObject(i);
                if (old != null) out.put(old);
            }
            prefs(context).edit().putString(KEY_HISTORY, out.toString()).apply();
        } catch (Throwable ignored) { }
    }

    public static List<Entry> load(Context context) {
        List<Entry> list = new ArrayList<>();
        if (context == null) return list;
        JSONArray arr = readArray(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.createdAt = o.optLong("createdAt", 0L);
            e.periodStart = o.optLong("periodStart", 0L);
            e.periodEnd = o.optLong("periodEnd", 0L);
            e.totalDuration = o.optLong("totalDuration", 0L);
            e.gameCount = o.optInt("gameCount", 0);
            e.sessionCount = o.optInt("sessionCount", 0);
            e.provider = o.optString("provider", "");
            e.model = o.optString("model", "");
            e.persona = o.optString("persona", "");
            JSONObject resultJson = o.optJSONObject("result");
            e.result = resultJson == null ? null : AiReviewResult.fromJson(resultJson);
            if (e.result != null) list.add(e);
        }
        return list;
    }

    public static void clear(Context context) {
        if (context == null) return;
        prefs(context).edit().remove(KEY_HISTORY).apply();
    }

    private static JSONArray readArray(Context context) {
        try {
            String text = prefs(context).getString(KEY_HISTORY, "[]");
            return text == null || text.trim().isEmpty() ? new JSONArray() : new JSONArray(text);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(AiReviewSettings.PREFS_NAME, Context.MODE_PRIVATE);
    }
}