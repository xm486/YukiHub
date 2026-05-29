package com.yuri.onscripter;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import com.yuki.yukihub.ons.OnsLibLoader;
import com.yuki.yukihub.ons.OnsSettings;
import com.yuki.yukihub.ons.OnsVideoActivity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ONScripter extends SDLActivity {
    private static final String TAG = "YukiONS";
    public static final String YURI_VERSION = "Yuri_0.7.6beta1";
    private static final String PREF_OVERLAY = "ons_overlay";
    private static final String KEY_OVERLAY_VISIBLE = "visible";

    private ArrayList<String> onsArgs;
    private boolean ignoreCutout = true;
    private String gameRoot;
    private FrameLayout onsOverlay;
    private LinearLayout leftControls;
    private LinearLayout rightControls;
    private TextView toggleButton;
    private final ArrayList<TextView> autoButtons = new ArrayList<>();
    private boolean controlsVisible = true;
    private boolean autoMode = false;
    private native int nativeInitJavaCallbacks();
    private native int nativeGetWidth();
    private native int nativeGetHeight();

    @Override public void loadLibraries() {
        OnsLibLoader.load(this);
    }

    @Override public String[] getLibraries() {
        return new String[]{"SDL2", "lua", "jpeg", "bz2", "modplug", "SDL2_image", "SDL2_mixer", "SDL2_ttf", OnsSettings.PREF_NAME};
    }

    @Override public String getMainSharedObject() {
        return new File(getFilesDir(), "libs/" + YURI_VERSION + "/libonsyuri.so").getAbsolutePath();
    }

    @Override public String[] getArguments() {
        if (onsArgs == null) onsArgs = new ArrayList<>();
        return onsArgs.toArray(new String[0]);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        gameRoot = firstNonEmpty(
                getIntent().getStringExtra("path"),
                getIntent().getStringExtra("gamePath"),
                getIntent().getStringExtra("rootUri"),
                getIntent().getStringExtra(OnsSettings.EXTRA_GAME_URI));
        gameRoot = normalizeRootPath(gameRoot);
        onsArgs = getIntent().getStringArrayListExtra(OnsSettings.EXTRA_GAME_ARGS);
        OnsSettings settings = OnsSettings.load(this);
        if (onsArgs == null) onsArgs = settings.buildArgs(this, gameRoot);
        ignoreCutout = getIntent().getBooleanExtra(OnsSettings.EXTRA_IGNORE_CUTOUT, settings.ignoreCutout);
        // 提前加载/释放 assets：确保 libonsyuri 与内置 DroidSansFallback.ttf 在 SDLActivity 启动前可用。
        OnsLibLoader.load(this);
        ensureDefaultFont();
        super.onCreate(savedInstanceState);
        try { nativeInitJavaCallbacks(); } catch (Throwable t) { Log.w(TAG, "nativeInitJavaCallbacks failed", t); }
        setupVirtualControls();
        fullscreen();
    }

    @Override public void onResume() {
        super.onResume();
        fullscreen();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        Log.d(TAG, "send ESC to ONS");
        try {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
        } catch (Throwable t) {
            Log.w(TAG, "send ESC failed", t);
        }
    }

    public int getFD(byte[] pathbyte, int mode) {
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            File file = resolveGameFile(decodePath(pathbyte));
            if (file == null) return -1;
            if (mode != 0) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, mode == 0 ? ParcelFileDescriptor.MODE_READ_ONLY : (ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE));
            int fd = pfd.detachFd();
            Log.i(TAG, "getFD fd=" + fd + " mode=" + mode + " file=" + file);
            return fd;
        } catch (Throwable t) {
            Log.w(TAG, "getFD failed", t);
            return -1;
        }
    }

    public int mkdir(byte[] pathbyte) {
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            File f = resolveGameFile(decodePath(pathbyte));
            return f != null && (f.exists() || f.mkdirs()) ? 0 : -1;
        } catch (Throwable t) {
            Log.w(TAG, "mkdir failed", t);
            return -1;
        }
    }

    public void playVideo(byte[] pathbyte) {
        if (pathbyte == null) return;
        String path = decodePath(pathbyte);
        Log.i(TAG, "playVideo " + path);
        try {
            File file = resolveVideoFile(path);
            if (file == null || !file.exists()) {
                Log.w(TAG, "video not found: " + path);
                return;
            }
            playVideo(Uri.fromFile(file));
        } catch (Throwable t) {
            Log.e(TAG, "playVideo failed", t);
        }
    }

    public void playVideo(Uri uri) {
        if (uri == null) return;
        Intent i = new Intent(this, OnsVideoActivity.class);
        i.putExtra(OnsVideoActivity.EXTRA_VIDEO_URI, uri.toString());
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    public void testVideo() {
        File file = resolveVideoFile("test.mp4");
        if (file != null && file.exists()) playVideo(Uri.fromFile(file));
    }

    private void setupVirtualControls() {
        try {
            controlsVisible = getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).getBoolean(KEY_OVERLAY_VISIBLE, true);
            onsOverlay = new FrameLayout(this);
            onsOverlay.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            onsOverlay.setClickable(false);
            onsOverlay.setFocusable(false);

            leftControls = buildControlColumn(true);
            rightControls = buildControlColumn(false);
            FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
            leftLp.leftMargin = dp(10);
            rightLp.rightMargin = dp(10);
            onsOverlay.addView(leftControls, leftLp);
            onsOverlay.addView(rightControls, rightLp);

            toggleButton = makeActionButton(controlsVisible ? "×" : "≡", 24, true);
            FrameLayout.LayoutParams toggleLp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.TOP);
            toggleLp.topMargin = dp(12);
            toggleLp.rightMargin = dp(12);
            onsOverlay.addView(toggleButton, toggleLp);

            addContentView(onsOverlay, new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
            applyVirtualControlsVisibility();
        } catch (Throwable t) {
            Log.w(TAG, "setupVirtualControls failed", t);
        }
    }

    private void toggleVirtualControls() {
        controlsVisible = !controlsVisible;
        getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY_VISIBLE, controlsVisible).apply();
        applyVirtualControlsVisibility();
    }

    private void applyVirtualControlsVisibility() {
        int vis = controlsVisible ? View.VISIBLE : View.GONE;
        if (leftControls != null) leftControls.setVisibility(vis);
        if (rightControls != null) rightControls.setVisibility(vis);
        if (toggleButton != null) toggleButton.setText(controlsVisible ? "✕" : "☰");
    }

    private LinearLayout buildControlColumn(boolean left) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int[] keys = left
                ? new int[]{KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_MENU}
                : new int[]{KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A};
        String[] labels = left
                ? new String[]{"↩\nESC", "»\nSKIP", "▶\nAUTO", "☰\nMENU"}
                : new String[]{"✓\nOK", "▸\nNEXT", "»\nSKIP", "▶\nAUTO"};
        for (int i = 0; i < keys.length; i++) {
            TextView button = makeActionButton(labels[i], keys[i], false);
            if (labels[i].contains("AUTO")) autoButtons.add(button);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
            lp.topMargin = dp(7);
            lp.bottomMargin = dp(7);
            column.addView(button, lp);
        }
        return column;
    }

    private void updateAutoButtons() {
        for (TextView b : autoButtons) {
            if (b == null) continue;
            styleVirtualButton(b, autoMode, false);
            b.setText(autoMode ? "■\nAUTO" : "▶\nAUTO");
        }
    }

    private void styleVirtualButton(TextView tv, boolean active, boolean toggle) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(toggle ? 16 : 15));
        if (active) {
            bg.setColor(Color.argb(210, 0, 122, 255));
            bg.setStroke(dp(2), Color.argb(230, 255, 255, 255));
        } else {
            bg.setColor(Color.argb(166, 16, 16, 16));
            bg.setStroke(dp(1), Color.argb(135, 255, 255, 255));
        }
        tv.setBackground(bg);
        tv.setTextColor(Color.WHITE);
    }

    private TextView makeActionButton(String label, int keyCode, boolean toggle) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(toggle ? 24 : 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLines(toggle ? 1 : 2);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(0f, 0.92f);
        tv.setTag(label.contains("AUTO") ? "auto" : "normal");
        styleVirtualButton(tv, label.contains("AUTO") && autoMode, toggle);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(toggle ? dp(48) : dp(60), toggle ? dp(48) : dp(60));
        tv.setLayoutParams(lp);
        tv.setPadding(dp(2), dp(5), dp(2), dp(5));
        tv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.65f);
                if (!toggle) {
                    SDLActivity.onNativeKeyDown(keyCode);
                }
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                if (toggle) {
                    toggleVirtualControls();
                } else {
                    SDLActivity.onNativeKeyUp(keyCode);
                    if ("auto".equals(v.getTag())) {
                        autoMode = !autoMode;
                        updateAutoButtons();
                    }
                }
                return true;
            }
            return true;
        });
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ensureDefaultFont() {
        if (gameRoot == null || gameRoot.isEmpty()) return;
        try {
            File fallbackFont = new File(gameRoot, "default.ttf");
            if (fallbackFont.exists()) return;
            File builtin = new File(getFilesDir(), "DroidSansFallback.ttf");
            if (!builtin.exists()) return;
            File parent = fallbackFont.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyFile(builtin, fallbackFont);
            Log.i(TAG, "default.ttf fallback installed: " + fallbackFont);
        } catch (Throwable t) {
            Log.w(TAG, "install default.ttf fallback failed", t);
        }
    }

    private File resolveVideoFile(String raw) {
        File file = resolveGameFile(raw);
        if (file == null) return null;
        if (file.exists()) return file;
        File mp4 = replaceExtension(file, ".mp4");
        if (mp4.exists()) return mp4;
        File ci = findFileIgnoreCase(file);
        if (ci != null && ci.exists()) return ci;
        File ciMp4 = findFileIgnoreCase(mp4);
        return ciMp4 != null ? ciMp4 : file;
    }

    private File resolveGameFile(String raw) {
        if (raw == null) return null;
        String path = raw.replace('\\', '/').trim();
        if (path.startsWith("file://")) path = path.substring("file://".length());
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(gameRoot, path);
        File ci = findFileIgnoreCase(file);
        return ci != null ? ci : file;
    }

    private File findFileIgnoreCase(File file) {
        if (file == null || file.exists()) return file;
        File parent = file.getParentFile();
        if (parent == null) return null;
        File fixedParent = parent.exists() ? parent : findFileIgnoreCase(parent);
        if (fixedParent == null || !fixedParent.isDirectory()) return null;
        File[] list = fixedParent.listFiles();
        if (list == null) return null;
        String wanted = file.getName();
        for (File f : list) {
            if (f.getName().equalsIgnoreCase(wanted)) return f;
        }
        return null;
    }

    private File replaceExtension(File file, String ext) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        File parent = file.getParentFile();
        return new File(parent == null ? new File(".") : parent, base + ext);
    }

    @Override public void onDestroy() {
        super.onDestroy();
    }

    private void fullscreen() {
        Window window = getWindow();
        if (ignoreCutout && Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = window.getDecorView().getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        window.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private String normalizeRootPath(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("file://")) v = v.substring("file://".length());
        if (v.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(v);
                String docId;
                try { docId = android.provider.DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { docId = null; }
                if (docId == null || docId.isEmpty()) docId = android.provider.DocumentsContract.getDocumentId(uri);
                String path = docIdToPath(docId);
                if (path != null) return path;
            } catch (Throwable ignored) { }
        }
        return v;
    }

    private String docIdToPath(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        String volume = colon >= 0 ? docId.substring(0, colon) : docId;
        String rel = colon >= 0 ? docId.substring(colon + 1) : "";
        if ("primary".equalsIgnoreCase(volume)) return "/storage/emulated/0" + (rel.isEmpty() ? "" : "/" + rel);
        if (volume != null && !volume.isEmpty()) return "/storage/" + volume + (rel.isEmpty() ? "" : "/" + rel);
        return null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private String decodePath(byte[] pathbyte) {
        return new String(pathbyte, StandardCharsets.UTF_8).replace('\\', '/');
    }

    private void copyFile(File from, File to) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
