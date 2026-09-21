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
import com.yuki.yukihub.model.Game;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
                return interceptLocal(request == null ? null : request.getUrl());
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
     */
    private WebResourceResponse interceptLocal(Uri uri) {
        if (uri == null) return null;
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
