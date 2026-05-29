package org.tvp.kirikiri2;

import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import org.cocos2dx.lib.Cocos2dxActivity;
import org.cocos2dx.lib.Cocos2dxGLSurfaceView;

public class KR2Activity extends Cocos2dxActivity {
    public static KR2Activity sInstance;
    static Handler msgHandler;
    static f mDialogMessage = new f();
    protected static View mTextEdit;
    static ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
    static ActivityManager mAcitivityManager = null;
    static Debug.MemoryInfo mDbgMemoryInfo = new Debug.MemoryInfo();
    SharedPreferences Sp;

    public static KR2Activity GetInstance() { return sInstance; }
    public static KR2Activity getInstance() { return sInstance; }

    public static String GetVersion() {
        try { return sInstance.getPackageManager().getPackageInfo(sInstance.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }

    public static boolean CreateFolders(String path) {
        try { return new File(path).mkdirs(); } catch (Throwable t) { return false; }
    }
    public static boolean DeleteFile(String path) { try { return new File(path).delete(); } catch (Throwable t) { return false; } }
    public static boolean RenameFile(String from, String to) { try { return new File(from).renameTo(new File(to)); } catch (Throwable t) { return false; } }
    public static boolean WriteFile(String path, byte[] data) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
            return true;
        } catch (Throwable t) { return false; }
    }

    public static void MessageController(int what, int arg1, int arg2) {
        if (msgHandler == null) return;
        Message m = msgHandler.obtainMessage();
        m.what = what;
        m.arg1 = arg1;
        m.arg2 = arg2;
        msgHandler.sendMessage(m);
    }

    public static String getLocaleName() {
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        String country = locale.getCountry();
        return country.isEmpty() ? language : language + "_" + country.toLowerCase();
    }

    public static void ShowMessageBox(String title, String msg, String[] buttons) {
        f fVar = mDialogMessage;
        fVar.f19629a = title;
        fVar.f19630b = msg;
        fVar.f19631c = buttons;
        if (msgHandler != null) msgHandler.post(new c());
    }
    public static void ShowInputBox(String title, String msg, String text, String[] buttons) {
        f fVar = mDialogMessage;
        fVar.f19629a = title;
        fVar.f19630b = msg;
        fVar.f19631c = buttons;
        if (msgHandler != null) msgHandler.post(new d(text));
    }
    private static void showDialogInternal(String title, String msg, String inputText, String[] buttons) {
        if (sInstance == null) return;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(sInstance).setTitle(title).setMessage(msg).setCancelable(false);
        final android.widget.EditText edit;
        if (inputText != null) {
            edit = new android.widget.EditText(sInstance);
            edit.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, -1));
            edit.setText(inputText);
            b.setView(edit);
        } else edit = null;
        String[] bs = buttons != null ? buttons : new String[]{"OK"};
        if (bs.length >= 1) b.setPositiveButton(bs[0], (d, w) -> finishDialog(edit, 0));
        if (bs.length >= 2) b.setNeutralButton(bs[1], (d, w) -> finishDialog(edit, 1));
        if (bs.length >= 3) b.setNegativeButton(bs[2], (d, w) -> finishDialog(edit, 2));
        android.app.AlertDialog dialog = b.create();
        dialog.show();
        if (edit != null) {
            edit.requestFocus();
            ((InputMethodManager) Cocos2dxActivity.getContext().getSystemService("input_method")).showSoftInput(edit, 0);
        }
    }
    private static void finishDialog(android.widget.EditText edit, int which) {
        if (edit != null) onMessageBoxText(edit.getText().toString());
        onMessageBoxOK(which);
    }

    public static void showTextInput(int x, int y, int w, int h) {
        if (msgHandler == null) return;
        g r = new g();
        r.f19633a = x;
        r.f19634b = y;
        r.f19635c = w;
        r.f19636d = h;
        msgHandler.post(r);
    }
    public static void hideTextInput() { if (msgHandler != null) msgHandler.post(KR2Activity::lambdaHideTextInput); }
    private static void lambdaHideTextInput() {
        View view = mTextEdit;
        if (view != null) {
            view.setVisibility(View.GONE);
            ((InputMethodManager) sInstance.getSystemService("input_method")).hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    public static void updateMemoryInfo() {
        if (mAcitivityManager == null) mAcitivityManager = (ActivityManager) sInstance.getSystemService("activity");
        mAcitivityManager.getMemoryInfo(memoryInfo);
        Debug.getMemoryInfo(mDbgMemoryInfo);
    }
    public static long getAvailMemory() { return memoryInfo.availMem; }
    public static long getUsedMemory() { return mDbgMemoryInfo.getTotalPss(); }
    public static void exit() {
    try {
        final KR2Activity activity = sInstance;
        if (activity != null) {
            activity.runOnUiThread(() -> {
                try { activity.finish(); } catch (Throwable ignored) { }
            });
        }
    } catch (Throwable ignored) { }
}
    public static boolean isWritableNormal(String path) { return true; }
    public static boolean isWritableNormalOrSaf(String path) { return true; }
    public static void requireLEXA(String path) { }

    private static native void initDump(String path);
    private static native void nativeOnLowMemory();
    private static native boolean nativeGetHideSystemButton();
    public static native void nativeCharInput(int ch);
    public static native void nativeCommitText(String text, int newCursorPosition);
    public static native void nativeDeleteBackward();
    public static native void nativeHoverMoved(float x, float y);
    public static native void nativeInsertText(String text);
    public static native boolean nativeKeyAction(int keyCode, boolean down);
    public static native void nativeMouseScrolled(float v);
    public static native void nativeTouchesBegin(int id, float x, float y);
    public static native void nativeTouchesCancel(int[] ids, float[] xs, float[] ys);
    public static native void nativeTouchesEnd(int id, float x, float y);
    public static native void nativeTouchesMove(int[] ids, float[] xs, float[] ys);
    public static native void onBannerSizeChanged(int w, int h);
    public static native void onMessageBoxOK(int which);
    public static native void onMessageBoxText(String text);
    public static native void onNativeInit();

    @Override public void onLoadNativeLibraries() {
        System.loadLibrary("SDL2");
        System.loadLibrary("ffmpeg");
        System.loadLibrary("game");
        System.loadLibrary("kirikiroid3");
    }
    @Override protected void onCreate(Bundle savedInstanceState) {
        doSetSystemUiVisibility();
        sInstance = this;
        msgHandler = new Handler(Looper.getMainLooper()) { @Override public void handleMessage(Message msg) { KR2Activity.this.handleMessage(msg); } };
        Sp = PreferenceManager.getDefaultSharedPreferences(this);
        ensureDefaultHideSystemButton();
        super.onCreate(savedInstanceState);
        doSetSystemUiVisibility();
        initDump(getFilesDir().getAbsolutePath() + "/dump");
    }


    public void handleMessage(Message message) { }

    private void ensureDefaultHideSystemButton() {
        try {
            if (!Sp.contains("hide_android_sys_btn")) {
                Sp.edit().putBoolean("hide_android_sys_btn", true).apply();
            }
        } catch (Throwable ignored) { }
    }

    public void doSetSystemUiVisibility() { getWindow().getDecorView().setSystemUiVisibility(5894); }
    public void hideSystemUI() {
        boolean hide = true;
        try { hide = nativeGetHideSystemButton(); } catch (Throwable ignored) { }
        if (hide) doSetSystemUiVisibility();
    }

    @Override public Cocos2dxGLSurfaceView onCreateView() {
        h gl = new h(this);
        hideSystemUI();
        if (mGLContextAttrs != null && mGLContextAttrs.length > 3 && mGLContextAttrs[3] > 0) gl.getHolder().setFormat(-3);
        if (mGLContextAttrs != null) gl.setEGLConfigChooser(new Cocos2dxActivity.Cocos2dxEGLConfigChooser(mGLContextAttrs));
        return gl;
    }

    @Override public void onResume() { super.onResume(); doSetSystemUiVisibility(); }
    @Override public void onDestroy() {
    try {
        mTextEdit = null;
        if (sInstance == this) sInstance = null;
    } catch (Throwable ignored) { }
    super.onDestroy();
}
    @Override public void onLowMemory() { nativeOnLowMemory(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) doSetSystemUiVisibility(); }
    public String[] getStoragePath() { return new String[]{Environment.getExternalStorageDirectory().getAbsolutePath()}; }
}