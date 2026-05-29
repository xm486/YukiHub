package org.cocos2dx.lib;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.DisplayMetrics;
import java.util.Locale;

public class Cocos2dxHelper {
    private static Activity sActivity;
    private static Context sContext;
    private static AssetManager sAssetManager;
    private static String sPackageName;
    private static String sFileDirectory;
    private static boolean sInited;
    private static boolean sActivityVisible;

    public static void init(Activity activity) {
        sActivity = activity;
        sContext = activity;
        if (sInited) return;
        sPackageName = activity.getApplicationInfo().packageName;
        sFileDirectory = activity.getFilesDir().getAbsolutePath();
        nativeSetApkPath(activity.getApplicationInfo().sourceDir);
        sAssetManager = activity.getAssets();
        nativeSetContext(activity, sAssetManager);
        try { Cocos2dxBitmap.setContext(activity); } catch (Throwable ignored) { }
        sInited = true;
    }

    public static void onPause() { sActivityVisible = false; onEnterBackground(); }
    public static void onResume() { sActivityVisible = true; onEnterForeground(); }
    public static void onEnterBackground() { }
    public static void onEnterForeground() { }

    public static void setKeepScreenOn(final boolean keepScreenOn) {
        final Activity activity = sActivity;
        if (activity == null) return;
        activity.runOnUiThread(() -> activity.getWindow().getDecorView().setKeepScreenOn(keepScreenOn));
    }

    public static Activity getActivity() { return sActivity; }
    public static Context getContext() { return sContext != null ? sContext : sActivity; }
    public static Context getApplicationContext() { return getContext(); }

    public static String getCocos2dxPackageName() {
        if (sPackageName != null) return sPackageName;
        Context context = getContext();
        return context != null ? context.getPackageName() : "com.yuki.yukihub";
    }

    public static String getCocos2dxWritablePath() {
        if (sFileDirectory != null) return sFileDirectory;
        Context context = getContext();
        if (context != null) {
            String path = context.getFilesDir().getAbsolutePath();
            return path.endsWith("/") ? path : path + "/";
        }
        return "/data/data/com.yuki.yukihub/files/";
    }

    public static String getCurrentLanguage() {
        return Locale.getDefault().getLanguage();
    }

    public static int setLowPowerMode(boolean enabled) {
        return 0;
    }

    public static int getDeviceRotation() {
        return 0;
    }

    public static int getDPI() {
        try {
            Activity a = sActivity;
            if (a != null) {
                DisplayMetrics dm = a.getResources().getDisplayMetrics();
                return (int) dm.densityDpi;
            }
        } catch (Throwable ignored) { }
        return 160;
    }

    public static byte[] conversionEncoding(byte[] text, String fromCharset, String newCharset) {
        try {
            String s = new String(text, fromCharset);
            return s.getBytes(newCharset);
        } catch (Throwable t) {
            return text;
        }
    }

    public static native void nativeSetContext(Context context, Object thiz);
    public static native void nativeSetApkPath(String path);
    public static native void nativeSetEditTextDialogResult(byte[] bytes);
}