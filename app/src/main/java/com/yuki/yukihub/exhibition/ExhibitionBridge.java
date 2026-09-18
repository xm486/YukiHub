package com.yuki.yukihub.exhibition;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 封面 URI 优先级与 GameAdapter / 大屏保持一致：persist 优先，其次 cover */
    static String pickCover(Game g) {
        if (g == null) return null;
        if (g.coverPersistUri != null && !g.coverPersistUri.trim().isEmpty()) return g.coverPersistUri.trim();
        if (g.coverUri != null && !g.coverUri.trim().isEmpty()) return g.coverUri.trim();
        return null;
    }

    /** 页面内提示 */
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
}