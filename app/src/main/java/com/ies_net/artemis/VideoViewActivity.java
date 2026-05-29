package com.ies_net.artemis;

import A4.c;
import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;

import tv.danmaku.ijk.media.player.IMediaPlayer;

public class VideoViewActivity extends Activity implements IMediaPlayer.OnCompletionListener, IMediaPlayer.OnErrorListener {
 private static final String TAG = "ArtemisVideo";
 private AssetFileDescriptor assetFileDescriptor = null;
 private RandomAccessFile randomAccessFile = null;
 private boolean pausedBySystem = false;
 private int skip = 0;
 private c videoView = null;

 @Override
 public boolean dispatchKeyEvent(KeyEvent event) {
 if (event != null && isSystemVolumeKey(event.getKeyCode())) return super.dispatchKeyEvent(event);
 if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_BACK && skip == 0) return true;
 return super.dispatchKeyEvent(event);
 }

 @Override
 public void finish() {
 super.finish();
 overridePendingTransition(0, 0);
 }

 @Override
 public void onCompletion(IMediaPlayer player) {
 Log.i(TAG, "IJK onCompletion");
 closeSources();
 setResult(RESULT_OK, new Intent());
 finish();
 }

 @Override
 public boolean onError(IMediaPlayer player, int what, int extra) {
 Log.e(TAG, "IJK onError what=" + what + " extra=" + extra);
 closeSources();
 setResult(RESULT_CANCELED, new Intent());
 finish();
 return true;
 }

 @Override
 protected void onCreate(Bundle bundle) {
 super.onCreate(bundle);
 setVolumeControlStream(AudioManager.STREAM_MUSIC);
 enterFullscreen();
 pausedBySystem = false;
 Intent intent = getIntent();
 String rawPath = intent == null ? null : intent.getStringExtra("PATH");
 String gameDir = intent == null ? null : intent.getStringExtra("GAME_DIR");
 int offset = intent == null ? 0 : intent.getIntExtra("OFFSET", intent.getIntExtra("A", 0));
 int length = intent == null ? 0 : intent.getIntExtra("LENGTH", intent.getIntExtra("B", 0));
 int volume = intent == null ? 0 : intent.getIntExtra("VOLUME", intent.getIntExtra("C", 0));
 skip = intent == null ? 0 : intent.getIntExtra("SKIP", intent.getIntExtra("D", 0));
 String path = resolvePath(rawPath, gameDir);
 Log.i(TAG, "IJK VideoViewActivity onCreate rawPath=" + rawPath + " path=" + path + " offset=" + offset + " length=" + length + " volume=" + volume + " skip=" + skip);

 if (path == null || path.trim().isEmpty()) {
 setResult(RESULT_CANCELED, new Intent());
 finish();
 return;
 }

 LinearLayout root = new LinearLayout(this);
 root.setBackgroundColor(Color.rgb(0, 0, 0));
 root.setGravity(17);
 setContentView(root, new WindowManager.LayoutParams(-1, -1));
 videoView = new c(this);
 videoView.setZOrderOnTop(true);
 videoView.requestFocus();
 videoView.setOnCompletionListener(this);
 videoView.setOnErrorListener(this);
 root.addView(videoView);

 try {
 openVideo(path, offset, length, volume);
 videoView.start();
 } catch (Throwable t) {
 Log.e(TAG, "IJK openVideo failed", t);
 setResult(RESULT_CANCELED, new Intent());
 finish();
 }
 }

 private void openVideo(String path, int offset, int length, int volume) throws Exception {
 closeSources();
 try {
 File file = new File(path);
 randomAccessFile = new RandomAccessFile(file, "r");
 FileDescriptor fd = randomAccessFile.getFD();
 Log.i(TAG, "IJK open file exists=" + file.exists() + " size=" + file.length() + " offset=" + offset + " length=" + length);
 videoView.d(fd, volume);
 } catch (Throwable fileError) {
 Log.w(TAG, "IJK open as file failed, try assets: " + path, fileError);
 closeSources();
 assetFileDescriptor = getAssets().openFd(path);
 FileDescriptor fd = assetFileDescriptor.getFileDescriptor();
 videoView.d(fd, volume);
 }
 }

 private String resolvePath(String raw, String gameDir) {
 if (raw == null) return null;
 String p = raw.trim();
 if (p.startsWith("file://")) p = p.substring("file://".length());
 if (p.startsWith("content://")) return p;
 File f = new File(p);
 if (!f.isAbsolute() && gameDir != null && !gameDir.trim().isEmpty()) f = new File(gameDir, p);
 return f.getPath();
 }

 @Override
 protected void onPause() {
 super.onPause();
 pausedBySystem = true;
 try { if (videoView != null) videoView.pause(); } catch (Throwable ignored) { }
 }

 @Override
 protected void onResume() {
 super.onResume();
 enterFullscreen();
 if (pausedBySystem) {
 pausedBySystem = false;
 setResult(RESULT_CANCELED, new Intent());
 finish();
 }
 }

 @Override
 protected void onDestroy() {
 closeSources();
 super.onDestroy();
 }

 @Override
 public boolean onTouchEvent(MotionEvent event) {
 if (skip == 0) return super.onTouchEvent(event);
 int action = event.getAction();
 if ((action == MotionEvent.ACTION_DOWN && skip <= 1) || (action == MotionEvent.ACTION_POINTER_DOWN && skip <= 2)) {
 onCompletion(null);
 }
 return super.onTouchEvent(event);
 }

 @Override
 public void onWindowFocusChanged(boolean hasFocus) {
 super.onWindowFocusChanged(hasFocus);
 if (hasFocus) enterFullscreen();
 }

 private void closeSources() {
 try { if (randomAccessFile != null) randomAccessFile.close(); } catch (Throwable ignored) { }
 randomAccessFile = null;
 try { if (assetFileDescriptor != null) assetFileDescriptor.close(); } catch (Throwable ignored) { }
 assetFileDescriptor = null;
 }

 private boolean isSystemVolumeKey(int keyCode) {
 return keyCode == KeyEvent.KEYCODE_VOLUME_UP
 || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
 || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
 || keyCode == KeyEvent.KEYCODE_MUTE;
 }

 private void enterFullscreen() {
 try { getWindow().getDecorView().setSystemUiVisibility(5894); } catch (Throwable ignored) { }
 }
}