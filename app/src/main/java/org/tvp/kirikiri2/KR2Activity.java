package org.tvp.kirikiri2;

import android.app.ActivityManager;
import android.content.Intent;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import bridge.NativeBridge;
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
        try {
            File f = new File(redirectScopedSavePath(path));
            boolean ok = f.exists() || f.mkdirs();
            if (!ok && isSafFallbackEnabled()) ok = NativeBridge.createDirectoryViaSafIfPossible(path);
            android.util.Log.i("KR2Activity", "CreateFolders " + path + " -> " + f.getAbsolutePath() + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            return isSafFallbackEnabled() && NativeBridge.createDirectoryViaSafIfPossible(path);
        }
    }

    public static boolean DeleteFile(String path) {
        try {
            File mapped = new File(redirectScopedSavePath(path));
            File original = new File(normalizeKrFilePath(path));
            boolean existed = mapped.exists() || original.exists();
            boolean ok = true;
            if (mapped.exists()) ok = mapped.delete();
            if (original.exists()) ok = original.delete() && ok;
            if (!existed) ok = true;
            if ((!ok || !existed) && isSafFallbackEnabled()) ok = NativeBridge.deleteViaSafIfPossible(path) || ok;
            android.util.Log.i("KR2Activity", "DeleteFile " + path + " mapped=" + mapped.getAbsolutePath() + " original=" + original.getAbsolutePath() + " existed=" + existed + " ok=" + ok);
            return ok;
        } catch (Throwable t) { return false; }
    }

    public static boolean RenameFile(String from, String to) {
        try {
            File mappedSrc = new File(redirectScopedSavePath(from));
            File originalSrc = new File(normalizeKrFilePath(from));
            File dst = new File(redirectScopedSavePath(to));
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File src = mappedSrc.exists() ? mappedSrc : originalSrc;
            boolean ok;
            boolean srcExisted = src.exists();
            if (!srcExisted) {
                if (isSafFallbackEnabled() && NativeBridge.existsViaSafIfPossible(from)) {
                    ok = NativeBridge.renameViaSafIfPossible(from, to);
                } else {
                    ok = true;
                }
            } else {
                ok = src.renameTo(dst);
                if (!ok) ok = copyThenDelete(src, dst);
                if (!ok && isSafFallbackEnabled()) ok = NativeBridge.renameViaSafIfPossible(from, to);
            }
            android.util.Log.i("KR2Activity", "RenameFile " + from + " -> " + to + " mappedSrc=" + mappedSrc.getAbsolutePath() + " originalSrc=" + originalSrc.getAbsolutePath() + " dst=" + dst.getAbsolutePath() + " srcExisted=" + srcExisted + " ok=" + ok);
            return ok;
        } catch (Throwable t) { return false; }
    }

    public static boolean WriteFile(String path, byte[] data) {
        try {
            String mapped = redirectScopedSavePath(path);
            File f = new File(mapped);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                if (isSafFallbackEnabled()) return NativeBridge.writeViaSafIfPossible(path, data);
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                if (data != null) fos.write(data);
            }
            android.util.Log.i("KR2Activity", "WriteFile " + path + " -> " + f.getAbsolutePath() + " bytes=" + (data == null ? 0 : data.length));
            return true;
        } catch (Throwable t) {
            return isSafFallbackEnabled() && NativeBridge.writeViaSafIfPossible(path, data);
        }
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

    private static boolean copyThenDelete(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyFile(src, dst);
            return src.delete();
        } catch (Throwable t) {
            android.util.Log.w("KR2Activity", "copyThenDelete failed " + src + " -> " + dst, t);
            return false;
        }
    }

    private static String normalizeKrFilePath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("storage/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        return p;
    }

    private static boolean isSafFallbackEnabled() {
        try {
            Intent intent = sInstance != null ? sInstance.getIntent() : null;
            return intent != null && intent.getBooleanExtra("safFileFallback", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String redirectScopedSavePath(String path) {
        try {
            if (path == null || path.trim().isEmpty()) return path;
            Intent intent = sInstance != null ? sInstance.getIntent() : null;
            if (intent == null || !intent.getBooleanExtra("scopedSaveDir", false)) return path;
            String p = normalizeKrFilePath(path);
            String lower = p.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("/savedata/");
            int folderLen = "/savedata/".length();
            if (idx < 0) {
                if (lower.endsWith("/savedata")) {
                    idx = lower.length() - "/savedata".length();
                    folderLen = "/savedata".length();
                } else {
                    return path;
                }
            }
            String rel = p.length() > idx + folderLen ? p.substring(idx + folderLen) : "";
            File base = new File(sInstance.getExternalFilesDir(null), "save");
            String name = intent.getStringExtra("scopedSaveName");
            File dir = new File(base, (name == null || name.trim().isEmpty()) ? "default" : name);
            File out = rel.isEmpty() ? dir : new File(dir, rel);
            File parent = out.isDirectory() ? out : out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            android.util.Log.i("KR2Activity", "redirectScopedSavePath " + p + " -> " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.w("KR2Activity", "redirectScopedSavePath failed path=" + path, t);
            return path;
        }
    }
    // 独立存档必须在文件写入入口完成重定向，禁止采用“先写原目录再周期复制/删除”的同步方案。


    private static void copyFile(File src, File dst) throws java.io.IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

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
    @Override public void onCreate(Bundle savedInstanceState) {
        sInstance = this;
        msgHandler = new Handler(Looper.getMainLooper()) { @Override public void handleMessage(Message msg) { KR2Activity.this.handleMessage(msg); } };
        Sp = PreferenceManager.getDefaultSharedPreferences(this);
        super.onCreate(savedInstanceState);
        initDump(getFilesDir().getAbsolutePath() + "/dump");
        android.util.Log.i("KR2Activity", "scoped save sync disabled; writes must be redirected at source");
    }


    public void handleMessage(Message message) { }

    public void doSetSystemUiVisibility() { getWindow().getDecorView().setSystemUiVisibility(5894); }
    public void hideSystemUI() {
        if (nativeGetHideSystemButton()) doSetSystemUiVisibility();
    }

    @Override public Cocos2dxGLSurfaceView onCreateView() {
        h gl = new h(this);
        hideSystemUI();
        if (mGLContextAttrs != null && mGLContextAttrs.length > 3 && mGLContextAttrs[3] > 0) gl.getHolder().setFormat(-3);
        if (mGLContextAttrs != null) gl.setEGLConfigChooser(this.new Cocos2dxEGLConfigChooser(this, mGLContextAttrs));
        return gl;
    }

    @Override public void onResume() { super.onResume(); doSetSystemUiVisibility(); }
    @Override public void onDestroy() {
        try {
            android.util.Log.i("KR2Activity", "destroy KR2Activity");
            mTextEdit = null;
            if (sInstance == this) sInstance = null;
        } catch (Throwable ignored) { }
        super.onDestroy();
    }
    @Override public void onLowMemory() { nativeOnLowMemory(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) doSetSystemUiVisibility(); }
    public String[] getStoragePath() {
        try {
            if (getIntent() != null && getIntent().getBooleanExtra("scopedSaveDir", false)) {
                File base = new File(getExternalFilesDir(null), "save");
                String name = getIntent().getStringExtra("scopedSaveName");
                File dir = new File(base, (name == null || name.trim().isEmpty()) ? "default" : name);
                if (!dir.exists()) dir.mkdirs();
                return new String[]{dir.getAbsolutePath()};
            }
        } catch (Throwable ignored) { }
        return new String[]{Environment.getExternalStorageDirectory().getAbsolutePath()};
    }
}