package com.yuki.yukihub.nextmoe;

import android.util.Log;

import com.yuki.yukihub.metadata.VnMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * NextMoe·未萌 目录客户端（第 5 元数据源）。
 * <p>
 * 直连 https://api.nextmoe.dev/v2，Bearer 用 NextMoe 用户访问令牌（catalog:read），
 * 配额按用户计（100/min、10,000/UTC日），无应用密钥、无服务器代理。
 * <p>
 * 线格式要点（developer.nextmoe.dev/docs/conventions）：
 * <ul>
 *   <li>无信封：响应体就是资源/集合；object 是类型判别符；id 是十进制字符串（用 optString）</li>
 *   <li>include 按需取块，写错是 400 UNKNOWN_INCLUDE（拒绝，不降级）</li>
 *   <li>错误是 RFC 9457 problem+json；合并 id 返回 404 ENTITY_MERGED + current_id</li>
 *   <li>客户端契约：忽略未知字段、容忍开放词表新值、未知错误 code 按状态码兜底</li>
 * </ul>
 */
public final class NextMoeClient {

    private static final String TAG = "NextMoe";
    private static final String API_BASE = "https://api.nextmoe.dev/v2";
    /** 用户档 100/min，留余量。 */
    private static final long MIN_REQUEST_INTERVAL_MS = 1100;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1200;

    private static volatile long lastRequestTime = 0L;

    private NextMoeClient() { }

    // ======================== public API ========================

    /**
     * 标题搜索（/catalog/search?object=work）。
     * 搜索命中行本身不带封面（covers 只在详情/列表水合车道），所以先用 q= 搜，
     * 再按 ids= 批量水合一次（include=covers，单请求 ≤100 条）补齐封面——
     * 候选弹窗没有图会和其他源体验割裂，且只多花一次请求。
     * r18 需显式 nsfw=true——YukiHub 端有自己的 nsfwBlur 分级展示，这里拉全量。
     */
    public static List<VnMetadata> searchCandidates(String keyword, int limit) throws Exception {
        List<VnMetadata> out = new ArrayList<>();
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) return out;
        int size = Math.max(1, Math.min(20, limit));
        JSONObject root = apiGet("/catalog/search", new String[][]{
                {"object", "work"},
                {"q", q},
                {"nsfw", "true"},
                {"limit", String.valueOf(size)}
        });
        JSONArray items = root.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < size; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            // 容忍开放词表：只处理 work 命中，其余家族（character/company…）跳过
            String target = o.optString("target_object", "work");
            if (!"work".equals(target)) continue;
            VnMetadata m = parseSearchHit(o);
            if (m != null && !m.id.isEmpty()) out.add(m);
        }
        hydrateCovers(out);
        return out;
    }

    /** ids= 批量水合封面。失败静默忽略——封面缺失只影响观感，不值得让搜索整体失败。 */
    private static void hydrateCovers(List<VnMetadata> list) {
        if (list == null || list.isEmpty()) return;
        try {
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) ids.append(',');
                ids.append(list.get(i).id);
            }
            JSONObject root = apiGet("/catalog/works", new String[][]{
                    {"ids", ids.toString()},
                    {"nsfw", "true"},
                    {"include", "covers"},
                    {"limit", "100"}
            });
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            java.util.Map<String, String> coverMap = new java.util.HashMap<>();
            java.util.Map<String, Double> sexualMap = new java.util.HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                String id = w.optString("id", "");
                if (id.isEmpty()) continue;
                JSONObject baseCover = w.optJSONObject("cover");
                if (baseCover != null && !baseCover.optString("url", "").isEmpty()) {
                    coverMap.put(id, baseCover.optString("url", ""));
                    sexualMap.put(id, sexualToLevel(baseCover.optString("sexual", "safe")));
                    continue;
                }
                JSONArray covers = w.optJSONArray("covers");
                if (covers != null) {
                    for (int j = 0; j < covers.length(); j++) {
                        JSONObject cv = covers.optJSONObject(j);
                        if (cv == null) continue;
                        String u = cv.optString("url", "");
                        if (!u.isEmpty()) {
                            coverMap.put(id, u);
                            sexualMap.put(id, sexualToLevel(cv.optString("sexual", "safe")));
                            break;
                        }
                    }
                }
            }
            for (VnMetadata m : list) {
                String u = coverMap.get(m.id);
                if (u != null && !u.isEmpty()) {
                    m.coverUrl = u;
                    Double lv = sexualMap.get(m.id);
                    if (lv != null) m.coverSexual = lv;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cover hydration skipped", t);
        }
    }

    /**
     * 按目录 id 取作品详情（含 titles/intros/covers/companies/tags/ratings/playtimes/screenshots 块）。
     *
     * @param base 搜索阶段的摘要（可为 null），详情失败时兜底
     */
    public static VnMetadata getWork(String id, VnMetadata base) throws Exception {
        if (id == null || id.trim().isEmpty()) return base;
        JSONObject root = apiGet("/catalog/works/" + id.trim(), new String[][]{
                {"nsfw", "true"},
                {"include", "titles,intros,covers,companies,tags,ratings,playtimes,screenshots"}
        });
        if (root == null || !"work".equals(root.optString("object", "work"))) return base;
        return parseWork(root, base);
    }

    // ======================== HTTP ========================

    private static JSONObject apiGet(String pathWithQueryBase, String[][] params) throws Exception {
        throttle();
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return apiGetOnce(pathWithQueryBase, params, attempt == 0);
            } catch (MergedRedirect mr) {
                // 合并重定向：按 current_id 换新 id 再打一次，不算重试次数
                String newPath = pathWithQueryBase.contains("/catalog/works/")
                        ? "/catalog/works/" + mr.currentId : pathWithQueryBase;
                return apiGetOnce(newPath, params, false);
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5");
                if (!retryable || attempt >= MAX_RETRIES) throw e;
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
            }
        }
        throw last == null ? new IllegalStateException("NextMoe API 调用失败") : last;
    }

    private static JSONObject apiGetOnce(String path, String[][] params, boolean allowRefresh) throws Exception {
        String token = NextMoeAuth.ensureAccessToken();
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("尚未连接 NextMoe 账号，请先在设置中授权");
        }
        StringBuilder url = new StringBuilder(API_BASE).append(path);
        if (params != null && params.length > 0) {
            url.append('?');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) url.append('&');
                url.append(enc(params[i][0])).append('=').append(enc(params[i][1]));
            }
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android)");
            c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();

            if (code == 401 && allowRefresh) {
                // 令牌过期：强制刷新一次后重放
                NextMoeAuth.refreshAccessToken();
                return apiGetOnce(path, params, false);
            }
            if (code == 429) {
                // 分钟窗打满：按 Retry-After 等（上限 60s），文档要求先读它
                long wait = 2L;
                try { wait = Math.min(60L, Long.parseLong(c.getHeaderField("Retry-After"))); } catch (Throwable ignored) { }
                Thread.sleep(wait * 1000L + 250L);
                throw new IllegalStateException("HTTP 429");
            }
            String body = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code == 404) {
                String problemCode = problemCode(body);
                if ("ENTITY_MERGED".equals(problemCode)) {
                    JSONObject p = new JSONObject(body);
                    String currentId = p.optString("current_id", "");
                    if (!currentId.isEmpty()) throw new MergedRedirect(currentId);
                }
                throw new IllegalStateException("HTTP 404: " + problemCode);
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + problemCode(body));
            }
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 404 + current_id：目录已把该 id 合并到新 id。 */
    private static final class MergedRedirect extends RuntimeException {
        final String currentId;
        MergedRedirect(String currentId) { this.currentId = currentId; }
    }

    private static String problemCode(String body) {
        try {
            return new JSONObject(body).optString("code", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static synchronized void throttle() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            try { Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed); } catch (InterruptedException ignored) { }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ======================== parsing ========================

    /** 搜索命中行：{ target_object, id, display_name, latin, localized, sources, content_rating }。 */
    private static VnMetadata parseSearchHit(JSONObject o) {
        VnMetadata m = new VnMetadata();
        m.id = o.optString("id", ""); // 十进制字符串，绝不 optLong 往返
        String displayName = o.optString("display_name", "");
        m.originalTitle = displayName;
        m.romanTitle = o.optString("latin", "");
        JSONObject localized = o.optJSONObject("localized");
        if (localized != null) {
            JSONObject zh = localized.optJSONObject("zh-Hans");
            if (zh != null) m.chineseTitle = zh.optString("value", "");
        }
        if (m.chineseTitle.isEmpty()) m.chineseTitle = displayName;
        // 列表车道同样带 release_date（含精度），先存上——详情解析会以详情行覆盖
        m.released = trimDateByPrecision(o.optString("release_date", ""), o.optString("release_date_precision", ""));
        return m;
    }

    /** 按发售日精度裁剪：day=完整 yyyy-MM-dd；month=yyyy-MM；year=yyyy；未知精度原样返回。 */
    private static String trimDateByPrecision(String date, String precision) {
        if (date == null || date.isEmpty()) return "";
        if ("month".equals(precision) && date.length() >= 7) return date.substring(0, 7);
        if ("year".equals(precision) && date.length() >= 4) return date.substring(0, 4);
        return date;
    }

    /** work 详情：身份内核 + include 块。解析器遵守「忽略未知字段」契约。 */
    private static VnMetadata parseWork(JSONObject o, VnMetadata base) {
        VnMetadata m = new VnMetadata();
        if (base != null) {
            m.id = base.id;
            m.chineseTitle = base.chineseTitle;
            m.originalTitle = base.originalTitle;
            m.romanTitle = base.romanTitle;
            m.coverUrl = base.coverUrl;
            m.coverSexual = base.coverSexual;
            m.developer = base.developer;
            m.released = base.released;
        }

        m.id = o.optString("id", m.id);
        String displayName = o.optString("display_name", "");
        if (!displayName.isEmpty()) m.originalTitle = displayName;
        String latin = o.optString("latin", "");
        if (!latin.isEmpty()) m.romanTitle = latin;

        // titles[]：官方名（ja 原名 / zh-Hans 中文名），is_machine 打标的机翻行不隐藏但排后
        JSONArray titles = o.optJSONArray("titles");
        if (titles != null) {
            String fallbackMachineZh = "";
            for (int i = 0; i < titles.length(); i++) {
                JSONObject t = titles.optJSONObject(i);
                if (t == null) continue;
                String lang = t.optString("lang", "");
                String title = t.optString("title", "");
                boolean machine = t.optBoolean("is_machine", false);
                if ("zh-Hans".equals(lang) && !title.isEmpty()) {
                    if (!machine) { m.chineseTitle = title; break; }
                    if (fallbackMachineZh.isEmpty()) fallbackMachineZh = title;
                } else if ("ja".equals(lang) && !title.isEmpty() && m.originalTitle.isEmpty()) {
                    m.originalTitle = title;
                }
                if (!t.optString("latin", "").isEmpty() && m.romanTitle.isEmpty()) {
                    m.romanTitle = t.optString("latin", "");
                }
            }
            if (m.chineseTitle.isEmpty() && !fallbackMachineZh.isEmpty()) m.chineseTitle = fallbackMachineZh;
        }
        JSONObject localized = o.optJSONObject("localized");
        if (localized != null) {
            JSONObject zh = localized.optJSONObject("zh-Hans");
            if (zh != null && !zh.optString("value", "").isEmpty()) {
                // 裁定后的中文名比 titles 行更权威
                m.chineseTitle = zh.optString("value", "");
            }
        }
        if (m.chineseTitle.isEmpty()) m.chineseTitle = m.originalTitle;

        // release_date：work 基础字段（不在 include 块）。精度为 month/year 时日期落在当月 1 号 /
        // 1 月 1 日，是 API 的填充约定而非真实日期，按精度裁剪展示（文档 §434：不要当成真的发生在那天）。
        String releaseDate = o.optString("release_date", "");
        if (!releaseDate.isEmpty()) {
            m.released = trimDateByPrecision(releaseDate, o.optString("release_date_precision", ""));
        }

        // intros[]：一语言一行。zh-Hans → translatedDescription；原语言 → description。
        // 行形状按防御式解析（body/text/value 任一），机翻行打标不隐藏
        JSONArray intros = o.optJSONArray("intros");
        if (intros != null) {
            String olang = o.optString("olang", "ja");
            for (int i = 0; i < intros.length(); i++) {
                JSONObject in = intros.optJSONObject(i);
                if (in == null) continue;
                String lang = in.optString("lang", in.optString("language", ""));
                String text = in.optString("body", in.optString("text", in.optString("value", "")));
                if (text.isEmpty()) continue;
                if ("zh-Hans".equals(lang) || "zh".equals(lang)) {
                    if (m.translatedDescription.isEmpty()) m.translatedDescription = cleanText(text);
                } else if (olang.equals(lang) || "ja".equals(lang)) {
                    if (m.description.isEmpty()) m.description = cleanText(text);
                }
            }
        }

        // covers[]：第一张可用封面。sexual 是封闭词表字符串，映射到 0~2 数值供 nsfw 检测
        JSONArray covers = o.optJSONArray("covers");
        if (covers != null) {
            for (int i = 0; i < covers.length(); i++) {
                JSONObject cv = covers.optJSONObject(i);
                if (cv == null) continue;
                String u = cv.optString("url", "");
                if (u.isEmpty()) continue;
                m.coverUrl = u;
                m.coverSexual = sexualToLevel(cv.optString("sexual", "safe"));
                break;
            }
        }
        if (m.coverUrl.isEmpty()) {
            // 身份内核自带单张 base cover
            JSONObject cover = o.optJSONObject("cover");
            if (cover != null) {
                m.coverUrl = cover.optString("url", "");
                m.coverSexual = sexualToLevel(cover.optString("sexual", "safe"));
            }
        }

        // companies[]：厂牌/社团
        JSONArray companies = o.optJSONArray("companies");
        if (companies != null && companies.length() > 0) {
            JSONObject c0 = companies.optJSONObject(0);
            if (c0 != null) m.developer = c0.optString("display_name", "");
        }

        // tags[]：名称键防御式（name/display_name/tag.*），取前 5
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < tags.length() && names.size() < 5; i++) {
                JSONObject t = tags.optJSONObject(i);
                if (t == null) continue;
                String name = t.optString("name", t.optString("display_name", ""));
                if (name.isEmpty()) {
                    JSONObject inner = t.optJSONObject("tag");
                    if (inner != null) name = inner.optString("name", inner.optString("display_name", ""));
                }
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
            m.tagsText = join(names, "  ");
        }

        // ratings[]：分源并列、刻度原生。优先 VNDB(10分制) → Bangumi(10分制) → ErogameScape(100分制)
        JSONArray ratings = o.optJSONArray("ratings");
        if (ratings != null) {
            String vndb = null, bgm = null, egs = null;
            for (int i = 0; i < ratings.length(); i++) {
                JSONObject r = ratings.optJSONObject(i);
                if (r == null) continue;
                String src = r.optString("source", "");
                double score = r.optDouble("score", 0);
                int votes = r.optInt("vote_count", 0);
                if (score <= 0) continue;
                if ("vndb".equals(src) && vndb == null) {
                    vndb = String.format(java.util.Locale.US, "评分：%.1f/10%s", score, votes > 0 ? "（" + votes + " 票）" : "");
                } else if ("bangumi".equals(src) && bgm == null) {
                    bgm = String.format(java.util.Locale.US, "评分：%.1f/10%s", score, votes > 0 ? "（" + votes + " 票）" : "");
                } else if ("erogamescape".equals(src) && egs == null) {
                    egs = String.format(java.util.Locale.US, "评分：%.0f/100%s", score, votes > 0 ? "（" + votes + " 票）" : "");
                }
            }
            m.ratingText = vndb != null ? vndb : (bgm != null ? bgm : (egs != null ? egs : ""));
        }

        // playtimes[]：{ source, minutes, vote_count }，VNDB → ErogameScape
        JSONArray playtimes = o.optJSONArray("playtimes");
        if (playtimes != null) {
            Integer minutes = null, votes = null;
            for (int i = 0; i < playtimes.length() && minutes == null; i++) {
                JSONObject p = playtimes.optJSONObject(i);
                if (p == null) continue;
                String src = p.optString("source", "");
                if (!"vndb".equals(src) && !"erogamescape".equals(src)) continue;
                int min = p.optInt("minutes", 0);
                if (min > 0) {
                    minutes = min;
                    votes = p.optInt("vote_count", 0);
                }
            }
            if (minutes != null) {
                m.lengthMinutes = minutes;
                m.lengthVotes = votes == null ? 0 : votes;
                double hours = minutes / 60.0;
                m.lengthText = String.format(java.util.Locale.US,
                        "时长：约 %s%s",
                        hours >= 10 ? String.format(java.util.Locale.US, "%.0f 小时", hours)
                                : String.format(java.util.Locale.US, "%.1f 小时", hours),
                        votes != null && votes > 0 ? "（" + votes + " 票）" : "");
            }
        }

        // screenshots[]：前 2 张
        JSONArray shots = o.optJSONArray("screenshots");
        if (shots != null) {
            for (int i = 0; i < shots.length() && m.screenshotUrls.size() < 2; i++) {
                JSONObject s = shots.optJSONObject(i);
                if (s == null) continue;
                String u = s.optString("url", "");
                if (!u.isEmpty()) m.screenshotUrls.add(u);
            }
        }

        return m;
    }

    /** sexual 封闭词表 → coverSexual 数值（0=safe, 1=suggestive, 2=explicit）。开放容忍：未知当 safe。 */
    private static double sexualToLevel(String sexual) {
        if ("explicit".equals(sexual)) return 2;
        if ("suggestive".equals(sexual)) return 1;
        return 0;
    }

    private static String cleanText(String s) {
        return s == null ? "" : s.replace("\r", "").trim();
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}