package com.yuki.yukihub;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.PersistableBundle;
import android.media.MediaPlayer;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;
   
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
 import org.w3c.dom.Element;
 import org.w3c.dom.NodeList;
 
 import java.io.File;
 import java.io.FileInputStream;
 import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
   import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.reflect.Method;

 import javax.xml.parsers.DocumentBuilderFactory;
 import javax.xml.transform.OutputKeys;
 import javax.xml.transform.Transformer;
 import javax.xml.transform.TransformerFactory;
 import javax.xml.transform.dom.DOMSource;
 import javax.xml.transform.stream.StreamResult;
 
 import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.GameRepository.PlayActivity;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.launcher.EmulatorLauncher;
import com.yuki.yukihub.metadata.BangumiClient;
import com.yuki.yukihub.metadata.VndbClient;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.ons.OnsSettings;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanResult;
import com.yuki.yukihub.ui.GameAdapter;
import com.yuki.yukihub.ui.ScanResultAdapter;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.TimeFormatUtil;
import com.yuki.yukihub.util.UiScaleUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Collator;
import java.util.Map;
import java.util.Calendar;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiScaleUtil.wrap(newBase));
    }

    private GameRepository repository;
private MetadataRepository metadataRepository;
    private GameAdapter adapter;
    private final List<Game> allGames = new ArrayList<>();
    private String filter = "ALL";
private String query = "";
private String developerFilter = "";
    private TextView tvEmpty, tvStats, tvProfileName, tvProfileInitial;
private ImageView ivProfileAvatar;
private View profileStatusDot;
private LinearLayout detailPanel, detailMetaPanel;
private ImageView sideDetailCover;
    private TextView sideDetailPlaceholder, sideDetailTitle, sideDetailOriginalTitle, sideDetailHint, sideDetailPath, sideDetailDeveloper, sideDetailDate, sideDetailRating, sideDetailLength, sideDetailTags, sideDescToggle, sideTranslateToggle;
private LinearLayout sideTagContainer;
private ImageView sideScreenshot1, sideScreenshot2;
private TextView sideBtnLaunch, sideBtnOptions;
private boolean sideDescExpanded = false;
private boolean sideShowingTranslatedDescription = false;
private String sideFullDescription = "";
private VnMetadata currentSideMetadata;
    private Game selectedGame;
    private Dialog pendingEditDialog;
    private String pendingDirUri, pendingCoverUri;
    private long runningGameId = -1;
private long runningSessionId = -1;
private long sessionStart = 0;
private boolean launchedExternal = false;
private StorageProbeResult lastStorageProbeResult;
private long lastStorageProbeAt;
private static final long MIN_PLAY_SESSION_MS = 0L;
private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;
private static final long STORAGE_PROBE_TIMEOUT_MS = 1000L;
    private boolean coverScanRunning = false;
    private boolean coverMaintenanceDone = false;
    private boolean autoLibraryScanRunning = false;
    private boolean webDavAutoSyncRunning = false;
    private boolean scanLoadingAnimated = false;
    private ObjectAnimator scanAnimator;
    private ImageView ivScanLoading;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri";
private static final String KEY_SCAN_ROOT_URIS = "scan_root_uris";
private static final int MAX_SCAN_ROOTS = 3;
    private static final String KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth";
    private static final String KEY_AUTO_SCAN_ON_STARTUP = "auto_scan_on_startup";
    private static final String KEY_CHECK_UPDATE_ON_STARTUP = "check_update_on_startup";
    private static final String KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at";
    private static final long UPDATE_AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final String UPDATE_API_URL = "https://api.github.com/repos/xm486/YukiHub/releases/latest";
    private static final String UPDATE_REPO_URL = "https://github.com/xm486/YukiHub";
    private static final String KEY_ENGINE_LABEL_POSITION = "engine_label_position";
    private static final String KEY_SIDE_TRANSLATED_PREFIX = "side_translated_";
    private static final int DEFAULT_STARTUP_SCAN_DEPTH = 2;
    private static final int MAX_STARTUP_SCAN_DEPTH = 4;
private static final String KEY_METADATA_SOURCE = "metadata_source";
    private static final String KEY_BANGUMI_TOKEN = "bangumi_token";
private static final String KEY_KR_COMPAT_MODE = "kr_compat_mode";
private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";
private static final String KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir";
private static final String KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir";
private static final String SOURCE_VNDB = "vndb";
    private static final String SOURCE_BANGUMI = "bangumi";
    private static final String SOURCE_BANGUMI_MIRROR = "bangumi_mirror";
private static final String KEY_SORT_MODE = "sort_mode";
private static final String SORT_MODE_RECENT = "recent";
private static final String SORT_MODE_NAME = "name";
private static final String SORT_MODE_NEWEST = "newest";
private static final String KEY_PROFILE_NAME = "profile_name";
private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
private static final String KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token";
private static final String KEY_AUTH_USER_ID = "auth_user_id";
private static final String KEY_AUTH_NICKNAME = "auth_nickname";
private static final String KEY_AUTH_AVATAR = "auth_avatar";
private static final String KEY_AUTH_EMAIL = "auth_email";
private static final String KEY_AUTH_STATUS = "auth_status";
private static final String AUTH_BASE_URL = "https://yukihub.kesug.com/api";
private static final String KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled";
private static final String KEY_LAST_SYNC_AT = "last_sync_at";
private static final String AUTH_STATUS_ONLINE = "online";
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.58 Mobile Safari/537.36";
private static final String AUTH_STATUS_OFFLINE = "offline";
private static final String AUTH_STATUS_EXPIRED = "expired";
private static final String AUTH_STATUS_SYNCING = "syncing";
private static final String KEY_PROFILE_SIGNATURE = "profile_signature";
private static final String KEY_PROFILE_AVATAR = "profile_avatar";
private static final String KEY_CUSTOM_BACKGROUND = "custom_background";
private static final String KEY_CUSTOM_BACKGROUND_TYPE = "custom_background_type";
private static final String KEY_BACKGROUND_DIM_ENABLED = "background_dim_enabled";
private static final String KEY_BACKGROUND_VIDEO_SOUND = "background_video_sound";
private static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
private static final String KEY_DISCLAIMER_ACCEPTED_AT = "disclaimer_accepted_at";
private static final int DISCLAIMER_VERSION = 1;
private int pendingScanRootReplaceIndex = -2;
private LinearLayout activeScanRootList;
private TextView activeScanRootInfo;

    private ActivityResultLauncher<Uri> scanDirLauncher;
    private ActivityResultLauncher<Uri> editDirLauncher;
private ActivityResultLauncher<String> coverLauncher;
private ActivityResultLauncher<String> profileAvatarLauncher;
private ActivityResultLauncher<String> backgroundPickerLauncher;
private ActivityResultLauncher<String> videoBackgroundPickerLauncher;
private MediaPlayer backgroundMediaPlayer;
private Uri pendingBackgroundVideoUri;
private ActivityResultLauncher<String> backupCreateLauncher;
private ActivityResultLauncher<String[]> backupOpenLauncher;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enterImmersiveMode();
        repository = new GameRepository(this);
        metadataRepository = new MetadataRepository(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!ensureDisclaimerAccepted()) {
            return;
        }
        applyCustomBackground();
        repository.deleteSampleGames();
        finishStalePlaySessionsIfAny();
        setupLaunchers();
        setupUi();
        loadGames();
        if (ivScanLoading != null) ivScanLoading.setVisibility(View.GONE);
        if (prefs != null && prefs.getBoolean(KEY_AUTO_SCAN_ON_STARTUP, false)) {
            autoScanLastRootIfAvailable();
        }
        checkUpdateOnStartupIfEnabled();
        ensureStoragePermissionForInternalKrkr();
    }

    private boolean ensureDisclaimerAccepted() {
        if (prefs == null) return false;
        long acceptedAt = prefs.getLong(KEY_DISCLAIMER_ACCEPTED_AT, 0L);
        boolean accepted = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false);
        if (accepted && acceptedAt > 0) return true;
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_disclaimer_first_launch, null, false);
        CheckBox agree = content.findViewById(R.id.cbDisclaimerAgree);
        TextView btnExit = content.findViewById(R.id.btnDisclaimerExit);
        TextView btnContinue = content.findViewById(R.id.btnDisclaimerContinue);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        dialog.show();
        styleAlertDialogDark(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.78f));
        }
        Runnable refreshContinueState = () -> {
            boolean enabled = agree.isChecked();
            btnContinue.setEnabled(enabled);
            btnContinue.setAlpha(enabled ? 1f : 0.45f);
        };
        refreshContinueState.run();
        agree.setOnCheckedChangeListener((buttonView, isChecked) -> refreshContinueState.run());
        btnExit.setOnClickListener(v -> finish());
        btnContinue.setOnClickListener(v -> {
            if (!agree.isChecked()) return;
            prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).putLong(KEY_DISCLAIMER_ACCEPTED_AT, System.currentTimeMillis()).apply();
            dialog.dismiss();
            recreate();
        });
        return false;
    }

    private void ensureStoragePermissionForInternalKrkr() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                if (!Environment.isExternalStorageManager()) {
                    new AlertDialog.Builder(this)
                            .setTitle("需要文件访问权限")
                            .setMessage("内置 KRKR 引擎需要访问外部存储来显示和读取游戏文件。请在系统页面允许“管理所有文件”。")
                            .setPositiveButton("去授权", (d, w) -> {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                    intent.setData(Uri.parse("package:" + getPackageName()));
                                    startActivity(intent);
                                } catch (Throwable t) {
                                    try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable ignored) { }
                                }
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
            }
        } catch (Throwable ignored) { }
    }

    private void enterImmersiveMode() {
Window window = getWindow();
applyImmersiveToWindow(window);
}

private void applyImmersiveToWindow(Window window) {
if (window == null) return;
window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
View decor = window.getDecorView();
if (decor == null) return;
if (android.os.Build.VERSION.SDK_INT >= 30) {
WindowInsetsController controller = decor.getWindowInsetsController();
if (controller != null) {
controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
}
}
decor.setSystemUiVisibility(
View.SYSTEM_UI_FLAG_FULLSCREEN
| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
);
}

    private void setupLaunchers() {
        scanDirLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
if (uri != null) {
takeFlags(uri);
boolean changed = addOrReplaceScanRoot(uri.toString(), pendingScanRootReplaceIndex);
pendingScanRootReplaceIndex = -2;
if (changed) {
refreshActiveScanRootListUi();
Toast.makeText(this, "扫描目录已更新", Toast.LENGTH_SHORT).show();
}
} else {
pendingScanRootReplaceIndex = -2;
}
});
        editDirLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri != null) {
                takeFlags(uri);
                pendingDirUri = uri.toString();
                if (pendingCoverUri == null || pendingCoverUri.isEmpty()) {
                    Uri autoCover = findFirstLevelImage(pendingDirUri);
                    if (autoCover != null) pendingCoverUri = copyCoverToInternalStorage(autoCover);
                }
                if (pendingEditDialog != null) {
                    ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedDir)).setText(pendingDirUri);
                    Spinner launchSp = pendingEditDialog.findViewById(R.id.spLaunchTarget);
                    List<String> options = buildLaunchOptions(pendingDirUri);
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, options);
                    adapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
                    launchSp.setAdapter(adapter);
                    ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedCover)).setText(emptyText(pendingCoverUri, "未选择封面"));
                }
            }
        });
        coverLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                pendingCoverUri = copyCoverToInternalStorage(uri);
                if (pendingEditDialog != null) ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedCover)).setText(pendingCoverUri == null ? "封面复制失败" : pendingCoverUri);
            }
        });
profileAvatarLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                String avatar = copyImageToInternalStorage(uri, "avatars", "avatar_", 320, 90);
                if (avatar == null || avatar.isEmpty()) {
                    Toast.makeText(this, "头像保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString(KEY_PROFILE_AVATAR, avatar).apply();
                updateProfilePanel();
                showProfileDialog();
            }
        });
        backgroundPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                String bg = copyImageToInternalStorage(uri, "backgrounds", "bg_", 1920, 88);
                if (bg == null || bg.isEmpty()) {
                    Toast.makeText(this, "背景保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                replaceCustomBackground(bg, "image");
                applyCustomBackground();
                Toast.makeText(this, "已设置图片背景", Toast.LENGTH_SHORT).show();
            }
        });
        videoBackgroundPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                String bg = copyVideoToInternalStorage(uri);
                if (bg == null || bg.isEmpty()) {
                    Toast.makeText(this, "视频背景保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                replaceCustomBackground(bg, "video");
                applyCustomBackground();
                Toast.makeText(this, "已设置视频背景", Toast.LENGTH_SHORT).show();
            }
        });

        backupCreateLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri != null) exportLocalBackup(uri);
        });
        backupOpenLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importLocalBackup(uri);
        });
    }

    private File persistentRemoteCoverDir() {
    File dir = new File(getFilesDir(), "covers_remote");
    if (!dir.exists()) dir.mkdirs();
    return dir;
}

private boolean isMissingFileUri(String uriText) {
    if (uriText == null || uriText.trim().isEmpty()) return false;
    try {
        Uri uri = Uri.parse(uriText);
        if (!"file".equalsIgnoreCase(uri.getScheme())) return false;
        String path = uri.getPath();
        return path == null || !(new File(path).exists());
    } catch (Throwable ignored) {
        return false;
    }
}

private void repairMissingMetadataCoversIfNeeded() {
    if (allGames.isEmpty() || metadataRepository == null) return;
    List<Game> targets = new ArrayList<>();
    for (Game g : allGames) {
        if (g == null || g.id <= 0) continue;
        boolean noCover = !hasCover(g);
        boolean missingFile = isMissingFileUri(g.coverPersistUri) || isMissingFileUri(g.coverUri);
        if (noCover || missingFile) targets.add(g);
    }
    if (targets.isEmpty()) return;
    AppExecutors.runOnIo(() -> {
        int changed = 0;
        for (Game g : targets) {
            try {
                VnMetadata meta = usingBangumi() ? metadataRepository.getBangumi(g.id) : metadataRepository.getVndb(g.id);
                if (meta == null) {
                    VnMetadata v = metadataRepository.getVndb(g.id);
                    VnMetadata b = metadataRepository.getBangumi(g.id);
                    meta = v != null ? v : b;
                }
                if (meta == null || meta.coverUrl == null || meta.coverUrl.trim().isEmpty()) continue;
                String cover = cacheRemoteImageSync(meta.coverUrl, "repair_cover_" + emptyText(meta.id, String.valueOf(g.id)));
                if (cover == null || cover.isEmpty()) continue;
                g.coverUri = cover;
                g.coverPersistUri = cover;
                g.coverSourceType = 1;
                repository.update(g);
                changed++;
            } catch (Throwable t) {
                Log.w("YukiHub", "repair cover failed: " + (g == null ? "null" : g.title), t);
            }
        }
        int finalChanged = changed;
        if (finalChanged > 0) runOnUiThread(() -> {
            allGames.clear();
            allGames.addAll(repository.getAll());
            applyFilter();
            Toast.makeText(this, "已恢复 " + finalChanged + " 个同步封面", Toast.LENGTH_SHORT).show();
        });
    });
}

private void deleteInternalFileUri(String uriText) {
    if (uriText == null || uriText.trim().isEmpty()) return;
    try {
        Uri uri = Uri.parse(uriText);
        if (!"file".equalsIgnoreCase(uri.getScheme())) return;
        String path = uri.getPath();
        if (path == null) return;
        File file = new File(path);
        File filesRoot = getFilesDir();
        String fp = file.getCanonicalPath();
        String rp = filesRoot.getCanonicalPath();
        if (fp.startsWith(rp) && file.exists()) file.delete();
    } catch (Throwable ignored) { }
}

private void replaceCustomBackground(String bg, String type) {
    String old = prefs == null ? null : prefs.getString(KEY_CUSTOM_BACKGROUND, "");
    if (prefs != null) prefs.edit().putString(KEY_CUSTOM_BACKGROUND, bg).putString(KEY_CUSTOM_BACKGROUND_TYPE, type).apply();
    if (old != null && !old.equals(bg)) deleteInternalFileUri(old);
}

private String copyCoverToInternalStorage(Uri uri) {
return copyImageToInternalStorage(uri, "covers", "cover_", 720, 88);
}

private void applyCustomBackground() {
    if (prefs == null) return;
    ImageView bgImage = findViewById(R.id.customBackgroundImage);
    TextureView bgVideo = findViewById(R.id.customBackgroundVideo);
    View bgDim = findViewById(R.id.customBackgroundDim);
    View dynamicBg = findViewById(R.id.dynamicBackground);
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

private void playBackgroundVideo(TextureView textureView, Uri uri, boolean forceRestart) {
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

private void startBackgroundMediaPlayer(TextureView textureView, Uri uri) {
    try {
        releaseBackgroundMediaPlayer();
        MediaPlayer mp = new MediaPlayer();
        backgroundMediaPlayer = mp;
        mp.setDataSource(this, uri);
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
            Toast.makeText(this, "视频背景播放失败，请尝试更换视频格式", Toast.LENGTH_SHORT).show();
            releaseBackgroundMediaPlayer();
            return true;
        });
        mp.prepareAsync();
    } catch (Throwable t) {
        if (prefs != null) prefs.edit().remove(KEY_CUSTOM_BACKGROUND).remove(KEY_CUSTOM_BACKGROUND_TYPE).apply();
        applyCustomBackground();
    }
}

private void applyVideoCenterCrop(TextureView textureView, MediaPlayer player) {
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

private void releaseBackgroundMediaPlayer() {
    if (backgroundMediaPlayer == null) return;
    try { backgroundMediaPlayer.stop(); } catch (Throwable ignored) { }
    try { backgroundMediaPlayer.release(); } catch (Throwable ignored) { }
    backgroundMediaPlayer = null;
}

private void stopBackgroundVideo() {
    pendingBackgroundVideoUri = null;
    releaseBackgroundMediaPlayer();
}

private String copyVideoToInternalStorage(Uri uri) {
    try {
        java.io.File dir = new java.io.File(getFilesDir(), "backgrounds");
        if (!dir.exists()) dir.mkdirs();
        java.io.File file = new java.io.File(dir, "bg_video_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = getContentResolver().openInputStream(uri); java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
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

private String copyImageToInternalStorage(Uri uri, String folder, String prefix, int max, int quality) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            if (bitmap == null) return null;
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w > max || h > max) {
                float scale = Math.min(max / (float) w, max / (float) h);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), true);
                bitmap.recycle();
                bitmap = scaled;
            }
            java.io.File dir = new java.io.File(getFilesDir(), folder == null ? "images" : folder);
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, (prefix == null ? "image_" : prefix) + System.currentTimeMillis() + ".jpg");
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            out.flush();
            out.close();
            bitmap.recycle();
            return Uri.fromFile(file).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void takeFlags(Uri uri) {
        if (uri == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            Log.i("YukiHub", "persisted tree permission: " + uri);
        } catch (SecurityException writeDenied) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.i("YukiHub", "persisted read-only tree permission: " + uri);
            } catch (Exception readDenied) {
                Log.w("YukiHub", "persist tree permission failed: " + uri, readDenied);
                Toast.makeText(this, "目录授权保存失败，请重新选择 TF 卡目录", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.w("YukiHub", "persist tree permission failed: " + uri, e);
            Toast.makeText(this, "目录授权保存失败，请重新选择 TF 卡目录", Toast.LENGTH_LONG).show();
        }
    }

    private void scanMissingCoversIfNeeded() {
        if (coverScanRunning || allGames.isEmpty()) return;
        List<Game> targets = new ArrayList<>();
        for (Game g : allGames) {
            if (g == null || g.rootUri == null || g.rootUri.isEmpty()) continue;
            if (hasCover(g)) continue;
            targets.add(g);
        }
        if (targets.isEmpty()) return;
        coverScanRunning = true;
        AppExecutors.runOnIo(() -> {
            int changed = 0;
            for (Game g : targets) {
                try {
                    Uri image = findFirstLevelImage(g.rootUri);
                    if (image == null) continue;
                    String cover = copyCoverToInternalStorage(image);
                    if (cover == null || cover.isEmpty()) continue;
                    g.coverUri = cover;
                    g.coverPersistUri = cover;
                    g.coverSourceType = 1;
                    repository.update(g);
                    changed++;
                } catch (Throwable ignored) { }
            }
            int finalChanged = changed;
            runOnUiThread(() -> {
                coverScanRunning = false;
                if (finalChanged > 0) {
                    allGames.clear();
                    allGames.addAll(repository.getAll());
                    applyFilter();
                }
            });
        });
    }

    private boolean hasCover(Game g) {
        return (g.coverPersistUri != null && !g.coverPersistUri.trim().isEmpty())
                || (g.coverUri != null && !g.coverUri.trim().isEmpty());
    }

    private Uri findFirstLevelImage(String rootUri) {
        try {
            if (rootUri == null || rootUri.trim().isEmpty()) return null;
            DocumentFile dir = null;
            if (rootUri.startsWith("/") || rootUri.startsWith("file://")) {
                File file = new File(rootUri.startsWith("file://") ? Uri.parse(rootUri).getPath() : rootUri);
                dir = DocumentFile.fromFile(file);
            } else {
                dir = DocumentFile.fromTreeUri(this, Uri.parse(rootUri));
            }
            if (dir == null || !dir.isDirectory()) return null;
            DocumentFile[] files = dir.listFiles();
            if (files == null) return null;
            DocumentFile best = null;
            int bestScore = Integer.MIN_VALUE;
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = f.getName();
                if (!isImageFile(name)) continue;
                int score = coverNameScore(name);
                if (best == null || score > bestScore) {
                    best = f;
                    bestScore = score;
                }
            }
            return best == null ? null : best.getUri();
        } catch (Throwable ignored) { return null; }
    }

    private boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private int coverNameScore(String name) {
        if (name == null) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("cover.jpg") || lower.equals("cover.png") || lower.equals("cover.webp")) return 100;
        if (lower.equals("folder.jpg") || lower.equals("folder.png") || lower.equals("folder.webp")) return 95;
        if (lower.contains("cover") || lower.contains("folder") || lower.contains("封面")) return 80;
        if (lower.contains("poster") || lower.contains("package") || lower.contains("main")) return 60;
        return 10;
    }
 
    private void setupUi() {
        RecyclerView recycler = findViewById(R.id.recyclerGames);
        tvEmpty = findViewById(R.id.tvEmpty);
tvStats = findViewById(R.id.tvStats);
tvProfileName = findViewById(R.id.tvProfileName);
tvProfileInitial = findViewById(R.id.tvProfileInitial);
profileStatusDot = findViewById(R.id.profileStatusDot);
ivProfileAvatar = findViewById(R.id.ivProfileAvatar);
detailPanel = findViewById(R.id.detailPanel);
        detailMetaPanel = findViewById(R.id.detailMetaPanel);
        sideDetailCover = findViewById(R.id.sideDetailCover);
        sideDetailPlaceholder = findViewById(R.id.sideDetailPlaceholder);
        sideDetailTitle = findViewById(R.id.sideDetailTitle);
sideDetailOriginalTitle = findViewById(R.id.sideDetailOriginalTitle);
sideDetailHint = findViewById(R.id.sideDetailHint);
sideDetailPath = findViewById(R.id.sideDetailPath);
sideDescToggle = findViewById(R.id.sideDescToggle);
sideTranslateToggle = findViewById(R.id.sideTranslateToggle);
        sideDetailDeveloper = findViewById(R.id.sideDetailDeveloper);
        sideDetailDate = findViewById(R.id.sideDetailDate);
        sideDetailRating = findViewById(R.id.sideDetailRating);
sideDetailLength = findViewById(R.id.sideDetailLength);
sideDetailTags = findViewById(R.id.sideDetailTags);
sideTagContainer = findViewById(R.id.sideTagContainer);
sideScreenshot1 = findViewById(R.id.sideScreenshot1);
sideScreenshot2 = findViewById(R.id.sideScreenshot2);
sideBtnLaunch = findViewById(R.id.sideBtnLaunch);
        sideBtnOptions = findViewById(R.id.sideBtnOptions);
        sideBtnLaunch.setOnClickListener(v -> { clickFeedback(v); if (selectedGame != null) launchGame(selectedGame); });
        sideBtnOptions.setOnClickListener(v -> { clickFeedback(v); if (selectedGame != null) showSideOptions(selectedGame); });
        sideDescToggle.setOnClickListener(v -> { clickFeedback(v); sideDescExpanded = !sideDescExpanded; renderSideDescription(); });
if (sideTranslateToggle != null) sideTranslateToggle.setOnClickListener(v -> { clickFeedback(v); toggleOrTranslateDescription(); });
        updateSideDetail(null);
        adapter = new GameAdapter();
        adapter.setOnGameClickListener(new GameAdapter.OnGameClickListener() {
            @Override public void onGameClick(Game game) { updateSideDetail(game); }
            @Override public void onGameDoubleClick(Game game) { if (game != null) launchGame(game); }
            @Override public void onGameLongClick(Game game) { showEditDialog(game); }
            @Override public void onStatusClick(Game game) { updateSideDetail(game); showPlayStatusDialog(game, null); }
        });
        recycler.setLayoutManager(new GridLayoutManager(this, 5));
        recycler.setAdapter(adapter);
View addButton = findViewById(R.id.btnAdd);
        View scanButton = findViewById(R.id.btnScan);
        ivScanLoading = findViewById(R.id.ivScanLoading);
 View settingsButton = findViewById(R.id.btnSettings);
        applyTopActionFeedback(addButton);
applyTopActionFeedback(scanButton);
applyTopActionFeedback(settingsButton);
addButton.setOnClickListener(v -> { clickFeedback(v); showEditDialog(null); });
scanButton.setOnClickListener(v -> { clickFeedback(v); scanLastRootOrChoose(); });
scanButton.setOnLongClickListener(v -> { clickFeedback(v); launchScanRootPicker(-1); return true; });
settingsButton.setOnClickListener(v -> { clickFeedback(v); showSettingsDialog(); });
        View friendsChatPanel = findViewById(R.id.friendsChatPanel);
if (friendsChatPanel != null) friendsChatPanel.setOnClickListener(v -> { clickFeedback(v); showFriendsChatPlaceholder(); });
View profilePanel = findViewById(R.id.profilePanel);
if (profilePanel != null) profilePanel.setOnClickListener(v -> { clickFeedback(v); showProfileDialog(); });
        setupDeveloperToggle();
bindFilter(R.id.filterAll, "ALL"); bindFilter(R.id.filterRecent, "RECENT");
bindFilter(R.id.filterPlaying, "PLAYING"); bindFilter(R.id.filterCompleted, "COMPLETED"); bindFilter(R.id.filterUnplayed, "UNPLAYED");
        updateFilterSelection();
        ((EditText)findViewById(R.id.etSearch)).addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { query = s.toString(); applyFilter(); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private void applyTopActionFeedback(View view) {
    if (view == null) return;
    view.setOnTouchListener((v, event) -> {
        if (event == null) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            v.animate().cancel();
            v.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.78f).setDuration(70L).start();
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            v.animate().cancel();
            v.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(120L).start();
        }
        return false;
    });
}

private void clickFeedback(View v) {
    if (v == null) return;
    try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
}

private void setScanLoading(boolean loading) {
    if (ivScanLoading == null) return;
    if (loading) {
        ivScanLoading.setVisibility(View.VISIBLE);
        ivScanLoading.setRotation(0f);
        if (scanAnimator == null) {
            scanAnimator = ObjectAnimator.ofFloat(ivScanLoading, View.ROTATION, 0f, 360f);
            scanAnimator.setDuration(900L);
            scanAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            scanAnimator.setInterpolator(new LinearInterpolator());
        }
        if (!scanAnimator.isStarted()) scanAnimator.start();
        scanLoadingAnimated = true;
    } else {
        if (scanAnimator != null) {
            try { scanAnimator.cancel(); } catch (Throwable ignored) { }
        }
        ivScanLoading.setRotation(0f);
        ivScanLoading.setVisibility(View.GONE);
        scanLoadingAnimated = false;
    }
}

private void showProfileDialog() {
    final String currentName = displayProfileName();
    final String localName = profileName();
    final String currentSignature = profileSignature();
    long total = totalPlayTime();

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundResource(R.drawable.bg_dialog);
    int pad = dp(16);
    root.setPadding(pad, dp(14), pad, dp(10));

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(android.view.Gravity.CENTER_VERTICAL);

    FrameLayout avatarBox = new FrameLayout(this);
    avatarBox.setBackgroundResource(R.drawable.bg_cover_placeholder);
    ImageView avatar = new ImageView(this);
    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
    TextView avatarInitial = new TextView(this);
    avatarInitial.setGravity(android.view.Gravity.CENTER);
    avatarInitial.setText(initials(currentName));
    avatarInitial.setTextColor(getColorCompat(R.color.yh_text));
    avatarInitial.setTextSize(24);
    avatarInitial.setTypeface(null, android.graphics.Typeface.BOLD);
    avatarBox.addView(avatar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    avatarBox.addView(avatarInitial, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    loadProfileAvatarInto(avatar, avatarInitial);
    avatarBox.setOnClickListener(v -> profileAvatarLauncher.launch("image/*"));
    header.addView(avatarBox, new LinearLayout.LayoutParams(dp(72), dp(72)));

    LinearLayout info = new LinearLayout(this);
    info.setOrientation(LinearLayout.VERTICAL);
    info.setPadding(dp(12), 0, 0, 0);
    TextView nameView = new TextView(this);
    nameView.setText(currentName);
    nameView.setTextColor(getColorCompat(R.color.yh_text));
    nameView.setTextSize(20);
    nameView.setTypeface(null, android.graphics.Typeface.BOLD);
    TextView statsView = new TextView(this);
    statsView.setText(allGames.size() + " Games · " + TimeFormatUtil.playTime(total) + "\n" + emptyText(currentSignature, "这个人还没有写签名"));
    statsView.setTextColor(getColorCompat(R.color.yh_text_muted));
    statsView.setTextSize(12);
    statsView.setPadding(0, dp(5), 0, 0);
    info.addView(nameView);
    TextView accountBadge = new TextView(this);
    accountBadge.setText(accountStatusLabelForDialog());
    accountBadge.setTextSize(11);
    accountBadge.setTextColor(accountStatusTextColor());
    accountBadge.setGravity(android.view.Gravity.CENTER);
    accountBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
    accountBadge.setBackgroundResource(accountStatusBackground());
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(22));
    badgeLp.setMargins(0, dp(5), 0, 0);
    info.addView(accountBadge, badgeLp);
    info.addView(statsView);
    header.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    root.addView(header);

    TextView avatarHint = new TextView(this);
    avatarHint.setText(isLoggedIn() ? "当前为云账户，退出登录后会恢复本地资料显示。" : "当前为本地账户：昵称、头像和游戏数据只保存在本机。登录后可开启云同步和好友/聊天。点击头像可更换头像。");
    avatarHint.setTextColor(getColorCompat(R.color.yh_primary));
    avatarHint.setTextSize(11);
    avatarHint.setPadding(0, dp(6), 0, dp(8));
    root.addView(avatarHint);

    LinearLayout accountRow = new LinearLayout(this);
    accountRow.setOrientation(LinearLayout.HORIZONTAL);
    Button loginBtn = krButton(isLoggedIn() ? "账号设置" : "登录 / 注册");
    Button syncBtn = krButton("云同步");
    loginBtn.setTextColor(getColorCompat(R.color.yh_primary));
    syncBtn.setTextColor(isLoggedIn() ? getColorCompat(R.color.yh_primary) : getColorCompat(R.color.yh_text_muted));
    syncBtn.setEnabled(isLoggedIn());
    loginBtn.setOnClickListener(v -> showAuthPlaceholderDialog());
    syncBtn.setOnClickListener(v -> Toast.makeText(this, "登录后即可使用云同步", Toast.LENGTH_SHORT).show());
    accountRow.addView(loginBtn, new LinearLayout.LayoutParams(0, dp(40), 1));
    LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(0, dp(40), 1);
    syncLp.setMargins(dp(8), 0, 0, 0);
    accountRow.addView(syncBtn, syncLp);
    root.addView(accountRow);

    LinearLayout statCards = new LinearLayout(this);
    statCards.setOrientation(LinearLayout.HORIZONTAL);
    statCards.setPadding(0, dp(2), 0, dp(10));
    statCards.addView(profileStatCard("游戏", String.valueOf(allGames.size())), new LinearLayout.LayoutParams(0, dp(48), 1));
    LinearLayout.LayoutParams statMid = new LinearLayout.LayoutParams(0, dp(48), 1);
    statMid.setMargins(dp(6), 0, dp(6), 0);
    statCards.addView(profileStatCard("总时长", TimeFormatUtil.playTime(total)), statMid);
    statCards.addView(profileStatCard("今日", TimeFormatUtil.playTime(todayTotalPlayTime())), new LinearLayout.LayoutParams(0, dp(48), 1));
    root.addView(statCards);

    TextView nameLabel = profileLabel("昵称");
    root.addView(nameLabel);
    EditText nameInput = profileEdit(localName, "输入昵称");
    root.addView(nameInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

    TextView signLabel = profileLabel("个人签名");
    signLabel.setPadding(0, dp(10), 0, dp(4));
    root.addView(signLabel);
    EditText signatureInput = profileEdit(currentSignature, "写点什么，比如：今天也要认真补完一部作品");
    signatureInput.setSingleLine(false);
    signatureInput.setMinLines(2);
    signatureInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
    root.addView(signatureInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

    TextView activityTitle = profileLabel("今日动态");
    activityTitle.setPadding(0, dp(12), 0, dp(4));
    root.addView(activityTitle);
    TextView activity = new TextView(this);
    activity.setText(buildTodayActivityText());
    activity.setTextColor(getColorCompat(R.color.yh_text_muted));
    activity.setTextSize(12);
    activity.setLineSpacing(dp(1), 1.0f);
    activity.setBackgroundResource(R.drawable.bg_input);
    activity.setPadding(dp(10), dp(8), dp(10), dp(8));
    root.addView(activity, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView recentTitle = profileLabel("最近动态");
    recentTitle.setPadding(0, dp(12), 0, dp(4));
    root.addView(recentTitle);
    LinearLayout feedList = new LinearLayout(this);
    feedList.setOrientation(LinearLayout.VERTICAL);
    buildRecentActivityViews(feedList);
    root.addView(feedList, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView backupTitle = profileLabel("同步中心");
    backupTitle.setPadding(0, dp(12), 0, dp(4));
    root.addView(backupTitle);
    Button syncCenterBtn = krButton("打开同步中心");
    syncCenterBtn.setTextColor(getColorCompat(R.color.yh_primary));
    syncCenterBtn.setOnClickListener(v -> showWebDavSettingsDialog());
    root.addView(syncCenterBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    TextView backupHint = new TextView(this);
    backupHint.setText("同步中心里包含 WebDAV 云同步、测试连接、自动同步和本地备份/导入。\n本地备份与云同步使用同一套数据结构，避免导入导出逻辑不一致。");
    backupHint.setTextColor(getColorCompat(R.color.yh_text_muted));
    backupHint.setTextSize(10);
    backupHint.setPadding(0, dp(6), 0, 0);
    root.addView(backupHint);

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setBackgroundResource(R.drawable.bg_dialog);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("个人资料")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("更换头像", null)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.62f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
        String sign = signatureInput.getText() == null ? "" : signatureInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(KEY_PROFILE_NAME, name).putString(KEY_PROFILE_SIGNATURE, sign).apply();
        updateProfilePanel();
        Toast.makeText(this, "个人资料已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    });
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> profileAvatarLauncher.launch("image/*"));
}

private String profileName() {
    return prefs == null ? "Yuki" : prefs.getString(KEY_PROFILE_NAME, "Yuki");
}

private String profileSignature() {
    return prefs == null ? "" : prefs.getString(KEY_PROFILE_SIGNATURE, "");
}

private long totalPlayTime() {
    long total = 0;
    for (Game g : allGames) if (g != null) total += g.totalPlayTime;
    return total;
}

private void showFriendsChatPlaceholder() {
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("好友 / 聊天")
            .setMessage("好友与聊天功能即将上线，敬请期待。")
            .setPositiveButton("知道了", null)
            .show();
    styleAlertDialogDark(dialog);
}

private String accountStatusLabelForDialog() {
    String s = accountStatus();
    if ("local".equals(s)) return "本地账户";
    if (AUTH_STATUS_ONLINE.equals(s)) return "云账户 · 在线";
    if (AUTH_STATUS_SYNCING.equals(s)) return "云账户 · 同步中";
    if (AUTH_STATUS_EXPIRED.equals(s)) return "云账户 · 登录过期";
    return "云账户 · 离线";
}

private int accountStatusBackground() {
    String s = accountStatus();
    if ("local".equals(s)) return R.drawable.bg_account_status_local;
    if (AUTH_STATUS_ONLINE.equals(s)) return R.drawable.bg_account_status_online;
    if (AUTH_STATUS_SYNCING.equals(s)) return R.drawable.bg_status_playing;
    if (AUTH_STATUS_EXPIRED.equals(s)) return R.drawable.bg_account_status_expired;
    return R.drawable.bg_account_status_offline;
}

private int accountStatusTextColor() {
    String s = accountStatus();
    if (AUTH_STATUS_ONLINE.equals(s)) return 0xFFE8FFE9;
    if (AUTH_STATUS_EXPIRED.equals(s)) return 0xFFFFF0D6;
    if (AUTH_STATUS_SYNCING.equals(s)) return 0xFFEAF7FF;
    if ("local".equals(s)) return 0xFFDCEBFF;
    return 0xFFE3E8F2;
}

private void showAuthPlaceholderDialog() {
    showAuthDialog();
}

private void showAuthDialog() {
    if (isLoggedIn()) {
        showAccountSettingsDialog();
        return;
    }
    startActivity(new Intent(this, AuthActivity.class));
}

private void showAccountSettingsDialog() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    root.setPadding(pad, dp(12), pad, dp(4));
    TextView info = new TextView(this);
    String email = prefs == null ? "" : prefs.getString(KEY_AUTH_EMAIL, "");
    String name = displayProfileName();
    boolean syncEnabled = prefs == null || prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, true);
    long lastSync = prefs == null ? 0 : prefs.getLong(KEY_LAST_SYNC_AT, 0);
    info.setText("账号：" + name + "\n邮箱：" + emptyText(email, "-") + "\n状态：" + accountStatusLabelForDialog() + "\n云同步：" + (syncEnabled ? "开启" : "关闭") + "\n最后同步：" + (lastSync > 0 ? TimeFormatUtil.date(lastSync) : "尚未同步"));
    info.setTextColor(getColorCompat(R.color.yh_text_muted));
    info.setTextSize(13);
    info.setLineSpacing(dp(2), 1.0f);
    root.addView(info);
    CheckBox syncCheck = krCheckBox("开启云同步", syncEnabled);
    syncCheck.setPadding(0, dp(10), 0, 0);
    root.addView(syncCheck);
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("账号设置")
            .setView(root)
            .setPositiveButton("保存", null)
            .setNeutralButton("退出登录", null)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, syncCheck.isChecked()).apply();
        updateProfilePanel();
        Toast.makeText(this, "账号设置已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    });
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmLogout(dialog));
}

private void confirmLogout(AlertDialog parent) {
    AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出登录不会删除本地游戏库和本地个人资料。云同步、好友/聊天将暂停。")
            .setPositiveButton("退出登录", (x, w) -> {
                logoutLocalOnly();
                if (parent != null) parent.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    styleAlertDialogDark(d);
}

private void showWebDavSettingsDialog() {
    com.yuki.yukihub.sync.WebDavSettingsDialog dialog = com.yuki.yukihub.sync.WebDavSettingsDialog.newInstance();
    dialog.show(getSupportFragmentManager(), "webdav_settings");
}

private void maybeAutoWebDavSync() {
    if (webDavAutoSyncRunning) return;
    com.yuki.yukihub.sync.SyncManager sm = new com.yuki.yukihub.sync.SyncManager(this);
    if (!sm.isConfigured() || !sm.isAutoSyncEnabled()) return;
    long last = sm.getLastSyncTime();
    if (last > 0 && System.currentTimeMillis() - last < 10L * 60L * 1000L) return;
    webDavAutoSyncRunning = true;
    sm.sync(new com.yuki.yukihub.sync.SyncManager.SyncListener() {
        @Override public void onSyncStart() { }
        @Override public void onProgress(String item, boolean changed) { }
        @Override public int onConflict(com.yuki.yukihub.sync.SyncManager.Conflict conflict) { return com.yuki.yukihub.sync.SyncManager.RESOLVE_MERGE; }
        @Override public void onSyncComplete(com.yuki.yukihub.sync.SyncManager.SyncResult result) {
            runOnUiThread(() -> {
                webDavAutoSyncRunning = false;
                if (result != null && result.hasChanges()) {
                    loadGames();
                    updateProfilePanel();
                    Toast.makeText(MainActivity.this, "WebDAV 自动同步完成", Toast.LENGTH_SHORT).show();
                }
            });
        }
        @Override public void onError(String error) {
            runOnUiThread(() -> webDavAutoSyncRunning = false);
        }
    });
}

private void logoutLocalOnly() {
    if (prefs != null) prefs.edit()
            .remove(KEY_AUTH_ACCESS_TOKEN)
            .remove(KEY_AUTH_REFRESH_TOKEN)
            .remove(KEY_AUTH_USER_ID)
            .remove(KEY_AUTH_NICKNAME)
            .remove(KEY_AUTH_AVATAR)
            .remove(KEY_AUTH_STATUS)
            .putBoolean(KEY_CLOUD_SYNC_ENABLED, false)
            .apply();
    updateProfilePanel();
    Toast.makeText(this, "已退出登录，本地账户仍可继续使用", Toast.LENGTH_SHORT).show();
}

private String normalizeBaseUrl(String base) {
    if (base == null) return "";
    String s = base.trim();
    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    return s;
}

private void performAuthRequest(boolean register, String email, String password, String nickname, Runnable onSuccess, Runnable onFailureUi) {
    final String base = normalizeBaseUrl(AUTH_BASE_URL);
    AppExecutors.runOnIo(() -> {
        try {
            String endpoint = register ? "/auth/register" : "/auth/login";
            String params = "email=" + java.net.URLEncoder.encode(email, "UTF-8")
                    + "&password=" + java.net.URLEncoder.encode(password, "UTF-8");
            if (register) {
                params += "&nickname=" + java.net.URLEncoder.encode(nickname, "UTF-8");
            }
            String url = base + endpoint + "?" + params;
            JSONObject resp = getJson(url);

            saveAuthSession(resp, email, nickname);
            runOnUiThread(() -> {
                updateProfilePanel();
                Toast.makeText(this, register ? "注册并登录成功" : "登录成功", Toast.LENGTH_SHORT).show();
                if (onSuccess != null) onSuccess.run();
            });
        } catch (Throwable t) {
            Log.w("YukiHub", "auth failed", t);
            runOnUiThread(() -> {
                if (prefs != null && isLoggedIn()) prefs.edit().putString(KEY_AUTH_STATUS, AUTH_STATUS_OFFLINE).apply();
                updateProfilePanel();
                Toast.makeText(this, "登录/注册失败：" + emptyText(t.getMessage(), "请检查网络或稍后重试"), Toast.LENGTH_LONG).show();
                if (onFailureUi != null) onFailureUi.run();
            });
        }
    });
}

private JSONObject getJson(String urlStr) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
    c.setRequestMethod("GET");
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setRequestProperty("Accept", "application/json,text/plain,*/*");
    c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    c.setRequestProperty("User-Agent", BROWSER_UA);
    c.setRequestProperty("Referer", "https://yukihub.kesug.com/");
    int code = c.getResponseCode();
    String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
    if (text != null && text.trim().startsWith("<")) {
        throw new RuntimeException("服务器返回了HTML页面，可能是免费主机防护页/缓存页，请稍后重试");
    }
    if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + text);
    return text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
}

private JSONObject postJson(String url, JSONObject body, String bearerToken) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setRequestMethod("POST");
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setDoOutput(true);
    c.setRequestProperty("Accept", "application/json");
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android)");
    if (bearerToken != null && !bearerToken.trim().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearerToken.trim());
    byte[] data = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
    c.setFixedLengthStreamingMode(data.length);
    try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) { os.write(data); }
    int code = c.getResponseCode();
    String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
    if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + text);
    return text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
}

/**
 * 用 Refresh Token 获取新的 Access Token
 * @return true 刷新成功，false 刷新失败需要重新登录
 */
private boolean refreshAccessToken() {
    if (prefs == null) return false;
    String refreshToken = prefs.getString(KEY_AUTH_REFRESH_TOKEN, "");
    if (refreshToken == null || refreshToken.isEmpty()) return false;
    
    try {
        String base = normalizeBaseUrl(AUTH_BASE_URL);
        JSONObject req = new JSONObject();
        req.put("refreshToken", refreshToken);
        JSONObject resp = postJson(base + "/auth/refresh", req, null);
        
        String newAccess = firstJsonString(resp, "accessToken", "access_token", "token");
        String newRefresh = firstJsonString(resp, "refreshToken", "refresh_token");
        JSONObject user = resp.optJSONObject("user");
        if (user == null) user = resp.optJSONObject("data") == null ? null : resp.optJSONObject("data").optJSONObject("user");
        
        if (newAccess == null || newAccess.isEmpty()) return false;
        
        // 更新 Token
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_AUTH_ACCESS_TOKEN, newAccess);
        if (newRefresh != null && !newRefresh.isEmpty()) {
            editor.putString(KEY_AUTH_REFRESH_TOKEN, newRefresh);
        }
        // 更新用户信息（如果有）
        if (user != null) {
            String nickname = firstJsonString(user, "nickname", "name", "username");
            String avatar = firstJsonString(user, "avatarUrl", "avatar_url", "avatar");
            if (nickname != null && !nickname.isEmpty()) editor.putString(KEY_AUTH_NICKNAME, nickname);
            if (avatar != null && !avatar.isEmpty()) editor.putString(KEY_AUTH_AVATAR, avatar);
        }
        editor.putString(KEY_AUTH_STATUS, AUTH_STATUS_ONLINE);
        editor.apply();
        
        Log.d("YukiHub", "Token refreshed successfully");
        return true;
    } catch (Throwable t) {
        Log.w("YukiHub", "Token refresh failed", t);
        return false;
    }
}

/**
 * 带自动刷新的 API 请求
 * 如果请求返回 401，自动尝试刷新 Token 后重试
 */
private JSONObject postJsonWithAuth(String url, JSONObject body) throws Exception {
    String token = prefs == null ? "" : prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
    
    try {
        return postJson(url, body, token);
    } catch (RuntimeException e) {
        // 检查是否是 401 错误
        if (e.getMessage() != null && e.getMessage().contains("HTTP 401")) {
            Log.d("YukiHub", "Got 401, attempting token refresh...");
            if (refreshAccessToken()) {
                // 刷新成功，用新 Token 重试
                String newToken = prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
                return postJson(url, body, newToken);
            } else {
                // 刷新失败，标记为登录过期
                if (prefs != null) {
                    prefs.edit().putString(KEY_AUTH_STATUS, AUTH_STATUS_EXPIRED).apply();
                }
                throw new RuntimeException("登录已过期，请重新登录");
            }
        }
        throw e;
    }
}

private void saveAuthSession(JSONObject resp, String emailFallback, String nicknameFallback) throws Exception {
    if (resp == null) throw new RuntimeException("empty response");
    String access = firstJsonString(resp, "accessToken", "access_token", "token");
    String refresh = firstJsonString(resp, "refreshToken", "refresh_token");
    JSONObject user = resp.optJSONObject("user");
    if (user == null) user = resp.optJSONObject("data") == null ? null : resp.optJSONObject("data").optJSONObject("user");
    String userId = user == null ? firstJsonString(resp, "userId", "user_id", "id") : firstJsonString(user, "id", "userId", "user_id");
    String nickname = user == null ? firstJsonString(resp, "nickname", "name", "username") : firstJsonString(user, "nickname", "name", "username");
    String email = user == null ? firstJsonString(resp, "email") : firstJsonString(user, "email");
    String avatar = user == null ? firstJsonString(resp, "avatarUrl", "avatar_url", "avatar") : firstJsonString(user, "avatarUrl", "avatar_url", "avatar");
    if (access == null || access.trim().isEmpty()) throw new RuntimeException("登录失败，请稍后重试");
    if (nickname == null || nickname.trim().isEmpty()) nickname = nicknameFallback;
    if (email == null || email.trim().isEmpty()) email = emailFallback;
    prefs.edit()
            .putString(KEY_AUTH_ACCESS_TOKEN, access)
            .putString(KEY_AUTH_REFRESH_TOKEN, refresh == null ? "" : refresh)
            .putString(KEY_AUTH_USER_ID, userId == null ? "" : userId)
            .putString(KEY_AUTH_NICKNAME, nickname == null ? "" : nickname)
            .putString(KEY_AUTH_EMAIL, email == null ? "" : email)
            .putString(KEY_AUTH_AVATAR, avatar == null ? "" : avatar)
            .putString(KEY_AUTH_STATUS, AUTH_STATUS_ONLINE)
            .putBoolean(KEY_CLOUD_SYNC_ENABLED, true)
            .apply();
}

private String firstJsonString(JSONObject o, String... keys) {
    if (o == null || keys == null) return "";
    for (String k : keys) {
        String v = o.optString(k, "");
        if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
    }
    return "";
}

private TextView profileStatCard(String label, String value) {
    TextView v = new TextView(this);
    v.setText(label + "\n" + value);
    v.setGravity(android.view.Gravity.CENTER);
    v.setTextColor(getColorCompat(R.color.yh_text));
    v.setTextSize(11);
    v.setTypeface(null, android.graphics.Typeface.BOLD);
    v.setLineSpacing(dp(1), 1.0f);
    v.setBackgroundResource(R.drawable.bg_input);
    return v;
}

private TextView profileLabel(String text) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextColor(getColorCompat(R.color.yh_text));
    v.setTextSize(13);
    v.setTypeface(null, android.graphics.Typeface.BOLD);
    v.setPadding(0, 0, 0, dp(4));
    return v;
}

private EditText profileEdit(String value, String hint) {
    EditText v = new EditText(this);
    v.setText(value == null ? "" : value);
    v.setHint(hint);
    v.setTextColor(getColorCompat(R.color.yh_text));
    v.setHintTextColor(getColorCompat(R.color.yh_text_muted));
    v.setTextSize(13);
    v.setSingleLine(true);
    v.setBackgroundResource(R.drawable.bg_input);
    v.setPadding(dp(10), 0, dp(10), 0);
    return v;
}

public void openLocalBackupExportFromSyncCenter() {
    backupCreateLauncher.launch("yukihub_backup_" + System.currentTimeMillis() + ".json");
}

public void openLocalBackupImportFromSyncCenter() {
    confirmImportLocalBackup();
}

private void confirmImportLocalBackup() {
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("本地导入")
            .setMessage("将从备份 JSON 导入个人资料、游戏库、游玩记录和元数据。\n\n导入策略：\n- 游戏按 rootUri 去重合并\n- 游玩记录按 session_uuid 去重\n- 图片只恢复 URI/URL，不复制图片文件\n\n是否继续？")
            .setPositiveButton("选择文件", (d, w) -> backupOpenLauncher.launch(new String[]{"application/json", "text/*", "*/*"}))
            .setNegativeButton("取消", null)
            .show();
    styleAlertDialogDark(dialog);
}

private void exportLocalBackup(Uri uri) {
    try {
        JSONObject root = new com.yuki.yukihub.sync.SyncManager(this).exportSnapshotForLocalBackup();
        root.put("created_at", System.currentTimeMillis());
        root.put("backup_type", "local_full");
        root.put("note", "Local backup uses the same schema as WebDAV sync, but keeps full play session history.");
        byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("openOutputStream failed");
            out.write(bytes);
            out.flush();
        }
        Toast.makeText(this, "备份完成：" + (bytes.length / 1024) + "KB", Toast.LENGTH_LONG).show();
    } catch (Throwable t) {
        Toast.makeText(this, "备份失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        Log.e("YukiHub", "export backup failed", t);
    }
}

private void importLocalBackup(Uri uri) {
    try {
        String text = readTextFromUri(uri);
        JSONObject root = new JSONObject(text);
        if (!"YukiHub".equals(root.optString("app", ""))) {
            Toast.makeText(this, "不是有效的 YukiHub 备份", Toast.LENGTH_LONG).show();
            return;
        }
        new com.yuki.yukihub.sync.SyncManager(this).importSnapshotFromLocalBackup(root);
        loadGames();
        applyCustomBackground();
        updateProfilePanel();
        int gameCount = root.optJSONArray("games") == null ? 0 : root.optJSONArray("games").length();
        int sessionCount = root.optJSONArray("play_sessions") == null ? 0 : root.optJSONArray("play_sessions").length();
        int metaCount = root.optJSONArray("metadata_cache") == null ? 0 : root.optJSONArray("metadata_cache").length();
        Toast.makeText(this, "导入完成：游戏 " + gameCount + "，记录 " + sessionCount + "，元数据 " + metaCount, Toast.LENGTH_LONG).show();
    } catch (Throwable t) {
        Toast.makeText(this, "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        Log.e("YukiHub", "import backup failed", t);
    }
}

private String readTextFromUri(Uri uri) throws Exception {
    try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
        if (in == null) throw new Exception("openInputStream failed");
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toString("UTF-8");
    }
}

private void buildRecentActivityViews(LinearLayout container) {
    if (container == null) return;
    List<PlayActivity> activities = repository == null ? new ArrayList<>() : repository.getRecentPlayActivities(8);
    if (activities.isEmpty()) {
        TextView empty = new TextView(this);
        empty.setText("暂无动态。开始游玩后，这里会记录你的足迹。");
        empty.setTextColor(getColorCompat(R.color.yh_text_muted));
        empty.setTextSize(12);
        empty.setBackgroundResource(R.drawable.bg_input);
        empty.setPadding(dp(10), dp(8), dp(10), dp(8));
        container.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return;
    }
    for (PlayActivity a : activities) {
        TextView item = new TextView(this);
        item.setText("玩了《" + a.gameTitle + "》 " + TimeFormatUtil.playTime(a.duration) + "\n" + TimeFormatUtil.date(a.endTime) + " · " + launchTypeLabel(a.launchType));
        item.setTextColor(getColorCompat(R.color.yh_text));
        item.setTextSize(12);
        item.setLineSpacing(dp(1), 1.0f);
        item.setBackgroundResource(R.drawable.bg_input);
        item.setPadding(dp(10), dp(8), dp(10), dp(8));
        item.setOnClickListener(v -> showPlayActivityDetail(a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        container.addView(item, lp);
    }
}

private void showPlayActivityDetail(PlayActivity a) {
    if (a == null) return;
    String text = "游戏：" + a.gameTitle + "\n"
            + "开始：" + TimeFormatUtil.date(a.startTime) + "\n"
            + "结束：" + TimeFormatUtil.date(a.endTime) + "\n"
            + "时长：" + TimeFormatUtil.playTime(a.duration) + "\n"
            + "启动类型：" + launchTypeLabel(a.launchType) + "\n"
            + "会话ID：" + emptyText(a.sessionUuid, String.valueOf(a.sessionId));
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("动态详情")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
}

private String launchTypeLabel(String launchType) {
    String t = launchType == null ? "" : launchType;
    if (t.startsWith("internal.krkr")) return "内置 KRKR";
    if (t.startsWith("internal.ons")) return "内置 ONS";
    if (t.startsWith("internal.tyrano")) return "内置 Tyrano";
    if (t.startsWith("internal.artemis")) return "内置 Artemis";
    return "外部模拟器";
}

private void updateProfilePanel() {
    String name = displayProfileName();
    long total = totalPlayTime();
    if (tvProfileName != null) tvProfileName.setText(name);
    if (tvProfileInitial != null) tvProfileInitial.setText(initials(name));
    updateProfileStatusDot();
    if (tvStats != null) tvStats.setText(allGames.size() + " Games\n" + TimeFormatUtil.playTime(total) + " Played");
    loadProfileAvatarInto(ivProfileAvatar, tvProfileInitial);
}

private void updateProfileStatusDot() {
    if (profileStatusDot == null) return;
    String status = accountStatus();
    if ("local".equals(status)) {
        profileStatusDot.setBackgroundResource(R.drawable.bg_profile_dot_local);
    } else if (AUTH_STATUS_ONLINE.equals(status)) {
        profileStatusDot.setBackgroundResource(R.drawable.bg_profile_dot_online);
    } else if (AUTH_STATUS_SYNCING.equals(status)) {
        profileStatusDot.setBackgroundResource(R.drawable.bg_profile_dot_syncing);
    } else if (AUTH_STATUS_EXPIRED.equals(status)) {
        profileStatusDot.setBackgroundResource(R.drawable.bg_profile_dot_expired);
    } else {
        profileStatusDot.setBackgroundResource(R.drawable.bg_profile_dot_local);
    }
}

private boolean isLoggedIn() {
    if (prefs == null) return false;
    String token = prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
    return token != null && !token.trim().isEmpty();
}

private String displayProfileName() {
    if (prefs != null && isLoggedIn()) {
        String cloudName = prefs.getString(KEY_AUTH_NICKNAME, "");
        if (cloudName != null && !cloudName.trim().isEmpty()) return cloudName.trim();
    }
    return profileName();
}

private String accountStatus() {
    if (!isLoggedIn()) return "local";
    String status = prefs == null ? AUTH_STATUS_OFFLINE : prefs.getString(KEY_AUTH_STATUS, AUTH_STATUS_OFFLINE);
    if (AUTH_STATUS_ONLINE.equals(status) || AUTH_STATUS_SYNCING.equals(status) || AUTH_STATUS_EXPIRED.equals(status)) return status;
    return AUTH_STATUS_OFFLINE;
}

private void loadProfileAvatarInto(ImageView avatar, TextView initial) {
if (avatar == null) return;
String uri = "";
if (prefs != null && isLoggedIn()) uri = prefs.getString(KEY_AUTH_AVATAR, "");
if (uri == null || uri.isEmpty()) uri = prefs == null ? "" : prefs.getString(KEY_PROFILE_AVATAR, "");
    if (uri == null || uri.isEmpty()) {
        avatar.setVisibility(View.GONE);
        if (initial != null) initial.setVisibility(View.VISIBLE);
        return;
    }
    try {
        avatar.setImageURI(Uri.parse(uri));
        avatar.setVisibility(View.VISIBLE);
        if (initial != null) initial.setVisibility(View.GONE);
    } catch (Throwable t) {
        avatar.setVisibility(View.GONE);
        if (initial != null) initial.setVisibility(View.VISIBLE);
    }
}

private long todayTotalPlayTime() {
    if (repository == null) return 0L;
    Calendar start = Calendar.getInstance();
    start.set(Calendar.HOUR_OF_DAY, 0);
    start.set(Calendar.MINUTE, 0);
    start.set(Calendar.SECOND, 0);
    start.set(Calendar.MILLISECOND, 0);
    Calendar end = (Calendar) start.clone();
    end.add(Calendar.DAY_OF_MONTH, 1);
    long total = 0L;
    Map<String, Long> today = repository.getPlayDurationsBetween(start.getTimeInMillis(), end.getTimeInMillis());
    for (Long v : today.values()) total += v == null ? 0L : v;
    return total;
}

private String buildTodayActivityText() {
    if (repository == null) return "今天还没有游玩记录。";
    Calendar start = Calendar.getInstance();
    start.set(Calendar.HOUR_OF_DAY, 0);
    start.set(Calendar.MINUTE, 0);
    start.set(Calendar.SECOND, 0);
    start.set(Calendar.MILLISECOND, 0);
    Calendar end = (Calendar) start.clone();
    end.add(Calendar.DAY_OF_MONTH, 1);
    Map<String, Long> today = repository.getPlayDurationsBetween(start.getTimeInMillis(), end.getTimeInMillis());
    if (today.isEmpty()) return "今天还没有游玩记录。\n启动游戏后，返回 YukiHub 就会生成动态。";
    StringBuilder sb = new StringBuilder();
    int count = 0;
    long total = 0;
    for (Map.Entry<String, Long> e : today.entrySet()) {
        if (count >= 5) break;
        long duration = e.getValue() == null ? 0L : e.getValue();
        total += duration;
        if (count > 0) sb.append('\n');
        sb.append("今天玩了《").append(e.getKey()).append("》 ").append(TimeFormatUtil.playTime(duration));
        count++;
    }
    if (today.size() > count) sb.append('\n').append("还有 ").append(today.size() - count).append(" 个游戏的记录...");
    sb.append("\n今日合计：").append(TimeFormatUtil.playTime(total));
    return sb.toString();
}

private void setupDeveloperToggle() {
    TextView title = findViewById(R.id.filterDeveloper);
    View list = findViewById(R.id.developerList);
    if (title == null || list == null) return;
    title.setOnClickListener(v -> {
        boolean show = list.getVisibility() != View.VISIBLE;
        list.setVisibility(show ? View.VISIBLE : View.GONE);
        title.setText(show ? "▾ 开发商" : "▸ 开发商");
    });
}

private void rebuildDeveloperFilters() {
    LinearLayout list = findViewById(R.id.developerList);
    TextView title = findViewById(R.id.filterDeveloper);
    if (list == null || title == null) return;
    list.removeAllViews();
    java.util.Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Game g : allGames) {
        String dev = developerOf(g);
        if (dev == null || dev.trim().isEmpty() || "-".equals(dev.trim())) continue;
        String[] parts = dev.split("/|、|,|，");
        for (String p : parts) {
            String name = p == null ? "" : p.trim();
            if (name.isEmpty()) continue;
            counts.put(name, counts.containsKey(name) ? counts.get(name) + 1 : 1);
        }
    }
    if (counts.isEmpty()) {
        TextView empty = sidebarDeveloperItem("暂无开发商", "");
        empty.setAlpha(0.45f);
        empty.setEnabled(false);
        list.addView(empty);
        title.setAlpha(0.55f);
        return;
    }
    title.setAlpha(1f);
    TextView all = sidebarDeveloperItem("全部开发商", "");
    list.addView(all);
    for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
        list.addView(sidebarDeveloperItem(e.getKey() + " (" + e.getValue() + ")", e.getKey()));
    }
    updateDeveloperFilterSelection();
}

private TextView sidebarDeveloperItem(String text, String developer) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTag(developer == null ? "" : developer);
    v.setGravity(android.view.Gravity.CENTER_VERTICAL);
    v.setMinHeight(dp(24));
    v.setPadding(dp(14), 0, dp(4), 0);
    v.setTextSize(8);
    v.setSingleLine(true);
    v.setEllipsize(android.text.TextUtils.TruncateAt.END);
    v.setBackgroundResource(R.drawable.bg_sidebar_item);
    v.setTextColor(getColorCompat(R.color.yh_text_muted));
    v.setOnClickListener(view -> {
        developerFilter = developer == null ? "" : developer;
        updateDeveloperFilterSelection();
        applyFilter();
    });
    return v;
}

private void updateDeveloperFilterSelection() {
    LinearLayout list = findViewById(R.id.developerList);
    if (list == null) return;
    for (int i = 0; i < list.getChildCount(); i++) {
        View child = list.getChildAt(i);
        if (!(child instanceof TextView)) continue;
        String dev = child.getTag() instanceof String ? (String) child.getTag() : "";
        boolean selected = (developerFilter == null ? "" : developerFilter).equals(dev);
        child.setSelected(selected);
        child.setAlpha(selected ? 1f : 0.72f);
        ((TextView) child).setTextColor(selected ? getColorCompat(R.color.yh_text) : getColorCompat(R.color.yh_text_muted));
        ((TextView) child).setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }
}

private String developerOf(Game game) {
    if (game == null || metadataRepository == null) return "";
    VnMetadata meta = metadataRepository.getVndb(game.id);
    if (meta == null) meta = metadataRepository.getBangumi(game.id);
    return meta == null ? "" : emptyText(meta.developer, "");
}

private void bindFilter(int id, String value) {
        View item = findViewById(id);
        item.setOnClickListener(v -> {
            filter = value;
            developerFilter = "";
            updateFilterSelection();
            applyFilter();
        });
    }

    private void updateFilterSelection() {
        updateFilterItem(R.id.filterAll, "ALL");
        updateFilterItem(R.id.filterRecent, "RECENT");
        updateFilterItem(R.id.filterPlaying, "PLAYING");
        updateFilterItem(R.id.filterCompleted, "COMPLETED");
        updateFilterItem(R.id.filterUnplayed, "UNPLAYED");
        updateDeveloperFilterSelection();
    }

    private void updateFilterItem(int id, String value) {
        View view = findViewById(id);
        boolean selected = value.equals(filter);
        view.setSelected(selected);
        view.setAlpha(selected ? 1f : 0.72f);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(selected ? getColorCompat(R.color.yh_text) : getColorCompat(R.color.yh_text_muted));
            ((TextView) view).setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }
    private void loadGames() {
allGames.clear();
allGames.addAll(repository.getAll());
rebuildDeveloperFilters();
applyFilter();
runCoverMaintenanceOnceIfNeeded();
}

private void runCoverMaintenanceOnceIfNeeded() {
if (coverMaintenanceDone) return;
coverMaintenanceDone = true;
repairMissingMetadataCoversIfNeeded();
scanMissingCoversIfNeeded();
}

    private void applyFilter() {
        List<Game> shown = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        long total = 0;
        for (Game g : allGames) {
            total += g.totalPlayTime;
            if (!q.isEmpty() && (g.title == null || !g.title.toLowerCase(Locale.ROOT).contains(q))) continue;
            if ("RECENT".equals(filter) && g.lastPlayedAt <= 0) continue;
            if ("PLAYING".equals(filter) && !"playing".equals(normalizePlayStatus(g.playStatus))) continue;
            if ("COMPLETED".equals(filter) && !"completed".equals(normalizePlayStatus(g.playStatus))) continue;
            if ("UNPLAYED".equals(filter) && !"unplayed".equals(normalizePlayStatus(g.playStatus))) continue;
            if ("KIRIKIRI".equals(filter) && g.engine != EngineType.KIRIKIRI) continue;
            if ("ONS".equals(filter) && g.engine != EngineType.ONS) continue;
            if ("TYRANO".equals(filter) && g.engine != EngineType.TYRANO) continue;
            if ("ARTEMIS".equals(filter) && g.engine != EngineType.ARTEMIS) continue;
            if ("WINLATOR".equals(filter) && g.engine != EngineType.WINLATOR) continue;
            if ("GAMEHUB".equals(filter) && g.engine != EngineType.GAMEHUB) continue;
            if ("UNKNOWN".equals(filter) && g.engine != EngineType.UNKNOWN) continue;
            if (developerFilter != null && !developerFilter.isEmpty()) {
                String dev = developerOf(g);
                if (dev == null || !dev.toLowerCase(Locale.ROOT).contains(developerFilter.toLowerCase(Locale.ROOT))) continue;
            }
            shown.add(g);
        }
        sortGames(shown);
        adapter.submit(shown);
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        tvStats.setText(allGames.size() + " Games\n" + TimeFormatUtil.playTime(total));
        updateProfilePanel();
        if (shown.isEmpty()) {
            updateSideDetail(null);
        } else if (selectedGame == null || !containsGameId(shown, selectedGame.id)) {
            updateSideDetail(shown.get(0));
        }
    }

    private void sortGames(List<Game> list) {
        if (list == null || list.size() <= 1) return;
        String mode = prefs == null ? SORT_MODE_RECENT : prefs.getString(KEY_SORT_MODE, SORT_MODE_RECENT);
        java.util.Comparator<Game> cmp;
        if (SORT_MODE_NAME.equals(mode)) {
            final Collator collator = Collator.getInstance(Locale.CHINA);
            collator.setStrength(Collator.PRIMARY);
            cmp = (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                boolean af = a.favorite;
                boolean bf = b.favorite;
                if (af != bf) return af ? -1 : 1;
                String at = a.title == null ? "" : a.title;
                String bt = b.title == null ? "" : b.title;
                int r = collator.compare(at, bt);
                if (r != 0) return r;
                return Long.compare(b.createdAt, a.createdAt);
            };
        } else if (SORT_MODE_NEWEST.equals(mode)) {
            cmp = (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                boolean af = a.favorite;
                boolean bf = b.favorite;
                if (af != bf) return af ? -1 : 1;
                int r = Long.compare(b.createdAt, a.createdAt);
                if (r != 0) return r;
                return Long.compare(b.lastPlayedAt, a.lastPlayedAt);
            };
        } else {
            cmp = (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                boolean af = a.favorite;
                boolean bf = b.favorite;
                if (af != bf) return af ? -1 : 1;
                int r = Long.compare(b.lastPlayedAt, a.lastPlayedAt);
                if (r != 0) return r;
                r = Long.compare(b.createdAt, a.createdAt);
                if (r != 0) return r;
                String at = a.title == null ? "" : a.title;
                String bt = b.title == null ? "" : b.title;
                return at.compareToIgnoreCase(bt);
            };
        }
        list.sort(cmp);
    }

    private boolean containsGameId(List<Game> games, long id) {
    if (games == null) return false;
    for (Game g : games) if (g != null && g.id == id) return true;
    return false;
}

private void loadRemoteImage(String url, ImageView target) {
    loadRemoteImage(url, target, "img");
}

private void loadRemoteImage(String url, ImageView target, String prefix) {
    if (target == null) return;
    if (url == null || url.trim().isEmpty()) { target.setImageDrawable(null); return; }
    final String imageUrl = url.trim();
    AppExecutors.runOnIo(() -> {
        try {
            File cacheDir = prefix != null && prefix.startsWith("cover_") ? persistentRemoteCoverDir() : new File(getCacheDir(), "vndb_images");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File cacheFile = new File(cacheDir, safeCacheName(prefix + "_" + imageUrl));
            Bitmap bitmap = null;
            if (cacheFile.exists() && cacheFile.length() > 0) {
                bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (bitmap == null) cacheFile.delete();
            }
            if (bitmap == null) {
                boolean ok = downloadImageAllowVndbWarningPage(imageUrl, cacheFile, 0);
                if (!ok) return;
                bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (bitmap == null) { cacheFile.delete(); return; }
            }
            Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> {
                if (finalBitmap == null || target.getWindowToken() == null) return;
                target.setImageBitmap(finalBitmap);
                Object tag = target.getTag();
                if (tag instanceof Game && prefix != null && prefix.startsWith("cover_") && cacheFile.exists()) {
                    Game taggedGame = (Game) tag;
                    String local = Uri.fromFile(cacheFile).toString();
                    if (taggedGame.coverUri == null || taggedGame.coverUri.isEmpty() || isMissingFileUri(taggedGame.coverUri)) {
                        taggedGame.coverUri = local;
                        taggedGame.coverPersistUri = local;
                        taggedGame.coverSourceType = 1;
                        try { repository.update(taggedGame); } catch (Throwable ignored) { }
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }
                }
            });
        } catch (Throwable ignored) { }
    });
}

private String metadataSource() {
    return prefs == null ? SOURCE_VNDB : prefs.getString(KEY_METADATA_SOURCE, SOURCE_VNDB);
}

private String metadataSourceLabel() {
    String source = metadataSource();
    if (SOURCE_BANGUMI.equals(source)) return "Bangumi";
    if (SOURCE_BANGUMI_MIRROR.equals(source)) return "Bangumi镜像";
    return "VNDB";
}

private boolean usingBangumi() {
    String source = metadataSource();
    return SOURCE_BANGUMI.equals(source) || SOURCE_BANGUMI_MIRROR.equals(source);
}

private boolean usingBangumiMirror() {
    return SOURCE_BANGUMI_MIRROR.equals(metadataSource());
}

private String bangumiToken() {
    return prefs == null ? "" : prefs.getString(KEY_BANGUMI_TOKEN, "");
}

private void fetchSelectedMetadata(Game game) {
    if (game == null) return;

    // 1. 优先显示当前资料源已有缓存。
    VnMetadata cached = currentSourceCachedMetadata(game.id);
    if (cached != null) {
        applyVndbMetadata(cached, game);
        return;
    }

    // 2. 当前源没有缓存时，不要把已经匹配过的其它源资料一刀切成“未匹配”。
    //    例如切到 Bangumi 后，原来 VNDB 已匹配的游戏仍继续显示 VNDB 资料。
    VnMetadata fallback = otherSourceCachedMetadata(game.id);
    if (fallback != null) {
        applyVndbMetadata(fallback, game);
        return;
    }

    // 3. 只有这个游戏完全没有任何资料缓存时，才按当前资料源自动匹配。
    if (usingBangumi()) fetchBangumiMetadata(game, false); else fetchVndbMetadata(game, false);
}

private void fetchSelectedMetadata(Game game, boolean forceRefresh) {
    if (forceRefresh) {
        if (usingBangumi()) fetchBangumiMetadata(game, true); else fetchVndbMetadata(game, true);
    } else {
        fetchSelectedMetadata(game);
    }
}

private VnMetadata currentSourceCachedMetadata(long gameId) {
    if (metadataRepository == null || gameId <= 0) return null;
    return usingBangumi() ? metadataRepository.getBangumi(gameId) : metadataRepository.getVndb(gameId);
}

private VnMetadata otherSourceCachedMetadata(long gameId) {
    if (metadataRepository == null || gameId <= 0) return null;
    return usingBangumi() ? metadataRepository.getVndb(gameId) : metadataRepository.getBangumi(gameId);
}

    private void fetchVndbMetadata(Game game, boolean forceRefresh) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return;
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        VnMetadata cached = metadataRepository == null || forceRefresh ? null : metadataRepository.getVndb(id);
        if (cached != null) {
            applyVndbMetadata(cached, game);
            return;
        }
        setSideDescription("正在从 VNDB 获取资料…");
        VndbClient.searchCandidatesAsync(keyword, 5, new VndbClient.CandidatesCallback() {
        @Override public void onSuccess(List<VnMetadata> data) {
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != id) return;
                if (data == null || data.isEmpty()) {
                    applyVndbMetadata(null, game);
                } else if (data.size() == 1 || isConfidentMatch(game.title, data.get(0))) {
                    metadataRepository.saveVndb(id, data.get(0));
                    applyVndbMetadata(data.get(0), game);
                } else {
                    showVndbCandidateDialog(game, data);
                }
            });
        }
        @Override public void onError(Exception error) {
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != id) return;
                setSideDescription(emptyText(game.description, "VNDB 暂未匹配到资料。"));
            });
        }
    });
}

private void fetchBangumiMetadata(Game game, boolean forceRefresh) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return;
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        VnMetadata cached = metadataRepository == null || forceRefresh ? null : metadataRepository.getBangumi(id);
        if (cached != null) {
            applyVndbMetadata(cached, game);
            return;
        }
        String token = bangumiToken();
        if (token == null || token.trim().isEmpty()) {
        sideDetailOriginalTitle.setText("Bangumi 未配置 Token");
        setSideDescription("请在右上角 设置 -> 元数据源 中填写 Bangumi Access Token。\n\n提示：Bangumi 官方建议账号注册超过三个月后再申请和使用 Token。");
        return;
    }
    setSideDescription("正在从 Bangumi 获取资料…");
    AppExecutors.runOnIo(() -> {
        try {
            VnMetadata meta = BangumiClient.searchFirst(keyword, token, usingBangumiMirror());
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != id) return;
                if (meta == null) {
                    applyVndbMetadata(null, game);
                } else {
                    metadataRepository.saveBangumi(id, meta);
                    applyVndbMetadata(meta, game);
                }
            });
        } catch (Throwable t) {
            Log.w("YukiHub", "Bangumi metadata failed", t);
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != id) return;
                setSideDescription("Bangumi 获取失败。请检查 Token 是否正确，账号是否满足使用条件，或稍后重试。\n\n" + t.getMessage());
            });
        }
    });
}

private boolean downloadImageAllowVndbWarningPage(String imageUrl, File cacheFile, int depth) {
    if (imageUrl == null || imageUrl.trim().isEmpty() || cacheFile == null || depth > 2) return false;
    try {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(imageUrl).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(9000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 YukiHub/1.0");
        // 不优先请求 AVIF，避免部分 Android BitmapFactory 解码失败。
        c.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        c.setRequestProperty("Referer", "https://vndb.org/");
        c.setRequestProperty("Cookie", "vndb_img=1; vndb_samesite=1");
        String type = c.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/")) {
            try (InputStream is = c.getInputStream(); FileOutputStream fos = new FileOutputStream(cacheFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            }
            return cacheFile.exists() && cacheFile.length() > 0;
        }
        String html = readSmallText(c.getInputStream());
        String next = extractImageUrlFromHtml(html, imageUrl);
        return next != null && !next.equals(imageUrl) && downloadImageAllowVndbWarningPage(next, cacheFile, depth + 1);
    } catch (Throwable ignored) {
        return false;
    }
}

private String readSmallText(InputStream is) throws Exception {
    if (is == null) return "";
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int total = 0, len;
    while ((len = is.read(buf)) != -1 && total < 256 * 1024) {
        bos.write(buf, 0, len);
        total += len;
    }
    return bos.toString("UTF-8");
}

private boolean isTranslatedStateFor(long gameId) {
    if (prefs == null || gameId <= 0) return false;
    return prefs.getBoolean(KEY_SIDE_TRANSLATED_PREFIX + gameId, false);
}

private void setTranslatedStateFor(long gameId, boolean translated) {
    if (prefs == null || gameId <= 0) return;
    prefs.edit().putBoolean(KEY_SIDE_TRANSLATED_PREFIX + gameId, translated).apply();
}

private void updateTranslateButtonState() {
    if (sideTranslateToggle == null) return;
    boolean hasMeta = currentSideMetadata != null;
    boolean hasDescription = hasMeta && currentSideMetadata.description != null && !currentSideMetadata.description.trim().isEmpty();
    sideTranslateToggle.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
    if (!hasDescription) return;
    sideTranslateToggle.setText(sideShowingTranslatedDescription ? "原文" : "译文");
    sideTranslateToggle.setEnabled(true);
    sideTranslateToggle.setAlpha(1f);
}

private void toggleOrTranslateDescription() {
    if (selectedGame == null || currentSideMetadata == null) return;
    VnMetadata meta = currentSideMetadata;
    if (sideShowingTranslatedDescription) {
        sideShowingTranslatedDescription = false;
        setTranslatedStateFor(selectedGame.id, false);
        setSideDescription(emptyText(meta.description, "暂无 VNDB 简介。"));
        updateTranslateButtonState();
        return;
    }
    if (meta.translatedDescription != null && !meta.translatedDescription.trim().isEmpty()) {
        sideShowingTranslatedDescription = true;
        setTranslatedStateFor(selectedGame.id, true);
        setSideDescription(meta.translatedDescription);
        updateTranslateButtonState();
        return;
    }
    final long gameId = selectedGame.id;
    sideTranslateToggle.setText("...");
    sideTranslateToggle.setEnabled(false);
    sideTranslateToggle.setAlpha(0.65f);
    AppExecutors.runOnIo(() -> {
        try {
            String translated = translateTextToChinese(meta.description);
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != gameId || currentSideMetadata != meta) return;
                if (translated == null || translated.trim().isEmpty()) {
                    Toast.makeText(this, "简介翻译失败", Toast.LENGTH_SHORT).show();
                    updateTranslateButtonState();
                    return;
                }
                meta.translatedDescription = translated.trim();
                if (metadataRepository != null) metadataRepository.saveVndb(gameId, meta);
                sideShowingTranslatedDescription = true;
                setTranslatedStateFor(gameId, true);
                setSideDescription(meta.translatedDescription);
                updateTranslateButtonState();
            });
        } catch (Throwable t) {
            Log.w("YukiHub", "translate description failed", t);
            runOnUiThread(() -> {
                if (selectedGame != null && selectedGame.id == gameId) {
                    Toast.makeText(this, "简介翻译失败", Toast.LENGTH_SHORT).show();
                    updateTranslateButtonState();
                }
            });
        }
    });
}

private String translateTextToChinese(String text) throws Exception {
    if (text == null || text.trim().isEmpty()) return "";
    List<String> parts = splitTextForTranslation(text.trim(), 480);
    StringBuilder out = new StringBuilder();
    Throwable last = null;
    for (String part : parts) {
        if (part == null || part.trim().isEmpty()) continue;
        String translated = null;
        try {
            translated = translateTextByMyMemory(part);
        } catch (Throwable t) {
            last = t;
            try { translated = translateTextByGoogleapis(part); }
            catch (Throwable t2) { last = t2; }
        }
        if (translated == null || translated.trim().isEmpty()) {
            if (last instanceof Exception) throw (Exception) last;
            throw new RuntimeException("Translate empty result");
        }
        if (out.length() > 0) out.append("\n\n");
        out.append(translated.trim());
        try { Thread.sleep(220); } catch (InterruptedException ignored) { }
    }
    return out.toString().trim();
}

private List<String> splitTextForTranslation(String text, int maxLen) {
    List<String> list = new ArrayList<>();
    if (text == null) return list;
    String s = text.trim();
    while (s.length() > maxLen) {
        int cut = Math.max(s.lastIndexOf("\n", maxLen), Math.max(s.lastIndexOf(". ", maxLen), s.lastIndexOf("。", maxLen)));
        if (cut < maxLen / 2) cut = maxLen;
        list.add(s.substring(0, Math.min(cut + 1, s.length())).trim());
        s = s.substring(Math.min(cut + 1, s.length())).trim();
    }
    if (!s.isEmpty()) list.add(s);
    return list;
}

private String translateTextByMyMemory(String q) throws Exception {
    String url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(q, "UTF-8") + "&langpair=en%7Czh-CN";
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(12000);
    c.setReadTimeout(18000);
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 YukiHub/1.0");
    c.setRequestProperty("Accept", "application/json,text/plain,*/*");
    c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    int code = c.getResponseCode();
    String body = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
    if (code < 200 || code >= 300) throw new RuntimeException("MyMemory HTTP " + code + ": " + body);
    JSONObject root = new JSONObject(body);
    if (root.optInt("responseStatus", 200) >= 400) throw new RuntimeException("MyMemory response " + root.optString("responseDetails", "failed"));
    JSONObject data = root.optJSONObject("responseData");
    String translated = data == null ? "" : data.optString("translatedText", "");
    return translated == null ? "" : translated.trim();
}

private String translateTextByEdge(String q) throws Exception {
    String endpoint = "https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&from=en&to=zh-Hans";
    HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
    c.setRequestMethod("POST");
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(12000);
    c.setReadTimeout(18000);
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    c.setRequestProperty("Accept", "application/json");
    c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36 Edg/120");
    c.setRequestProperty("Origin", "https://www.bing.com");
    c.setRequestProperty("Referer", "https://www.bing.com/translator");
    JSONArray req = new JSONArray();
    JSONObject obj = new JSONObject();
    obj.put("Text", q);
    req.put(obj);
    byte[] data = req.toString().getBytes("UTF-8");
    c.setFixedLengthStreamingMode(data.length);
    try (OutputStream os = c.getOutputStream()) { os.write(data); }
    int code = c.getResponseCode();
    String body = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
    if (code < 200 || code >= 300) throw new RuntimeException("Edge Translate HTTP " + code + ": " + body);
    JSONArray root = new JSONArray(body);
    if (root.length() == 0) return "";
    JSONArray translations = root.optJSONObject(0) == null ? null : root.optJSONObject(0).optJSONArray("translations");
    if (translations == null || translations.length() == 0) return "";
    JSONObject first = translations.optJSONObject(0);
    return first == null ? "" : first.optString("text", "").trim();
}

private String translateTextByGoogleapis(String q) throws Exception {
    return translateWithGoogleEndpoint("https://translate.googleapis.com/translate_a/single", q);
}

private String translateWithGoogleEndpoint(String endpoint, String q) throws Exception {
    String url = endpoint + "?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" + URLEncoder.encode(q, "UTF-8");
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(12000);
    c.setReadTimeout(18000);
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
    c.setRequestProperty("Accept", "application/json,text/plain,*/*");
    c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    int code = c.getResponseCode();
    String body = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
    if (code < 200 || code >= 300) throw new RuntimeException("Translate HTTP " + code + " " + endpoint);
    JSONArray root = new JSONArray(body);
    JSONArray sentences = root.optJSONArray(0);
    StringBuilder sb = new StringBuilder();
    if (sentences != null) {
        for (int i = 0; i < sentences.length(); i++) {
            JSONArray part = sentences.optJSONArray(i);
            if (part != null) sb.append(part.optString(0, ""));
        }
    }
    return sb.toString().trim();
}

private String extractImageUrlFromHtml(String html, String baseUrl) {
    if (html == null || html.isEmpty()) return null;
    java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://[^\\\"'<> ]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\\"'<> ]*)?", java.util.regex.Pattern.CASE_INSENSITIVE);
    java.util.regex.Matcher m = p.matcher(html);
    if (m.find()) return m.group();
    p = java.util.regex.Pattern.compile("(?:src|href)=['\\\"]([^'\\\"]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^'\\\"]*)?)['\\\"]", java.util.regex.Pattern.CASE_INSENSITIVE);
    m = p.matcher(html);
    if (m.find()) {
        String url = m.group(1);
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return "https://vndb.org" + url;
        if (url.startsWith("http")) return url;
        try { return new java.net.URL(new java.net.URL(baseUrl), url).toString(); } catch (Throwable ignored) { }
    }
    return null;
}

private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
}

private String safeCacheName(String input) {
    if (input == null) return "cache";
    return input.replaceAll("[^a-zA-Z0-9._-]", "_");
}

private void setSideDescription(String text) {
    sideFullDescription = emptyText(text, "暂无简介。");
    sideDescExpanded = false;
    renderSideDescription();
}

private void renderSideDescription() {
    if (sideDetailHint == null || sideDescToggle == null) return;
    sideDetailHint.setText(sideFullDescription == null ? "" : sideFullDescription);
    boolean longEnough = sideFullDescription != null && (sideFullDescription.length() > 110 || sideFullDescription.contains("\n\n") || sideFullDescription.split("\n").length > 5);
    sideDetailHint.setMaxLines(sideDescExpanded ? Integer.MAX_VALUE : 5);
    sideDetailHint.setEllipsize(sideDescExpanded ? null : android.text.TextUtils.TruncateAt.END);
    sideDescToggle.setVisibility(longEnough ? View.VISIBLE : View.GONE);
    sideDescToggle.setText(sideDescExpanded ? "收起" : "展开");
}

private void renderTagChips(String tagsText) {
    if (sideTagContainer == null || sideDetailTags == null) return;
    sideTagContainer.removeAllViews();
    String source = tagsText == null ? "" : tagsText.trim();
    if (source.isEmpty() || "-".equals(source)) {
        sideTagContainer.addView(sideDetailTags);
        sideDetailTags.setText("-");
        sideDetailTags.setVisibility(View.VISIBLE);
        return;
    }
    sideDetailTags.setVisibility(View.GONE);
    String[] tags = source.split("\\s{2,}|[,，/]");
    LinearLayout row = null;
    int countInRow = 0;
    for (String raw : tags) {
        String tag = raw == null ? "" : raw.trim();
        if (tag.isEmpty()) continue;
        if (row == null || countInRow >= 2) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            sideTagContainer.addView(row);
            countInRow = 0;
        }
        TextView chip = new TextView(this);
        chip.setText(tag);
        chip.setTextSize(7);
        chip.setTextColor(getResources().getColor(R.color.yh_primary));
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(20), 1);
        lp.setMargins(0, 0, dp(3), dp(3));
        row.addView(chip, lp);
        countInRow++;
    }
    if (sideTagContainer.getChildCount() == 0) {
        sideTagContainer.addView(sideDetailTags);
        sideDetailTags.setText("-");
        sideDetailTags.setVisibility(View.VISIBLE);
    }
}

private String buildMetadataSearchKeyword(String title) {
        if (title == null) return "";
        String cleaned = title.replaceAll("[【\\[][^】\\]]*[】\\]]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    private boolean isConfidentMatch(String localTitle, VnMetadata meta) {
        if (meta == null || localTitle == null) return false;
        String a = buildMetadataSearchKeyword(localTitle).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5ぁ-んァ-ン一-龯]", "");
        String b = (emptyText(meta.chineseTitle, "") + emptyText(meta.originalTitle, "") + emptyText(meta.romanTitle, "")).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5ぁ-んァ-ン一-龯]", "");
        return !a.isEmpty() && !b.isEmpty() && (b.contains(a) || a.contains(b));
    }

private void showVndbCandidateDialog(Game game, List<VnMetadata> list) {
    if (game == null || list == null || list.isEmpty()) return;
    androidx.recyclerview.widget.RecyclerView rv = new androidx.recyclerview.widget.RecyclerView(this);
    rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
    rv.setPadding(dp(6), dp(6), dp(6), dp(6));
    rv.setClipToPadding(false);
    final AlertDialog[] dialogRef = new AlertDialog[1];
    final List<VnMetadata> items = new ArrayList<>(list);
    items.add(null);
    rv.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
        @Override public int getItemViewType(int position) { return position; }
        @Override public int getItemCount() { return items.size(); }
        @Override public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            android.view.View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vndb_candidate, parent, false);
            return new androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {};
        }
        @Override public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
            android.view.View itemView = holder.itemView;
            VnMetadata m = items.get(position);
            if (m == null) {
                ((TextView) itemView.findViewById(R.id.tvCandidateTitle)).setText("不匹配 / 暂不使用 VNDB");
                ((TextView) itemView.findViewById(R.id.tvCandidateOriginal)).setText("保留当前本地资料");
                ((TextView) itemView.findViewById(R.id.tvCandidateInfo)).setText("关闭弹窗，不绑定 VNDB");
                ((ImageView) itemView.findViewById(R.id.ivCandidateCover)).setImageDrawable(null);
            } else {
                ((TextView) itemView.findViewById(R.id.tvCandidateTitle)).setText(emptyText(m.chineseTitle, emptyText(m.romanTitle, "未命名")));
                ((TextView) itemView.findViewById(R.id.tvCandidateOriginal)).setText(emptyText(m.originalTitle, m.id));
                ((TextView) itemView.findViewById(R.id.tvCandidateInfo)).setText(emptyText(m.developer, "VNDB 候选"));
                ImageView cover = itemView.findViewById(R.id.ivCandidateCover);
                cover.setImageDrawable(null);
                if (m.coverUrl != null && !m.coverUrl.isEmpty()) loadRemoteImage(m.coverUrl, cover, "cand_" + m.id);
            }
            itemView.setOnClickListener(v -> {
                if (selectedGame == null || selectedGame.id != game.id) return;
                if (position >= 0 && position < list.size()) {
                    VnMetadata chosen = list.get(position);
if (metadataRepository != null) { if (usingBangumi()) metadataRepository.saveBangumi(game.id, chosen); else metadataRepository.saveVndb(game.id, chosen); }
applyVndbMetadata(chosen, game);
                } else {
                    sideDetailOriginalTitle.setText("未绑定 VNDB");
                    setSideDescription(emptyText(game.description, "已跳过 VNDB 匹配。"));
                }
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
        }
    });
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("选择 VNDB 匹配结果")
            .setView(rv)
            .setNegativeButton("取消", null)
            .show();
    dialogRef[0] = dialog;
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.70f), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
    }
}

private void applyVndbMetadata(VnMetadata meta, Game game) {
    currentSideMetadata = meta;
    long gameId = game == null ? -1 : game.id;
    sideShowingTranslatedDescription = gameId > 0 && isTranslatedStateFor(gameId) && meta != null && meta.translatedDescription != null && !meta.translatedDescription.trim().isEmpty();
    if (meta == null) {
        updateTranslateButtonState();
        setSideDescription(emptyText(game.description, "VNDB 暂未匹配到资料。"));
        return;
    }
    sideDetailTitle.setText(emptyText(meta.chineseTitle, emptyText(game.title, "未命名游戏")));
    sideDetailOriginalTitle.setText(emptyText(meta.originalTitle, meta.romanTitle));
    updateTranslateButtonState();
    setSideDescription(sideShowingTranslatedDescription ? meta.translatedDescription : emptyText(meta.description, "暂无 VNDB 简介。"));
    sideDetailDate.setText("发布日期：" + emptyText(meta.released, "-"));
    sideDetailDeveloper.setText("开发商：" + emptyText(meta.developer, "-"));
    if (sideDetailPath != null) sideDetailPath.setText("路径：" + displayPath(game.rootUri));
    sideDetailRating.setText(emptyText(meta.ratingText, "评分：-/10"));
if (sideDetailLength != null) sideDetailLength.setText(emptyText(meta.lengthText, "游玩时长：-"));
renderTagChips(emptyText(meta.tagsText, "-"));
    if (meta.coverUrl != null && !meta.coverUrl.isEmpty()) {
sideDetailCover.setVisibility(View.VISIBLE);
sideDetailPlaceholder.setVisibility(View.GONE);
sideDetailCover.setTag(game);
loadRemoteImage(meta.coverUrl, sideDetailCover, "cover_" + emptyText(meta.id, String.valueOf(game.id)));
}
    if (meta.screenshotUrls.size() > 0) loadRemoteImage(meta.screenshotUrls.get(0), sideScreenshot1, "shot1_" + emptyText(meta.id, String.valueOf(game.id)));
    if (meta.screenshotUrls.size() > 1) loadRemoteImage(meta.screenshotUrls.get(1), sideScreenshot2, "shot2_" + emptyText(meta.id, String.valueOf(game.id)));
}

private void updateSideDetail(Game game) {
        selectedGame = game;
        currentSideMetadata = null;
        sideShowingTranslatedDescription = game != null && isTranslatedStateFor(game.id);
        updateTranslateButtonState();
        if (adapter != null) adapter.setSelectedGameId(game == null ? -1 : game.id);
        if (sideDetailTitle == null) return;
        boolean hasGame = game != null;
        sideBtnLaunch.setEnabled(hasGame);
        sideBtnOptions.setEnabled(hasGame);
        sideBtnLaunch.setAlpha(hasGame ? 1f : 0.45f);
        sideBtnOptions.setAlpha(hasGame ? 1f : 0.45f);
        if (!hasGame) {
            sideDetailTitle.setText("请选择游戏");
            sideDetailOriginalTitle.setText("");
            setSideDescription("点击中间的游戏卡片后，这里会显示封面、启动入口和选项。后续可接 VNDB/APJ 简介与元数据。");
            sideDetailDate.setText("发布日期：-");
sideDetailDeveloper.setText("开发商：-");
if (sideDetailPath != null) sideDetailPath.setText("路径：-");
            sideDetailRating.setText("评分：-/10");
            if (sideDetailLength != null) sideDetailLength.setText("游玩时长：-");
            renderTagChips("-");
            sideDetailCover.setImageDrawable(null);
            sideDetailCover.setVisibility(View.GONE);
            sideDetailPlaceholder.setVisibility(View.VISIBLE);
            sideDetailPlaceholder.setText("选择游戏");
            if (sideScreenshot1 != null) sideScreenshot1.setImageDrawable(null);
            if (sideScreenshot2 != null) sideScreenshot2.setImageDrawable(null);
            return;
        }
        sideDetailTitle.setText(emptyText(game.title, "未命名游戏"));
        sideDetailOriginalTitle.setText("VNDB 匹配中…");
        setSideDescription(emptyText(game.description, "正在从 VNDB 获取简介…"));
        sideDetailDate.setText("发布日期：-");
sideDetailDeveloper.setText("开发商：-");
if (sideDetailPath != null) sideDetailPath.setText("路径：" + displayPath(game.rootUri));
        sideDetailRating.setText("评分：-/10");
        if (sideDetailLength != null) sideDetailLength.setText("游玩时长：-");
        renderTagChips("-");
        if (sideScreenshot1 != null) sideScreenshot1.setImageDrawable(null);
        if (sideScreenshot2 != null) sideScreenshot2.setImageDrawable(null);
        String coverUri = safeCoverUri(game);
        if (coverUri != null && !coverUri.isEmpty()) {
            try {
                sideDetailCover.setImageURI(Uri.parse(coverUri));
                sideDetailCover.setVisibility(View.VISIBLE);
                sideDetailPlaceholder.setVisibility(View.GONE);
            } catch (Throwable t) {
                sideDetailCover.setImageDrawable(null);
                sideDetailCover.setVisibility(View.GONE);
                sideDetailPlaceholder.setVisibility(View.VISIBLE);
                sideDetailPlaceholder.setText(initials(game.title));
            }
        } else {
            sideDetailCover.setImageDrawable(null);
            sideDetailCover.setVisibility(View.GONE);
            sideDetailPlaceholder.setVisibility(View.VISIBLE);
            sideDetailPlaceholder.setText(initials(game.title));
        }
        fetchSelectedMetadata(game);
    }

    private void showCustomVndbSearchDialog(Game game) {
    if (game == null) return;
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(emptyText(game.title, ""));
    input.setSelectAllOnFocus(true);
    input.setHint("输入 VNDB 搜索关键词或原名");
    input.setTextColor(getResources().getColor(R.color.yh_text));
    input.setHintTextColor(getResources().getColor(R.color.yh_text_muted));
    input.setBackgroundResource(R.drawable.bg_input);
    input.setPadding(dp(12), 0, dp(12), 0);
    new AlertDialog.Builder(this)
            .setTitle("自定义搜索 VNDB")
            .setView(input)
            .setPositiveButton("搜索", (d, w) -> {
                String keyword = input.getText() == null ? "" : input.getText().toString().trim();
                if (keyword.isEmpty()) { Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show(); return; }
                searchVndbWithKeyword(game, keyword);
            })
            .setNegativeButton("取消", null)
            .show();
}

private void showCustomBangumiSearchDialog(Game game) {
    if (game == null) return;
    if (bangumiToken() == null || bangumiToken().trim().isEmpty()) {
        Toast.makeText(this, "请先在设置里填写 Bangumi Token", Toast.LENGTH_SHORT).show();
        return;
    }
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(emptyText(game.title, ""));
    input.setSelectAllOnFocus(true);
    input.setHint("输入 Bangumi 搜索关键词");
    input.setTextColor(getResources().getColor(R.color.yh_text));
    input.setHintTextColor(getResources().getColor(R.color.yh_text_muted));
    input.setBackgroundResource(R.drawable.bg_input);
    input.setPadding(dp(12), 0, dp(12), 0);
    new AlertDialog.Builder(this)
            .setTitle("自定义搜索 Bangumi")
            .setView(input)
            .setPositiveButton("搜索", (d, w) -> {
                String keyword = input.getText() == null ? "" : input.getText().toString().trim();
                if (keyword.isEmpty()) { Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show(); return; }
                searchBangumiWithKeyword(game, keyword);
            })
            .setNegativeButton("取消", null)
            .show();
}

private void searchBangumiWithKeyword(Game game, String keyword) {
    if (game == null || keyword == null || keyword.trim().isEmpty()) return;
    String token = bangumiToken();
    setSideDescription("正在按自定义关键词搜索 Bangumi…");
    AppExecutors.runOnIo(() -> {
        try {
            List<VnMetadata> data = BangumiClient.searchCandidates(keyword, token, 8, usingBangumiMirror());
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != game.id) return;
                if (data == null || data.isEmpty()) {
                    Toast.makeText(MainActivity.this, "没有匹配到 Bangumi 结果", Toast.LENGTH_SHORT).show();
                    setSideDescription(emptyText(game.description, "Bangumi 暂未匹配到资料。"));
                } else {
                    showVndbCandidateDialog(game, data);
                }
            });
        } catch (Throwable t) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Bangumi 搜索失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
        }
    });
}

private void searchVndbWithKeyword(Game game, String keyword) {
    if (game == null || keyword == null || keyword.trim().isEmpty()) return;
    setSideDescription("正在按自定义关键词搜索 VNDB…");
    VndbClient.searchCandidatesAsync(keyword, 8, new VndbClient.CandidatesCallback() {
        @Override public void onSuccess(List<VnMetadata> data) {
            runOnUiThread(() -> {
                if (selectedGame == null || selectedGame.id != game.id) return;
                if (data == null || data.isEmpty()) {
                    Toast.makeText(MainActivity.this, "没有匹配到 VNDB 结果", Toast.LENGTH_SHORT).show();
                    setSideDescription(emptyText(game.description, "VNDB 暂未匹配到资料。"));
                } else {
                    showVndbCandidateDialog(game, data);
                }
            });
        }
        @Override public void onError(Exception error) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "VNDB 搜索失败", Toast.LENGTH_SHORT).show());
        }
    });
}

private void syncVndbToGameCard(Game game) {
    if (game == null) return;
    VnMetadata meta = metadataRepository == null ? null : metadataRepository.getVndb(game.id);
    if (meta == null) {
        Toast.makeText(this, "请先匹配 VNDB 资料", Toast.LENGTH_SHORT).show();
        return;
    }
    Toast.makeText(this, "正在同步 VNDB 到游戏卡片…", Toast.LENGTH_SHORT).show();
    AppExecutors.runOnIo(() -> {
        String localCover = null;
        if (meta.coverUrl != null && !meta.coverUrl.isEmpty()) {
            localCover = cacheRemoteImageSync(meta.coverUrl, "card_cover_" + emptyText(meta.id, String.valueOf(game.id)));
        }
        final String cover = localCover;
        runOnUiThread(() -> {
            String newTitle = emptyText(meta.chineseTitle, emptyText(meta.originalTitle, meta.romanTitle));
            if (!newTitle.isEmpty()) game.title = newTitle;
            if (meta.originalTitle != null && !meta.originalTitle.isEmpty()) game.originalTitle = meta.originalTitle;
            if (meta.description != null && !meta.description.isEmpty()) game.description = meta.description;
            if (meta.tagsText != null && !meta.tagsText.isEmpty()) game.tags = meta.tagsText;
            if (cover != null && !cover.isEmpty()) {
                game.coverUri = cover;
                game.coverPersistUri = cover;
                game.coverSourceType = 1;
            }
            repository.update(game);
            loadGames();
            updateSideDetail(game);
            Toast.makeText(this, "已同步 VNDB 中文名和封面到游戏卡片", Toast.LENGTH_SHORT).show();
        });
    });
}

private String cacheRemoteImageSync(String url, String prefix) {
if (url == null || url.trim().isEmpty()) return null;
try {
File cacheDir = persistentRemoteCoverDir();
if (!cacheDir.exists()) cacheDir.mkdirs();
File cacheFile = new File(cacheDir, safeCacheName(prefix + "_" + url.trim()));
if (!cacheFile.exists() || cacheFile.length() == 0 || BitmapFactory.decodeFile(cacheFile.getAbsolutePath()) == null) {
if (cacheFile.exists()) cacheFile.delete();
boolean ok = downloadImageAllowVndbWarningPage(url.trim(), cacheFile, 0);
if (!ok || BitmapFactory.decodeFile(cacheFile.getAbsolutePath()) == null) return null;
}
return Uri.fromFile(cacheFile).toString();
} catch (Throwable t) {
return null;
}
}

private void styleAlertDialogDark(AlertDialog dialog) {
    if (dialog == null) return;
    try {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        int text = getColorCompat(R.color.yh_text);
        int muted = getColorCompat(R.color.yh_text_muted);
        int primary = getColorCompat(R.color.yh_primary);
        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = dialog.findViewById(titleId);
        if (title != null) title.setTextColor(text);
        TextView msg = dialog.findViewById(android.R.id.message);
        if (msg != null) msg.setTextColor(muted);
        Button p = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button n = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (p != null) p.setTextColor(primary);
        if (n != null) n.setTextColor(primary);
        if (neu != null) neu.setTextColor(primary);
        android.widget.ListView list = dialog.getListView();
        if (list != null) {
            list.setBackgroundColor(Color.TRANSPARENT);
            list.setCacheColorHint(Color.TRANSPARENT);
        }
    } catch (Throwable ignored) { }
}

private void showSideOptions(Game game) {
        if (game == null) return;
        String sourceLabel = metadataSourceLabel();
String rematchItem = "重新匹配" + sourceLabel;
        String customSearchItem = usingBangumi() ? "自定义搜索Bangumi" : "自定义搜索VNDB";
        String syncItem = "同步" + sourceLabel + "到卡片";
        String playTimeItem = "修改游玩时长";
        String favoriteItem = game.favorite ? "取消收藏" : "收藏游戏";
        String[] items = (game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ONS)
                ? new String[]{"编辑游戏", "设置游玩状态", playTimeItem, favoriteItem, rematchItem, customSearchItem, syncItem, "引擎设置", "详细信息", "删除游戏"}
                : new String[]{"编辑游戏", "设置游玩状态", playTimeItem, favoriteItem, rematchItem, customSearchItem, syncItem, "详细信息", "删除游戏"};
        LinearLayout listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setBackgroundResource(R.drawable.bg_dialog);
        int hp = dp(18);
        listRoot.setPadding(0, dp(6), 0, dp(6));
        final AlertDialog[] ref = new AlertDialog[1];
        for (String item : items) {
            TextView row = new TextView(this);
            row.setText(item);
            row.setTextColor(getColorCompat("删除游戏".equals(item) ? R.color.yh_secondary : R.color.yh_text));
            row.setTextSize(15);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(hp, 0, hp, 0);
            row.setBackgroundResource(R.drawable.bg_input);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
            rlp.setMargins(dp(10), dp(4), dp(10), dp(4));
            listRoot.addView(row, rlp);
            row.setOnClickListener(v -> {
                if (ref[0] != null) ref[0].dismiss();
                String chosen = ((TextView) v).getText().toString();
                if ("编辑游戏".equals(chosen)) showEditDialog(game);
                else if ("设置游玩状态".equals(chosen)) showPlayStatusDialog(game, null);
                else if (playTimeItem.equals(chosen)) showEditPlayTimeDialog(game);
                else if (favoriteItem.equals(chosen)) {
                    game.favorite = !game.favorite;
                    repository.update(game);
                    loadGames();
                    Toast.makeText(this, game.favorite ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                }
                else if (rematchItem.equals(chosen)) {
                    if (metadataRepository != null) {
                        if (usingBangumi()) metadataRepository.clearBangumi(game.id);
                        else metadataRepository.clearVndb(game.id);
                    }
                    fetchSelectedMetadata(game, true);
                }
                else if (customSearchItem.equals(chosen)) { if (usingBangumi()) showCustomBangumiSearchDialog(game); else showCustomVndbSearchDialog(game); }
                else if (syncItem.equals(chosen)) syncVndbToGameCard(game);
                else if ("引擎设置".equals(chosen)) { if (game.engine == EngineType.ONS) showOnsSettingsDialog(game); else showKrSettingsDialog(game); }
                else if ("详细信息".equals(chosen)) showDetailDialog(game);
                else if ("删除游戏".equals(chosen)) confirmDeleteGame(game);
            });
        }
        ScrollView optionScroll = new ScrollView(this);
        optionScroll.setFillViewport(false);
        optionScroll.setBackgroundResource(R.drawable.bg_dialog);
        optionScroll.addView(listRoot, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        AlertDialog optionDialog = new AlertDialog.Builder(this)
                .setTitle(emptyText(game.title, "游戏选项"))
                .setView(optionScroll)
                .show();
        ref[0] = optionDialog;
        styleAlertDialogDark(optionDialog);
        if (optionDialog.getWindow() != null) {
            optionDialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.48f), (int) (getResources().getDisplayMetrics().heightPixels * 0.78f));
        }
    }

    private void confirmDeleteGame(Game game) {
        if (game == null) return;
        new AlertDialog.Builder(this)
                .setTitle("删除游戏")
                .setMessage("确定删除 “" + game.title + "”？不会删除本体文件。")
                .setPositiveButton("删除", (x,w)->{ repository.delete(game.id); selectedGame = null; loadGames(); })
                .setNegativeButton("取消", null)
                .show();
    }

    private String initials(String title) {
        if (title == null || title.trim().isEmpty()) return "YH";
        return title.trim().substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private String safeCoverUri(Game g) {
        if (g == null) return null;
        if (g.coverPersistUri != null && !g.coverPersistUri.isEmpty()) return g.coverPersistUri;
        if (g.coverUri != null && !g.coverUri.isEmpty()) return g.coverUri;
        return null;
    }

    private void showSettingsDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, dp(8));

        TextView scanTitle = new TextView(this);
        scanTitle.setText("扫描目录");
        scanTitle.setTextColor(getColorCompat(R.color.yh_text));
        scanTitle.setTextSize(14);
        scanTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(scanTitle);

        TextView scanInfo = new TextView(this);
        scanInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        scanInfo.setTextSize(11);
        scanInfo.setPadding(0, dp(4), 0, dp(8));
        root.addView(scanInfo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout scanRootList = new LinearLayout(this);
        scanRootList.setOrientation(LinearLayout.VERTICAL);
        root.addView(scanRootList);
        activeScanRootList = scanRootList;
        activeScanRootInfo = scanInfo;
        refreshScanRootListUi(scanRootList, scanInfo);

        Button addScanRootButton = krButton("+ 添加扫描目录");
        addScanRootButton.setTextColor(getColorCompat(R.color.yh_primary));
        addScanRootButton.setOnClickListener(v -> {
            if (getScanRootUris().size() >= MAX_SCAN_ROOTS) {
                Toast.makeText(this, "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
                return;
            }
            launchScanRootPicker(-1);
        });
        LinearLayout.LayoutParams addScanRootLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        addScanRootLp.setMargins(0, dp(4), 0, dp(8));
        root.addView(addScanRootButton, addScanRootLp);

        TextView scanDepthTitle = new TextView(this);
        scanDepthTitle.setText("\n启动时扫描最大深度");
        scanDepthTitle.setTextColor(getColorCompat(R.color.yh_text));
        scanDepthTitle.setTextSize(14);
        scanDepthTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(scanDepthTitle);

        TextView scanDepthInfo = new TextView(this);
        int savedDepth = prefs == null ? DEFAULT_STARTUP_SCAN_DEPTH : prefs.getInt(KEY_STARTUP_SCAN_DEPTH, DEFAULT_STARTUP_SCAN_DEPTH);
        savedDepth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, savedDepth));
        scanDepthInfo.setText("当前：" + savedDepth + " 层（最深 4 层）");
        scanDepthInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        scanDepthInfo.setTextSize(11);
        scanDepthInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(scanDepthInfo);

        LinearLayout depthRow = new LinearLayout(this);
        depthRow.setOrientation(LinearLayout.HORIZONTAL);
        depthRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        SeekBar scanDepthSeek = new SeekBar(this);
        scanDepthSeek.setMax(MAX_STARTUP_SCAN_DEPTH - 1);
        scanDepthSeek.setProgress(savedDepth - 1);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        depthRow.addView(scanDepthSeek, seekLp);
        TextView depthValue = new TextView(this);
        depthValue.setText(String.valueOf(savedDepth));
        depthValue.setTextColor(getColorCompat(R.color.yh_text));
        depthValue.setTextSize(15);
        depthValue.setTypeface(null, android.graphics.Typeface.BOLD);
        depthValue.setPadding(dp(10), 0, 0, 0);
        depthRow.addView(depthValue);
        root.addView(depthRow);
        scanDepthSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int depth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, progress + 1));
                depthValue.setText(String.valueOf(depth));
                scanDepthInfo.setText("当前：" + depth + " 层（最深 4 层）");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        scanDepthSeek.setProgress(savedDepth - 1);

        TextView fontTitle = new TextView(this);
        fontTitle.setText("\n整体字体大小");
        fontTitle.setTextColor(getColorCompat(R.color.yh_text));
        fontTitle.setTextSize(14);
        fontTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(fontTitle);

        float savedFontScale = prefs == null ? UiScaleUtil.DEFAULT_FONT_SCALE : prefs.getFloat(UiScaleUtil.KEY_UI_FONT_SCALE, UiScaleUtil.DEFAULT_FONT_SCALE);
        TextView fontInfo = new TextView(this);
        fontInfo.setText("当前：" + UiScaleUtil.percent(savedFontScale) + "%（默认 100%）");
        fontInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        fontInfo.setTextSize(11);
        fontInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(fontInfo);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        SeekBar fontSeek = new SeekBar(this);
        fontSeek.setMax((int) ((UiScaleUtil.MAX_FONT_SCALE - UiScaleUtil.MIN_FONT_SCALE) * 100f));
        fontSeek.setProgress(Math.round((savedFontScale - UiScaleUtil.MIN_FONT_SCALE) * 100f));
        LinearLayout.LayoutParams fontSeekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        fontRow.addView(fontSeek, fontSeekLp);
        TextView fontValue = new TextView(this);
        fontValue.setText(UiScaleUtil.percent(savedFontScale) + "%");
        fontValue.setTextColor(getColorCompat(R.color.yh_text));
        fontValue.setTextSize(15);
        fontValue.setTypeface(null, android.graphics.Typeface.BOLD);
        fontValue.setPadding(dp(10), 0, 0, 0);
        fontRow.addView(fontValue);
        root.addView(fontRow);
        fontSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = UiScaleUtil.clamp(UiScaleUtil.MIN_FONT_SCALE + progress / 100f);
                fontValue.setText(UiScaleUtil.percent(scale) + "%");
                fontInfo.setText("当前：" + UiScaleUtil.percent(scale) + "%（默认 100%）");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        Button fontReset = krButton("恢复默认字体");
        fontReset.setTextColor(getColorCompat(R.color.yh_text));
        fontReset.setOnClickListener(v -> {
            fontSeek.setProgress(Math.round((UiScaleUtil.DEFAULT_FONT_SCALE - UiScaleUtil.MIN_FONT_SCALE) * 100f));
            fontValue.setText("100%");
            fontInfo.setText("当前：100%（默认 100%）");
        });
        LinearLayout.LayoutParams fontResetLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        fontResetLp.topMargin = dp(6);
        root.addView(fontReset, fontResetLp);

        CheckBox autoScanCheck = krCheckBox("进入应用时自动扫描上次目录", prefs == null || prefs.getBoolean(KEY_AUTO_SCAN_ON_STARTUP, true));
        root.addView(autoScanCheck);

        TextView sortTitle = new TextView(this);
        sortTitle.setText("\n游戏库排序");
        sortTitle.setTextColor(getColorCompat(R.color.yh_text));
        sortTitle.setTextSize(14);
        sortTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(sortTitle);

        TextView sortInfo = new TextView(this);
        sortInfo.setText("默认按最近游玩排序，收藏会始终置顶。");
        sortInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        sortInfo.setTextSize(11);
        sortInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(sortInfo);

        Spinner sortSpinner = new Spinner(this);
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, new String[]{"最近游玩", "最近添加", "名称排序"});
        sortAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        sortSpinner.setAdapter(sortAdapter);
        String savedSortMode = prefs == null ? SORT_MODE_RECENT : prefs.getString(KEY_SORT_MODE, SORT_MODE_RECENT);
        if (SORT_MODE_NEWEST.equals(savedSortMode)) sortSpinner.setSelection(1);
        else if (SORT_MODE_NAME.equals(savedSortMode)) sortSpinner.setSelection(2);
        else sortSpinner.setSelection(0);
        root.addView(sortSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView personalizationTitle = new TextView(this);
        personalizationTitle.setText("\n个性化功能");
        personalizationTitle.setTextColor(getColorCompat(R.color.yh_text));
        personalizationTitle.setTextSize(14);
        personalizationTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(personalizationTitle);

        TextView engineLabelTitle = new TextView(this);
        engineLabelTitle.setText("游戏引擎标签位置");
        engineLabelTitle.setTextColor(getColorCompat(R.color.yh_text_muted));
        engineLabelTitle.setTextSize(11);
        engineLabelTitle.setPadding(0, dp(4), 0, dp(6));
        root.addView(engineLabelTitle);

        Spinner engineLabelSpinner = new Spinner(this);
        ArrayAdapter<String> engineLabelAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, new String[]{"游戏标题下方", "封面左下角"});
        engineLabelAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        engineLabelSpinner.setAdapter(engineLabelAdapter);
        String engineLabelPos = prefs == null ? "title" : prefs.getString(KEY_ENGINE_LABEL_POSITION, "title");
        engineLabelSpinner.setSelection("cover".equals(engineLabelPos) ? 1 : 0);
        root.addView(engineLabelSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView accountTitle = new TextView(this);
accountTitle.setText("\n账户与同步");
accountTitle.setTextColor(getColorCompat(R.color.yh_text));
accountTitle.setTextSize(14);
accountTitle.setTypeface(null, android.graphics.Typeface.BOLD);
root.addView(accountTitle);
TextView accountInfo = new TextView(this);
accountInfo.setText(isLoggedIn()
        ? ("当前：" + displayProfileName() + " · " + accountStatusLabelForDialog())
        : "当前：本地账户，未登录云账户");
accountInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
accountInfo.setTextSize(11);
accountInfo.setPadding(0, dp(4), 0, dp(6));
root.addView(accountInfo);
LinearLayout accountActions = new LinearLayout(this);
        accountActions.setOrientation(LinearLayout.HORIZONTAL);
        Button accountButton = krButton(isLoggedIn() ? "账号设置" : "登录/注册");
        Button webdavButton = krButton("同步中心");
        accountButton.setTextColor(getColorCompat(R.color.yh_primary));
        webdavButton.setTextColor(getColorCompat(R.color.yh_primary));
        accountButton.setOnClickListener(v -> showAuthDialog());
        webdavButton.setOnClickListener(v -> showWebDavSettingsDialog());
        accountActions.addView(accountButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams webdavLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        webdavLp.setMargins(dp(8), 0, 0, 0);
        accountActions.addView(webdavButton, webdavLp);
        root.addView(accountActions);

        TextView disclaimerTitle = new TextView(this);
        disclaimerTitle.setText("\n使用说明与免责声明");
        disclaimerTitle.setTextColor(getColorCompat(R.color.yh_text));
        disclaimerTitle.setTextSize(14);
        disclaimerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(disclaimerTitle);
        TextView disclaimerInfo = new TextView(this);
        disclaimerInfo.setText("本应用为开源项目，旨在帮助用户管理与启动自己拥有权限的游戏/应用资源。" +
                "使用者需自行确认所添加内容、账号、同步服务及第三方组件的合法性与可用性。\n\n" +
                "程序不提供任何游戏资源、破解资源或绕过授权的能力；Shizuku、GameHub、WebDAV、VNDB、Bangumi 等第三方服务/应用均由其各自规则与可用性决定。\n\n" +
                "若你不同意上述内容，请不要继续使用相关功能。" );
        disclaimerInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        disclaimerInfo.setTextSize(11);
        disclaimerInfo.setLineSpacing(dp(2), 1.0f);
        disclaimerInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(disclaimerInfo);
        Button disclaimerButton = krButton("查看完整免责声明");
        disclaimerButton.setTextColor(getColorCompat(R.color.yh_primary));
        disclaimerButton.setOnClickListener(v -> showDisclaimerDialog());
        root.addView(disclaimerButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        TextView aboutTitle = new TextView(this);
        aboutTitle.setText("\n关于我们");
        aboutTitle.setTextColor(getColorCompat(R.color.yh_text));
        aboutTitle.setTextSize(14);
        aboutTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(aboutTitle);

        TextView aboutInfo = new TextView(this);
        aboutInfo.setText("这里可以找到项目主页、官网和交流群，方便查看更新、反馈问题和获取帮助。\n");
        aboutInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        aboutInfo.setTextSize(11);
        aboutInfo.setLineSpacing(dp(2), 1.0f);
        aboutInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(aboutInfo);

        Button updateButton = krButton("检查更新");
        updateButton.setTextColor(getColorCompat(R.color.yh_primary));
        updateButton.setOnClickListener(v -> checkUpdateManually());
        CheckBox updateOnStartupCheck = krCheckBox("启动时自动检查更新", prefs == null || prefs.getBoolean(KEY_CHECK_UPDATE_ON_STARTUP, true));
        root.addView(updateButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        root.addView(updateOnStartupCheck);

        LinearLayout githubButton = linkCardButton("GitHub 仓库", R.drawable.ic_github);
        LinearLayout websiteButton = linkCardButton("官方网站", android.R.drawable.ic_menu_view);
        LinearLayout groupButton = linkCardButton("QQ 交流群", android.R.drawable.ic_dialog_email);
        githubButton.setOnClickListener(v -> openExternalUrl("https://github.com/xm486/YukiHub"));
        websiteButton.setOnClickListener(v -> openExternalUrl("https://yukihub.kesug.com/"));
        groupButton.setOnClickListener(v -> openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info"));
        root.addView(githubButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout.LayoutParams websiteLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        websiteLp.topMargin = dp(8);
        root.addView(websiteButton, websiteLp);
        LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        groupLp.topMargin = dp(8);
        root.addView(groupButton, groupLp);

        TextView sourceTitle = new TextView(this);
        sourceTitle.setText("\n右侧资料源");
        sourceTitle.setTextColor(getColorCompat(R.color.yh_text));
        sourceTitle.setTextSize(14);
        sourceTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(sourceTitle);

        Spinner sourceSpinner = new Spinner(this);
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, new String[]{"VNDB（默认）", "Bangumi（需要 Token）", "Bangumi 镜像（需要 Token）"});
        sourceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        sourceSpinner.setAdapter(sourceAdapter);
        String currentSource = metadataSource();
        if (SOURCE_BANGUMI.equals(currentSource)) sourceSpinner.setSelection(1);
        else if (SOURCE_BANGUMI_MIRROR.equals(currentSource)) sourceSpinner.setSelection(2);
        else sourceSpinner.setSelection(0);
        root.addView(sourceSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView tokenLabel = new TextView(this);
        tokenLabel.setText("Bangumi Access Token");
        tokenLabel.setTextColor(getColorCompat(R.color.yh_text));
        tokenLabel.setTextSize(13);
        tokenLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(tokenLabel);

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setText(bangumiToken());
        tokenInput.setHint("选择 Bangumi 时必填");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        tokenInput.setTextColor(getColorCompat(R.color.yh_text));
        tokenInput.setHintTextColor(getColorCompat(R.color.yh_text_muted));
        tokenInput.setBackgroundResource(R.drawable.bg_input);
        tokenInput.setPadding(dp(10), 0, dp(10), 0);
        root.addView(tokenInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView warn = new TextView(this);
        warn.setText("提醒：Bangumi API Token 建议使用注册超过三个月的账号申请。切换资料源后，对当前游戏可点“重新匹配”刷新资料。");
        warn.setTextColor(getColorCompat(R.color.yh_warning));
        warn.setTextSize(11);
        warn.setPadding(0, dp(8), 0, 0);
        root.addView(warn);

        TextView tokenLink = new TextView(this);
        tokenLink.setText("没有token?");
        tokenLink.setTextColor(Color.rgb(138, 180, 255));
        tokenLink.setTextSize(12);
        tokenLink.setTypeface(null, android.graphics.Typeface.BOLD);
        tokenLink.setPadding(0, dp(8), 0, dp(4));
        tokenLink.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://next.bgm.tv/demo/access-token/create"))); }
            catch (Throwable t) { Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show(); }
        });
        root.addView(tokenLink);

        TextView bgTitle = new TextView(this);
        bgTitle.setText("\n界面背景");
        bgTitle.setTextColor(getColorCompat(R.color.yh_text));
        bgTitle.setTextSize(14);
        bgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(bgTitle);

        TextView bgInfo = new TextView(this);
        String customBg = prefs.getString(KEY_CUSTOM_BACKGROUND, "");
        String customBgType = prefs.getString(KEY_CUSTOM_BACKGROUND_TYPE, "image");
        bgInfo.setText(customBg == null || customBg.isEmpty() ? "当前：默认动态背景" : ("video".equals(customBgType) ? "当前：自定义视频背景" : "当前：自定义图片背景"));
        bgInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        bgInfo.setTextSize(11);
        bgInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(bgInfo);

        LinearLayout bgActions = new LinearLayout(this);
        bgActions.setOrientation(LinearLayout.HORIZONTAL);
        Button chooseBgButton = krButton("图片背景");
        Button chooseVideoBgButton = krButton("视频背景");
        Button resetBgButton = krButton("恢复默认");
        chooseBgButton.setTextColor(getColorCompat(R.color.yh_primary));
        chooseVideoBgButton.setTextColor(getColorCompat(R.color.yh_primary));
        resetBgButton.setTextColor(getColorCompat(R.color.yh_text));
        bgActions.addView(chooseBgButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams videoBgLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        videoBgLp.setMargins(dp(6), 0, 0, 0);
        bgActions.addView(chooseVideoBgButton, videoBgLp);
        LinearLayout.LayoutParams resetBgLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        resetBgLp.setMargins(dp(6), 0, 0, 0);
        bgActions.addView(resetBgButton, resetBgLp);
        root.addView(bgActions);

        CheckBox bgDimEnabled = krCheckBox("背景遮罩（提高文字可读性）", prefs.getBoolean(KEY_BACKGROUND_DIM_ENABLED, true));
        CheckBox bgVideoSound = krCheckBox("视频背景声音", prefs.getBoolean(KEY_BACKGROUND_VIDEO_SOUND, false));
        root.addView(bgDimEnabled);
        root.addView(bgVideoSound);

        TextView krTitle = new TextView(this);
        krTitle.setText("\nKRKR 引擎");
        krTitle.setTextColor(getColorCompat(R.color.yh_text));
        krTitle.setTextSize(14);
        krTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(krTitle);

        TextView krInfo = new TextView(this);
        krInfo.setText("华为等部分机型如因存储权限导致引擎崩溃或闪退，可开启对应引擎的独立存档目录。");
        krInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        krInfo.setTextSize(11);
        krInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(krInfo);

        root.addView(krLabel("KR 引擎版本"));
        Spinner krEngineVersion = krSpinner(new String[]{"自动", "1.3.9", "1.3.4"}, krEngineVersionToLabel(prefs.getString(KEY_KR_ENGINE_VERSION, "auto")));
        root.addView(krEngineVersion);

        CheckBox krCompatMode = krCheckBox("KR 启动参数兼容模式", prefs.getBoolean(KEY_KR_COMPAT_MODE, false));
        CheckBox krScopedSaveDir = krCheckBox("KR 独立存档目录（权限异常闪退时开启）", prefs.getBoolean(KEY_KR_SCOPED_SAVE_DIR, false));
        CheckBox artemisScopedSaveDir = krCheckBox("Artemis 独立存档目录（权限异常闪退时开启）", prefs.getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, false));
        root.addView(krCompatMode);
        root.addView(krScopedSaveDir);
        root.addView(artemisScopedSaveDir);

        Button nativeKrkrButton = krButton("进入原生KRKR");
        nativeKrkrButton.setTextColor(getColorCompat(R.color.yh_primary));
        nativeKrkrButton.setOnClickListener(v -> {
            try {
                startActivity(EmulatorLauncher.buildInternalKrkrIntent(this, "", "", true));
            } catch (Throwable t) {
                Toast.makeText(this, "无法进入原生KRKR", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams nativeKrkrLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        nativeKrkrLp.setMargins(0, dp(10), 0, dp(4));
        root.addView(nativeKrkrButton, nativeKrkrLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundResource(R.drawable.bg_dialog);
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNeutralButton("添加目录", null)
                .setNegativeButton("关闭", null)
                .show();
        styleAlertDialogDark(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.58f), (int) (getResources().getDisplayMetrics().heightPixels * 0.78f));
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int sourceSelection = sourceSpinner.getSelectedItemPosition();
            boolean bangumi = sourceSelection == 1;
            boolean bangumiMirror = sourceSelection == 2;
            String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
            if ((bangumi || bangumiMirror) && token.isEmpty()) {
                Toast.makeText(this, "选择 Bangumi 时需要填写 Token", Toast.LENGTH_SHORT).show();
                return;
            }
            int depth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, scanDepthSeek.getProgress() + 1));
            float fontScale = UiScaleUtil.clamp(UiScaleUtil.MIN_FONT_SCALE + fontSeek.getProgress() / 100f);
            String sortMode = SORT_MODE_RECENT;
            int sortSelection = sortSpinner.getSelectedItemPosition();
            if (sortSelection == 1) sortMode = SORT_MODE_NEWEST;
            else if (sortSelection == 2) sortMode = SORT_MODE_NAME;
            prefs.edit()
                    .putString(KEY_METADATA_SOURCE, bangumiMirror ? SOURCE_BANGUMI_MIRROR : (bangumi ? SOURCE_BANGUMI : SOURCE_VNDB))
                    .putString(KEY_BANGUMI_TOKEN, token)
                    .putInt(KEY_STARTUP_SCAN_DEPTH, depth)
                    .putBoolean(KEY_AUTO_SCAN_ON_STARTUP, autoScanCheck.isChecked())
                    .putBoolean(KEY_CHECK_UPDATE_ON_STARTUP, updateOnStartupCheck.isChecked())
                    .putString(KEY_ENGINE_LABEL_POSITION, engineLabelSpinner.getSelectedItemPosition() == 1 ? "cover" : "title")
                    .putString(KEY_SORT_MODE, sortMode)
                    .putBoolean(KEY_BACKGROUND_DIM_ENABLED, bgDimEnabled.isChecked())
                .putBoolean(KEY_BACKGROUND_VIDEO_SOUND, bgVideoSound.isChecked())
                .putString(KEY_KR_ENGINE_VERSION, krEngineVersionFromLabel(String.valueOf(krEngineVersion.getSelectedItem())))
.putBoolean(KEY_KR_COMPAT_MODE, krCompatMode.isChecked())
.putBoolean(KEY_KR_SCOPED_SAVE_DIR, krScopedSaveDir.isChecked())
.putBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, artemisScopedSaveDir.isChecked())
.putFloat(UiScaleUtil.KEY_UI_FONT_SCALE, fontScale)
                    .apply();
            applyCustomBackground();
            Toast.makeText(this, "已保存资料源：" + (bangumiMirror ? "Bangumi镜像" : (bangumi ? "Bangumi" : "VNDB")) + "，扫描深度：" + depth + " 层，字体：" + UiScaleUtil.percent(fontScale) + "%", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            recreate();
        });
        chooseBgButton.setOnClickListener(v -> {
            dialog.dismiss();
            backgroundPickerLauncher.launch("image/*");
        });
        chooseVideoBgButton.setOnClickListener(v -> {
            dialog.dismiss();
            videoBackgroundPickerLauncher.launch("video/*");
        });
        resetBgButton.setOnClickListener(v -> {
            String oldBg = prefs.getString(KEY_CUSTOM_BACKGROUND, "");
            prefs.edit().remove(KEY_CUSTOM_BACKGROUND).remove(KEY_CUSTOM_BACKGROUND_TYPE).apply();
            deleteInternalFileUri(oldBg);
            applyCustomBackground();
            bgInfo.setText("当前：默认动态背景");
            Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (getScanRootUris().size() >= MAX_SCAN_ROOTS) {
                Toast.makeText(this, "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
                return;
            }
            launchScanRootPicker(-1);
        });
        dialog.setOnDismissListener(d -> {
            activeScanRootList = null;
            activeScanRootInfo = null;
        });
    }

    private List<String> getScanRootUris() {
List<String> roots = new ArrayList<>();
if (prefs == null) return roots;
String joined = prefs.getString(KEY_SCAN_ROOT_URIS, "");
if (joined != null && !joined.trim().isEmpty()) {
for (String part : joined.split("\\n")) {
String s = part == null ? "" : part.trim();
if (!s.isEmpty() && !roots.contains(s)) roots.add(s);
if (roots.size() >= MAX_SCAN_ROOTS) break;
}
}
String legacy = prefs.getString(KEY_LAST_SCAN_ROOT_URI, "");
if (roots.isEmpty() && legacy != null && !legacy.trim().isEmpty()) roots.add(legacy.trim());
return roots;
}

private void saveScanRootUris(List<String> roots) {
if (prefs == null) return;
List<String> cleaned = new ArrayList<>();
if (roots != null) {
for (String r : roots) {
String s = r == null ? "" : r.trim();
if (!s.isEmpty() && !cleaned.contains(s)) cleaned.add(s);
if (cleaned.size() >= MAX_SCAN_ROOTS) break;
}
}
StringBuilder joined = new StringBuilder();
for (String r : cleaned) {
if (joined.length() > 0) joined.append('\n');
joined.append(r);
}
SharedPreferences.Editor e = prefs.edit().putString(KEY_SCAN_ROOT_URIS, joined.toString());
if (!cleaned.isEmpty()) e.putString(KEY_LAST_SCAN_ROOT_URI, cleaned.get(0)); else e.remove(KEY_LAST_SCAN_ROOT_URI);
e.apply();
}

private boolean addOrReplaceScanRoot(String uri, int replaceIndex) {
if (uri == null || uri.trim().isEmpty()) return false;
List<String> roots = getScanRootUris();
String value = uri.trim();
roots.remove(value);
if (replaceIndex >= 0 && replaceIndex < roots.size()) roots.set(replaceIndex, value);
else if (roots.size() < MAX_SCAN_ROOTS) roots.add(value);
else {
Toast.makeText(this, "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
return false;
}
saveScanRootUris(roots);
return true;
}

private void removeScanRootAt(int index) {
List<String> roots = getScanRootUris();
if (index < 0 || index >= roots.size()) return;
roots.remove(index);
saveScanRootUris(roots);
}

private String scanRootsSummary() {
List<String> roots = getScanRootUris();
if (roots.isEmpty()) return "未绑定";
StringBuilder sb = new StringBuilder();
for (int i = 0; i < roots.size(); i++) {
if (i > 0) sb.append('\n');
sb.append(i + 1).append(". ").append(roots.get(i));
}
return sb.toString();
}

private String compactUriLabel(String uri) {
if (uri == null || uri.trim().isEmpty()) return "未绑定";
String s = uri.trim();
try {
Uri u = Uri.parse(s);
String last = u.getLastPathSegment();
if (last != null && !last.isEmpty()) return java.net.URLDecoder.decode(last, "UTF-8").replace("primary:", "/storage/emulated/0/");
} catch (Throwable ignored) { }
return s.length() > 72 ? "..." + s.substring(s.length() - 72) : s;
}

private void launchScanRootPicker(int replaceIndex) {
pendingScanRootReplaceIndex = replaceIndex;
scanDirLauncher.launch(null);
}

private LinearLayout scanRootCard(String uri, int index, Runnable refresh) {
LinearLayout card = new LinearLayout(this);
card.setOrientation(LinearLayout.HORIZONTAL);
card.setGravity(android.view.Gravity.CENTER_VERTICAL);
card.setBackgroundResource(R.drawable.bg_input);
card.setPadding(dp(10), dp(8), dp(8), dp(8));
TextView text = new TextView(this);
text.setText((index + 1) + ". " + compactUriLabel(uri));
text.setTextColor(getColorCompat(R.color.yh_text));
text.setTextSize(11);
text.setSingleLine(false);
card.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
TextView change = new TextView(this);
change.setText("更换");
change.setTextColor(getColorCompat(R.color.yh_primary));
change.setTextSize(12);
change.setTypeface(null, android.graphics.Typeface.BOLD);
change.setPadding(dp(10), 0, dp(8), 0);
change.setOnClickListener(v -> launchScanRootPicker(index));
card.addView(change);
TextView remove = new TextView(this);
remove.setText("移除");
remove.setTextColor(getColorCompat(R.color.yh_warning));
remove.setTextSize(12);
remove.setTypeface(null, android.graphics.Typeface.BOLD);
remove.setPadding(dp(8), 0, 0, 0);
remove.setOnClickListener(v -> {
removeScanRootAt(index);
if (refresh != null) refresh.run();
});
card.addView(remove);
return card;
}

private void refreshActiveScanRootListUi() {
if (activeScanRootList != null) refreshScanRootListUi(activeScanRootList, activeScanRootInfo);
}

private void refreshScanRootListUi(LinearLayout container, TextView info) {
if (container == null) return;
container.removeAllViews();
List<String> roots = getScanRootUris();
if (info != null) info.setText("已绑定 " + roots.size() + "/" + MAX_SCAN_ROOTS + " 个目录，扫描时会合并扫描。" + (roots.isEmpty() ? "\n请先添加扫描目录。" : ""));
if (roots.isEmpty()) {
TextView empty = new TextView(this);
empty.setText("暂无扫描目录");
empty.setTextColor(getColorCompat(R.color.yh_text_muted));
empty.setTextSize(12);
empty.setGravity(android.view.Gravity.CENTER);
empty.setBackgroundResource(R.drawable.bg_input);
container.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
return;
}
for (int i = 0; i < roots.size(); i++) {
final int index = i;
LinearLayout card = scanRootCard(roots.get(i), index, () -> refreshScanRootListUi(container, info));
LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
lp.setMargins(0, 0, 0, dp(6));
container.addView(card, lp);
}
}

private void checkUpdateOnStartupIfEnabled() {
        try {
            if (prefs == null || !prefs.getBoolean(KEY_CHECK_UPDATE_ON_STARTUP, true)) return;
            long last = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L);
            if (last > 0 && System.currentTimeMillis() - last < UPDATE_AUTO_CHECK_INTERVAL_MS) return;
            checkUpdate(false);
        } catch (Throwable t) {
            Log.w("YukiHub", "startup update check skipped", t);
        }
    }

    private void checkUpdateManually() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();
        checkUpdate(true);
    }

    private void checkUpdate(boolean manual) {
        AppExecutors.runOnIo(() -> {
            try {
                UpdateInfo info = fetchLatestRelease();
                if (prefs != null) prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, System.currentTimeMillis()).apply();
                String current = getCurrentVersionName();
                boolean newer = info != null && isNewerVersion(info.version, current);
                runOnUiThread(() -> {
                    if (newer) {
                        showUpdateDialog(info, current);
                    } else if (manual) {
                        Toast.makeText(this, "已是最新版本：" + emptyText(current, "未知"), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "check update failed", t);
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(this, "检查更新失败：" + emptyText(t.getMessage(), "请稍后重试"), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private UpdateInfo fetchLatestRelease() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_API_URL).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "YukiHub-Android/" + getCurrentVersionName());
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new RuntimeException("GitHub HTTP " + code + ": " + trimForDialog(text, 160));
        JSONObject o = new JSONObject(text == null ? "{}" : text);
        UpdateInfo info = new UpdateInfo();
        info.tagName = o.optString("tag_name", "");
        info.version = normalizeVersion(info.tagName);
        info.name = o.optString("name", info.tagName);
        info.body = o.optString("body", "");
        info.releaseUrl = o.optString("html_url", UPDATE_REPO_URL + "/releases");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String assetName = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (url == null || url.trim().isEmpty()) continue;
                if (info.downloadUrl == null || info.downloadUrl.isEmpty()) info.downloadUrl = url;
                String lowerName = assetName.toLowerCase(Locale.ROOT);
                String lowerUrl = url.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith(".apk") || lowerUrl.contains(".apk")) {
                    info.apkUrl = url;
                    break;
                }
            }
        }
        if (info.version == null || info.version.isEmpty()) info.version = normalizeVersion(info.name);
        if (info.downloadUrl == null || info.downloadUrl.isEmpty()) info.downloadUrl = info.releaseUrl;
        if (info.apkUrl == null || info.apkUrl.isEmpty()) info.apkUrl = info.releaseUrl;
        return info;
    }

    private String getCurrentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isNewerVersion(String latest, String current) {
        String l = normalizeVersion(latest);
        String c = normalizeVersion(current);
        if (l.isEmpty() || c.isEmpty()) return !l.equals(c);
        String[] la = l.split("\\.");
        String[] ca = c.split("\\.");
        int n = Math.max(la.length, ca.length);
        for (int i = 0; i < n; i++) {
            long lv = i < la.length ? parseVersionPart(la[i]) : 0L;
            long cv = i < ca.length ? parseVersionPart(ca[i]) : 0L;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private long parseVersionPart(String part) {
        try {
            if (part == null) return 0L;
            String digits = part.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0L : Long.parseLong(digits);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private String normalizeVersion(String value) {
        if (value == null) return "";
        String v = value.trim();
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+){1,5})").matcher(v);
        if (m.find()) return m.group(1);
        v = v.replaceFirst("^[vV]", "").replaceAll("[^0-9.]", "");
        while (v.startsWith(".")) v = v.substring(1);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private void showUpdateDialog(UpdateInfo info, String currentVersion) {
        if (info == null || isFinishing()) return;
        String latestLabel = emptyText(info.tagName, info.version);
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(emptyText(currentVersion, "未知")).append("\n");
        msg.append("最新版本：").append(emptyText(latestLabel, "未知")).append("\n\n");
        String body = trimForDialog(info.body, 1600);
        if (body != null && !body.trim().isEmpty()) {
            msg.append("更新内容：\n").append(body.trim());
        } else {
            msg.append("发现新的 GitHub Release，可前往发布页查看详情。");
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + emptyText(latestLabel, ""))
                .setMessage(msg.toString())
                .setPositiveButton("前往下载", (d, w) -> openExternalUrl(emptyText(info.apkUrl, info.releaseUrl)))
                .setNeutralButton("发布页", (d, w) -> openExternalUrl(emptyText(info.releaseUrl, UPDATE_REPO_URL + "/releases")))
                .setNegativeButton("稍后", null)
                .show();
        styleAlertDialogDark(dialog);
    }

    private String trimForDialog(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (max <= 0 || t.length() <= max) return t;
        return t.substring(0, max) + "\n...";
    }

    private static class UpdateInfo {
        String tagName;
        String version;
        String name;
        String body;
        String releaseUrl;
        String downloadUrl;
        String apkUrl;
    }

    private void openExternalUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDisclaimerDialog() {
        String text = "免责声明\n\n" +
                "1. 本应用为开源项目，仅用于管理、整理和启动用户本人有权使用的游戏与应用。\n\n" +
                "2. 用户应自行确保所添加资源、账号、同步内容以及第三方服务的合法性、完整性与可用性。\n\n" +
                "3. 本应用不提供任何游戏本体、破解资源、绕过授权或规避版权/平台规则的能力。\n\n" +
                "4. Shizuku、GameHub、WebDAV、VNDB、Bangumi、系统存储权限等能力均依赖第三方应用、系统环境或外部服务，可能因设备、系统版本、权限状态或服务变更而不可用。\n\n" +
                "5. 因第三方服务、系统限制、用户误操作或资源本身问题造成的数据丢失、同步异常、启动失败、兼容性问题或其他损失，开发者不承担额外责任。\n\n" +
                "6. 如果你不同意以上说明，请停止使用相关功能。";
        TextView tv = new TextView(this);
        int pad = dp(18);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextColor(getColorCompat(R.color.yh_text_muted));
        tv.setTextSize(13);
        tv.setLineSpacing(dp(3), 1.08f);
        tv.setText(text);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("免责声明")
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .show();
        styleAlertDialogDark(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.62f), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
        }
    }

    private String normalizePlayStatus(String status) {
    if (status == null) return "unplayed";
    String s = status.trim().toLowerCase(Locale.ROOT);
    if ("completed".equals(s) || "played".equals(s) || "done".equals(s)) return "completed";
    if ("playing".equals(s) || "current".equals(s)) return "playing";
    return "unplayed";
}

private String playStatusLabel(String status) {
    String s = normalizePlayStatus(status);
    if ("completed".equals(s)) return "🏆 玩过";
    if ("playing".equals(s)) return "🎮 在玩";
    return "☆ 未玩";
}

private int playStatusIndex(String status) {
    String s = normalizePlayStatus(status);
    if ("playing".equals(s)) return 1;
    if ("completed".equals(s)) return 2;
    return 0;
}

private String playStatusFromIndex(int index) {
    if (index == 1) return "playing";
    if (index == 2) return "completed";
    return "unplayed";
}

private void showPlayStatusDialog(Game game, Dialog parentDialog) {
    if (game == null) return;
    String[] labels = new String[]{"☆ 未玩", "🎮 在玩", "🏆 玩过"};
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundResource(R.drawable.bg_dialog);
    root.setPadding(dp(14), dp(8), dp(14), dp(8));
    final AlertDialog[] ref = new AlertDialog[1];
    int selected = playStatusIndex(game.playStatus);
    for (int i = 0; i < labels.length; i++) {
        final int index = i;
        TextView row = new TextView(this);
        row.setText((index == selected ? "●  " : "○  ") + labels[index]);
        row.setTextColor(getColorCompat(index == selected ? R.color.yh_primary : R.color.yh_text));
        row.setTextSize(18);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input);
        row.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(row, lp);
        row.setOnClickListener(v -> {
            game.playStatus = playStatusFromIndex(index);
            repository.update(game);
            Toast.makeText(this, "已标记为：" + playStatusLabel(game.playStatus), Toast.LENGTH_SHORT).show();
            if (ref[0] != null) ref[0].dismiss();
            if (parentDialog != null) parentDialog.dismiss();
            loadGames();
            updateSideDetail(game);
        });
    }
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("设置游玩状态")
            .setView(root)
            .setNegativeButton("取消", null)
            .show();
    ref[0] = dialog;
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.42f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }
}

private void showDetailDialog(Game game) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow();
        d.setOnShowListener(dialog -> {
            applyImmersiveToWindow(d.getWindow());
            enterImmersiveMode();
        });
        d.setOnDismissListener(dialog -> enterImmersiveMode());
        d.setContentView(R.layout.dialog_game_detail);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            applyImmersiveToWindow(d.getWindow());
        }
        ((TextView)d.findViewById(R.id.detailTitle)).setText(game.title);
        ((TextView)d.findViewById(R.id.detailInfo)).setText("状态：" + playStatusLabel(game.playStatus) + "\n引擎：" + game.engine.getDisplayName() + "\n总时长：" + TimeFormatUtil.playTime(game.totalPlayTime) + "\n最近游玩：" + TimeFormatUtil.date(game.lastPlayedAt) + "\n模拟器：" + emptyText(game.emulatorPackage, "未配置"));
        ((TextView)d.findViewById(R.id.detailPath)).setText("路径：" + displayPath(game.rootUri));
        ImageView cover = d.findViewById(R.id.detailCover);
        TextView ph = d.findViewById(R.id.detailCoverPlaceholder);
        String safeCover = safeCoverUri(game);
        if (safeCover != null && !safeCover.isEmpty()) {
            try {
                Uri u = Uri.parse(safeCover);
                cover.setImageURI(u);
                cover.setVisibility(View.VISIBLE);
                ph.setVisibility(View.GONE);
            } catch (Throwable e) {
                cover.setImageDrawable(null);
                cover.setVisibility(View.GONE);
                ph.setVisibility(View.VISIBLE);
            }
        }
        d.findViewById(R.id.btnStatus).setOnClickListener(v -> showPlayStatusDialog(game, d));
        d.findViewById(R.id.btnEdit).setOnClickListener(v -> { d.dismiss(); showEditDialog(game); });
        boolean hasEngineSettings = game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ONS;
        d.findViewById(R.id.btnKrSettings).setVisibility(hasEngineSettings ? View.VISIBLE : View.GONE);
        d.findViewById(R.id.btnKrSettings).setOnClickListener(v -> {
            if (game.engine == EngineType.ONS) showOnsSettingsDialog(game); else showKrSettingsDialog(game);
        });
        d.findViewById(R.id.btnDelete).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("删除游戏").setMessage("确定删除 “" + game.title + "”？不会删除本体文件。").setPositiveButton("删除", (x,w)->{ repository.delete(game.id); d.dismiss(); loadGames(); }).setNegativeButton("取消", null).show());
        d.findViewById(R.id.btnLaunch).setOnClickListener(v -> launchGame(game));
        d.show();
        applyImmersiveToWindow(d.getWindow());
        enterImmersiveMode();
        if (d.getWindow() != null) {
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            applyImmersiveToWindow(d.getWindow());
            d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            applyImmersiveToWindow(d.getWindow());
        }
    }

    private void showGameHubShortcutPicker(EditText titleTarget, EditText pkgTarget, EditText gamehubIdTarget) {
        if (requestShizukuPermissionIfNeeded()) return;
        AppExecutors.runOnSingle(() -> {
            try {
                List<GameHubShortcutItem> items = loadGameHubShortcuts();
                runOnUiThread(() -> {
                    if (items.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("导入快捷方式")
                                .setMessage("没有读取到可用的 GameHub 快捷方式。\n\n请确认：1）Shizuku 正在运行并已授权；2）GameHub 已创建桌面快捷方式；3）补丁包包名为 com.xiaoji.egggamz 或原包 com.xiaoji.egggame。\n\n如果是 iQOO/OriginOS 等设备，可点击“复制诊断”把 Shizuku/Shortcut 输出发给开发者排查。也可以粘贴 shortcut dump 参数导入。")
                                .setPositiveButton("粘贴参数", (x, w) -> showGameHubShortcutTextImport(titleTarget, pkgTarget, gamehubIdTarget))
                                .setNeutralButton("复制诊断", (x, w) -> copyGameHubShortcutDiagnostics())
                                .setNegativeButton("知道了", null)
                                .show();
                        return;
                    }
                    showGameHubShortcutListDialog(items, titleTarget, pkgTarget, gamehubIdTarget);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("导入失败")
                        .setMessage("读取快捷方式失败：" + t.getClass().getSimpleName() + "\n\n如果系统没有授予读取桌面快捷方式的权限，这属于系统限制。")
                        .setPositiveButton("知道了", null)
                        .show());
            }
        });
    }

    private void showGameHubShortcutListDialog(List<GameHubShortcutItem> source, EditText titleTarget, EditText pkgTarget, EditText gamehubIdTarget) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_gamehub_shortcut_picker);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.74f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
        RecyclerView rv = dialog.findViewById(R.id.recyclerGameHubShortcuts);
        EditText search = dialog.findViewById(R.id.etGameHubShortcutSearch);
        TextView hint = dialog.findViewById(R.id.tvGameHubShortcutHint);
        rv.setLayoutManager(new LinearLayoutManager(this));
        Drawable icon = getGameHubIcon();
        for (GameHubShortcutItem item : source) {
            if (item != null && item.icon == null) item.icon = icon;
        }
        final GameHubShortcutAdapter[] adapterRef = new GameHubShortcutAdapter[1];
        adapterRef[0] = new GameHubShortcutAdapter(source, item -> {
            if (item == null) return;
            if (gamehubIdTarget != null) gamehubIdTarget.setText(item.localGameId);
            if (titleTarget != null && (titleTarget.getText() == null || titleTarget.getText().toString().trim().isEmpty())) titleTarget.setText(item.localAppName);
            if (pkgTarget != null && (pkgTarget.getText() == null || pkgTarget.getText().toString().trim().isEmpty())) pkgTarget.setText(guessInstalledGameHubPackage());
            Toast.makeText(this, "已导入 GameHub 快捷方式", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        rv.setAdapter(adapterRef[0]);
        hint.setText("共 " + adapterRef[0].getItemCount() + " 个快捷方式，可搜索游戏名或ID");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (adapterRef[0] == null) return;
                adapterRef[0].filter(s == null ? "" : s.toString());
                hint.setText("共 " + source.size() + " 个快捷方式，当前显示 " + adapterRef[0].getItemCount() + " 个");
            }
            public void afterTextChanged(Editable e) {}
        });
        dialog.findViewById(R.id.btnCloseGameHubShortcutPicker).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.74f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private Drawable getGameHubIcon() {
        try { return getPackageManager().getApplicationIcon(guessInstalledGameHubPackage()); } catch (Throwable ignored) { }
        try { return getPackageManager().getApplicationIcon("com.xiaoji.egggame"); } catch (Throwable ignored) { }
        return null;
    }

    private interface GameHubShortcutCallback { void onPick(GameHubShortcutItem item); }

    private class GameHubShortcutAdapter extends RecyclerView.Adapter<GameHubShortcutAdapter.Holder> {
        private final List<GameHubShortcutItem> allItems;
        private final List<GameHubShortcutItem> items = new ArrayList<>();
        private final GameHubShortcutCallback callback;
        GameHubShortcutAdapter(List<GameHubShortcutItem> source, GameHubShortcutCallback callback) {
            this.allItems = source == null ? new ArrayList<>() : new ArrayList<>(source);
            this.items.addAll(this.allItems);
            this.callback = callback;
        }
        void filter(String query) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            items.clear();
            if (q.isEmpty()) {
                items.addAll(allItems);
            } else {
                for (GameHubShortcutItem item : allItems) {
                    if (item == null) continue;
                    String label = item.displayLabel == null ? "" : item.displayLabel.toLowerCase(Locale.ROOT);
                    String name = item.localAppName == null ? "" : item.localAppName.toLowerCase(Locale.ROOT);
                    String id = item.localGameId == null ? "" : item.localGameId.toLowerCase(Locale.ROOT);
                    if (label.contains(q) || name.contains(q) || id.contains(q)) items.add(item);
                }
            }
            notifyDataSetChanged();
        }
        @Override public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_picker, parent, false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(76));
            lp.setMargins(0, 0, 0, dp(8));
            v.setLayoutParams(lp);
            return new Holder(v);
        }
        @Override public void onBindViewHolder(Holder h, int position) {
            GameHubShortcutItem item = items.get(position);
            h.label.setText(emptyText(item.displayLabel, item.localAppName));
            h.id.setText(item.localGameId);
            if (item.icon != null) h.icon.setImageDrawable(item.icon); else h.icon.setImageResource(android.R.mipmap.sym_def_app_icon);
            h.itemView.setOnClickListener(v -> { if (callback != null) callback.onPick(item); });
        }
        @Override public int getItemCount() { return items.size(); }
        class Holder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView label, id;
            Holder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.ivAppIcon);
                label = itemView.findViewById(R.id.tvAppLabel);
                id = itemView.findViewById(R.id.tvAppPackage);
            }
        }
    }

    private void showGameHubShortcutTextImport(EditText titleTarget, EditText pkgTarget, EditText gamehubIdTarget) {
        final EditText input = new EditText(this);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setGravity(android.view.Gravity.TOP);
        input.setHint("粘贴包含 localGameId=local_xxx 或 steamAppId=123456 的快捷方式参数");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("粘贴 GameHub 快捷方式参数")
                .setView(input)
                .setPositiveButton("导入", (d, w) -> {
                    GameHubShortcutItem item = parseGameHubShortcutText(input.getText() == null ? "" : input.getText().toString());
                    if (item == null || item.localGameId.isEmpty()) {
                        Toast.makeText(this, "未识别到 localGameId 或 steamAppId", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (gamehubIdTarget != null) gamehubIdTarget.setText(item.localGameId);
                    if (titleTarget != null && (titleTarget.getText() == null || titleTarget.getText().toString().trim().isEmpty())) titleTarget.setText(item.localAppName);
                    if (pkgTarget != null && (pkgTarget.getText() == null || pkgTarget.getText().toString().trim().isEmpty())) pkgTarget.setText(guessInstalledGameHubPackage());
                    Toast.makeText(this, "已导入 GameHub 快捷方式参数", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private GameHubShortcutItem parseGameHubShortcutText(String text) {
        if (text == null) return null;
        text = text.replace('\0', ' ');
        String localGameId = matchFirst(text, "(?i)\\blocalGameId\\b\\s*[:=]\\s*([^,}\\]\\s]+)");
        localGameId = cleanGameHubValue(localGameId);
        if (localGameId == null || localGameId.trim().isEmpty()) localGameId = matchFirst(text, "\\blocal_[0-9a-fA-F\\-]{8,}\\b");
        localGameId = cleanGameHubValue(localGameId);
        String steamAppId = matchFirst(text, "(?i)\\bsteamAppI[dD]\\b\\s*[:=]\\s*[^0-9]*([0-9]+)");
        steamAppId = cleanGameHubValue(steamAppId);
        String storedId = localGameId == null || localGameId.trim().isEmpty() ? null : localGameId.trim();
        if ((storedId == null || storedId.isEmpty()) && steamAppId != null && !steamAppId.trim().isEmpty() && !"0".equals(steamAppId.trim())) storedId = "steam:" + steamAppId.trim();
        if (storedId == null || storedId.trim().isEmpty()) return null;
        String localAppName = extractGameHubNameFromText(text);
        if (localAppName == null || localAppName.trim().isEmpty()) localAppName = storedId;
        return new GameHubShortcutItem(localAppName.trim(), localAppName.trim(), storedId.trim());
    }

    private String extractGameHubNameFromText(String text) {
        String name = matchFirst(text, "(?i)\\blocalAppName\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)");
        if (name == null || name.trim().isEmpty()) name = matchFirst(text, "(?i)\\bgameName\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)");
        if (name == null || name.trim().isEmpty()) name = matchFirst(text, "(?i)\\bshortLabel\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)");
        if (name == null || name.trim().isEmpty()) name = matchFirst(text, "(?i)\\blabel\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)");
        return cleanGameHubValue(name);
    }

    private String cleanGameHubValue(String value) {
        if (value == null) return null;
        String v = value.replace('\0', ' ').trim();
        while (v.startsWith("\"") || v.startsWith("'") || v.startsWith("[")) v = v.substring(1).trim();
        while (v.endsWith("\"") || v.endsWith("'") || v.endsWith(",") || v.endsWith("}") || v.endsWith("]")) v = v.substring(0, v.length() - 1).trim();
        if (v.startsWith("String:")) v = v.substring("String:".length()).trim();
        if ("null".equalsIgnoreCase(v)) return null;
        return v;
    }

    private String matchFirst(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : null;
        } catch (Throwable ignored) { return null; }
    }

    private List<GameHubShortcutItem> loadGameHubShortcuts() {
        List<GameHubShortcutItem> items = new ArrayList<>();
        items.addAll(loadGameHubShortcutsFromShizuku());
        if (!items.isEmpty()) return items;
        try {
            LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps == null) return items;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    if (!launcherApps.hasShortcutHostPermission()) {
                        Log.w("YukiHub", "LauncherApps shortcut permission missing");
                    }
                } catch (Throwable ignored) { }
            }
            List<ShortcutInfo> shortcuts = new ArrayList<>();
            for (String ghPkg : new String[]{"com.xiaoji.egggamz", "com.xiaoji.egggame"}) {
                try {
                    LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
                    query.setPackage(ghPkg);
                    query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
                    List<ShortcutInfo> part = launcherApps.getShortcuts(query, android.os.Process.myUserHandle());
                    if (part != null) shortcuts.addAll(part);
                } catch (Throwable ignored) { }
            }
            if (shortcuts.isEmpty()) return items;
            for (ShortcutInfo si : shortcuts) {
                if (si == null) continue;
                String localGameId = extractGameHubLocalGameId(si);
                if (localGameId == null || localGameId.trim().isEmpty()) continue;
                String localAppName = extractGameHubLocalAppName(si);
                String label = String.valueOf(si.getShortLabel());
                if (label == null || label.trim().isEmpty() || "null".equalsIgnoreCase(label.trim())) label = localAppName;
                if (label == null || label.trim().isEmpty()) label = localGameId;
                Drawable shortcutIcon = null;
                try { shortcutIcon = launcherApps.getShortcutIconDrawable(si, getResources().getDisplayMetrics().densityDpi); } catch (Throwable ignored) { }
                items.add(new GameHubShortcutItem(label, localAppName, localGameId, shortcutIcon));
            }
            items.sort((a, b) -> a.displayLabel.compareToIgnoreCase(b.displayLabel));
        } catch (Throwable t) {
            Log.w("YukiHub", "loadGameHubShortcuts failed", t);
        }
        if (items.isEmpty()) items.addAll(loadGameHubShortcutsFromExternalLogs());
        return items;
    }

    private boolean requestShizukuPermissionIfNeeded() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return false;
            Shizuku.requestPermission(62001);
            Toast.makeText(this, "请在 Shizuku 弹窗中授权，授权后再点一次导入快捷方式", Toast.LENGTH_LONG).show();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<GameHubShortcutItem> loadGameHubShortcutsFromShizuku() {
        List<GameHubShortcutItem> items = new ArrayList<>();
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return items;
            String out = runGameHubShizukuCommand(buildGameHubShortcutDumpCommand(false));
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            addGameHubShortcutItemsFromText(out, items, seen);
            items.sort((a, b) -> a.displayLabel.compareToIgnoreCase(b.displayLabel));
        } catch (Throwable t) {
            Log.w("YukiHub", "loadGameHubShortcutsFromShizuku failed", t);
        }
        return items;
    }

    private void addGameHubShortcutItemsFromText(String text, List<GameHubShortcutItem> out, java.util.HashSet<String> seen) {
        if (text == null || out == null || seen == null) return;
        String normalized = text.replace('\0', ' ');
        String[] lines = normalized.split("\\r?\\n");
        for (String line : lines) {
            addGameHubShortcutItemIfValid(parseGameHubShortcutText(line), out, seen);
        }
        try {
            Matcher m = Pattern.compile("(?i)(localGameId\\s*[:=]\\s*([^,}\\]\\s]+)|\\blocal_[0-9a-fA-F\\-]{8,}\\b|steamAppI[dD]\\s*[:=]\\s*[^0-9]*([0-9]+))").matcher(normalized);
            while (m.find()) {
                int start = Math.max(0, m.start() - 700);
                int end = Math.min(normalized.length(), m.end() + 1600);
                addGameHubShortcutItemIfValid(parseGameHubShortcutText(normalized.substring(start, end)), out, seen);
            }
        } catch (Throwable ignored) { }
    }

    private void addGameHubShortcutItemIfValid(GameHubShortcutItem item, List<GameHubShortcutItem> out, java.util.HashSet<String> seen) {
        if (item == null || item.localGameId == null || item.localGameId.trim().isEmpty()) return;
        String key = item.localGameId.trim().toLowerCase(Locale.ROOT);
        if (seen.contains(key)) return;
        seen.add(key);
        out.add(item);
    }

    private String buildGameHubShortcutDumpCommand(boolean diagnostic) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("uid=$(am get-current-user 2>/dev/null | tr -d '\\r' | head -n 1); ");
        cmd.append("case \"$uid\" in ''|*[!0-9]*) uid=0;; esac; ");
        cmd.append("echo '--- YukiHub GameHub shortcut dump ---'; ");
        cmd.append("echo user=$uid; ");
        cmd.append("echo sdk=$(getprop ro.build.version.sdk 2>/dev/null) release=$(getprop ro.build.version.release 2>/dev/null); ");
        cmd.append("echo brand=$(getprop ro.product.brand 2>/dev/null) manufacturer=$(getprop ro.product.manufacturer 2>/dev/null) model=$(getprop ro.product.model 2>/dev/null); ");
        cmd.append("echo '--- packages ---'; pm path com.xiaoji.egggamz 2>&1; pm path com.xiaoji.egggame 2>&1; ");
        cmd.append("for u in $uid 0; do ");
        cmd.append("echo --- cmd shortcut user=$u package=com.xiaoji.egggamz ---; cmd shortcut get-shortcuts --user $u --flags 31 com.xiaoji.egggamz 2>&1; ");
        cmd.append("echo --- cmd shortcut user=$u package=com.xiaoji.egggame ---; cmd shortcut get-shortcuts --user $u --flags 31 com.xiaoji.egggame 2>&1; ");
        cmd.append("done; ");
        cmd.append("echo '--- dumpsys shortcut filtered ---'; ");
        cmd.append("dumpsys shortcut 2>&1 | grep -i -A 40 -B 12 'com.xiaoji.egggamz\\|com.xiaoji.egggame\\|localGameId\\|local_\\|steamAppId' 2>&1; ");
        if (diagnostic) {
            cmd.append("echo '--- launcher packages ---'; pm list packages | grep -i 'launcher\\|bbk\\|vivo\\|origin' 2>&1; ");
        }
        return cmd.toString();
    }

    private String runGameHubShizukuCommand(String cmd) throws Exception {
        Process p;
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, new Object[]{new String[]{"/system/bin/sh", "-c", cmd}, null, null});
        } catch (Throwable reflectError) {
            throw new RuntimeException("Shizuku newProcess unavailable", reflectError);
        }
        String out = readProcessStream(p.getInputStream()) + "\n" + readProcessStream(p.getErrorStream());
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out;
    }

    private void copyGameHubShortcutDiagnostics() {
        if (requestShizukuPermissionIfNeeded()) return;
        Toast.makeText(this, "正在生成 GameHub 快捷方式诊断...", Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            String result;
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    result = "Shizuku 未运行或未授权。";
                } else {
                    result = runGameHubShizukuCommand(buildGameHubShortcutDumpCommand(true));
                }
            } catch (Throwable t) {
                result = "生成诊断失败：" + t.getClass().getName() + "\n" + String.valueOf(t.getMessage());
            }
            final String text = result == null ? "" : (result.length() > 180000 ? result.substring(0, 180000) + "\n--- truncated by YukiHub ---" : result);
            runOnUiThread(() -> {
                try {
                    Object service = getSystemService(Context.CLIPBOARD_SERVICE);
                    if (service instanceof android.content.ClipboardManager) {
                        ((android.content.ClipboardManager) service).setPrimaryClip(android.content.ClipData.newPlainText("YukiHub GameHub shortcut diagnostics", text));
                        Toast.makeText(this, "诊断信息已复制，可发给开发者排查", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "复制失败：无法获取剪贴板服务", Toast.LENGTH_LONG).show();
                    }
                } catch (Throwable t) {
                    Toast.makeText(this, "复制诊断失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String readProcessStream(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private List<GameHubShortcutItem> loadGameHubShortcutsFromExternalLogs() {
        List<GameHubShortcutItem> items = new ArrayList<>();
        String[] roots = new String[]{
                "/sdcard/Android/data/com.xiaoji.egggamz/files/log",
                "/sdcard/Android/data/com.xiaoji.egggamz/files/logs",
                "/sdcard/Android/data/com.xiaoji.egggamz/files/Documents/XiaoKunLogcat",
                "/sdcard/Android/data/com.xiaoji.egggame/files/log",
                "/sdcard/Android/data/com.xiaoji.egggame/files/logs",
                "/sdcard/Android/data/com.xiaoji.egggame/files/Documents/XiaoKunLogcat"
        };
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String root : roots) {
            collectGameHubShortcutItemsFromDir(new File(root), items, seen, 2);
        }
        items.sort((a, b) -> a.displayLabel.compareToIgnoreCase(b.displayLabel));
        return items;
    }

    private void collectGameHubShortcutItemsFromDir(File dir, List<GameHubShortcutItem> out, java.util.HashSet<String> seen, int depth) {
        if (dir == null || out == null || seen == null || depth < 0 || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collectGameHubShortcutItemsFromDir(f, out, seen, depth - 1);
                continue;
            }
            String name = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".json") || name.endsWith(".xml"))) continue;
            if (f.length() > 1024L * 1024L * 4L) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("localGameId") && !line.contains("local_") && !line.contains("steamAppId") && !line.contains("steamAppid")) continue;
                    GameHubShortcutItem item = parseGameHubShortcutText(line);
                    if (item == null || item.localGameId.isEmpty() || seen.contains(item.localGameId)) continue;
                    seen.add(item.localGameId);
                    out.add(item);
                }
            } catch (Throwable ignored) { }
        }
    }

    private String extractGameHubLocalGameId(ShortcutInfo si) {
        if (si == null) return null;
        try {
            Intent[] intents = si.getIntents();
            if (intents != null && intents.length > 0) {
                for (int i = intents.length - 1; i >= 0; i--) {
                    Intent intent = intents[i];
                    if (intent == null) continue;
                    Bundle extras = intent.getExtras();
                    if (extras != null) {
                        String localGameId = extras.getString("localGameId");
                        if (localGameId != null && !localGameId.trim().isEmpty()) return localGameId.trim();
                        GameHubShortcutItem fromExtras = parseGameHubShortcutText(extras.toString());
                        if (fromExtras != null && fromExtras.localGameId != null && !fromExtras.localGameId.trim().isEmpty()) return fromExtras.localGameId.trim();
                    }
                    GameHubShortcutItem fromIntent = parseGameHubShortcutText(intent.toUri(0));
                    if (fromIntent != null && fromIntent.localGameId != null && !fromIntent.localGameId.trim().isEmpty()) return fromIntent.localGameId.trim();
                }
            }
        } catch (Throwable ignored) { }
        try {
            PersistableBundle extras = si.getExtras();
            if (extras != null) {
                String localGameId = extras.getString("localGameId");
                if (localGameId != null && !localGameId.trim().isEmpty()) return localGameId.trim();
                GameHubShortcutItem fromExtras = parseGameHubShortcutText(extras.toString());
                if (fromExtras != null && fromExtras.localGameId != null && !fromExtras.localGameId.trim().isEmpty()) return fromExtras.localGameId.trim();
            }
        } catch (Throwable ignored) { }
        try {
            GameHubShortcutItem fromShortcut = parseGameHubShortcutText(si.toString());
            if (fromShortcut != null && fromShortcut.localGameId != null && !fromShortcut.localGameId.trim().isEmpty()) return fromShortcut.localGameId.trim();
        } catch (Throwable ignored) { }
        return null;
    }

    private String extractGameHubLocalAppName(ShortcutInfo si) {
        if (si == null) return "";
        try {
            Intent[] intents = si.getIntents();
            if (intents != null && intents.length > 0) {
                for (int i = intents.length - 1; i >= 0; i--) {
                    Intent intent = intents[i];
                    if (intent == null) continue;
                    Bundle extras = intent.getExtras();
                    if (extras != null) {
                        String name = extras.getString("localAppName");
                        if (name != null && !name.trim().isEmpty()) return name.trim();
                        name = extractGameHubNameFromText(extras.toString());
                        if (name != null && !name.trim().isEmpty()) return name.trim();
                    }
                    String name = extractGameHubNameFromText(intent.toUri(0));
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Throwable ignored) { }
        try {
            PersistableBundle extras = si.getExtras();
            if (extras != null) {
                String name = extras.getString("localAppName");
                if (name != null && !name.trim().isEmpty()) return name.trim();
                name = extractGameHubNameFromText(extras.toString());
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Throwable ignored) { }
        try {
            String name = extractGameHubNameFromText(si.toString());
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Throwable ignored) { }
        CharSequence shortLabel = null;
        try { shortLabel = si.getShortLabel(); } catch (Throwable ignored) { }
        return shortLabel == null ? "" : shortLabel.toString();
    }

    private static class GameHubShortcutItem {
        final String displayLabel;
        final String localAppName;
        final String localGameId;
        Drawable icon;
        GameHubShortcutItem(String displayLabel, String localAppName, String localGameId) {
            this(displayLabel, localAppName, localGameId, null);
        }
        GameHubShortcutItem(String displayLabel, String localAppName, String localGameId, Drawable icon) {
            this.displayLabel = displayLabel == null ? "" : displayLabel;
            this.localAppName = localAppName == null ? "" : localAppName;
            this.localGameId = localGameId == null ? "" : localGameId;
            this.icon = icon;
        }
    }

    private void showInstalledAppPicker(EditText target) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_picker);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.74f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
        RecyclerView rv = dialog.findViewById(R.id.recyclerAppPicker);
        View loading = dialog.findViewById(R.id.layoutAppLoading);
        TextView hint = dialog.findViewById(R.id.tvAppPickerHint);
        EditText search = dialog.findViewById(R.id.etAppSearch);
        rv.setLayoutManager(new LinearLayoutManager(this));
        dialog.findViewById(R.id.btnCloseAppPicker).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.74f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }

        AppExecutors.runOnIo(() -> {
            List<AppPickItem> items = loadLaunchableAppsForPicker();
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                loading.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
                if (items.isEmpty()) {
                    hint.setText("没有找到可启动的应用");
                    return;
                }
                hint.setText("共 " + items.size() + " 个可启动应用，可搜索应用名或包名");
                final AppPickerAdapter[] adapterRef = new AppPickerAdapter[1];
                adapterRef[0] = new AppPickerAdapter(items, item -> {
                    target.setText(item.packageName);
                    dialog.dismiss();
                });
                rv.setAdapter(adapterRef[0]);
                search.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        if (adapterRef[0] == null) return;
                        adapterRef[0].filter(s == null ? "" : s.toString());
                        hint.setText("共 " + items.size() + " 个应用，当前显示 " + adapterRef[0].getItemCount() + " 个");
                    }
                    public void afterTextChanged(Editable e) {}
                });
            });
        });
    }

    private interface AppPickCallback { void onPick(AppPickItem item); }

    private List<AppPickItem> loadLaunchableAppsForPicker() {
        LinkedHashMap<String, AppPickItem> map = new LinkedHashMap<>();
        try {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchers = pm.queryIntentActivities(launcher, 0);
            if (launchers != null) {
                for (ResolveInfo ri : launchers) {
                    if (ri == null || ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    addAppPickItem(map, pm, ri.activityInfo.applicationInfo);
                }
            }
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (apps != null) {
                for (ApplicationInfo app : apps) {
                    if (app == null || app.packageName == null) continue;
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) addAppPickItem(map, pm, app);
                }
            }
        } catch (Throwable t) {
            Log.w("YukiHub", "load launchable apps failed", t);
        }
        List<AppPickItem> items = new ArrayList<>(map.values());
        items.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return items;
    }

    private void addAppPickItem(Map<String, AppPickItem> map, PackageManager pm, ApplicationInfo app) {
        if (map == null || pm == null || app == null || app.packageName == null) return;
        String key = app.packageName;
        if (map.containsKey(key)) return;
        String label;
        try { label = String.valueOf(pm.getApplicationLabel(app)); }
        catch (Throwable ignored) { label = app.packageName; }
        Drawable icon = null;
        try { icon = pm.getApplicationIcon(app); } catch (Throwable ignored) { }
        map.put(key, new AppPickItem(label, app.packageName, icon));
    }

    private static class AppPickItem {
        final String label;
        final String packageName;
        final Drawable icon;
        AppPickItem(String label, String packageName, Drawable icon) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.icon = icon;
        }
    }

    private class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.Holder> {
        private final List<AppPickItem> allItems;
        private final List<AppPickItem> items = new ArrayList<>();
        private final AppPickCallback callback;
        AppPickerAdapter(List<AppPickItem> items, AppPickCallback callback) {
            this.allItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
            this.items.addAll(this.allItems);
            this.callback = callback;
        }
        void filter(String query) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            items.clear();
            if (q.isEmpty()) {
                items.addAll(allItems);
            } else {
                for (AppPickItem item : allItems) {
                    String label = item.label == null ? "" : item.label.toLowerCase(Locale.ROOT);
                    String pkg = item.packageName == null ? "" : item.packageName.toLowerCase(Locale.ROOT);
                    if (label.contains(q) || pkg.contains(q)) items.add(item);
                }
            }
            notifyDataSetChanged();
        }
        @Override public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_picker, parent, false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(76));
            lp.setMargins(0, 0, 0, dp(8));
            v.setLayoutParams(lp);
            return new Holder(v);
        }
        @Override public void onBindViewHolder(Holder h, int position) {
            AppPickItem item = items.get(position);
            h.label.setText(emptyText(item.label, item.packageName));
            h.pkg.setText(item.packageName);
            if (item.icon != null) h.icon.setImageDrawable(item.icon); else h.icon.setImageResource(android.R.mipmap.sym_def_app_icon);
            h.itemView.setOnClickListener(v -> { if (callback != null) callback.onPick(item); });
        }
        @Override public int getItemCount() { return items.size(); }
        class Holder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView label, pkg;
            Holder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.ivAppIcon);
                label = itemView.findViewById(R.id.tvAppLabel);
                pkg = itemView.findViewById(R.id.tvAppPackage);
            }
        }
    }

private String displayPath(String value) {
        if (value == null || value.trim().isEmpty()) return "未选择游戏目录";
        String s = value.trim();
        if (s.startsWith("file://")) {
            try {
                String path = Uri.parse(s).getPath();
                return path == null || path.isEmpty() ? s.substring("file://".length()) : path;
            } catch (Throwable ignored) {
                return s.substring("file://".length());
            }
        }
        if (s.startsWith("content://")) {
            String path = documentUriToPath(s);
            if (path != null && !path.isEmpty()) return path;
        }
        return s;
    }

    private String documentUriToPath(String value) {
        try {
            Uri uri = Uri.parse(value);
            String docId = null;
            // DocumentFile.fromTreeUri(...).listFiles() 得到的子目录 URI 通常是：
            // content://.../tree/primary%3AGames/document/primary%3AGames%2FExample
            // 详情页要显示到真正的游戏子目录，所以优先取 documentId，而不是 treeId。
            try {
                docId = DocumentsContract.getDocumentId(uri);
            } catch (Throwable ignored) { }
            if (docId == null || docId.isEmpty()) {
                try {
                    docId = DocumentsContract.getTreeDocumentId(uri);
                } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                docId = uri.getLastPathSegment();
            }
            return documentIdToPath(docId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String documentIdToPath(String docId) {
        if (docId == null || docId.trim().isEmpty()) return null;
        String id = Uri.decode(docId.trim());
        // 有些 fallback 可能拿到带前缀的片段，先剥掉 URI 结构前缀；
        // 但不能按最后一个 / 截断，因为 primary:Games/Example 里的 / 是真实路径层级。
        int docPrefix = id.indexOf("/document/");
        if (docPrefix >= 0) id = id.substring(docPrefix + "/document/".length());
        if (id.startsWith("document/")) id = id.substring("document/".length());
        int treePrefix = id.indexOf("/tree/");
        if (treePrefix >= 0) id = id.substring(treePrefix + "/tree/".length());
        if (id.startsWith("tree/")) id = id.substring("tree/".length());
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon);
        String rel = id.substring(colon + 1);
        if (rel.startsWith("/")) rel = rel.substring(1);
        if ("primary".equalsIgnoreCase(volume)) {
            return rel.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + rel;
        }
        return rel.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + rel;
    }

    private void showEditDialog(Game game) {
        pendingDirUri = game == null ? null : game.rootUri;
        pendingCoverUri = game == null ? null : game.coverUri;
        Dialog d = new Dialog(this); pendingEditDialog = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_game_edit);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        ((TextView)d.findViewById(R.id.editDialogTitle)).setText(game == null ? "添加游戏" : "编辑游戏");
        EditText title = d.findViewById(R.id.etGameTitle), pkg = d.findViewById(R.id.etEmulatorPackage), desc = d.findViewById(R.id.etDescription);
        EditText gamehubLocalGameId = d.findViewById(R.id.etGameHubLocalGameId);
        Spinner sp = d.findViewById(R.id.spEngine);
        Spinner launchSp = d.findViewById(R.id.spLaunchTarget);
        Spinner winlatorModeSp = d.findViewById(R.id.spWinlatorLaunchMode);
        Spinner gamehubModeSp = d.findViewById(R.id.spGameHubLaunchMode);
        View winlatorAdvancedLayout = d.findViewById(R.id.layoutWinlatorLaunchMode);
        View gamehubLaunchLayout = d.findViewById(R.id.layoutGameHubLaunch);
        View artemisVersionLayout = d.findViewById(R.id.layoutArtemisVersion);
        Button btnArtemisAuto = d.findViewById(R.id.btnArtemisAuto);
        Button btnArtemisStd = d.findViewById(R.id.btnArtemisStd);
        Button btnArtemisCompat = d.findViewById(R.id.btnArtemisCompat);
        Button btnArtemisCompatV2 = d.findViewById(R.id.btnArtemisCompatV2);
        TextView tvPlayTimeInfo = d.findViewById(R.id.tvPlayTimeInfo);
        View btnPickEmulatorApp = d.findViewById(R.id.btnPickEmulatorApp);
        View btnResetEmulatorPackage = d.findViewById(R.id.btnResetEmulatorPackage);
        View btnPickGameHubShortcut = d.findViewById(R.id.btnPickGameHubShortcut);
        btnPickGameHubShortcut.setOnClickListener(v -> showGameHubShortcutPicker(title, pkg, gamehubLocalGameId));
        btnPickEmulatorApp.setOnClickListener(v -> showInstalledAppPicker(pkg));
        pkg.setOnClickListener(v -> showInstalledAppPicker(pkg));
        Runnable updateWinlatorAdvanced = () -> {
            String engine = sp.getSelectedItem() == null ? "" : sp.getSelectedItem().toString();
            boolean isWinlator = "WINLATOR".equals(engine) || isWinlatorPackageName(pkg.getText() == null ? "" : pkg.getText().toString());
            winlatorAdvancedLayout.setVisibility(isWinlator ? View.VISIBLE : View.GONE);
            gamehubLaunchLayout.setVisibility("GAMEHUB".equals(engine) ? View.VISIBLE : View.GONE);
        };
        btnResetEmulatorPackage.setOnClickListener(v -> {
            String engine = sp.getSelectedItem() == null ? "" : sp.getSelectedItem().toString();
            String defaultPkg = defaultEmulatorPackageForEngine(engine);
            pkg.setText(defaultPkg);
            if ("GAMEHUB".equals(engine)) gamehubLocalGameId.setText("");
            if ("ARTEMIS".equals(engine)) updateArtemisVersionButtons(defaultPkg, btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2);
            updateWinlatorAdvanced.run();
            Toast.makeText(this, defaultPkg.isEmpty() ? "已清空默认包名" : "已恢复默认包名：" + defaultPkg, Toast.LENGTH_SHORT).show();
        });

        pkg.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { updateWinlatorAdvanced.run(); }
            public void afterTextChanged(Editable e) {}
        });
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, new String[]{"AUTO", "KIRIKIRI", "ONS", "TYRANO", "ARTEMIS", "WINLATOR", "GAMEHUB", "UNKNOWN"});
        spAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        sp.setAdapter(spAdapter);
        ArrayAdapter<String> winlatorModeAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, new String[]{"启动到游戏", "启动到程序"});
        winlatorModeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        winlatorModeSp.setAdapter(winlatorModeAdapter);
        ArrayAdapter<String> gamehubModeAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, new String[]{"启动到游戏", "启动到程序"});
        gamehubModeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        gamehubModeSp.setAdapter(gamehubModeAdapter);
        List<String> launchOptions = buildLaunchOptions(pendingDirUri);
        ArrayAdapter<String> launchAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, launchOptions);
        launchAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        launchSp.setAdapter(launchAdapter);
        if (game != null) {
            tvPlayTimeInfo.setVisibility(View.VISIBLE);
            tvPlayTimeInfo.setText("总时长：" + TimeFormatUtil.playTime(game.totalPlayTime) + " / 最近游玩：" + TimeFormatUtil.date(game.lastPlayedAt));
        }
        btnArtemisAuto.setOnClickListener(v -> { pkg.setText(resolveArtemisPackageFromMarkers(pendingDirUri)); updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2); });
        btnArtemisStd.setOnClickListener(v -> { pkg.setText("internal.artemis"); updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2); });
        btnArtemisCompat.setOnClickListener(v -> { pkg.setText("internal.artemis.compat"); updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2); });
        btnArtemisCompatV2.setOnClickListener(v -> { pkg.setText("internal.artemis.compat.v2"); updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2); });
        if (game != null) {
            title.setText(game.title); pkg.setText(game.emulatorPackage); gamehubLocalGameId.setText(game.gamehubLocalGameId); updateWinlatorAdvanced.run(); desc.setText(game.description);
            winlatorModeSp.setSelection(winlatorModeIndex(game.winlatorLaunchMode));
            gamehubModeSp.setSelection(gamehubModeIndex(game.gamehubLaunchMode));
            sp.setSelection(engineIndex(game.engine));
            launchSp.setSelection(findLaunchSelection(launchOptions, game.launchTarget));
            ((TextView)d.findViewById(R.id.tvSelectedDir)).setText(emptyText(game.rootUri, "未选择游戏目录"));
            ((TextView)d.findViewById(R.id.tvSelectedCover)).setText(emptyText(game.coverUri, "未选择封面"));
            if (game.engine == EngineType.ARTEMIS) updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2);
        } else if (pendingDirUri != null) {
            ((TextView)d.findViewById(R.id.tvSelectedDir)).setText(pendingDirUri);
        }
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String engine = (String) sp.getSelectedItem();
                boolean isArtemis = "ARTEMIS".equals(engine);
                boolean isGameHub = "GAMEHUB".equals(engine);
                artemisVersionLayout.setVisibility(isArtemis ? View.VISIBLE : View.GONE);
                pkg.setVisibility(isArtemis ? View.GONE : View.VISIBLE);
                if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && "KIRIKIRI".equals(engine)) {
                    pkg.setText("internal.krkr");
                } else if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && "TYRANO".equals(engine)) {
                    pkg.setText("internal.tyrano");
                } else if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && "ONS".equals(engine)) {
                    pkg.setText("internal.ons");
                } else if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && "ARTEMIS".equals(engine)) {
                    pkg.setText("internal.artemis");
                } else if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && "WINLATOR".equals(engine)) {
                    pkg.setText(guessInstalledWinlatorPackage());
                } else if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && isGameHub) {
                    pkg.setText(guessInstalledGameHubPackage());
                }
                updateWinlatorAdvanced.run();
                if (isArtemis) updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        d.findViewById(R.id.btnPickDir).setOnClickListener(v -> editDirLauncher.launch(null));
        d.findViewById(R.id.btnPickCover).setOnClickListener(v -> coverLauncher.launch("image/*"));
        d.findViewById(R.id.btnCancel).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty()) { Toast.makeText(this, "请填写标题", Toast.LENGTH_SHORT).show(); return; }
            Game g = game == null ? new Game() : game;
            if ((pendingCoverUri == null || pendingCoverUri.isEmpty()) && pendingDirUri != null && !pendingDirUri.isEmpty()) {
                Uri autoCover = findFirstLevelImage(pendingDirUri);
                if (autoCover != null) pendingCoverUri = copyCoverToInternalStorage(autoCover);
            }
            g.title = title.getText().toString().trim(); g.rootUri = pendingDirUri == null ? "" : pendingDirUri; g.coverUri = pendingCoverUri; g.coverPersistUri = pendingCoverUri; g.coverSourceType = pendingCoverUri == null ? 0 : 1;
            g.engine = EngineType.fromString((String) sp.getSelectedItem()); if (g.engine == EngineType.AUTO) g.engine = EngineType.UNKNOWN;
            g.emulatorPackage = pkg.getText().toString().trim();
            g.gamehubLocalGameId = gamehubLocalGameId.getText().toString().trim();
            if (g.engine == EngineType.ARTEMIS) {
                g.emulatorPackage = normalizeArtemisPackage(g.emulatorPackage);
                if (!saveArtemisVersionMarker(g.rootUri, g.emulatorPackage)) {
                    Toast.makeText(this, "保存 Artemis 兼容标记失败", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (g.engine == EngineType.ONS && (g.emulatorPackage == null || g.emulatorPackage.trim().isEmpty())) g.emulatorPackage = "internal.ons";
            if (g.engine == EngineType.WINLATOR && (g.emulatorPackage == null || g.emulatorPackage.trim().isEmpty())) g.emulatorPackage = guessInstalledWinlatorPackage();
            if (g.engine == EngineType.GAMEHUB && (g.emulatorPackage == null || g.emulatorPackage.trim().isEmpty())) g.emulatorPackage = guessInstalledGameHubPackage();
            if (g.engine != EngineType.GAMEHUB) g.gamehubLocalGameId = "";
            g.winlatorLaunchMode = (g.engine == EngineType.WINLATOR || isWinlatorPackageName(g.emulatorPackage)) ? winlatorModeValue(winlatorModeSp.getSelectedItemPosition()) : "game";
            g.gamehubLaunchMode = g.engine == EngineType.GAMEHUB ? gamehubModeValue(gamehubModeSp.getSelectedItemPosition()) : "game";
            String selectedLaunchTarget = (String) launchSp.getSelectedItem();
            if (g.engine == EngineType.ARTEMIS || g.engine == EngineType.TYRANO) selectedLaunchTarget = "[游戏目录]";
            if (g.engine == EngineType.GAMEHUB) selectedLaunchTarget = "[GameHub]";
            g.launchTarget = selectedLaunchTarget;
            g.description = desc.getText().toString();
            if (game == null) repository.insert(g); else repository.update(g);
            d.dismiss(); loadGames();
        });
        d.setOnDismissListener(x -> pendingEditDialog = null);
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private String defaultEmulatorPackageForEngine(String engine) {
        String e = engine == null ? "" : engine.trim().toUpperCase(Locale.ROOT);
        if ("KIRIKIRI".equals(e)) return "internal.krkr";
        if ("TYRANO".equals(e)) return "internal.tyrano";
        if ("ONS".equals(e)) return "internal.ons";
        if ("ARTEMIS".equals(e)) return "internal.artemis";
        if ("WINLATOR".equals(e)) return guessInstalledWinlatorPackage();
        if ("GAMEHUB".equals(e)) return guessInstalledGameHubPackage();
        return "";
    }

    private void updateArtemisVersionButtons(String value, Button auto, Button std, Button compat, Button compatV2) {
String pkg = normalizeArtemisPackage(value);
boolean isCompat = "internal.artemis.compat".equalsIgnoreCase(pkg);
boolean isV2 = "internal.artemis.compat.v2".equalsIgnoreCase(pkg);
boolean isStd = "internal.artemis".equalsIgnoreCase(pkg);
boolean autoMode = false;
auto.setSelected(autoMode);
std.setSelected(isStd);
compat.setSelected(isCompat);
compatV2.setSelected(isV2);
        auto.setAlpha(auto.isSelected() ? 1f : 0.55f);
        std.setAlpha(std.isSelected() ? 1f : 0.55f);
        compat.setAlpha(compat.isSelected() ? 1f : 0.55f);
        compatV2.setAlpha(compatV2.isSelected() ? 1f : 0.55f);
    }

    private String normalizeArtemisPackage(String value) {
String pkg = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
if (pkg.contains("compat.v2") || pkg.contains("compatible_v2") || pkg.endsWith(".2")) return "internal.artemis.compat.v2";
if (pkg.contains("compat")) return "internal.artemis.compat";
return "internal.artemis";
}

private String resolveArtemisPackageFromMarkers(String rootUri) {
try {
DocumentFile dir = gameDir(rootUri);
if (dir != null) {
if (dir.findFile(".compatible_v2") != null || dir.findFile("compatible_v2.ini") != null) return "internal.artemis.compat.v2";
if (dir.findFile(".compatible") != null || dir.findFile("compatible.ini") != null) return "internal.artemis.compat";
}
} catch (Throwable ignored) { }
try {
String path = displayPath(rootUri);
if (path != null && path.startsWith("/")) {
if (new File(path, ".compatible_v2").exists() || new File(path, "compatible_v2.ini").exists()) return "internal.artemis.compat.v2";
if (new File(path, ".compatible").exists() || new File(path, "compatible.ini").exists()) return "internal.artemis.compat";
}
} catch (Throwable ignored) { }
return "internal.artemis";
}

private boolean saveArtemisVersionMarker(String rootUri, String artemisPackage) {
String mode = normalizeArtemisPackage(artemisPackage);
try {
DocumentFile dir = gameDir(rootUri);
if (dir != null) {
DocumentFile c1 = dir.findFile(".compatible");
DocumentFile c2 = dir.findFile(".compatible_v2");
DocumentFile i1 = dir.findFile("compatible.ini");
DocumentFile i2 = dir.findFile("compatible_v2.ini");
if ("internal.artemis".equals(mode)) return true;
if (c1 != null) c1.delete();
if (c2 != null) c2.delete();
if (i1 != null) i1.delete();
if (i2 != null) i2.delete();
if ("internal.artemis.compat".equals(mode)) return dir.createFile("application/octet-stream", ".compatible") != null;
if ("internal.artemis.compat.v2".equals(mode)) return dir.createFile("application/octet-stream", ".compatible_v2") != null;
return true;
}
} catch (Throwable ignored) { }
try {
String path = displayPath(rootUri);
if (path == null || !path.startsWith("/")) return false;
File c1 = new File(path, ".compatible");
File c2 = new File(path, ".compatible_v2");
File i1 = new File(path, "compatible.ini");
File i2 = new File(path, "compatible_v2.ini");
if ("internal.artemis".equals(mode)) return true;
deleteFileQuietly(c1);
deleteFileQuietly(c2);
deleteFileQuietly(i1);
deleteFileQuietly(i2);
if ("internal.artemis.compat".equals(mode)) return c1.exists() || c1.createNewFile();
if ("internal.artemis.compat.v2".equals(mode)) return c2.exists() || c2.createNewFile();
return true;
} catch (Throwable ignored) {
return false;
}
}

private DocumentFile gameDir(String rootUri) {
if (rootUri == null || rootUri.trim().isEmpty()) return null;
if (rootUri.startsWith("/") || rootUri.startsWith("file://")) {
File file = new File(rootUri.startsWith("file://") ? Uri.parse(rootUri).getPath() : rootUri);
return DocumentFile.fromFile(file);
}
return DocumentFile.fromTreeUri(this, Uri.parse(rootUri));
}

private void deleteFileQuietly(File file) {
try {
if (file != null && file.exists()) file.delete();
} catch (Throwable ignored) { }
}

private void showEditPlayTimeDialog(Game game) {
        if (game == null || game.id <= 0) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, dp(10));

        TextView info = new TextView(this);
        info.setText("当前总时长：" + TimeFormatUtil.playTime(game.totalPlayTime) + "\n最近游玩：" + TimeFormatUtil.date(game.lastPlayedAt));
        info.setTextColor(getColorCompat(R.color.yh_text_muted));
        info.setTextSize(12);
        info.setLineSpacing(dp(2), 1.0f);
        root.addView(info);

        TextView totalLabel = new TextView(this);
        totalLabel.setText("\n设置新的总时长");
        totalLabel.setTextColor(getColorCompat(R.color.yh_text));
        totalLabel.setTextSize(14);
        totalLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(totalLabel);

        EditText totalInput = krEdit("例如 3h 20m / 200m / 7200s / 2.5h", TimeFormatUtil.playTime(game.totalPlayTime));
        totalInput.setText(parseDurationForEdit(game.totalPlayTime));
        root.addView(totalInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView addLabel = new TextView(this);
        addLabel.setText("\n追加游玩时长");
        addLabel.setTextColor(getColorCompat(R.color.yh_text));
        addLabel.setTextSize(14);
        addLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(addLabel);

        EditText addInput = krEdit("例如 30m / 1h30m / 0.5h", "");
        root.addView(addInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView hint = new TextView(this);
        hint.setText("说明：上面的“总时长”会直接覆盖该游戏的累计时长；“追加游玩时长”会在当前基础上增加。两者可以二选一，也可以都填。");
        hint.setTextColor(getColorCompat(R.color.yh_text_muted));
        hint.setTextSize(11);
        hint.setLineSpacing(dp(2), 1.0f);
        hint.setPadding(0, dp(8), 0, 0);
        root.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("修改游玩时长")
                .setView(root)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .show();
        styleAlertDialogDark(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.52f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Long totalMinutes = parseDurationToMinutes(totalInput.getText() == null ? "" : totalInput.getText().toString().trim());
            Long addMinutes = parseDurationToMinutes(addInput.getText() == null ? "" : addInput.getText().toString().trim());
            if ((totalMinutes == null || totalMinutes < 0) && (addMinutes == null || addMinutes <= 0)) {
                Toast.makeText(this, "请填写有效的时长", Toast.LENGTH_SHORT).show();
                return;
            }
            long currentDuration = Math.max(0L, game.totalPlayTime);
            long finalDuration = currentDuration;
            if (totalMinutes != null && totalMinutes >= 0) {
                finalDuration = totalMinutes * 60_000L;
            }
            if (addMinutes != null && addMinutes > 0) {
                finalDuration += addMinutes * 60_000L;
            }
            repository.setManualPlayTimeForGame(game.id, finalDuration);
            Toast.makeText(this, "游玩时长已更新", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            loadGames();
            updateSideDetail(game);
            updateProfilePanel();
        });
    }

    private Long parseDurationToMinutes(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        try {
            if (s.matches("^\\d+$")) return Long.parseLong(s);
            long totalMs = 0L;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])").matcher(s);
            boolean matched = false;
            while (m.find()) {
                matched = true;
                double value = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                if ("d".equals(unit)) totalMs += (long) (value * 24d * 60d * 60d * 1000d);
                else if ("h".equals(unit)) totalMs += (long) (value * 60d * 60d * 1000d);
                else if ("m".equals(unit)) totalMs += (long) (value * 60d * 1000d);
                else if ("s".equals(unit)) totalMs += (long) (value * 1000d);
            }
            if (!matched) return null;
            return Math.max(0L, totalMs / 60_000L);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String parseDurationForEdit(long durationMs) {
        if (durationMs <= 0) return "0m";
        long minutes = durationMs / 60_000L;
        long hours = minutes / 60L;
        long remain = minutes % 60L;
        if (hours <= 0) return remain + "m";
        if (remain <= 0) return hours + "h";
        return hours + "h" + remain + "m";
    }

    private int engineIndex(EngineType e) { if (e == EngineType.KIRIKIRI) return 1; if (e == EngineType.ONS) return 2; if (e == EngineType.TYRANO) return 3; if (e == EngineType.ARTEMIS) return 4; if (e == EngineType.WINLATOR) return 5; if (e == EngineType.GAMEHUB) return 6; if (e == EngineType.UNKNOWN) return 7; return 0; }

    private boolean isWinlatorPackageName(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        return p.equals("com.winlator")
                || p.startsWith("com.winlator.")
                || p.contains("winlator")
                || p.contains("glibc")
                || p.contains("proot")
                || p.contains("mobox")
                || p.contains("winalator");
    }

    private int winlatorModeIndex(String mode) {
        String m = mode == null ? "game" : mode.trim().toLowerCase(Locale.ROOT);
        if ("program".equals(m) || "normal".equals(m)) return 1;
        return 0;
    }

    private String winlatorModeValue(int index) {
        if (index == 1) return "program";
        return "game";
    }

    private int gamehubModeIndex(String mode) {
        String m = mode == null ? "game" : mode.trim().toLowerCase(Locale.ROOT);
        if ("program".equals(m) || "normal".equals(m)) return 1;
        return 0;
    }

    private String gamehubModeValue(int index) {
        if (index == 1) return "program";
        return "game";
    }

    private void showKrSettingsDialog(Game game) {
        if (game == null || game.rootUri == null || game.rootUri.isEmpty()) {
            Toast.makeText(this, "请先选择游戏目录", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, String> prefs = loadKrPrefs(game.rootUri);
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(getColorCompat(com.yuki.yukihub.R.color.yh_card));
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("KR 游戏设置");
        title.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        title.setTextSize(22);
        title.setPadding(0, 0, 0, pad / 2);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);

        CheckBox outputLog = krCheckBox("打印日志", "1".equals(pref(prefs, "outputlog", "1")));
        CheckBox showFps = krCheckBox("显示 FPS", "1".equals(pref(prefs, "showfps", "0")));
        CheckBox keepScreen = krCheckBox("保持屏幕常亮", "1".equals(pref(prefs, "keep_screen_alive", "1")));
        CheckBox forceFont = krCheckBox("强制使用默认字体", "1".equals(pref(prefs, "force_default_font", "0")));
        CheckBox textureCompress = krCheckBox("纹理压缩", "1".equals(pref(prefs, "texture_compress", "0")));
        Spinner renderer = krSpinner(new String[]{"软件渲染器", "OpenGL（试验性）"}, rendererToLabel(pref(prefs, "renderer", "software")));
        Spinner memusage = krSpinner(new String[]{"unlimited", "low", "medium", "high"}, pref(prefs, "memusage", "unlimited"));
        Spinner renderThread = krSpinner(new String[]{"auto", "1", "2", "3", "4", "6", "8"}, pref(prefs, "render_thread", "auto"));
        EditText fpsLimit = krEdit("FPS 限制，例如 60", pref(prefs, "fps_limit", "60"));
        fpsLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText menuOpa = krEdit("手柄/菜单透明度，例如 0.15", pref(prefs, "menu_handler_opa", "0.15"));
        menuOpa.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText cursorScale = krEdit("虚拟光标缩放，例如 0.5", pref(prefs, "vcursor_scale", "0.5"));
        cursorScale.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText defaultFont = krEdit("默认字体路径，留空使用内置字体", pref(prefs, "default_font", ""));

        root.addView(krLabel("图形渲染器")); root.addView(renderer);
        root.addView(krLabel("内存用量")); root.addView(memusage);
        root.addView(krLabel("渲染线程数")); root.addView(renderThread);
        root.addView(krLabel("限制 FPS")); root.addView(fpsLimit);
        root.addView(krLabel("手柄/菜单透明度")); root.addView(menuOpa);
        root.addView(krLabel("虚拟光标缩放")); root.addView(cursorScale);
        root.addView(outputLog);
        root.addView(showFps);
        root.addView(keepScreen);
        root.addView(textureCompress);
        root.addView(forceFont);
        root.addView(krLabel("默认字体路径")); root.addView(defaultFont);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, pad / 2, 0, 0);
        Button cancel = krButton("取消");
        Button save = krButton("保存");
        actions.addView(cancel, new LinearLayout.LayoutParams(0, (int) (44 * getResources().getDisplayMetrics().density), 1));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, (int) (44 * getResources().getDisplayMetrics().density), 1);
        saveLp.leftMargin = pad / 2;
        actions.addView(save, saveLp);

        scroll.addView(root);
        panel.addView(title);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        panel.addView(actions);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            prefs.put("menu_handler_opa", menuOpa.getText().toString().trim().isEmpty() ? "0.15" : menuOpa.getText().toString().trim());
            prefs.put("vcursor_scale", cursorScale.getText().toString().trim().isEmpty() ? "0.5" : cursorScale.getText().toString().trim());
            prefs.put("renderer", rendererFromLabel(String.valueOf(renderer.getSelectedItem())));
            prefs.put("memusage", String.valueOf(memusage.getSelectedItem()));
            prefs.put("render_thread", String.valueOf(renderThread.getSelectedItem()));
            prefs.put("fps_limit", fpsLimit.getText().toString().trim().isEmpty() ? "60" : fpsLimit.getText().toString().trim());
            prefs.put("outputlog", outputLog.isChecked() ? "1" : "0");
            prefs.put("showfps", showFps.isChecked() ? "1" : "0");
            prefs.put("keep_screen_alive", keepScreen.isChecked() ? "1" : "0");
            prefs.put("texture_compress", textureCompress.isChecked() ? "1" : "0");
            prefs.put("force_default_font", forceFont.isChecked() ? "1" : "0");
            prefs.put("default_font", defaultFont.getText().toString().trim());
            if (saveKrPrefs(game.rootUri, prefs)) {
                Toast.makeText(this, "KR 设置已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } else {
                Toast.makeText(this, "保存 KR 设置失败", Toast.LENGTH_LONG).show();
            }
        });
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            shownWindow.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private void showOnsSettingsDialog(Game game) {
        OnsSettings settings = OnsSettings.load(this);
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(getColorCompat(com.yuki.yukihub.R.color.yh_card));
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ONScripter 设置");
        title.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        title.setTextSize(22);
        title.setPadding(0, 0, 0, pad / 2);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        CheckBox stretchFull = krCheckBox("拉伸全屏（--fullscreen2）", settings.stretchFull);
        CheckBox ignoreCutout = krCheckBox("忽略刘海/挖孔区域", settings.ignoreCutout);
        CheckBox disableVideo = krCheckBox("禁用视频播放（--no-video）", settings.disableVideo);
        CheckBox scopedSave = krCheckBox("使用 YukiHub 独立存档目录", settings.scopedSaveDir);
        CheckBox allowEditArgs = krCheckBox("允许在详情中编辑启动参数", settings.allowEditArgs);
        CheckBox sharpness = krCheckBox("启用锐化（--sharpness）", settings.sharpness);
        EditText sharpnessValue = krEdit("锐化值，例如 2", settings.sharpnessValue);
        sharpnessValue.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner encoding = krSpinner(new String[]{"gbk", "sjis", "utf8"}, settings.encoding);

        root.addView(krLabel("文本编码")); root.addView(encoding);
        root.addView(stretchFull);
        root.addView(ignoreCutout);
        root.addView(disableVideo);
        root.addView(scopedSave);
        root.addView(allowEditArgs);
        root.addView(sharpness);
        root.addView(krLabel("锐化值")); root.addView(sharpnessValue);

        TextView tip = krLabel("说明：设置会生成 OnsYuri 原版参数：--root、--font、--fullscreen/--fullscreen2、--enc、--save-dir 等。修改后下次启动 ONS 游戏生效。");
        tip.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text_muted));
        root.addView(tip);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, pad / 2, 0, 0);
        Button cancel = krButton("取消");
        Button save = krButton("保存");
        actions.addView(cancel, new LinearLayout.LayoutParams(0, (int) (44 * getResources().getDisplayMetrics().density), 1));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, (int) (44 * getResources().getDisplayMetrics().density), 1);
        saveLp.leftMargin = pad / 2;
        actions.addView(save, saveLp);

        scroll.addView(root);
        panel.addView(title);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        panel.addView(actions);
        dialog.setContentView(panel);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            settings.stretchFull = stretchFull.isChecked();
            settings.ignoreCutout = ignoreCutout.isChecked();
            settings.disableVideo = disableVideo.isChecked();
            settings.scopedSaveDir = scopedSave.isChecked();
            settings.allowEditArgs = allowEditArgs.isChecked();
            settings.sharpness = sharpness.isChecked();
            settings.sharpnessValue = sharpnessValue.getText().toString().trim().isEmpty() ? "2" : sharpnessValue.getText().toString().trim();
            settings.encoding = OnsSettings.normalizeEncoding(String.valueOf(encoding.getSelectedItem()));
            settings.save(this);
            if (game != null && (game.emulatorPackage == null || game.emulatorPackage.trim().isEmpty())) {
                game.emulatorPackage = "internal.ons";
                repository.update(game);
                loadGames();
            }
            Toast.makeText(this, "ONS 设置已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            shownWindow.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private TextView krLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        v.setPadding(0, 10, 0, 4);
        return v;
    }

    private CheckBox krCheckBox(String text, boolean checked) {
        CheckBox v = new CheckBox(this);
        v.setText(text);
        v.setChecked(checked);
        v.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        v.setButtonTintList(android.content.res.ColorStateList.valueOf(getColorCompat(com.yuki.yukihub.R.color.yh_primary)));
        return v;
    }

    private Button krButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        b.setBackgroundColor(getColorCompat(com.yuki.yukihub.R.color.yh_card_2));
        return b;
    }

    private LinearLayout linkCardButton(String text, int iconResId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackgroundResource(R.drawable.bg_auth_tab_inactive);
        row.setMinimumHeight(dp(48));
        ImageView icon = new ImageView(this);
        try {
            Drawable d = getDrawable(iconResId);
            if (d != null) {
                d = d.mutate();
                d.setTint(getColorCompat(com.yuki.yukihub.R.color.yh_primary));
                icon.setImageDrawable(d);
            }
        } catch (Throwable ignored) { }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.rightMargin = dp(10);
        row.addView(icon, iconLp);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        label.setTextSize(14);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        row.addView(label, labelLp);
        return row;
    }

    private EditText krEdit(String hint, String value) {
        EditText v = new EditText(this);
        v.setHint(hint);
        v.setSingleLine(true);
        v.setText(value);
        v.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        v.setHintTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text_muted));
        v.setBackgroundColor(getColorCompat(com.yuki.yukihub.R.color.yh_card_2));
        v.setPadding(12, 0, 12, 0);
        return v;
    }

    private Spinner krSpinner(String[] values, String selected) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item_dark, values);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_dark);
        sp.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(selected)) { sp.setSelection(i); break; }
        return sp;
    }

    private int getColorCompat(int id) {
        if (Build.VERSION.SDK_INT >= 23) return getColor(id);
        return getResources().getColor(id);
    }

    private String rendererToLabel(String value) {
        if (value == null) return "软件渲染器";
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("opengl".equals(v) || "open_gl".equals(v) || "gl".equals(v) || "hardware".equals(v)) return "OpenGL（试验性）";
        return "软件渲染器";
    }

    private String rendererFromLabel(String label) {
if (label != null && label.toLowerCase(Locale.ROOT).contains("opengl")) return "opengl";
return "software";
}

private String krEngineVersionToLabel(String value) {
String mode = normalizeKrEngineVersion(value);
if ("1.3.4".equals(mode)) return "1.3.4";
if ("1.3.9".equals(mode)) return "1.3.9";
return "自动";
}

private String krEngineVersionFromLabel(String label) {
if (label == null) return "auto";
String v = label.trim();
if (v.contains("1.3.4")) return "1.3.4";
if (v.contains("1.3.9")) return "1.3.9";
return "auto";
}

private String normalizeKrEngineVersion(String value) {
String v = value == null ? "auto" : value.trim().toLowerCase(Locale.ROOT);
if ("134".equals(v) || "1.3.4".equals(v) || "kr134".equals(v)) return "1.3.4";
if ("139".equals(v) || "1.3.9".equals(v) || "kr139".equals(v)) return "1.3.9";
return "auto";
}

private String pref(Map<String, String> prefs, String key, String def) {
        String v = prefs.get(key);
        return v == null ? def : v;
    }

    private List<String> buildLaunchOptions(String rootUri) {
        List<String> options = new ArrayList<>();
        if (rootUri != null && !rootUri.isEmpty()) {
            String directPath = displayPath(rootUri);
            if (directPath != null && directPath.toLowerCase(Locale.ROOT).endsWith(".desktop")) {
                String name = directPath.substring(Math.max(directPath.lastIndexOf('/'), directPath.lastIndexOf('\\')) + 1);
                if (!name.isEmpty() && !options.contains(name)) options.add(name);
            }
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(rootUri));
                if (dir != null && dir.isDirectory()) {
                    DocumentFile[] files = dir.listFiles();
                    if (files != null) {
                        for (DocumentFile file : files) {
                            String name = file.getName();
                            if (name == null || !file.isFile()) continue;
                            String lower = name.toLowerCase(Locale.ROOT);
                            if (lower.endsWith(".xp3") || lower.endsWith(".tjs") || lower.endsWith(".ks") || lower.endsWith(".html") || lower.endsWith(".txt") || lower.endsWith(".dat") || lower.endsWith(".pfs") || lower.endsWith(".desktop")) {
                                if (!options.contains(name)) options.add(name);
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        if (options.contains("data.xp3")) {
            options.remove("data.xp3");
            options.add(0, "data.xp3");
        }
        if (options.contains("[游戏目录]")) options.remove("[游戏目录]");
        options.add("[游戏目录]");
        if (options.isEmpty()) options.add("未扫描到可启动文件，请先选择目录");
        return options;
    }

    private int findLaunchSelection(List<String> options, String target) {
        if (options == null || options.isEmpty()) return 0;
        if (target == null || target.trim().isEmpty()) target = "[游戏目录]";
        for (int i = 0; i < options.size(); i++) {
            if (target.equals(options.get(i))) return i;
        }
        int dirIndex = options.indexOf("[游戏目录]");
        return dirIndex >= 0 ? dirIndex : 0;
    }

    private Map<String, String> loadKrPrefs(String rootUri) {
        Map<String, String> prefs = defaultKrPrefs();
        try (InputStream in = openKrPrefsInput(rootUri)) {
            if (in == null) return prefs;
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            NodeList items = doc.getElementsByTagName("Item");
            for (int i = 0; i < items.getLength(); i++) {
                if (!(items.item(i) instanceof Element)) continue;
                Element item = (Element) items.item(i);
                String key = item.getAttribute("key");
                if (key == null || key.isEmpty()) continue;
                prefs.put(key, item.getAttribute("value"));
            }
        } catch (Throwable ignored) { }
        return prefs;
    }

    private Map<String, String> defaultKrPrefs() {
        Map<String, String> prefs = new LinkedHashMap<>();
        prefs.put("menu_handler_opa", "0.15");
        prefs.put("vcursor_scale", "0.5");
        prefs.put("force_default_font", "0");
        prefs.put("default_font", "");
        prefs.put("renderer", "software");
        prefs.put("memusage", "unlimited");
        prefs.put("render_thread", "auto");
        prefs.put("texture_compress", "0");
        prefs.put("fps_limit", "60");
        prefs.put("keep_screen_alive", "1");
        prefs.put("showfps", "0");
        prefs.put("outputlog", "1");
        return prefs;
    }

    // KR 引擎版本只保留全局设置，不再通过单个游戏目录的 .1.3.4 标记读写，避免与右上角设置冲突。

    private DocumentFile krGameDir(String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return null;
        if (rootUri.startsWith("/") || rootUri.startsWith("file://")) {
            File file = new File(rootUri.startsWith("file://") ? Uri.parse(rootUri).getPath() : rootUri);
            return DocumentFile.fromFile(file);
        }
        return DocumentFile.fromTreeUri(this, Uri.parse(rootUri));
    }

    private InputStream openKrPrefsInput(String rootUri) {
        try {
            if (rootUri == null || rootUri.isEmpty()) return null;
            if (rootUri.startsWith("/") || rootUri.startsWith("file://")) {
                File f = new File(rootUri.startsWith("file://") ? Uri.parse(rootUri).getPath() : rootUri, "Kirikiroid2Preference.xml");
                return f.exists() ? new FileInputStream(f) : null;
            }
            DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(rootUri));
            DocumentFile file = dir == null ? null : dir.findFile("Kirikiroid2Preference.xml");
            return file == null || !file.isFile() ? null : getContentResolver().openInputStream(file.getUri());
        } catch (Throwable ignored) { return null; }
    }

    private boolean saveKrPrefs(String rootUri, Map<String, String> prefs) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("GlobalPreference");
            doc.appendChild(root);
            for (Map.Entry<String, String> e : prefs.entrySet()) {
                Element item = doc.createElement("Item");
                item.setAttribute("key", e.getKey());
                item.setAttribute("value", e.getValue() == null ? "" : e.getValue());
                root.appendChild(item);
            }
            try (OutputStream out = openKrPrefsOutput(rootUri)) {
                if (out == null) return false;
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.transform(new DOMSource(doc), new StreamResult(out));
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private OutputStream openKrPrefsOutput(String rootUri) {
        try {
            if (rootUri == null || rootUri.isEmpty()) return null;
            if (rootUri.startsWith("/") || rootUri.startsWith("file://")) {
                File dir = new File(rootUri.startsWith("file://") ? Uri.parse(rootUri).getPath() : rootUri);
                if (!dir.exists() && !dir.mkdirs()) return null;
                return new FileOutputStream(new File(dir, "Kirikiroid2Preference.xml"));
            }
            DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(rootUri));
            if (dir == null || !dir.isDirectory()) return null;
            DocumentFile file = dir.findFile("Kirikiroid2Preference.xml");
            if (file == null) file = dir.createFile("text/xml", "Kirikiroid2Preference.xml");
            return file == null ? null : getContentResolver().openOutputStream(file.getUri(), "wt");
        } catch (Throwable ignored) { return null; }
    }
private void showScanResults(List<ScanResult> results) {
        if (results.isEmpty()) { Toast.makeText(this, "未发现子目录候选游戏", Toast.LENGTH_LONG).show(); return; }
        Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setContentView(R.layout.dialog_scan_result);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        RecyclerView rv = d.findViewById(R.id.recyclerScanResults); rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new ScanResultAdapter(results));
        ((TextView)d.findViewById(R.id.tvScanTitle)).setText("扫描结果：" + results.size() + " 个候选游戏");
        d.findViewById(R.id.btnCancelScan).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.btnImportScan).setOnClickListener(v -> {
            ScanImportStats stats = importScannedGames(results);
            if (stats.added > 0) AppExecutors.runOnIo(() -> autoMatchVndbForImportedGames(stats.importedGames));
            d.dismiss();
            loadGames();
            Toast.makeText(this, "新增 " + stats.added + " 个，已存在 " + stats.skipped + " 个" + (stats.added > 0 ? "，正在自动匹配 VNDB 封面" : ""), Toast.LENGTH_SHORT).show();
        });
        d.show();
    }

    private void runLibraryScan(List<String> rootUris, boolean showToast) {
if (rootUris == null || rootUris.isEmpty()) return;
if (autoLibraryScanRunning) return;
autoLibraryScanRunning = true;
runOnUiThread(() -> setScanLoading(true));
if (showToast) Toast.makeText(this, "正在扫描 " + rootUris.size() + " 个目录，请稍候...", Toast.LENGTH_SHORT).show();
        int scanDepth = prefs == null ? DEFAULT_STARTUP_SCAN_DEPTH : prefs.getInt(KEY_STARTUP_SCAN_DEPTH, DEFAULT_STARTUP_SCAN_DEPTH);
        scanDepth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, scanDepth));
        final int finalScanDepth = scanDepth;
        final List<String> scanRoots = new ArrayList<>(rootUris);
        AppExecutors.runOnSingle(() -> {
            List<ScanResult> results = new ArrayList<>();
            for (String root : scanRoots) {
                if (root == null || root.trim().isEmpty()) continue;
                try {
                    results.addAll(GameScanner.scan(this, Uri.parse(root), finalScanDepth));
                } catch (Throwable t) {
                    Log.w("YukiHub", "library scan failed root=" + root, t);
                }
            }
            ScanImportStats stats = importScannedGames(results);
            if (stats.added > 0) AppExecutors.runOnIo(() -> autoMatchVndbForImportedGames(stats.importedGames));
            runOnUiThread(() -> {
                autoLibraryScanRunning = false;
                setScanLoading(false);
                loadGames();
                if (showToast) Toast.makeText(this, "扫描 " + scanRoots.size() + " 个目录：新增 " + stats.added + " 个，已存在 " + stats.skipped + " 个" + (stats.added > 0 ? "，正在自动匹配 VNDB 封面" : ""), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void runLibraryScan(Uri rootUri, boolean showToast) {
        if (rootUri == null) return;
        List<String> roots = new ArrayList<>();
        roots.add(rootUri.toString());
        runLibraryScan(roots, showToast);
    }

    private void scanLastRootOrChoose() {
        List<String> roots = getScanRootUris();
        if (roots.isEmpty()) {
            launchScanRootPicker(-1);
            return;
        }
        runLibraryScan(roots, true);
    }

    private void autoScanLastRootIfAvailable() {
        List<String> roots = getScanRootUris();
        if (roots.isEmpty()) return;
        runLibraryScan(roots, false);
    }

    private void setScanButtonLoadingState(boolean loading) {
        setScanLoading(loading);
    }

    private String defaultLaunchTargetForEngine(EngineType engine) {
        if (engine == EngineType.TYRANO || engine == EngineType.ARTEMIS || engine == EngineType.KIRIKIRI) return "[游戏目录]";
        if (engine == EngineType.GAMEHUB) return "[GameHub]";
        return "[游戏目录]";
    }

    private void autoMatchVndbForImportedGames(List<Game> games) {
        if (games == null || games.isEmpty()) return;
        int changed = 0;
        for (Game g : games) {
            if (g == null || g.id <= 0 || g.title == null || g.title.trim().isEmpty()) continue;
            try {
                List<VnMetadata> candidates = VndbClient.searchCandidates(g.title, 1);
                if (candidates == null || candidates.isEmpty()) continue;
                VnMetadata meta = candidates.get(0);
                if (metadataRepository != null) metadataRepository.saveVndb(g.id, meta);
                boolean updated = false;
                if (!hasCover(g) && meta.coverUrl != null && !meta.coverUrl.isEmpty()) {
                    String cover = cacheRemoteImageSync(meta.coverUrl, "scan_cover_" + emptyText(meta.id, String.valueOf(g.id)));
                    if (cover != null && !cover.isEmpty()) {
                        g.coverUri = cover;
                        g.coverPersistUri = cover;
                        g.coverSourceType = 1;
                        updated = true;
                    }
                }
                if (updated) {
                    repository.update(g);
                    changed++;
                }
            } catch (Throwable t) {
                Log.w("YukiHub", "auto VNDB match failed: " + g.title, t);
            }
        }
        int finalChanged = changed;
        if (finalChanged > 0) runOnUiThread(() -> {
            loadGames();
            Toast.makeText(this, "已自动补全 " + finalChanged + " 个 VNDB 封面", Toast.LENGTH_SHORT).show();
        });
    }

    private boolean isDesktopLaunchTarget(String target) {
        return target != null && target.trim().toLowerCase(Locale.ROOT).endsWith(".desktop");
    }

    private String guessInstalledGameHubPackage() {
        try {
            PackageManager pm = getPackageManager();
            if (pm.getLaunchIntentForPackage("com.xiaoji.egggamz") != null) return "com.xiaoji.egggamz";
            if (pm.getLaunchIntentForPackage("com.xiaoji.egggame") != null) return "com.xiaoji.egggame";
        } catch (Throwable ignored) { }
        return "com.xiaoji.egggamz";
    }

private String guessInstalledWinlatorPackage() {
try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            String fallback = "";
            for (ApplicationInfo app : apps) {
                if (app == null || app.packageName == null) continue;
                String pkg = app.packageName.toLowerCase(Locale.ROOT);
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(app)).toLowerCase(Locale.ROOT); } catch (Throwable ignored) { }
                boolean hit = pkg.contains("winlator") || label.contains("winlator") || pkg.contains("glibc") || pkg.contains("proot");
                if (!hit) continue;
                if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;
                if (pkg.contains("cmod")) return app.packageName;
                if (fallback.isEmpty()) fallback = app.packageName;
            }
            return fallback;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private ScanImportStats importScannedGames(List<ScanResult> results) {
        ScanImportStats stats = new ScanImportStats();
        if (results == null || results.isEmpty()) return stats;
        Set<String> existing = repository.getRootUriKeySet();
        for (ScanResult r : results) {
            if (r == null || r.uri == null || r.uri.trim().isEmpty()) continue;
            String rootKey = GameRepository.normalizeRootUriKey(r.uri);
            if (existing.contains(rootKey)) {
                stats.skipped++;
                continue;
            }
            Game g = new Game();
            g.title = r.title;
            g.rootUri = r.uri;
            g.engine = r.engine;
            g.launchTarget = (r.launchTarget == null || r.launchTarget.trim().isEmpty()) ? defaultLaunchTargetForEngine(r.engine) : r.launchTarget;
            String cover = null;
            if (r.coverUri != null && !r.coverUri.trim().isEmpty()) {
                cover = copyCoverToInternalStorage(Uri.parse(r.coverUri));
            }
            if (cover == null || cover.isEmpty()) {
                Uri autoCover = findFirstLevelImage(r.uri);
                if (autoCover != null) cover = copyCoverToInternalStorage(autoCover);
            }
            if (cover != null) {
                g.coverUri = cover;
                g.coverPersistUri = cover;
                g.coverSourceType = 1;
            }
            if (r.engine == EngineType.KIRIKIRI) g.emulatorPackage = "internal.krkr";
            if (r.engine == EngineType.ONS) g.emulatorPackage = "internal.ons";
            if (r.engine == EngineType.TYRANO) g.emulatorPackage = "internal.tyrano";
            if (r.engine == EngineType.ARTEMIS) g.emulatorPackage = resolveArtemisPackageFromMarkers(g.rootUri);
            if (isDesktopLaunchTarget(g.launchTarget)) g.emulatorPackage = guessInstalledWinlatorPackage();
            long newId = repository.insertIfNotExists(g);
            if (newId > 0) {
                g.id = newId;
                existing.add(rootKey);
                stats.added++;
                stats.importedGames.add(g);
            } else {
                stats.skipped++;
            }
        }
        return stats;
    }

    private static class ScanImportStats {
        int added;
        int skipped;
        final List<Game> importedGames = new ArrayList<>();
    }

    private void launchGame(Game game) {
        lastStorageProbeResult = null;
        lastStorageProbeAt = 0L;
        if (shouldProbeStorageBeforeLaunch(game)) {
            launchGameWithStorageProbe(game);
            return;
        }
        doLaunchGame(game);
    }

    private void doLaunchGame(Game game) {
        String emulatorPackage = game.emulatorPackage == null ? "" : game.emulatorPackage.trim();
        if (emulatorPackage.isEmpty() && game.engine == EngineType.KIRIKIRI) emulatorPackage = "internal.krkr";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ONS) emulatorPackage = "internal.ons";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.TYRANO) emulatorPackage = "internal.tyrano";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.WINLATOR) emulatorPackage = guessInstalledWinlatorPackage();
        if (emulatorPackage.isEmpty() && game.engine == EngineType.GAMEHUB) emulatorPackage = guessInstalledGameHubPackage();
        if (game.engine == EngineType.ARTEMIS) {
            emulatorPackage = normalizeArtemisPackage(emulatorPackage);
        }
        String launchTarget = game.launchTarget;
        if (game.engine == EngineType.ARTEMIS || game.engine == EngineType.TYRANO) launchTarget = "[游戏目录]";
        if (game.engine == EngineType.GAMEHUB) {
            String ghMode = game.gamehubLaunchMode == null ? "game" : game.gamehubLaunchMode.trim().toLowerCase(Locale.ROOT);
            if (!("program".equals(ghMode) || "normal".equals(ghMode)) && (game.gamehubLocalGameId == null || game.gamehubLocalGameId.trim().isEmpty())) { Toast.makeText(this, "请先编辑游戏，通过Shizuku导入GameHub localGameId。", Toast.LENGTH_LONG).show(); return; }
            launchTarget = game.title;
        }
        if (emulatorPackage.isEmpty()) { Toast.makeText(this, "请先编辑游戏，填写模拟器包名。", Toast.LENGTH_LONG).show(); return; }
        runningGameId = game.id;
        sessionStart = System.currentTimeMillis();
        String launchType = resolveLaunchType(emulatorPackage);
        runningSessionId = repository.startPlaySession(game.id, sessionStart, launchType);
        launchedExternal = true;
        if (!launchGameInternal(game, emulatorPackage, launchTarget)) {
            repository.cancelPlaySession(runningSessionId);
            launchedExternal = false;
            runningGameId = -1;
            runningSessionId = -1;
            sessionStart = 0;
            Toast.makeText(this, "启动失败：未找到该模拟器，或该模拟器不接受当前启动目标", Toast.LENGTH_LONG).show();
        }
    }

    private boolean shouldProbeStorageBeforeLaunch(Game game) {
        if (game == null || game.rootUri == null || game.rootUri.trim().isEmpty()) return false;
        // If the user explicitly enabled YukiHub/app-private save redirection,
        // keep the old scoped-save path and do not enable the SAF file fallback hook.
        if (isScopedSaveEnabledFor(game.engine)) return false;
        if (game.engine == EngineType.KIRIKIRI) return true;
        if (game.engine == EngineType.ARTEMIS) return true;
        return false;
    }

    private void launchGameWithStorageProbe(Game game) {
        final Game target = game;
        final AtomicBoolean launched = new AtomicBoolean(false);
        Future<?> future = AppExecutors.io().submit(() -> {
            StorageProbeResult result = probeGameStorage(target);
            Log.i("YukiStorageProbe", result.toLogLine());
            runOnUiThread(() -> {
                if (!launched.compareAndSet(false, true)) return;
                handleStorageProbeResultBeforeLaunch(target, result);
            });
        });
        AppExecutors.schedule(() -> runOnUiThread(() -> {
            if (!launched.compareAndSet(false, true)) return;
            Log.w("YukiStorageProbe", "probe timeout after " + STORAGE_PROBE_TIMEOUT_MS + "ms root=" + (target == null ? null : target.rootUri));
            try { future.cancel(true); } catch (Throwable ignored) { }
            doLaunchGame(target);
        }), STORAGE_PROBE_TIMEOUT_MS);
    }

    private void handleStorageProbeResultBeforeLaunch(Game game, StorageProbeResult result) {
        lastStorageProbeResult = result;
        lastStorageProbeAt = System.currentTimeMillis();
        if (game == null) return;
        if (result != null && result.rawResolved && !result.rawWriteOk) {
            boolean scopedEnabled = isScopedSaveEnabledFor(game.engine);
            boolean safCanHandle = canUseKrSafFileFallback(game, result);
            String engine = game.engine == null ? "引擎" : game.engine.getDisplayName();
            Log.w("YukiStorageProbe", "raw write unavailable for " + engine + ", scopedSaveEnabled=" + scopedEnabled + ", safCanHandle=" + safCanHandle + ", rawPath=" + result.rawPath + ", err=" + result.writeError + ", safErr=" + result.safError);
            if (!scopedEnabled && !safCanHandle) {
                Toast.makeText(this, "检测到游戏目录可能无法写入，若闪退或无法存档，请在设置中开启" + engine + "独立存档目录。", Toast.LENGTH_LONG).show();
            }
        }
        if (result != null && result.rawResolved && !result.rawReadOk) {
            Log.w("YukiStorageProbe", "raw read unavailable rawPath=" + result.rawPath + ", err=" + result.readError + ", safErr=" + result.safError);
        }
        doLaunchGame(game);
    }

    private boolean isScopedSaveEnabledFor(EngineType engine) {
        if (prefs == null || engine == null) return false;
        if (engine == EngineType.KIRIKIRI) return prefs.getBoolean(KEY_KR_SCOPED_SAVE_DIR, false);
        if (engine == EngineType.ARTEMIS) return prefs.getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, false);
        return false;
    }

    private boolean shouldUseKrSafFileFallback(Game game) {
        return canUseKrSafFileFallback(game, lastStorageProbeResult)
                && System.currentTimeMillis() - lastStorageProbeAt <= 5000L;
    }

    private boolean canUseKrSafFileFallback(Game game, StorageProbeResult r) {
        if (game == null || game.engine != EngineType.KIRIKIRI) return false;
        if (isScopedSaveEnabledFor(game.engine)) return false;
        if (r == null || !r.rawResolved || !r.safTreeCoversPath || !r.safWriteOk) return false;
        return r.rawReadOk || r.safReadOk;
    }

    private StorageProbeResult probeGameStorage(Game game) {
        long start = System.currentTimeMillis();
        StorageProbeResult result = new StorageProbeResult();
        result.engine = game == null || game.engine == null ? "unknown" : game.engine.name();
        result.rootUri = game == null ? null : game.rootUri;
        result.rawPath = fastRawPathFromUri(result.rootUri);
        result.rawResolved = result.rawPath != null && result.rawPath.startsWith("/");
        try {
            File appExternal = getExternalFilesDir(null);
            result.appPrivateWriteOk = quickWriteProbe(appExternal, ".yukihub_app_probe");
        } catch (Throwable t) {
            result.appPrivateError = shortError(t);
        }
        if (!result.rawResolved) {
            result.elapsedMs = System.currentTimeMillis() - start;
            result.readError = "raw path unavailable";
            return result;
        }
        File root = new File(result.rawPath);
        try {
            result.rawExists = root.exists();
            result.rawIsDirectory = root.isDirectory();
            if (result.rawIsDirectory) {
                String[] names = root.list();
                result.rawReadOk = names != null;
            } else {
                result.rawReadOk = root.isFile() && root.canRead();
            }
        } catch (Throwable t) {
            result.readError = shortError(t);
        }
        if (!result.rawReadOk && result.readError == null) result.readError = "list/canRead failed";
        try {
            File writeDir = root.isDirectory() ? root : root.getParentFile();
            result.rawWriteOk = quickWriteProbe(writeDir, ".yukihub_write_probe");
        } catch (Throwable t) {
            result.writeError = shortError(t);
        }
        if (!result.rawWriteOk && result.writeError == null) result.writeError = "create/write/delete failed";
        if (game != null && game.engine == EngineType.KIRIKIRI) {
            probeSafWriteFallback(result);
        } else if (!result.rawReadOk || !result.rawWriteOk) {
            probeSafWriteFallback(result);
        }
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    private void probeSafWriteFallback(StorageProbeResult result) {
        if (result == null || !result.rawResolved || result.rawPath == null || result.rawPath.trim().isEmpty()) return;
        result.safCandidate = result.rawPath.startsWith("/storage/") || result.rawPath.startsWith("/sdcard");
        if (!result.safCandidate) return;
        try {
            SafPath safPath = toSafPath(result.rawPath);
            if (safPath == null || safPath.volume == null || safPath.rel == null) {
                result.safError = "raw path cannot map to SAF doc id";
                return;
            }
            ContentResolver resolver = getContentResolver();
            if (resolver == null) {
                result.safError = "content resolver unavailable";
                return;
            }
            for (UriPermission perm : resolver.getPersistedUriPermissions()) {
                if (perm == null || perm.getUri() == null) continue;
                String treeId;
                try { treeId = DocumentsContract.getTreeDocumentId(perm.getUri()); } catch (Throwable ignored) { continue; }
                if (treeId == null) continue;
                String decodedTreeId = Uri.decode(treeId);
                if (decodedTreeId == null || !decodedTreeId.startsWith(safPath.volume + ":")) continue;
                String treeRel = decodedTreeId.substring((safPath.volume + ":").length());
                if (!treeRel.isEmpty() && !safPath.rel.equals(treeRel) && !safPath.rel.startsWith(treeRel + "/")) continue;
                result.safTreeCoversPath = true;
                result.safReadOk = perm.isReadPermission();
                boolean safTargetIsDirectory = result.rawIsDirectory || isSafTargetDirectory(perm.getUri(), decodedTreeId, safPath);
                if (!perm.isWritePermission()) {
                    result.safError = "persisted SAF tree is read-only";
                    return;
                }
                Uri probeUri = createSafProbeDocument(resolver, perm.getUri(), decodedTreeId, safPath, safTargetIsDirectory, ".yukihub_saf_probe_" + android.os.Process.myPid() + "_" + System.nanoTime() + ".tmp");
                if (probeUri == null) {
                    result.safError = "create SAF probe failed";
                    return;
                }
                try (OutputStream out = resolver.openOutputStream(probeUri, "wt")) {
                    if (out == null) {
                        result.safError = "open SAF probe output failed";
                        return;
                    }
                    out.write(new byte[]{'Y', 'H'});
                    out.flush();
                } finally {
                    try { DocumentsContract.deleteDocument(resolver, probeUri); } catch (Throwable ignored) { }
                }
                result.safWriteOk = true;
                result.safError = null;
                return;
            }
            result.safError = "no persisted SAF tree covers raw path";
        } catch (Throwable t) {
            result.safError = shortError(t);
        }
    }

    private SafPath toSafPath(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.contains("//")) p = p.replace("//", "/");
        String volume;
        String rel;
        if (p.startsWith("/storage/emulated/0/")) {
            volume = "primary";
            rel = p.substring("/storage/emulated/0/".length());
        } else if ("/storage/emulated/0".equals(p)) {
            volume = "primary";
            rel = "";
        } else if (p.startsWith("/sdcard/")) {
            volume = "primary";
            rel = p.substring("/sdcard/".length());
        } else if ("/sdcard".equals(p)) {
            volume = "primary";
            rel = "";
        } else if (p.startsWith("/storage/")) {
            String rest = p.substring("/storage/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            volume = rest.substring(0, slash);
            rel = rest.substring(slash + 1);
        } else {
            return null;
        }
        if (volume == null || volume.isEmpty() || rel == null) return null;
        return new SafPath(volume, rel);
    }

    private boolean isSafTargetDirectory(Uri tree, String decodedTreeId, SafPath safPath) {
        try {
            if (tree == null || safPath == null) return false;
            DocumentFile current = DocumentFile.fromTreeUri(this, tree);
            if (current == null) return false;
            String treePrefix = safPath.volume + ":";
            String localRel = safPath.rel;
            String treeRel = decodedTreeId != null && decodedTreeId.startsWith(treePrefix) ? decodedTreeId.substring(treePrefix.length()) : "";
            if (!treeRel.isEmpty()) {
                if (localRel.equals(treeRel)) localRel = "";
                else if (localRel.startsWith(treeRel + "/")) localRel = localRel.substring(treeRel.length() + 1);
            }
            if (localRel == null || localRel.isEmpty()) return current.isDirectory();
            String[] parts = localRel.split("/");
            for (String part : parts) {
                if (part == null || part.isEmpty() || ".".equals(part)) continue;
                current = current.findFile(part);
                if (current == null) return false;
            }
            return current.isDirectory();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Uri createSafProbeDocument(ContentResolver resolver, Uri tree, String decodedTreeId, SafPath safPath, boolean rawIsDirectory, String probeName) {
        try {
            if (resolver == null || tree == null || safPath == null || probeName == null || probeName.trim().isEmpty()) return null;
            DocumentFile dir = DocumentFile.fromTreeUri(this, tree);
            if (dir == null) return null;
            String treePrefix = safPath.volume + ":";
            String localRel = safPath.rel;
            String treeRel = decodedTreeId != null && decodedTreeId.startsWith(treePrefix) ? decodedTreeId.substring(treePrefix.length()) : "";
            if (!treeRel.isEmpty()) {
                if (localRel.equals(treeRel)) localRel = "";
                else if (localRel.startsWith(treeRel + "/")) localRel = localRel.substring(treeRel.length() + 1);
            }
            String[] parts = localRel.split("/");
            DocumentFile current = dir;
            int end = rawIsDirectory ? parts.length : Math.max(0, parts.length - 1);
            for (int i = 0; i < end; i++) {
                String part = parts[i];
                if (part == null || part.isEmpty() || ".".equals(part)) continue;
                DocumentFile child = current.findFile(part);
                if (child == null) child = current.createDirectory(part);
                if (child == null || !child.isDirectory()) return null;
                current = child;
            }
            DocumentFile existing = current.findFile(probeName);
            if (existing != null) {
                try { existing.delete(); } catch (Throwable ignored) { }
            }
            DocumentFile probe = current.createFile("application/octet-stream", probeName);
            return probe == null ? null : probe.getUri();
        } catch (Throwable t) {
            Log.w("YukiStorageProbe", "create SAF probe failed", t);
            return null;
        }
    }

    private static class SafPath {
        final String volume;
        final String rel;
        SafPath(String volume, String rel) {
            this.volume = volume;
            this.rel = rel;
        }
    }

    private boolean quickWriteProbe(File dir, String prefix) throws Exception {
        if (dir == null || !dir.isDirectory()) return false;
        File probe = new File(dir, prefix + "_" + android.os.Process.myPid() + "_" + System.nanoTime() + ".tmp");
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(probe, false)) {
            out.write(new byte[]{'Y', 'H'});
            out.flush();
            ok = probe.isFile() && probe.length() >= 2;
        } finally {
            if (probe.exists() && !probe.delete()) Log.w("YukiStorageProbe", "probe delete failed " + probe.getAbsolutePath());
        }
        return ok;
    }

    private String fastRawPathFromUri(String value) {
        if (value == null || value.trim().isEmpty()) return value;
        String s = value.trim();
        if (s.startsWith("file://")) {
            try { return Uri.parse(s).getPath(); } catch (Throwable ignored) { return s.substring("file://".length()); }
        }
        if (s.startsWith("/")) return s;
        try {
            Uri uri = Uri.parse(s);
            String docId = null;
            String path = uri.getPath();
            if (path != null && path.contains("/document/")) {
                try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                try { docId = DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId != null && !docId.isEmpty()) {
                int colon = docId.indexOf(':');
                String volume = colon >= 0 ? docId.substring(0, colon) : docId;
                String rel = colon >= 0 ? docId.substring(colon + 1) : "";
                if ("primary".equalsIgnoreCase(volume)) return rel.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + rel;
                if (volume != null && !volume.isEmpty()) return rel.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + rel;
            }
            String p = uri.getPath();
            return p == null ? s : p;
        } catch (Throwable t) {
            return s;
        }
    }

    private String shortError(Throwable t) {
        if (t == null) return null;
        String msg = t.getMessage();
        String name = t.getClass().getSimpleName();
        return msg == null || msg.trim().isEmpty() ? name : name + ": " + msg;
    }

    private static class StorageProbeResult {
        String engine;
        String rootUri;
        String rawPath;
        boolean rawResolved;
        boolean rawExists;
        boolean rawIsDirectory;
        boolean rawReadOk;
        boolean rawWriteOk;
        boolean safCandidate;
        boolean safTreeCoversPath;
        boolean safReadOk;
        boolean safWriteOk;
        boolean appPrivateWriteOk;
        String readError;
        String writeError;
        String safError;
        String appPrivateError;
        long elapsedMs;

        String toLogLine() {
            return "engine=" + engine
                    + " rawResolved=" + rawResolved
                    + " rawExists=" + rawExists
                    + " rawDir=" + rawIsDirectory
                    + " rawReadOk=" + rawReadOk
                    + " rawWriteOk=" + rawWriteOk
                    + " safCandidate=" + safCandidate
                    + " safCovers=" + safTreeCoversPath
                    + " safReadOk=" + safReadOk
                    + " safWriteOk=" + safWriteOk
                    + " appPrivateWriteOk=" + appPrivateWriteOk
                    + " elapsedMs=" + elapsedMs
                    + " rawPath=" + rawPath
                    + " readErr=" + readError
                    + " writeErr=" + writeError
                    + " safErr=" + safError
                    + " appErr=" + appPrivateError;
        }
    }

    private boolean launchGameInternal(Game game, String emulatorPackage, String launchTarget) {
        if (game == null || emulatorPackage == null || emulatorPackage.trim().isEmpty()) return false;
        String pkg = emulatorPackage.trim();
        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) {
boolean compatMode = prefs != null && prefs.getBoolean(KEY_KR_COMPAT_MODE, false);
String krEngineVersion = prefs == null ? "auto" : prefs.getString(KEY_KR_ENGINE_VERSION, "auto");
boolean safFileFallback = shouldUseKrSafFileFallback(game);
if (safFileFallback) Log.i("YukiStorageProbe", "enable KR SAF file fallback for root=" + game.rootUri);
return startActivitySafely(EmulatorLauncher.buildInternalKrkrIntent(this, game.rootUri, launchTarget, false, compatMode, krEngineVersion, safFileFallback));
}
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) {
            return startActivitySafely(EmulatorLauncher.buildInternalTyranoIntent(this, game.rootUri, launchTarget));
        }
        if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) {
            return startActivitySafely(EmulatorLauncher.buildInternalOnsIntent(this, game.rootUri, launchTarget));
        }
        if (pkg.startsWith("internal.artemis")) {
            return startActivitySafely(EmulatorLauncher.buildInternalArtemisIntent(this, pkg, game.rootUri, launchTarget));
        }
        return EmulatorLauncher.launchGame(this, emulatorPackage, game.rootUri, launchTarget, game.winlatorLaunchMode, game.gamehubLaunchMode, game.gamehubLocalGameId);
    }

    private boolean startActivitySafely(android.content.Intent intent) {
        if (intent == null) return false;
        try {
            startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w("YukiHub", "startActivitySafely failed", t);
            return false;
        }
    }

    private String resolveLaunchType(String emulatorPackage) {
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim().toLowerCase(Locale.ROOT);
        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) return "internal.krkr";
        if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) return "internal.ons";
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) return "internal.tyrano";
        if (pkg.startsWith("internal.artemis")) return pkg;
        return "external";
    }

    private void finishCurrentPlaySessionIfAny() {
        if (launchedExternal && runningGameId > 0 && runningSessionId > 0 && sessionStart > 0) {
            repository.finishPlaySession(runningSessionId, System.currentTimeMillis(), MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
            launchedExternal = false;
            runningGameId = -1;
            runningSessionId = -1;
            sessionStart = 0;
            loadGames();
        }
    }

    private void finishStalePlaySessionsIfAny() {
        if (repository == null) return;
        PlayActivity open = repository.findLatestOpenPlaySession();
        if (open == null) return;
        long now = System.currentTimeMillis();
        long rawDuration = Math.max(0L, now - open.startTime);
        long duration = Math.min(rawDuration, MAX_PLAY_SESSION_MS);
        String message = "检测到最近一次游玩未正常结束。\n\n"
                + "游戏：" + emptyText(open.gameTitle, "未命名游戏") + "\n"
                + "开始时间：" + TimeFormatUtil.date(open.startTime) + "\n"
                + "可补记时长：" + TimeFormatUtil.playTime(duration) + "\n\n"
                + "如果这段时间确实在游玩，可选择补记；如果只是测试启动、闪退或误操作，请选择忽略。\n\n"
                + "本操作仅处理这一条未完成记录。";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现未完成的游玩记录")
                .setMessage(message)
                .setPositiveButton("补记", (d, w) -> {
                    repository.finishPlaySession(open.sessionId, System.currentTimeMillis(), MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
                    loadGames();
                    updateProfilePanel();
                    Toast.makeText(this, "已补记上次游玩时长", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("忽略", (d, w) -> {
                    repository.deleteOpenPlaySession(open.sessionId);
                    loadGames();
                    updateProfilePanel();
                    Toast.makeText(this, "已忽略上次未完成记录", Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                .show();
        styleAlertDialogDark(dialog);
    }

    @Override protected void onResume() {
    super.onResume();
    enterImmersiveMode();
    finishCurrentPlaySessionIfAny();
    resumeBackgroundVideoIfNeeded();
    
    // 自动刷新 Token（如果已登录但可能过期）
    if (isLoggedIn()) {
        AppExecutors.runOnIo(() -> {
            if (refreshAccessToken()) {
                runOnUiThread(() -> updateProfilePanel());
            }
        });
    }
    
    updateProfilePanel();
    maybeAutoWebDavSync();
}

@Override protected void onPause() {
    pauseBackgroundVideoIfNeeded();
    super.onPause();
}

@Override protected void onDestroy() {
    releaseBackgroundMediaPlayer();
    super.onDestroy();
}

private void resumeBackgroundVideoIfNeeded() {
    if (prefs == null || !"video".equals(prefs.getString(KEY_CUSTOM_BACKGROUND_TYPE, "image"))) return;
    if (backgroundMediaPlayer != null) {
        try { if (!backgroundMediaPlayer.isPlaying()) backgroundMediaPlayer.start(); } catch (Throwable ignored) { }
    } else if (pendingBackgroundVideoUri != null) {
        TextureView textureView = findViewById(R.id.customBackgroundVideo);
        if (textureView != null && textureView.getVisibility() == View.VISIBLE) playBackgroundVideo(textureView, pendingBackgroundVideoUri, false);
    }
}

private void pauseBackgroundVideoIfNeeded() {
    if (prefs == null || !"video".equals(prefs.getString(KEY_CUSTOM_BACKGROUND_TYPE, "image"))) return;
    try { if (backgroundMediaPlayer != null && backgroundMediaPlayer.isPlaying()) backgroundMediaPlayer.pause(); } catch (Throwable ignored) { }
}

    private String emptyText(String s, String fallback) { return s == null || s.trim().isEmpty() ? fallback : s; }
}
