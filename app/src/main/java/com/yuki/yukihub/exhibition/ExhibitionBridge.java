package com.yuki.yukihub.exhibition;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

import java.util.List;

/**
 * 展厅 JS 桥接层（对齐 TyranoActivity 的 appJsInterface 范式）
 * =========================================================
 * 页面通过 window.ExhibitionBridge 调用这里的方法。
 *
 * 线程注意：@JavascriptInterface 方法运行在 WebView 的 JavaBridge 线程，
 *          任何触碰 UI 的操作必须 post 回主线程。
 *
 * M0 只做"观测"（日志 / 提示 / 性能摘要），**不暴露任何用户数据**；
 * M1 才会加入只读的 getMyLibrary() 与封面取流。
 */
public class ExhibitionBridge {

    public static final String NAME = "ExhibitionBridge";
    private static final String TAG = "ExhibitionBridge";

    private final ExhibitionActivity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ExhibitionBridge(ExhibitionActivity activity) {
        this.activity = activity;
    }

    /** 页面模块加载完成（M0 会传 "M0"） */
    @JavascriptInterface
    public void onReady(String stage) {
        Log.i(TAG, "展厅就绪: " + stage);
    }

    /** 每 5 秒一次的性能摘要（JSON 字符串），M0 先只写日志 */
    @JavascriptInterface
    public void onPerf(String json) {
        Log.i(TAG, "性能摘要: " + json);
    }

    /**
     * 读取本机库存（纯本地只读，不上传任何东西）
     * @return {"games":[...],"count":N} 或 {"error":"..."}
     */
    @JavascriptInterface
    public String getMyLibrary() {
        try {
            if (activity == null) return "{\"error\":\"宿主已销毁\"}";

            java.util.List<Game> games = new GameRepository(activity).getAll();
            java.util.Map<Long, String> coverIndex = new java.util.HashMap<>();
            org.json.JSONArray arr = new org.json.JSONArray();

            for (Game g : games) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", g.id);
                o.put("title", nz(g.title));
                o.put("originalTitle", nz(g.originalTitle));
                o.put("engine", g.engine == null ? "" : g.engine.name());
                o.put("playStatus", nz(g.playStatus).isEmpty() ? "unplayed" : g.playStatus);
                o.put("totalPlayTime", g.totalPlayTime);
                o.put("lastPlayedAt", g.lastPlayedAt);
                o.put("favorite", g.favorite);
                o.put("nsfw", g.nsfw);
                o.put("tags", nz(g.tags));

                String cover = pickCover(g);
                o.put("hasCover", cover != null);
                if (cover != null) {
                    coverIndex.put(g.id, cover);
                    o.put("cover", "https://exhibition.local/cover/" + g.id);
                }
                arr.put(o);
            }

            // 交给 Activity，供 /cover/<id> 直出字节流（页面侧就是普通图片 URL）
            activity.setCoverIndex(coverIndex);

            org.json.JSONObject root = new org.json.JSONObject();
            root.put("games", arr);
            root.put("count", arr.length());
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "getMyLibrary 失败", t);
            return "{\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 封面 URI 优先级与 GameAdapter / 大屏保持一致：persist 优先，其次 cover */
    static String pickCover(Game g) {
        if (g == null) return null;
        if (g.coverPersistUri != null && !g.coverPersistUri.trim().isEmpty()) return g.coverPersistUri.trim();
        if (g.coverUri != null && !g.coverUri.trim().isEmpty()) return g.coverUri.trim();
        return null;
    }

    /**
     * M5-c：读取音乐厅墙数据（专辑 + 曲目 + URI 索引）
     * @return {"albums":[{id,title,gameId,trackCount,cover}], "tracks":[...], "count":N} 或 {"error":"..."}
     */
     @JavascriptInterface
     public String getMusicLibrary() {
         try {
             if (activity == null) return "{\"error\":\"宿主已销毁\"}";

             com.yuki.yukihub.data.MusicRepository repo = new com.yuki.yukihub.data.MusicRepository(activity);
             org.json.JSONArray albumsArr = new org.json.JSONArray();
             org.json.JSONArray tracksArr = new org.json.JSONArray();

             // 取墙上 12 张专辑（按最近添加优先）
             List<com.yuki.yukihub.model.MusicAlbum> wallAlbums = repo.getWallAlbums(12);
             com.yuki.yukihub.exhibition.ExhibitionActivity.MusicIndex idx = new com.yuki.yukihub.exhibition.ExhibitionActivity.MusicIndex();

             for (com.yuki.yukihub.model.MusicAlbum a : wallAlbums) {
                 org.json.JSONObject ao = new org.json.JSONObject();
                 ao.put("id", a.id);
                 ao.put("title", nz(a.title));
                 ao.put("gameId", a.gameId > 0 ? a.gameId : null);
                 ao.put("trackCount", a.trackCount);
                 
                 String coverUri = a.coverUri;
                 if (coverUri != null && !coverUri.trim().isEmpty()) {
                     idx.albumCover.put(a.id, coverUri);
                     ao.put("cover", "/music/cover/" + a.id);
                 }
                 albumsArr.put(ao);

                 // 加曲目到总列表
                 for (com.yuki.yukihub.model.MusicTrack t : repo.getTracks(a.id)) {
                     org.json.JSONObject to = new org.json.JSONObject();
                     to.put("id", t.id);
                     to.put("albumId", a.id);
                     to.put("trackNo", t.trackNo);
                     to.put("title", nz(t.title));
                     to.put("artist", nz(t.artist));
                     to.put("durationMs", t.durationMs);
                     
                     // URI
                     String audioUri = t.audioUri;
                     if (audioUri != null) {
                         idx.audio.put(t.id, audioUri);
                         to.put("audioUrl", "/music/audio/" + t.id);
                     }
                     
                     String pvUri = t.pvUri;
                     if (pvUri != null) {
                         idx.pv.put(t.id, pvUri);
                         to.put("hasPv", true);
                         to.put("pvUrl", "/music/pv/" + t.id);
                     } else {
                         to.put("hasPv", false);
                     }
                     
                     // Track cover
                     String trackCover = t.coverUri;
                     if (trackCover != null) {
                         idx.trackCover.put(t.id, trackCover);
                     }
                     
                     tracksArr.put(to);
                 }
             }
             
// 加上散装单曲虚拟专辑（如果存在）
com.yuki.yukihub.model.MusicAlbum singles = repo.virtualSinglesAlbum();
if (singles != null) {
    org.json.JSONObject saO = new org.json.JSONObject();
    saO.put("id", singles.id);
    saO.put("title", "♪ 散装单曲");
    saO.put("gameId", null);
    saO.put("trackCount", singles.trackCount);
    
    String sCover = singles.coverUri;
    if (sCover != null && !sCover.trim().isEmpty()) {
        idx.albumCover.put(singles.id, sCover);
        saO.put("cover", "/music/cover/" + singles.id);
    }
    albumsArr.put(saO);
    
    for (com.yuki.yukihub.model.MusicTrack t : repo.getTracks(singles.id)) {
        org.json.JSONObject taO = new org.json.JSONObject();
        taO.put("id", t.id);
        taO.put("albumId", singles.id);
        taO.put("trackNo", t.trackNo);
        taO.put("title", nz(t.title));
        taO.put("artist", nz(t.artist));
        taO.put("durationMs", t.durationMs);
        
        if (t.audioUri != null) {
            idx.audio.put(t.id, t.audioUri);
            taO.put("audioUrl", "/music/audio/" + t.id);
        }
        
        if (t.pvUri != null) {
            idx.pv.put(t.id, t.pvUri);
            taO.put("hasPv", true);
            taO.put("pvUrl", "/music/pv/" + t.id);
        } else {
            taO.put("hasPv", false);
        }
        
        if (t.coverUri != null) {
            idx.trackCover.put(t.id, t.coverUri);
        }
        
        tracksArr.put(taO);
    }
}

// 建立索引给 Activity
activity.setMusicIndex(idx);

org.json.JSONObject root = new org.json.JSONObject();
root.put("albums", albumsArr);
root.put("tracks", tracksArr);
root.put("count", albumsArr.length());
root.put("trackCount", tracksArr.length());
return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "getMusicLibrary 失败", t);
            return "{\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 空串保护器 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 调起音乐库管理页（从展厅内返回后自动刷新）
     */
    @JavascriptInterface
    public void openMusicLibrary() {
        main.post(() -> {
            try {
                if (activity != null) {
                    activity.markMusicRefreshPending();
                    Intent i = new Intent(activity, com.yuki.yukihub.music.MusicLibraryActivity.class);
                    activity.startActivity(i);
                }
            } catch (Throwable ignored) {}
        });
    }

    /** 页面内提示 Toast */
    @JavascriptInterface
    public void toast(final String msg) {
        if (msg == null) return;
        main.post(() -> {
            if (activity != null && !activity.isFinishing()) {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 通用日志通道（调试用） */
    @JavascriptInterface
    public void log(String msg) {
        Log.i(TAG, String.valueOf(msg));
    }

    /* ==================== 主题展台陈列槽位（M4） ==================== */
    /*
     * 4 座主题展台的"玩家自定义陈列"，存 SharedPreferences。
     *
     * 为什么用 SP 而不是数据库：
     *   · 它是本地显示偏好，不参与云同步（需求已明确）
     *   · 不需要建表 / 迁移 / 关联查询，读写就是一行
     *   · 与 yukihub_prefs 一样，属于"App 自己记住的小状态"
     *
     * 格式：逗号分隔的 4 段（gameId），空段表示空台
     *   例 "12,,45," → 0号摆12 / 1号空 / 2号摆45 / 3号空
     */

    private static final String SP_DISPLAY = "exhibition_display";
    private static final String KEY_SLOTS = "slots";

    /** 读取主题展台槽位（页面启动时调用） */
    @JavascriptInterface
    public String getDisplaySlots() {
        try {
            if (activity == null) return "";
            return activity
                    .getSharedPreferences(SP_DISPLAY, android.content.Context.MODE_PRIVATE)
                    .getString(KEY_SLOTS, "");
        } catch (Throwable t) {
            Log.w(TAG, "getDisplaySlots 失败", t);
            return "";
        }
    }

    /** 保存主题展台槽位；返回是否写入成功 */
    @JavascriptInterface
    public boolean setDisplaySlots(String csv) {
        try {
            if (activity == null) return false;
            activity
                    .getSharedPreferences(SP_DISPLAY, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SLOTS, csv == null ? "" : csv)
                    .apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setDisplaySlots 失败", t);
            return false;
        }
    }
}