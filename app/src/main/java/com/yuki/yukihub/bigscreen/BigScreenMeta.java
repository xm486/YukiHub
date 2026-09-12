package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.metadata.VnMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 详情层用的元数据装载器（bigscreen_spec.md §S3）。
 *
 * <p>数据来自既有的 {@link MetadataRepository}，**按来源顺序合并**：
 * NextMoe（当前主源）→ VNDB → Bangumi → Ymgal → ひかり凪。
 * 每个字段取第一个非空值，所以几个源可以互相补全。
 *
 * <p>DB 读取放在单线程池里，结果回主线程；带内存缓存，同一游戏只查一次。
 */
public class BigScreenMeta {

    /** 详情层需要的一组字段 */
    public static class Data {
        public String originalTitle = "";
        public String romanTitle = "";
        public String developer = "";
        public String released = "";
        public String description = "";
        public String rating = "";
        public String lengthText = "";
        public final List<String> tags = new ArrayList<>();
        public final List<String> screenshots = new ArrayList<>();

        public boolean isEmpty() {
            return developer.isEmpty() && released.isEmpty() && description.isEmpty()
                    && tags.isEmpty() && screenshots.isEmpty();
        }
    }

    public interface Callback {
        void onLoaded(long gameId, Data data);
    }

    /**
     * 标签规范化（M8，信息浮层与详情层共用）：
     * <ul>
     *   <li>元数据标签优先，为空时退回本地 {@code game.tags}</li>
     *   <li>有些数据源把多个标签**用空格拼成一整串**，这里再按"2 个以上空格"与常见分隔符拆开</li>
     *   <li>去重 + 截断到 {@code max} 个（默认只展示前 3 个，用户要求）</li>
     * </ul>
     */
    public static List<String> normalizeTags(List<String> rawTags, String fallback, int max) {
        List<String> out = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        if (rawTags != null) { raw.addAll(rawTags); }
        if (raw.isEmpty() && fallback != null && !fallback.isEmpty()) { raw.add(fallback); }
        for (String t : raw) {
            if (t == null) { continue; }
            for (String piece : t.split("\\s{2,}|[,，/、;；|]")) {
                String s = piece.trim();
                if (!s.isEmpty() && !out.contains(s)) { out.add(s); }
            }
        }
        if (max > 0 && out.size() > max) { return new ArrayList<>(out.subList(0, max)); }
        return out;
    }

    private final MetadataRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Long, Data> cache = new HashMap<>();

    public BigScreenMeta(Context context) {
        this.repository = new MetadataRepository(context);
    }

    /** 异步加载（命中缓存则同步回调） */
    public void load(final long gameId, final Callback callback) {
        Data cached = cache.get(gameId);
        if (cached != null) {
            if (callback != null) { callback.onLoaded(gameId, cached); }
            return;
        }
        executor.execute(() -> {
            final Data data = new Data();
            try {
                merge(data, repository.getNextMoe(gameId));
                merge(data, repository.getVndb(gameId));
                merge(data, repository.getBangumi(gameId));
                merge(data, repository.getYmgal(gameId));
                merge(data, repository.getHikarinagi(gameId));
            } catch (Throwable ignored) { }
            cache.put(gameId, data);
            if (callback != null) {
                ui.post(() -> callback.onLoaded(gameId, data));
            }
        });
    }

    public void shutdown() { executor.shutdownNow(); }

    public void clearCache() { cache.clear(); }

    // ================= 内部 =================

    private void merge(Data d, VnMetadata m) {
        if (m == null) { return; }
        if (d.originalTitle.isEmpty()) { d.originalTitle = nz(m.originalTitle); }
        if (d.romanTitle.isEmpty()) { d.romanTitle = nz(m.romanTitle); }
        if (d.developer.isEmpty()) { d.developer = nz(m.developer); }
        if (d.released.isEmpty()) { d.released = nz(m.released); }
        if (d.description.isEmpty()) { d.description = firstNonEmpty(m.translatedDescription, m.description); }
        if (d.rating.isEmpty()) { d.rating = nz(m.ratingText); }
        if (d.lengthText.isEmpty()) { d.lengthText = nz(m.lengthText); }
        if (d.tags.isEmpty() && !nz(m.tagsText).isEmpty()) {
            d.tags.addAll(splitTags(m.tagsText));
        }
        if (d.screenshots.isEmpty() && m.screenshotUrls != null) {
            for (String s : m.screenshotUrls) {
                if (s != null && !s.trim().isEmpty() && d.screenshots.size() < 8) {
                    d.screenshots.add(s.trim());
                }
            }
        }
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static String firstNonEmpty(String a, String b) {
        String x = nz(a);
        return x.isEmpty() ? nz(b) : x;
    }

    /** tagsText 常见分隔符：中英文逗号、顿号、斜杠 */
    private static List<String> splitTags(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("[,，、/|]")) {
            String t = part.trim();
            if (!t.isEmpty() && out.size() < 6) { out.add(t); }
        }
        return out;
    }
}