package com.yuki.yukihub.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AiReviewResult {
    public String title = "AI 周点评";
    public String subtitle = "这周的欧尼酱也被游戏拿捏了呢";
    public String scoreName = "沉迷指数";
    public int score = 0;
    public String roast = "";
    public final List<String> highlights = new ArrayList<>();
    public final List<GameComment> topGamesComment = new ArrayList<>();
    public final List<String> advice = new ArrayList<>();
    public String oneLine = "";
    public String rawText = "";
    public boolean parsedJson = true;

    public static class GameComment {
        public String game = "";
        public String comment = "";
    }

    public static AiReviewResult fromContent(String content) {
        AiReviewResult r = new AiReviewResult();
        r.rawText = content == null ? "" : content.trim();
        try {
            JSONObject o = new JSONObject(extractJsonObject(r.rawText));
            r.title = safe(o.optString("title", r.title), r.title);
            r.subtitle = safe(o.optString("subtitle", r.subtitle), r.subtitle);
            r.scoreName = safe(o.optString("scoreName", o.optString("score_name", r.scoreName)), r.scoreName);
            r.score = Math.max(0, Math.min(100, o.optInt("score", 0)));
            r.roast = safe(o.optString("roast", ""), "");
            r.oneLine = safe(o.optString("oneLine", o.optString("one_line", "")), "");
            readStringArray(o.optJSONArray("highlights"), r.highlights, 3);
            readStringArray(o.optJSONArray("advice"), r.advice, 3);
            JSONArray games = o.optJSONArray("topGamesComment");
            if (games == null) games = o.optJSONArray("top_games_comment");
            if (games != null) {
                for (int i = 0; i < games.length() && r.topGamesComment.size() < 5; i++) {
                    JSONObject item = games.optJSONObject(i);
                    if (item == null) continue;
                    GameComment gc = new GameComment();
                    gc.game = safe(item.optString("game", ""), "");
                    gc.comment = safe(item.optString("comment", ""), "");
                    if (!gc.game.isEmpty() || !gc.comment.isEmpty()) r.topGamesComment.add(gc);
                }
            }
            if (r.roast.isEmpty() && !r.rawText.isEmpty()) r.roast = r.rawText;
            r.sanitizeVisualNovelSemantics();
        } catch (Throwable t) {
            r.parsedJson = false;
            r.title = "AI 周点评";
            r.subtitle = "模型返回了自由文本，先给欧尼酱原样展示";
            r.scoreName = "锐评指数";
            r.score = 66;
            r.roast = r.rawText;
            r.oneLine = "下次可以让模型严格输出 JSON，杂鱼格式也要调教呢。";
            r.sanitizeVisualNovelSemantics();
        }
        return r;
    }

    public JSONObject toJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("subtitle", subtitle);
        o.put("scoreName", scoreName);
        o.put("score", score);
        o.put("roast", roast);
        JSONArray hs = new JSONArray();
        for (String h : highlights) hs.put(h);
        o.put("highlights", hs);
        JSONArray games = new JSONArray();
        for (GameComment gc : topGamesComment) {
            JSONObject item = new JSONObject();
            item.put("game", gc.game);
            item.put("comment", gc.comment);
            games.put(item);
        }
        o.put("topGamesComment", games);
        JSONArray adv = new JSONArray();
        for (String a : advice) adv.put(a);
        o.put("advice", adv);
        o.put("oneLine", oneLine);
        o.put("rawText", rawText);
        o.put("parsedJson", parsedJson);
        return o;
    }

    public static AiReviewResult fromJson(JSONObject o) {
        AiReviewResult r = new AiReviewResult();
        if (o == null) return r;
        r.title = safe(o.optString("title", r.title), r.title);
        r.subtitle = safe(o.optString("subtitle", r.subtitle), r.subtitle);
        r.scoreName = safe(o.optString("scoreName", r.scoreName), r.scoreName);
        r.score = Math.max(0, Math.min(100, o.optInt("score", r.score)));
        r.roast = safe(o.optString("roast", r.roast), r.roast);
        r.oneLine = safe(o.optString("oneLine", r.oneLine), r.oneLine);
        r.rawText = safe(o.optString("rawText", r.rawText), r.rawText);
        r.parsedJson = o.optBoolean("parsedJson", r.parsedJson);
        readStringArray(o.optJSONArray("highlights"), r.highlights, 3);
        readStringArray(o.optJSONArray("advice"), r.advice, 3);
        JSONArray games = o.optJSONArray("topGamesComment");
        if (games != null) {
            for (int i = 0; i < games.length() && r.topGamesComment.size() < 5; i++) {
                JSONObject item = games.optJSONObject(i);
                if (item == null) continue;
                GameComment gc = new GameComment();
                gc.game = safe(item.optString("game", ""), "");
                gc.comment = safe(item.optString("comment", ""), "");
                if (!gc.game.isEmpty() || !gc.comment.isEmpty()) r.topGamesComment.add(gc);
            }
        }
        r.sanitizeVisualNovelSemantics();
        return r;
    }

    private void sanitizeVisualNovelSemantics() {
        roast = sanitizeText(roast);
        oneLine = sanitizeText(oneLine);
        for (int i = 0; i < highlights.size(); i++) highlights.set(i, sanitizeText(highlights.get(i)));
        for (int i = 0; i < advice.size(); i++) advice.set(i, sanitizeText(advice.get(i)));
        for (GameComment gc : topGamesComment) {
            if (gc != null) gc.comment = sanitizeText(gc.comment);
        }
    }

    private static String sanitizeText(String text) {
        if (text == null) return "";
        String s = text;
        s = s.replace("重复游玩已通关游戏", "重温已通关作品");
        s = s.replace("重复游玩已通关的游戏", "重温已通关作品");
        s = s.replace("再通关已玩过游戏", "回味已玩过作品");
        s = s.replace("克制已通关游戏", "整理已通关作品的感想");
        s = s.replace("通关游戏花痴", "通关后回味");
        s = s.replace("浪费时间", "沉浸得有点久");
        return s;
    }

    public String toShareText() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n").append(subtitle).append("\n");
        if (scoreName != null && !scoreName.isEmpty()) sb.append(scoreName).append("：").append(score).append("/100\n");
        if (roast != null && !roast.isEmpty()) sb.append("\n").append(roast).append("\n");
        if (!highlights.isEmpty()) {
            sb.append("\n亮点：\n");
            for (String h : highlights) sb.append("- ").append(h).append("\n");
        }
        if (!advice.isEmpty()) {
            sb.append("\n下周处方：\n");
            for (String a : advice) sb.append("- ").append(a).append("\n");
        }
        if (oneLine != null && !oneLine.isEmpty()) sb.append("\n").append(oneLine);
        return sb.toString().trim();
    }

    private static void readStringArray(JSONArray arr, List<String> out, int max) {
        if (arr == null || out == null) return;
        for (int i = 0; i < arr.length() && out.size() < max; i++) {
            String s = arr.optString(i, "");
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
    }

    private static String extractJsonObject(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('{');
            int last = s.lastIndexOf('}');
            if (first >= 0 && last > first) return s.substring(first, last + 1);
        }
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) return s.substring(first, last + 1);
        return s;
    }

    private static String safe(String value, String fallback) {
        if (value == null) return fallback == null ? "" : fallback;
        String s = value.trim();
        return s.isEmpty() ? (fallback == null ? "" : fallback) : s;
    }
}