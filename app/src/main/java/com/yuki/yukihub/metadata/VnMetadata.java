package com.yuki.yukihub.metadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class VnMetadata {
    public String id = "";
    public String chineseTitle = "";
    public String originalTitle = "";
    public String romanTitle = "";
    public String coverUrl = "";
    public String description = "";
    public String translatedDescription = "";
    public String released = "";
    public String developer = "";
    public String tagsText = "";
    public String ratingText = "";
    public String lengthText = "";
    public int lengthMinutes = 0;
    public int lengthVotes = 0;
    public double coverSexual = 0;
    public double coverViolence = 0;
    public final List<String> screenshotUrls = new ArrayList<>();

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("chineseTitle", chineseTitle);
            o.put("originalTitle", originalTitle);
            o.put("romanTitle", romanTitle);
            o.put("coverUrl", coverUrl);
            o.put("description", description);
            o.put("translatedDescription", translatedDescription);
            o.put("released", released);
            o.put("developer", developer);
            o.put("tagsText", tagsText);
            o.put("ratingText", ratingText);
            o.put("lengthText", lengthText);
            o.put("lengthMinutes", lengthMinutes);
            o.put("lengthVotes", lengthVotes);
            o.put("coverSexual", coverSexual);
            o.put("coverViolence", coverViolence);
            JSONArray arr = new JSONArray();
            for (String s : screenshotUrls) arr.put(s);
            o.put("screenshotUrls", arr);
        } catch (Exception ignored) { }
        return o;
    }

    public static VnMetadata fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            VnMetadata m = new VnMetadata();
            m.id = o.optString("id", "");
            m.chineseTitle = o.optString("chineseTitle", "");
            m.originalTitle = o.optString("originalTitle", "");
            m.romanTitle = o.optString("romanTitle", "");
            m.coverUrl = o.optString("coverUrl", "");
            m.description = o.optString("description", "");
            m.translatedDescription = o.optString("translatedDescription", "");
            m.released = o.optString("released", "");
            m.developer = o.optString("developer", "");
            m.tagsText = o.optString("tagsText", "");
            m.ratingText = o.optString("ratingText", "");
            m.lengthText = o.optString("lengthText", "");
            m.lengthMinutes = o.optInt("lengthMinutes", 0);
            m.lengthVotes = o.optInt("lengthVotes", 0);
            m.coverSexual = o.optDouble("coverSexual", 0);
            m.coverViolence = o.optDouble("coverViolence", 0);
            JSONArray arr = o.optJSONArray("screenshotUrls");
            if (arr != null) for (int i = 0; i < arr.length(); i++) m.screenshotUrls.add(arr.optString(i, ""));
            return m;
        } catch (Exception ignored) {
            return null;
        }
    }
}
