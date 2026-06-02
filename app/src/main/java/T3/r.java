package T3;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import bridge.NativeBridge;
import org.tvp.kirikiri2.KR2Activity;

public abstract class r extends KR2Activity {
    private static final String TAG = "Kirikiroid2";
    public static Context app;
    private TextView mask;

    @Override
    public void onCreate(Bundle bundle) {
        doSetSystemUiVisibility();
        super.onCreate(bundle);
        app = this;
        if (getIntent().getBooleanExtra("originMode", false)) {
            return;
        }
        TextView textView = new TextView(this);
        textView.setBackgroundColor(0xff000000);
        textView.setText("Loading...");
        textView.setTextColor(0xffffffff);
        textView.setTextSize(32.0f);
        textView.setGravity(17);
        textView.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        this.mask = textView;
        this.mFrameLayout.addView(textView);
        String path = getIntent().getStringExtra("path");
        boolean maps = getIntent().getBooleanExtra("maps", false);
        if (path != null && path.length() != 0) {
            tryLaunchGame(path, maps);
        } else {
            finish();
        }
    }
    @Override
    public void onLoadNativeLibraries() {
        boolean initialized = NativeBridge.initialize(soName());
        Log.i(TAG, "native initialize result=" + initialized + " so=" + soName());
        Intent intent = getIntent();
        boolean scopedSaveDir = intent != null && intent.getBooleanExtra("scopedSaveDir", false);
        boolean safFileFallback = intent != null && intent.getBooleanExtra("safFileFallback", false);
        if (intent == null || (!scopedSaveDir && !safFileFallback)) {
            Log.i(TAG, "native interceptor skipped: scoped save and SAF fallback disabled");
            return;
        }
        String prefix = null;
        try {
            String rawPath = intent.getStringExtra("path");
            if (rawPath != null && !rawPath.trim().isEmpty()) {
                String resolved = normalizeKrPath(rawPath);
                File root = new File(resolved);
                if (root.isFile()) root = root.getParentFile();
                if (root != null) {
                    File saveRoot = new File(new File(getExternalFilesDir(null), "save"), safeSaveName(root.getAbsolutePath()));
                    if (saveRoot.exists() || saveRoot.mkdirs()) {
                        Log.i(TAG, scopedSaveDir ? "KRKR scoped save uses private mirror root; native save hook enabled" : "KRKR SAF file fallback hook enabled");
                        prefix = storagePrefix(root.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve scoped hook prefix failed", t);
        }
        if (prefix != null) {
            try {
                NativeBridge.interceptor(prefix);
                NativeBridge.relocate();
                Log.i(TAG, "native interceptor enabled prefix=" + prefix);
            } catch (Throwable t) {
                Log.e(TAG, "enable native interceptor failed", t);
            }
        } else {
            Log.w(TAG, "native interceptor skipped: empty prefix");
        }
    }


    private void tryLaunchGame(String path, boolean maps) {
        new Thread(() -> {
            final boolean[] launched = new boolean[]{false};
            int retry = 15;
            while (!launched[0] && retry-- > 0) {
                runOnGLThread(() -> {
                    try {
                        boolean ok = NativeBridge.launch(soName(), path, maps);
                        launched[0] = ok;
                        Log.i(TAG, "launch result=" + ok + " path=" + path);
                        if (ok && mask != null) {
                            mask.post(() -> mask.animate().alpha(0.0f).setDuration(500L).setStartDelay(1500L).start());
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "launch failed", t);
                    }
                });
                if (!launched[0]) {
                    try { Thread.sleep(1000L); } catch (InterruptedException ignored) { break; }
                }
            }
            if (!launched[0]) {
                runOnUiThread(() -> {
                    if (mask != null) mask.setText("启动失败");
                });
            }
        }).start();
    }

    private static String normalizeKrPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("storage/")) p = "/" + p;
        return p;
    }

    private static String storagePrefix(String path) {
        String p = normalizeKrPath(path);
        String lower = p.toLowerCase();
        if (lower.startsWith("/storage/emulated/0/")) return "/storage/emulated/0";
        if (lower.startsWith("/sdcard/")) return "/sdcard";
        if (lower.startsWith("/storage/")) {
            String rest = p.substring("/storage/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) return "/storage/" + rest.substring(0, slash);
        }
        return p;
    }

    private static String safeSaveName(String rootPath) {
        try {
            String path = normalizeKrPath(rootPath);
            File f = new File(path);
            String name = f.getName();
            if (name == null || name.trim().isEmpty()) {
                File parent = f.getParentFile();
                name = parent == null ? "default" : parent.getName();
            }
            name = name == null ? "default" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            return name.isEmpty() ? "default" : name;
        } catch (Throwable ignored) {
            return "default";
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Intent oldIntent = getIntent();
        if (oldIntent == null || intent == null) return;
        String oldPath = oldIntent.getStringExtra("path");
        String newPath = intent.getStringExtra("path");
        if (newPath != null && !newPath.equals(oldPath)) {
            Toast.makeText(this, "已有游戏在运行，请先存档并退出游戏后再启动新游戏", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setRequestedOrientation(getIntent().getIntExtra("orientation", 6));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        String focus = getIntent().getStringExtra("focus");
        boolean forceFocus = focus != null && Boolean.parseBoolean(focus);
        super.onWindowFocusChanged(hasFocus || forceFocus);
        if (hasFocus || forceFocus) doSetSystemUiVisibility();
    }

    public abstract String soName();
}
