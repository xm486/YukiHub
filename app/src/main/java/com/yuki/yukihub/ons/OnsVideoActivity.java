package com.yuki.yukihub.ons;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;
import android.media.MediaPlayer;

public class OnsVideoActivity extends Activity {
    public static final String EXTRA_VIDEO_URI = "video_uri";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersive();
        VideoView view = new VideoView(this);
        view.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(view);
        String text = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        Uri uri = text == null ? getIntent().getParcelableExtra(EXTRA_VIDEO_URI) : Uri.parse(text);
        if (uri == null) { finish(); return; }
        view.setVideoURI(uri);
        view.setOnCompletionListener(mp -> finish());
        view.setOnErrorListener((MediaPlayer mp, int what, int extra) -> { finish(); return true; });
        view.start();
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersive();
    }

    private void enterImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getDecorView().getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}