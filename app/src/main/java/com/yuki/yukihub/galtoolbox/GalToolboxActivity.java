package com.yuki.yukihub.galtoolbox;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gal 工具箱的新网页版宿主。虚拟 HTTPS origin 映射到 assets/galtoolbox，
 * 不开放 file/content 访问、不暴露 YukiHub 身份或任意 JavaScript 原生桥。
 * 内部页面在本页导航，第三方站点与下载交给系统浏览器/对应 App。
 */
public class GalToolboxActivity extends AppCompatActivity {
    private static final String HOST = "galtoolbox.local";
    private static final String HOME = "https://" + HOST + "/index.html";
    private WebView webView;
    private TextView title;
    private ProgressBar progress;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141B2D);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(4), 0);
        TextView back = button("‹", "返回；工具箱首页时退出");
        back.setOnClickListener(v -> navigateBack());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = button("Gal工具箱", "当前页面标题");
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView menu = button("⋮", "工具箱菜单");
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        com.yuki.yukihub.ui.GamepadFocus.attach(this);
        // HTML input/textarea 的输入法连接由 WebView 提供，触摸时必须允许宿主获焦。
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        webView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && !v.hasFocus()) {
                v.requestFocus();
            }
            return false; // 不吞事件，交给网页决定是否弹输入法，滚动/链接照常工作
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (HOST.equalsIgnoreCase(request.getUrl().getHost())) { return assetResponse(request); }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 不抢走 giscus 等 iframe 内部的正常导航。
                if (!request.isForMainFrame()) { return false; }
                return route(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return route(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!isLocal(Uri.parse(url))) { return; }
                // 原网页大量使用 target=_blank / window.open（含 noopener）。
                // 转到当前窗口，再由主框架导航路由区分内部页和外部站点；不建立第二个 WebView。
                view.evaluateJavascript("(function(){window.open=function(u){if(u)location.href=new URL(u,location.href).href;return null;};"
                        + "document.addEventListener('click',function(e){var a=e.target.closest?e.target.closest('a'):null;"
                        + "if(a&&a.target==='_blank'){a.target='_self';}},true);})()", null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public void onReceivedTitle(WebView view, String value) {
                title.setText(value == null || value.isEmpty() ? "Gal工具箱" : value);
            }
        });
        webView.setDownloadListener((url, ua, disposition, mime, length) -> openExternal(Uri.parse(url)));
        // 复用原壳的「长按图片」能力，但不注入可被第三方脚本调用的原生桥。
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = webView.getHitTestResult();
            if (hit != null && (hit.getType() == WebView.HitTestResult.IMAGE_TYPE
                    || hit.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && hit.getExtra() != null) {
                PopupMenu popup = new PopupMenu(this, webView);
                popup.getMenu().add("复制图片链接").setOnMenuItemClickListener(item -> { copy(hit.getExtra()); return true; });
                Uri image = Uri.parse(hit.getExtra());
                if (!isLocal(image)) {
                    popup.getMenu().add("在浏览器打开 / 保存图片").setOnMenuItemClickListener(item -> { openExternal(image); return true; });
                }
                popup.show();
                return true;
            }
            return false;
        });
        if (state == null || webView.restoreState(state) == null) { webView.loadUrl(HOME); }
    }

    private static boolean isLocal(Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean route(Uri uri) {
        if (isLocal(uri)) { return false; }
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        // 拒绝本地文件和可执行 URL；不转发 intent:// 中的 component/extras。
        if (scheme == null || "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme)
                || "javascript".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)
                || "intent".equalsIgnoreCase(scheme) || HOST.equalsIgnoreCase(uri.getHost())) {
            Toast.makeText(this, "此链接不能交给外部应用打开", Toast.LENGTH_SHORT).show();
            return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (Exception e) { Toast.makeText(this, "没有可打开此链接的应用，可在菜单中复制链接", Toast.LENGTH_LONG).show(); }
    }

    private WebResourceResponse assetResponse(WebResourceRequest request) {
        try {
            Uri uri = request.getUrl();
            if (!isLocal(uri) || !"GET".equalsIgnoreCase(request.getMethod())) { return missing(); }
            String path = uri.getPath();
            if (path == null || "/".equals(path)) { path = "/index.html"; }
            for (String part : path.split("/")) { if ("..".equals(part) || ".".equals(part)) { return missing(); } }
            if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) { return missing(); }
            String asset = "galtoolbox/" + path.substring(1);
            byte[] bytes;
            try (InputStream in = getAssets().open(asset);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); }
                bytes = out.toByteArray();
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept-Ranges", "bytes");
            headers.put("X-Content-Type-Options", "nosniff");
            int start = 0, end = bytes.length - 1, code = 200;
            String range = null;
            for (Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                if ("Range".equalsIgnoreCase(h.getKey())) { range = h.getValue(); break; }
            }
            if (range != null && range.matches("bytes=\\d+-\\d*")) {
                String[] bounds = range.substring(6).split("-", -1);
                long first = Long.parseLong(bounds[0]);
                long last = bounds[1].isEmpty() ? end : Long.parseLong(bounds[1]);
                if (first >= bytes.length || first > last) {
                    headers.put("Content-Range", "bytes */" + bytes.length);
                    return new WebResourceResponse(mime(path), null, 416, "Range Not Satisfiable", headers,
                            new ByteArrayInputStream(new byte[0]));
                }
                start = (int) first;
                end = (int) Math.min(last, end);
                code = 206;
                headers.put("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
            }
            int length = Math.max(0, end - start + 1);
            headers.put("Content-Length", String.valueOf(length));
            String type = mime(path);
            String encoding = type.startsWith("text/") || type.contains("javascript") ? "UTF-8" : null;
            return new WebResourceResponse(type, encoding, code, code == 206 ? "Partial Content" : "OK", headers,
                    new ByteArrayInputStream(bytes, start, length));
        } catch (Exception e) { return missing(); }
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        // 原 favicon.ico 的真实格式是 PNG。
        if (p.endsWith(".png") || p.endsWith(".ico")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("工具箱首页").setOnMenuItemClickListener(i -> { webView.loadUrl(HOME); return true; });
        popup.getMenu().add("刷新").setOnMenuItemClickListener(i -> { webView.reload(); return true; });
        popup.getMenu().add("前进").setOnMenuItemClickListener(i -> { if (webView.canGoForward()) webView.goForward(); return true; });
        popup.getMenu().add("复制当前链接").setOnMenuItemClickListener(i -> { copy(webView.getUrl()); return true; });
        popup.getMenu().add("退出工具箱").setOnMenuItemClickListener(i -> { finish(); return true; });
        popup.show();
    }

    private void copy(String value) {
        if (value == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText("Gal工具箱", value)); }
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
    }

    private TextView button(String text, String description) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(22);
        v.setTextColor(0xFFF0F4FA);
        v.setGravity(Gravity.CENTER);
        v.setContentDescription(description);
        v.setFocusable(true);
        return v;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void navigateBack() { if (webView.canGoBack()) webView.goBack(); else finish(); }
    @Override public void onBackPressed() { navigateBack(); }
    @Override protected void onSaveInstanceState(Bundle out) { webView.saveState(out); super.onSaveInstanceState(out); }
    @Override protected void onPause() { webView.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (webView != null) webView.onResume(); }
    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
