package com.yuki.yukihub.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class BackgroundManager {

    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_CUSTOM_BACKGROUND = "custom_background";
    private static final String KEY_CUSTOM_BACKGROUND_TYPE = "custom_background_type";
    private static final String KEY_BACKGROUND_DIM_ENABLED = "background_dim_enabled";
    private static final String KEY_BACKGROUND_VIDEO_SOUND = "background_video_sound";

    private final Context context;
    private SharedPreferences prefs;
    private MediaPlayer backgroundMediaPlayer;
    private Uri pendingBackgroundVideoUri;

    public BackgroundManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void replaceCustomBackground(String bg, String type) {
        String old = prefs == null ? null : prefs.getString(KEY_CUSTOM_BACKGROUND, "");
        if (prefs != null) prefs.edit().putString(KEY_CUSTOM_BACKGROUND, bg).putString(KEY_CUSTOM_BACKGROUND_TYPE, type).apply();
        if (old != null && !old.equals(bg)) deleteInternalFileUri(old);
    }

    public String copyCoverToInternalStorage(Uri uri) {
        return copyImageToInternalStorage(uri, "covers", "cover_", 720, 88);
    }

    public void applyCustomBackground(ImageView bgImage, TextureView bgVideo, View bgDim, View dynamicBg) {
        if (prefs == null) return;
        if (bgImage == null || bgVideo == null || bgDim == null || dynamicBg == null) return;
        String bg = prefs.getString(KEY_CUSTOM_BACKGROUND, "");
        String type = prefs.getString(KEY_CUSTOM_BACKGROUND_TYPE, "image");
        boolean dimEnabled = prefs.getBoolean(KEY_BACKGROUND_DIM_ENABLED, true);
        if (bg == null || bg.isEmpty()) {
            stopBackgroundVideo();
            bgImage.setImageDrawable(null);
            bgImage.setVisibility(View.GONE);
            bgVideo.setVisibility(View.GONE);
            bgDim.setVisibility(View.GONE);
            dynamicBg.setVisibility(View.VISIBLE);
            return;
        }
        try {
            if ("video".equals(type)) {
                bgImage.setImageDrawable(null);
                bgImage.setVisibility(View.GONE);
                dynamicBg.setVisibility(View.GONE);
                bgVideo.setVisibility(View.VISIBLE);
                bgDim.setVisibility(dimEnabled ? View.VISIBLE : View.GONE);
                playBackgroundVideo(bgVideo, Uri.parse(bg), true);
            } else {
                stopBackgroundVideo();
                bgVideo.setVisibility(View.GONE);
                bgImage.setImageURI(Uri.parse(bg));
                bgImage.setVisibility(View.VISIBLE);
                bgDim.setVisibility(dimEnabled ? View.VISIBLE : View.GONE);
                dynamicBg.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            prefs.edit().remove(KEY_CUSTOM_BACKGROUND).remove(KEY_CUSTOM_BACKGROUND_TYPE).apply();
            stopBackgroundVideo();
            bgImage.setImageDrawable(null);
            bgImage.setVisibility(View.GONE);
            bgVideo.setVisibility(View.GONE);
            bgDim.setVisibility(View.GONE);
            dynamicBg.setVisibility(View.VISIBLE);
        }
    }

    public void playBackgroundVideo(TextureView textureView, Uri uri, boolean forceRestart) {
        pendingBackgroundVideoUri = uri;
        if (forceRestart) releaseBackgroundMediaPlayer();
        textureView.setSurfaceTextureListener(null);
        if (textureView.isAvailable()) {
            textureView.post(() -> startBackgroundMediaPlayer(textureView, uri));
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    startBackgroundMediaPlayer(textureView, uri);
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    applyVideoCenterCrop(textureView, backgroundMediaPlayer);
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    releaseBackgroundMediaPlayer();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
            });
        }
    }

    public void startBackgroundMediaPlayer(TextureView textureView, Uri uri) {
        try {
            releaseBackgroundMediaPlayer();
            MediaPlayer mp = new MediaPlayer();
            backgroundMediaPlayer = mp;
            mp.setDataSource(context, uri);
            Surface surface = new Surface(textureView.getSurfaceTexture());
            mp.setSurface(surface);
            surface.release();
            mp.setLooping(true);
            boolean soundOn = prefs != null && prefs.getBoolean(KEY_BACKGROUND_VIDEO_SOUND, false);
            mp.setVolume(soundOn ? 1f : 0f, soundOn ? 1f : 0f);
            mp.setOnPreparedListener(player -> {
                applyVideoCenterCrop(textureView, player);
                player.start();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                Toast.makeText(context, "视频背景播放失败，请尝试更换视频格式", Toast.LENGTH_SHORT).show();
                releaseBackgroundMediaPlayer();
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            if (prefs != null) prefs.edit().remove(KEY_CUSTOM_BACKGROUND).remove(KEY_CUSTOM_BACKGROUND_TYPE).apply();
            stopBackgroundVideo();
        }
    }

    public void applyVideoCenterCrop(TextureView textureView, MediaPlayer player) {
        if (textureView == null || player == null) return;
        int viewW = textureView.getWidth();
        int viewH = textureView.getHeight();
        int videoW = player.getVideoWidth();
        int videoH = player.getVideoHeight();
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return;
        float scale = Math.max((float) viewW / videoW, (float) viewH / videoH);
        float scaledW = videoW * scale;
        float scaledH = videoH * scale;
        Matrix matrix = new Matrix();
        matrix.setScale(scaledW / viewW, scaledH / viewH, viewW / 2f, viewH / 2f);
        textureView.setTransform(matrix);
    }

    public void releaseBackgroundMediaPlayer() {
        if (backgroundMediaPlayer == null) return;
        try { backgroundMediaPlayer.stop(); } catch (Throwable ignored) { }
        try { backgroundMediaPlayer.release(); } catch (Throwable ignored) { }
        backgroundMediaPlayer = null;
    }

    public void stopBackgroundVideo() {
        pendingBackgroundVideoUri = null;
        releaseBackgroundMediaPlayer();
    }

    public void resumeBackgroundVideoIfNeeded(TextureView textureView) {
        if (prefs == null || !"video".equals(prefs.getString(KEY_CUSTOM_BACKGROUND_TYPE, "image"))) return;
        if (backgroundMediaPlayer != null) {
            try { if (!backgroundMediaPlayer.isPlaying()) backgroundMediaPlayer.start(); } catch (Throwable ignored) { }
        } else if (pendingBackgroundVideoUri != null) {
            if (textureView != null && textureView.getVisibility() == View.VISIBLE) playBackgroundVideo(textureView, pendingBackgroundVideoUri, false);
        }
    }

    public void pauseBackgroundVideoIfNeeded() {
        if (prefs == null || !"video".equals(prefs.getString(KEY_CUSTOM_BACKGROUND_TYPE, "image"))) return;
        try { if (backgroundMediaPlayer != null && backgroundMediaPlayer.isPlaying()) backgroundMediaPlayer.pause(); } catch (Throwable ignored) { }
    }

    public String copyVideoToInternalStorage(Uri uri) {
        try {
            File dir = new File(context.getFilesDir(), "backgrounds");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "bg_video_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(file)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                out.flush();
            }
            return Uri.fromFile(file).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public String copyImageToInternalStorage(Uri uri, String folder, String prefix, int max, int quality) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(context.getContentResolver().openInputStream(uri));
            if (bitmap == null) return null;
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w > max || h > max) {
                float scale = Math.min(max / (float) w, max / (float) h);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), true);
                bitmap.recycle();
                bitmap = scaled;
            }
            File dir = new File(context.getFilesDir(), folder == null ? "images" : folder);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, (prefix == null ? "image_" : prefix) + System.currentTimeMillis() + ".jpg");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            out.flush();
            out.close();
            bitmap.recycle();
            return Uri.fromFile(file).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteInternalFileUri(String uriText) {
        if (uriText == null || uriText.trim().isEmpty()) return;
        try {
            Uri uri = Uri.parse(uriText);
            if (!"file".equalsIgnoreCase(uri.getScheme())) return;
            String path = uri.getPath();
            if (path == null) return;
            File file = new File(path);
            File filesRoot = context.getFilesDir();
            String fp = file.getCanonicalPath();
            String rp = filesRoot.getCanonicalPath();
            if (fp.startsWith(rp) && file.exists()) file.delete();
        } catch (Throwable ignored) { }
    }
}