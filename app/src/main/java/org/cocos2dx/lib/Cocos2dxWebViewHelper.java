package org.cocos2dx.lib;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.widget.FrameLayout;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class Cocos2dxWebViewHelper {
    private static Cocos2dxActivity sCocos2dxActivity;
    private static Handler sHandler;
    private static FrameLayout sLayout;
    private static int viewTag;
    private static SparseArray<Cocos2dxWebView> webViews = new SparseArray<>();

    public Cocos2dxWebViewHelper(FrameLayout layout) {
        sCocos2dxActivity = (Cocos2dxActivity) Cocos2dxActivity.getContext();
        sLayout = layout;
        sHandler = new Handler(Looper.getMainLooper());
    }

    private static native void didFailLoading(int tag, String url);
    private static native void didFinishLoading(int tag, String url);
    private static native void onJsCallback(int tag, String message);
    private static native boolean shouldStartLoading(int tag, String url);

    public static void _didFailLoading(int tag, String url) { didFailLoading(tag, url); }
    public static void _didFinishLoading(int tag, String url) { didFinishLoading(tag, url); }
    public static void _onJsCallback(int tag, String msg) { onJsCallback(tag, msg); }
    public static boolean _shouldStartLoading(int tag, String url) { return !shouldStartLoading(tag, url); }

    public static <T> T callInMainThread(Callable<T> c) throws Exception {
        FutureTask<T> task = new FutureTask<>(c);
        if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else sHandler.post(task);
        return task.get();
    }

    public static int createWebView() {
        final int tag = viewTag++;
        if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> {
            Cocos2dxWebView w = new Cocos2dxWebView(sCocos2dxActivity);
            webViews.put(tag, w);
            if (sLayout != null) sLayout.addView(w);
        });
        return tag;
    }
    public static void removeWebView(final int tag) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null&&sLayout!=null)sLayout.removeView(w); webViews.remove(tag); }); }
    public static void setVisible(final int tag, final boolean visible) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.setVisibility(visible?android.view.View.VISIBLE:android.view.View.GONE); }); }
    public static void setWebViewRect(final int tag, final int x, final int y, final int width, final int height) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null){ FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,height); lp.leftMargin=x; lp.topMargin=y; w.setLayoutParams(lp);} }); }
    public static void loadUrl(final int tag, final String url) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.loadUrl(url); }); }
    public static void loadFile(int tag, String file) { loadUrl(tag, "file://" + file); }
    public static void loadData(final int tag, final String data, final String mime, final String encoding, final String baseUrl) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.loadDataWithBaseURL(baseUrl, data, mime, encoding, null); }); }
    public static void loadHTMLString(int tag, String html, String baseUrl) { loadData(tag, html, "text/html", "UTF-8", baseUrl); }
    public static void reload(final int tag) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.reload(); }); }
    public static void stopLoading(final int tag) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.stopLoading(); }); }
    public static void goBack(final int tag) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.goBack(); }); }
    public static void goForward(final int tag) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.goForward(); }); }
    public static boolean canGoBack(int tag) { Cocos2dxWebView w=webViews.get(tag); return w != null && w.canGoBack(); }
    public static boolean canGoForward(int tag) { Cocos2dxWebView w=webViews.get(tag); return w != null && w.canGoForward(); }
    public static void evaluateJS(final int tag, final String js) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.evaluateJavascript(js, null); }); }
    public static void setJavascriptInterfaceScheme(int tag, String scheme) { }
    public static void setScalesPageToFit(final int tag, final boolean scales) { if (sCocos2dxActivity != null) sCocos2dxActivity.runOnUiThread(() -> { Cocos2dxWebView w=webViews.get(tag); if(w!=null) w.getSettings().setUseWideViewPort(scales); }); }
}