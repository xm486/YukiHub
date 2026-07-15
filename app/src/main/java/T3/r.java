package T3;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import bridge.NativeBridge;
import org.tvp.kirikiri2.KR2Activity;

public abstract class r extends KR2Activity {
    private static final String TAG = "Kirikiroid2";
    private static final int MAX_LAUNCH_ATTEMPTS = 15;
    private static final long GL_LAUNCH_TIMEOUT_MS = 2500L;
    public static Context app;
    private View mask;
    private volatile boolean nativeBridgeInitialized;
    private volatile boolean destroyed;

    @Override
    public void onCreate(Bundle bundle) {
        doSetSystemUiVisibility();
        super.onCreate(bundle);
        app = this;
        if (getIntent().getBooleanExtra("originMode", false)) {
            return;
        }
        boolean continueLoading = getIntent().getBooleanExtra(GameLoadingView.EXTRA_CONTINUE_LOADING, false);
        int tipIndex = getIntent().getIntExtra(GameLoadingView.EXTRA_INITIAL_TIP_INDEX, -1);
        GameLoadingView loadingView = new GameLoadingView(this, continueLoading, tipIndex);
        loadingView.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        this.mask = loadingView;
        this.mFrameLayout.addView(loadingView);
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
        nativeBridgeInitialized = initialized;
        Log.i(TAG, "native initialize result=" + initialized + " so=" + soName());
        if (!initialized) {
            Log.e(TAG, "native bridge initialization failed; skip KRKR hook setup");
            return;
        }
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
        if (!nativeBridgeInitialized) {
            Log.e(TAG, "skip launch because native bridge was not initialized");
            showLaunchFailure("KRKR 引擎初始化失败");
            return;
        }
        // Native launch must run on the GL thread. Wait for each queued call before retrying;
        // otherwise the old loop can enqueue all retries before the first callback runs.
        new Thread(() -> {
            boolean launched = false;
            for (int attempt = 1; !launched && attempt <= MAX_LAUNCH_ATTEMPTS; attempt++) {
                if (destroyed || isFinishing()) break;
                final int currentAttempt = attempt;
                final boolean[] launchResult = new boolean[]{false};
                final CountDownLatch launchCompleted = new CountDownLatch(1);
                runOnGLThread(() -> {
                    try {
                        if (destroyed) return;
                        launchResult[0] = NativeBridge.launch(soName(), path, maps);
                        Log.i(TAG, "launch result=" + launchResult[0] + " path=" + path + " attempt=" + currentAttempt);
                    } catch (Throwable t) {
                        Log.e(TAG, "launch failed", t);
                    } finally {
                        launchCompleted.countDown();
                    }
                });
                try {
                    if (!launchCompleted.await(GL_LAUNCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        Log.w(TAG, "launch callback timed out attempt=" + currentAttempt);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
                launched = launchResult[0];
                if (launched && mask != null) {
                    final View fadeMask = mask;
                    fadeMask.post(() -> {
                        fadeMask.animate().alpha(0.0f).setDuration(600L).setStartDelay(1200L)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() -> {
                                    try { ((ViewGroup) fadeMask.getParent()).removeView(fadeMask); } catch (Throwable ignored) { }
                                })
                                .start();
                    });
                } else if (!destroyed && currentAttempt < MAX_LAUNCH_ATTEMPTS) {
                    try { Thread.sleep(1000L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!launched && !destroyed) showLaunchFailure("启动失败");
        }, "KrkrLaunch").start();
    }

    private void showLaunchFailure(String message) {
        runOnUiThread(() -> {
            if (destroyed || isFinishing() || mask == null) return;
            if (mask instanceof GameLoadingView) {
                ((GameLoadingView) mask).showError(message);
            } else if (mask instanceof TextView) {
                ((TextView) mask).setText(message);
            }
        });
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

    @Override
    public void onDestroy() {
        try {
            destroyed = true;
            mask = null;
            if (app == this) app = null;
        } catch (Throwable ignored) { }
        // This Activity has a dedicated process. Do not enter Cocos teardown first:
        // its RenderThread can still lock native state after that state is destroyed.
        Log.i(TAG, "terminate dedicated KR process before Cocos teardown");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public abstract String soName();
}
