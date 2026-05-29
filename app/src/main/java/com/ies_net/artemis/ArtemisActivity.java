package com.ies_net.artemis;

import android.app.NativeActivity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

public class ArtemisActivity extends NativeActivity {
 private static final int REQ_VIDEO = 1;
 private static final String TAG = "ArtemisVideo";

 public ArtemisActivity() {
 super();
 }

 public void DownloadExpansionFiles(String value) {
 }

 public void DownloadResource(String a, String b, String c) {
 }

 public native void EmulateKeyEvent(int keyCode, int action);

 public native void ExecuteTag(String tag);

 public void InAppBilling(String a, String b, boolean c, boolean d) {
 OnFinishPurchase(1, "", "", "", "",1, "");
 }

 public native void OnFinishPurchase(int result, String a, String b, String c, String d, int e, String f);

 public native void OnFinishVideo();

 public native void OnReadyPlayAssetDelivery(int a, int b, int c);

 public void PlayVideo(String path, int offset, int length, int volume, int skip) {
 try {
 Log.i(TAG, "PlayVideo path=" + path + " offset=" + offset + " length=" + length + " volume=" + volume + " skip=" + skip);
 Intent intent = new Intent(getApplicationContext(), VideoViewActivity.class);
 intent.putExtra("PATH", path);
 intent.putExtra("GAME_DIR", getExternalFilesDir(null).getAbsolutePath());
 intent.putExtra("OFFSET", offset);
 intent.putExtra("LENGTH", length);
 intent.putExtra("VOLUME", volume);
 intent.putExtra("SKIP", skip);
 // Keep old debug extras for compatibility with the previous stub.
 intent.putExtra("A", offset);
 intent.putExtra("B", length);
 intent.putExtra("C", volume);
 intent.putExtra("D", skip);
 startActivityForResult(intent, REQ_VIDEO);
 overridePendingTransition(0, 0);
 } catch (Throwable t) {
 Log.e(TAG, "PlayVideo failed", t);
 notifyFinishVideoSafely();
 }
 }

 @Override
 public boolean dispatchKeyEvent(KeyEvent event) {
 int keyCode = event == null ? 0 : event.getKeyCode();
 if (isSystemVolumeKey(keyCode)) return super.dispatchKeyEvent(event);
 try {
 EmulateKeyEvent(event.getKeyCode(), event.getAction());
 return true;
 } catch (Throwable ignored) {
 return super.dispatchKeyEvent(event);
 }
 }

 private boolean isSystemVolumeKey(int keyCode) {
 return keyCode == KeyEvent.KEYCODE_VOLUME_UP
 || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
 || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
 || keyCode == KeyEvent.KEYCODE_MUTE;
 }

 @Override
 public void onActivityResult(int requestCode, int resultCode, Intent data) {
 super.onActivityResult(requestCode, resultCode, data);
 Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + resultCode);
 if (requestCode == REQ_VIDEO) notifyFinishVideoSafely();
 }

 @Override
 public void onCreate(Bundle savedInstanceState) {
 super.onCreate(savedInstanceState);
 setVolumeControlStream(AudioManager.STREAM_MUSIC);
 enterFullscreen();
 }

 @Override
 public void onNewIntent(Intent intent) {
 super.onNewIntent(intent);
 Intent oldIntent = getIntent();
 if (oldIntent == null || intent == null) return;
 String oldPath = oldIntent.getStringExtra("path");
 String newPath = intent.getStringExtra("path");
 if (newPath != null && !newPath.equals(oldPath)) {
 setIntent(intent);
 }
 }

 @Override
 public void onWindowFocusChanged(boolean hasFocus) {
 super.onWindowFocusChanged(hasFocus);
 if (hasFocus) enterFullscreen();
 }

 private void enterFullscreen() {
 try { getWindow().getDecorView().setSystemUiVisibility(5894); } catch (Throwable ignored) { }
 }

 private void notifyFinishVideoSafely() {
 try { OnFinishVideo(); } catch (Throwable ignored) { }
 }
}