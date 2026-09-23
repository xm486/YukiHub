package com.yuki.yukihub.exhibition;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MusicRepository;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.model.MusicAlbum;
import com.yuki.yukihub.model.MusicTrack;

import android.content.res.AssetFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D 展厅宿主 Activity（M0）
 * =========================================================
 * 同一个 Activity 承载两种展厅，靠 Intent extra 区分：
 *   MODE_LOCAL  → https://exhibition.local/index.html
 *                 该域名不联网，由 shouldInterceptRequest 映射到 APK 内的
 *                 assets/exhibition/，因此离线个人展厅**完全不需要网络**。
 *   MODE_ONLINE → 服务器页面（M2 内容，M0 先显示占位说明）
 *
 * 为什么用"虚拟 origin"而不是 file://：
 *   file:// 下 ES module、fetch、TextureLoader 都会被 CORS 拦死；
 *   自建一个正规 https origin（拦截自己服务）后，ES module / localStorage /
 *   纹理加载全部正常，而且**不需要新增 androidx.webkit 依赖**
 *   （WebViewAssetLoader 需要，本项目当前没有）。
 *
 * 后续里程碑的落点（都在这一个方法里，改动很小）：
 *   · M1：/cover/<gameId> → 从 GameRepository + ContentResolver 回传本机封面字节流
 *   · M4：openAsset() 内可加"本地缓存优先、assets 兜底"，实现免编译更新展厅外观
 */
public class ExhibitionActivity extends AppCompatActivity {

    private static final String TAG = "Exhibition";

    public static final String EXTRA_MODE = "mode";
    /** 离线个人展厅（本地库存，无需联网） */
    public static final int MODE_LOCAL = 0;
    /** 在线多人展厅（服务器页面） */
    public static final int MODE_ONLINE = 1;

    /** 虚拟 origin 域名（不是真实站点，只由本 Activity 拦截服务） */
    private static final String LOCAL_HOST = "exhibition.local";
    private static final String LOCAL_BASE = "https://" + LOCAL_HOST + "/";
    /** assets 内的展厅根目录 */
    private static final String ASSET_ROOT = "exhibition/";

    /** 在线展厅地址（M2 上线） */
    private static final String ONLINE_URL = "https://yukihub.zh.kg/community/exhibition/";
    /**
     * 在线展厅是否已就绪。
     * M0 阶段服务器页面还没做，置 false 显示占位说明；
     * M2 上线后把这里改成 true 即可（一行）。
     */
    private static final boolean ONLINE_READY = false;

    private WebView webView;
    private int mode = MODE_LOCAL;

    /** id → 封面 URI（由桥接层在 getMyLibrary() 时填入，供 /cover/<id> 使用） */
    private volatile java.util.Map<Long, String> coverIndex = null;
    /** 封面请求计数（只打印前若干条，避免刷屏） */
    private int coverReqCount = 0;

    /** 由 ExhibitionBridge.getMyLibrary() 调用，建立封面索引 */
    public void setCoverIndex(java.util.Map<Long, String> index) {
        this.coverIndex = index;
    }

    /* ==================== 音乐厅索引（M5-c） ==================== */

    /**
     * 音乐媒体索引：把 trackId / albumId 映射回本机 URI。
     *
     * <p>引用模式下数据库只存 URI，页面请求 `/music/audio/<id>` 时，
     * 这里负责把 id 翻回真实 URI 再开流（页面永远看不到真实路径）。
     *
     * <p>索引在 {@code getMusicLibrary()} 时由桥接层一次性填好；
     * 若页面刷新后索引还没建（或 id 不在墙上），走 {@link #lookupMusicUri} 兜底查库。
     */
    public static class MusicIndex {
        public final Map<Long, String> audio = new HashMap<>();      // trackId → 音频 URI
        public final Map<Long, String> pv = new HashMap<>();         // trackId → PV URI
        public final Map<Long, String> trackCover = new HashMap<>(); // trackId → 单曲封面
        public final Map<Long, String> albumCover = new HashMap<>(); // albumId → 专辑封面
    }

    private volatile MusicIndex musicIndex = null;
    /** 音乐媒体请求计数（只打印前若干条，避免刷屏） */
    private int musicReqCount = 0;
    /**
     * ★ MIME 缓存：trackId/pvKey → 嗅探出的 MIME。
     * 真机实录：Range 续传（start>0）时文件头已被跳过、没法嗅探，
     * 上一版用扩展名兜底 → 假 mp3(实为ogg) 的续传响应变成 octet-stream，
     * 而 chromium 要求同一资源的所有 Range 响应 Content-Type 一致 → NotSupportedError。
     * 修法：首次请求嗅探后记住，后续 Range 全部复用。
     */
    private final java.util.Map<String, String> mediaMimeCache = new java.util.HashMap<>();

    /** 由 ExhibitionBridge.getMusicLibrary() 调用，建立音乐索引 */
    public void setMusicIndex(MusicIndex idx) {
        this.musicIndex = idx;
    }

    /** 是否已从音乐库返回（用于页面自动刷新） */
    private volatile boolean musicRefreshPending = false;

    /** 桥接层调起音乐库页面前调用 */
    public void markMusicRefreshPending() {
        musicRefreshPending = true;
        // ★ 坑 19：音乐库可能变过（换歌/换 PV），媒体缓存全部作废重拉
        try {
            File dir = new File(getCacheDir(), "exmedia");
            File[] files = dir.listFiles();
            if (files != null) {
                int n = 0;
                for (File f : files) { if (f.delete()) n++; }
                Log.i(TAG, "媒体缓存已清空: " + n + " 个文件");
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mode = (getIntent() == null)
                ? MODE_LOCAL
                : getIntent().getIntExtra(EXTRA_MODE, MODE_LOCAL);

        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF05070C));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 逛展厅时别息屏

        // ★ 主动申请高刷新率。
        //   背景：Android 默认会把"非白名单 App"压在 60Hz（厂商省电策略），
        //        所以同一个 APK 在有些设备上只有 60fps、在已放行的设备上能跑 90/120。
        //   做法：把 Window 的首选刷新率设为屏幕支持的**最高档**。
        //   注意：这只是一个"请求"，厂商仍可无视；但能显著提高拿到高刷的概率，
        //        对已经手动加过白名单的用户则完全生效。
        applyHighRefreshRate();

        // ⚠️ 顺序很重要：applyImmersive() 会取 getWindow().getInsetsController()，
        //    而部分机型（实测 OPlus 系）在 DecorView 尚未创建时会直接抛 NPE
        //    （PhoneWindow.getInsetsController → mDecor.getWindowInsetsController）。
        //    所以必须【先 setContentView（在 createWebView 内）再进沉浸式】。
        createWebView();
        applyImmersive();

        if (mode == MODE_LOCAL) {
            webView.loadUrl(LOCAL_BASE + "index.html");
        } else if (ONLINE_READY) {
            webView.loadUrl(ONLINE_URL);
        } else {
            webView.loadDataWithBaseURL(null, onlinePlaceholderHtml(), "text/html", "utf-8", null);
        }
    }

    /* ==================== 高刷新率申请 ==================== */

    /**
     * 把当前 Window 的首选刷新率设为屏幕支持的最高值。
     *
     * 为什么需要它：Android（尤其国产 ROM）默认把"非系统/未上白名单"的应用
     * 限制在 60Hz。同一个 APK 在不同设备上帧率不一样，就是这个原因。
     * 这里显式请求最高刷新率，能提高拿到高刷的概率（厂商仍可能无视）。
     *
     * 兼容性：
     *   · API 30+ 用 Display.getSupportedModes() 找最高 refreshRate
     *   · API 23~29 用 Display.getSupportedRefreshRates()（已废弃但可用）
     *   · 都失败就退回"设 120"这个常见值
     * 整个过程 try 包住：任何机型异常都不该影响展厅启动。
     */
    @SuppressWarnings("deprecation")
    private void applyHighRefreshRate() {
        try {
            float best = 0f;
            Display display = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display = getDisplay();
            }
            if (display == null) {
                display = getWindowManager().getDefaultDisplay();
            }
            if (display == null) return;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Display.Mode[] modes = display.getSupportedModes();
                if (modes != null) {
                    for (Display.Mode m : modes) {
                        if (m.getRefreshRate() > best) best = m.getRefreshRate();
                    }
                }
            }
            if (best <= 0f) {
                float[] rates = display.getSupportedRefreshRates();
                if (rates != null) {
                    for (float r : rates) if (r > best) best = r;
                }
            }
            if (best <= 0f) best = 120f;    // 兜底：常见高刷值

            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = best;
            getWindow().setAttributes(lp);
            Log.i(TAG, "申请高刷新率: " + best + "Hz");
        } catch (Throwable t) {
            Log.w(TAG, "申请高刷新率失败（不影响使用）", t);
        }
    }

    /* ==================== WebView 与拦截 ==================== */

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF05070C);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // localStorage（M3 会用到）
        s.setAllowFileAccess(false);                   // 不放文件系统，一切走虚拟 origin
        s.setAllowContentAccess(false);                // 封面由原生读，交给页面时已是字节流
        s.setMediaPlaybackRequiresUserGesture(false);  // M4 环境音可用
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return interceptLocal(request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = (request == null) ? null : request.getUrl();
                if (u == null) return false;
                String host = (u.getHost() == null) ? "" : u.getHost().toLowerCase();

                // 虚拟 origin 与自家站点：留在 WebView 内
                if (LOCAL_HOST.equals(host) || host.endsWith("zh.kg") || host.endsWith("kesug.com")) {
                    return false;
                }
                // 其它外链交给系统浏览器
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Throwable ignored) {
                    // 没有可用浏览器时不阻断页面
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String desc = (error == null) ? "" : String.valueOf(error.getDescription());
                    Log.w(TAG, "主文档加载失败: " + desc);
                    webView.loadDataWithBaseURL(null, loadErrorHtml(desc), "text/html", "utf-8", null);
                }
            }
        });

        webView.addJavascriptInterface(new ExhibitionBridge(this), ExhibitionBridge.NAME);
        setContentView(webView);
    }

/**
         * 虚拟 origin 的"服务器"。
         * 只服务 exhibition.local，其它域名返回 null（照常走网络）。
         * M5-c：接受 WebResourceRequest 以便读取 Range 头（音频/视频拖动进度条必需）。
         */
        private WebResourceResponse interceptLocal(WebResourceRequest request) {
            if (request == null) return null;
            Uri uri = request.getUrl();
        String host = uri.getHost();
        if (host == null || !LOCAL_HOST.equalsIgnoreCase(host)) return null;

        String path = uri.getPath();
        if (path == null || path.isEmpty()) path = "/index.html";

        // M1：本机游戏封面（coverUri → ContentResolver → 字节流）
        // 走虚拟 origin 直出真实图片流，页面用 TextureLoader 直接吃，
        // 不需要 base64（避免几百张封面把内存吃爆），也不存在跨域问题。
        if (path.startsWith("/cover/")) {
            return serveCover(path.substring("/cover/".length()));
        }

        // M5-c：音乐厅媒体（音频/PV/封面）——支持 Range 拖动进度条
        if (path.startsWith("/music/audio/")) {
            return serveMediaAudio(path.substring("/music/audio/".length()), request);
        }
        if (path.startsWith("/music/pv/")) {
            return serveMediaPv(path.substring("/music/pv/".length()), request);
        }
        if (path.startsWith("/music/cover/")) {
            return serveMusicCover(path.substring("/music/cover/".length()));
        }

        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.isEmpty()) rel = "index.html";

        try {
            // M4 落点：可在此先查缓存目录，未命中再回退 assets
            if ("index.html".equals(rel)) {
                // 关键诊断点：这行出现就说明虚拟 origin 拦截生效了
                Log.i(TAG, "拦截生效，主文档: " + path);
            }
            InputStream is = getAssets().open(ASSET_ROOT + rel);
            return new WebResourceResponse(
                    guessMime(rel),
                    isTextual(rel) ? "utf-8" : null,
                    200, "OK",
                    noCacheHeaders(),
                    is);
        } catch (IOException e) {
            Log.w(TAG, "资产缺失: " + rel);
            return plainText(404, "资产缺失：" + rel);
        }
    }

    /* ==================== M1：本机封面直出 ==================== */

    /** 把 /cover/<id> 映射到本机封面（content:// 或 file://），直出真实图片流 */
    private WebResourceResponse serveCover(String idStr) {
        long id;
        try {
            id = Long.parseLong(idStr.trim());
        } catch (Throwable t) {
            return plainText(404, "非法的封面 id");
        }

        String uriStr = null;
        java.util.Map<Long, String> idx = coverIndex;
        if (idx != null) uriStr = idx.get(id);
        if (uriStr == null) uriStr = lookupCoverUri(id);   // 桥接尚未建索引时的兜底
        if (uriStr == null) return plainText(404, "该游戏没有封面");

        InputStream raw = null;
        try {
            raw = getContentResolver().openInputStream(Uri.parse(uriStr));
            if (raw == null) return plainText(404, "无法打开封面流");
            PushbackInputStream in = new PushbackInputStream(raw, 12);
            // WebView 需要准确的图片 MIME，否则纹理加载会失败（封面多来自相册，扩展名不可信）
            String mime = sniffImageMime(in);
            if (coverReqCount < 40) {
                coverReqCount++;
                Log.i(TAG, "封面请求 #" + coverReqCount + " id=" + id + " mime=" + mime + " uri=" + uriStr);
            }
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "max-age=86400");
            return new WebResourceResponse(mime, null, 200, "OK", h, in);
        } catch (Throwable t) {
            Log.w(TAG, "封面读取失败 id=" + id + " uri=" + uriStr + " : " + t.getMessage());
            try { if (raw != null) raw.close(); } catch (Throwable ignored) { }
            return plainText(404, "封面读取失败");
        }
    }

    /** 兜底：扫一遍库存找这个 id 的封面 URI */
    private String lookupCoverUri(long id) {
        try {
            GameRepository repo = new GameRepository(this);
            for (Game g : repo.getAll()) {
                if (g.id == id) return ExhibitionBridge.pickCover(g);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /* ==================== M5-c：音乐厅媒体（音频/PV/封面） ==================== */

    /** 兜底：用索引查找音乐 URI，找不到则查库 */
    private String lookupMusicUri(long id, boolean isTrack) {
        // 优先从索引找
        MusicIndex idx = musicIndex;
        if (idx != null) {
            if (isTrack) {
                String s = idx.audio.get(id);
                if (s != null) return s;
                s = idx.pv.get(id);
                if (s != null) return s;
            }
            // album cover check handled separately
        }
        // 索引没有 -> 查库兜底
        try {
            MusicRepository repo = new MusicRepository(this);
            for (MusicAlbum a : repo.getAllAlbums()) {
                if (a.id == id) return a.coverUri;
                for (MusicTrack t : repo.getTracks(a.id)) {
                    if (t.id == id) return isTrack ? t.audioUri : t.coverUri;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 专辑/单曲封面 —— 不走 Range，直接流式 */
    private WebResourceResponse serveMusicCover(String idStr) {
        long id;
        try {
            id = Long.parseLong(idStr.trim());
        } catch (Throwable t) {
            return plainText(404, "非法 ID");
        }

        String uriStr = null;
        MusicIndex idx = musicIndex;
        if (idx != null) {
            uriStr = idx.trackCover.get(id);   // 单曲封面优先
            if (uriStr == null) uriStr = idx.albumCover.get(id); // 其次专辑封面
        }
        if (uriStr == null) {
            // 兜底：albumId 也可能被当作 trackId 传过来（页面 bug），双向查询
            uriStr = lookupMusicUri(id, false);
            if (uriStr == null) uriStr = lookupMusicUri(id, true); // 再试试当作 track
        }
        if (uriStr == null) return plainText(404, "未找到封面");

        InputStream raw = null;
        try {
            raw = getContentResolver().openInputStream(Uri.parse(uriStr));
            if (raw == null) return plainText(404, "无法打开封面流");
            PushbackInputStream in = new PushbackInputStream(raw, 12);
            String mime = sniffImageMime(in);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "max-age=86400");
            return new WebResourceResponse(mime, null, 200, "OK", h, in);
        } catch (Throwable t) {
            Log.w(TAG, "音乐封面读取失败 id=" + id + " uri=" + uriStr + " : " + t.getMessage());
            try { if (raw != null) raw.close(); } catch (Throwable ignored) { }
            return plainText(404, "封面读取失败");
        }
    }

    /** 音频流 —— 支持 Range 拖动进度条（关键！） */
    private WebResourceResponse serveMediaAudio(String idStr, WebResourceRequest request) {
        return serveMedia(idStr, request, true); // isAudio=true
    }

    /** PV 视频流 —— 支持 Range（同音频） */
    private WebResourceResponse serveMediaPv(String idStr, WebResourceRequest request) {
        return serveMedia(idStr, request, false); // isAudio=false
    }

    /** 通用媒体流处理器（音频/PV）—— 核心实现 Range + MIME 嗅探 */
    private WebResourceResponse serveMedia(String idStr, WebResourceRequest request, boolean isAudio) {
        long id;
        try {
            id = Long.parseLong(idStr.trim());
        } catch (Throwable t) {
            return plainText(404, "非法 ID");
        }

        // 先查索引
        MusicIndex idx = musicIndex;
        String uriStr = null;
        if (idx != null) {
            if (isAudio) uriStr = idx.audio.get(id);
            else uriStr = idx.pv.get(id);
        }
        if (uriStr == null) {
            uriStr = lookupMusicUri(id, true); // 兜底查库
        }
        if (uriStr == null) return plainText(404, "未找到媒体");
        Uri uri = Uri.parse(uriStr);

        // ★★★ 坑 19（真机实录）：SAF 管道（AFD / openInputStream）的流定位
        //    全部不可靠——skip 循环不行，FileChannel.position 也不行（平台实现
        //    在 offset 语义上不老实）。根治：**首次请求把资源完整复制到应用
        //    私有缓存**，之后全部从真实本地文件（FileInputStream）服务——
        //    本地 fd 的 lseek 是内核语义，绝无歧义。代价只是首次 ~100ms 级复制。
        File local = mediaCacheFile(id, isAudio);
        if (local == null || !local.isFile() || local.length() <= 0) {
            if (!copyToCache(id, isAudio, uri)) {
                Log.w(TAG, "缓存复制失败 id=" + id + " uri=" + uriStr);
                return plainText(404, "媒体缓存失败");
            }
            local = mediaCacheFile(id, isAudio);
        }
        uri = Uri.fromFile(local);   // 后续全部按本地文件走

        long total = local.length();     // ★ 本地文件直接 stat，长度无歧义
        String mime = null;

        // 解析 Range 头
        String rangeHeader = null;
        if (request != null) {
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if ("Range".equalsIgnoreCase(e.getKey())) {
                        rangeHeader = e.getValue();
                        break;
                    }
                }
            }
        }

        long start = 0, end = -1;
        boolean hasRange = false;
        if (rangeHeader != null && total > 0) {
            String spec = rangeHeader.trim();
            int eq = spec.indexOf('=');
            if (eq >= 0) spec = spec.substring(eq + 1);
            int comma = spec.indexOf(',');
            if (comma >= 0) spec = spec.substring(0, comma); // 只取第一段
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String s = spec.substring(0, dash).trim();
                String eStr = spec.substring(dash + 1).trim();
                try {
                    if (!s.isEmpty()) start = Long.parseLong(s);
                    if (!eStr.isEmpty()) end = Long.parseLong(eStr);
                } catch (NumberFormatException ignored) {}
                hasRange = true;
                if (start < 0) start = 0;
                if (start >= total) return plainText(416, "Invalid range start");
                if (end < 0 || end >= total) end = total - 1;
                if (end < start) return plainText(416, "Invalid range end");
            }
        }

        // 开流（★ 全部走本地真文件——坑 19 根治，本地 fd 的 lseek 是内核语义）
        InputStream in = null;
        try {
            in = new FileInputStream(local);

            // ★ MIME 决策（真机实录见 mediaMimeCache 注释）：
            //   同一资源的所有请求（含 Range 续传）必须返回一致 Content-Type。
            //   首次请求（start=0）→ 魔数嗅探并缓存；Range 续传 → 直接用缓存；
            //   缓存未命中（极端时序）→ 扩展名兜底。
            String cacheKey = (isAudio ? "a" : "v") + id;
            synchronized (mediaMimeCache) {
                mime = mediaMimeCache.get(cacheKey);
            }
            if (mime == null && !(hasRange && start > 0)) {
                PushbackInputStream pin = new PushbackInputStream(in, 12);
                mime = sniffMediaMime(pin, isAudio, uriStr);
                in = pin;
                synchronized (mediaMimeCache) { mediaMimeCache.put(cacheKey, mime); }
            }
            if (mime == null) {
                mime = guessMediaMime(isAudio, uriStr);
                if (!"application/octet-stream".equals(mime)) {
                    synchronized (mediaMimeCache) { mediaMimeCache.put(cacheKey, mime); }
                }
            }
            if (musicReqCount < 12) {
                musicReqCount++;
                Log.i(TAG, "音乐流 #" + musicReqCount + " id=" + id + " mime=" + mime + " total=" + total
                        + " range=" + (hasRange ? (start + "-" + end) : "-")
                        + " src=" + (local != null && local.isFile() ? "cache" : "saf"));
            }

            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            // ★★★ 坑 20（四轮真机实录的最终结论）：这条 WebView 通道上
            //   **206 全灭、200 全活**——
            //   · 四轮构建里所有能播的响应都是 200 全量（MP3 顺序流）；
            //   · 一旦 chromium 发起 Range 续传（mp4 找 moov / ogg 找页结构），
            //     我们的 206 无论数据多正确都会陷入"拉同一段×N"死循环；
            //   · 与 skip/FileChannel/本地缓存都无关（全试过，行为分毫不变）。
            //   修法：**不再支持 Range，永远 200 全量**。HTTP 语义允许服务器
            //   忽略 Range 头（客户端必须接受 200 全量响应）——chromium 会
            //   自动切换到"顺序下载"模式播放，这正是 MP3 一直在走的、被
            //   本设备反复证明可行的路径。本地缓存文件顺序读极快，全量秒级缓冲。
            if (musicReqCount < 12) {
                musicReqCount++;
                Log.i(TAG, "音乐流 #" + musicReqCount + " id=" + id + " mime=" + mime + " total=" + total
                        + " range要求=" + (hasRange ? (start + "-" + end) + "(忽略)" : "-")
                        + " src=" + (local != null && local.isFile() ? "cache" : "saf"));
            }
            if (total > 0) h.put("Content-Length", String.valueOf(total));
            return new WebResourceResponse(mime, null, 200, "OK", h, in);
        } catch (Throwable t) {
            Log.w(TAG, "媒体读取失败 id=" + id + " uri=" + uriStr + " : " + t.getMessage());
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            return plainText(404, "媒体读取失败");
        }
    }

    /**
     * ★ 坑 19 辅助：媒体私有缓存路径
     *   /data/data/<pkg>/cache/exmedia/{a|v}<id>.bin
     *   bin 后缀是故意的：绝不让任何组件按扩展名猜 MIME（MIME 只信魔数嗅探）。
     */
    private File mediaCacheFile(long id, boolean isAudio) {
        try {
            File dir = new File(getCacheDir(), "exmedia");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return new File(dir, (isAudio ? "a" : "v") + id + ".bin");
        } catch (Throwable t) {
            return null;
        }
    }

    /** ★ 坑 19 辅助：把 SAF 资源完整复制到私有缓存（顺序读，不用任何 skip/seek） */
    private boolean copyToCache(long id, boolean isAudio, Uri uri) {
        InputStream src = null;
        java.io.FileOutputStream dst = null;
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            src = getContentResolver().openInputStream(uri);
            if (src == null) return false;
            File out = mediaCacheFile(id, isAudio);
            if (out == null) return false;
            dst = new java.io.FileOutputStream(out);
            byte[] buf = new byte[256 * 1024];
            long copied = 0;
            int n;
            while ((n = src.read(buf)) > 0) {
                dst.write(buf, 0, n);
                copied += n;
            }
            dst.flush();
            dst.getFD().sync();
            Log.i(TAG, "媒体缓存完成 id=" + id + " bytes=" + copied
                    + " 耗时=" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
            return copied > 0;
        } catch (Throwable t) {
            Log.w(TAG, "媒体缓存异常 id=" + id + " : " + t.getMessage());
            return false;
        } finally {
            try { if (src != null) src.close(); } catch (Throwable ignored) {}
            try { if (dst != null) dst.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * ★ 媒体 MIME：魔数优先（真机实录：用户目录里一半的"mp3"实际是 OGG 改后缀——
     * 按扩展名报 audio/mpeg，Chromium 拿 MP3 解码器吃 OGG 数据 → NotSupportedError）。
     *
     * 策略：先从流头读 12 字节嗅探真实格式；读不出再回退扩展名。
     * PushbackInputStream 把字节推回流，后续消费不受影响。
     */
    private static String sniffMediaMime(PushbackInputStream in, boolean isAudio, String pathOrUri) {
        try {
            byte[] b = new byte[12];
            int n = in.read(b);
            if (n > 0) in.unread(b, 0, n);
            if (n >= 12 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "audio/mpeg";       // ID3 头的 MP3
            if (n >= 4 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "audio/flac";
            if (n >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "audio/ogg";
            if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
                // ftyn 盒：可能是 M4A 音频或 MP4 视频——按用途给（网页里 m4a 也能用 audio/mp4）
                return isAudio ? "audio/mp4" : "video/mp4";
            }
            if (n >= 4 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return "audio/wav";
            if (n >= 4 && b[0] == (byte)0x1A && b[1] == (byte)0x45 && b[2] == (byte)0xDF && b[3] == (byte)0xA3) return "video/webm"; // EBML
            // MP3 裸帧同步（0xFFEx/0xFFFx）
            if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "audio/mpeg";
            if (n >= 4 && b[0] == (byte)0x30 && b[1] == (byte)0x26 && b[2] == (byte)0xB2 && b[3] == (byte)0x75) return "video/x-ms-wmv"; // ASF(WMA)
        } catch (Throwable ignored) { }
        return guessMediaMime(isAudio, pathOrUri);   // 兜底：扩展名
    }

    /** 按扩展名猜媒体 MIME（sniffMediaMime 的兜底） */
    private static String guessMediaMime(boolean isAudio, String pathOrUri) {
        String p = pathOrUri.toLowerCase();
        if (p.contains(".mp3")) return "audio/mpeg";
        if (p.contains(".flac")) return "audio/flac";
        if (p.contains(".m4a") || p.contains(".aac")) return "audio/mp4";
        if (p.contains(".wav")) return "audio/wav";
        if (p.contains(".ogg") || p.contains(".oga")) return "audio/ogg";
        if (p.contains(".opus")) return "audio/opus";
        if (p.contains(".mp4")) return "video/mp4";
        if (p.contains(".webm")) return "video/webm";
        if (p.contains(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }

    /** 流跳过 n 字节（可靠版本）*/
    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long s = in.skip(remaining);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("stream ended early");
                remaining--;
            } else {
                remaining -= s;
            }
        }
    }

    /** 限制读取长度的过滤器 */
    private static class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
            this.remaining = limit;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long toSkip = Math.min(n, remaining);
            long skipped = in.skip(toSkip);
            remaining -= skipped;
            return skipped;
        }
    }

    /** 按魔数嗅探图片类型 */
    private static String sniffImageMime(PushbackInputStream in) {
        try {
            byte[] b = new byte[12];
            int n = in.read(b);
            if (n > 0) in.unread(b, 0, n);
            if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
            if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
            if (n >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
            if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
            if (n >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        } catch (Throwable ignored) { }
        return "image/jpeg";
    }

    private static Map<String, String> noCacheHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return h;
    }

    private static WebResourceResponse plainText(int code, String msg) {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse("text/plain", "utf-8", code,
                code == 404 ? "Not Found" : "OK",
                noCacheHeaders(), new ByteArrayInputStream(body));
    }

    /** 关键：模块脚本必须是 JS 的 MIME，否则 WebView 会拒绝执行（白屏最常见原因） */
    private static String guessMime(String rel) {
        String p = rel.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".ogg")) return "audio/ogg";
        if (p.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private static boolean isTextual(String rel) {
        String p = rel.toLowerCase();
        return p.endsWith(".html") || p.endsWith(".htm")
                || p.endsWith(".js") || p.endsWith(".mjs")
                || p.endsWith(".css") || p.endsWith(".json")
                || p.endsWith(".svg");
    }

    /* ==================== 沉浸式 ==================== */

    private void applyImmersive() {
        try {
            // DecorView 未就绪时不做（某些机型的 getInsetsController 会 NPE）
            if (getWindow() == null || getWindow().getDecorView() == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable t) {
            // 沉浸式失败不影响展厅使用，降级为带系统栏显示
            Log.w(TAG, "沉浸式设置失败（已降级）: " + t.getMessage());
        }
    }

    /* ==================== 生命周期 ==================== */
@Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        
        // M5-c：从音乐库返回后自动刷新音乐数据（如果页面已注册钩子）
        if (musicRefreshPending) {
            musicRefreshPending = false;
            try {
                webView.evaluateJavascript(
                    "window.__exhibitionRefreshMusic && window.__exhibitionRefreshMusic()", 
                    null);
            } catch (Throwable ignored) {}
        }
        
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface(ExhibitionBridge.NAME);
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /* ==================== 占位 / 兜底页面 ==================== */

    private static String baseCss() {
        return "<style>"
                + "html,body{margin:0;height:100%;background:#05070C;color:#EAF2FF;"
                + "font-family:-apple-system,'Noto Sans CJK SC',Roboto,sans-serif;}"
                + ".wrap{height:100%;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;gap:12px;padding:28px;text-align:center;}"
                + "h2{margin:0;font-size:17px;color:#FFB86B;font-weight:700;}"
                + "p{margin:0;font-size:13px;line-height:1.8;color:#9FB3CC;max-width:640px;}"
                + "code{font-size:12px;color:#C9D6E6;background:rgba(0,0,0,.45);"
                + "padding:8px 12px;border-radius:8px;max-width:640px;word-break:break-all;}"
                + "</style>";
    }

    private static String onlinePlaceholderHtml() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" + baseCss() + "</head><body>"
                + "<div class=\"wrap\">"
                + "<h2>在线多人展厅正在建设中</h2>"
                + "<p>这里是<b>全站博物馆 + 玩家交流广场</b>，计划在 M2 上线（公共展厅数据、"
                + "\"谁玩过这款\"、玩家化身走动）。<br>"
                + "当前是 M0，先验证<b>离线个人展厅</b>的渲染性能与走动手感。</p>"
                + "<p>目标地址：<code>" + ONLINE_URL + "</code></p>"
                + "</div></body></html>";
    }

    private static String loadErrorHtml(String desc) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" + baseCss() + "</head><body>"
                + "<div class=\"wrap\">"
                + "<h2>展厅页面加载失败</h2>"
                + "<p>两种可能：<br>"
                + "① APK 里缺少资产 —— 确认 <code>assets/exhibition/</code> 下有 index.html、exh.js、"
                + "vendor/three.module.min.js；<br>"
                + "② 虚拟 origin 拦截未生效 —— 用 <code>adb logcat -s Exhibition</code> 看是否有"
                + "「资产缺失」以外的报错。</p>"
                + "<code>" + (desc == null ? "" : desc) + "</code>"
                + "</div></body></html>";
    }
}
