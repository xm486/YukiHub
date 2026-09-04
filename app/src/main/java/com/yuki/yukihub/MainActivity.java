package com.yuki.yukihub;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
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

import androidx.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.PersistableBundle;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
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
import android.view.ViewGroup;
import android.view.ViewParent;
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
import android.widget.ProgressBar;
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
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
   import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.ai.AiReviewClient;
import com.yuki.yukihub.ai.AiReviewController;
import com.yuki.yukihub.ai.AiReviewHistoryStore;
import com.yuki.yukihub.ai.AiReviewResult;
import com.yuki.yukihub.ai.AiReviewSettings;
import com.yuki.yukihub.ai.WeeklyPlayStats;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.GameRepository.PlayActivity;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.gamecursor.GameCursorConfig;
import com.yuki.yukihub.gamecursor.GameCursorIconRenderer;
import com.yuki.yukihub.launcher.EmulatorLauncher;
import com.yuki.yukihub.metadata.BangumiClient;
import com.yuki.yukihub.metadata.MetadataController;
import com.yuki.yukihub.metadata.VndbClient;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.metadata.YmgalClient;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.ons.OnsLibLoader;
import com.yuki.yukihub.ons.OnsSettings;
import com.yuki.yukihub.scanner.FastGameScanner;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanResult;
import com.yuki.yukihub.ui.GameAdapter;
import com.yuki.yukihub.ui.CardGlowView;
import com.yuki.yukihub.ui.DynamicSnowBackgroundView;
import com.yuki.yukihub.ui.DynamicTheme;
import com.yuki.yukihub.ui.ThemeColorExtractor;
import com.yuki.yukihub.ui.ScanResultAdapter;
import com.yuki.yukihub.ui.colorpicker.ColorPickerDialog;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.NoMediaHelper;
import com.yuki.yukihub.util.DevLogger;
import com.yuki.yukihub.util.TimeFormatUtil;
import com.yuki.yukihub.util.UiScaleUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Collator;
import java.util.Map;
import java.util.Calendar;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiScaleUtil.wrap(newBase));
    }

    private GameRepository repository;
    private MetadataRepository metadataRepository;
    private AiReviewController aiReviewController;
    private MetadataController metadataController;
    private GameAdapter adapter;
    private final List<Game> allGames = new ArrayList<>();
    private String filter = "ALL";
    /** 本地游戏分类的安装检测缓存（key=rootUri，进入分类时清空重查，分类内搜索/排序复用）。 */
    private final java.util.Map<String, Boolean> localInstalledCache = new java.util.HashMap<>();
private String query = "";
private String developerFilter = "";
    // 多选删除栏
    private LinearLayout multiSelectBar;
    private TextView multiSelectCount;
    private TextView tvEmpty, tvStatsGames, tvStatsTime, tvProfileName, tvProfileInitial;
private ImageView ivProfileAvatar;
private View profileStatusDot;
private LinearLayout detailPanel, detailMetaPanel;
private ImageView sideDetailCover;
private ProgressBar sideDetailCoverLoading;
    private TextView sideDetailPlaceholder, sideDetailTitle, sideMetadataSourceBadge, sideDetailOriginalTitle, sideDetailHint, sideDetailPath, sideDetailDeveloper, sideDetailDate, sideDetailRating, sideDetailLength, sideDetailTags, sideDescToggle, sideTranslateToggle;
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
    /** 当前编辑对话框对应的游戏 id（-1 = 新增模式）。Activity 重建恢复编辑状态用。 */
    private long editingGameId = -1L;
    /** 最近一次保存的编辑对话框状态（onSaveInstanceState 快照），用于重建后恢复及选封面兜底。 */
    private Bundle lastEditDialogState;
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
    private boolean coverMetadataRepairRunning = false;
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
    private static final String KEY_USE_BUILTIN_FILE_CHOOSER = "use_builtin_file_chooser"; // true=内置, false=原生SAF
    private static final String KEY_SCAN_ROOT_ENABLED = "scan_root_enabled"; // 保存每个目录的开关状态
    private static final int MAX_SCAN_ROOTS = 3;
    // .nomedia 询问记录：按扫描根 uri 存（不按下标，避免删除中间项时错位）
    private static final String KEY_NOMEDIA_ASKED = "nomedia_asked_uris";
    private static final String KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth";
    private static final String KEY_AUTO_SCAN_ON_STARTUP = "auto_scan_on_startup";
    private static final String KEY_SCAN_MODE = "scan_mode"; // fast / legacy
    private static final String SCAN_MODE_FAST = "fast";
    private static final String SCAN_MODE_LEGACY = "legacy";
    private static final String KEY_CHECK_UPDATE_ON_STARTUP = "check_update_on_startup";
    private static final String KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at";
    private static final long UPDATE_AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final String UPDATE_GITHUB_API_URL = "https://api.github.com/repos/xm486/YukiHub/releases/latest";
    private static final String UPDATE_GITHUB_REPO_URL = "https://github.com/xm486/YukiHub";
    private static final String UPDATE_GITCODE_API_URL = "https://gitcode.com/api/v5/repos/xm486/YukiHub/releases/latest";
    private static final String UPDATE_GITCODE_REPO_URL = "https://gitcode.com/xm486/YukiHub";
    private static final String KEY_ENGINE_LABEL_POSITION = "engine_label_position";
    private static final int DEFAULT_STARTUP_SCAN_DEPTH = 2;
    private static final int MAX_STARTUP_SCAN_DEPTH = 4;
    private static final String KEY_KR_COMPAT_MODE = "kr_compat_mode";
private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";
private static final String KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir";
private static final String KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir";
/** 编辑对话框重建恢复：标记/状态/表单字段 key（onSaveInstanceState 保存）。 */
private static final String KEY_EDIT_DIALOG_OPEN = "edit_dialog_open";
private static final String KEY_EDIT_STATE = "edit_dialog_state";
private static final String KEY_EDIT_GAME_ID = "edit_game_id";
private static final String KEY_EDIT_TITLE = "edit_title";
private static final String KEY_EDIT_PKG = "edit_pkg";
private static final String KEY_EDIT_DESC = "edit_desc";
private static final String KEY_EDIT_GAMEHUB_ID = "edit_gamehub_id";
private static final String KEY_EDIT_ENGINE = "edit_engine";
private static final String KEY_EDIT_LAUNCH_TARGET = "edit_launch_target";
private static final String KEY_EDIT_WINLATOR_MODE = "edit_winlator_mode";
private static final String KEY_EDIT_GAMEHUB_MODE = "edit_gamehub_mode";
private static final String KEY_EDIT_DIR_URI = "edit_dir_uri";
private static final String KEY_EDIT_COVER_URI = "edit_cover_uri";
/** KRKR 引擎允许绘制刘海/挖孔区域（默认开启）。 */
public static final String KEY_KR_DRAW_CUTOUT = "kr_draw_cutout";
/** Artemis 引擎允许绘制刘海/挖孔区域（默认开启）。 */
public static final String KEY_ARTEMIS_DRAW_CUTOUT = "artemis_draw_cutout";
/** 主界面（首页/游戏库/登录页）允许绘制刘海/挖孔区域（默认关闭，可在首页设置开启）。 */
public static final String KEY_MAIN_DRAW_CUTOUT = "main_draw_cutout";
private static final String KEY_SORT_MODE = "sort_mode";
private static final String SORT_MODE_RECENT = "recent";
private static final String SORT_MODE_NAME = "name";
private static final String SORT_MODE_NEWEST = "newest";
private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
private static final String KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token";
private static final String KEY_AUTH_USER_ID = "auth_user_id";
private static final String KEY_AUTH_UID = "auth_uid";
private static final String KEY_AUTH_NICKNAME = "auth_nickname";
private static final String KEY_AUTH_AVATAR = "auth_avatar";
private static final String KEY_AUTH_EMAIL = "auth_email";
private static final String KEY_AUTH_STATUS = "auth_status";
private static final String KEY_KUN_BOUND = "kungal_bound";
private static final String KEY_KUN_OAUTH_STATE = "kun_oauth_state";
    private static final String KEY_KUN_OAUTH_CODE_VERIFIER = "kun_oauth_code_verifier";
    private static final String KEY_KUN_OAUTH_STARTED_AT = "kun_oauth_started_at";
    private static final String KEY_KUN_OAUTH_MODE = "kun_oauth_mode";
    private static final String KUN_OAUTH_MODE_BIND = "bind";
    private static final String KUN_ANDROID_CLIENT_ID = "16cc006913d6b666c6b1a1a115f644de";
    private static final String KUN_OAUTH_AUTHORIZE_URL = "https://oauth.kungal.com/api/v1/oauth/authorize";
    private static final String KUN_OAUTH_REDIRECT_URI = "yukihub://oauth/callback";
    private static final String KUN_OAUTH_SCOPE = "openid profile email";
    private static final String KEY_HIKARINAGI_BOUND = "hikarinagi_bound";
    private static final String KEY_HIKARINAGI_OAUTH_STATE = "hikarinagi_oauth_state";
    private static final String KEY_HIKARINAGI_OAUTH_CODE_VERIFIER = "hikarinagi_oauth_code_verifier";
    private static final String KEY_HIKARINAGI_OAUTH_NONCE = "hikarinagi_oauth_nonce";
    private static final String KEY_HIKARINAGI_OAUTH_STARTED_AT = "hikarinagi_oauth_started_at";
    private static final String KEY_HIKARINAGI_OAUTH_MODE = "hikarinagi_oauth_mode";
    private static final String HIKARINAGI_OAUTH_MODE_BIND = "bind";
    private static final String HIKARINAGI_ANDROID_CLIENT_ID = "hkn_qtmXMJfBoxcNLA-a";
    private static final String HIKARINAGI_OAUTH_AUTHORIZE_URL = "https://id.hikarinagi.org/oidc/auth";
    private static final String HIKARINAGI_OAUTH_REDIRECT_URI = "yukihub://hikarinagi/callback";
    private static final String HIKARINAGI_OAUTH_SCOPE = "openid user:read";
private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";
private static final String KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled";
private static final String KEY_SHARE_PLAYING = "share_playing_status";
private static final String KEY_FRIEND_PLAY_NOTIFY = "friend_play_notify";
private static final String KEY_LAST_SYNC_AT = "last_sync_at";
private static final String AUTH_STATUS_ONLINE = "online";
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.58 Mobile Safari/537.36";
private static final String AUTH_STATUS_OFFLINE = "offline";
private static final String AUTH_STATUS_EXPIRED = "expired";
private static final String AUTH_STATUS_SYNCING = "syncing";
private static final String KEY_PROFILE_SIGNATURE = "profile_signature";
private static final String KEY_PROFILE_AVATAR = "profile_avatar";
private static final String EXTRA_HOME_TARGET = "home_target";
private static final String EXTRA_HOME_GAME_ID = "home_game_id";
private static final String HOME_TARGET_PROFILE = "profile";
private static final String HOME_TARGET_SETTINGS = "settings";
private static final String HOME_TARGET_LAUNCH_GAME = "launch_game";
private static final String HOME_TARGET_FRIENDS = "friends";
private static final String KEY_CUSTOM_BACKGROUND = "custom_background";
private static final String KEY_CUSTOM_BACKGROUND_TYPE = "custom_background_type";
private static final String KEY_BACKGROUND_DIM_ENABLED = "background_dim_enabled";
private static final String KEY_BACKGROUND_VIDEO_SOUND = "background_video_sound";
private static final String KEY_BG_THEME_ENABLED = "bg_theme_enabled";
private static final String KEY_UI_CLICK_SOUND = "ui_click_sound";
    public static final String KEY_NSFW_BLUR = "nsfw_blur_enabled";
    private static final int UI_SOUND_CLICK = 0;
private static final int UI_SOUND_CONFIRM = 1;
private static final int UI_SOUND_SWITCH = 2;
    private static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
    private static final String KEY_DISCLAIMER_ACCEPTED_AT = "disclaimer_accepted_at";
    private static final String KEY_DISCLAIMER_ACCEPTED_VERSION = "disclaimer_accepted_version";
    private static final int DISCLAIMER_VERSION = 2;
    private static final String KEY_GAME_COLUMNS = "game_columns";
    private static final int DEFAULT_GAME_COLUMNS = 5;
private int pendingScanRootReplaceIndex = -2;
private LinearLayout activeScanRootList;
private TextView activeScanRootInfo;

    private ActivityResultLauncher<Uri> scanDirLauncher;
    private ActivityResultLauncher<Uri> editDirLauncher;
private ActivityResultLauncher<String> coverLauncher;
private ActivityResultLauncher<String> profileAvatarLauncher;
private ActivityResultLauncher<String> backgroundPickerLauncher;
private ActivityResultLauncher<String> videoBackgroundPickerLauncher;
private ActivityResultLauncher<String> cursorIconPickerLauncher;
    /** Windows 光标包（文件夹）导入。 */
    private ActivityResultLauncher<Uri> cursorSchemePickerLauncher;
    /** 当前打开的虚拟鼠标设置对话框里的预览控件；关闭时置 null。 */
    private CursorPreviewView gameCursorPreview;
    /**
     * 当前正在编辑的虚拟鼠标配置对象；对话框关闭时置 null。
     *
     * 必须存在：导入光标包/选图是异步回调，回来时得改**对话框正在持有的那个**
     * cfg 对象。否则另 load 一份副本改完保存，用户点"保存"时对话框里的旧快照
     * 又会把结果覆盖回去。
     */
    private GameCursorConfig gameCursorEditing;
    /** 设置对话框里显示"当前外观来源"的那行文字；随导入结果更新。 */
    private TextView gameCursorSourceStatus;
private MediaPlayer backgroundMediaPlayer;
private SoundPool uiSoundPool;
    private int uiClickSoundId;
    private int uiConfirmSoundId;
    private int uiSwitchSoundId;
    private AlertDialog pendingProfileDialog;
    private com.yuki.yukihub.social.PresenceManager presenceManager;
private boolean activityHeartbeatHeld = false;
private long lastUiSoundAt;
private Uri pendingBackgroundVideoUri;
private ActivityResultLauncher<String> backupCreateLauncher;
    private ActivityResultLauncher<String[]> backupOpenLauncher;
    private ActivityResultLauncher<String[]> playniteImportLauncher;
    private ActivityResultLauncher<String[]> potatovnImportLauncher;
    private ActivityResultLauncher<Uri> vniteImportLauncher;
    private ActivityResultLauncher<String[]> lunaboxImportLauncher;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 聊天选图 launcher 必须在 STARTED 之前注册（供 FriendsChatDialog 借用）
        com.yuki.yukihub.social.ChatImagePicker.register(this);
        enterImmersiveMode();
        repository = new GameRepository(this);
metadataRepository = new MetadataRepository(this);
prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        aiReviewController = new AiReviewController(this, new AiReviewController.Delegate() {
            @Override public GameRepository gameRepository() { return repository; }
            @Override public MetadataRepository metadataRepository() { return MainActivity.this.metadataRepository; }
            @Override public List<Game> allGames() { return allGames; }
            @Override public SharedPreferences prefs() { return prefs; }
            @Override public String visibleMetadataSource(long gameId) { return MainActivity.this.visibleMetadataSource(gameId); }
            @Override public VnMetadata metadataForSource(long gameId, String source) { return MainActivity.this.metadataForSource(gameId, source); }
            @Override public VnMetadata anyCachedMetadata(long gameId) { return MainActivity.this.anyCachedMetadata(gameId); }
            @Override public boolean usingYmgal() { return MainActivity.this.usingYmgal(); }
            @Override public boolean usingHikarinagi() { return MainActivity.this.usingHikarinagi(); }
            @Override public boolean usingBangumi() { return MainActivity.this.usingBangumi(); }
            @Override public boolean usingBangumiMirror() { return MainActivity.this.usingBangumiMirror(); }
            @Override public String bangumiToken() { return MainActivity.this.bangumiToken(); }
            @Override public String buildMetadataSearchKeyword(String title) { return MainActivity.this.buildMetadataSearchKeyword(title); }
            @Override public boolean isConfidentMatch(String localTitle, VnMetadata meta) { return MainActivity.this.isConfidentMatch(localTitle, meta); }
            @Override public int dp(int value) { return MainActivity.this.dp(value); }
            @Override public int getColorCompat(int id) { return MainActivity.this.getColorCompat(id); }
            @Override public Button krButton(String text) { return MainActivity.this.krButton(text); }
            @Override public Spinner krSpinner(String[] values, String selected) { return MainActivity.this.krSpinner(values, selected); }
            @Override public CheckBox krCheckBox(String text, boolean checked) { return MainActivity.this.krCheckBox(text, checked); }
            @Override public void styleAlertDialogDark(AlertDialog dialog) { MainActivity.this.styleAlertDialogDark(dialog); }
            @Override public void applyImmersiveToWindow(Window window) { MainActivity.this.applyImmersiveToWindow(window); }
            @Override public void playUiSound(int type) { MainActivity.this.playUiSound(type); }
            @Override public String emptyText(String s, String fallback) { return MainActivity.this.emptyText(s, fallback); }
            @Override public String normalizePlayStatus(String status) { return MainActivity.this.normalizePlayStatus(status); }
            @Override public String safeCoverUri(Game g) { return MainActivity.this.safeCoverUri(g); }
            @Override public String initials(String title) { return MainActivity.this.initials(title); }
        });
        metadataController = new MetadataController(new MetadataController.Delegate() {
            @Override public SharedPreferences prefs() { return prefs; }
            @Override public MetadataRepository metadataRepository() { return MainActivity.this.metadataRepository; }
            @Override public GameRepository gameRepository() { return repository; }
            @Override public List<Game> allGames() { return allGames; }
            @Override public void runOnUiThread(Runnable r) { MainActivity.this.runOnUiThread(r); }
            @Override public android.app.Activity activity() { return MainActivity.this; }
            @Override public Game selectedGame() { return selectedGame; }
            @Override public void setSelectedGame(Game game) { selectedGame = game; }
            @Override public VnMetadata currentSideMetadata() { return MainActivity.this.currentSideMetadata; }
            @Override public void setCurrentSideMetadata(VnMetadata meta) { MainActivity.this.currentSideMetadata = meta; }
            @Override public boolean sideShowingTranslatedDescription() { return sideShowingTranslatedDescription; }
            @Override public void setSideShowingTranslatedDescription(boolean value) { sideShowingTranslatedDescription = value; }
            @Override public String sideFullDescription() { return sideFullDescription; }
            @Override public void setSideFullDescription(String text) { sideFullDescription = text; }
            @Override public TextView sideDetailTitle() { return sideDetailTitle; }
            @Override public TextView sideDetailOriginalTitle() { return sideDetailOriginalTitle; }
            @Override public TextView sideDetailHint() { return sideDetailHint; }
            @Override public TextView sideDetailPath() { return sideDetailPath; }
            @Override public TextView sideDetailDeveloper() { return sideDetailDeveloper; }
            @Override public TextView sideDetailDate() { return sideDetailDate; }
            @Override public TextView sideDetailRating() { return sideDetailRating; }
            @Override public TextView sideDetailLength() { return sideDetailLength; }
            @Override public TextView sideDetailTags() { return sideDetailTags; }
            @Override public TextView sideDescToggle() { return sideDescToggle; }
            @Override public TextView sideTranslateToggle() { return sideTranslateToggle; }
            @Override public TextView sideMetadataSourceBadge() { return sideMetadataSourceBadge; }
            @Override public ImageView sideDetailCover() { return sideDetailCover; }
            @Override public View sideDetailPlaceholder() { return sideDetailPlaceholder; }
            @Override public ImageView sideScreenshot1() { return sideScreenshot1; }
            @Override public ImageView sideScreenshot2() { return sideScreenshot2; }
            @Override public LinearLayout sideTagContainer() { return sideTagContainer; }
            @Override public TextView sideBtnLaunch() { return sideBtnLaunch; }
            @Override public TextView sideBtnOptions() { return sideBtnOptions; }
            @Override public GameAdapter adapter() { return adapter; }
            @Override public int dp(int value) { return MainActivity.this.dp(value); }
            @Override public int getColorCompat(int id) { return MainActivity.this.getColorCompat(id); }
            @Override public void styleAlertDialogDark(AlertDialog dialog) { MainActivity.this.styleAlertDialogDark(dialog); }
            @Override public void playUiSound(int type) { MainActivity.this.playUiSound(type); }
            @Override public void applyImmersiveToWindow(Window window) { MainActivity.this.applyImmersiveToWindow(window); }
            @Override public void loadRemoteImage(String url, ImageView target, String prefix) { MainActivity.this.loadRemoteImage(url, target, prefix); }
            @Override public void setSideDescription(String text) { MainActivity.this.setSideDescription(text); }
            @Override public void renderSideDescription() { MainActivity.this.renderSideDescription(); }
            @Override public void renderTagChips(String tagsText) { MainActivity.this.renderTagChips(tagsText); }
            @Override public void updateTranslateButtonState() { MainActivity.this.updateTranslateButtonState(); }
            @Override public String emptyText(String s, String fallback) { return MainActivity.this.emptyText(s, fallback); }
            @Override public String safeCoverUri(Game g) { return MainActivity.this.safeCoverUri(g); }
            @Override public String initials(String title) { return MainActivity.this.initials(title); }
            @Override public String displayPath(String value) { return MainActivity.this.displayPath(value); }
            @Override public File persistentRemoteCoverDir() { return MainActivity.this.persistentRemoteCoverDir(); }
            @Override public File cacheDir() { return getCacheDir(); }
            @Override public boolean isMissingFileUri(String uriText) { return MainActivity.this.isMissingFileUri(uriText); }
            @Override public void loadGames() { MainActivity.this.loadGames(); }
            @Override public void updateSideDetail(Game game) { MainActivity.this.updateSideDetail(game); }
            @Override public void showEditDialog(Game game) { MainActivity.this.showEditDialog(game); }
            @Override public void showPlayStatusDialog(Game game, Dialog parentDialog) { MainActivity.this.showPlayStatusDialog(game, parentDialog); }
            @Override public void showEditPlayTimeDialog(Game game) { MainActivity.this.showEditPlayTimeDialog(game); }
            @Override public void showKrSettingsDialog(Game game) { MainActivity.this.showKrSettingsDialog(game); }
            @Override public void showOnsSettingsDialog(Game game) { MainActivity.this.showOnsSettingsDialog(game); }
            @Override public void showDetailDialog(Game game) { MainActivity.this.showDetailDialog(game); }
            @Override public void confirmDeleteGame(Game game) { MainActivity.this.confirmDeleteGame(game); }
            @Override public void showToast(String text, int duration) { Toast.makeText(MainActivity.this, text, duration).show(); }
            @Override public void updateProfilePanel() { MainActivity.this.updateProfilePanel(); }
            @Override public String translateTextToChinese(String text) throws Exception { return MainActivity.this.translateTextToChinese(text); }
        });
        try { DevLogger.init(this); } catch (Throwable ignored) { }
        try { setVolumeControlStream(AudioManager.STREAM_MUSIC); } catch (Throwable ignored) { }
        try { ensureUiSoundPool(); } catch (Throwable ignored) { }
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
        handleHomeTargetIntent(getIntent());
        // 编辑对话框可能在选封面/选目录期间因 Activity 重建而丢失：
        // 检测到保存的编辑状态时，延迟重新弹出对话框并回填表单。
        if (savedInstanceState != null && savedInstanceState.getBoolean(KEY_EDIT_DIALOG_OPEN, false)) {
            final Bundle editState = savedInstanceState.getBundle(KEY_EDIT_STATE);
            if (editState != null) {
                lastEditDialogState = editState;
                getWindow().getDecorView().post(() -> restoreEditDialog(editState));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 编辑对话框打开时保存快照，Activity 重建（旋转/内存回收）后恢复编辑现场。
        if (pendingEditDialog != null && pendingEditDialog.isShowing()) {
            Bundle state = captureEditDialogState();
            if (state != null) {
                lastEditDialogState = state;
                outState.putBoolean(KEY_EDIT_DIALOG_OPEN, true);
                outState.putBundle(KEY_EDIT_STATE, state);
            }
        }
    }

    /** 读取编辑对话框当前表单，生成状态快照。 */
    private Bundle captureEditDialogState() {
        if (pendingEditDialog == null) return null;
        Bundle b = new Bundle();
        b.putLong(KEY_EDIT_GAME_ID, editingGameId);
        b.putString(KEY_EDIT_DIR_URI, pendingDirUri);
        b.putString(KEY_EDIT_COVER_URI, pendingCoverUri);
        EditText title = pendingEditDialog.findViewById(R.id.etGameTitle);
        if (title != null) b.putString(KEY_EDIT_TITLE, title.getText() == null ? "" : title.getText().toString());
        EditText pkg = pendingEditDialog.findViewById(R.id.etEmulatorPackage);
        if (pkg != null) b.putString(KEY_EDIT_PKG, pkg.getText() == null ? "" : pkg.getText().toString());
        EditText desc = pendingEditDialog.findViewById(R.id.etDescription);
        if (desc != null) b.putString(KEY_EDIT_DESC, desc.getText() == null ? "" : desc.getText().toString());
        EditText ghId = pendingEditDialog.findViewById(R.id.etGameHubLocalGameId);
        if (ghId != null) b.putString(KEY_EDIT_GAMEHUB_ID, ghId.getText() == null ? "" : ghId.getText().toString());
        Spinner engineSp = pendingEditDialog.findViewById(R.id.spEngine);
        if (engineSp != null && engineSp.getSelectedItem() != null) b.putString(KEY_EDIT_ENGINE, engineSp.getSelectedItem().toString());
        Spinner launchSp = pendingEditDialog.findViewById(R.id.spLaunchTarget);
        if (launchSp != null && launchSp.getSelectedItem() != null) b.putString(KEY_EDIT_LAUNCH_TARGET, launchSp.getSelectedItem().toString());
        Spinner winSp = pendingEditDialog.findViewById(R.id.spWinlatorLaunchMode);
        if (winSp != null && winSp.getSelectedItem() != null) b.putString(KEY_EDIT_WINLATOR_MODE, winSp.getSelectedItem().toString());
        Spinner ghSp = pendingEditDialog.findViewById(R.id.spGameHubLaunchMode);
        if (ghSp != null && ghSp.getSelectedItem() != null) b.putString(KEY_EDIT_GAMEHUB_MODE, ghSp.getSelectedItem().toString());
        return b;
    }

    private void refreshRuntimeAccountState() {
        if (prefs == null) return;
        updateProfilePanel();
        try { if (adapter != null) adapter.notifyDataSetChanged(); } catch (Throwable ignored) { }
    }

private void handleHomeTargetIntent(Intent intent) {
        if (intent == null) return;
        refreshRuntimeAccountState();
        String target = intent.getStringExtra(EXTRA_HOME_TARGET);
        if (target == null || target.trim().isEmpty()) return;
        long targetGameId = intent.getLongExtra(EXTRA_HOME_GAME_ID, -1L);
        // 通知点击直达会话（与 HomeActivity 同一套 extra）
        final String chatFriendId = intent.getStringExtra("chat_friend_id");
        final int chatGroupId = intent.getIntExtra("chat_group_id", 0);
        intent.removeExtra(EXTRA_HOME_TARGET);
        intent.removeExtra(EXTRA_HOME_GAME_ID);
        intent.removeExtra("chat_friend_id");
        intent.removeExtra("chat_group_id");
        getWindow().getDecorView().post(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            if (HOME_TARGET_PROFILE.equals(target)) {
                if (pendingProfileDialog != null && pendingProfileDialog.isShowing()) pendingProfileDialog.dismiss();
                showProfileDialog();
            } else if (HOME_TARGET_SETTINGS.equals(target)) {
                showSettingsDialog();
            } else if (HOME_TARGET_LAUNCH_GAME.equals(target)) {
                // 未完成记录弹窗是模态的，此时启动会让游戏跑在弹窗背后。
                // 挂起请求，等弹窗关闭后由 consumePendingShortcutLaunch 接手。
                if (staleSessionDialogShowing) {
                    pendingShortcutGameId = targetGameId;
                } else {
                    launchGameFromHome(targetGameId);
                }
            } else if (HOME_TARGET_FRIENDS.equals(target)) {
                if (chatFriendId != null && !chatFriendId.isEmpty()) {
                    new com.yuki.yukihub.social.FriendsChatDialog(this)
                            .showAndOpenFriendChat(chatFriendId);
                } else if (chatGroupId > 0) {
                    new com.yuki.yukihub.social.FriendsChatDialog(this)
                            .showAndOpenGroupChat(chatGroupId);
                } else {
                    showFriendsChatPlaceholder();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleHomeTargetIntent(intent);
    }

    private void launchGameFromHome(long gameId) {
        if (gameId <= 0L) {
            Toast.makeText(this, "无法识别要启动的游戏", Toast.LENGTH_SHORT).show();
            return;
        }
        Game target = null;
        for (Game game : allGames) {
            if (game != null && game.id == gameId) {
                target = game;
                break;
            }
        }
        if (target == null && repository != null) {
            List<Game> latest = repository.getAll();
            if (latest != null) {
                for (Game game : latest) {
                    if (game != null && game.id == gameId) {
                        target = game;
                        break;
                    }
                }
            }
        }
        if (target == null) {
            Toast.makeText(this, "游戏不存在或已被删除", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedGame = target;
        updateSideDetail(target);
        launchGame(target);
    }

    private boolean ensureDisclaimerAccepted() {
        if (prefs == null) return false;
        long acceptedAt = prefs.getLong(KEY_DISCLAIMER_ACCEPTED_AT, 0L);
        boolean accepted = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false);
        int acceptedVersion = prefs.getInt(KEY_DISCLAIMER_ACCEPTED_VERSION, 0);
        // 版本号变化时重新展示新声明（老用户升级后也能看到）
        if (accepted && acceptedAt > 0 && acceptedVersion == DISCLAIMER_VERSION) return true;
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_disclaimer_first_launch, null, false);
        tintDialogRoot(content);
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
            prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true)
                                .putLong(KEY_DISCLAIMER_ACCEPTED_AT, System.currentTimeMillis())
                                .putInt(KEY_DISCLAIMER_ACCEPTED_VERSION, DISCLAIMER_VERSION)
                                .apply();
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
 // 主界面可选：是否绘制到刘海/挖孔区域（首页设置里开关，默认开启）
 com.yuki.yukihub.util.CutoutCompat.setCutoutMode(window, prefs != null && prefs.getBoolean(KEY_MAIN_DRAW_CUTOUT, true));
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
Toast.makeText(MainActivity.this, "扫描目录已更新", Toast.LENGTH_SHORT).show();
maybePromptNoMedia(uri.toString());
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
                    ArrayAdapter<String> adapter = krSpinnerAdapter(options.toArray(new String[0]));
                    launchSp.setAdapter(adapter);
                    ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedCover)).setText(emptyText(pendingCoverUri, "未选择封面"));
                    updateClearDirButton(pendingEditDialog.findViewById(R.id.btnClearDir), pendingDirUri);
                }
            }
        });
        coverLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                pendingCoverUri = copyCoverToInternalStorage(uri);
                if (pendingEditDialog != null) {
                    ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedCover)).setText(pendingCoverUri == null ? "封面复制失败" : pendingCoverUri);
                } else {
                    // 对话框已随 Activity 重建丢失：不再静默，提示并尝试用快照恢复
                    Toast.makeText(MainActivity.this,
                            pendingCoverUri == null ? "封面复制失败" : "封面已选择，正在恢复编辑窗口…",
                            Toast.LENGTH_SHORT).show();
                    if (lastEditDialogState != null) {
                        final Bundle s = lastEditDialogState;
                        if (pendingCoverUri != null) s.putString(KEY_EDIT_COVER_URI, pendingCoverUri);
                        getWindow().getDecorView().post(() -> restoreEditDialog(s));
                    }
                }
            }
        });
profileAvatarLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                String avatar = copyImageToInternalStorage(uri, "avatars", "avatar_", 320, 90);
                if (avatar == null || avatar.isEmpty()) {
                    Toast.makeText(MainActivity.this, "头像保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString(KEY_PROFILE_AVATAR, avatar).remove(KEY_AUTH_AVATAR).apply();
                updateProfilePanel();
                showProfileDialog();
                // 立即上传新头像到服务器
                AppExecutors.runOnIo(() -> {
                    try {
                        com.yuki.yukihub.sync.SyncManager syncMgr = new com.yuki.yukihub.sync.SyncManager(MainActivity.this);
                        syncMgr.uploadAvatarNow();
                    } catch (Throwable ignored) {}
                });
            }
        });
        backgroundPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
String bg = copyImageToInternalStorage(uri, "backgrounds", "bg_", 1920, 88);
                if (bg == null || bg.isEmpty()) {
                    Toast.makeText(MainActivity.this, "背景保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                replaceCustomBackground(bg, "image");
                applyCustomBackground();
                Toast.makeText(MainActivity.this, "已设置图片背景", Toast.LENGTH_SHORT).show();
            }
        });
        videoBackgroundPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                String bg = copyVideoToInternalStorage(uri);
                if (bg == null || bg.isEmpty()) {
                    Toast.makeText(MainActivity.this, "视频背景保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                replaceCustomBackground(bg, "video");
                applyCustomBackground();
                Toast.makeText(MainActivity.this, "已设置视频背景", Toast.LENGTH_SHORT).show();
            }
        });
        // 游戏内虚拟鼠标：光标图标选择（复制到内部存储，避免 content Uri 授权失效）
        cursorIconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            String saved = copyImageToInternalStorage(uri, "cursors", "cursor_", 512, 100);
            if (saved == null || saved.isEmpty()) {
                Toast.makeText(MainActivity.this, "光标图标保存失败", Toast.LENGTH_SHORT).show();
                return;
            }
            // 必须改设置对话框正在持有的那个 cfg，不能另 load 一份 ——
            // 否则对话框点"保存"时会用旧快照把这次选择覆盖掉。
            GameCursorConfig cfg = gameCursorEditing != null
                    ? gameCursorEditing : GameCursorConfig.load(this);
            cfg.iconUri = saved;
            // PNG 与光标包互斥：光标包优先级更高，选了 PNG 就得把包卸掉，
            // 否则设置了 PNG 却看不到变化。
            if (cfg.hasWinCursor()) {
                cfg.winCursorName = null;
                com.yuki.yukihub.gamecursor.wincursor.WinCursorSupport.uninstall(this);
            }
            cfg.save(this);
            if (gameCursorPreview != null) gameCursorPreview.refresh(cfg);
            if (gameCursorSourceStatus != null) gameCursorSourceStatus.setText("当前：自定义图片");
            Toast.makeText(MainActivity.this, "光标图标已更新，游戏内实时生效", Toast.LENGTH_SHORT).show();
        });
        // 游戏内虚拟鼠标：Windows 光标包（.ani/.cur）整包导入
        cursorSchemePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri == null) return;
                    onCursorSchemeFolderPicked(uri);
                });

        backupCreateLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            if (uri != null) exportLocalBackup(uri);
        });
        backupOpenLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importLocalBackup(uri);
        });

        // ===== 三方平台导入 =====
        playniteImportLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) doImportFromPlaynite(uri);
        });
        potatovnImportLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) doImportFromPotatoVN(uri);
        });
        vniteImportLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri != null) doImportFromVnite(uri);
        });
        lunaboxImportLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) doImportFromLunaBox(uri);
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
    if (coverMetadataRepairRunning) return;
    List<Game> targets = new ArrayList<>();
    for (Game g : allGames) {
        if (g == null || g.id <= 0) continue;
        // Target games that have no cover at all, OR have a cover URI pointing
        // to a file that no longer exists (e.g. restored from backup on a new
        // device where the cached cover file path is invalid).
        boolean noCover = !hasCover(g);
        boolean missingFile = isMissingFileUri(g.coverPersistUri) || isMissingFileUri(g.coverUri);
        if (noCover || missingFile) targets.add(g);
    }
    if (targets.isEmpty()) return;
    coverMetadataRepairRunning = true;
    AppExecutors.runOnIo(() -> {
        int changed = 0;
        for (Game g : targets) {
            try {
                VnMetadata meta = currentSourceMetadata(g.id);
                if (meta == null) meta = anyCachedMetadata(g.id);
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
        runOnUiThread(() -> {
            coverMetadataRepairRunning = false;
            if (finalChanged > 0) {
                allGames.clear();
                allGames.addAll(repository.getAll());
                applyFilter();
                if (selectedGame != null && containsGameId(allGames, selectedGame.id)) {
                    // Refresh side panel cover if it was the selected game
                    for (Game g : allGames) {
                        if (g.id == selectedGame.id) { selectedGame = g; break; }
                    }
                }
                Toast.makeText(MainActivity.this, "已恢复 " + finalChanged + " 个同步封面", Toast.LENGTH_SHORT).show();
            }
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
    boolean customColorEnabled = prefs.getBoolean(DynamicTheme.KEY_CUSTOM_COLOR_ENABLED, false);
    DynamicTheme theme = DynamicTheme.getInstance();
    theme.loadCustomColorSettings(prefs);
    if (bg == null || bg.isEmpty()) {
        stopBackgroundVideo();
        bgImage.setImageDrawable(null);
        bgImage.setVisibility(View.GONE);
        bgVideo.setVisibility(View.GONE);
        bgDim.setVisibility(View.GONE);
        dynamicBg.setVisibility(View.VISIBLE);
        if (customColorEnabled) {
            theme.setEnabled(true);
            theme.setCustomColorEnabled(true);
            applyDynamicTheme(theme.getColors());
        } else {
            applyDynamicTheme(null);
        }
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
        // 主题优先级：自定义颜色 > 背景取色 > 默认
        if (customColorEnabled) {
            theme.setEnabled(true);
            theme.setCustomColorEnabled(true);
            applyDynamicTheme(theme.getColors());
        } else if (prefs.getBoolean(KEY_BG_THEME_ENABLED, false)) {
            extractAndApplyTheme(bg, type);
        } else {
            applyDynamicTheme(null);
        }
    } catch (Throwable t) {
        prefs.edit().remove(KEY_CUSTOM_BACKGROUND).remove(KEY_CUSTOM_BACKGROUND_TYPE).apply();
        stopBackgroundVideo();
        bgImage.setImageDrawable(null);
        bgImage.setVisibility(View.GONE);
        bgVideo.setVisibility(View.GONE);
        bgDim.setVisibility(View.GONE);
        dynamicBg.setVisibility(View.VISIBLE);
        if (customColorEnabled) {
            theme.setEnabled(true);
            theme.setCustomColorEnabled(true);
            applyDynamicTheme(theme.getColors());
        } else {
            applyDynamicTheme(null);
        }
    }
}

private void extractAndApplyTheme(String bgUri, String type) {
    DynamicTheme dt = DynamicTheme.getInstance();
    dt.setEnabled(true);
    if ("image".equals(type)) {
        AppExecutors.io().execute(() -> {
            ThemeColorExtractor.ThemeColors colors = dt.extractAndCache(this, bgUri);
            runOnUiThread(() -> applyDynamicTheme(colors));
        });
    } else {
        // For video, extract from first frame using MediaMetadataRetriever
        AppExecutors.io().execute(() -> {
            ThemeColorExtractor.ThemeColors colors = extractVideoFirstFrame(bgUri);
            if (colors != null) {
                runOnUiThread(() -> applyDynamicTheme(colors));
            } else {
                // Fallback: try cache
                if (!dt.loadCache(prefs)) {
                    // Last resort: try TextureView after delay
                    runOnUiThread(this::scheduleVideoThemeExtraction);
                } else {
                    runOnUiThread(() -> applyDynamicTheme(dt.getColors()));
                }
            }
        });
    }
}

@Nullable
private ThemeColorExtractor.ThemeColors extractVideoFirstFrame(String videoUri) {
    android.media.MediaMetadataRetriever retriever = null;
    try {
        retriever = new android.media.MediaMetadataRetriever();
        retriever.setDataSource(this, Uri.parse(videoUri));
        android.graphics.Bitmap frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (frame != null) {
            // Scale down for faster Palette extraction
            android.graphics.Bitmap scaled = frame;
            if (frame.getWidth() > 200 || frame.getHeight() > 200) {
                float scale = 200f / Math.max(frame.getWidth(), frame.getHeight());
                scaled = Bitmap.createScaledBitmap(frame, 
                        (int)(frame.getWidth() * scale), (int)(frame.getHeight() * scale), true);
                if (scaled != frame) frame.recycle();
            }
            DynamicTheme dt = DynamicTheme.getInstance();
            ThemeColorExtractor.ThemeColors colors = dt.extractFromBitmapAndCache(this, scaled);
            scaled.recycle();
            return colors;
        }
    } catch (Throwable ignored) {
    } finally {
        if (retriever != null) {
            try { retriever.release(); } catch (Throwable ignored) {}
        }
    }
    return null;
}

private void scheduleVideoThemeExtraction() {
    TextureView bgVideo = findViewById(R.id.customBackgroundVideo);
    if (bgVideo == null) return;
    bgVideo.postDelayed(() -> {
        try {
            if (bgVideo.isAvailable()) {
                android.graphics.Bitmap frame = bgVideo.getBitmap(100, 100);
                if (frame != null) {
                    DynamicTheme dt = DynamicTheme.getInstance();
                    ThemeColorExtractor.ThemeColors colors = dt.extractFromBitmapAndCache(this, frame);
                    frame.recycle();
                    applyDynamicTheme(colors);
                }
            }
        } catch (Throwable ignored) {}
    }, 1500);
}

/** Apply dynamic theme colors to all UI components. Pass null to reset to defaults. */
    private void applyDynamicTheme(ThemeColorExtractor.ThemeColors colors) {
        DynamicTheme dt = DynamicTheme.getInstance();
        boolean resetting = (colors == null);
        if (resetting) {
            dt.setEnabled(false);
            dt.setCustomColorEnabled(false);
            colors = ThemeColorExtractor.DEFAULT;
        } else {
            // 如果传入了颜色，检查是否是自定义颜色
            if (dt.isCustomColorEnabled()) {
                // 应用自定义颜色
                dt.setEnabled(true);
            } else {
                // 应用背景提取的颜色
                dt.setEnabled(true);
            }
        }
    // Update DynamicSnowBackgroundView
    DynamicSnowBackgroundView dynamicBg = findViewById(R.id.dynamicBackground);
    if (dynamicBg != null) {
        dynamicBg.setThemeColors(colors.bg, colors.bg2, colors.auroraColor1, colors.auroraColor2, colors.auroraColor3);
    }
    // Update GameAdapter (card backgrounds only, not text)
    if (adapter != null) {
        adapter.setFullThemeColors(resetting ? null : colors);
        adapter.notifyDataSetChanged();
    }
    // Update CardGlowView instances (via RecyclerView)
    RecyclerView recycler = findViewById(R.id.recyclerGames);
    if (recycler != null) {
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (child == null) continue;
            CardGlowView glow = child.findViewById(R.id.cardGlow);
            if (glow != null) glow.setThemeColors(colors.glowColor1, colors.glowColor2);
        }
    }

    // --- Apply extracted colors to UI component backgrounds/borders only ---

    //Sidebar filter items (background only)
    int[] sidebarIds = {R.id.filterAll, R.id.filterFavorite, R.id.filterRecent, R.id.filterStatus, R.id.filterDeveloper};
    for (int id : sidebarIds) {
        TextView tv = findViewById(id);
        if (tv != null) {
            tv.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_sidebar_item) : tintSidebarItem(colors));
        }
    }

    // Profile panel (background only)
    View profilePanel = findViewById(R.id.profilePanel);
    if (profilePanel != null) {
        profilePanel.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_sidebar) : tintSidebar(colors));
    }

    // Search input (background only)
    EditText etSearch = findViewById(R.id.etSearch);
    if (etSearch != null) {
        etSearch.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_input) : tintInput(colors));
    }

    // Detail panel (right sidebar) — outer FrameLayout has bg_game_card
    View detailPanel = findViewById(R.id.detailPanel);
    if (detailPanel != null) {
        // The outer FrameLayout is the detailPanel's parent (ScrollView's parent)
        View detailOuterFrame = (View) detailPanel.getParent().getParent();
        if (detailOuterFrame != null) {
            detailOuterFrame.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_game_card) : tintCardSelected(colors));
        }
        // The ScrollView parent
        View detailScrollParent = (View) detailPanel.getParent();
        if (detailScrollParent != null) {
            detailScrollParent.setBackgroundColor(0x00000000);
        }
    }

    // Detail panel interactive element backgrounds
    TextView sideMetadataSourceBadge = findViewById(R.id.sideMetadataSourceBadge);
    if (sideMetadataSourceBadge != null) {
        sideMetadataSourceBadge.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_chip) : tintChip(colors));
    }
    TextView sideTranslateToggle = findViewById(R.id.sideTranslateToggle);
    if (sideTranslateToggle != null) {
        sideTranslateToggle.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_input) : tintInput(colors));
    }

    // Detail panel cover placeholder
    TextView sideDetailPlaceholder = findViewById(R.id.sideDetailPlaceholder);
    if (sideDetailPlaceholder != null) {
        View coverFrame = (View) sideDetailPlaceholder.getParent();
        if (coverFrame != null) {
            coverFrame.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_cover_placeholder) : tintCoverPlaceholder(colors));
        }
    }

    // Detail panel screenshot placeholders
    ImageView sideScreenshot1 = findViewById(R.id.sideScreenshot1);
    if (sideScreenshot1 != null) {
        sideScreenshot1.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_cover_placeholder) : tintCoverPlaceholder(colors));
    }
    ImageView sideScreenshot2 = findViewById(R.id.sideScreenshot2);
    if (sideScreenshot2 != null) {
        sideScreenshot2.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_cover_placeholder) : tintCoverPlaceholder(colors));
    }

    // Launch button (background only)
    TextView sideBtnLaunch = findViewById(R.id.sideBtnLaunch);
    if (sideBtnLaunch != null) {
        sideBtnLaunch.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_yuki_button) : tintButton(colors));
    }
    // Options button (background only)
    TextView sideBtnOptions = findViewById(R.id.sideBtnOptions);
    if (sideBtnOptions != null) {
        sideBtnOptions.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_input) : tintInput(colors));
    }

    // Top bar buttons (background only)
    View btnScan = findViewById(R.id.btnScan);
    if (btnScan != null) btnScan.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_yuki_button) : tintButton(colors));
    TextView btnAdd = findViewById(R.id.btnAdd);
    if (btnAdd != null) btnAdd.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_yuki_button) : tintButton(colors));
    View btnSettings = findViewById(R.id.btnSettings);
    if (btnSettings != null) btnSettings.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_yuki_button) : tintButton(colors));

    // Nav buttons (background only)
    int[] navIds = {R.id.navHome, R.id.navLibrary, R.id.navBigScreen, R.id.navChat};
    for (int id : navIds) {
        View nv = findViewById(id);
        if (nv != null) {
            if (id == R.id.navLibrary) {
                // navLibrary is the active/selected nav — use bg_home_nav_active on reset, tintSidebar when themed
                nv.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_home_nav_active) : tintSidebar(colors));
            } else {
                nv.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_sidebar_item) : tintSidebarItem(colors));
            }
        }
    }

    // Sidebar container backgrounds (profile, nav, stats)
    int[] sidebarContainerIds = {R.id.profileContainer, R.id.navContainer, R.id.statsContainer};
    for (int id : sidebarContainerIds) {
        View sc = findViewById(id);
        if (sc != null) {
            sc.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_sidebar) : tintSidebar(colors));
        }
    }

    // Filter bar items + sort button (use bg_input style)
    int[] filterIds = {R.id.filterAll, R.id.filterFavorite, R.id.filterRecent, R.id.filterStatus, R.id.filterDeveloper, R.id.btnSort};
    for (int id : filterIds) {
        View fv = findViewById(id);
        if (fv != null) {
            fv.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_input) : tintInput(colors));
        }
    }

    // Status bar items (clock, battery — same bg_input style)
    int[] statusIds = {R.id.tvClock, R.id.tvBatteryLevel};
    for (int id : statusIds) {
        View sv = findViewById(id);
        if (sv != null) {
            sv.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_input) : tintInput(colors));
        }
    }

    // Notice button (uses bg_status_item on reset, tintInput when themed — no padding to avoid icon squeeze)
    View btnNotice = findViewById(R.id.btnNotice);
    if (btnNotice != null) {
        btnNotice.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_status_item) : tintInput(colors));
    }

    // Background dim overlay color
    View bgDim = findViewById(R.id.customBackgroundDim);
    if (bgDim != null) {
        bgDim.setBackgroundColor((0x66 << 24) | (colors.bg & 0x00FFFFFF));
    }

    // Profile avatar placeholder
    // Cover placeholders (profile avatar, detail panel cover, screenshots)
    int[] placeholderIds = {R.id.tvCoverPlaceholder};
    for (int id : placeholderIds) {
        View v = findViewById(id);
        if (v != null && v.getVisibility() == View.VISIBLE) {
            v.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_cover_placeholder) : tintCoverPlaceholder(colors));
        }
    }

    // Detail panel cover placeholder
    View detailCoverPlaceholder = null;
    View detailPanel2 = findViewById(R.id.detailPanel);
    if (detailPanel2 != null && detailPanel2 instanceof ViewGroup) {
        detailCoverPlaceholder = findViewByIdRecursive((ViewGroup) detailPanel2, R.id.tvCoverPlaceholder);
    }
    if (detailCoverPlaceholder != null) {
        detailCoverPlaceholder.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_cover_placeholder) : tintCoverPlaceholder(colors));
    }

    // Profile avatar placeholder
    View profileAvatar = findViewById(R.id.profilePanel);
    if (profileAvatar != null && profileAvatar instanceof ViewGroup) {
        View avatarFrame = findViewByIndex((ViewGroup) profileAvatar, 0);
        if (avatarFrame != null) {
            avatarFrame.setBackground(resetting ? ContextCompat.getDrawable(this, R.drawable.bg_cover_placeholder) : tintCoverPlaceholder(colors));
        }
    }

    // Navigation bar color
    getWindow().setNavigationBarColor(colors.bg);

    // Tint all text colors in main layout
    View mainRoot = findViewById(android.R.id.content);
    if (mainRoot instanceof ViewGroup) {
        tintMainTextColors((ViewGroup) mainRoot, resetting ? null : colors);
    }
}

/** Recursively tint text colors in the main layout for dynamic theme. */
private void tintMainTextColors(ViewGroup root, ThemeColorExtractor.ThemeColors colors) {
    boolean enabled = (colors != null);
    for (int i = 0; i < root.getChildCount(); i++) {
        View child = root.getChildAt(i);
        if (child instanceof ViewGroup) {
            tintMainTextColors((ViewGroup) child, colors);
        }
        if (child instanceof TextView) {
            TextView tv = (TextView) child;
            if (enabled) {
                int currentColor = tv.getCurrentTextColor();
                boolean isDarkText = (currentColor & 0x00FFFFFF) < 0x555555;
                // Buttons or dark-text TextViews (on bright buttons) → pure white
                if (child instanceof Button || child instanceof CheckBox || isDarkText) {
                    tv.setTextColor(0xFFFFFFFF);
                }
                // EditText → near-white text, gray hint
                else if (child instanceof EditText) {
                    tv.setTextColor(0xFFF0F4FA);
                    tv.setHintTextColor(0xFF8090A8);
                }
                // Other TextViews → gray-white
                else {
                    tv.setTextColor(0xFFE8EDF5);
                }
            } else {
                // Reset to original colors
                int currentColor = tv.getCurrentTextColor();
                boolean isDarkText = (currentColor & 0x00FFFFFF) < 0x555555;
                if (child instanceof Button || child instanceof CheckBox || isDarkText) {
                    tv.setTextColor(0xFF000000);
                } else if (child instanceof EditText) {
                    tv.setTextColor(getColorCompat(R.color.yh_text));
                    tv.setHintTextColor(getColorCompat(R.color.yh_text_muted));
                } else {
                    tv.setTextColor(getColorCompat(R.color.yh_text));
                }
            }
        }
    }
}

/** Create a sidebar-style background with dynamic colors.
     * 渐变模式时使用 c1→c2 真实渐变。 */
    private android.graphics.drawable.GradientDrawable tintSidebar(ThemeColorExtractor.ThemeColors c) {
        android.graphics.drawable.GradientDrawable d;
        if (c.isGradient) {
            d = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{
                            (0xC8 << 24) | (c.bg & 0x00FFFFFF),
                            (0xC8 << 24) | (c.bg2 & 0x00FFFFFF)
                    });
        } else {
            d = new android.graphics.drawable.GradientDrawable();
            d.setColor((0xC8 << 24) | (c.bg & 0x00FFFFFF));
        }
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), (0x42 << 24) | (c.primary & 0x00FFFFFF));
        return d;
    }

/** Create a sidebar item background with dynamic colors.
     * 渐变模式时 selected 使用 secondary 色来体现双色渐变感。 */
    private android.graphics.drawable.StateListDrawable tintSidebarItem(ThemeColorExtractor.ThemeColors c) {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        // Selected state：渐变模式使用 secondary，单色模式使用 primary
        int selColor = (c.isGradient) ? c.secondary : c.primary;
        android.graphics.drawable.GradientDrawable selected = new android.graphics.drawable.GradientDrawable();
        selected.setColor((0x27 << 24) | (selColor & 0x00FFFFFF));
        selected.setCornerRadius(dp(9));
        selected.setStroke(dp(1), (0x7E << 24) | (selColor & 0x00FFFFFF));
        sld.addState(new int[]{android.R.attr.state_selected}, selected);
        // Pressed state
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setColor((0x1A << 24) | (c.primary & 0x00FFFFFF));
        pressed.setCornerRadius(dp(9));
        pressed.setStroke(dp(1), (0x55 << 24) | (c.primary & 0x00FFFFFF));
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        // Default state
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(0x00000000);
        normal.setCornerRadius(dp(9));
        sld.addState(new int[]{}, normal);
        return sld;
    }

/** Create an input field background with dynamic colors.
     * 渐变模式时边框使用 c1→c2 渐变。 */
    private android.graphics.drawable.GradientDrawable tintInput(ThemeColorExtractor.ThemeColors c) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor((0xA8 << 24) | (c.bg & 0x00FFFFFF));
        d.setCornerRadius(dp(8));
        if (c.isGradient) {
            d.setStroke(dp(1), (0x5E << 24) | (c.secondary & 0x00FFFFFF));
        } else {
            d.setStroke(dp(1), (0x5E << 24) | (c.primary & 0x00FFFFFF));
        }
        return d;
    }

/** Create a primary action button background with dynamic colors.
     * 渐变模式时使用真正的渐变色 c1→c2。 */
    private android.graphics.drawable.GradientDrawable tintButton(ThemeColorExtractor.ThemeColors c) {
        android.graphics.drawable.GradientDrawable d;
        if (c.isGradient) {
            // 渐变模式：从 primary 到 secondary 的真实渐变
            d = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{
                            (0xB4 << 24) | (c.primary & 0x00FFFFFF),
                            (0x86 << 24) | (c.secondary & 0x00FFFFFF)
                    });
        } else {
            // 单色模式：保持原样
            d = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{
                            (0xB4 << 24) | (c.primary & 0x00FFFFFF),
                            (0x86 << 24) | (c.primary & 0x00FFFFFF),
                            (0x6B << 24) | (c.primary & 0x00FFFFFF)
                    });
        }
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), (0xBF << 24) | (c.primary & 0x00FFFFFF));
        return d;
    }

/** Create a chip/tag background with dynamic colors. */
private android.graphics.drawable.GradientDrawable tintChip(ThemeColorExtractor.ThemeColors c) {
    android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
    d.setColor((0xAA << 24) | (c.card2 & 0x00FFFFFF));
    d.setCornerRadius(dp(999));
    d.setStroke(dp(1), (0x7A << 24) | (c.primary & 0x00FFFFFF));
    return d;
}

/** Create a selected card background with dynamic colors.
     * 渐变模式时使用 c1→c2 真实渐变。 */
    private android.graphics.drawable.GradientDrawable tintCardSelected(ThemeColorExtractor.ThemeColors c) {
        android.graphics.drawable.GradientDrawable d;
        if (c.isGradient) {
            d = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{
                            (0xE4 << 24) | (c.card & 0x00FFFFFF),
                            (0xD8 << 24) | (c.card2 & 0x00FFFFFF)
                    });
        } else {
            d = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{
                            (0xE4 << 24) | (c.card & 0x00FFFFFF),
                            (0xD9 << 24) | (c.card & 0x00FFFFFF),
                            (0xD8 << 24) | (c.bg2 & 0x00FFFFFF)
                    });
        }
        d.setCornerRadius(dp(10));
        d.setStroke(dp(2), (0x9C << 24) | (c.secondary & 0x00FFFFFF));
        return d;
    }

/** Create a cover placeholder gradient with dynamic colors. */
private GradientDrawable tintCoverPlaceholder(ThemeColorExtractor.ThemeColors c) {
    GradientDrawable d = new GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            new int[]{
                    (0xFF << 24) | (c.card & 0x00FFFFFF),
                    (0xFF << 24) | (c.card2 & 0x00FFFFFF),
                    (0xFF << 24) | (c.secondary & 0x00FFFFFF)
            });
    d.setCornerRadius(dp(8));
    d.setStroke(dp(1), 0x22FFFFFF);
    return d;
}

/** Recursively find a view by ID within a ViewGroup. */
private View findViewByIdRecursive(ViewGroup root, int id) {
    if (root == null) return null;
    for (int i = 0; i < root.getChildCount(); i++) {
        View child = root.getChildAt(i);
        if (child.getId() == id) return child;
        if (child instanceof ViewGroup) {
            View found = findViewByIdRecursive((ViewGroup) child, id);
            if (found != null) return found;
        }
    }
    return null;
}

/** Get child view at a specific index from a ViewGroup. */
private View findViewByIndex(ViewGroup parent, int index) {
    if (parent == null || index < 0 || index >= parent.getChildCount()) return null;
    return parent.getChildAt(index);
}

/** Apply dynamic theme to a dialog's root view. Call after setting up the dialog content. */
public void tintDialogRoot(View rootView) {
    DynamicTheme dt = DynamicTheme.getInstance();
    if (!dt.isEnabled()) return;
    ThemeColorExtractor.ThemeColors colors = dt.getColors();
    if (colors == null) return;
    rootView.setBackground(tintDialog(colors));
    // Postpone child tinting so all views are added first
    rootView.post(() -> tintDialogChildren((ViewGroup) rootView, colors));
}

/** Create a dialog background with dynamic colors. */
private GradientDrawable tintDialog(ThemeColorExtractor.ThemeColors c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor((0xF0 << 24) | (c.card & 0x00FFFFFF));
    d.setStroke(dp(1), (0x5E << 24) | (c.primary & 0x00FFFFFF));
    d.setCornerRadius(dp(10));
    return d;
}

/** Recursively tint known background patterns inside dialogs. */
private void tintDialogChildren(ViewGroup root, ThemeColorExtractor.ThemeColors colors) {
    for (int i = 0; i < root.getChildCount(); i++) {
        View child = root.getChildAt(i);
        // Recurse into child ViewGroups first
        if (child instanceof ViewGroup) {
            tintDialogChildren((ViewGroup) child, colors);
        }

        // ViewGroup with bg_dialog background → replace with tintDialog
        if (child instanceof ViewGroup && !(child instanceof RecyclerView) && !(child instanceof FrameLayout)
                && !(child instanceof Spinner) && !(child instanceof EditText)
                && child.getBackground() != null) {
            child.setBackground(tintDialog(colors));
        }

        // CheckBox → dynamic tint + white text
        if (child instanceof CheckBox) {
            ((CheckBox) child).setButtonTintList(android.content.res.ColorStateList.valueOf(colors.primary));
            ((CheckBox) child).setTextColor(0xFFE8EDF5);
            continue;
        }

        // SeekBar → dynamic progress tint
        if (child instanceof SeekBar) {
            ((SeekBar) child).setProgressTintList(android.content.res.ColorStateList.valueOf(colors.primary));
            ((SeekBar) child).setThumbTintList(android.content.res.ColorStateList.valueOf(colors.primary));
            continue;
        }

        // Button → tintButton + white text
        if (child instanceof Button) {
            child.setBackground(tintButton(colors));
            ((Button) child).setTextColor(0xFFFFFFFF);
            continue;
        }
        // EditText → tintInput + white text + gray hint
        if (child instanceof EditText) {
            child.setBackground(tintInput(colors));
            ((EditText) child).setTextColor(0xFFF0F4FA);
            ((EditText) child).setHintTextColor(0xFF8090A8);
            continue;
        }
        // Spinner → tintInput + white text
        if (child instanceof Spinner) {
            child.setBackground(tintInput(colors));
            continue;
        }

        Drawable bg = child.getBackground();
        // TextView with background (labels, badges, action buttons like btnReset/btnPick)
        if (child instanceof TextView && !(child instanceof EditText) && !(child instanceof Button)) {
            TextView tv = (TextView) child;
            int currentTextColor = tv.getCurrentTextColor();
            // If text was dark (like #071221 on bright buttons), treat as action button
            boolean isDarkText = (currentTextColor & 0x00FFFFFF) < 0x555555;
            if (isDarkText) {
                child.setBackground(tintButton(colors));
                tv.setTextColor(0xFFFFFFFF);
            } else if (bg != null) {
                child.setBackground(tintLabel(colors));
                tv.setTextColor(0xFFE8EDF5);
            } else {
                // TextView without background — just tint text
                tv.setTextColor(0xFFE8EDF5);
            }
            continue;
        }
        // FrameLayout with background → cover placeholder
        if (child instanceof FrameLayout && bg != null) {
            child.setBackground(tintCoverPlaceholder(colors));
        }
    }
}

/** Create a label background with dynamic colors (replaces bg_label). */
private GradientDrawable tintLabel(ThemeColorExtractor.ThemeColors c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor((0x33 << 24) | (c.primary & 0x00FFFFFF));
    d.setStroke(dp(1), (0x7A << 24) | (c.primary & 0x00FFFFFF));
    d.setCornerRadius(dp(6));
    d.setPadding(dp(10), dp(5), dp(10), dp(5));
    return d;
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
            Toast.makeText(MainActivity.this, "视频背景播放失败，请尝试更换视频格式", Toast.LENGTH_SHORT).show();
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
DocumentFile dir = gameDir(rootUri);
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
 
    private TextView tvGreeting;

    private void setupUi() {
        RecyclerView recycler = findViewById(R.id.recyclerGames);
        tvEmpty = findViewById(R.id.tvEmpty);
tvStatsGames = findViewById(R.id.tvStatsGames);
tvStatsTime = findViewById(R.id.tvStatsTime);
tvProfileName = findViewById(R.id.tvProfileName);
tvProfileInitial = findViewById(R.id.tvProfileInitial);
profileStatusDot = findViewById(R.id.profileStatusDot);
ivProfileAvatar = findViewById(R.id.ivProfileAvatar);
tvGreeting = findViewById(R.id.tvGreeting);
detailPanel = findViewById(R.id.detailPanel);
        detailMetaPanel = findViewById(R.id.detailMetaPanel);
        sideDetailCover = findViewById(R.id.sideDetailCover);
        sideDetailCoverLoading = findViewById(R.id.sideDetailCoverLoading);
        sideDetailPlaceholder = findViewById(R.id.sideDetailPlaceholder);
        sideDetailTitle = findViewById(R.id.sideDetailTitle);
sideMetadataSourceBadge = findViewById(R.id.sideMetadataSourceBadge);
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
        prepareManualClickFeedback(sideBtnLaunch);
        prepareManualClickFeedback(sideBtnOptions);
        prepareManualClickFeedback(sideDescToggle);
        prepareManualClickFeedback(sideTranslateToggle);
        sideBtnLaunch.setOnClickListener(v -> { clickFeedback(v); if (selectedGame != null) launchGame(selectedGame); });
        sideBtnOptions.setOnClickListener(v -> { clickFeedback(v); if (selectedGame != null) showSideOptions(selectedGame); });
        sideDescToggle.setOnClickListener(v -> { clickFeedback(v); sideDescExpanded = !sideDescExpanded; renderSideDescription(); });
if (sideTranslateToggle != null) sideTranslateToggle.setOnClickListener(v -> { clickFeedback(v); toggleOrTranslateDescription(); });
        updateSideDetail(null);
        adapter = new GameAdapter();
adapter.setOnUiFeedbackListener(type -> playUiSound(type == GameAdapter.FEEDBACK_CONFIRM ? UI_SOUND_CONFIRM : (type == GameAdapter.FEEDBACK_SWITCH ? UI_SOUND_SWITCH : UI_SOUND_CLICK)));
adapter.setOnGameClickListener(new GameAdapter.OnGameClickListener() {
            @Override public void onGameClick(Game game) { updateSideDetail(game); }
            @Override public void onGameDoubleClick(Game game) { if (game != null) launchGame(game); }
            @Override public void onGameLongClick(Game game) { showEditDialog(game); }
            @Override public void onStatusClick(Game game) { updateSideDetail(game); showPlayStatusDialog(game, null); }
        });
        adapter.setOnSelectionChangedListener(count -> updateMultiSelectBar(count));
        adapter.setNsfwBlurEnabled(nsfwBlurEnabled());
        int columns = prefs == null ? DEFAULT_GAME_COLUMNS : prefs.getInt(KEY_GAME_COLUMNS, DEFAULT_GAME_COLUMNS);
        columns = Math.max(2, Math.min(10, columns));
        recycler.setLayoutManager(new GridLayoutManager(this, columns));
        recycler.setAdapter(adapter);
        ensureMultiSelectBar();
View addButton = findViewById(R.id.btnAdd);
        View scanButton = findViewById(R.id.btnScan);
        ivScanLoading = findViewById(R.id.ivScanLoading);
        applyTopActionFeedback(addButton);
applyTopActionFeedback(scanButton);
prepareManualClickFeedback(addButton);
prepareManualClickFeedback(scanButton);
addButton.setOnClickListener(v -> { clickFeedback(v); showEditDialog(null); });
scanButton.setOnClickListener(v -> { clickFeedback(v); scanLastRootOrChoose(); });
scanButton.setOnLongClickListener(v -> { clickFeedback(v); launchScanRootPicker(-1); return true; });
// 设置按钮（右上角）
View settingsButton = findViewById(R.id.btnSettings);
if (settingsButton != null) {
    applyTopActionFeedback(settingsButton);
    prepareManualClickFeedback(settingsButton);
    settingsButton.setOnClickListener(v -> { clickFeedback(v); showSettingsDialog(); });
}
// 通知按钮
View btnNotice = findViewById(R.id.btnNotice);
if (btnNotice != null) {
    prepareManualClickFeedback(btnNotice);
    btnNotice.setOnClickListener(v -> { clickFeedback(v); showNoticeDialog(); });
}
// 导航按钮
View navHome = findViewById(R.id.navHome);
 View navTranslate = findViewById(R.id.navTranslate);
 View navBigScreen = findViewById(R.id.navBigScreen);
View navChat = findViewById(R.id.navChat);
if (navHome != null) { prepareManualClickFeedback(navHome); navHome.setOnClickListener(v -> { clickFeedback(v); startActivity(new Intent(this, HomeActivity.class).putExtra("force_home", true)); finish(); }); }
if (navTranslate != null) { prepareManualClickFeedback(navTranslate); navTranslate.setOnClickListener(v -> { clickFeedback(v); startActivity(new Intent(this, com.yuki.yukihub.translate.TranslateControlActivity.class)); }); }
if (navBigScreen != null) { prepareManualClickFeedback(navBigScreen); navBigScreen.setOnClickListener(v -> { clickFeedback(v); Toast.makeText(this, "大屏模式正在开发中，入口已为欧尼酱预留。", Toast.LENGTH_SHORT).show(); }); }
if (navChat != null) { prepareManualClickFeedback(navChat); navChat.setOnClickListener(v -> { clickFeedback(v); showFriendsChatPlaceholder(); }); }
// 排序按钮
View btnSort = findViewById(R.id.btnSort);
if (btnSort != null) { prepareManualClickFeedback(btnSort); btnSort.setOnClickListener(v -> { clickFeedback(v); showSortDialog(); }); }
// 头像点击
View profilePanel = findViewById(R.id.profilePanel);
if (profilePanel != null) {
    prepareManualClickFeedback(profilePanel);
    profilePanel.setOnClickListener(v -> { clickFeedback(v); showProfileDialog(); });
}
        setupDeveloperToggle();
        setupStatusToggle();
bindFilter(R.id.filterAll, "ALL"); bindFilter(R.id.filterFavorite, "FAVORITE"); bindFilter(R.id.filterRecent, "LOCAL");
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

private void prepareManualClickFeedback(View v) {
    if (v == null) return;
    try { v.setSoundEffectsEnabled(false); } catch (Throwable ignored) { }
}

private void attachUiTouchSound(View v, int type) {
    if (v == null) return;
    prepareManualClickFeedback(v);
    v.setOnTouchListener((view, event) -> {
        if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) playUiSound(type);
        return false;
    });
}

private boolean uiClickSoundEnabled() {
    return prefs == null || prefs.getBoolean(KEY_UI_CLICK_SOUND, true);
}

private void clickFeedback(View v) {
    if (v == null) return;
    try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
    playUiSound(UI_SOUND_CLICK);
}

private void playUiSound(int type) {
    if (!uiClickSoundEnabled()) return;
    long now = System.currentTimeMillis();
    if (now - lastUiSoundAt < 35L) return;
    lastUiSoundAt = now;
    try {
        ensureUiSoundPool();
        int soundId = type == UI_SOUND_CONFIRM ? uiConfirmSoundId : (type == UI_SOUND_SWITCH ? uiSwitchSoundId : uiClickSoundId);
        if (soundId != 0 && uiSoundPool != null) uiSoundPool.play(soundId, 0.65f, 0.65f, 1, 0, 1.0f);
    } catch (Throwable ignored) { }
}

private void ensureUiSoundPool() {
    if (uiSoundPool != null) return;
    uiSoundPool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
    uiClickSoundId = uiSoundPool.load(this, R.raw.ui_click, 1);
    uiConfirmSoundId = uiSoundPool.load(this, R.raw.ui_confirm, 1);
    uiSwitchSoundId = uiSoundPool.load(this, R.raw.ui_switch, 1);
}

private void releaseUiSoundPool() {
    if (uiSoundPool == null) return;
    try { uiSoundPool.release(); } catch (Throwable ignored) { }
    uiSoundPool = null;
    uiClickSoundId = 0;
    uiConfirmSoundId = 0;
    uiSwitchSoundId = 0;
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
    tintDialogRoot(root);
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
    // 昵称行：昵称 + 铅笔按钮（仅登录后可改云端昵称）
    LinearLayout nameRow = new LinearLayout(this);
    nameRow.setOrientation(LinearLayout.HORIZONTAL);
    nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    TextView nameView = new TextView(this);
    nameView.setText(currentName);
    nameView.setTextColor(getColorCompat(R.color.yh_text));
    nameView.setTextSize(20);
    nameView.setTypeface(null, android.graphics.Typeface.BOLD);
    nameView.setSingleLine(true);
    nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    // 昵称按内容宽度排布，铅笔才能紧跟其后（用 weight=1 会把铅笔推到行尾）。
    // 超长昵称用 maxWidth 兜底，省略号截断而不是把铅笔挤出屏幕。
    nameView.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.45f));
    nameRow.addView(nameView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    if (isLoggedIn()) {
        ImageView editNameBtn = new ImageView(this);
        editNameBtn.setImageResource(R.drawable.ic_edit_pencil);
        editNameBtn.setColorFilter(getColorCompat(R.color.yh_text_muted));
        editNameBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        editNameBtn.setContentDescription("修改昵称");
        int ep = dp(4);
        editNameBtn.setPadding(ep, ep, ep, ep);
        editNameBtn.setOnClickListener(v -> showCloudNicknameDialog(nameView));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        editLp.setMargins(dp(6), 0, 0, 0);
        nameRow.addView(editNameBtn, editLp);
    }
    TextView statsView = new TextView(this);
    String uid = prefs == null ? "" : prefs.getString(KEY_AUTH_UID, "");
    String uidStr = uid.isEmpty() ? "" : "UID: " + uid + "  ·  ";
    statsView.setText(uidStr + allGames.size() + " Games · " + TimeFormatUtil.playTime(total) + "\n" + emptyText(currentSignature, "这个人还没有写签名"));
    statsView.setTextColor(getColorCompat(R.color.yh_text_muted));
    statsView.setTextSize(12);
    statsView.setPadding(0, dp(5), 0, 0);
    info.addView(nameRow);
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
    // 等级 + 经验（仅登录用户，异步拉取服务器数据）。
    // 展示口径为「B站式继承条」：总经验 / 升到下一级的累计阈值（与网页端一致）
    if (isLoggedIn()) {
        final TextView levelView = new TextView(this);
        levelView.setText("Lv.1 · 0 EXP");
        levelView.setTextSize(11);
        levelView.setTextColor(getColorCompat(R.color.yh_text_muted));
        levelView.setPadding(0, dp(4), 0, 0);
        info.addView(levelView);
        AppExecutors.runOnIo(() -> {
            try {
                com.yuki.yukihub.social.SocialApiClient client = new com.yuki.yukihub.social.SocialApiClient(MainActivity.this);
                org.json.JSONObject lv = client.getMyLevel();
                final int level = lv.optInt("level", 1);
                final int totalExp = lv.optInt("exp", 0);
                final int nextTotal = lv.optInt("nextLevelTotalExp", totalExp + 100);
                final boolean maxed = lv.optBoolean("isMaxLevel", false) || level >= 30;
                final boolean checked = lv.optBoolean("todayCheckedIn", false);
                runOnUiThread(() -> {
                    levelView.setText(maxed
                            ? ("Lv." + level + " · " + totalExp + " EXP · 已满级")
                            : ("Lv." + level + " · " + totalExp + "/" + nextTotal + " EXP") + (checked ? " · 已签到" : ""));
                    levelView.setTextColor(levelColorForLevel(level));
                });
            } catch (Throwable ignored) {}
        });
    }
    info.addView(statsView);
    header.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    root.addView(header);

    TextView avatarHint = new TextView(this);
    avatarHint.setText(isLoggedIn() ? "当前为云账户，退出登录后会恢复本地资料显示。" : "当前为本地账户：昵称、头像和游戏数据只保存在本机。登录后可开启云同步和好友/聊天。点击头像可更换头像。");
    avatarHint.setTextColor(primaryTextColor());
    avatarHint.setTextSize(11);
    avatarHint.setPadding(0, dp(6), 0, dp(8));
    root.addView(avatarHint);

    LinearLayout accountRow = new LinearLayout(this);
    accountRow.setOrientation(LinearLayout.HORIZONTAL);
    Button loginBtn = krButton(isLoggedIn() ? "账号设置" : "登录 / 注册");
    Button syncBtn = krButton("云同步");
    loginBtn.setTextColor(primaryTextColor());
    syncBtn.setTextColor(isLoggedIn() ? primaryTextColor() : getColorCompat(R.color.yh_text_muted));
    syncBtn.setEnabled(isLoggedIn());
    loginBtn.setOnClickListener(v -> showAuthPlaceholderDialog());
    syncBtn.setOnClickListener(v -> {
        if (!isLoggedIn()) {
            Toast.makeText(this, "登录后即可使用云同步", Toast.LENGTH_SHORT).show();
            return;
        }
        doServerSync(false);
    });
    accountRow.addView(loginBtn, new LinearLayout.LayoutParams(0, dp(40), 1));
    LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(0, dp(40), 1);
    syncLp.setMargins(dp(8), 0, 0, 0);
    accountRow.addView(syncBtn, syncLp);
    root.addView(accountRow);

    LinearLayout kunRow = new LinearLayout(this);
    kunRow.setOrientation(LinearLayout.HORIZONTAL);
    kunRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    kunRow.setPadding(0, dp(8), 0, dp(4));
    ImageView kunIcon = new ImageView(this);
    kunIcon.setImageResource(R.drawable.ic_kungal_logo);
    kunIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    kunIcon.setAdjustViewBounds(true);
    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
    iconLp.setMargins(0, 0, dp(6), 0);
    kunRow.addView(kunIcon, iconLp);
    TextView kunStatus = new TextView(this);
    kunStatus.setText(kungalBound() ? "鲲 Galgame · 已绑定" : "鲲 Galgame · 未绑定");
    kunStatus.setTextColor(kungalBound() ? 0xFFE8FFE9 : getColorCompat(R.color.yh_text_muted));
    kunStatus.setTextSize(12);
    kunStatus.setGravity(android.view.Gravity.CENTER_VERTICAL);
    kunRow.addView(kunStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    Button kunBindBtn = krButton(kungalBound() ? "已绑定" : "绑定鲲账号");
    kunBindBtn.setTextColor(kungalBound() ? getColorCompat(R.color.yh_text_muted) : primaryTextColor());
    kunBindBtn.setEnabled(isLoggedIn() && !kungalBound());
    kunBindBtn.setOnClickListener(v -> startKungalBindOAuth());
    LinearLayout.LayoutParams kunBtnLp = new LinearLayout.LayoutParams(dp(118), dp(40));
    kunBtnLp.setMargins(dp(8), 0, 0, 0);
    kunRow.addView(kunBindBtn, kunBtnLp);
    root.addView(kunRow);

    LinearLayout hikariRow = new LinearLayout(this);
    hikariRow.setOrientation(LinearLayout.HORIZONTAL);
    hikariRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    hikariRow.setPadding(0, dp(8), 0, dp(4));
    ImageView hikariIcon = new ImageView(this);
    hikariIcon.setImageResource(R.drawable.ic_hikarinagi_logo);
    hikariIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    hikariIcon.setAdjustViewBounds(true);
    LinearLayout.LayoutParams hikariIconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
    hikariIconLp.setMargins(0, 0, dp(6), 0);
    hikariRow.addView(hikariIcon, hikariIconLp);
    TextView hikariStatus = new TextView(this);
    hikariStatus.setText(hikarinagiBound() ? "Hikarinagi · 已绑定" : "Hikarinagi · 未绑定");
    hikariStatus.setTextColor(hikarinagiBound() ? 0xFFE8FFE9 : getColorCompat(R.color.yh_text_muted));
    hikariStatus.setTextSize(12);
    hikariStatus.setGravity(android.view.Gravity.CENTER_VERTICAL);
    hikariRow.addView(hikariStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    Button hikariBindBtn = krButton(hikarinagiBound() ? "已绑定" : "绑定 Hikarinagi");
    hikariBindBtn.setTextColor(hikarinagiBound() ? getColorCompat(R.color.yh_text_muted) : primaryTextColor());
    hikariBindBtn.setEnabled(isLoggedIn() && !hikarinagiBound());
    hikariBindBtn.setOnClickListener(v -> startHikarinagiBindOAuth());
    LinearLayout.LayoutParams hikariBtnLp = new LinearLayout.LayoutParams(dp(118), dp(40));
    hikariBtnLp.setMargins(dp(8), 0, 0, 0);
    hikariRow.addView(hikariBindBtn, hikariBtnLp);
    root.addView(hikariRow);

    LinearLayout statCards = new LinearLayout(this);
    statCards.setOrientation(LinearLayout.HORIZONTAL);
    statCards.setPadding(0, dp(2), 0, dp(10));
    statCards.addView(profileStatCard("游戏", String.valueOf(allGames.size())), new LinearLayout.LayoutParams(0, dp(48), 1));
    LinearLayout.LayoutParams statMid = new LinearLayout.LayoutParams(0, dp(48), 1);
    statMid.setMargins(dp(6), 0, dp(6), 0);
    statCards.addView(profileStatCard("总时长", TimeFormatUtil.playTime(total)), statMid);
    statCards.addView(profileStatCard("今日", TimeFormatUtil.playTime(todayTotalPlayTime())), new LinearLayout.LayoutParams(0, dp(48), 1));
    root.addView(statCards);

    Button aiReviewBtn = krButton("AI 周点评");
    aiReviewBtn.setTextColor(primaryTextColor());
    aiReviewBtn.setOnClickListener(v -> aiReviewController.showAiReviewDialog());
    root.addView(aiReviewBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    TextView aiReviewHint = new TextView(this);
    aiReviewHint.setText("根据最近 7 天游玩记录生成小恶魔式锐评；只发送游戏名和统计，不发送路径、存档或账号信息。");
    aiReviewHint.setTextColor(getColorCompat(R.color.yh_text_muted));
    aiReviewHint.setTextSize(10);
    aiReviewHint.setPadding(0, dp(5), 0, dp(8));
    root.addView(aiReviewHint);

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
    syncCenterBtn.setTextColor(primaryTextColor());
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
    tintDialogRoot(scroll);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("个人资料")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("更换头像", null)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    pendingProfileDialog = dialog;
    dialog.setOnDismissListener(d -> { if (pendingProfileDialog == dialog) pendingProfileDialog = null; });
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.62f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
        String sign = signatureInput.getText() == null ? "" : signatureInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(MainActivity.this, "昵称不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(KEY_PROFILE_NAME, name).putString(KEY_PROFILE_SIGNATURE, sign).apply();
        updateProfilePanel();
        Toast.makeText(MainActivity.this, "个人资料已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    });
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> profileAvatarLauncher.launch("image/*"));
}

/** 等级颜色（与社区 Web 端每5级一档一致） */
private int levelColorForLevel(int level) {
    String hex = "#B9BCC7";
    if (level >= 30) hex = "#FFD27A";
    else if (level >= 25) hex = "#FF9090";
    else if (level >= 20) hex = "#FFB37A";
    else if (level >= 15) hex = "#C9A0FF";
    else if (level >= 10) hex = "#7DB8FF";
    else if (level >= 5) hex = "#7EE2A0";
    try {
        return android.graphics.Color.parseColor(hex);
    } catch (Throwable t) {
        return 0xFFB9BCC7;
    }
}

/**
 * 云端昵称修改弹窗（个人资料页头像右侧铅笔按钮触发）。
 * 调用服务器 /user/update_nickname 更新云端昵称主数据源，
 * 成功后同步更新本地 auth_nickname 缓存并刷新界面。
 */
private void showCloudNicknameDialog(final TextView nameViewToUpdate) {
    if (!isLoggedIn()) {
        Toast.makeText(this, "登录后才能修改云端昵称", Toast.LENGTH_SHORT).show();
        return;
    }
    final String current = displayProfileName();

    final EditText input = new EditText(this);
    input.setText(current);
    input.setHint("2-20 个字符");
    input.setSingleLine(true);
    input.setTextColor(getColorCompat(R.color.yh_text));
    input.setHintTextColor(getColorCompat(R.color.yh_text_muted));
    input.setBackgroundResource(R.drawable.bg_input);
    input.setPadding(dp(10), dp(8), dp(10), dp(8));
    if (input.getText() != null) input.setSelection(input.getText().length());

    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.setBackgroundResource(R.drawable.bg_dialog);
    tintDialogRoot(wrap);
    wrap.setPadding(dp(18), dp(14), dp(18), dp(10));
    wrap.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
    TextView tip = new TextView(this);
    tip.setText("昵称会同步到云端，好友和社区都能看到。不能包含 < > { } [ ] / \\ 等特殊字符。");
    tip.setTextColor(getColorCompat(R.color.yh_text_muted));
    tip.setTextSize(11);
    tip.setPadding(dp(2), dp(8), dp(2), 0);
    wrap.addView(tip);

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("修改昵称")
            .setView(wrap)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show();
    styleAlertDialogDark(dialog);

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        final String name = input.getText() == null ? "" : input.getText().toString().trim();
        if (name.length() < 2 || name.length() > 20) {
            Toast.makeText(MainActivity.this, "昵称需要2-20个字符", Toast.LENGTH_SHORT).show();
            return;
        }
        if (name.matches(".*[<>{}\\[\\]/\\\\].*")) {
            Toast.makeText(MainActivity.this, "昵称包含不允许的字符", Toast.LENGTH_SHORT).show();
            return;
        }
        v.setEnabled(false);
        AppExecutors.runOnIo(() -> {
            try {
                com.yuki.yukihub.social.SocialApiClient client = new com.yuki.yukihub.social.SocialApiClient(MainActivity.this);
                final String newName = client.updateNickname(name);
                runOnUiThread(() -> {
                    // 更新本地云端昵称缓存 + 本地昵称，避免下次同步被本地旧值覆盖
                    if (prefs != null) {
                        prefs.edit()
                                .putString(KEY_AUTH_NICKNAME, newName)
                                .putString(KEY_PROFILE_NAME, newName)
                                .apply();
                    }
                    if (nameViewToUpdate != null) nameViewToUpdate.setText(newName);
                    updateProfilePanel();
                    Toast.makeText(MainActivity.this, "昵称已修改", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    v.setEnabled(true);
                    Toast.makeText(MainActivity.this, "修改失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    });
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

private boolean kungalBound() {
    return prefs != null && prefs.getBoolean(KEY_KUN_BOUND, false);
}

private void startKungalBindOAuth() {
    if (!isLoggedIn()) {
        Toast.makeText(this, "请先登录 YukiHub 账号", Toast.LENGTH_SHORT).show();
        return;
    }
    try {
        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(64);
        String challenge = pkceS256(verifier);
        prefs.edit()
                .putString(KEY_KUN_OAUTH_STATE, state)
                .putString(KEY_KUN_OAUTH_CODE_VERIFIER, verifier)
                .putLong(KEY_KUN_OAUTH_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_KUN_OAUTH_MODE, KUN_OAUTH_MODE_BIND)
                .apply();
        Uri uri = Uri.parse(KUN_OAUTH_AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", KUN_ANDROID_CLIENT_ID)
                .appendQueryParameter("redirect_uri", KUN_OAUTH_REDIRECT_URI)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("state", state)
                .appendQueryParameter("scope", KUN_OAUTH_SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build();
        startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
    } catch (Throwable t) {
        Toast.makeText(this, "无法打开鲲站绑定页面：" + emptyText(t.getMessage(), "请检查浏览器"), Toast.LENGTH_LONG).show();
    }
}

private boolean hikarinagiBound() {
    return prefs != null && prefs.getBoolean(KEY_HIKARINAGI_BOUND, false);
}

private void startHikarinagiBindOAuth() {
    if (!isLoggedIn()) {
        Toast.makeText(this, "请先登录 YukiHub 账号", Toast.LENGTH_SHORT).show();
        return;
    }
    try {
        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(64);
        String challenge = pkceS256(verifier);
        String nonce = randomUrlSafe(16);
        prefs.edit()
                .putString(KEY_HIKARINAGI_OAUTH_STATE, state)
                .putString(KEY_HIKARINAGI_OAUTH_CODE_VERIFIER, verifier)
                .putString(KEY_HIKARINAGI_OAUTH_NONCE, nonce)
                .putLong(KEY_HIKARINAGI_OAUTH_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_HIKARINAGI_OAUTH_MODE, HIKARINAGI_OAUTH_MODE_BIND)
                .apply();
        Uri uri = Uri.parse(HIKARINAGI_OAUTH_AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", HIKARINAGI_ANDROID_CLIENT_ID)
                .appendQueryParameter("redirect_uri", HIKARINAGI_OAUTH_REDIRECT_URI)
                .appendQueryParameter("scope", HIKARINAGI_OAUTH_SCOPE)
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build();
        startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
    } catch (Throwable t) {
        Toast.makeText(this, "无法打开 Hikarinagi 绑定页面：" + emptyText(t.getMessage(), "请检查浏览器"), Toast.LENGTH_LONG).show();
    }
}

private String randomUrlSafe(int bytes) {
    byte[] data = new byte[bytes];
    new SecureRandom().nextBytes(data);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
}

private String pkceS256(String verifier) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
}

private void showAuthPlaceholderDialog() {
    if (isLoggedIn()) {
        showAccountSettingsDialog();
        return;
    }
    // 解除限制，直接跳转登录/注册界面
    startActivity(new Intent(this, AuthActivity.class));
}

private void showFriendsChatPlaceholder() {
    new com.yuki.yukihub.social.FriendsChatDialog(this).show();
}

private void showSortDialog() {
    String[] items = {"最近游玩", "最近添加", "名称排序"};
    String current = prefs == null ? SORT_MODE_RECENT : prefs.getString(KEY_SORT_MODE, SORT_MODE_RECENT);
    int checkedItem = SORT_MODE_NEWEST.equals(current) ? 1 : (SORT_MODE_NAME.equals(current) ? 2 : 0);
    final int[] selected = {checkedItem};
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("排序方式")
            .setSingleChoiceItems(items, checkedItem, (d, which) -> {
                selected[0] = which;
            })
            .setPositiveButton("确定", (d, w) -> {
                String mode = SORT_MODE_RECENT;
                if (selected[0] == 1) mode = SORT_MODE_NEWEST;
                else if (selected[0] == 2) mode = SORT_MODE_NAME;
                if (prefs != null) prefs.edit().putString(KEY_SORT_MODE, mode).apply();
                applyFilter();
            })
            .setNegativeButton("取消", null)
            .show();
    styleAlertDialogDark(dialog);
    // Color the radio list items (needs post to run after ListView measures children)
    try {
        android.widget.ListView list = dialog.getListView();
        if (list != null) {
            list.setBackgroundColor(Color.TRANSPARENT);
            list.post(() -> {
                for (int i = 0; i < list.getChildCount(); i++) {
                    View child = list.getChildAt(i);
                    if (child instanceof android.widget.CheckedTextView) {
                        ((android.widget.CheckedTextView) child).setTextColor(
                                i == selected[0] ? getColorCompat(R.color.yh_primary) : getColorCompat(R.color.yh_text));
                    }
                }
            });
            list.setOnItemClickListener((parent, view, position, lid) -> {
                selected[0] = position;
                list.post(() -> {
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View c = parent.getChildAt(i);
                        if (c instanceof android.widget.CheckedTextView) {
                            ((android.widget.CheckedTextView) c).setTextColor(
                                    i == position ? getColorCompat(R.color.yh_primary) : getColorCompat(R.color.yh_text));
                        }
                    }
                });
            });
        }
    } catch (Throwable ignored) { }
}

private void showNoticeDialog() {
    // 清除红点
    View dot = findViewById(R.id.noticeRedDot);
    if (dot != null) dot.setVisibility(View.GONE);
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("消息通知")
            .setMessage("暂无新消息。")
            .setPositiveButton("知道了", null)
            .show();
    styleAlertDialogDark(dialog);
}

private android.os.Handler statusBarHandler;
private Runnable statusBarRunnable;
private android.content.BroadcastReceiver batteryReceiver;

private void startStatusBarUpdates() {
    if (statusBarHandler == null) statusBarHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    if (statusBarRunnable == null) statusBarRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            updateChatBadge();
            if (statusBarHandler != null) statusBarHandler.postDelayed(this, 5000L);
        }
    };
    statusBarHandler.post(statusBarRunnable);
    updateBatteryLevel();
    registerBatteryReceiver();
}

private void stopStatusBarUpdates() {
    if (statusBarHandler != null && statusBarRunnable != null) {
        statusBarHandler.removeCallbacks(statusBarRunnable);
    }
    if (batteryReceiver != null) {
        try { unregisterReceiver(batteryReceiver); } catch (Throwable ignored) { }
        batteryReceiver = null;
    }
}

private void updateClock() {
    TextView tvClock = findViewById(R.id.tvClock);
    if (tvClock == null) return;
    java.util.Calendar cal = java.util.Calendar.getInstance();
    int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
    int minute = cal.get(java.util.Calendar.MINUTE);
    int second = cal.get(java.util.Calendar.SECOND);
    String timeStr = String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hour, minute, second);
    tvClock.setText("🕐 " + timeStr);
}

private void updateBatteryLevel() {
    try {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) return;
        int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int status = bm.isCharging() ? 1 : 0;
        TextView tvBattery = findViewById(R.id.tvBatteryLevel);
        if (tvBattery == null) return;
        String icon;
        int color;
        if (status == 1) {
            icon = "⚡";
            color = getColorCompat(R.color.yh_success);
        } else if (level <= 20) {
            icon = "🪫";
            color = getColorCompat(R.color.yh_secondary);
        } else {
            icon = "🔋";
            color = getColorCompat(R.color.yh_text);
        }
        tvBattery.setText(icon + level + "%");
        tvBattery.setTextColor(color);
    } catch (Throwable ignored) { }
}

private void updateChatBadge() {
    if (!isLoggedIn()) return;
    TextView badge = findViewById(R.id.navChatBadge);
    if (badge == null) return;
    com.yuki.yukihub.util.AppExecutors.runOnIo(() -> {
        try {
            com.yuki.yukihub.social.SocialApiClient client = new com.yuki.yukihub.social.SocialApiClient(this);
            int unread = client.getTotalUnread();
            // 调用 getFriendsList() 刷新 pendingRequests 缓存，再取好友申请数
            client.getFriendsList();
            int pending = client.getPendingRequestsCount();
            int total = unread + pending;
            runOnUiThread(() -> {
                if (total > 0) {
                    badge.setText(String.valueOf(total));
                    badge.setVisibility(View.VISIBLE);
                } else {
                    badge.setVisibility(View.GONE);
                }
            });
        } catch (Throwable ignored) { }
    });
}

private void registerBatteryReceiver() {
    if (batteryReceiver != null) return;
    batteryReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            updateBatteryLevel();
        }
    };
    android.content.IntentFilter filter = new android.content.IntentFilter();
    filter.addAction(android.content.Intent.ACTION_BATTERY_CHANGED);
    filter.addAction(android.content.Intent.ACTION_BATTERY_LOW);
    try { registerReceiver(batteryReceiver, filter); } catch (Throwable ignored) { }
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
    String uid = prefs == null ? "" : prefs.getString(KEY_AUTH_UID, "");
    boolean syncEnabled = prefs != null && prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false);
    boolean sharePlaying = prefs == null || prefs.getBoolean(KEY_SHARE_PLAYING, true);
    boolean friendNotify = prefs == null || prefs.getBoolean(KEY_FRIEND_PLAY_NOTIFY, true);
    long lastSync = prefs == null ? 0 : prefs.getLong(KEY_LAST_SYNC_AT, 0);
    info.setText("账号：" + name + "\nUID：" + (uid.isEmpty() ? "-" : uid) + "\n邮箱：" + emptyText(email, "-") + "\n状态：" + accountStatusLabelForDialog() + "\n自动同步：" + (syncEnabled ? "开启" : "关闭") + "\n最后同步：" + (lastSync > 0 ? TimeFormatUtil.date(lastSync) : "尚未同步"));
    info.setTextColor(getColorCompat(R.color.yh_text_muted));
    info.setTextSize(13);
    info.setLineSpacing(dp(2), 1.0f);
    root.addView(info);
    CheckBox syncCheck = krCheckBox("开启自动同步（启动时自动云同步）", syncEnabled);
    syncCheck.setPadding(0, dp(10), 0, 0);
    root.addView(syncCheck);
    CheckBox sharePlayingCheck = krCheckBox("向好友展示正在玩的游戏", sharePlaying);
    sharePlayingCheck.setPadding(0, dp(8), 0, 0);
    root.addView(sharePlayingCheck);
    CheckBox friendNotifyCheck = krCheckBox("好友开始玩游戏时通知（后台保活）", friendNotify);
    friendNotifyCheck.setPadding(0, dp(8), 0, 0);
    root.addView(friendNotifyCheck);
    TextView notifyHint = new TextView(this);
    notifyHint.setText("开启后会显示一条低调的常驻通知，用于保持在线并监听好友开玩。");
    notifyHint.setTextColor(getColorCompat(R.color.yh_text_muted));
    notifyHint.setTextSize(11);
    notifyHint.setPadding(0, dp(4), 0, 0);
    root.addView(notifyHint);
    // 通知通道设置入口：允许用户手动调整横幅/铃声/锁屏
    TextView channelSettingsBtn = new TextView(this);
    channelSettingsBtn.setText("⚙ 通知样式设置（横幅 · 铃声 · 锁屏）");
    channelSettingsBtn.setTextColor(getColorCompat(R.color.yh_primary));
    channelSettingsBtn.setTextSize(12);
    channelSettingsBtn.setPadding(0, dp(8), 0, 0);
    channelSettingsBtn.setOnClickListener(v -> {
        ensureNotificationPermission();
        // 如果权限已授予，直接打开通道设置；否则等权限回调自动引导
        if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            openChannelSettings();
        }
    });
    root.addView(channelSettingsBtn);

    // 包一层 ScrollView，防止小屏设备内容被遮挡
    ScrollView scrollView = new ScrollView(this);
    scrollView.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("账号设置")
            .setView(scrollView)
            .setPositiveButton("保存", null)
            .setNeutralButton("退出登录", null)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        boolean enableNotify = friendNotifyCheck.isChecked();
        prefs.edit()
                .putBoolean(KEY_CLOUD_SYNC_ENABLED, syncCheck.isChecked())
                .putBoolean(KEY_SHARE_PLAYING, sharePlayingCheck.isChecked())
                .putBoolean(KEY_FRIEND_PLAY_NOTIFY, enableNotify)
                .apply();
        if (enableNotify) ensureNotificationPermission();
        com.yuki.yukihub.social.PresenceService.sync(MainActivity.this);
        updateProfilePanel();
        Toast.makeText(MainActivity.this, "账号设置已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    });
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmLogout(dialog));
}

private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            // 先弹解释弹窗，再请求权限（QQ 做法）
            new AlertDialog.Builder(this)
                    .setTitle("需要通知权限")
                    .setMessage("YukiHub 需要通知权限才能在你玩游戏时提醒你「好友开始玩什么游戏」。\n\n点击「允许」后，系统会弹出权限请求。")
                    .setPositiveButton("继续", (d, w) -> {
                        d.dismiss();
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9910);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9910) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                // 权限已授予，引导用户打开通道详情页（横幅/铃声/锁屏可能默认开启）
                showNotificationChannelGuide();
            } else {
                // 用户拒绝：引导去系统设置手动开启
                showNotificationDeniedGuide();
            }
        }
    }

    /** 通知权限被拒绝后，引导用户去系统设置手动开启 */
    private void showNotificationDeniedGuide() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("通知权限未开启")
                    .setMessage("你拒绝了通知权限，好友开玩时将无法收到提醒。\n\n你可以随时去系统设置手动开启。")
                    .setPositiveButton("去设置", (d, w) -> {
                        d.dismiss();
                        openAppNotificationSettings();
                    })
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** 通知权限已授予后，引导用户确认通知通道设置（横幅/铃声/振动/锁屏） */
    private void showNotificationChannelGuide() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("通知已开启")
                    .setMessage("你可以进一步调整通知样式：是否弹出横幅、是否响铃、是否在锁屏显示。\n\n点击下方按钮进入通知通道设置。")
                    .setPositiveButton("去设置", (d, w) -> {
                        d.dismiss();
                        openChannelSettings();
                    })
                    .setNegativeButton("保持默认", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** 打开 App 通知设置页 */
    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable e) {
            // 降级：打开全部通知设置
            try {
                startActivity(new Intent("android.settings.NOTIFICATION_SETTINGS"));
            } catch (Throwable ignored) {}
        }
    }

    /** 直接打开 friend_play_v2 通知通道详情页（横幅/铃声/锁屏开关） */
    private void openChannelSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                intent.putExtra(Settings.EXTRA_CHANNEL_ID,
                        com.yuki.yukihub.social.FriendNotifier.CHANNEL_FRIEND_PLAY);
                startActivity(intent);
            }
        } catch (Throwable e) {
            openAppNotificationSettings();
        }
    }

private void confirmLogout(AlertDialog parent) {
    AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出登录不会删除本地游戏库和本地个人资料。云同步、好友/聊天将暂停。")
            .setPositiveButton("退出登录", (x, w) -> {
                logoutLocalOnly();
                if (parent != null) parent.dismiss();
                refreshRuntimeAccountState();
                if (pendingProfileDialog != null && pendingProfileDialog.isShowing()) pendingProfileDialog.dismiss();
                getWindow().getDecorView().postDelayed(() -> {
                    if (!isFinishing() && !(android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) showProfileDialog();
                }, 150L);
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
                    coverMaintenanceDone = false;
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

// ========== 服务器云同步 ==========

private boolean serverSyncRunning = false;

/**
 * 手动触发服务器云同步。
 * @param silent true=自动同步（不弹Toast），false=手动点击（有UI反馈）
 */
private void doServerSync(boolean silent) {
    if (serverSyncRunning) {
        if (!silent) Toast.makeText(this, "正在同步中…", Toast.LENGTH_SHORT).show();
        return;
    }
    serverSyncRunning = true;
    com.yuki.yukihub.sync.SyncManager sm = new com.yuki.yukihub.sync.SyncManager(this);
    if (!silent) Toast.makeText(this, "开始云同步…", Toast.LENGTH_SHORT).show();
    sm.syncToServer(new com.yuki.yukihub.sync.SyncManager.SyncListener() {
        @Override public void onSyncStart() { }
        @Override public void onProgress(String item, boolean changed) { }
        @Override public int onConflict(com.yuki.yukihub.sync.SyncManager.Conflict conflict) {
            return com.yuki.yukihub.sync.SyncManager.RESOLVE_MERGE;
        }
        @Override public void onSyncComplete(com.yuki.yukihub.sync.SyncManager.SyncResult result) {
            runOnUiThread(() -> {
                serverSyncRunning = false;
                if (result != null) {
                    coverMaintenanceDone = false;
                    loadGames();
                    updateProfilePanel();
                    if (!silent) {
                        int showBytes = result.compressedBytes > 0 ? result.compressedBytes : result.localBytes;
                        String msg;
                        if (result.merged) {
                            msg = "云同步完成 · 智能合并 · " + (showBytes / 1024) + "KB";
                        } else if (result.downloaded) {
                            msg = "云同步完成 · 已下载云端数据 · " + (showBytes / 1024) + "KB";
                        } else if (result.uploaded) {
                            msg = "云同步完成 · 已上传本地数据 · " + (showBytes / 1024) + "KB";
                        } else if (result.noChanges) {
                            msg = "云同步完成 · 数据已是最新 · " + (showBytes / 1024) + "KB";
                        } else if (result.cancelled) {
                            msg = "云同步已取消";
                        } else {
                            msg = "云同步完成 · " + (showBytes / 1024) + "KB";
                        }
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        @Override public void onError(String error) {
            runOnUiThread(() -> {
                serverSyncRunning = false;
                if (!silent) Toast.makeText(MainActivity.this, "云同步失败：" + error, Toast.LENGTH_LONG).show();
            });
        }
    });
}

/**
 * App 启动时自动服务器同步（静默）。
 * 1. 如果是登录后首次（needs_initial_sync=true），无视开关状态直接同步一次
 * 2. 否则检查自动同步开关，按常规逻辑走
 */
private void maybeAutoServerSync() {
    if (serverSyncRunning) return;
    if (!isLoggedIn()) return;

    // ===== 登录后首次同步（独立于自动同步开关）=====
    if (prefs != null && prefs.getBoolean("needs_initial_sync", false)) {
        prefs.edit().putBoolean("needs_initial_sync", false).apply();
        doServerSync(true);  // 静默同步
        return;
    }

    serverSyncRunning = true;
    com.yuki.yukihub.sync.SyncManager sm = new com.yuki.yukihub.sync.SyncManager(this);
    boolean triggered = sm.maybeAutoSyncToServer(new com.yuki.yukihub.sync.SyncManager.SyncListener() {
        @Override public void onSyncStart() { }
        @Override public void onProgress(String item, boolean changed) { }
        @Override public int onConflict(com.yuki.yukihub.sync.SyncManager.Conflict conflict) {
            return com.yuki.yukihub.sync.SyncManager.RESOLVE_MERGE;
        }
        @Override public void onSyncComplete(com.yuki.yukihub.sync.SyncManager.SyncResult result) {
            runOnUiThread(() -> {
                serverSyncRunning = false;
                if (result != null) {
                    if (result.hasChanges()) {
                        coverMaintenanceDone = false;
                        loadGames();
                        updateProfilePanel();
                    }
                    // 自动同步也弹窗，让用户知道发生了什么
                    int showBytes = result.compressedBytes > 0 ? result.compressedBytes : result.localBytes;
                    String msg;
                    if (result.merged) {
                        msg = "自动云同步 · 智能合并 · " + (showBytes / 1024) + "KB";
                    } else if (result.downloaded) {
                        msg = "自动云同步 · 已下载云端数据 · " + (showBytes / 1024) + "KB";
                    } else if (result.uploaded) {
                        msg = "自动云同步 · 已上传本地数据 · " + (showBytes / 1024) + "KB";
                    } else if (result.noChanges) {
                        msg = "自动云同步 · 数据已是最新";
                    } else if (result.cancelled) {
                        msg = "自动云同步已取消";
                    } else {
                        msg = "自动云同步完成 · " + (showBytes / 1024) + "KB";
                    }
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
        @Override public void onError(String error) {
            runOnUiThread(() -> {
                serverSyncRunning = false;
                Toast.makeText(MainActivity.this, "自动云同步失败：" + error, Toast.LENGTH_LONG).show();
            });
        }
    });
    if (!triggered) serverSyncRunning = false;
}

private void logoutLocalOnly() {
    // 停止心跳与后台服务，尽力标记离线
    try { com.yuki.yukihub.social.PresenceService.stop(this); } catch (Throwable ignored) {}
    if (presenceManager != null) presenceManager.markOffline();
    if (prefs != null) prefs.edit()
            .remove(KEY_AUTH_ACCESS_TOKEN)
            .remove(KEY_AUTH_REFRESH_TOKEN)
            .remove(KEY_AUTH_USER_ID)
            .remove(KEY_AUTH_UID)
            .remove(KEY_AUTH_NICKNAME)
            .remove(KEY_AUTH_AVATAR)
            .remove(KEY_AUTH_STATUS)
            .remove(KEY_KUN_BOUND)
            .remove(KEY_HIKARINAGI_BOUND)
            .remove("server_last_sync_hash")
            .remove(KEY_LAST_SYNC_AT)
            .remove("needs_initial_sync")
            .remove("current_playing_activity")
            .putBoolean(KEY_CLOUD_SYNC_ENABLED, false)
            .apply();
    updateProfilePanel();
    Toast.makeText(MainActivity.this, "已退出登录，本地账户仍可继续使用", Toast.LENGTH_SHORT).show();
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
                // 登录成功后自动触发一次云同步（有弹窗反馈）
                doServerSync(false);
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
            String uid = user.optString("uid", "");
            if (nickname != null && !nickname.isEmpty()) editor.putString(KEY_AUTH_NICKNAME, nickname);
            if (avatar != null && !avatar.isEmpty()) editor.putString(KEY_AUTH_AVATAR, avatar);
            if (!uid.isEmpty()) editor.putString(KEY_AUTH_UID, uid);
            editor.putBoolean(KEY_KUN_BOUND, user.optBoolean("kungalBound", false));
            editor.putBoolean(KEY_HIKARINAGI_BOUND, user.optBoolean("hikarinagiBound", false));
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
        // 检查是否是 403 禁用
        if (e.getMessage() != null && e.getMessage().contains("HTTP 403") && e.getMessage().contains("禁用")) {
            if (prefs != null) {
                prefs.edit()
                        .remove(KEY_AUTH_ACCESS_TOKEN)
                        .remove(KEY_AUTH_REFRESH_TOKEN)
                        .putString(KEY_AUTH_STATUS, AUTH_STATUS_EXPIRED)
                        .apply();
            }
            runOnUiThread(() -> {
                updateProfilePanel();
                Toast.makeText(this, "您的账号已被管理员禁用", Toast.LENGTH_LONG).show();
            });
            throw new RuntimeException("账号已被禁用");
        }
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
            .putString(KEY_AUTH_UID, (user != null ? user.optString("uid", "") : ""))
            .putString(KEY_AUTH_NICKNAME, nickname == null ? "" : nickname)
            .putString(KEY_AUTH_EMAIL, email == null ? "" : email)
            .putString(KEY_AUTH_AVATAR, avatar == null ? "" : avatar)
            .putString(KEY_AUTH_STATUS, AUTH_STATUS_ONLINE)
            .putBoolean(KEY_KUN_BOUND, user != null && user.optBoolean("kungalBound", false))
            .putBoolean(KEY_HIKARINAGI_BOUND, user != null && user.optBoolean("hikarinagiBound", false))
            .putBoolean(KEY_CLOUD_SYNC_ENABLED, false)
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
    backupCreateLauncher.launch("yukihub_backup_" + System.currentTimeMillis() + ".ykbak");
}

public void openLocalBackupImportFromSyncCenter() {
    backupOpenLauncher.launch(new String[]{"application/octet-stream", "application/json", "text/*", "*/*"});
}

private void exportLocalBackup(Uri uri) {
    try {
        JSONObject root = new com.yuki.yukihub.sync.SyncManager(this).exportSnapshotForLocalBackup();
        root.put("created_at", System.currentTimeMillis());
        root.put("backup_type", "local_full");
        root.put("note", "Local backup keeps the latest 30 play sessions. Uses gzip compression.");
        // toString() 而非 toString(2)：备份是机读文件，缩进只会白白撑大待压缩的字节数
        byte[] jsonBytes = root.toString().getBytes(StandardCharsets.UTF_8);
        // gzip 压缩后写入文件
        ByteArrayOutputStream gzipBos = new ByteArrayOutputStream(jsonBytes.length / 4);
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBos)) {
            gzip.write(jsonBytes);
            gzip.finish();
        }
        byte[] compressed = gzipBos.toByteArray();
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("openOutputStream failed");
            out.write(compressed);
            out.flush();
        }
        Toast.makeText(this, "备份完成：" + (compressed.length / 1024) + "KB（压缩后，原始 " + (jsonBytes.length / 1024) + "KB）", Toast.LENGTH_LONG).show();
} catch (Throwable t) {
            Toast.makeText(this, "备份失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            Log.e("YukiHub", "export backup failed", t);
        }
}

private void importLocalBackup(Uri uri) {
     try {
         // 读取原始字节，自动检测 gzip 或纯 JSON
         byte[] rawBytes;
         try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
             if (in == null) throw new Exception("openInputStream failed");
             byte[] buf = new byte[8192];
             int len;
             while ((len = in.read(buf)) != -1) bos.write(buf, 0, len);
             rawBytes = bos.toByteArray();
         }
         // gzip 文件头: 0x1f 0x8b
         String text;
         if (rawBytes.length >= 2 && (rawBytes[0] & 0xff) == 0x1f && (rawBytes[1] & 0xff) == 0x8b) {
             // gzip 压缩格式，解压
             try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(rawBytes)); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                 byte[] buf = new byte[8192];
                 int len;
                 while ((len = gzip.read(buf)) != -1) bos.write(buf, 0, len);
                 text = bos.toString("UTF-8");
             }
         } else {
             // 纯 JSON 文本，兼容老备份
             text = new String(rawBytes, StandardCharsets.UTF_8);
         }
         JSONObject root = new JSONObject(text);
         if (!"YukiHub".equals(root.optString("app", ""))) {
             Toast.makeText(this, "不是有效的 YukiHub 备份", Toast.LENGTH_LONG).show();
             return;
         }
         // 显示导入预览
         showYukiHubBackupPreview(root);
     } catch (Throwable t) {
         Toast.makeText(this, "读取备份失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
         Log.e("YukiHub", "read backup failed", t);
     }
 }

 /**
  * YukiHub 备份导入预览：展示备份中的游戏/记录/元数据统计，用户确认后导入。
  */
 private void showYukiHubBackupPreview(JSONObject root) {
     int gameCount = root.optJSONArray("games") == null ? 0 : root.optJSONArray("games").length();
     int sessionCount = root.optJSONArray("play_sessions") == null ? 0 : root.optJSONArray("play_sessions").length();
     int metaCount = root.optJSONArray("metadata_cache") == null ? 0 : root.optJSONArray("metadata_cache").length();
     String backupType = root.optString("backup_type", "local_full");
     String note = root.optString("note", "");
     // 备份 JSON 写的字段名是 "schema"（整数），不是 "schema_version"
     int schemaInt = root.optInt("schema", -1);
     String schemaVersion = schemaInt > 0 ? String.valueOf(schemaInt) : root.optString("schema_version", "未知");
     long createdAt = root.optLong("created_at", 0);

     // 统计新游戏 vs 已存在
     int newGames = 0, existingGames = 0;
     JSONArray gamesArr = root.optJSONArray("games");
     java.util.Set<String> existingNames = new java.util.HashSet<>();
     if (repository != null && repository.getAll() != null) {
         for (Game g : repository.getAll()) {
             if (g.title != null) existingNames.add(g.title.trim().toLowerCase());
         }
     }
     if (gamesArr != null) {
         for (int i = 0; i < gamesArr.length(); i++) {
             JSONObject o = gamesArr.optJSONObject(i);
             if (o == null) continue;
             String title = o.optString("title", "").trim().toLowerCase();
             if (title.isEmpty()) continue;
             if (existingNames.contains(title)) existingGames++;
             else newGames++;
         }
     }

     // 时间格式化
     String dateStr = "";
     if (createdAt > 0) {
         java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
         dateStr = sdf.format(new java.util.Date(createdAt));
     }

     int pad = dp(16);
     int itemSpacing = dp(10);

     LinearLayout listRoot = new LinearLayout(this);
     listRoot.setOrientation(LinearLayout.VERTICAL);
     listRoot.setPadding(pad, dp(14), pad, dp(14));
     // ── 备份信息卡片 ──
     LinearLayout infoCard = new LinearLayout(this);
     infoCard.setOrientation(LinearLayout.VERTICAL);
     infoCard.setPadding(pad, dp(12), pad, dp(12));
     infoCard.setBackgroundResource(R.drawable.bg_card);


     TextView infoHeader = new TextView(this);
     infoHeader.setText("📁 备份信息");
     infoHeader.setTextColor(getColorCompat(R.color.yh_text));
     infoHeader.setTextSize(14);
     infoHeader.setTypeface(null, android.graphics.Typeface.BOLD);
     infoHeader.setPadding(0, 0, 0, dp(8));
     infoCard.addView(infoHeader);

     // 用表格风格的两列展示信息
     infoCard.addView(makeInfoRow("版本", schemaVersion));
     infoCard.addView(makeInfoRow("类型", backupType));
     if (!dateStr.isEmpty()) infoCard.addView(makeInfoRow("备份时间", dateStr));

     listRoot.addView(infoCard);

     // 间距
     addVerticalSpace(listRoot, itemSpacing);

     // ── 数据统计卡片 ──
     LinearLayout statCard = new LinearLayout(this);
     statCard.setOrientation(LinearLayout.VERTICAL);
     statCard.setPadding(pad, dp(12), pad, dp(12));
     statCard.setBackgroundResource(R.drawable.bg_card);

     TextView statHeader = new TextView(this);
     statHeader.setText("📊 数据概览");
     statHeader.setTextColor(getColorCompat(R.color.yh_text));
     statHeader.setTextSize(14);
     statHeader.setTypeface(null, android.graphics.Typeface.BOLD);
     statHeader.setPadding(0, 0, 0, dp(8));
     statCard.addView(statHeader);

     // 三列统计
     LinearLayout statRow = new LinearLayout(this);
     statRow.setOrientation(LinearLayout.HORIZONTAL);
     statRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

     statRow.addView(makeStatBlock(gameCount + "", "游戏",
             newGames > 0 ? getColorCompat(R.color.yh_primary) : getColorCompat(R.color.yh_text)));
     statRow.addView(makeStatBlock(sessionCount + "", "游玩记录", getColorCompat(R.color.yh_text)));
     statRow.addView(makeStatBlock(metaCount + "", "元数据", getColorCompat(R.color.yh_text)));

     statCard.addView(statRow);

     // 新增/已存在标签
     TextView existInfo = new TextView(this);
     existInfo.setText("┃ 新增 " + newGames + " 个  ·  已存在 " + existingGames + " 个（将合并更新）");
     existInfo.setTextColor(newGames > 0 ? getColorCompat(R.color.yh_primary) : getColorCompat(R.color.yh_text_muted));
     existInfo.setTextSize(11);
     existInfo.setTypeface(null, android.graphics.Typeface.BOLD);
     existInfo.setPadding(0, dp(10), 0, 0);
     statCard.addView(existInfo);

     listRoot.addView(statCard);

     addVerticalSpace(listRoot, itemSpacing);

     // ── 导入策略卡片 ──
     LinearLayout strategyCard = new LinearLayout(this);
     strategyCard.setOrientation(LinearLayout.VERTICAL);
     strategyCard.setPadding(pad, dp(12), pad, dp(12));
     strategyCard.setBackgroundResource(R.drawable.bg_card);

     TextView strategyHeader = new TextView(this);
     strategyHeader.setText("ℹ️ 导入策略");
     strategyHeader.setTextColor(getColorCompat(R.color.yh_text));
     strategyHeader.setTextSize(14);
     strategyHeader.setTypeface(null, android.graphics.Typeface.BOLD);
     strategyHeader.setPadding(0, 0, 0, dp(8));
     strategyCard.addView(strategyHeader);

     String[] strategies = {
         "游戏按 rootUri/标题去重合并（字段更新，非跳过）",
         "游玩记录按 session_uuid 去重",
         "元数据缓存按来源 + source_id 匹配",
         "图片只恢复 URI/URL，不复制图片文件"
     };
     for (String s : strategies) {
         TextView item = new TextView(this);
         item.setText("• " + s);
         item.setTextColor(getColorCompat(R.color.yh_text_muted));
         item.setTextSize(10);
         item.setLineSpacing(dp(2), 1.0f);
         item.setPadding(0, dp(2), 0, dp(2));
         strategyCard.addView(item);
     }

     listRoot.addView(strategyCard);

     // 备注
     if (!note.isEmpty()) {
         addVerticalSpace(listRoot, itemSpacing);
         TextView noteText = new TextView(this);
         noteText.setText("💬 " + note);
         noteText.setTextColor(getColorCompat(R.color.yh_text_muted));
         noteText.setTextSize(10);
         noteText.setPadding(pad, dp(8), pad, dp(8));
         noteText.setBackgroundColor(0x00000000);
         listRoot.addView(noteText);
     }

     ScrollView scroll = new ScrollView(this);
     scroll.setFillViewport(false);
     scroll.setBackgroundResource(R.drawable.bg_dialog);
     tintDialogRoot(scroll);
     scroll.addView(listRoot, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

     AlertDialog dialog = new AlertDialog.Builder(this)
             .setTitle("YukiHub 备份导入预览")
             .setView(scroll)
             .setPositiveButton("确认导入", null)
             .setNegativeButton("取消", null)
             .show();
     styleAlertDialogDark(dialog);
     if (dialog.getWindow() != null) {
         dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.54f), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
     }
     dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
         dialog.dismiss();
         executeYukiHubBackupImport(root);
     });
 }

 /** 创建信息行：标签 + 值 */
 private LinearLayout makeInfoRow(String label, String value) {
     LinearLayout row = new LinearLayout(this);
     row.setOrientation(LinearLayout.HORIZONTAL);
     row.setGravity(android.view.Gravity.CENTER_VERTICAL);
     row.setPadding(0, dp(3), 0, dp(3));

     TextView labelView = new TextView(this);
     labelView.setText(label);
     labelView.setTextColor(getColorCompat(R.color.yh_text_muted));
     labelView.setTextSize(11);
     labelView.setTypeface(null, android.graphics.Typeface.BOLD);
     labelView.setMinWidth(dp(72));
     row.addView(labelView);

     TextView sepView = new TextView(this);
     sepView.setText("  :  ");
     sepView.setTextColor(getColorCompat(R.color.yh_text_muted));
     sepView.setTextSize(11);
     row.addView(sepView);

     TextView valueView = new TextView(this);
     valueView.setText(value);
     valueView.setTextColor(getColorCompat(R.color.yh_text));
     valueView.setTextSize(12);
     row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

     return row;
 }

 /** 创建统计块：大数字 + 小标签 */
 private LinearLayout makeStatBlock(String number, String label, int color) {
     LinearLayout block = new LinearLayout(this);
     block.setOrientation(LinearLayout.VERTICAL);
     block.setGravity(android.view.Gravity.CENTER);

     TextView numView = new TextView(this);
     numView.setText(number);
     numView.setTextColor(color);
     numView.setTextSize(22);
     numView.setTypeface(null, android.graphics.Typeface.BOLD);
     numView.setGravity(android.view.Gravity.CENTER);
     block.addView(numView);

     TextView labelView = new TextView(this);
     labelView.setText(label);
     labelView.setTextColor(getColorCompat(R.color.yh_text_muted));
     labelView.setTextSize(10);
     labelView.setGravity(android.view.Gravity.CENTER);
     labelView.setPadding(0, dp(2), 0, 0);
     block.addView(labelView);

     LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
     block.setLayoutParams(lp);
     return block;
 }

 /** 添加垂直间距 */
 private void addVerticalSpace(LinearLayout parent, int heightDp) {
     View space = new View(this);
     space.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightDp));
     parent.addView(space);
 }

 private void executeYukiHubBackupImport(JSONObject root) {
     showImportLoading("正在导入 YukiHub 备份…");
     new Thread(() -> {
         try {
             new com.yuki.yukihub.sync.SyncManager(this).importSnapshotFromLocalBackup(root);
             runOnUiThread(() -> {
                 hideImportLoading();
                 coverMaintenanceDone = false;
                 loadGames();
                 applyCustomBackground();
                 updateProfilePanel();
                 int gameCount = root.optJSONArray("games") == null ? 0 : root.optJSONArray("games").length();
                 int sessionCount = root.optJSONArray("play_sessions") == null ? 0 : root.optJSONArray("play_sessions").length();
                 int metaCount = root.optJSONArray("metadata_cache") == null ? 0 : root.optJSONArray("metadata_cache").length();
                 Toast.makeText(this, "导入完成：游戏 " + gameCount + "，记录 " + sessionCount + "，元数据 " + metaCount, Toast.LENGTH_LONG).show();
             });
         } catch (Exception e) {
             runOnUiThread(() -> {
                 hideImportLoading();
                 Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                 Log.e("YukiHub", "backup import failed", e);
             });
         }
     }).start();
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

 // ==================== 三方平台数据导入 ====================

/**
   * 弹出三方平台导入选择对话框。
   * 四个平台：Playnite / PotatoVN / Vnite / LunaBox
   */
  private void showExternalImportDialog() {
      int pad = dp(16);

      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setPadding(pad, dp(14), pad, dp(14));

      // 说明文字
      TextView intro = new TextView(this);
      intro.setText("选择要导入数据的平台，导入前会先预览所有可导入的游戏。");
      intro.setTextColor(getColorCompat(R.color.yh_text_muted));
      intro.setTextSize(11);
      intro.setLineSpacing(dp(2), 1.0f);
      intro.setPadding(0, 0, 0, dp(12));
      root.addView(intro);

      // 四个平台卡片
      String[][] platforms = {
          {"🎮  Playnite", "JSON 文件", "在 Playnite 中导出游戏库为 JSON",
           "application/json", "text/*", "*/*"},
          {"🥔  PotatoVN", "ZIP 文件", "包含游戏列表、封面和游玩记录",
           "application/zip", "application/octet-stream", "*/*"},
          {"📁  Vnite", "导出目录", "选择 Vnite 的导出文件夹",
           null, null, null},
          {"🌙  LunaBox", "ZIP 备份", "LunaBox 完整备份（游戏 + 封面 + 游玩记录）",
           "application/zip", "application/octet-stream", "*/*"}
      };

      for (int i = 0; i < platforms.length; i++) {
          final int idx = i;
          LinearLayout card = new LinearLayout(this);
          card.setOrientation(LinearLayout.HORIZONTAL);
          card.setGravity(android.view.Gravity.CENTER_VERTICAL);
          card.setPadding(pad, dp(12), pad, dp(12));
          card.setBackgroundResource(R.drawable.bg_card);

          // 左侧：图标 + 名称 + 格式
          LinearLayout textCol = new LinearLayout(this);
          textCol.setOrientation(LinearLayout.VERTICAL);
          textCol.setPadding(0, 0, dp(8), 0);

          TextView title = new TextView(this);
          title.setText(platforms[i][0]);
          title.setTextColor(getColorCompat(R.color.yh_text));
          title.setTextSize(14);
          title.setTypeface(null, android.graphics.Typeface.BOLD);
          textCol.addView(title);

          TextView subtitle = new TextView(this);
          subtitle.setText(platforms[i][1] + "  ·  " + platforms[i][2]);
          subtitle.setTextColor(getColorCompat(R.color.yh_text_muted));
          subtitle.setTextSize(10);
          subtitle.setPadding(0, dp(2), 0, 0);
          textCol.addView(subtitle);

          card.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

          // 右侧：箭头
          TextView arrow = new TextView(this);
          arrow.setText("→");
          arrow.setTextColor(getColorCompat(R.color.yh_primary));
          arrow.setTextSize(18);
          arrow.setTypeface(null, android.graphics.Typeface.BOLD);
          card.addView(arrow);

          // 点击事件
          card.setOnClickListener(v -> {
              switch (idx) {
                  case 0: // Playnite
                      playniteImportLauncher.launch(new String[]{"application/json", "text/*", "*/*"});
                      break;
                  case 1: // PotatoVN
                      potatovnImportLauncher.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
                      break;
                  case 2: // Vnite
                      vniteImportLauncher.launch(null);
                      break;
                  case 3: // LunaBox
                      lunaboxImportLauncher.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
                      break;
              }
          });

          LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
          if (i > 0) cardLp.setMargins(0, dp(8), 0, 0);
          root.addView(card, cardLp);
      }

      // 底部注意事项
      addVerticalSpace(root, dp(12));
      TextView notice = new TextView(this);
      notice.setText("⚠️ 注意：游戏路径是 PC 路径，导入后不设游戏目录。已存在的同名游戏会自动跳过。游玩记录会自动导入。");
      notice.setTextColor(getColorCompat(R.color.yh_text_muted));
      notice.setTextSize(9);
      notice.setLineSpacing(dp(2), 1.0f);
      root.addView(notice);

      ScrollView scroll = new ScrollView(this);
      scroll.setFillViewport(false);
      scroll.setBackgroundResource(R.drawable.bg_dialog);
      tintDialogRoot(scroll);
      scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

      AlertDialog dialog = new AlertDialog.Builder(this)
              .setTitle("从其他平台导入")
              .setView(scroll)
              .setNegativeButton("关闭", null)
              .show();
      styleAlertDialogDark(dialog);
      if (dialog.getWindow() != null) {
          dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.56f), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
      }
  }

 /** 存储待确认的导入候选项（预览阶段） */
 private java.util.List<com.yuki.yukihub.importer.ImportGameData> pendingImportGames;
 /** 存储待确认导入的数据源标签 */
 private String pendingImportSourceLabel;

 /**
  * 执行 Playnite JSON 导入（解析 → 预览）
  */
 private void doImportFromPlaynite(Uri uri) {
     showImportLoading("正在解析 Playnite 数据…");
     new Thread(() -> {
         try {
             java.util.List<com.yuki.yukihub.importer.ImportGameData> games =
                     com.yuki.yukihub.importer.PlayniteImporter.parse(this, uri);
             com.yuki.yukihub.importer.ImporterService service =
                     new com.yuki.yukihub.importer.ImporterService(this);
             service.markExisting(games);
             runOnUiThread(() -> {
                 hideImportLoading();
                 pendingImportGames = games;
                 pendingImportSourceLabel = "Playnite";
                 showImportPreviewDialog(games, "Playnite");
             });
         } catch (Exception e) {
             runOnUiThread(() -> {
                 hideImportLoading();
                 Toast.makeText(this, "Playnite 解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                 Log.e("YukiHub", "Playnite parse failed", e);
             });
         }
     }).start();
 }

 /**
  * 执行 PotatoVN ZIP 导入（解析 → 预览）
  */
 private void doImportFromPotatoVN(Uri uri) {
     showImportLoading("正在解析 PotatoVN 数据…");
     new Thread(() -> {
         try {
             java.util.List<com.yuki.yukihub.importer.ImportGameData> games =
                     com.yuki.yukihub.importer.PotatoVnImporter.parse(this, uri);
             com.yuki.yukihub.importer.ImporterService service =
                     new com.yuki.yukihub.importer.ImporterService(this);
             service.markExisting(games);
             runOnUiThread(() -> {
                 hideImportLoading();
                 pendingImportGames = games;
                 pendingImportSourceLabel = "PotatoVN";
                 showImportPreviewDialog(games, "PotatoVN");
             });
         } catch (Exception e) {
             runOnUiThread(() -> {
                 hideImportLoading();
                 Toast.makeText(this, "PotatoVN 解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                 Log.e("YukiHub", "PotatoVN parse failed", e);
             });
         }
     }).start();
 }

/**
     * 执行 Vnite 目录导入（解析 → 预览）
     */
    private void doImportFromVnite(Uri uri) {
        showImportLoading("正在解析 Vnite 数据…");
        new Thread(() -> {
            try {
                java.util.List<com.yuki.yukihub.importer.ImportGameData> games =
                        com.yuki.yukihub.importer.VniteImporter.parse(this, uri);
                com.yuki.yukihub.importer.ImporterService service =
                        new com.yuki.yukihub.importer.ImporterService(this);
                service.markExisting(games);
                runOnUiThread(() -> {
                    hideImportLoading();
                    pendingImportGames = games;
                    pendingImportSourceLabel = "Vnite";
                    showImportPreviewDialog(games, "Vnite");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideImportLoading();
                    Toast.makeText(this, "Vnite 解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e("YukiHub", "Vnite parse failed", e);
                });
            }
        }).start();
    }

    /**
     * 执行 LunaBox ZIP 备份导入（解析 → 预览）
     */
    private void doImportFromLunaBox(Uri uri) {
        showImportLoading("正在解析 LunaBox 备份…");
        new Thread(() -> {
            try {
                java.util.List<com.yuki.yukihub.importer.ImportGameData> games =
                        com.yuki.yukihub.importer.LunaBoxImporter.parse(this, uri);
                com.yuki.yukihub.importer.ImporterService service =
                        new com.yuki.yukihub.importer.ImporterService(this);
                service.markExisting(games);
                runOnUiThread(() -> {
                    hideImportLoading();
                    pendingImportGames = games;
                    pendingImportSourceLabel = "LunaBox";
                    showImportPreviewDialog(games, "LunaBox");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideImportLoading();
                    Toast.makeText(this, "LunaBox 解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e("YukiHub", "LunaBox parse failed", e);
                });
            }
        }).start();
    }

 // ==================== 导入预览对话框（通用） ====================

 /**
  * 通用导入预览对话框：展示候选项列表 + 勾选，用户确认后才实际导入。
  *
  * 列表内容：游戏名、开发商、数据源、状态标签（新增/已存在）
  * 底部按钮：全选/全不选、确认导入、取消
  */
 private void showImportPreviewDialog(java.util.List<com.yuki.yukihub.importer.ImportGameData> games, String sourceLabel) {
     if (games == null || games.isEmpty()) {
         Toast.makeText(this, "未找到可导入的游戏数据", Toast.LENGTH_SHORT).show();
         return;
     }

     int newCount = 0, existCount = 0, sessionCount = 0;
     for (com.yuki.yukihub.importer.ImportGameData g : games) {
         if (g.exists) existCount++;
         else newCount++;
if (g.playedTimeMap != null) sessionCount += g.playedTimeMap.size();
            else if (g.vniteTimers != null) sessionCount += g.vniteTimers.size();
            else if (g.lunaBoxSessions != null) sessionCount += g.lunaBoxSessions.size();
     }

     // 列表容器
     LinearLayout listRoot = new LinearLayout(this);
     listRoot.setOrientation(LinearLayout.VERTICAL);
     listRoot.setPadding(dp(8), dp(8), dp(8), dp(8));

     // 摘要
     TextView summary = new TextView(this);
     summary.setText("来源：" + sourceLabel + "  ·  共 " + games.size() + " 个游戏"
             + "  ·  新增 " + newCount + "  ·  已存在 " + existCount
             + (sessionCount > 0 ? "  ·  游玩记录 " + sessionCount : ""));
     summary.setTextColor(getColorCompat(R.color.yh_text_muted));
     summary.setTextSize(11);
     summary.setPadding(dp(4), dp(4), dp(4), dp(8));
     listRoot.addView(summary);

     // 全选/全不选按钮行
     LinearLayout actionRow = new LinearLayout(this);
     actionRow.setOrientation(LinearLayout.HORIZONTAL);
     actionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
     Button selectAllBtn = krButton("全选新游戏");
     Button deselectAllBtn = krButton("取消选择");
     selectAllBtn.setTextColor(primaryTextColor());
     deselectAllBtn.setTextColor(getColorCompat(R.color.yh_text));
     actionRow.addView(selectAllBtn, new LinearLayout.LayoutParams(0, dp(36), 1));
     LinearLayout.LayoutParams deselectLp = new LinearLayout.LayoutParams(0, dp(36), 1);
     deselectLp.setMargins(dp(6), 0, 0, 0);
     actionRow.addView(deselectAllBtn, deselectLp);

     LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
     actionRowLp.setMargins(0, 0, 0, dp(4));
     listRoot.addView(actionRow, actionRowLp);

     // 候选项列表（带勾选框）
     LinearLayout itemsContainer = new LinearLayout(this);
     itemsContainer.setOrientation(LinearLayout.VERTICAL);
     listRoot.addView(itemsContainer);

     // 渲染每个候选项
     java.util.List<CheckBox> checkBoxes = new java.util.ArrayList<>();
     for (int i = 0; i < games.size(); i++) {
         com.yuki.yukihub.importer.ImportGameData g = games.get(i);
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(LinearLayout.HORIZONTAL);
         row.setGravity(android.view.Gravity.CENTER_VERTICAL);
         row.setPadding(dp(4), dp(6), dp(4), dp(6));

         CheckBox cb = new CheckBox(this);
         cb.setChecked(g.selected);
         cb.setEnabled(!g.exists);
         if (g.exists) {
             cb.setAlpha(0.4f);
         }
         checkBoxes.add(cb);
         row.addView(cb);

         // 文字部分：游戏名 + 附加信息
         LinearLayout textCol = new LinearLayout(this);
         textCol.setOrientation(LinearLayout.VERTICAL);
         textCol.setPadding(dp(8), 0, 0, 0);

         TextView nameText = new TextView(this);
         String nameDisplay = g.name;
         if (g.developer != null && !g.developer.isEmpty()) nameDisplay += "  · " + g.developer;
         if (g.sourceType != null && !g.sourceType.isEmpty() && !"local".equals(g.sourceType)) {
             nameDisplay += "  [" + g.sourceType.toUpperCase() + "]";
         }
         nameText.setText(nameDisplay);
         nameText.setTextColor(g.exists ? getColorCompat(R.color.yh_text_muted) : getColorCompat(R.color.yh_text));
         nameText.setTextSize(13);
         nameText.setTypeface(null, android.graphics.Typeface.BOLD);
         textCol.addView(nameText);

         // 第二行：状态信息
         TextView infoText = new TextView(this);
         StringBuilder info = new StringBuilder();
         if (g.exists) {
             info.append("已存在，将跳过");
         } else {
             info.append("新增");
         }
         // 游玩状态标签
         if (g.playStatus != null && !g.playStatus.isEmpty() && !"unplayed".equals(g.playStatus)) {
             if ("completed".equals(g.playStatus)) info.append("  · 🏆已完成");
             else if ("playing".equals(g.playStatus)) info.append("  · 🎮在玩");
             else if ("onhold".equals(g.playStatus)) info.append("  · ⏸搁置");
             else if ("dropped".equals(g.playStatus)) info.append("  · 🗑抛弃");
         }
         if (g.nsfw) info.append("  · 🔞NSFW");
if (g.playedTimeMap != null && !g.playedTimeMap.isEmpty()) info.append("  · 游玩记录 ").append(g.playedTimeMap.size()).append(" 天");
          if (g.vniteTimers != null && !g.vniteTimers.isEmpty()) info.append("  · 游玩记录 ").append(g.vniteTimers.size()).append(" 条");
          if (g.lunaBoxSessions != null && !g.lunaBoxSessions.isEmpty()) info.append("  · 游玩记录 ").append(g.lunaBoxSessions.size()).append(" 条");
         if (g.coverUrl != null && !g.coverUrl.isEmpty()) info.append("  · 有封面");
         if (g.coverLocalPath != null && !g.coverLocalPath.isEmpty()) info.append("  · 有封面");
         if (g.description != null && !g.description.isEmpty()) info.append("  · 有简介");
         infoText.setText(info.toString());
         infoText.setTextColor(getColorCompat(R.color.yh_text_muted));
         infoText.setTextSize(10);
         textCol.addView(infoText);

         row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

         itemsContainer.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

         // 勾选变化监听
         final int idx = i;
         cb.setOnCheckedChangeListener((button, isChecked) -> {
             games.get(idx).selected = isChecked;
         });
     }

     // 全选/取消全选逻辑
     selectAllBtn.setOnClickListener(v -> {
         for (int i = 0; i < games.size(); i++) {
             if (!games.get(i).exists) {
                 games.get(i).selected = true;
                 checkBoxes.get(i).setChecked(true);
             }
         }
     });
     deselectAllBtn.setOnClickListener(v -> {
         for (int i = 0; i < games.size(); i++) {
             if (!games.get(i).exists) {
                 games.get(i).selected = false;
                 checkBoxes.get(i).setChecked(false);
             }
         }
     });

     // 滚动容器
     ScrollView scroll = new ScrollView(this);
     scroll.setFillViewport(false);
     scroll.setBackgroundResource(R.drawable.bg_dialog);
     tintDialogRoot(scroll);
     scroll.addView(listRoot, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

     AlertDialog dialog = new AlertDialog.Builder(this)
             .setTitle("导入预览 — " + sourceLabel)
             .setView(scroll)
             .setPositiveButton("确认导入", null)
             .setNegativeButton("取消", null)
             .show();
     styleAlertDialogDark(dialog);
     if (dialog.getWindow() != null) {
         dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.56f), (int) (getResources().getDisplayMetrics().heightPixels * 0.78f));
     }
     // 用PositiveButton的独立ClickListener防止对话框自动关闭
     dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
         dialog.dismiss();
         executeExternalImport(games, sourceLabel);
     });
 }

 /**
  * 执行确认后的导入（后台线程）
  */
private void executeExternalImport(java.util.List<com.yuki.yukihub.importer.ImportGameData> games, String sourceLabel) {
      int selectedCount = 0;
      if (games != null) {
          for (com.yuki.yukihub.importer.ImportGameData g : games) {
              if (g != null && g.selected && !g.exists) selectedCount++;
          }
      }
      showImportLoading("正在导入" + sourceLabel + "数据…\n准备处理 " + selectedCount + " 个游戏", true, Math.max(1, selectedCount));
      new Thread(() -> {
          try {
              com.yuki.yukihub.importer.ImporterService service =
                      new com.yuki.yukihub.importer.ImporterService(this);
              final com.yuki.yukihub.importer.ImportResult result = service.importSelected(games, (current, total, itemName) -> {
                  String name = itemName == null || itemName.trim().isEmpty() ? "未命名游戏" : itemName.trim();
                  runOnUiThread(() -> updateImportLoading("正在导入 " + sourceLabel + " 数据…\n" + current + "/" + total + "  《" + name + "》", current, total));
              });
              runOnUiThread(() -> {
                  hideImportLoading();
                  afterExternalImport(result);
              });
          } catch (Exception e) {
              runOnUiThread(() -> {
                  hideImportLoading();
                  Toast.makeText(this, sourceLabel + " 导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                  Log.e("YukiHub", sourceLabel + " import failed", e);
              });
          }
      }).start();
  }

 /**
  * 外部平台导入完成后的统一处理
  */
 private void afterExternalImport(com.yuki.yukihub.importer.ImportResult result) {
     coverMaintenanceDone = false;
     loadGames();
     updateProfilePanel();
     String msg = "导入完成：" + result.toString();
     if (!result.skippedNames.isEmpty()) {
         msg += "\n跳过：" + String.join("、", result.skippedNames);
     }
     if (!result.failedNames.isEmpty()) {
         msg += "\n失败：" + String.join("、", result.failedNames);
     }
     Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
 }

// 导入进度对话框
  private AlertDialog importLoadingDialog;
  private TextView importLoadingText;
  private ProgressBar importProgressBar;
  private void showImportLoading(String message) {
      showImportLoading(message, false, 0);
  }

  private void showImportLoading(String message, boolean determinate, int max) {
      if (importLoadingDialog != null) importLoadingDialog.dismiss();
      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setPadding(dp(18), dp(12), dp(18), dp(8));
      root.setBackgroundResource(R.drawable.bg_dialog);

      importLoadingText = new TextView(this);
      importLoadingText.setText(message);
      importLoadingText.setTextColor(getColorCompat(R.color.yh_text));
      importLoadingText.setTextSize(12);
      importLoadingText.setLineSpacing(dp(2), 1.0f);
      root.addView(importLoadingText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

      importProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      importProgressBar.setIndeterminate(!determinate);
      importProgressBar.setMax(Math.max(1, max));
      importProgressBar.setProgress(0);
      LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
      barLp.setMargins(0, dp(12), 0, 0);
      root.addView(importProgressBar, barLp);

      importLoadingDialog = new AlertDialog.Builder(this)
              .setTitle("数据导入")
              .setView(root)
              .setCancelable(false)
              .show();
      styleAlertDialogDark(importLoadingDialog);
  }

  private void updateImportLoading(String message, int progress, int max) {
      if (importLoadingText != null) importLoadingText.setText(message);
      if (importProgressBar != null) {
          importProgressBar.setIndeterminate(false);
          importProgressBar.setMax(Math.max(1, max));
          importProgressBar.setProgress(Math.max(0, Math.min(progress, Math.max(1, max))));
      }
  }

  private void hideImportLoading() {
      if (importLoadingDialog != null) {
          importLoadingDialog.dismiss();
          importLoadingDialog = null;
      }
      importLoadingText = null;
      importProgressBar = null;
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
        item.setOnClickListener(v -> { playUiSound(UI_SOUND_CLICK); showPlayActivityDetail(a); });
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
        if (tvStatsGames != null) tvStatsGames.setText(String.valueOf(allGames.size()));
        if (tvStatsTime != null) tvStatsTime.setText(TimeFormatUtil.playTime(total));
        if (tvGreeting != null) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            String period = hour < 5 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
            tvGreeting.setText(period + "，" + name);
        }
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
    // 始终使用 profile_avatar 显示（本地 file:// 路径），
    // auth_avatar 仅用于标记是否已上传到服务器，不用于显示
    String uri = prefs == null ? "" : prefs.getString(KEY_PROFILE_AVATAR, "");
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
    View scroll = findViewById(R.id.developerScroll);
    if (title == null || scroll == null) return;
    title.setOnClickListener(v -> {
        playUiSound(UI_SOUND_SWITCH);
        boolean show = scroll.getVisibility() != View.VISIBLE;
        // 两个二级面板互斥：展开一个就收起另一个，避免占两行高度
        if (show) collapseStatusPanel();
        scroll.setVisibility(show ? View.VISIBLE : View.GONE);
        title.setText(show ? "▾ 开发商" : "▸ 开发商");
    });
}

// ==================== 游玩状态二级分类 ====================

/** 游玩状态二级分类：{筛选值, 显示文案}，顺序即展示顺序。 */
private static final String[][] STATUS_FILTERS = {
        {"", "全部"},
        {"PLAYING", "🎮 在玩"},
        {"COMPLETED", "🏆 玩过"},
        {"UNPLAYED", "☆ 未玩"},
        {"ONHOLD", "⏸ 搁置"},
        {"DROPPED", "🗑 抛弃"},
};

/** 当前选中的游玩状态筛选值，空串表示不按状态过滤。 */
private String statusFilter = "";

private void setupStatusToggle() {
    TextView title = findViewById(R.id.filterStatus);
    View scroll = findViewById(R.id.statusScroll);
    if (title == null || scroll == null) return;
    title.setOnClickListener(v -> {
        playUiSound(UI_SOUND_SWITCH);
        boolean show = scroll.getVisibility() != View.VISIBLE;
        if (show) collapseDeveloperPanel();
        scroll.setVisibility(show ? View.VISIBLE : View.GONE);
        updateStatusTitle();
    });
    rebuildStatusFilters();
}

private void collapseStatusPanel() {
    View scroll = findViewById(R.id.statusScroll);
    if (scroll != null) scroll.setVisibility(View.GONE);
    updateStatusTitle();
}

private void collapseDeveloperPanel() {
    View scroll = findViewById(R.id.developerScroll);
    TextView title = findViewById(R.id.filterDeveloper);
    if (scroll != null) scroll.setVisibility(View.GONE);
    if (title != null) title.setText("▸ 开发商");
}

/**
 * 一级标题文案：收起时若已选了具体状态就直接显示该状态，
 * 这样面板收起后依然能看出当前在筛什么。
 */
private void updateStatusTitle() {
    TextView title = findViewById(R.id.filterStatus);
    View scroll = findViewById(R.id.statusScroll);
    if (title == null) return;
    boolean expanded = scroll != null && scroll.getVisibility() == View.VISIBLE;
    String label = "游玩状态";
    if (!expanded && statusFilter != null && !statusFilter.isEmpty()) {
        for (String[] item : STATUS_FILTERS) {
            if (item[0].equals(statusFilter)) { label = item[1]; break; }
        }
    }
    title.setText((expanded ? "▾ " : "▸ ") + label);
}

private void rebuildStatusFilters() {
    LinearLayout list = findViewById(R.id.statusList);
    if (list == null) return;
    list.removeAllViews();
    for (String[] item : STATUS_FILTERS) {
        list.addView(statusFilterItem(item[1], item[0]));
    }
    updateStatusFilterSelection();
}

private TextView statusFilterItem(String text, String value) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTag(value == null ? "" : value);
    v.setGravity(android.view.Gravity.CENTER);
    v.setTextSize(8);
    v.setSingleLine(true);
    v.setBackgroundResource(R.drawable.bg_input);
    v.setTextColor(getColorCompat(R.color.yh_text));
    v.setPadding(dp(10), 0, dp(10), 0);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(26));
    lp.setMarginStart(dp(4));
    v.setLayoutParams(lp);
    v.setMinWidth(dp(48));
    v.setClickable(true);
    v.setFocusable(true);
    v.setOnClickListener(view -> {
        playUiSound(UI_SOUND_CLICK);
        statusFilter = value == null ? "" : value;
        // 选了具体状态就退出「收藏 / 本地游戏」这类互斥的一级分类，
        // 否则两个条件叠加会让用户以为筛选没生效。
        if (!statusFilter.isEmpty() && !"ALL".equals(filter)
                && !isEngineFilter(filter)) {
            filter = "ALL";
        }
        updateStatusFilterSelection();
        updateFilterSelection();
        applyFilter();
    });
    return v;
}

/**
 * 状态筛选值（大写）→ 数据库里的状态值（小写）。
 */
private String statusFilterValue(String filterValue) {
    if (filterValue == null) return "";
    switch (filterValue) {
        case "PLAYING": return "playing";
        case "COMPLETED": return "completed";
        case "ONHOLD": return "onhold";
        case "DROPPED": return "dropped";
        case "UNPLAYED": return "unplayed";
        default: return "";
    }
}

/** 引擎类一级分类可以和状态并存（例如「KRKR + 在玩」）。 */
private boolean isEngineFilter(String value) {
    if (value == null) return false;
    switch (value) {
        case "KIRIKIRI": case "ONS": case "TYRANO": case "ARTEMIS":
        case "WINLATOR": case "GAMEHUB": case "PSP": case "ANDROID":
        case "UNKNOWN":
            return true;
        default:
            return false;
    }
}

private void updateStatusFilterSelection() {
    LinearLayout list = findViewById(R.id.statusList);
    updateStatusTitle();
    if (list == null) return;
    for (int i = 0; i < list.getChildCount(); i++) {
        View child = list.getChildAt(i);
        if (!(child instanceof TextView)) continue;
        String value = child.getTag() instanceof String ? (String) child.getTag() : "";
        boolean selected = (statusFilter == null ? "" : statusFilter).equals(value);
        TextView tv = (TextView) child;
        child.setAlpha(selected ? 1f : 0.82f);
        if (selected) {
            child.setBackgroundResource(R.drawable.bg_yuki_button);
            tv.setTextColor(0xFF071221);
        } else {
            child.setBackgroundResource(R.drawable.bg_input);
            tv.setTextColor(getColorCompat(R.color.yh_text));
        }
        tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }
}

private void rebuildDeveloperFilters() {
    LinearLayout list = findViewById(R.id.developerList);
    View scroll = findViewById(R.id.developerScroll);
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
        if (scroll != null) scroll.setVisibility(View.GONE);
        title.setText("▸ 开发商");
        return;
    }
    title.setAlpha(1f);
    list.addView(sidebarDeveloperItem("全部", ""));
    // 按游戏数降序，数量相同按名称，常玩的开发商排在前面便于横向查找
    List<java.util.Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
    java.util.Collections.sort(entries, (a, b) -> {
        int r = b.getValue().compareTo(a.getValue());
        if (r != 0) return r;
        return a.getKey().compareToIgnoreCase(b.getKey());
    });
    for (java.util.Map.Entry<String, Integer> e : entries) {
        list.addView(sidebarDeveloperItem(e.getKey() + " · " + e.getValue(), e.getKey()));
    }
    updateDeveloperFilterSelection();
}
private TextView sidebarDeveloperItem(String text, String developer) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTag(developer == null ? "" : developer);
    v.setGravity(android.view.Gravity.CENTER);
    v.setTextSize(8);
    v.setSingleLine(true);
    v.setMaxWidth(dp(150));
    v.setEllipsize(android.text.TextUtils.TruncateAt.END);
    v.setBackgroundResource(R.drawable.bg_input);
    v.setTextColor(getColorCompat(R.color.yh_text));
    v.setPadding(dp(10), 0, dp(10), 0);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(26));
    lp.setMarginStart(dp(4));
    v.setLayoutParams(lp);
    v.setMinWidth(dp(48));
    v.setClickable(true);
    v.setFocusable(true);
    v.setOnClickListener(view -> {
        playUiSound(UI_SOUND_CLICK);
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
        TextView tv = (TextView) child;
        child.setAlpha(selected ? 1f : 0.82f);
        if (selected) {
            child.setBackgroundResource(R.drawable.bg_yuki_button);
            tv.setTextColor(0xFF071221);
        } else {
            child.setBackgroundResource(R.drawable.bg_input);
            tv.setTextColor(getColorCompat(R.color.yh_text));
        }
        tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }
}

private String developerOf(Game game) {
    if (game == null) return "";
    VnMetadata meta = anyCachedMetadata(game.id);
return meta == null ? "" : emptyText(meta.developer, "");
}

private void bindFilter(int id, String value) {
        View item = findViewById(id);
        item.setOnClickListener(v -> {
            playUiSound(UI_SOUND_CLICK);
            filter = value;
            developerFilter = "";
            // 点「全部 / 收藏 / 本地游戏」视为重置筛选，状态二级分类一并清空
            statusFilter = "";
            if ("LOCAL".equals(value)) localInstalledCache.clear(); // 进入本地游戏分类：重新检测最新文件状态
            updateFilterSelection();
            applyFilter();
        });
    }

    private void updateFilterSelection() {
        updateFilterItem(R.id.filterAll, "ALL");
        updateFilterItem(R.id.filterFavorite, "FAVORITE");
        updateFilterItem(R.id.filterRecent, "LOCAL");
        updateDeveloperFilterSelection();
        updateStatusFilterSelection();
    }

    private void updateFilterItem(int id, String value) {
        View view = findViewById(id);
        if (view == null) return;
        boolean selected = value.equals(filter);
        view.setAlpha(selected ? 1f : 0.82f);
        if (view instanceof TextView) {
            if (selected) {
                view.setBackgroundResource(R.drawable.bg_yuki_button);
                ((TextView) view).setTextColor(0xFF071221);
            } else {
                view.setBackgroundResource(R.drawable.bg_input);
                ((TextView) view).setTextColor(getColorCompat(R.color.yh_text));
            }
            ((TextView) view).setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }
private void loadGames() {
allGames.clear();
allGames.addAll(repository.getAll());
rebuildDeveloperFilters();
applyFilter();
if (adapter != null) adapter.setNsfwBlurEnabled(nsfwBlurEnabled());
runCoverMaintenanceOnceIfNeeded();
    }

private void runCoverMaintenanceOnceIfNeeded() {
if (coverMaintenanceDone) return;
coverMaintenanceDone = true;
repairMissingMetadataCoversIfNeeded();
scanMissingCoversIfNeeded();
}

    /** 判断游戏是否已安装：rootUri 对应的目录/文件真实存在（兼容 file:// 与 content://）。带缓存，避免分类内重复查询。 */
    private boolean isGameInstalled(Game g) {
        if (g == null) return false;
        if (g.engine == EngineType.ANDROID) {
            String pkg = g.emulatorPackage == null ? "" : g.emulatorPackage.trim();
            if (pkg.isEmpty()) return false;
            String key = "android:" + pkg;
            Boolean cached = localInstalledCache.get(key);
            if (cached != null) return cached;
            boolean installed = false;
            try { installed = getPackageManager().getLaunchIntentForPackage(pkg) != null; } catch (Throwable ignored) { }
            localInstalledCache.put(key, installed);
            return installed;
        }
        String rootUri = g.rootUri == null ? "" : g.rootUri.trim();
        if (rootUri.isEmpty()) return false;
        Boolean cached = localInstalledCache.get(rootUri);
        if (cached != null) return cached;
        boolean installed = checkRootExists(rootUri);
        localInstalledCache.put(rootUri, installed);
        return installed;
    }

    private boolean checkRootExists(String rootUri) {
        try {
            DocumentFile dir = documentDir(rootUri);
            return dir != null && dir.exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyFilter() {
        List<Game> shown = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        long total = 0;
        for (Game g : allGames) {
            total += g.totalPlayTime;
            if (!q.isEmpty() && (g.title == null || !g.title.toLowerCase(Locale.ROOT).contains(q))) continue;
            if ("LOCAL".equals(filter) && !isGameInstalled(g)) continue;
            if ("FAVORITE".equals(filter) && !g.favorite) continue;
            // 游玩状态是独立维度（statusFilter），可与引擎分类、开发商叠加
            if (statusFilter != null && !statusFilter.isEmpty()
                    && !statusFilterValue(statusFilter).equals(normalizePlayStatus(g.playStatus))) continue;
            if ("KIRIKIRI".equals(filter) && g.engine != EngineType.KIRIKIRI) continue;
        if ("ONS".equals(filter) && g.engine != EngineType.ONS) continue;
        if ("TYRANO".equals(filter) && g.engine != EngineType.TYRANO) continue;
        if ("ARTEMIS".equals(filter) && g.engine != EngineType.ARTEMIS) continue;
        if ("WINLATOR".equals(filter) && g.engine != EngineType.WINLATOR) continue;
        if ("GAMEHUB".equals(filter) && g.engine != EngineType.GAMEHUB) continue;
        if ("PSP".equals(filter) && g.engine != EngineType.PSP) continue;
        if ("ANDROID".equals(filter) && g.engine != EngineType.ANDROID) continue;
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
        tvStatsGames.setText(String.valueOf(allGames.size()));
        tvStatsTime.setText(TimeFormatUtil.playTime(total));
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

private void invalidateRemoteImageRequest(ImageView target) {
    if (target == null) return;
    target.setTag(R.id.tag_remote_image_request, "cancel:" + System.nanoTime());
}

private void loadRemoteImage(String url, ImageView target, String prefix) {
    if (target == null) return;
    final boolean isDetailCover = target == sideDetailCover && prefix != null && prefix.startsWith("cover_");
    final String requestTag = "remote:" + (prefix == null ? "img" : prefix) + ":" + (url == null ? "" : url.trim());
    target.setTag(R.id.tag_remote_image_request, requestTag);
    if (isDetailCover && sideDetailCoverLoading != null) {
        sideDetailCoverLoading.setVisibility(View.VISIBLE);
        if (sideDetailPlaceholder != null) {
            sideDetailPlaceholder.setText("封面加载中…");
            sideDetailPlaceholder.setVisibility(View.VISIBLE);
        }
    }
    if (url == null || url.trim().isEmpty()) {
        target.setImageDrawable(null);
        if (isDetailCover && sideDetailCoverLoading != null) sideDetailCoverLoading.setVisibility(View.GONE);
        return;
    }
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
                if (!ok) {
                    showRemoteImageFailed(target, requestTag, isDetailCover);
                    return;
                }
                bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (bitmap == null) {
                    cacheFile.delete();
                    showRemoteImageFailed(target, requestTag, isDetailCover);
                    return;
                }
            }
            Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> {
                if (finalBitmap == null || target.getWindowToken() == null) return;
                Object currentRequest = target.getTag(R.id.tag_remote_image_request);
                if (!(currentRequest instanceof String) || !requestTag.equals(currentRequest)) return;
                target.setImageBitmap(finalBitmap);
                if (isDetailCover) {
                    if (sideDetailCoverLoading != null) sideDetailCoverLoading.setVisibility(View.GONE);
                    if (sideDetailPlaceholder != null) sideDetailPlaceholder.setVisibility(View.GONE);
                    target.setVisibility(View.VISIBLE);
                }
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
        } catch (Throwable ignored) {
            showRemoteImageFailed(target, requestTag, isDetailCover);
        }
    });
}

private void showRemoteImageFailed(ImageView target, String requestTag, boolean isDetailCover) {
    if (target == null) return;
    runOnUiThread(() -> {
        Object currentRequest = target.getTag(R.id.tag_remote_image_request);
        if (!(currentRequest instanceof String) || !requestTag.equals(currentRequest)) return;
        target.setImageDrawable(null);
        if (isDetailCover) {
            if (sideDetailCoverLoading != null) sideDetailCoverLoading.setVisibility(View.GONE);
            target.setVisibility(View.GONE);
            if (sideDetailPlaceholder != null) {
                sideDetailPlaceholder.setText("封面加载失败\n可稍后重试或手动设置");
                sideDetailPlaceholder.setVisibility(View.VISIBLE);
            }
        }
    });
}

private String metadataSource() {
    return metadataController.metadataSource();
}

private String metadataSourceLabel() {
    return metadataController.metadataSourceLabel();
}

private String metadataSourceLabel(String source) {
    return metadataController.metadataSourceLabel(source);
}

private String normalizeMetadataSource(String source) {
    return metadataController.normalizeMetadataSource(source);
}

private boolean isValidMetadataSource(String source) {
    return metadataController.isValidMetadataSource(source);
}

private String visibleMetadataSource(long gameId) {
    return metadataController.visibleMetadataSource(gameId);
}

private void setVisibleMetadataSource(long gameId, String source) {
    metadataController.setVisibleMetadataSource(gameId, source);
}

private VnMetadata metadataForSource(long gameId, String source) {
    return metadataController.metadataForSource(gameId, source);
}

private String metadataSourceForVisibleMetadata(long gameId, VnMetadata meta) {
    return metadataController.metadataSourceForVisibleMetadata(gameId, meta);
}

private String metadataSourceLabelForVisibleMetadata(long gameId, VnMetadata meta) {
    return metadataController.metadataSourceLabelForVisibleMetadata(gameId, meta);
}

private void updateSideMetadataSourceBadge(String label) {
    metadataController.updateSideMetadataSourceBadge(label);
}

private boolean usingBangumi() {
    return metadataController.usingBangumi();
}

private boolean usingBangumiMirror() {
    return metadataController.usingBangumiMirror();
}

private boolean usingYmgal() {
    return metadataController.usingYmgal();
}

private boolean usingHikarinagi() {
    return metadataController.usingHikarinagi();
}

private String bangumiToken() {
    return metadataController.bangumiToken();
}

private void fetchSelectedMetadata(Game game) {
    metadataController.fetchSelectedMetadata(game);
}

private void fetchSelectedMetadata(Game game, boolean forceRefresh) {
    metadataController.fetchSelectedMetadata(game, forceRefresh);
}

private void fetchCurrentSourceMetadata(Game game, boolean forceRefresh) {
    metadataController.fetchCurrentSourceMetadata(game, forceRefresh);
}

private VnMetadata currentSourceCachedMetadata(long gameId) {
    return metadataController.currentSourceCachedMetadata(gameId);
}

private VnMetadata anyCachedMetadata(long gameId) {
    return metadataController.anyCachedMetadata(gameId);
}

private VnMetadata otherSourceCachedMetadata(long gameId) {
    return metadataController.otherSourceCachedMetadata(gameId);
}

private void saveCurrentSourceMetadata(long gameId, VnMetadata meta) {
    metadataController.saveCurrentSourceMetadata(gameId, meta);
}

private void saveVisibleMetadata(long gameId, VnMetadata meta) {
    metadataController.saveVisibleMetadata(gameId, meta);
}

private void saveMetadataForSource(long gameId, String source, VnMetadata meta) {
    metadataController.saveMetadataForSource(gameId, source, meta);
}

private boolean sameMetadataIdentity(VnMetadata a, VnMetadata b) {
    return metadataController.sameMetadataIdentity(a, b);
}

private void clearCurrentSourceMetadata(long gameId) {
    metadataController.clearCurrentSourceMetadata(gameId);
}

private VnMetadata currentSourceMetadata(long gameId) {
    return metadataController.currentSourceMetadata(gameId);
}

private void showCurrentSourceCustomSearchDialog(Game game) {
    metadataController.showCurrentSourceCustomSearchDialog(game);
}

private void searchCurrentSourceWithKeyword(Game game, String keyword) {
    metadataController.searchCurrentSourceWithKeyword(game, keyword);
}

private void fetchVndbMetadata(Game game, boolean forceRefresh) {
    metadataController.fetchVndbMetadata(game, forceRefresh);
}

private void fetchBangumiMetadata(Game game, boolean forceRefresh) {
    metadataController.fetchBangumiMetadata(game, forceRefresh);
}

private void fetchYmgalMetadata(Game game, boolean forceRefresh) {
    metadataController.fetchYmgalMetadata(game, forceRefresh);
}

private void fetchHikarinagiMetadata(Game game, boolean forceRefresh) {
    metadataController.fetchHikarinagiMetadata(game, forceRefresh);
}

private void fetchAndApplyYmgalDetail(Game game, VnMetadata candidate) {
    metadataController.fetchAndApplyYmgalDetail(game, candidate);
}

private void fetchAndApplyHikarinagiDetail(Game game, VnMetadata candidate) {
    metadataController.fetchAndApplyHikarinagiDetail(game, candidate);
}

private boolean downloadImageAllowVndbWarningPage(String imageUrl, File cacheFile, int depth) {
    return metadataController.downloadImageAllowVndbWarningPage(imageUrl, cacheFile, depth);
}

private String readSmallText(InputStream is) throws Exception {
    return metadataController.readSmallText(is);
}

private boolean isTranslatedStateFor(long gameId) {
    return metadataController.isTranslatedStateFor(gameId);
}

private void setTranslatedStateFor(long gameId, boolean translated) {
    metadataController.setTranslatedStateFor(gameId, translated);
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
        setSideDescription(emptyText(meta.description, "暂无" + metadataSourceLabel() + "简介。"));
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
                    Toast.makeText(MainActivity.this, "简介翻译失败", Toast.LENGTH_SHORT).show();
                    updateTranslateButtonState();
                    return;
                }
                meta.translatedDescription = translated.trim();
                if (metadataRepository != null) saveVisibleMetadata(gameId, meta);
                sideShowingTranslatedDescription = true;
                setTranslatedStateFor(gameId, true);
                setSideDescription(meta.translatedDescription);
                updateTranslateButtonState();
            });
        } catch (Throwable t) {
            Log.w("YukiHub", "translate description failed", t);
            runOnUiThread(() -> {
                if (selectedGame != null && selectedGame.id == gameId) {
                    Toast.makeText(MainActivity.this, "简介翻译失败", Toast.LENGTH_SHORT).show();
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
    return metadataController.extractImageUrlFromHtml(html, baseUrl);
}

private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

private String safeCacheName(String input) {
    return metadataController.safeCacheName(input);
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
        chip.setTextColor(primaryTextColor());
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
    return metadataController.buildMetadataSearchKeyword(title);
}

    private boolean isConfidentMatch(String localTitle, VnMetadata meta) {
        return metadataController.isConfidentMatch(localTitle, meta);
    }

private void showVndbCandidateDialog(Game game, List<VnMetadata> list) {
    metadataController.showVndbCandidateDialog(game, list);
}

private void applyVndbMetadata(VnMetadata meta, Game game) {
    metadataController.applyVndbMetadata(meta, game);
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
updateSideMetadataSourceBadge("");
sideDetailOriginalTitle.setText("");
            setSideDescription("点击中间的游戏卡片后，这里会显示封面、启动入口和选项。后续可接 VNDB/API 简介与元数据。");
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
updateSideMetadataSourceBadge("");
sideDetailOriginalTitle.setText(metadataSourceLabel() + " 匹配中…");
        setSideDescription(emptyText(game.description, "正在从" + metadataSourceLabel() + "获取简介…"));
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
        metadataController.showCustomVndbSearchDialog(game);
    }

private void showCustomBangumiSearchDialog(Game game) {
    metadataController.showCustomBangumiSearchDialog(game);
}

private void showCustomYmgalSearchDialog(Game game) {
    metadataController.showCustomYmgalSearchDialog(game);
}

private void showCustomHikarinagiSearchDialog(Game game) {
    metadataController.showCustomHikarinagiSearchDialog(game);
}

private void searchBangumiWithKeyword(Game game, String keyword) {
    metadataController.searchBangumiWithKeyword(game, keyword);
}

private void searchYmgalWithKeyword(Game game, String keyword) {
    metadataController.searchYmgalWithKeyword(game, keyword);
}

private void searchHikarinagiWithKeyword(Game game, String keyword) {
    metadataController.searchHikarinagiWithKeyword(game, keyword);
}

private void searchVndbWithKeyword(Game game, String keyword) {
    metadataController.searchVndbWithKeyword(game, keyword);
}

private void syncCurrentMetadataToGameCard(Game game) {
    metadataController.syncCurrentMetadataToGameCard(game);
}

private String cacheRemoteImageSync(String url, String prefix) {
    return metadataController.cacheRemoteImageSync(url, prefix);
}

private void styleAlertDialogDark(AlertDialog dialog) {
    if (dialog == null) return;
    try {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            DynamicTheme dt = DynamicTheme.getInstance();
            if (dt.isEnabled() && dt.getColors() != null) {
                w.setBackgroundDrawable(tintDialog(dt.getColors()));
            }
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
        DynamicTheme dt2 = DynamicTheme.getInstance();
        if (dt2.isEnabled() && dt2.getColors() != null) {
            ThemeColorExtractor.ThemeColors dc = dt2.getColors();
            if (p != null) { p.setBackground(tintButton(dc)); p.setTextColor(0xFFFFFFFF); }
            if (n != null) { n.setBackground(tintInput(dc)); n.setTextColor(0xFFFFFFFF); }
            if (neu != null) { neu.setBackground(tintInput(dc)); neu.setTextColor(0xFFFFFFFF); }
        } else {
            if (p != null) p.setTextColor(primary);
            if (n != null) n.setTextColor(primary);
            if (neu != null) neu.setTextColor(primary);
        }
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
        String customSearchItem = "自定义搜索" + sourceLabel;
        String syncItem = "同步" + sourceLabel + "到卡片";
        String playTimeItem = "修改游玩时长";
        String favoriteItem = game.favorite ? "取消收藏" : "收藏游戏";
        String nsfwBlurLabel = "🔞 NSFW 封面模糊";
        boolean nsfwBlurGameOn = game.nsfw;
        String nsfwBlurItem = nsfwBlurLabel + "：" + (nsfwBlurGameOn ? "开启" : "关闭");
        java.util.List<String> itemList = new java.util.ArrayList<>(java.util.Arrays.asList(
                "编辑游戏", "设置游玩状态", playTimeItem, favoriteItem, nsfwBlurItem, rematchItem, customSearchItem, syncItem));
        if (game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ONS) itemList.add("引擎设置");
        if (game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ARTEMIS) itemList.add("虚拟鼠标");
        // 桌面快捷方式：部分启动器（含部分定制 ROM）不支持固定快捷方式，不支持时不显示该项
        boolean shortcutSupported = com.yuki.yukihub.shortcut.GameShortcutManager.isSupported(this);
        String shortcutItem = "📌 添加到桌面";
        if (shortcutSupported) itemList.add(shortcutItem);
        itemList.add("详细信息");
        itemList.add("删除游戏");
        itemList.add("多选删除…");
        String[] items = itemList.toArray(new String[0]);
        LinearLayout listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setBackgroundResource(R.drawable.bg_dialog);
        tintDialogRoot(listRoot);
        int hp = dp(18);
        listRoot.setPadding(0, dp(6), 0, dp(6));
        final AlertDialog[] ref = new AlertDialog[1];
        for (String item : items) {
            TextView row = new TextView(this);
            row.setText(item);
            row.setTextColor(getColorCompat(
                    ("删除游戏".equals(item) || "多选删除…".equals(item)) ? R.color.yh_secondary : R.color.yh_text));
            row.setTextSize(15);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(hp, 0, hp, 0);
            row.setBackgroundResource(R.drawable.bg_input);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
            rlp.setMargins(dp(10), dp(4), dp(10), dp(4));
            listRoot.addView(row, rlp);
            row.setOnClickListener(v -> {
                playUiSound(UI_SOUND_CONFIRM);
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
    clearCurrentSourceMetadata(game.id);
    fetchSelectedMetadata(game, true);
}
else if (customSearchItem.equals(chosen)) showCurrentSourceCustomSearchDialog(game);
else if (syncItem.equals(chosen)) syncCurrentMetadataToGameCard(game);
                else if (chosen != null && chosen.startsWith(nsfwBlurLabel)) {
                    boolean newVal = !nsfwBlurGameOn;
                    game.nsfw = newVal;
                    repository.update(game);
                    Toast.makeText(this, "NSFW 封面模糊：" + (newVal ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                    loadGames();
                }
                else if ("引擎设置".equals(chosen)) { if (game.engine == EngineType.ONS) showOnsSettingsDialog(game); else showKrSettingsDialog(game); }
                else if ("虚拟鼠标".equals(chosen)) showGameCursorDialog(game);
                else if (shortcutItem.equals(chosen)) requestGameShortcut(game);
                else if ("详细信息".equals(chosen)) showDetailDialog(game);
                else if ("删除游戏".equals(chosen)) confirmDeleteGame(game);
                else if ("多选删除…".equals(chosen)) enterMultiSelectMode(game);
            });
        }
        ScrollView optionScroll = new ScrollView(this);
        optionScroll.setFillViewport(false);
        optionScroll.setBackgroundResource(R.drawable.bg_dialog);
        tintDialogRoot(optionScroll);
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

    // ==================== 批量重扫全部游戏资料 ====================

    /** 保存批量重扫间隔（秒），非法输入回退到已存值。 */
    private void saveBulkRefreshInterval(EditText input) {
        if (input == null) return;
        try {
            float sec = Float.parseFloat(input.getText().toString().trim());
            if (sec < 0.5f) sec = 0.5f;
            if (sec > 15f) sec = 15f;
            prefs.edit().putFloat(MetadataController.KEY_BULK_REFRESH_INTERVAL_SEC, sec).apply();
            input.setText(String.valueOf(sec));
        } catch (Throwable t) {
            input.setText(String.valueOf(prefs.getFloat(MetadataController.KEY_BULK_REFRESH_INTERVAL_SEC, 2.5f)));
        }
    }

    /** 设置页"一键重扫全部游戏资料"入口：确认弹窗。 */
    private void showBulkMetadataRefreshDialog() {
        if (metadataController.isBulkRefreshRunning()) {
            Toast.makeText(this, "批量重扫已在运行中", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = 0;
        int incomplete = 0;
        for (Game g : allGames) {
            if (g != null && g.id > 0 && g.title != null && !g.title.trim().isEmpty()) {
                count++;
                if (!metadataController.isCurrentSourceMetadataComplete(g.id)) incomplete++;
            }
        }
        if (count == 0) {
            Toast.makeText(this, "没有可重扫的游戏", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        TextView msg = new TextView(this);
        float intervalSec = prefs.getFloat(MetadataController.KEY_BULK_REFRESH_INTERVAL_SEC, 2.5f);
        msg.setText("将使用当前资料源「" + metadataSourceLabel() + "」重新获取 " + count + " 款游戏的资料" + (incomplete > 0 ? "（其中约 " + incomplete + " 款资料不完整）" : "") + "，已缓存内容会被覆盖刷新。\n\n第三方接口有限流，串行重扫且每款间隔约 " + intervalSec + " 秒，期间请保持网络连接。");
        msg.setTextColor(getColorCompat(R.color.yh_text));
        msg.setTextSize(12);
        msg.setLineSpacing(dp(2), 1.0f);
        box.addView(msg);
        CheckBox onlyIncomplete = new CheckBox(this);
        onlyIncomplete.setText("仅重扫资料不完整的游戏（跳过已有完整缓存的，更快更省）");
        onlyIncomplete.setTextColor(getColorCompat(R.color.yh_text));
        onlyIncomplete.setTextSize(12);
        onlyIncomplete.setChecked(true);
        box.addView(onlyIncomplete);
        final int totalCount = count;
        AlertDialog confirmDialog = new AlertDialog.Builder(this)
                .setTitle("重扫全部游戏资料")
                .setView(box)
                .setPositiveButton("开始重扫", (d, w) -> startBulkMetadataRefresh(onlyIncomplete.isChecked(), totalCount))
                .setNegativeButton("取消", null)
                .show();
        styleAlertDialogDark(confirmDialog);
    }

    /** 执行批量重扫：进度对话框 + 回调。 */
    private void startBulkMetadataRefresh(boolean onlyIncomplete, int totalGames) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(10));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(totalGames, 1));
        pb.setProgress(0);
        box.addView(pb);
        TextView status = new TextView(this);
        status.setText("正在准备…");
        status.setTextColor(getColorCompat(R.color.yh_text));
        status.setTextSize(12);
        status.setLineSpacing(dp(2), 1.0f);
        status.setPadding(0, dp(8), 0, 0);
        box.addView(status);
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在重扫游戏资料")
                .setView(box)
                .setNegativeButton("停止", (d, w) -> {
                    metadataController.cancelBulkRefresh();
                    status.setText("正在停止…（当前游戏处理完后退出）");
                })
                .setCancelable(false)
                .create();
        progressDialog.show();
        styleAlertDialogDark(progressDialog);

        metadataController.refreshAllMetadata(onlyIncomplete, new MetadataController.BulkRefreshCallback() {
            @Override
            public void onProgress(int done, int total, String gameTitle, String statusText) {
                if (isFinishing() || isDestroyed()) {
                    metadataController.cancelBulkRefresh();
                    return;
                }
                pb.setMax(Math.max(total, 1));
                pb.setProgress(Math.min(done + 1, Math.max(total, 1)));
                status.setText("正在处理 " + (done + 1) + "/" + total + "：" + gameTitle);
            }

            @Override
            public void onFinished(MetadataController.BulkRefreshResult result) {
                if (isFinishing() || isDestroyed()) {
                    metadataController.cancelBulkRefresh();
                    return;
                }
                try { progressDialog.dismiss(); } catch (Throwable ignored) { }
                showBulkRefreshResult(result);
            }
        });
    }

    /** 批量重扫结果汇总。 */
    private void showBulkRefreshResult(MetadataController.BulkRefreshResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.cancelled ? "已停止，已完成部分：\n" : "重扫完成：\n");
        sb.append("· 尝试 ").append(r.attempted).append(" 款\n");
        if (r.skipped > 0) sb.append("· 跳过（资料已完整）").append(r.skipped).append(" 款\n");
        sb.append("· 成功 ").append(r.success).append(" 款\n");
        sb.append("· 失败 ").append(r.failed).append(" 款\n");
        if (r.needConfirm > 0) sb.append("· 需人工确认 ").append(r.needConfirm).append(" 款\n");
        StringBuilder detailSb = new StringBuilder();
        int shown = 0;
        for (String d : r.details) {
            if (shown >= 20) break;
            detailSb.append(d).append("\n");
            shown++;
        }
        if (r.details.size() > shown) detailSb.append("…共 ").append(r.details.size()).append(" 条明细");
        AlertDialog resultDialog = new AlertDialog.Builder(this)
                .setTitle(r.cancelled ? "已停止" : "重扫完成")
                .setMessage(sb.toString() + (detailSb.length() > 0 ? "\n" + detailSb : ""))
                .setPositiveButton("知道了", null)
                .show();
        styleAlertDialogDark(resultDialog);
        // 当前选中的游戏若刚被重扫，刷新右侧资料卡片
        if (selectedGame != null) updateSideDetail(selectedGame);
    }

    /**
     * 请求把游戏添加到桌面。
     *
     * 图标生成要解码封面 + 取主色，放在 IO 线程做；
     * requestPinShortcut 本身会弹系统确认框，必须回到主线程调用。
     */
    private void requestGameShortcut(Game game) {
        if (game == null) return;
        if (!com.yuki.yukihub.shortcut.GameShortcutManager.isSupported(this)) {
            Toast.makeText(this, "当前桌面不支持添加快捷方式", Toast.LENGTH_LONG).show();
            return;
        }
        if (com.yuki.yukihub.shortcut.GameShortcutManager.isPinned(this, game.id)) {
            Toast.makeText(this, "桌面已有该游戏的快捷方式", Toast.LENGTH_SHORT).show();
            return;
        }
        final long gameId = game.id;
        final String title = game.title;
        final String cover = safeCoverUri(game);
        AppExecutors.runOnIo(() -> {
            // 封面解码 + 取主色在 IO 线程完成，避免主线程卡顿
            final android.graphics.drawable.Icon icon =
                    com.yuki.yukihub.shortcut.GameShortcutManager.prepareIcon(
                            MainActivity.this, title, cover);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // requestPinShortcut 会弹系统确认框，必须在主线程
                boolean ok = com.yuki.yukihub.shortcut.GameShortcutManager.requestPinWithIcon(
                        MainActivity.this, gameId, title, icon, MainActivity.class);
                if (!ok) {
                    Toast.makeText(MainActivity.this, "添加失败，当前桌面可能不支持", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void confirmDeleteGame(Game game) {
        if (game == null) return;
        new AlertDialog.Builder(this)
                .setTitle("删除游戏")
                .setMessage("确定删除 “" + game.title + "”？不会删除本体文件。")
                .setPositiveButton("删除", (x,w)->{
                    com.yuki.yukihub.shortcut.GameShortcutManager.disableForGame(this, game.id);
                    repository.delete(game.id); selectedGame = null; loadGames(); })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 多选删除 ====================

    /** 进入多选模式，并预勾选当前游戏。 */
    private void enterMultiSelectMode(Game seed) {
        if (adapter == null) return;
        adapter.setMultiSelectMode(true);
        if (seed != null) adapter.toggleChecked(seed.id);
        ensureMultiSelectBar();
        if (multiSelectBar != null) multiSelectBar.setVisibility(View.VISIBLE);
        updateMultiSelectBar(adapter.getCheckedCount());
        Toast.makeText(this, "已进入多选模式，点选游戏后可批量删除", Toast.LENGTH_SHORT).show();
    }

    private void exitMultiSelectMode() {
        if (adapter != null) adapter.setMultiSelectMode(false);
        if (multiSelectBar != null) multiSelectBar.setVisibility(View.GONE);
    }

    private void updateMultiSelectBar(int count) {
        if (multiSelectCount != null) {
            multiSelectCount.setText("已选 " + count + " 个");
        }
        if (multiSelectBar != null) {
            multiSelectBar.setVisibility(adapter != null && adapter.isMultiSelectMode() ? View.VISIBLE : View.GONE);
        }
    }

    /** 在主内容区顶部动态挂载多选操作栏。 */
    private void ensureMultiSelectBar() {
        if (multiSelectBar != null) return;
        ViewGroup contentRoot = findViewById(android.R.id.content);
        if (contentRoot == null) return;
        // 挂到 activity 根 FrameLayout 上，绝对定位在底部
        View root = contentRoot.getChildCount() > 0 ? contentRoot.getChildAt(0) : contentRoot;
        if (!(root instanceof ViewGroup)) return;

        multiSelectBar = new LinearLayout(this);
        multiSelectBar.setOrientation(LinearLayout.HORIZONTAL);
        multiSelectBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        multiSelectBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xF0121A2E);
        barBg.setCornerRadius(dp(12));
        barBg.setStroke(dp(1), 0x5534C759);
        multiSelectBar.setBackground(barBg);
        multiSelectBar.setElevation(dp(8));
        multiSelectBar.setVisibility(View.GONE);

        multiSelectCount = new TextView(this);
        multiSelectCount.setText("已选 0 个");
        multiSelectCount.setTextColor(getColorCompat(R.color.yh_text));
        multiSelectCount.setTextSize(13);
        multiSelectCount.setTypeface(null, android.graphics.Typeface.BOLD);
        multiSelectBar.addView(multiSelectCount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView selectAllBtn = makeMultiSelectAction("全选", getColorCompat(R.color.yh_primary));
        selectAllBtn.setOnClickListener(v -> {
            if (adapter != null) adapter.selectAllVisible();
        });
        multiSelectBar.addView(selectAllBtn, multiSelectBtnLp());

        TextView deleteBtn = makeMultiSelectAction("删除", getColorCompat(R.color.yh_secondary));
        deleteBtn.setOnClickListener(v -> confirmBatchDelete());
        multiSelectBar.addView(deleteBtn, multiSelectBtnLp());

        TextView cancelBtn = makeMultiSelectAction("取消", getColorCompat(R.color.yh_text_muted));
        cancelBtn.setOnClickListener(v -> exitMultiSelectMode());
        multiSelectBar.addView(cancelBtn, multiSelectBtnLp());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM;
        lp.setMargins(dp(12), 0, dp(12), dp(14));
        if (root instanceof FrameLayout) {
            ((FrameLayout) root).addView(multiSelectBar, lp);
        } else {
            // 兜底：包一层
            ((ViewGroup) root).addView(multiSelectBar, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private TextView makeMultiSelectAction(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackgroundResource(R.drawable.bg_input);
        return tv;
    }

    private LinearLayout.LayoutParams multiSelectBtnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private void confirmBatchDelete() {
        if (adapter == null) return;
        java.util.Set<Long> ids = adapter.getCheckedIds();
        if (ids.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的游戏", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = ids.size();
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定删除选中的 " + count + " 个游戏？\n不会删除本体文件，仅从游戏库移除。")
                .setPositiveButton("删除 " + count + " 个", (d, w) -> {
                    com.yuki.yukihub.shortcut.GameShortcutManager.disableForGames(this, ids);
                    int deleted = repository.deleteBatch(ids);
                    selectedGame = null;
                    exitMultiSelectMode();
                    loadGames();
                    Toast.makeText(MainActivity.this, "已删除 " + deleted + " 个游戏", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 一键清空游戏库（二次确认）。 */
    private void confirmClearAllGames() {
        int total = allGames == null ? 0 : allGames.size();
        if (total <= 0) {
            Toast.makeText(this, "游戏库已经是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空游戏库")
                .setMessage("即将删除全部 " + total + " 个游戏记录。\n\n"
                        + "· 不会删除本体文件\n"
                        + "· 游玩时长记录会一并清除\n"
                        + "· 此操作不可撤销\n\n"
                        + "确定继续？")
                .setPositiveButton("全部清空", (d, w) -> {
                    // 再确认一次，防止误触
                    new AlertDialog.Builder(this)
                            .setTitle("最后确认")
                            .setMessage("真的要清空全部 " + total + " 个游戏吗？")
                            .setPositiveButton("确定清空", (d2, w2) -> {
                                com.yuki.yukihub.shortcut.GameShortcutManager.disableAll(this);
                                int deleted = repository.deleteAll();
                                selectedGame = null;
                                exitMultiSelectMode();
                                loadGames();
                                Toast.makeText(MainActivity.this, "已清空 " + deleted + " 个游戏", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
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
        tintDialogRoot(root);
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
        addScanRootButton.setTextColor(primaryTextColor());
        addScanRootButton.setOnClickListener(v -> {
            if (getScanRootUris().size() >= MAX_SCAN_ROOTS) {
                Toast.makeText(MainActivity.this, "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
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

        TextView scanModeTitle = new TextView(this);
        scanModeTitle.setText("\n扫描模式");
        scanModeTitle.setTextColor(getColorCompat(R.color.yh_text));
        scanModeTitle.setTextSize(14);
        scanModeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(scanModeTitle);

        TextView scanModeInfo = new TextView(this);
        scanModeInfo.setText("快速模式：批量读取目录并缓存，扫描更快；兼容模式：旧版逐项扫描，行为最保守。如快速模式扫描结果异常可切回兼容模式。");
        scanModeInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        scanModeInfo.setTextSize(11);
        scanModeInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(scanModeInfo);

        Spinner scanModeSpinner = new Spinner(this);
        ArrayAdapter<String> scanModeAdapter = krSpinnerAdapter(new String[]{"快速模式", "兼容模式"});
        scanModeSpinner.setAdapter(scanModeAdapter);
        String savedScanMode = prefs == null ? SCAN_MODE_FAST : prefs.getString(KEY_SCAN_MODE, SCAN_MODE_FAST);
        scanModeSpinner.setSelection(SCAN_MODE_LEGACY.equals(savedScanMode) ? 1 : 0);
        root.addView(scanModeSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

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

        TextView uiScaleTitle = new TextView(this);
        uiScaleTitle.setText("\n界面整体缩放");
        uiScaleTitle.setTextColor(getColorCompat(R.color.yh_text));
        uiScaleTitle.setTextSize(14);
        uiScaleTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(uiScaleTitle);

        float savedUiScale = prefs == null ? UiScaleUtil.DEFAULT_UI_SCALE : prefs.getFloat(UiScaleUtil.KEY_UI_SCALE, UiScaleUtil.DEFAULT_UI_SCALE);
        TextView uiScaleInfo = new TextView(this);
        uiScaleInfo.setText("当前：" + UiScaleUtil.uiScalePercent(savedUiScale) + "%（默认 100%）· 平板建议 120-150%");
        uiScaleInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        uiScaleInfo.setTextSize(11);
        uiScaleInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(uiScaleInfo);

        LinearLayout uiScaleRow = new LinearLayout(this);
        uiScaleRow.setOrientation(LinearLayout.HORIZONTAL);
        uiScaleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        SeekBar uiScaleSeek = new SeekBar(this);
        uiScaleSeek.setMax((int) ((UiScaleUtil.MAX_UI_SCALE - UiScaleUtil.MIN_UI_SCALE) * 100f));
        uiScaleSeek.setProgress(Math.round((savedUiScale - UiScaleUtil.MIN_UI_SCALE) * 100f));
        LinearLayout.LayoutParams uiScaleSeekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        uiScaleRow.addView(uiScaleSeek, uiScaleSeekLp);
        TextView uiScaleValue = new TextView(this);
        uiScaleValue.setText(UiScaleUtil.uiScalePercent(savedUiScale) + "%");
        uiScaleValue.setTextColor(getColorCompat(R.color.yh_text));
        uiScaleValue.setTextSize(15);
        uiScaleValue.setTypeface(null, android.graphics.Typeface.BOLD);
        uiScaleValue.setPadding(dp(10), 0, 0, 0);
        uiScaleRow.addView(uiScaleValue);
        root.addView(uiScaleRow);
        uiScaleSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = UiScaleUtil.clampUiScale(UiScaleUtil.MIN_UI_SCALE + progress / 100f);
                uiScaleValue.setText(UiScaleUtil.uiScalePercent(scale) + "%");
                uiScaleInfo.setText("当前：" + UiScaleUtil.uiScalePercent(scale) + "%（默认 100%）· 平板建议 120-150%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        Button uiScaleReset = krButton("恢复默认缩放");
        uiScaleReset.setTextColor(getColorCompat(R.color.yh_text));
        uiScaleReset.setOnClickListener(v -> {
            uiScaleSeek.setProgress(Math.round((UiScaleUtil.DEFAULT_UI_SCALE - UiScaleUtil.MIN_UI_SCALE) * 100f));
            uiScaleValue.setText("100%");
            uiScaleInfo.setText("当前：100%（默认 100%）· 平板建议 120-150%");
        });
        LinearLayout.LayoutParams uiScaleResetLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        uiScaleResetLp.topMargin = dp(6);
        root.addView(uiScaleReset, uiScaleResetLp);

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
        ArrayAdapter<String> sortAdapter = krSpinnerAdapter(new String[]{"最近游玩", "最近添加", "名称排序"});
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

        TextView columnsTitle = new TextView(this);
        columnsTitle.setText("每行游戏数目");
        columnsTitle.setTextColor(getColorCompat(R.color.yh_text_muted));
        columnsTitle.setTextSize(11);
        columnsTitle.setPadding(0, dp(4), 0, dp(6));
        root.addView(columnsTitle);

        int savedColumns = prefs == null ? DEFAULT_GAME_COLUMNS : prefs.getInt(KEY_GAME_COLUMNS, DEFAULT_GAME_COLUMNS);
        savedColumns = Math.max(2, Math.min(10, savedColumns));
        TextView columnsInfo = new TextView(this);
        columnsInfo.setText("当前：" + savedColumns + " 个（默认 5 个）");
        columnsInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        columnsInfo.setTextSize(11);
        columnsInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(columnsInfo);

        LinearLayout columnsRow = new LinearLayout(this);
        columnsRow.setOrientation(LinearLayout.HORIZONTAL);
        columnsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        SeekBar columnsSeek = new SeekBar(this);
        columnsSeek.setMax(8); // 2-10: 0-8
        columnsSeek.setProgress(savedColumns - 2);
        LinearLayout.LayoutParams columnsSeekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        columnsRow.addView(columnsSeek, columnsSeekLp);
        TextView columnsValue = new TextView(this);
        columnsValue.setText(String.valueOf(savedColumns));
        columnsValue.setTextColor(getColorCompat(R.color.yh_text));
        columnsValue.setTextSize(15);
        columnsValue.setTypeface(null, android.graphics.Typeface.BOLD);
        columnsValue.setPadding(dp(10), 0, 0, 0);
        columnsRow.addView(columnsValue);
        root.addView(columnsRow);
        columnsSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int cols = Math.max(2, Math.min(10, progress + 2));
                columnsValue.setText(String.valueOf(cols));
                columnsInfo.setText("当前：" + cols + " 个（默认 5 个）");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        TextView engineLabelTitle = new TextView(this);
        engineLabelTitle.setText("游戏引擎标签位置");
        engineLabelTitle.setTextColor(getColorCompat(R.color.yh_text_muted));
        engineLabelTitle.setTextSize(11);
        engineLabelTitle.setPadding(0, dp(4), 0, dp(6));
        root.addView(engineLabelTitle);

        Spinner engineLabelSpinner = new Spinner(this);
        ArrayAdapter<String> engineLabelAdapter = krSpinnerAdapter(new String[]{"游戏标题下方", "封面左下角"});
        engineLabelSpinner.setAdapter(engineLabelAdapter);
        String engineLabelPos = prefs == null ? "title" : prefs.getString(KEY_ENGINE_LABEL_POSITION, "title");
        engineLabelSpinner.setSelection("cover".equals(engineLabelPos) ? 1 : 0);
        root.addView(engineLabelSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        CheckBox uiClickSoundCheck = krCheckBox("界面点击音效", prefs == null || prefs.getBoolean(KEY_UI_CLICK_SOUND, true));
        root.addView(uiClickSoundCheck);

        CheckBox nsfwBlurCheck = krCheckBox("🔞 NSFW 封面模糊", prefs == null || prefs.getBoolean(KEY_NSFW_BLUR, true));
        root.addView(nsfwBlurCheck);

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
        accountButton.setTextColor(primaryTextColor());
        webdavButton.setTextColor(primaryTextColor());
        accountButton.setOnClickListener(v -> showAuthPlaceholderDialog());
        webdavButton.setOnClickListener(v -> showWebDavSettingsDialog());
        accountActions.addView(accountButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams webdavLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        webdavLp.setMargins(dp(8), 0, 0, 0);
        accountActions.addView(webdavButton, webdavLp);
        root.addView(accountActions);

        // ===== 从其他平台导入 =====
        TextView importTitle = new TextView(this);
        importTitle.setText("\n数据迁移");
        importTitle.setTextColor(getColorCompat(R.color.yh_text));
        importTitle.setTextSize(14);
        importTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(importTitle);

        TextView importInfo = new TextView(this);
        importInfo.setText("从 Playnite、PotatoVN、Vnite、LunaBox 导入游戏列表、封面和游玩记录。");
        importInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        importInfo.setTextSize(11);
        importInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(importInfo);

        Button externalImportBtn = krButton("从其他平台导入");
        externalImportBtn.setTextColor(primaryTextColor());
        externalImportBtn.setOnClickListener(v -> showExternalImportDialog());
        root.addView(externalImportBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        // ===== 危险操作 =====
        TextView dangerTitle = new TextView(this);
        dangerTitle.setText("\n危险操作");
        dangerTitle.setTextColor(getColorCompat(R.color.yh_secondary));
        dangerTitle.setTextSize(14);
        dangerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(dangerTitle);

        TextView dangerInfo = new TextView(this);
        dangerInfo.setText("清空游戏库只会删除游戏记录和游玩时长，不会删除本体文件。操作不可撤销。");
        dangerInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        dangerInfo.setTextSize(11);
        dangerInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(dangerInfo);

        Button clearAllBtn = krButton("清空游戏库");
        clearAllBtn.setTextColor(getColorCompat(R.color.yh_secondary));
        clearAllBtn.setOnClickListener(v -> confirmClearAllGames());
        root.addView(clearAllBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        TextView disclaimerTitle = new TextView(this);
        disclaimerTitle.setText("\n使用说明与免责声明");
        disclaimerTitle.setTextColor(getColorCompat(R.color.yh_text));
        disclaimerTitle.setTextSize(14);
        disclaimerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(disclaimerTitle);
        TextView disclaimerInfo = new TextView(this);
        disclaimerInfo.setText("本应用为开源项目，旨在帮助用户管理与启动自己拥有权限的游戏/应用资源。" +
                "使用者需自行确认所添加内容、账号、第三方登录（鲲 / Hikarinagi）、同步服务及第三方组件的合法性与可用性。\n\n" +
                "程序不提供任何游戏资源、破解资源或绕过授权的能力；Shizuku、GameHub、WebDAV、VNDB、Bangumi、月幕 Gal、Hikarinagi、KIRIKIRI2、Tyranno、Winlator 等第三方服务/应用均由其各自规则与可用性决定。\n\n" +
                "若你不同意上述内容，请不要继续使用相关功能。" );
        disclaimerInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        disclaimerInfo.setTextSize(11);
        disclaimerInfo.setLineSpacing(dp(2), 1.0f);
        disclaimerInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(disclaimerInfo);
        Button disclaimerButton = krButton("查看完整免责声明");
        disclaimerButton.setTextColor(primaryTextColor());
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
        updateButton.setTextColor(primaryTextColor());
        updateButton.setOnClickListener(v -> checkUpdateManually());
        CheckBox updateOnStartupCheck = krCheckBox("启动时自动检查更新", prefs == null || prefs.getBoolean(KEY_CHECK_UPDATE_ON_STARTUP, true));
        root.addView(updateButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        root.addView(updateOnStartupCheck);

        LinearLayout githubButton = linkCardButton("GitHub 仓库", R.drawable.ic_github);
        LinearLayout gitcodeButton = linkCardButton("GitCode 仓库", R.drawable.ic_gitcode);
        LinearLayout websiteButton = linkCardButton("官方网站", android.R.drawable.ic_menu_view);
        LinearLayout groupButton = linkCardButton("QQ 交流群", android.R.drawable.ic_dialog_email);
        githubButton.setOnClickListener(v -> openExternalUrl("https://github.com/xm486/YukiHub"));
        gitcodeButton.setOnClickListener(v -> openExternalUrl("https://gitcode.com/xm486/YukiHub"));
        websiteButton.setOnClickListener(v -> openExternalUrl("https://yukihub.kesug.com/"));
        groupButton.setOnClickListener(v -> openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info"));
        root.addView(githubButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout.LayoutParams gitcodeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        gitcodeLp.topMargin = dp(8);
        root.addView(gitcodeButton, gitcodeLp);
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
        ArrayAdapter<String> sourceAdapter = krSpinnerAdapter(new String[]{"VNDB（默认）", "Bangumi（需要 Token）", "Bangumi 镜像（需要 Token）", "月幕 Gal（公开 API）", "Hikarinagi（公开 API）"});
        sourceSpinner.setAdapter(sourceAdapter);
        String currentSource = metadataSource();
        if (MetadataController.SOURCE_BANGUMI.equals(currentSource)) sourceSpinner.setSelection(1);
else if (MetadataController.SOURCE_BANGUMI_MIRROR.equals(currentSource)) sourceSpinner.setSelection(2);
else if (MetadataController.SOURCE_YMGAL.equals(currentSource)) sourceSpinner.setSelection(3);
else if (MetadataController.SOURCE_HIKARINAGI.equals(currentSource)) sourceSpinner.setSelection(4);
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

        // 一键重扫全部游戏资料（使用当前选择的资料源，串行限速防第三方限流）
        Button bulkRefreshButton = krButton("↻ 一键重扫全部游戏资料");
        LinearLayout.LayoutParams bulkRefreshLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        bulkRefreshLp.topMargin = dp(8);
        root.addView(bulkRefreshButton, bulkRefreshLp);

        // 重扫间隔设置行（秒/款，0.5~15s）
        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        intervalRow.setPadding(dp(4), dp(6), dp(4), 0);
        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("重扫间隔");
        intervalLabel.setTextColor(getColorCompat(R.color.yh_text));
        intervalLabel.setTextSize(13);
        intervalRow.addView(intervalLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        EditText intervalInput = new EditText(this);
        intervalInput.setSingleLine(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        intervalInput.setText(String.valueOf(prefs.getFloat(MetadataController.KEY_BULK_REFRESH_INTERVAL_SEC, 2.5f)));
        intervalInput.setTextColor(getColorCompat(R.color.yh_text));
        intervalInput.setHintTextColor(getColorCompat(R.color.yh_text_muted));
        intervalInput.setBackgroundResource(R.drawable.bg_input);
        intervalInput.setGravity(android.view.Gravity.CENTER);
        intervalInput.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams intervalInputLp = new LinearLayout.LayoutParams(dp(72), dp(36));
        intervalRow.addView(intervalInput, intervalInputLp);
        TextView intervalUnit = new TextView(this);
        intervalUnit.setText("秒/款（0.5~15）");
        intervalUnit.setTextColor(getColorCompat(R.color.yh_text_muted));
        intervalUnit.setTextSize(12);
        intervalUnit.setPadding(dp(6), 0, 0, 0);
        intervalRow.addView(intervalUnit);
        root.addView(intervalRow);

        bulkRefreshButton.setOnClickListener(v -> {
            saveBulkRefreshInterval(intervalInput);
            showBulkMetadataRefreshDialog();
        });

        TextView warn = new TextView(this);
        warn.setText("提醒：Bangumi API Token 建议使用注册超过三个月的账号申请。月幕 Gal 和 Hikarinagi 使用公开 API，无需 Token。切换资料源后，已有其它源缓存会继续显示；对当前游戏可点“重新匹配”刷新，或点上方“一键重扫全部游戏资料”批量刷新当前源资料。");
        warn.setTextColor(getColorCompat(R.color.yh_warning));
        warn.setTextSize(11);
        warn.setPadding(0, dp(8), 0, 0);
        root.addView(warn);

        TextView tokenLink = new TextView(this);
        tokenLink.setText("没有token?");
        tokenLink.setTextColor(primaryTextColor());
        tokenLink.setTextSize(12);
        tokenLink.setTypeface(null, android.graphics.Typeface.BOLD);
        tokenLink.setPadding(0, dp(8), 0, dp(4));
        tokenLink.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://next.bgm.tv/demo/access-token/create"))); }
            catch (Throwable t) { Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show(); }
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
        chooseBgButton.setTextColor(primaryTextColor());
        chooseVideoBgButton.setTextColor(primaryTextColor());
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
        CheckBox bgThemeEnabled = krCheckBox("背景UI取色（从背景提取色调）", prefs.getBoolean(KEY_BG_THEME_ENABLED, false));
        root.addView(bgDimEnabled);
        root.addView(bgVideoSound);
        root.addView(bgThemeEnabled);

        // 自定义颜色选项
        CheckBox customColorEnabled = krCheckBox("自定义主题颜色", prefs.getBoolean(DynamicTheme.KEY_CUSTOM_COLOR_ENABLED, false));
        root.addView(customColorEnabled);
        
        // 颜色选择按钮和预览
        LinearLayout colorPickerRow = new LinearLayout(this);
        colorPickerRow.setOrientation(LinearLayout.HORIZONTAL);
        colorPickerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        colorPickerRow.setPadding(0, dp(8), 0, dp(8));
        
        // 颜色预览
        View colorPreview = new View(this);
        colorPreview.setBackgroundColor(prefs.getInt(DynamicTheme.KEY_CUSTOM_COLOR_PRIMARY, 0xFF8AB4FF));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        previewLp.setMarginEnd(dp(12));
        colorPreview.setLayoutParams(previewLp);
        colorPickerRow.addView(colorPreview);
        
        // 选择颜色按钮
        Button chooseColorBtn = krButton("选择主题颜色");
        chooseColorBtn.setOnClickListener(v -> {
            customColorEnabled.setChecked(true);
            showColorPickerDialog(colorPreview, customColorEnabled);
        });
        colorPickerRow.addView(chooseColorBtn, new LinearLayout.LayoutParams(0, dp(40), 1));
        
        // 恢复默认值按钮
        Button resetColorBtn = krButton("恢复默认");
        resetColorBtn.setOnClickListener(v -> {
            DynamicTheme dt = DynamicTheme.getInstance();
            dt.setCustomColorEnabled(false);
            dt.setCustomColorPrimary(0xFF8AB4FF);
            dt.setCustomColorSecondary(0xFFFF8AB3);
            dt.setCustomGradientAngle(0f);
            dt.saveCustomColorSettings(this);
            customColorEnabled.setChecked(false);
            colorPreview.setBackgroundColor(0xFF8AB4FF);
            applyDynamicTheme(null);
            Toast.makeText(MainActivity.this, "已恢复默认主题颜色", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(dp(80), dp(40));
        resetLp.setMarginStart(dp(8));
        colorPickerRow.addView(resetColorBtn, resetLp);
        
        root.addView(colorPickerRow);

        // 文件选择器选项
        CheckBox useBuiltinFileChooser = krCheckBox("使用内置文件选择器（避免权限问题）", prefs.getBoolean(KEY_USE_BUILTIN_FILE_CHOOSER, true));
        root.addView(useBuiltinFileChooser);

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
        CheckBox krDrawCutout = krCheckBox("KRKR 允许绘制刘海/挖孔区域", prefs.getBoolean(KEY_KR_DRAW_CUTOUT, true));
        CheckBox artemisDrawCutout = krCheckBox("Artemis 允许绘制刘海/挖孔区域", prefs.getBoolean(KEY_ARTEMIS_DRAW_CUTOUT, true));
        root.addView(krCompatMode);
        root.addView(krScopedSaveDir);
        root.addView(artemisScopedSaveDir);
        root.addView(krDrawCutout);
        root.addView(artemisDrawCutout);

        Button nativeKrkrButton = krButton("进入原生KRKR");
        nativeKrkrButton.setTextColor(primaryTextColor());
        nativeKrkrButton.setOnClickListener(v -> {
            try {
                startActivity(EmulatorLauncher.buildInternalKrkrIntent(this, "", "", true));
            } catch (Throwable t) {
                Toast.makeText(MainActivity.this, "无法进入原生KRKR", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams nativeKrkrLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        nativeKrkrLp.setMargins(0, dp(10), 0, dp(4));
        root.addView(nativeKrkrButton, nativeKrkrLp);

        // === 开发者选项 ===
        TextView devTitle = new TextView(this);
        devTitle.setText("\n开发者选项");
        devTitle.setTextColor(getColorCompat(R.color.yh_text));
        devTitle.setTextSize(14);
        devTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(devTitle);

        TextView devInfo = new TextView(this);
        devInfo.setText("开启后自动捕获系统日志（logcat），包含引擎运行、WebView、异常等所有输出。\n日志路径：Android/data/com.yuki.yukihub/files/logs/");
        devInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        devInfo.setTextSize(11);
        devInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(devInfo);

        CheckBox logEnabledCheck = krCheckBox("开启日志收集", DevLogger.isEnabled());
        root.addView(logEnabledCheck);

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        logActions.setPadding(0, dp(8), 0, 0);

        Button exportLogBtn = krButton("导出日志");
        exportLogBtn.setTextColor(primaryTextColor());
        exportLogBtn.setOnClickListener(v -> {
            File logFile = DevLogger.getLogFile();
            if (logFile == null || !logFile.exists()) {
                Toast.makeText(MainActivity.this, "暂无日志文件", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", logFile));
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "YukiHub Logcat");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "导出日志"));
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        logActions.addView(exportLogBtn, new LinearLayout.LayoutParams(0, dp(40), 1));

        Button clearLogBtn = krButton("清空日志");
        clearLogBtn.setTextColor(getColorCompat(R.color.yh_text));
        clearLogBtn.setOnClickListener(v -> {
            if (DevLogger.clearLog()) {
                Toast.makeText(MainActivity.this, "日志已清空", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "清空失败", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams clearLogLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        clearLogLp.setMargins(dp(8), 0, 0, 0);
        logActions.addView(clearLogBtn, clearLogLp);
        root.addView(logActions);

        TextView logPathInfo = new TextView(this);
        String logPath = DevLogger.getLogPath();
        String logSize = DevLogger.formatSize(DevLogger.getLogSize());
        logPathInfo.setText("当前日志大小：" + logSize + "\n路径：" + (logPath != null ? logPath : "未初始化"));
        logPathInfo.setTextColor(getColorCompat(R.color.yh_text_muted));
        logPathInfo.setTextSize(10);
        logPathInfo.setPadding(0, dp(6), 0, dp(4));
        root.addView(logPathInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundResource(R.drawable.bg_dialog);
        tintDialogRoot(scroll);
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
            boolean ymgal = sourceSelection == 3;
            boolean hikarinagi = sourceSelection == 4;
            String selectedMetadataSource = hikarinagi ? MetadataController.SOURCE_HIKARINAGI : (ymgal ? MetadataController.SOURCE_YMGAL : (bangumiMirror ? MetadataController.SOURCE_BANGUMI_MIRROR : (bangumi ? MetadataController.SOURCE_BANGUMI : MetadataController.SOURCE_VNDB)));
            String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
            if ((bangumi || bangumiMirror) && token.isEmpty()) {
                Toast.makeText(MainActivity.this, "选择 Bangumi 时需要填写 Token", Toast.LENGTH_SHORT).show();
                return;
            }
            int depth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, scanDepthSeek.getProgress() + 1));
            float fontScale = UiScaleUtil.clamp(UiScaleUtil.MIN_FONT_SCALE + fontSeek.getProgress() / 100f);
            String sortMode = SORT_MODE_RECENT;
            int sortSelection = sortSpinner.getSelectedItemPosition();
            if (sortSelection == 1) sortMode = SORT_MODE_NEWEST;
            else if (sortSelection == 2) sortMode = SORT_MODE_NAME;
            prefs.edit()
                    .putString(MetadataController.KEY_METADATA_SOURCE, selectedMetadataSource)
                    .putString(MetadataController.KEY_BANGUMI_TOKEN, token)
                    .putInt(KEY_STARTUP_SCAN_DEPTH, depth)
                    .putBoolean(KEY_AUTO_SCAN_ON_STARTUP, autoScanCheck.isChecked())
                    .putString(KEY_SCAN_MODE, scanModeSpinner.getSelectedItemPosition() == 1 ? SCAN_MODE_LEGACY : SCAN_MODE_FAST)
                    .putBoolean(KEY_CHECK_UPDATE_ON_STARTUP, updateOnStartupCheck.isChecked())
                    .putString(KEY_ENGINE_LABEL_POSITION, engineLabelSpinner.getSelectedItemPosition() == 1 ? "cover" : "title")
                    .putString(KEY_SORT_MODE, sortMode)
                    .putBoolean(KEY_BACKGROUND_DIM_ENABLED, bgDimEnabled.isChecked())
                    .putBoolean(KEY_BACKGROUND_VIDEO_SOUND, bgVideoSound.isChecked())
                    .putBoolean(KEY_BG_THEME_ENABLED, bgThemeEnabled.isChecked())
                .putBoolean(DynamicTheme.KEY_CUSTOM_COLOR_ENABLED, customColorEnabled.isChecked())
                .putBoolean(KEY_USE_BUILTIN_FILE_CHOOSER, useBuiltinFileChooser.isChecked())
                    .putBoolean(KEY_UI_CLICK_SOUND, uiClickSoundCheck.isChecked())
                    .putBoolean(KEY_NSFW_BLUR, nsfwBlurCheck.isChecked())
                    .putString(KEY_KR_ENGINE_VERSION, krEngineVersionFromLabel(String.valueOf(krEngineVersion.getSelectedItem())))
                    .putBoolean(KEY_KR_COMPAT_MODE, krCompatMode.isChecked())
                    .putBoolean(KEY_KR_SCOPED_SAVE_DIR, krScopedSaveDir.isChecked())
                    .putBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, artemisScopedSaveDir.isChecked())
                    .putBoolean(KEY_KR_DRAW_CUTOUT, krDrawCutout.isChecked())
                    .putBoolean(KEY_ARTEMIS_DRAW_CUTOUT, artemisDrawCutout.isChecked())
                    .putFloat(UiScaleUtil.KEY_UI_FONT_SCALE, fontScale)
                    .putFloat(UiScaleUtil.KEY_UI_SCALE, UiScaleUtil.clampUiScale(UiScaleUtil.MIN_UI_SCALE + uiScaleSeek.getProgress() / 100f))
                    .putInt(KEY_GAME_COLUMNS, Math.max(2, Math.min(10, columnsSeek.getProgress() + 2)))
                    .putBoolean("dev_log_enabled", logEnabledCheck.isChecked())
                    .apply();
                
                // 保存自定义颜色设置
                DynamicTheme dt = DynamicTheme.getInstance();
                dt.setCustomColorEnabled(customColorEnabled.isChecked());
                dt.saveCustomColorSettings(this);
            applyCustomBackground();
            Toast.makeText(MainActivity.this, "已保存资料源：" + (hikarinagi ? "Hikarinagi" : (ymgal ? "月幕Gal" : (bangumiMirror ? "Bangumi镜像" : (bangumi ? "Bangumi" : "VNDB")))) + "，扫描深度：" + depth + " 层，字体：" + UiScaleUtil.percent(fontScale) + "%", Toast.LENGTH_SHORT).show();
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
            DynamicTheme.getInstance().clearCache(prefs);
            deleteInternalFileUri(oldBg);
            applyCustomBackground();
            bgInfo.setText("当前：默认动态背景");
            Toast.makeText(MainActivity.this, "已恢复默认背景", Toast.LENGTH_SHORT).show();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (getScanRootUris().size() >= MAX_SCAN_ROOTS) {
                Toast.makeText(MainActivity.this, "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
                return;
            }
            launchScanRootPicker(-1);
        });
        dialog.setOnDismissListener(d -> {
            activeScanRootList = null;
            activeScanRootInfo = null;
        });
    }
    
    private void showColorPickerDialog(View colorPreview, CheckBox customColorEnabledCheck) {
        DynamicTheme dt = DynamicTheme.getInstance();
        dt.loadCustomColorSettings(prefs);
        final boolean oldCustomEnabled = dt.isCustomColorEnabled();
        final int oldPrimary = dt.getCustomColorPrimary();
        final int oldSecondary = dt.getCustomColorSecondary();
        final float oldAngle = dt.getCustomGradientAngle();
        final boolean[] applied = {false};
        
        ColorPickerDialog colorPickerDialog = new ColorPickerDialog(this);
        colorPickerDialog.setInitialColor(dt.getCustomColorPrimary());
        colorPickerDialog.setInitialGradient(dt.getCustomColorPrimary(), dt.getCustomColorSecondary(), dt.getCustomGradientAngle());
        colorPickerDialog.setOnColorPreviewListener((color1, color2, gradientAngle, gradientMode) -> {
            dt.setCustomColorPrimary(color1);
            dt.setCustomColorSecondary(color2);
            dt.setCustomGradientAngle(gradientAngle);
            dt.setCustomColorMode(gradientMode ? 1 : 0);
            dt.setCustomColorEnabled(true);
            dt.setEnabled(true);
            colorPreview.setBackgroundColor(color1);
            applyDynamicTheme(dt.getColors());
        });
        
        colorPickerDialog.setOnColorSelectedListener((color1, color2, gradientAngle) -> {
            // 更新自定义颜色设置
            dt.setCustomColorPrimary(color1);
            dt.setCustomColorSecondary(color2);
            dt.setCustomGradientAngle(gradientAngle);
            // 使用对话框的实际模式，而不是比较颜色
            dt.setCustomColorMode(colorPickerDialog.getCurrentMode() == ColorPickerDialog.ColorMode.GRADIENT_COLOR ? 1 : 0);
            dt.setCustomColorEnabled(true);
            if (customColorEnabledCheck != null) customColorEnabledCheck.setChecked(true);
            applied[0] = true;
            dt.saveCustomColorSettings(this);
            
            // 更新预览颜色
            colorPreview.setBackgroundColor(color1);
            
            // 应用新的主题颜色（直接应用，不调用 applyCustomBackground 避免重新加载设置）
            applyDynamicTheme(dt.getColors());
            
            Toast.makeText(MainActivity.this, "已应用自定义主题颜色", Toast.LENGTH_SHORT).show();
        });
        colorPickerDialog.setOnDismissListener(d -> {
            if (!applied[0]) {
                dt.setCustomColorEnabled(oldCustomEnabled);
                if (customColorEnabledCheck != null) customColorEnabledCheck.setChecked(oldCustomEnabled);
                dt.setCustomColorPrimary(oldPrimary);
                dt.setCustomColorSecondary(oldSecondary);
                dt.setCustomGradientAngle(oldAngle);
                if (oldCustomEnabled) {
                    dt.setEnabled(true);
                    applyDynamicTheme(dt.getColors());
                    colorPreview.setBackgroundColor(oldPrimary);
                } else {
                    applyCustomBackground();
                    colorPreview.setBackgroundColor(0xFF8AB4FF);
                }
            }
        });
        
        colorPickerDialog.show();
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

    /**
     * 获取扫描目录的开关状态
     * @return 返回一个布尔值列表，表示每个目录是否启用
     */
    private List<Boolean> getScanRootEnabledStates() {
        List<Boolean> states = new ArrayList<>();
        if (prefs == null) return states;
        String joined = prefs.getString(KEY_SCAN_ROOT_ENABLED, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split(",")) {
                String s = part == null ? "" : part.trim();
                states.add("1".equals(s));
            }
        }
        // 补齐到MAX_SCAN_ROOTS个，默认全部启用
        while (states.size() < MAX_SCAN_ROOTS) {
            states.add(true);
        }
        return states;
    }

    /**
     * 保存扫描目录的开关状态
     */
    private void saveScanRootEnabledStates(List<Boolean> states) {
        if (prefs == null || states == null) return;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < Math.min(states.size(), MAX_SCAN_ROOTS); i++) {
            if (joined.length() > 0) joined.append(',');
            joined.append(states.get(i) ? "1" : "0");
        }
        prefs.edit().putString(KEY_SCAN_ROOT_ENABLED, joined.toString()).apply();
    }

    /**
     * 设置指定目录的开关状态
     */
    private void setScanRootEnabled(int index, boolean enabled) {
        List<Boolean> states = getScanRootEnabledStates();
        if (index >= 0 && index < states.size()) {
            states.set(index, enabled);
            saveScanRootEnabledStates(states);
        }
    }

    /**
     * 获取启用的扫描目录列表
     */
    private List<String> getActiveScanRootUris() {
        List<String> roots = getScanRootUris();
        List<Boolean> states = getScanRootEnabledStates();
        List<String> active = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            if (i < states.size() && states.get(i)) {
                active.add(roots.get(i));
            }
        }
        return active;
    }

// ==================== .nomedia 相册防污染 ====================

    /**
     * 已询问过 .nomedia 的扫描根 uri 集合（按 uri 存，删除中间项不会错位）。
     *
     * <p>读取时顺带清理已经不在扫描根列表里的陈旧条目，
     * 避免用户反复更换目录后这个集合无限增长。
     */
    private Set<String> getNoMediaAskedUris() {
        Set<String> asked = new HashSet<>();
        if (prefs == null) return asked;
        String joined = prefs.getString(KEY_NOMEDIA_ASKED, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split("\\n")) {
                String s = part == null ? "" : part.trim();
                if (!s.isEmpty()) asked.add(s);
            }
        }
        return asked;
    }

    /** 丢弃已不在扫描根列表中的「已问过」记录，保持 prefs 不无限增长。 */
    private void pruneNoMediaAsked() {
        if (prefs == null) return;
        try {
            Set<String> asked = getNoMediaAskedUris();
            if (asked.isEmpty()) return;
            Set<String> valid = new HashSet<>();
            for (String r : getScanRootUris()) {
                if (r != null && !r.trim().isEmpty()) valid.add(r.trim());
            }
            if (asked.retainAll(valid)) {
                saveNoMediaAsked(asked);
            }
        } catch (Throwable t) {
            Log.w("YukiHub", "prune nomedia asked failed", t);
        }
    }

    private void saveNoMediaAsked(Set<String> asked) {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String s : asked) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.trim());
        }
        prefs.edit().putString(KEY_NOMEDIA_ASKED, sb.toString()).apply();
    }

    private void markNoMediaAsked(String rootUri) {
        if (prefs == null || rootUri == null || rootUri.trim().isEmpty()) return;
        Set<String> asked = getNoMediaAskedUris();
        if (!asked.add(rootUri.trim())) return; // 已经记过，不必重写 prefs
        saveNoMediaAsked(asked);
    }

    /**
     * 绑定扫描目录 / 扫描完成后调用：探测是否需要询问创建 .nomedia。
     *
     * <p>此阶段<b>只做只读探测，不做安全判定，不弹任何警告</b>。
     * 安全判定要等用户明确点了「创建」之后才执行（见 {@link #performNoMediaCreate}），
     * 这样不想用这功能的人从头到尾只会看到一个普通询问框。
     */
    private void maybePromptNoMedia(String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return;
        final String root = rootUri.trim();
        if (getNoMediaAskedUris().contains(root)) return;
        AppExecutors.runOnIo(() -> {
            boolean exists;
            try {
                exists = NoMediaHelper.exists(MainActivity.this, root);
            } catch (Throwable t) {
                Log.w("YukiHub", "nomedia probe failed root=" + root, t);
                return;
            }
            if (exists) return; // 已经有了，静默
            runOnUiThread(() -> showNoMediaAskDialog(root));
        });
    }

    /** 扫描完成后对所有启用的扫描根做一次检查（覆盖已绑定目录的老用户）。 */
    private void maybePromptNoMediaForActiveRoots() {
        try {
            List<String> roots = getActiveScanRootUris();
            Set<String> asked = getNoMediaAskedUris();
            for (String root : roots) {
                if (root == null || root.trim().isEmpty()) continue;
                if (asked.contains(root.trim())) continue;
                maybePromptNoMedia(root);
                return; // 一次只问一个，避免弹窗叠加
            }
        } catch (Throwable t) {
            Log.w("YukiHub", "nomedia batch prompt failed", t);
        }
    }

    /** 询问弹窗：用户表态前不显示任何警告。 */
    private void showNoMediaAskDialog(final String rootUri) {
        if (isFinishing() || isDestroyed()) return;
        String shown = displayPath(rootUri);
        String msg = "扫描目录：\n" + shown + "\n\n"
                + "游戏解压后会产生大量 CG、立绘图片，系统相册会把它们全部收录，导致相册被刷满。\n\n"
                + "可以在该目录创建一个 .nomedia 空文件，让系统相册忽略这个目录下的所有图片。"
                + "这不会影响游戏运行，也不会影响 YukiHub 读取封面。\n\n"
                + "⚠️ 已经进入相册的图片不会立刻消失，需要等系统重新扫描（可能需要重启设备）。";
        try {
            new AlertDialog.Builder(this)
                    .setTitle("防止游戏图片塞满相册")
                    .setMessage(msg)
                    .setPositiveButton("创建", (d, w) -> performNoMediaCreate(rootUri, true))
                    .setNegativeButton("不用了", (d, w) -> markNoMediaAsked(rootUri))
                    .setNeutralButton("以后再说", null) // 不记状态，下次还会问
                    .show();
        } catch (Throwable t) {
            Log.w("YukiHub", "show nomedia dialog failed", t);
        }
    }

    /**
     * 用户点了「创建」之后才执行：先安全判定，通过再写入。
     *
     * @param allowFallback 被安全判定拦截时是否提供「只在游戏目录创建」的降级选项
     */
    private void performNoMediaCreate(final String rootUri, final boolean allowFallback) {
        NoMediaHelper.Safety safety = NoMediaHelper.check(rootUri);
        if (!safety.allowed) {
            if (allowFallback) {
                showNoMediaBlockedDialog(rootUri, safety);
            } else {
                Toast.makeText(this, safety.message(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        AppExecutors.runOnIo(() -> {
            final NoMediaHelper.Result r = NoMediaHelper.create(MainActivity.this, rootUri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (r.success) {
                    markNoMediaAsked(rootUri);
                    Toast.makeText(this,
                            r.alreadyExists ? "该目录已有 .nomedia，无需重复创建"
                                    : "已创建 .nomedia，相册将忽略该目录下的图片",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, r.error, Toast.LENGTH_LONG).show();
                }
                refreshActiveScanRootListUi();
            });
        });
    }

    /** 安全判定拦截时的降级弹窗：改为只在扫描到的游戏目录里逐个创建。 */
    private void showNoMediaBlockedDialog(final String rootUri, NoMediaHelper.Safety safety) {
        if (isFinishing() || isDestroyed()) return;
        String msg = safety.message() + "\n\n"
                + "可以改为只在扫描到的游戏目录里单独创建，效果相同且安全。";
        try {
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ 已阻止：该位置不能创建")
                    .setMessage(msg)
                    .setPositiveButton("只在游戏目录创建", (d, w) -> createNoMediaForScannedGames(rootUri))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w("YukiHub", "show nomedia blocked dialog failed", t);
        }
    }

    /**
     * 降级路径：扫描该根下的游戏目录，对每个目录逐个做安全判定后创建。
     * 安全判定不放水，同一套规则。
     */
    private void createNoMediaForScannedGames(final String rootUri) {
        Toast.makeText(this, "正在扫描游戏目录，请稍候...", Toast.LENGTH_SHORT).show();
        AppExecutors.runOnSingle(() -> {
            List<ScanResult> results = new ArrayList<>();
            try {
                int depth = prefs == null ? DEFAULT_STARTUP_SCAN_DEPTH
                        : prefs.getInt(KEY_STARTUP_SCAN_DEPTH, DEFAULT_STARTUP_SCAN_DEPTH);
                depth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, depth));
                boolean useFast = prefs == null
                        || SCAN_MODE_FAST.equals(prefs.getString(KEY_SCAN_MODE, SCAN_MODE_FAST));
                if (useFast) {
                    results.addAll(FastGameScanner.scan(this, Uri.parse(rootUri), depth));
                } else {
                    results.addAll(GameScanner.scan(this, Uri.parse(rootUri), depth));
                }
            } catch (Throwable t) {
                Log.w("YukiHub", "nomedia fallback scan failed root=" + rootUri, t);
            }

            int created = 0, already = 0, skipped = 0;
            List<String> rescanPaths = new ArrayList<>();
            for (ScanResult sr : results) {
                if (sr == null || sr.uri == null || sr.uri.trim().isEmpty()) continue;
                try {
                    NoMediaHelper.Safety s = NoMediaHelper.check(sr.uri);
                    if (!s.allowed) { skipped++; continue; }
                    // rescan=false：批量期间不逐个触发媒体扫描，结束后统一扫一次
                    NoMediaHelper.Result r = NoMediaHelper.create(MainActivity.this, sr.uri, false);
                    if (r.success) {
                        if (r.alreadyExists) already++; else created++;
                        if (r.realPath != null && !r.realPath.isEmpty()) rescanPaths.add(r.realPath);
                    } else {
                        skipped++;
                    }
                } catch (Throwable t) {
                    Log.w("YukiHub", "nomedia create failed uri=" + sr.uri, t);
                    skipped++;
                }
            }
            if (!rescanPaths.isEmpty()) {
                NoMediaHelper.requestMediaRescan(MainActivity.this,
                        rescanPaths.toArray(new String[0]));
            }

            final int fCreated = created, fAlready = already, fSkipped = skipped;
            final int total = results.size();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // 只有真的扫到了目录才记「已问过」；扫描失败(total=0)不记，
                // 否则用户以后再也不会被提示（多为权限或目录变动导致的临时失败）。
                if (total > 0) markNoMediaAsked(rootUri);
                String msg = total == 0
                        ? "没有扫描到游戏目录，未做任何改动"
                        : "新建 " + fCreated + " 个，已存在 " + fAlready + " 个"
                                + (fSkipped > 0 ? "，跳过 " + fSkipped + " 个" : "");
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refreshActiveScanRootListUi();
            });
        });
    }

    /** 设置界面点击 .nomedia 状态时的管理弹窗（创建 / 移除）。 */
    private void showNoMediaManageDialog(final String rootUri) {
        if (isFinishing() || isDestroyed()) return;
        AppExecutors.runOnIo(() -> {
            final boolean exists = NoMediaHelper.exists(MainActivity.this, rootUri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String shown = displayPath(rootUri);
                try {
                    if (exists) {
                        new AlertDialog.Builder(this)
                                .setTitle("相册防污染：已开启")
                                .setMessage("目录：\n" + shown + "\n\n"
                                        + "该目录已有 .nomedia，系统相册会忽略这里的图片。\n\n"
                                        + "移除后相册会重新收录该目录下的图片。")
                                .setPositiveButton("移除", (d, w) -> performNoMediaRemove(rootUri))
                                .setNegativeButton("关闭", null)
                                .show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("相册防污染：未开启")
                                .setMessage("目录：\n" + shown + "\n\n"
                                        + "创建 .nomedia 后，系统相册会忽略该目录下的所有图片，"
                                        + "避免游戏 CG 塞满相册。不影响游戏运行和封面读取。\n\n"
                                        + "⚠️ 请勿在存储卡根目录创建，否则相册会读不到任何图片。"
                                        + "YukiHub 会自动阻止这类危险位置。")
                                .setPositiveButton("创建", (d, w) -> performNoMediaCreate(rootUri, true))
                                .setNegativeButton("取消", null)
                                .show();
                    }
                } catch (Throwable t) {
                    Log.w("YukiHub", "show nomedia manage dialog failed", t);
                }
            });
        });
    }

    private void performNoMediaRemove(final String rootUri) {
        AppExecutors.runOnIo(() -> {
            final NoMediaHelper.Result r = NoMediaHelper.remove(MainActivity.this, rootUri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this,
                        r.success ? "已移除 .nomedia，相册将重新收录该目录图片" : r.error,
                        Toast.LENGTH_LONG).show();
                // 不清「已问过」记录：用户是主动移除的，
                // 清掉会导致下次扫描又弹窗询问，属于骚扰。
                // 需要重新创建时可从设置里的「相册」入口手动操作。
                refreshActiveScanRootListUi();
            });
        });
    }

private boolean addOrReplaceScanRoot(String uri, int replaceIndex) {
if (uri == null || uri.trim().isEmpty()) return false;
List<String> roots = getScanRootUris();
String value = uri.trim();
roots.remove(value);
if (replaceIndex >= 0 && replaceIndex < roots.size()) roots.set(replaceIndex, value);
else if (roots.size() < MAX_SCAN_ROOTS) roots.add(value);
else {
Toast.makeText(MainActivity.this, "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
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
// 目录已移除，顺带丢掉它的 .nomedia 询问记录，避免 prefs 无限增长
pruneNoMediaAsked();
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

private boolean useBuiltinFileChooser() {
        return prefs == null || prefs.getBoolean(KEY_USE_BUILTIN_FILE_CHOOSER, true);
    }
    private boolean nsfwBlurEnabled() {
        return prefs == null || prefs.getBoolean(KEY_NSFW_BLUR, true);
    }

    private void launchScanRootPicker(int replaceIndex) {
        pendingScanRootReplaceIndex = replaceIndex;
        if (useBuiltinFileChooser()) {
            new com.yuki.yukihub.ui.filechooser.FileChooserDialog(this)
                .setMode(com.yuki.yukihub.ui.filechooser.FileChooserDialog.Mode.DIRECTORY)
                .setTitle("选择扫描目录")
                .setOnFileSelectedListener(new com.yuki.yukihub.ui.filechooser.FileChooserDialog.OnFileSelectedListener() {
                    @Override public void onFileSelected(Uri uri, String path, String fileName) {}
                    @Override public void onDirectorySelected(Uri uri, String path) {
String selectedDir = uri != null ? uri.toString() : path;
boolean changed = addOrReplaceScanRoot(selectedDir, pendingScanRootReplaceIndex);
pendingScanRootReplaceIndex = -2;
                        if (changed) {
                            refreshActiveScanRootListUi();
                            Toast.makeText(MainActivity.this, "扫描目录已更新", Toast.LENGTH_SHORT).show();
                            maybePromptNoMedia(selectedDir);
                        }
                    }
                })
                .setOnSafRequestListener(() -> scanDirLauncher.launch(null))
                .show();
        } else {
            scanDirLauncher.launch(null);
        }
    }

private LinearLayout scanRootCard(String uri, int index, Runnable refresh) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_input);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        
        // 添加开关控件
        android.widget.Switch enableSwitch = new android.widget.Switch(this);
        List<Boolean> states = getScanRootEnabledStates();
        boolean isEnabled = index < states.size() ? states.get(index) : true;
        enableSwitch.setChecked(isEnabled);
        enableSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            setScanRootEnabled(index, checked);
            // 更新提示信息
            if (refresh != null) refresh.run();
        });
        card.addView(enableSwitch, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        
        TextView text = new TextView(this);
        text.setText((index + 1) + ". " + compactUriLabel(uri));
        text.setTextColor(getColorCompat(R.color.yh_text));
        text.setTextSize(11);
        text.setSingleLine(false);
        card.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // 相册防污染（.nomedia）状态入口：异步探测后回填，避免主线程 IO
        TextView noMedia = new TextView(this);
        noMedia.setText("相册…");
        noMedia.setTextColor(getColorCompat(R.color.yh_text_muted));
        noMedia.setTextSize(12);
        noMedia.setTypeface(null, android.graphics.Typeface.BOLD);
        noMedia.setPadding(dp(8), 0, dp(4), 0);
        noMedia.setOnClickListener(v -> showNoMediaManageDialog(uri));
        card.addView(noMedia);
        AppExecutors.runOnIo(() -> {
            boolean has;
            try {
                has = NoMediaHelper.exists(MainActivity.this, uri);
            } catch (Throwable t) {
                return;
            }
            final boolean fHas = has;
            runOnUiThread(() -> {
                // 卡片可能已被刷新替换掉，旧 View 脱离视图树后不必再更新
                if (isFinishing() || isDestroyed() || noMedia.getParent() == null) return;
                noMedia.setText(fHas ? "相册✓" : "相册✕");
                noMedia.setTextColor(fHas ? primaryTextColor() : getColorCompat(R.color.yh_text_muted));
            });
        });
        TextView change = new TextView(this);
        change.setText("更换");
        change.setTextColor(primaryTextColor());
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
        List<Boolean> states = getScanRootEnabledStates();
        long enabledCount = 0;
        for (int i = 0; i < roots.size(); i++) {
            if (i < states.size() && states.get(i)) enabledCount++;
        }
        if (info != null) info.setText("已绑定 " + roots.size() + "/" + MAX_SCAN_ROOTS + " 个目录，已启用 " + enabledCount + " 个。" + (roots.isEmpty() ? "\n请先添加扫描目录。" : ""));
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
        Toast.makeText(MainActivity.this, "正在检查更新...", Toast.LENGTH_SHORT).show();
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
        UpdateSource[] sources = new UpdateSource[]{
                new UpdateSource("GitHub", UPDATE_GITHUB_API_URL, UPDATE_GITHUB_REPO_URL, "application/vnd.github+json"),
                new UpdateSource("GitCode", UPDATE_GITCODE_API_URL, UPDATE_GITCODE_REPO_URL, "application/json")
        };
        Exception lastError = null;
        for (UpdateSource source : sources) {
            try {
                return fetchLatestReleaseFrom(source);
            } catch (Exception e) {
                lastError = e;
                Log.w("YukiHub", "update check source failed: " + source.name, e);
            }
        }
        throw lastError == null ? new RuntimeException("所有更新源均不可用") : lastError;
    }

    private UpdateInfo fetchLatestReleaseFrom(UpdateSource source) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(source.apiUrl).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", source.accept);
        c.setRequestProperty("User-Agent", "YukiHub-Android/" + getCurrentVersionName());
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new RuntimeException(source.name + " HTTP " + code + ": " + trimForDialog(text, 160));
        JSONObject o = new JSONObject(text == null ? "{}" : text);
        UpdateInfo info = parseReleaseInfo(o, source);
        if (info.version == null || info.version.isEmpty()) throw new RuntimeException(source.name + " 未返回有效版本号");
        return info;
    }

    private UpdateInfo parseReleaseInfo(JSONObject o, UpdateSource source) {
        UpdateInfo info = new UpdateInfo();
        info.sourceName = source.name;
        info.repoUrl = source.repoUrl;
        info.tagName = o.optString("tag_name", "");
        info.version = normalizeVersion(info.tagName);
        info.name = o.optString("name", info.tagName);
        info.body = o.optString("body", "");
        info.releaseUrl = o.optString("html_url", source.repoUrl + "/releases");
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
            msg.append("发现新的 ").append(emptyText(info.sourceName, "发布源")).append(" Release，可前往发布页查看详情。");
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + emptyText(latestLabel, ""))
                .setMessage(msg.toString())
                .setPositiveButton("前往下载", (d, w) -> openExternalUrl(emptyText(info.apkUrl, info.releaseUrl)))
                .setNeutralButton("发布页", (d, w) -> openExternalUrl(emptyText(info.releaseUrl, emptyText(info.repoUrl, UPDATE_GITHUB_REPO_URL) + "/releases")))
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

    private static class UpdateSource {
        final String name;
        final String apiUrl;
        final String repoUrl;
        final String accept;

        UpdateSource(String name, String apiUrl, String repoUrl, String accept) {
            this.name = name;
            this.apiUrl = apiUrl;
            this.repoUrl = repoUrl;
            this.accept = accept;
        }
    }

    private static class UpdateInfo {
        String sourceName;
        String repoUrl;
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
            Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDisclaimerDialog() {
        String text = "免责声明\n\n" +
                "1. 本应用为开源项目，仅用于管理、整理和启动用户本人有权使用的游戏与应用，不提供任何游戏本体、破解资源、绕过授权或规避版权/平台规则的能力。\n\n" +
                "2. 用户应自行确保所添加资源、账号、同步内容、社区内容以及第三方服务的合法性、完整性与可用性；因使用这些内容产生的责任由用户自行承担。\n\n" +
                "3. 第三方登录（鲲 Galgame、Hikarinagi 等账号体系）仅用于身份关联与云同步，账号数据、头像与资料由其对应平台的规则决定；请妥善保管你的账号与授权，避免在不可信设备上登录。\n\n" +
                "4. 云同步（WebDAV / 服务器）、好友聊天、在线状态与社区功能依赖外部服务与网络环境，可能因服务变更、网络状况或账号状态而不可用或产生数据差异。\n\n" +
                "5. 游戏启动依赖 KIRIKIRI2、Tyranno、Artemis、ONScripter、Winlator、盖世等第三方引擎与应用；启动文件的自动选择、快速/兼容扫描模式等仅提供尽力而为的适配，不保证所有资源均可正确启动。\n\n" +
                "6. 游戏资料刮削（VNDB、Bangumi、月幕 Gal、Hikarinagi 等）获取的封面、简介与标签来自第三方元数据服务，可能不准确或不完整，仅供整理与参考。\n\n" +
                "7. 系统存储权限、Shizuku、GameHub 等能力依赖系统环境与第三方应用，可能因设备、系统版本、权限状态或服务变更而不可用。\n\n" +
                "8. 因第三方服务、系统限制、用户误操作或资源本身问题造成的数据丢失、同步异常、启动失败、兼容性问题或其他损失，开发者不承担额外责任。\n\n" +
                "9. 如果你不同意以上说明，请停止使用相关功能。";
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
    // 搁置：开了但暂时不打算继续，将来还想回来
    if ("onhold".equals(s) || "on_hold".equals(s) || "on-hold".equals(s)
            || "shelved".equals(s) || "paused".equals(s) || "hold".equals(s)) return "onhold";
    // 抛弃：明确不再继续
    if ("dropped".equals(s) || "drop".equals(s) || "abandoned".equals(s)
            || "abandon".equals(s) || "give_up".equals(s)) return "dropped";
    return "unplayed";
}
private String playStatusLabel(String status) {
    String s = normalizePlayStatus(status);
    if ("completed".equals(s)) return "🏆 玩过";
    if ("playing".equals(s)) return "🎮 在玩";
    if ("onhold".equals(s)) return "⏸ 搁置";
    if ("dropped".equals(s)) return "🗑 抛弃";
    return "☆ 未玩";
}
private int playStatusIndex(String status) {
    String s = normalizePlayStatus(status);
    if ("playing".equals(s)) return 1;
    if ("completed".equals(s)) return 2;
    if ("onhold".equals(s)) return 3;
    if ("dropped".equals(s)) return 4;
    return 0;
}
private String playStatusFromIndex(int index) {
    if (index == 1) return "playing";
    if (index == 2) return "completed";
    if (index == 3) return "onhold";
    if (index == 4) return "dropped";
    return "unplayed";
}
private void showPlayStatusDialog(Game game, Dialog parentDialog) {
    if (game == null) return;
    String[] labels = new String[]{"☆ 未玩", "🎮 在玩", "🏆 玩过", "⏸ 搁置", "🗑 抛弃"};
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundResource(R.drawable.bg_dialog);
    tintDialogRoot(root);
    root.setPadding(dp(14), dp(8), dp(14), dp(8));
    final AlertDialog[] ref = new AlertDialog[1];
    int selected = playStatusIndex(game.playStatus);
    for (int i = 0; i < labels.length; i++) {
        final int index = i;
        TextView row = new TextView(this);
        row.setText((index == selected ? "●  " : "○  ") + labels[index]);
        row.setTextColor(index == selected ? primaryTextColor() : getColorCompat(R.color.yh_text));
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
            .setView(wrapDialogScroll(root))
            .setNegativeButton("取消", null)
            .show();
    ref[0] = dialog;
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.42f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }
}

/**
 * 把弹窗内容包进 ScrollView，避免内容高度超过屏幕时选项被截断且无法滑动。
 *
 * 弹窗高度按内容自适应；只有内容真的超出可用高度时才会出现滚动，
 * 所以选项少的时候观感与原来完全一致。
 */
private android.view.View wrapDialogScroll(android.view.View content) {
    android.widget.ScrollView sv = new android.widget.ScrollView(this);
    sv.setLayoutParams(new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
    sv.setFillViewport(false);
    sv.setVerticalScrollBarEnabled(false);
    sv.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
    sv.addView(content, new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
    return sv;
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
        tintDialogRoot(d.findViewById(android.R.id.content));
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
        d.findViewById(R.id.btnDelete).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("删除游戏").setMessage("确定删除 “" + game.title + "”？不会删除本体文件。").setPositiveButton("删除", (x,w)->{ com.yuki.yukihub.shortcut.GameShortcutManager.disableForGame(this, game.id); repository.delete(game.id); d.dismiss(); loadGames(); }).setNegativeButton("取消", null).show());
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
        tintDialogRoot(dialog.findViewById(android.R.id.content));
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
            Toast.makeText(MainActivity.this, "已导入 GameHub 快捷方式", Toast.LENGTH_SHORT).show();
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
            // Dynamic theme text colors
            DynamicTheme dt = DynamicTheme.getInstance();
            if (dt.isEnabled() && dt.getColors() != null) {
                h.label.setTextColor(0xFFF0F4FA);
                h.id.setTextColor(0xFFB0B8C8);
            }
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
                    Toast.makeText(MainActivity.this, "已导入 GameHub 快捷方式参数", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(MainActivity.this, "正在生成 GameHub 快捷方式诊断...", Toast.LENGTH_SHORT).show();
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

    private void showInstalledAppPicker(EditText target, EditText titleTarget) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_picker);
        tintDialogRoot(dialog.findViewById(android.R.id.content));
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
                    if (titleTarget != null && (titleTarget.getText() == null || titleTarget.getText().toString().trim().isEmpty())) {
                        titleTarget.setText(item.label);
                    }
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
            // Dynamic theme text colors
            DynamicTheme dt = DynamicTheme.getInstance();
            if (dt.isEnabled() && dt.getColors() != null) {
                h.label.setTextColor(0xFFF0F4FA);
                h.pkg.setTextColor(0xFFB0B8C8);
            }
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
        editingGameId = game == null ? -1L : game.id;
        pendingDirUri = game == null ? null : game.rootUri;
        pendingCoverUri = game == null ? null : game.coverUri;
        Dialog d = new Dialog(this); pendingEditDialog = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_game_edit);
        // Apply dynamic theme to the dialog's root ScrollView
        View dialogRoot = d.findViewById(R.id.editDialogTitle);
        if (dialogRoot != null) {
            View scrollView = (View) dialogRoot.getParent();
            while (scrollView != null && !(scrollView instanceof ScrollView)) {
                ViewParent p = scrollView.getParent();
                scrollView = p instanceof View ? (View) p : null;
            }
            if (scrollView != null) tintDialogRoot(scrollView);
        }
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
        View btnClearDir = d.findViewById(R.id.btnClearDir);
        btnClearDir.setOnClickListener(v -> {
            pendingDirUri = null;
            if (pendingEditDialog != null) {
                ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedDir)).setText("未选择游戏目录");
                List<String> options = buildLaunchOptions(null);
                launchSp.setAdapter(krSpinnerAdapter(options.toArray(new String[0])));
                updateClearDirButton(btnClearDir, pendingDirUri);
            }
            Toast.makeText(MainActivity.this, "已清除游戏目录", Toast.LENGTH_SHORT).show();
        });
        View btnPickEmulatorApp = d.findViewById(R.id.btnPickEmulatorApp);
        View btnResetEmulatorPackage = d.findViewById(R.id.btnResetEmulatorPackage);
        View btnPickGameHubShortcut = d.findViewById(R.id.btnPickGameHubShortcut);
        btnPickGameHubShortcut.setOnClickListener(v -> showGameHubShortcutPicker(title, pkg, gamehubLocalGameId));
        btnPickEmulatorApp.setOnClickListener(v -> showInstalledAppPicker(pkg, title));
        pkg.setOnClickListener(v -> showInstalledAppPicker(pkg, title));
        Runnable updateWinlatorAdvanced = () -> {
            String engine = sp.getSelectedItem() == null ? "" : sp.getSelectedItem().toString();
            boolean isWinlator = "WINLATOR".equals(engine) || isWinlatorPackageName(pkg.getText() == null ? "" : pkg.getText().toString());
            winlatorAdvancedLayout.setVisibility(isWinlator ? View.VISIBLE : View.GONE);
            gamehubLaunchLayout.setVisibility("GAMEHUB".equals(engine) ? View.VISIBLE : View.GONE);
        };
        Runnable updateAndroidVisibility = () -> {
            boolean isAndroid = "ANDROID".equals(sp.getSelectedItem() == null ? "" : sp.getSelectedItem().toString());
            View dirRow = d.findViewById(R.id.layoutDirRow);
            View btnPickDirView = d.findViewById(R.id.btnPickDir);
            View launchLabel = d.findViewById(R.id.tvLaunchTargetLabel);
            if (dirRow != null) dirRow.setVisibility(isAndroid ? View.GONE : View.VISIBLE);
            if (btnPickDirView != null) btnPickDirView.setVisibility(isAndroid ? View.GONE : View.VISIBLE);
            if (launchLabel != null) launchLabel.setVisibility(isAndroid ? View.GONE : View.VISIBLE);
            if (launchSp != null) launchSp.setVisibility(isAndroid ? View.GONE : View.VISIBLE);
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
        ArrayAdapter<String> spAdapter = krSpinnerAdapter(new String[]{"AUTO", "KIRIKIRI", "ONS", "TYRANO", "ARTEMIS", "WINLATOR", "GAMEHUB", "PSP", "ANDROID", "UNKNOWN"});
        sp.setAdapter(spAdapter);
        ArrayAdapter<String> winlatorModeAdapter = krSpinnerAdapter(new String[]{"启动到游戏", "启动到程序"});
        winlatorModeSp.setAdapter(winlatorModeAdapter);
        ArrayAdapter<String> gamehubModeAdapter = krSpinnerAdapter(new String[]{"启动到游戏", "启动到程序"});
        gamehubModeSp.setAdapter(gamehubModeAdapter);
        List<String> launchOptions = buildLaunchOptions(pendingDirUri);
        ArrayAdapter<String> launchAdapter = krSpinnerAdapter(launchOptions.toArray(new String[0]));
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
        updateClearDirButton(btnClearDir, pendingDirUri);
        updateAndroidVisibility.run();
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
                } else if ((pkg.getText() == null || pkg.getText().toString().trim().isEmpty()) && "PSP".equals(engine)) {
                    pkg.setText("org.ppsspp.ppsspp");
                    // 检查PPSSPP是否安装，如果未安装给出提示
                    if (!EmulatorLauncher.isPPSSPPInstalled(MainActivity.this)) {
                        Toast.makeText(MainActivity.this, "提示：PSP游戏需要安装PPSSPP模拟器", Toast.LENGTH_LONG).show();
                    }
                }
                updateWinlatorAdvanced.run();
                updateAndroidVisibility.run();
                if (isArtemis) updateArtemisVersionButtons(pkg.getText().toString(), btnArtemisAuto, btnArtemisStd, btnArtemisCompat, btnArtemisCompatV2);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        d.findViewById(R.id.btnPickDir).setOnClickListener(v -> {
            if (useBuiltinFileChooser()) {
                new com.yuki.yukihub.ui.filechooser.FileChooserDialog(this)
                    .setMode(com.yuki.yukihub.ui.filechooser.FileChooserDialog.Mode.DIRECTORY)
                    .setTitle("选择游戏目录")
                    .setOnFileSelectedListener(new com.yuki.yukihub.ui.filechooser.FileChooserDialog.OnFileSelectedListener() {
                        @Override public void onFileSelected(Uri uri, String path, String fileName) {}
                        @Override public void onDirectorySelected(Uri uri, String path) {
pendingDirUri = uri != null ? uri.toString() : path;
if (pendingCoverUri == null || pendingCoverUri.isEmpty()) {
                                Uri autoCover = findFirstLevelImage(pendingDirUri);
                                if (autoCover != null) pendingCoverUri = copyCoverToInternalStorage(autoCover);
                            }
                            if (pendingEditDialog != null) {
                                ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedDir)).setText(pendingDirUri);
                                Spinner launchSp = pendingEditDialog.findViewById(R.id.spLaunchTarget);
                                List<String> options = buildLaunchOptions(pendingDirUri);
                                ArrayAdapter<String> adapter = krSpinnerAdapter(options.toArray(new String[0]));
                                launchSp.setAdapter(adapter);
                                ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedCover)).setText(emptyText(pendingCoverUri, "未选择封面"));
                                updateClearDirButton(pendingEditDialog.findViewById(R.id.btnClearDir), pendingDirUri);
                            }
                        }
                    })
                    .setOnSafRequestListener(() -> editDirLauncher.launch(null))
                    .show();
            } else {
                editDirLauncher.launch(null);
            }
        });
        d.findViewById(R.id.btnPickCover).setOnClickListener(v -> coverLauncher.launch("image/*"));
        d.findViewById(R.id.btnCancel).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty()) { Toast.makeText(MainActivity.this, "请填写标题", Toast.LENGTH_SHORT).show(); return; }
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
            if (g.engine == EngineType.PSP && (g.emulatorPackage == null || g.emulatorPackage.trim().isEmpty())) g.emulatorPackage = "org.ppsspp.ppsspp";
            if (g.engine != EngineType.GAMEHUB) g.gamehubLocalGameId = "";
            if (g.engine == EngineType.ANDROID) {
                String androidPkg = pkg.getText() == null ? "" : pkg.getText().toString().trim();
                if (androidPkg.isEmpty()) { Toast.makeText(MainActivity.this, "请先选择要启动的安卓应用", Toast.LENGTH_SHORT).show(); return; }
                g.rootUri = "";
                g.winlatorLaunchMode = "game";
                g.gamehubLaunchMode = "game";
            }
            g.winlatorLaunchMode = (g.engine == EngineType.WINLATOR || isWinlatorPackageName(g.emulatorPackage)) ? winlatorModeValue(winlatorModeSp.getSelectedItemPosition()) : "game";
            g.gamehubLaunchMode = g.engine == EngineType.GAMEHUB ? gamehubModeValue(gamehubModeSp.getSelectedItemPosition()) : "game";
            String selectedLaunchTarget = (String) launchSp.getSelectedItem();
            if (g.engine == EngineType.ANDROID) selectedLaunchTarget = "";
            if (g.engine == EngineType.ARTEMIS || g.engine == EngineType.TYRANO) selectedLaunchTarget = "[游戏目录]";
            if (g.engine == EngineType.GAMEHUB) selectedLaunchTarget = "[GameHub]";
            g.launchTarget = selectedLaunchTarget;
            g.description = desc.getText().toString();
            if (game == null) repository.insert(g); else repository.update(g);
            // 标题/封面可能变了，刷新已固定到桌面的快捷方式（没有则静默跳过）
            if (game != null) {
                final long shortcutGameId = g.id;
                final String shortcutTitle = g.title;
                final String shortcutCover = safeCoverUri(g);
                AppExecutors.runOnIo(() -> com.yuki.yukihub.shortcut.GameShortcutManager
                        .updateIfExists(MainActivity.this, shortcutGameId, shortcutTitle,
                                shortcutCover, MainActivity.class));
            }
            d.dismiss(); loadGames();
        });
        d.setOnDismissListener(x -> {
            pendingEditDialog = null;
            editingGameId = -1L;
        });
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * Activity 重建后恢复编辑对话框（选封面/选目录期间进程被系统回收的场景）。
     * 已保存的目录/封面/表单字段会回填，用户无需重新输入。
     */
    private void restoreEditDialog(Bundle state) {
        if (state == null || isFinishing() || isDestroyed()) return;
        // 对话框可能已在重建流程中被重新打开（比如 coverLauncher 回调兜底），
        // 此时只需用快照刷新封面/目录显示，避免重复弹窗。
        if (pendingEditDialog != null && pendingEditDialog.isShowing()) {
            String cover = state.getString(KEY_EDIT_COVER_URI);
            if (cover != null) {
                pendingCoverUri = cover;
                TextView coverTv = pendingEditDialog.findViewById(R.id.tvSelectedCover);
                if (coverTv != null) coverTv.setText(cover);
            }
            return;
        }
        long savedGameId = state.getLong(KEY_EDIT_GAME_ID, -1L);
        Game game = null;
        if (savedGameId > 0) {
            for (Game g : allGames) {
                if (g != null && g.id == savedGameId) { game = g; break; }
            }
            // allGames 未加载完时兜底：直接查库
            if (game == null) {
                try {
                    java.util.List<Game> all = repository.getAll();
                    if (all != null) {
                        for (Game g : all) {
                            if (g != null && g.id == savedGameId) { game = g; break; }
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }
        final String savedDir = state.getString(KEY_EDIT_DIR_URI);
        final String savedCover = state.getString(KEY_EDIT_COVER_URI);
        showEditDialog(game);
        if (pendingEditDialog == null) return;
        // showEditDialog 开头会用 game 的 rootUri/coverUri 重置 pending 字段，这里恢复为快照值
        pendingDirUri = savedDir;
        pendingCoverUri = savedCover;
        // 引擎选择会触发 listener（联动 Artemis 版本按钮、包名默认值等），必须先设置引擎再回填表单文本
        Spinner engineSp = pendingEditDialog.findViewById(R.id.spEngine);
        String engine = state.getString(KEY_EDIT_ENGINE, "");
        if (engineSp != null && !engine.isEmpty()) {
            ArrayAdapter<String> ad = (ArrayAdapter<String>) engineSp.getAdapter();
            for (int i = 0; ad != null && i < ad.getCount(); i++) {
                if (engine.equals(ad.getItem(i))) { engineSp.setSelection(i); break; }
            }
        }
        // 回填表单（showEditDialog 已按 game 填过默认值，这里用快照覆盖为用户编辑中的值）
        EditText title = pendingEditDialog.findViewById(R.id.etGameTitle);
        if (title != null) title.setText(state.getString(KEY_EDIT_TITLE, ""));
        EditText pkg = pendingEditDialog.findViewById(R.id.etEmulatorPackage);
        if (pkg != null) pkg.setText(state.getString(KEY_EDIT_PKG, ""));
        EditText desc = pendingEditDialog.findViewById(R.id.etDescription);
        if (desc != null) desc.setText(state.getString(KEY_EDIT_DESC, ""));
        EditText ghId = pendingEditDialog.findViewById(R.id.etGameHubLocalGameId);
        if (ghId != null) ghId.setText(state.getString(KEY_EDIT_GAMEHUB_ID, ""));
        ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedDir)).setText(emptyText(pendingDirUri, "未选择游戏目录"));
        ((TextView) pendingEditDialog.findViewById(R.id.tvSelectedCover)).setText(emptyText(pendingCoverUri, "未选择封面"));
        updateClearDirButton(pendingEditDialog.findViewById(R.id.btnClearDir), pendingDirUri);
        Spinner launchSp = pendingEditDialog.findViewById(R.id.spLaunchTarget);
        // 恢复的目录可能与 game.rootUri 不同（用户改过目录但未保存），按快照目录重建启动项列表
        String gameRoot = game == null ? null : game.rootUri;
        boolean dirChanged = savedDir != null && !savedDir.equals(gameRoot);
        if (launchSp != null && dirChanged) {
            List<String> options = buildLaunchOptions(pendingDirUri);
            launchSp.setAdapter(krSpinnerAdapter(options.toArray(new String[0])));
        }
        String launchTarget = state.getString(KEY_EDIT_LAUNCH_TARGET, "");
        if (launchSp != null && !launchTarget.isEmpty()) {
            ArrayAdapter<String> ad = (ArrayAdapter<String>) launchSp.getAdapter();
            for (int i = 0; ad != null && i < ad.getCount(); i++) {
                if (launchTarget.equals(ad.getItem(i))) { launchSp.setSelection(i); break; }
            }
        }
        Spinner winSp = pendingEditDialog.findViewById(R.id.spWinlatorLaunchMode);
        String winMode = state.getString(KEY_EDIT_WINLATOR_MODE, "");
        if (winSp != null && !winMode.isEmpty()) {
            ArrayAdapter<String> ad = (ArrayAdapter<String>) winSp.getAdapter();
            for (int i = 0; ad != null && i < ad.getCount(); i++) {
                if (winMode.equals(ad.getItem(i))) { winSp.setSelection(i); break; }
            }
        }
        Spinner ghSp = pendingEditDialog.findViewById(R.id.spGameHubLaunchMode);
        String ghMode = state.getString(KEY_EDIT_GAMEHUB_MODE, "");
        if (ghSp != null && !ghMode.isEmpty()) {
            ArrayAdapter<String> ad = (ArrayAdapter<String>) ghSp.getAdapter();
            for (int i = 0; ad != null && i < ad.getCount(); i++) {
                if (ghMode.equals(ad.getItem(i))) { ghSp.setSelection(i); break; }
            }
        }
        Toast.makeText(MainActivity.this, "已恢复编辑窗口，请检查后保存", Toast.LENGTH_SHORT).show();
    }

    private void updateClearDirButton(View btn, String dirUri) {
        boolean hasDir = dirUri != null && !dirUri.trim().isEmpty();
        btn.setEnabled(hasDir);
        btn.setAlpha(hasDir ? 1f : 0.45f);
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
return documentDir(rootUri);
}

private DocumentFile documentDir(String value) {
if (value == null || value.trim().isEmpty()) return null;
String s = value.trim();
if (s.startsWith("/") || s.startsWith("file://")) {
File file = fileFromRootUri(s);
return file == null ? null : DocumentFile.fromFile(file);
}
try {
Uri uri = Uri.parse(s);
if ("content".equalsIgnoreCase(uri.getScheme())) {
DocumentFile documentTree = documentBackedTreeDir(uri);
if (documentTree != null) return documentTree;
}
return DocumentFile.fromTreeUri(this, uri);
} catch (Throwable ignored) {
return null;
}
}

private DocumentFile documentBackedTreeDir(Uri uri) {
try {
if (uri == null || uri.getAuthority() == null) return null;
String path = uri.getPath();
if (path == null || !path.contains("/document/")) return null;
String docId = DocumentsContract.getDocumentId(uri);
if (docId == null || docId.trim().isEmpty()) return null;
Uri treeUri = DocumentsContract.buildTreeDocumentUri(uri.getAuthority(), docId);
DocumentFile dir = DocumentFile.fromTreeUri(this, treeUri);
return dir != null && dir.isDirectory() ? dir : null;
} catch (Throwable ignored) {
return null;
}
}

private File fileFromRootUri(String value) {
if (value == null || value.trim().isEmpty()) return null;
String s = value.trim();
if (s.startsWith("file://")) {
String path = Uri.parse(s).getPath();
return path == null || path.isEmpty() ? null : new File(path);
}
return new File(s);
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
        tintDialogRoot(root);
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
                Toast.makeText(MainActivity.this, "请填写有效的时长", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(MainActivity.this, "游玩时长已更新", Toast.LENGTH_SHORT).show();
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

    private int engineIndex(EngineType e) { if (e == EngineType.KIRIKIRI) return 1; if (e == EngineType.ONS) return 2; if (e == EngineType.TYRANO) return 3; if (e == EngineType.ARTEMIS) return 4; if (e == EngineType.WINLATOR) return 5; if (e == EngineType.GAMEHUB) return 6; if (e == EngineType.PSP) return 7; if (e == EngineType.ANDROID) return 8; if (e == EngineType.UNKNOWN) return 9; return 0; }

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

    /**
     * 游戏内虚拟鼠标设置（KRKR / Artemis 共用）。
     * 含内置箭头实时预览（与游戏内共用 GameCursorIconRenderer）。
     */
    private void showGameCursorDialog(Game game) {
        if (game == null) return;
        boolean krkr = game.engine == EngineType.KIRIKIRI;
        boolean artemis = game.engine == EngineType.ARTEMIS;
        if (!krkr && !artemis) {
            Toast.makeText(this, "该引擎暂不支持虚拟鼠标", Toast.LENGTH_SHORT).show();
            return;
        }
        GameCursorConfig cfg = GameCursorConfig.load(this);
        gameCursorEditing = cfg;   // 供异步导入回调直接改这一份

        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(getColorCompat(com.yuki.yukihub.R.color.yh_card));
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(krkr ? "KRKR 虚拟鼠标" : "Artemis 虚拟鼠标");
        title.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        title.setTextSize(22);
        title.setPadding(0, 0, 0, pad / 2);
        panel.addView(title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        CheckBox enable = krCheckBox(krkr
                        ? "启用 KRKR 虚拟鼠标（悬浮光标，拖动定位，抬手点击）"
                        : "启用 Artemis 虚拟鼠标（悬浮光标，拖动定位，抬手点击）",
                krkr ? cfg.krkrEnabled : cfg.artemisEnabled);
        root.addView(enable);

        // ===== 实时预览（与游戏内同一渲染代码）=====
        TextView previewLabel = krLabel("光标预览（与游戏内一致，红十字 = 鼠标尖位置）");
        root.addView(previewLabel);
        final CursorPreviewView preview = new CursorPreviewView(this, cfg);
        gameCursorPreview = preview;   // 供导入回调刷新
        root.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));

        TextView scaleLabel = krLabel("光标大小");
        root.addView(scaleLabel);
        android.widget.SeekBar scaleSeek = new android.widget.SeekBar(this);
        scaleSeek.setMax(100);
        final float scaleSpan = GameCursorConfig.MAX_SCALE - GameCursorConfig.MIN_SCALE;
        scaleSeek.setProgress(Math.round(
                (gcClampScale(cfg.scale) - GameCursorConfig.MIN_SCALE) / scaleSpan * 100f));
        scaleSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                cfg.scale = GameCursorConfig.MIN_SCALE + progress / 100f * scaleSpan;
                preview.refresh(cfg);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        root.addView(scaleSeek);

        TextView alphaLabel = krLabel("光标不透明度");
        root.addView(alphaLabel);
        android.widget.SeekBar alphaSeek = new android.widget.SeekBar(this);
        alphaSeek.setMax(80);
        alphaSeek.setProgress(Math.round((gcClampAlpha(cfg.alpha) - 0.2f) / 0.8f * 80f));
        alphaSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                cfg.alpha = 0.2f + progress / 80f * 0.8f;
                preview.refresh(cfg);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        root.addView(alphaSeek);

        TextView sensLabel = krLabel("鼠标模式移动灵敏度");
        root.addView(sensLabel);
        android.widget.SeekBar sensSeek = new android.widget.SeekBar(this);
        sensSeek.setMax(100);
        sensSeek.setProgress(Math.round((gcClampSens(cfg.sensitivity) - 0.5f) / 2.5f * 100f));
        sensSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                cfg.sensitivity = 0.5f + progress / 100f * 2.5f;
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        root.addView(sensSeek);

        // ===== 光标外观来源 =====
        TextView srcLabel = krLabel("光标外观");
        root.addView(srcLabel);

        TextView srcStatus = new TextView(this);
        gameCursorSourceStatus = srcStatus;   // 导入回调要更新它
        srcStatus.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text_muted));
        srcStatus.setTextSize(11);
        srcStatus.setPadding(0, 0, 0, dp(6));
        srcStatus.setText(cfg.hasWinCursor()
                ? "当前：" + cfg.winCursorName
                : (cfg.iconUri != null && !cfg.iconUri.isEmpty()
                ? "当前：自定义图片" : "当前：内置箭头"));
        root.addView(srcStatus);

        // Windows 光标包：整包导入，能读 inf 就自动标出哪个是正常状态
        Button importScheme = krButton("导入 Windows 光标包（文件夹）…");
        importScheme.setOnClickListener(v -> {
            try {
                cursorSchemePickerLauncher.launch(null);
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(importScheme, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView schemeHint = new TextView(this);
        schemeHint.setText("支持 .ani（动画）与 .cur（静态）。选中包含光标文件的文件夹后，"
                + "会列出里面的光标让你挑一个，带 AutoSetup.inf 的包能自动标出「正常状态」那一个。"
                + "动画光标会按原速播放，鼠标尖的位置直接取自文件本身。");
        schemeHint.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text_muted));
        schemeHint.setTextSize(10);
        schemeHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(schemeHint);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pickIcon = krButton("选择图片…");
        pickIcon.setOnClickListener(v -> cursorIconPickerLauncher.launch("image/*"));
        Button resetIcon = krButton("恢复默认箭头");
        resetIcon.setOnClickListener(v -> {
            cfg.iconUri = null;
            // 同时卸掉光标包，否则"恢复默认"只清了 PNG 却仍显示光标包
            if (cfg.hasWinCursor()) {
                cfg.winCursorName = null;
                com.yuki.yukihub.gamecursor.wincursor.WinCursorSupport.uninstall(this);
            }
            cfg.save(this);
            preview.refresh(cfg);
            srcStatus.setText("当前：内置箭头");
            Toast.makeText(this, "已恢复内置箭头", Toast.LENGTH_SHORT).show();
        });
        iconRow.addView(pickIcon, new LinearLayout.LayoutParams(0, dp(44), 1f));
        iconRow.addView(resetIcon, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(iconRow);

        // Artemis 专属：显示 native 注入通道状态 + 无障碍（现在只是备用通道）
        if (artemis) {
            TextView accStatus = new TextView(this);
            int probe = com.yuki.yukihub.gamecursor.ArtemisNativeInput.probe();
            boolean nativeOk = probe >= 1; // 库已加载且符号取到（2 才是引擎也就绪）
            boolean ready = isAccessibilityTapReady();
            accStatus.setText(nativeOk
                    ? "✓ 引擎直连注入可用，无需额外权限"
                    : (ready
                    ? "引擎直连不可用，将回退到无障碍手势"
                    : "⚠ 引擎直连不可用，且无障碍未开启：点击不会生效"));
            accStatus.setTextColor(getColorCompat(nativeOk || ready
                    ? com.yuki.yukihub.R.color.yh_text : com.yuki.yukihub.R.color.yh_warning));
            accStatus.setTextSize(12);
            accStatus.setPadding(0, dp(10), 0, dp(4));
            root.addView(accStatus);

            // 只在直连不可用时才提供无障碍入口，避免让人以为必须开
            if (!nativeOk) {
                Button accBtn = krButton(ready ? "无障碍服务设置（已开启）" : "去开启无障碍服务（备用通道）");
                accBtn.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开系统设置，请手动前往：设置 → 无障碍", Toast.LENGTH_LONG).show();
                    }
                });
                root.addView(accBtn, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
            }
        }

        TextView tip = new TextView(this);
        tip.setText("用法：进游戏后右下角有一个模式浮标（可拖动换位置）。\n"
                + "· 点浮标切到鼠标模式（浮标变蓝）：全屏任意位置滑动移动光标，原地轻点 = 在光标尖端注入点击。\n"
                + "· 再点一次切回直接触摸（浮标变灰）：触摸原样给游戏，光标隐藏。\n"
                + (krkr
                ? "KRKR 的点击走引擎自身触摸管线，原引擎「虚拟光标缩放」等设置不受影响。"
                : "Artemis 的点击与悬停直接写入引擎输入层，不经过系统输入派发，"
                + "因此不需要无障碍权限。光标显示仍需要「显示在其他应用上层」权限。"));
        tip.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        tip.setTextSize(11);
        tip.setPadding(0, dp(10), 0, 0);
        root.addView(tip);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = krButton("取消");
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button save = krButton("保存");
        save.setOnClickListener(v -> {
            // 只改当前引擎自己的开关，另一个引擎的设置必须原样保留。
            // 之前写成 cfg.krkrEnabled = krkr && checked，在 artemis 对话框里
            // krkr=false 会把 krkr 的开关直接清掉，导致"开一个关另一个"。
            if (krkr) {
                cfg.krkrEnabled = enable.isChecked();
            } else {
                cfg.artemisEnabled = enable.isChecked();
            }
            cfg.save(this);
            // 只在 native 直连不可用时才提示无障碍：
            // 直连走引擎内部输入层，根本不需要无障碍权限。
            if (artemis && enable.isChecked()
                    && com.yuki.yukihub.gamecursor.ArtemisNativeInput.probe() < 1
                    && !isAccessibilityTapReady()) {
                promptAccessibilityForCursor(dialog);
                return;
            }
            Toast.makeText(this, "虚拟鼠标设置已保存，下次进游戏生效", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(46), 1f));
        panel.addView(actions);

        // 对话框关掉后别再持有这些引用（异步回调会判空）
        dialog.setOnDismissListener(d -> {
            if (gameCursorPreview == preview) gameCursorPreview = null;
            if (gameCursorEditing == cfg) gameCursorEditing = null;
            if (gameCursorSourceStatus == srcStatus) gameCursorSourceStatus = null;
        });
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
        dialog.show();
    }

    /**
     * 光标包文件夹选中后的处理：扫描 → 弹角色选择列表。
     *
     * 有 inf 时能准确标出哪个是「正常状态」（inf 的 [Strings] 段 pointer= 那一项），
     * 没有 inf 就按文件名猜，猜不到就不标注让用户自己认 —— 有些包确实分不清。
     */
    private void onCursorSchemeFolderPicked(Uri treeUri) {
        try {
            // 持久化授权，否则下次读不到（虽然我们会立刻解码落盘，但扫描期间要用）
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }

        Toast.makeText(this, "正在扫描光标包…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.Scheme scheme =
                    com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.scan(this, treeUri);
            runOnUiThread(() -> {
                if (scheme.entries.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("没有找到光标文件")
                            .setMessage("这个文件夹里没有 .ani 或 .cur 文件。\n\n"
                                    + "请选择包含光标文件的文件夹（通常还有一个 AutoSetup.inf）。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                showCursorSchemePickDialog(scheme);
            });
        }, "cursor-scan").start();
    }

    /** 光标包角色选择对话框：每项带动图预览，可直接看到长什么样。 */
    private void showCursorSchemePickDialog(
            com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.Scheme scheme) {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(com.yuki.yukihub.R.drawable.bg_dialog);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText(scheme.name);
        title.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        TextView hint = new TextView(this);
        hint.setText(scheme.hasRoleInfo
                ? "已从安装信息识别出各光标的用途。选一个作为游戏内的光标：\n"
                + "推荐选「普通选择」——那是桌面上的常态指针。"
                : "这个包没有可读的安装信息，无法确定每个文件的用途。\n"
                + "下面按文件名列出，点开预览自己挑一个。");
        hint.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text_muted));
        hint.setTextSize(11);
        hint.setPadding(0, dp(8), 0, dp(10));
        panel.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // 每项：动图预览 + 标签 + 帧数。解码放后台，避免一次点开卡住 UI。
        final List<CursorPreviewItemView> items = new ArrayList<>();
        for (com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.Entry e : scheme.entries) {
            CursorPreviewItemView item = new CursorPreviewItemView(this, e);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                installCursorScheme(scheme.name, e);
            });
            list.addView(item, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
            items.add(item);
        }
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button cancel = krButton("取消");
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        dialog.setOnDismissListener(d -> {
            for (CursorPreviewItemView it : items) it.release();
        });
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.8f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.75f));
        }
        dialog.show();

        // 后台逐个解码并刷新预览
        new Thread(() -> {
            for (CursorPreviewItemView it : items) {
                it.decodeInBackground();
            }
        }, "cursor-preview").start();
    }

    /** 安装选中的光标为常态光标。 */
    private void installCursorScheme(
            String schemeName,
            com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.Entry entry) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final int frames = com.yuki.yukihub.gamecursor.wincursor.WinCursorSupport
                    .install(this, entry.uri, entry.fileName);
            runOnUiThread(() -> {
                if (frames <= 0) {
                    Toast.makeText(this, "这个光标文件无法解码，换一个试试", Toast.LENGTH_LONG).show();
                    return;
                }
                final String name = schemeName + " · " + entry.label();
                // 关键：必须改设置对话框正在持有的那个 cfg 对象，不能另 load 一份。
                // 之前在新 load 的副本上改并保存，对话框里的 cfg 仍是旧快照，
                // 点"保存"时又用 winCursorName=null 把导入结果覆盖掉了 ——
                // 表现就是导入能预览、一动滑块就变回普通箭头、进游戏也没生效。
                if (gameCursorEditing != null) {
                    gameCursorEditing.winCursorName = name;
                    gameCursorEditing.iconUri = null;   // 光标包优先级高于 PNG
                    gameCursorEditing.save(this);
                } else {
                    GameCursorConfig cfg = GameCursorConfig.load(this);
                    cfg.winCursorName = name;
                    cfg.iconUri = null;
                    cfg.save(this);
                }
                if (gameCursorPreview != null) gameCursorPreview.refresh(gameCursorEditing);
                if (gameCursorSourceStatus != null) gameCursorSourceStatus.setText("当前：" + name);
                Toast.makeText(this,
                        frames > 1 ? "已导入（" + frames + " 帧动画）" : "已导入（静态光标）",
                        Toast.LENGTH_SHORT).show();
            });
        }, "cursor-install").start();
    }

    /**
     * 光标包选择列表里的一项：左边动图预览，右边名称与帧数。
     *
     * 预览直接播动画，这样「哪个是我想要的」一眼就能看出来，
     * 不用靠文件名猜。
     */
    private final class CursorPreviewItemView extends LinearLayout {
        private final com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.Entry entry;
        private final AnimatedCursorView icon;
        private final TextView label;

        CursorPreviewItemView(Context c,
                              com.yuki.yukihub.gamecursor.wincursor.CursorSchemeScanner.Entry e) {
            super(c);
            this.entry = e;
            setOrientation(HORIZONTAL);
            setGravity(android.view.Gravity.CENTER_VERTICAL);
            setPadding(dp(6), dp(6), dp(6), dp(6));
            setClickable(true);
            setBackgroundResource(com.yuki.yukihub.R.drawable.bg_game_card);

            icon = new AnimatedCursorView(c);
            addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            label = new TextView(c);
            label.setText(e.label());
            label.setTextColor(getColorCompat(e.isNormal
                    ? com.yuki.yukihub.R.color.yh_primary : com.yuki.yukihub.R.color.yh_text));
            label.setTextSize(13);
            label.setPadding(dp(12), 0, 0, 0);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            addView(label, lp);
        }

        void decodeInBackground() {
            final com.yuki.yukihub.gamecursor.wincursor.CursorPack pack =
                    com.yuki.yukihub.gamecursor.wincursor.WinCursorSupport
                            .decode(MainActivity.this, entry.uri, entry.fileName);
            runOnUiThread(() -> {
                if (pack == null) {
                    label.setText(entry.label() + "（无法解码）");
                    return;
                }
                int n = pack.sequence != null ? pack.sequence.length : 1;
                label.setText(entry.label() + (n > 1 ? "  " + n + " 帧" : "  静态"));
                icon.setPack(pack);
            });
        }

        void release() {
            icon.release();
        }
    }

    /** 播放 CursorPack 动画的小控件，只用于设置界面的预览列表。 */
    private static final class AnimatedCursorView extends View {
        private com.yuki.yukihub.gamecursor.wincursor.CursorPack pack;
        private int step;
        private Runnable ticker;
        private final RectF dst = new RectF();

        AnimatedCursorView(Context c) {
            super(c);
        }

        void setPack(com.yuki.yukihub.gamecursor.wincursor.CursorPack p) {
            release();
            pack = p;
            step = 0;
            invalidate();
            if (p != null && p.isAnimated()) startTicker();
        }

        private void startTicker() {
            stopTicker();
            ticker = new Runnable() {
                @Override
                public void run() {
                    if (ticker != this || pack == null) return;
                    step = (step + 1) % pack.sequence.length;
                    invalidate();
                    int d = step < pack.stepDurations.length ? pack.stepDurations[step] : 67;
                    postDelayed(this, Math.max(16, d));
                }
            };
            int d = pack.stepDurations.length > 0 ? pack.stepDurations[0] : 67;
            postDelayed(ticker, Math.max(16, d));
        }

        private void stopTicker() {
            if (ticker != null) {
                removeCallbacks(ticker);
                ticker = null;
            }
        }

        void release() {
            stopTicker();
            if (pack != null) {
                pack.recycle();
                pack = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            release();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (pack == null || pack.frames == null || pack.frames.length == 0) return;
            int idx = pack.sequence != null && pack.sequence.length > 0
                    ? pack.sequence[step % pack.sequence.length] : 0;
            if (idx < 0 || idx >= pack.frames.length) idx = 0;
            android.graphics.Bitmap bmp = pack.frames[idx].bitmap;
            if (bmp == null || bmp.isRecycled()) return;
            // 等比铺满，保持光标原始比例
            float vw = getWidth(), vh = getHeight();
            float bw = bmp.getWidth(), bh = bmp.getHeight();
            float k = Math.min(vw / bw, vh / bh);
            float w = bw * k, h = bh * k;
            dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
            canvas.drawBitmap(bmp, null, dst, null);
        }
    }

    /** 光标缩放/透明度/灵敏度的取值夹取，与 GameCursorConfig 的上下限保持一致。 */
    private static float gcClampScale(float v) {
        return v < GameCursorConfig.MIN_SCALE ? GameCursorConfig.MIN_SCALE
                : (v > GameCursorConfig.MAX_SCALE ? GameCursorConfig.MAX_SCALE : v);
    }
    private static float gcClampAlpha(float v) { return v < 0.2f ? 0.2f : (v > 1f ? 1f : v); }
    private static float gcClampSens(float v) { return v < 0.5f ? 0.5f : (v > 3f ? 3f : v); }

    /**
     * Artemis 虚拟鼠标的点击注入依赖无障碍手势，这里查系统的已启用服务列表。
     * 不能只看 ScreenshotServiceManager.getService()：那是进程内实例，
     * 主进程没跑过翻译时即使系统已开启也拿不到。
     */
    private boolean isAccessibilityTapReady() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String target = getPackageName() + "/"
                    + com.yuki.yukihub.translate.YukiScreenshotAccessibilityService.class.getName();
            String shortTarget = getPackageName() + "/."
                    + com.yuki.yukihub.translate.YukiScreenshotAccessibilityService.class.getSimpleName();
            for (String s : enabled.split(":")) {
                String t = s.trim();
                if (t.equalsIgnoreCase(target) || t.equalsIgnoreCase(shortTarget)) return true;
                // 兜底：厂商 ROM 有时只写包名段，做包含匹配
                if (t.startsWith(getPackageName() + "/")
                        && t.contains("YukiScreenshotAccessibilityService")) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 引导用户开启无障碍服务（Artemis 点击注入的唯一通道）。 */
    private void promptAccessibilityForCursor(Dialog parent) {
        new AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("Artemis 引擎是 NativeActivity，触摸事件由引擎自己的 native 层消费，"
                        + "应用层无法直接注入点击。\n\n"
                        + "因此 Artemis 的虚拟鼠标点击必须借助系统无障碍手势："
                        + "光标移动不受影响，但「点击」需要开启 YukiHub 的无障碍服务才生效。\n\n"
                        + "开启路径：系统设置 → 无障碍 → 已下载的服务 → YukiHub → 开启。\n"
                        + "（该服务与屏幕翻译共用，只做截图与手势，不读取窗口内容）")
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开系统设置，请手动前往：设置 → 无障碍", Toast.LENGTH_LONG).show();
                    }
                    if (parent != null) parent.dismiss();
                })
                .setNegativeButton("稍后再说", (d, w) -> {
                    Toast.makeText(this, "已保存。未开启无障碍前，Artemis 光标可移动但点击无效", Toast.LENGTH_LONG).show();
                    if (parent != null) parent.dismiss();
                })
                .show();
    }

    /**
     * 设置界面里的光标实时预览。
     *
     * 用与游戏内 CursorView 完全相同的 CursorAppearance，所以看到的就是实际效果：
     * 光标包会播动画，PNG 显示图片，都没有则内置箭头。
     * 之前这里只画内置箭头，换了图在预览里看不出区别、进游戏才发现不一样。
     *
     * 顺便画一个十字标出热点（鼠标尖的实际位置），这在光标包场景下很有用 ——
     * 不同角色的热点差异极大（文本选择在垂直中间，对角箭头在右上）。
     */
    private final class CursorPreviewView extends View {
        private GameCursorConfig cfg;
        private final com.yuki.yukihub.gamecursor.CursorAppearance appearance =
                new com.yuki.yukihub.gamecursor.CursorAppearance();
        private Runnable ticker;
        private Paint hotspotPaint;
        /**
         * 上次实际加载的外观来源。
         *
         * 不能拿 cfg 前后对比：refresh 传进来的常常就是同一个对象引用，
         * 字段已被就地改掉，比较结果永远"没变"。必须自己留一份快照。
         */
        private String loadedWinName;
        private String loadedIconUri;

        CursorPreviewView(Context c, GameCursorConfig initial) {
            super(c);
            cfg = initial;
            reloadAppearance();
        }

        void refresh(GameCursorConfig c) {
            if (c == null) return;
            cfg = c;
            // 只有外观来源真的变了才重新解码。滑块拖动时 refresh 每帧都来，
            // 每次重解几十帧 PNG 会卡住 UI。
            if (!eq(loadedWinName, cfg.winCursorName) || !eq(loadedIconUri, cfg.iconUri)) {
                reloadAppearance();
            }
            invalidate();
        }

        private void reloadAppearance() {
            appearance.load(MainActivity.this, cfg);
            loadedWinName = cfg != null ? cfg.winCursorName : null;
            loadedIconUri = cfg != null ? cfg.iconUri : null;
            restartTicker();
        }

        private boolean eq(String x, String y) {
            return x == null ? y == null : x.equals(y);
        }

        private void restartTicker() {
            stopTicker();
            if (!appearance.isAnimated()) return;
            appearance.resetAnimation();
            ticker = new Runnable() {
                @Override
                public void run() {
                    if (ticker != this) return;
                    appearance.advance();
                    invalidate();
                    postDelayed(this, Math.max(16, appearance.currentDurationMs()));
                }
            };
            postDelayed(ticker, Math.max(16, appearance.currentDurationMs()));
        }

        private void stopTicker() {
            if (ticker != null) {
                removeCallbacks(ticker);
                ticker = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopTicker();
            appearance.release();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            // 与游戏内一致的尺寸算法：基准高度 × 缩放，按内容宽高比定宽
            float aspect = appearance.aspect();
            float h = dp(44) * gcClampScale(cfg.scale) / GameCursorConfig.DEFAULT_SCALE;
            float w = h * aspect;
            // 预览框放不下时等比缩小，保证小缩放值也能看清
            float maxW = getWidth() * 0.9f, maxH = getHeight() * 0.9f;
            if (w > maxW || h > maxH) {
                float k = Math.min(maxW / w, maxH / h);
                w *= k;
                h *= k;
            }
            float left = (getWidth() - w) / 2f;
            float top = (getHeight() - h) / 2f;

            int saveCount = canvas.save();
            canvas.translate(left, top);
            // 应用不透明度：游戏内是 View.setAlpha()，预览这里没有独立 View，
            // 所以用 saveLayerAlpha 包一层。只包光标本身 ——
            // 下面的热点十字是辅助标记，不该跟着变淡。
            int alpha = Math.round(gcClampAlpha(cfg.alpha) * 255f);
            int layer = -1;
            if (alpha < 255) {
                layer = canvas.saveLayerAlpha(0, 0, w, h, alpha);
            }
            appearance.draw(canvas, w, h, false);
            if (layer >= 0) canvas.restoreToCount(layer);
            canvas.restoreToCount(saveCount);

            // 热点十字：标出"鼠标尖"的实际位置
            if (hotspotPaint == null) {
                hotspotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                hotspotPaint.setStyle(Paint.Style.STROKE);
                hotspotPaint.setStrokeWidth(dp(1));
                hotspotPaint.setColor(0x99FF3B30);
            }
            float hx = left + w * appearance.hotspotX();
            float hy = top + h * appearance.hotspotY();
            float r = dp(4);
            canvas.drawLine(hx - r, hy, hx + r, hy, hotspotPaint);
            canvas.drawLine(hx, hy - r, hx, hy + r, hotspotPaint);
        }
    }

    private void showKrSettingsDialog(Game game) {
        if (game == null || game.rootUri == null || game.rootUri.isEmpty()) {
            Toast.makeText(MainActivity.this, "请先选择游戏目录", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(MainActivity.this, "KR 设置已保存", Toast.LENGTH_SHORT).show();
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
        // 引擎版本可切换：0.7.7 为默认，遇到兼容问题可退回 0.7.6。
        Spinner engineVersion = krSpinner(OnsLibLoader.AVAILABLE_VERSIONS, OnsLibLoader.getSelectedVersion(this));

        CheckBox noVsync = krCheckBox("关闭垂直同步（--no-vsync）", settings.noVsync);
        CheckBox fontCache = krCheckBox("缓存字体（--fontcache）", settings.fontCache);
        CheckBox renderFontOutline = krCheckBox("文字描边替代投影（--render-font-outline）", settings.renderFontOutline);
        CheckBox disableRescale = krCheckBox("不缩放档案内图片（--disable-rescale）", settings.disableRescale);
        CheckBox forceButtonShortcut = krCheckBox("强制启用按键快捷（--force-button-shortcut）", settings.forceButtonShortcut);
        CheckBox wheelDownAdvance = krCheckBox("滚轮下滚推进文本", settings.wheelDownAdvance);
        CheckBox debugLog = krCheckBox("输出引擎调试日志（--debug:1）", settings.debugLog);
        EditText forceWidth = krEdit("宽度，0 为自动", settings.forceWidth > 0 ? String.valueOf(settings.forceWidth) : "");
        forceWidth.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText forceHeight = krEdit("高度，0 为自动", settings.forceHeight > 0 ? String.valueOf(settings.forceHeight) : "");
        forceHeight.setInputType(InputType.TYPE_CLASS_NUMBER);

        root.addView(krLabel("引擎版本")); root.addView(engineVersion);
        root.addView(krLabel("文本编码")); root.addView(encoding);

        // 虚拟按键布局编辑器入口：进全屏界面直接拖，所见即所得
        Button btnLayout = krButton("编辑虚拟按键布局…");
        final TextView layoutSummary = krLabel(onsLayoutSummary());
        layoutSummary.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text_muted));
        btnLayout.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(MainActivity.this,
                        com.yuki.yukihub.ons.OnsButtonLayoutActivity.class));
            } catch (Throwable t) {
                android.widget.Toast.makeText(MainActivity.this,
                        "打开布局编辑器失败：" + t, android.widget.Toast.LENGTH_LONG).show();
            }
        });
        root.addView(krLabel("虚拟按键"));
        root.addView(btnLayout);
        root.addView(layoutSummary);

        root.addView(stretchFull);
        root.addView(ignoreCutout);
        root.addView(disableVideo);
        root.addView(scopedSave);
        root.addView(allowEditArgs);
        root.addView(sharpness);
        root.addView(krLabel("锐化值")); root.addView(sharpnessValue);
        root.addView(krLabel("进阶选项"));
        root.addView(noVsync);
        root.addView(fontCache);
        root.addView(renderFontOutline);
        root.addView(disableRescale);
        root.addView(forceButtonShortcut);
        root.addView(wheelDownAdvance);
        root.addView(debugLog);
        root.addView(krLabel("强制分辨率（宽高需同时填写，留空为自动）"));
        root.addView(forceWidth);
        root.addView(forceHeight);

        TextView tip = krLabel("说明：设置会生成 OnsYuri 原版参数：--root、--font、--fullscreen/--fullscreen2、--enc、--save-dir 等。修改后下次启动 ONS 游戏生效。引擎版本切换需要完全退出游戏进程后才会重新加载。");
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
            settings.noVsync = noVsync.isChecked();
            settings.fontCache = fontCache.isChecked();
            settings.renderFontOutline = renderFontOutline.isChecked();
            settings.disableRescale = disableRescale.isChecked();
            settings.forceButtonShortcut = forceButtonShortcut.isChecked();
            settings.wheelDownAdvance = wheelDownAdvance.isChecked();
            settings.debugLog = debugLog.isChecked();
            settings.forceWidth = parsePositiveInt(forceWidth.getText().toString());
            settings.forceHeight = parsePositiveInt(forceHeight.getText().toString());
            settings.save(this);
            OnsLibLoader.setSelectedVersion(this, String.valueOf(engineVersion.getSelectedItem()));
            if (game != null && (game.emulatorPackage == null || game.emulatorPackage.trim().isEmpty())) {
                game.emulatorPackage = "internal.ons";
                repository.update(game);
                loadGames();
            }
            Toast.makeText(MainActivity.this, "ONS 设置已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            shownWindow.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    /**
     * 虚拟按键当前配置的一句话摘要，显示在设置项下方。
     */
    private String onsLayoutSummary() {
        try {
            com.yuki.yukihub.ons.OnsButtonConfig c =
                    com.yuki.yukihub.ons.OnsButtonConfig.load(this);
            int left = 0, right = 0, custom = 0;
            for (com.yuki.yukihub.ons.OnsButtonConfig.Item it : c.items) {
                if (it.x < 50f) left++; else right++;
                if (it.isCustom()) custom++;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(c.items.size()).append(" 个（左 ").append(left)
              .append(" / 右 ").append(right).append("）");
            if (custom > 0) sb.append("，含 ").append(custom).append(" 个自定义");
            sb.append("　直径 ").append(c.size).append("dp　不透明度 ")
              .append(c.opacity).append("%");
            return sb.toString();
        } catch (Throwable t) {
            return "点击上方按钮进行配置";
        }
    }

    /**
     * 解析用户输入的正整数，非法或空值返回 0（表示「自动 / 不指定」）。
     * 用于强制分辨率这类可留空的数值输入。
     */
    private int parsePositiveInt(String text) {
        if (text == null) return 0;
        String t = text.trim();
        if (t.isEmpty()) return 0;
        try {
            int v = Integer.parseInt(t);
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
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
DynamicTheme dt = DynamicTheme.getInstance();
if (dt.isEnabled() && dt.getColors() != null) {
    v.setButtonTintList(android.content.res.ColorStateList.valueOf(dt.getColors().primary));
} else {
    v.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
    v.setButtonTintList(android.content.res.ColorStateList.valueOf(getColorCompat(com.yuki.yukihub.R.color.yh_primary)));
}
attachUiTouchSound(v, UI_SOUND_SWITCH);
return v;
}

private Button krButton(String text) {
Button b = new Button(this);
b.setText(text);
b.setAllCaps(false);
DynamicTheme dt = DynamicTheme.getInstance();
if (dt.isEnabled() && dt.getColors() != null) {
    b.setBackground(tintButton(dt.getColors()));
    b.setTextColor(0xFFFFFFFF);
} else {
    b.setTextColor(getColorCompat(com.yuki.yukihub.R.color.yh_text));
    b.setBackgroundColor(getColorCompat(com.yuki.yukihub.R.color.yh_card_2));
}
attachUiTouchSound(b, UI_SOUND_CONFIRM);
return b;
}

    private LinearLayout linkCardButton(String text, int iconResId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        DynamicTheme dt = DynamicTheme.getInstance();
        if (dt.isEnabled() && dt.getColors() != null) {
            row.setBackground(tintInput(dt.getColors()));
        } else {
            row.setBackgroundResource(R.drawable.bg_auth_tab_inactive);
        }
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
        ArrayAdapter<String> adapter = krSpinnerAdapter(values);
        sp.setAdapter(adapter);
        DynamicTheme dt = DynamicTheme.getInstance();
        if (dt.isEnabled() && dt.getColors() != null) {
            sp.setBackground(tintInput(dt.getColors()));
        }
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(selected)) { sp.setSelection(i); break; }
        return sp;
    }

    private int getColorCompat(int id) {
        if (Build.VERSION.SDK_INT >= 23) return getColor(id);
        return getResources().getColor(id);
    }

    /** In dynamic theme mode, returns white for button text; otherwise returns yh_primary. */
    private int primaryTextColor() {
        DynamicTheme dt = DynamicTheme.getInstance();
        return (dt.isEnabled() && dt.getColors() != null) ? 0xFFFFFFFF : getColorCompat(R.color.yh_primary);
    }

    /** Create an ArrayAdapter with dynamic theme text colors for Spinner. */
    private ArrayAdapter<String> krSpinnerAdapter(String[] items) {
        DynamicTheme dt = DynamicTheme.getInstance();
        boolean themed = dt.isEnabled() && dt.getColors() != null;
        int itemLayout = themed ? R.layout.spinner_item_themed : R.layout.spinner_item_dark;
        int dropLayout = themed ? R.layout.spinner_dropdown_themed : R.layout.spinner_dropdown_dark;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, itemLayout, items);
        adapter.setDropDownViewResource(dropLayout);
        return adapter;
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
                DocumentFile dir = gameDir(rootUri);
                if (dir != null && dir.isDirectory()) {
                    DocumentFile[] files = dir.listFiles();
                    if (files != null) {
                        for (DocumentFile file : files) {
                            String name = file.getName();
                            if (name == null || !file.isFile()) continue;
                            if (isLaunchCandidate(name) && !options.contains(name)) {
                                options.add(name);
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        // KR 启动入口排序：中文「启动/游戏」xp3 > data.xp3 > 其它（见 KrkrEntryPriority）
        // 非 KR 候选（.desktop / .iso 等）保持原有相对顺序，排在 KR 候选之后。
        java.util.Collections.sort(options, (a, b) -> {
            int sa = com.yuki.yukihub.scanner.KrkrEntryPriority.scoreEntry(a);
            int sb = com.yuki.yukihub.scanner.KrkrEntryPriority.scoreEntry(b);
            boolean ka = sa != com.yuki.yukihub.scanner.KrkrEntryPriority.SCORE_NONE;
            boolean kb = sb != com.yuki.yukihub.scanner.KrkrEntryPriority.SCORE_NONE;
            if (ka && kb) return sb - sa;
            if (ka) return -1;
            if (kb) return 1;
            return 0;
        });
        if (options.contains("[游戏目录]")) options.remove("[游戏目录]");
        options.add("[游戏目录]");
        if (options.isEmpty()) options.add("未扫描到可启动文件，请先选择目录");
        return options;
    }

    private boolean isLaunchCandidate(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xp3") || lower.endsWith(".tjs") || lower.endsWith(".ks")
                || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".txt")
                || lower.endsWith(".dat") || lower.endsWith(".pfs") || lower.endsWith(".desktop")
                || lower.endsWith(".nsa") || lower.endsWith(".sar")
                || lower.endsWith(".iso") || lower.endsWith(".cso") || lower.endsWith(".chd")
                || lower.endsWith(".elf") || lower.endsWith(".pbp")
                // .exe：部分 KR 整合版把真正入口放在同名 exe 上（例：是谁杀了知更鸟.exe），
                // 内核可以直接吃 exe 路径。EmulatorLauncher 早已支持该后缀，
                // 这里补上候选，否则下拉里选不到。
                || lower.endsWith(".exe");
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
return documentDir(rootUri);
}

    private InputStream openKrPrefsInput(String rootUri) {
try {
if (rootUri == null || rootUri.isEmpty()) return null;
if (rootUri.startsWith("/") || rootUri.startsWith("file://")) {
File f = new File(rootUri.startsWith("file://") ? Uri.parse(rootUri).getPath() : rootUri, "Kirikiroid2Preference.xml");
return f.exists() ? new FileInputStream(f) : null;
}
DocumentFile dir = documentDir(rootUri);
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
DocumentFile dir = documentDir(rootUri);
if (dir == null || !dir.isDirectory()) return null;
DocumentFile file = dir.findFile("Kirikiroid2Preference.xml");
if (file == null) file = dir.createFile("text/xml", "Kirikiroid2Preference.xml");
return file == null ? null : getContentResolver().openOutputStream(file.getUri(), "wt");
} catch (Throwable ignored) { return null; }
}
private void showScanResults(List<ScanResult> results) {
        if (results.isEmpty()) { Toast.makeText(this, "未发现子目录候选游戏", Toast.LENGTH_LONG).show(); return; }
        Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setContentView(R.layout.dialog_scan_result);
        tintDialogRoot(d.findViewById(android.R.id.content));
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
if (showToast) Toast.makeText(MainActivity.this, "正在扫描 " + rootUris.size() + " 个目录，请稍候...", Toast.LENGTH_SHORT).show();
        int scanDepth = prefs == null ? DEFAULT_STARTUP_SCAN_DEPTH : prefs.getInt(KEY_STARTUP_SCAN_DEPTH, DEFAULT_STARTUP_SCAN_DEPTH);
        scanDepth = Math.max(1, Math.min(MAX_STARTUP_SCAN_DEPTH, scanDepth));
        final int finalScanDepth = scanDepth;
        final boolean useFastScan = prefs == null || SCAN_MODE_FAST.equals(prefs.getString(KEY_SCAN_MODE, SCAN_MODE_FAST));
        final String scanModeLabel = useFastScan ? "快速" : "兼容";
        final List<String> scanRoots = new ArrayList<>(rootUris);
        AppExecutors.runOnSingle(() -> {
            List<ScanResult> results = new ArrayList<>();
            for (String root : scanRoots) {
                if (root == null || root.trim().isEmpty()) continue;
                try {
                    if (useFastScan) {
                        results.addAll(FastGameScanner.scan(this, Uri.parse(root), finalScanDepth));
                    } else {
                        results.addAll(GameScanner.scan(this, Uri.parse(root), finalScanDepth));
                    }
                } catch (Throwable t) {
                    Log.w("YukiHub", "library scan failed root=" + root + " mode=" + scanModeLabel, t);
                }
            }
            ScanImportStats stats = importScannedGames(results);
            if (stats.added > 0) AppExecutors.runOnIo(() -> autoMatchVndbForImportedGames(stats.importedGames));
            runOnUiThread(() -> {
                autoLibraryScanRunning = false;
                setScanLoading(false);
                loadGames();
                if (showToast) Toast.makeText(this, "扫描[" + scanModeLabel + "] " + scanRoots.size() + " 个目录：新增 " + stats.added + " 个，已存在 " + stats.skipped + " 个" + (stats.added > 0 ? "，正在自动匹配 VNDB 封面" : ""), Toast.LENGTH_SHORT).show();
                // 覆盖已绑定目录的老用户：扫描完成后检查一次是否需要询问 .nomedia。
                // 只在用户主动扫描时触发，开机自动扫描不打扰。
                if (showToast) maybePromptNoMediaForActiveRoots();
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
        List<String> roots = getActiveScanRootUris();
        if (roots.isEmpty()) {
            launchScanRootPicker(-1);
            return;
        }
        runLibraryScan(roots, true);
    }

    private void autoScanLastRootIfAvailable() {
        List<String> roots = getActiveScanRootUris();
        if (roots.isEmpty()) return;
        runLibraryScan(roots, false);
    }

    private void setScanButtonLoadingState(boolean loading) {
        setScanLoading(loading);
    }

    private String defaultLaunchTargetForEngine(EngineType engine) {
        if (engine == EngineType.TYRANO || engine == EngineType.ARTEMIS || engine == EngineType.KIRIKIRI) return "[游戏目录]";
        if (engine == EngineType.GAMEHUB) return "[GameHub]";
        if (engine == EngineType.PSP) return "[PSP游戏文件]";
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
                saveMetadataForSource(g.id, MetadataController.SOURCE_VNDB, meta);
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
            Toast.makeText(MainActivity.this, "已自动补全 " + finalChanged + " 个 VNDB 封面", Toast.LENGTH_SHORT).show();
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
            if (r.engine == EngineType.PSP) g.emulatorPackage = "org.ppsspp.ppsspp";
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
        if (shouldShowLaunchLoadingOverlay(game)) {
            showLaunchLoadingOverlay();
        }
        if (shouldProbeStorageBeforeLaunch(game)) {
            launchGameWithStorageProbe(game);
            return;
        }
        doLaunchGame(game);
    }

    private View launchLoadingOverlay = null;
    private int launchLoadingTipIndex = -1;

    private boolean shouldShowLaunchLoadingOverlay(Game game) {
        if (game == null) return false;
        if (shouldProbeStorageBeforeLaunch(game)) return true;
        String pkg = game.emulatorPackage == null ? "" : game.emulatorPackage.trim();
        if (pkg.isEmpty() && game.engine == EngineType.KIRIKIRI) return true;
        return isInternalKrkrLaunchPackage(pkg);
    }

    private boolean isInternalKrkrLaunchPackage(String emulatorPackage) {
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim().toLowerCase(Locale.ROOT);
        return pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal");
    }

    private void showLaunchLoadingOverlay() {
        try {
            if (launchLoadingOverlay != null) return;
            T3.GameLoadingView overlay = new T3.GameLoadingView(this);
            overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
            ViewGroup root = findViewById(android.R.id.content);
            if (root != null) {
                root.addView(overlay);
                launchLoadingOverlay = overlay;
                launchLoadingTipIndex = overlay.getTipIndex();
            }
        } catch (Throwable ignored) { }
    }

    private void hideLaunchLoadingOverlay() {
        try {
            if (launchLoadingOverlay == null) return;
            View v = launchLoadingOverlay;
            launchLoadingOverlay = null;
            launchLoadingTipIndex = -1;
            v.animate().alpha(0f).setDuration(200L).setStartDelay(0)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        try { ((ViewGroup) v.getParent()).removeView(v); } catch (Throwable ignored) { }
                    }).start();
        } catch (Throwable ignored) {
            launchLoadingOverlay = null;
            launchLoadingTipIndex = -1;
        }
    }

    private void clearLaunchLoadingOverlay() {
        try {
            if (launchLoadingOverlay == null) return;
            View v = launchLoadingOverlay;
            launchLoadingOverlay = null;
            launchLoadingTipIndex = -1;
            try { v.animate().cancel(); } catch (Throwable ignored) { }
            try { ((ViewGroup) v.getParent()).removeView(v); } catch (Throwable ignored) { }
        } catch (Throwable ignored) {
            launchLoadingOverlay = null;
            launchLoadingTipIndex = -1;
        }
    }

    private void doLaunchGame(Game game) {
        String emulatorPackage = game.emulatorPackage == null ? "" : game.emulatorPackage.trim();
        if (emulatorPackage.isEmpty() && game.engine == EngineType.KIRIKIRI) emulatorPackage = "internal.krkr";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ONS) emulatorPackage = "internal.ons";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.TYRANO) emulatorPackage = "internal.tyrano";
        if (emulatorPackage.isEmpty() && game.engine == EngineType.WINLATOR) emulatorPackage = guessInstalledWinlatorPackage();
        if (emulatorPackage.isEmpty() && game.engine == EngineType.GAMEHUB) emulatorPackage = guessInstalledGameHubPackage();
        if (emulatorPackage.isEmpty() && game.engine == EngineType.PSP) emulatorPackage = "org.ppsspp.ppsspp";
        if (game.engine == EngineType.ARTEMIS) {
            emulatorPackage = normalizeArtemisPackage(emulatorPackage);
        }
        String launchTarget = game.launchTarget;
        if (game.engine == EngineType.ARTEMIS || game.engine == EngineType.TYRANO) launchTarget = "[游戏目录]";
        if (game.engine == EngineType.GAMEHUB) {
            String ghMode = game.gamehubLaunchMode == null ? "game" : game.gamehubLaunchMode.trim().toLowerCase(Locale.ROOT);
            if (!("program".equals(ghMode) || "normal".equals(ghMode)) && (game.gamehubLocalGameId == null || game.gamehubLocalGameId.trim().isEmpty())) { clearLaunchLoadingOverlay(); Toast.makeText(this, "请先编辑游戏，通过Shizuku导入GameHub localGameId。", Toast.LENGTH_LONG).show(); return; }
            launchTarget = game.title;
        }
        if (emulatorPackage.isEmpty()) { clearLaunchLoadingOverlay(); Toast.makeText(this, "请先编辑游戏，填写模拟器包名。", Toast.LENGTH_LONG).show(); return; }
        runningGameId = game.id;
        sessionStart = System.currentTimeMillis();
        String launchType = resolveLaunchType(emulatorPackage);
        runningSessionId = repository.startPlaySession(game.id, sessionStart, launchType);
        // 复位「本次运行已处理过未完成记录」标记：本次游玩若异常结束，
        // 回到启动器时应当能再次得到提示，而不是被之前的标记压掉。
        GameRepository.resetStaleSessionHandled();
        launchedExternal = true;
        // 标记正在玩（Steam 风格），并确保后台心跳继续
        try {
            if (presenceManager == null) presenceManager = com.yuki.yukihub.social.PresenceManager.get(this);
            presenceManager.setPlayingGame(game.title);
            com.yuki.yukihub.social.PresenceService.sync(this);
            com.yuki.yukihub.social.PresenceService.refresh(this);
        } catch (Throwable ignored) {}
        if (!launchGameInternal(game, emulatorPackage, launchTarget)) {
            clearLaunchLoadingOverlay();
            repository.cancelPlaySession(runningSessionId);
            launchedExternal = false;
            runningGameId = -1;
            runningSessionId = -1;
            sessionStart = 0;
            try {
                if (presenceManager != null) {
                    presenceManager.clearPlayingGame();
                    com.yuki.yukihub.social.PresenceService.refresh(this);
                }
            } catch (Throwable ignored) {}
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
android.content.Intent intent = EmulatorLauncher.buildInternalKrkrIntent(this, game.rootUri, launchTarget, false, compatMode, krEngineVersion, safFileFallback);
if (launchLoadingOverlay != null) {
    intent.putExtra(T3.GameLoadingView.EXTRA_CONTINUE_LOADING, true);
    intent.putExtra(T3.GameLoadingView.EXTRA_INITIAL_TIP_INDEX, launchLoadingTipIndex);
}
return startActivitySafely(intent);
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
        if (pkg.startsWith("internal.psp") || pkg.equals("org.ppsspp.ppsspp")) {
            // 检查PPSSPP是否安装
            if (!EmulatorLauncher.isPPSSPPInstalled(this)) {
                // 显示提示对话框
                new AlertDialog.Builder(this)
                    .setTitle("需要PPSSPP模拟器")
                    .setMessage("启动PSP游戏需要安装PPSSPP模拟器。\n\n是否现在去下载？")
                    .setPositiveButton("去下载", (d, w) -> {
                        try {
                            startActivity(EmulatorLauncher.getPPSSPPDownloadIntent());
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "无法打开应用商店", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return false;
            }
            return startActivitySafely(EmulatorLauncher.buildInternalPspIntent(this, game.rootUri, launchTarget));
        }
        if (game.engine == EngineType.ANDROID) {
            return EmulatorLauncher.launch(this, pkg);
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
            long end = System.currentTimeMillis();
            repository.finishPlaySession(runningSessionId, end, MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
            // 上报游玩时长到服务器（获得游戏经验）
            reportPlaySessionToServer(runningSessionId, sessionStart, end);
            launchedExternal = false;
            runningGameId = -1;
            runningSessionId = -1;
            sessionStart = 0;
            // 结束游玩状态
            try {
                if (presenceManager == null) presenceManager = com.yuki.yukihub.social.PresenceManager.get(this);
                presenceManager.clearPlayingGame();
                com.yuki.yukihub.social.PresenceService.refresh(this);
            } catch (Throwable ignored) {}
            loadGames();
        }
    }

    /**
     * 游戏会话结束 → 上报游玩时长到服务器（获得游戏经验）。
     * 仅登录用户上报；失败静默忽略（本地 play_sessions 仍保留，后续版本可做云同步补报）。
     */
    private void reportPlaySessionToServer(long sessionId, long start, long end) {
        if (!isLoggedIn()) return;
        AppExecutors.runOnIo(() -> {
            try {
                GameRepository.PlayActivity a = repository.findPlaySession(sessionId);
                if (a == null || a.sessionUuid == null || a.sessionUuid.isEmpty()) return;
                com.yuki.yukihub.social.SocialApiClient client = new com.yuki.yukihub.social.SocialApiClient(MainActivity.this);
                org.json.JSONObject res = client.reportPlayTime(a.sessionUuid, "", a.gameTitle == null ? "" : a.gameTitle, start, end);
                int awarded = res.optInt("expAwarded", 0);
                if (awarded > 0) {
                    Log.i("YukiHub", "Play session reported: exp +" + awarded + ", level " + res.optInt("level", 1));
                }
            } catch (Throwable ignored) {
                // 静默失败，不影响游戏退出流程
            }
        });
    }

    private boolean staleSessionDialogShowing = false;
    /** 未完成记录弹窗期间到达的快捷方式启动请求，弹窗关闭后再执行。 */
    private long pendingShortcutGameId = -1L;

    /**
     * 秒退阈值：未完成且起始时间距今短于此值的记录直接丢弃，不询问用户。
     * 游戏启动失败会连续产生多条这类垃圾记录，逐条询问会让弹窗看起来没完没了。
     * 正常手动退出的游戏由 finishCurrentPlaySessionIfAny 结算，不受此阈值影响。
     */
    private static final long DISCARD_OPEN_SESSION_MS = 10L * 1000L;

    /** 进程内互斥：MainActivity 与 HomeActivity 都会调用，标记放在 GameRepository 供两处共用。 */
    private void finishStalePlaySessionsIfAny() {
        if (repository == null) return;
        // 同一次运行内只处理一次，避免在两个界面间切换时反复弹窗
        if (GameRepository.isStaleSessionHandled()) return;

        // 先静默丢弃启动失败产生的秒退垃圾记录
        int discarded = repository.discardShortOpenPlaySessions(DISCARD_OPEN_SESSION_MS);
        if (discarded > 0) {
            Log.i("YukiHub", "discarded " + discarded + " short open play sessions");
        }

        PlayActivity open = repository.findLatestOpenPlaySession();
        if (open == null) {
            GameRepository.markStaleSessionHandled();
            return;
        }
        GameRepository.markStaleSessionHandled();
        staleSessionDialogShowing = true;
        // 剩余未完成记录的总数：一次性告知并统一处理，不再逐条弹窗
        int total = repository.countOpenPlaySessions();
        long now = System.currentTimeMillis();
        long rawDuration = Math.max(0L, now - open.startTime);
        long duration = Math.min(rawDuration, MAX_PLAY_SESSION_MS);
        String message = "检测到最近一次游玩未正常结束。\n\n"
                + "游戏：" + emptyText(open.gameTitle, "未命名游戏") + "\n"
                + "开始时间：" + TimeFormatUtil.date(open.startTime) + "\n"
                + "可补记时长：" + TimeFormatUtil.playTime(duration) + "\n\n"
                + (total > 1
                    ? "另有 " + (total - 1) + " 条更早的未完成记录，将一并处理。\n\n"
                    : "")
                + "如果这段时间确实在游玩，可选择补记；如果只是测试启动、闪退或误操作，请选择忽略。";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现未完成的游玩记录")
                .setMessage(message)
                .setPositiveButton("补记", (d, w) -> {
                    // 一次性结算全部未完成记录，避免下次进来又弹
                    repository.finishUnfinishedPlaySessions(
                            System.currentTimeMillis(), MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
                    loadGames();
                    updateProfilePanel();
                    Toast.makeText(MainActivity.this, "已补记上次游玩时长", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("忽略", (d, w) -> {
                    // 同样一次性清掉全部，而非只删这一条
                    repository.deleteOpenPlaySessions();
                    loadGames();
                    updateProfilePanel();
                    Toast.makeText(MainActivity.this, "已忽略未完成记录", Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                // 这个弹窗是模态的。桌面快捷方式冷启动时它会先弹出来，
                // 若不拦住，游戏会在弹窗背后被启动，用户看不到也无法确认。
                // 因此启动请求先挂起，弹窗关掉后再执行。
                .setOnDismissListener(d -> {
                    staleSessionDialogShowing = false;
                    consumePendingShortcutLaunch();
                })
                .show();
        styleAlertDialogDark(dialog);
    }

    /** 执行被未完成记录弹窗挡住的快捷方式启动请求。 */
    private void consumePendingShortcutLaunch() {
        long gameId = pendingShortcutGameId;
        pendingShortcutGameId = -1L;
        if (gameId <= 0) return;
        getWindow().getDecorView().post(() -> {
            if (isFinishing() || isDestroyed()) return;
            launchGameFromHome(gameId);
        });
    }

@Override protected void onResume() {
     super.onResume();
     clearLaunchLoadingOverlay();
     enterImmersiveMode();
     // 启动在线心跳（引用计数 retain，避免重复 acquire）
     if (presenceManager == null) presenceManager = com.yuki.yukihub.social.PresenceManager.get(this);
     if (!activityHeartbeatHeld) {
         presenceManager.retainHeartbeat();
         activityHeartbeatHeld = true;
     }
     // 按开关同步前台保活服务
     try {
         if (isLoggedIn() && (prefs == null || prefs.getBoolean(KEY_FRIEND_PLAY_NOTIFY, true))) {
             ensureNotificationPermission();
         }
         com.yuki.yukihub.social.PresenceService.sync(this);
     } catch (Throwable ignored) {}
     finishCurrentPlaySessionIfAny();
    resumeBackgroundVideoIfNeeded();
    
    // 自动刷新 Token（如果已登录但可能过期）
    if (isLoggedIn()) {
        AppExecutors.runOnIo(() -> {
            if (refreshAccessToken()) {
                runOnUiThread(() -> refreshRuntimeAccountState());
            }
        });
    }
    
    refreshRuntimeAccountState();
    maybeAutoWebDavSync();
    maybeAutoServerSync();
    startStatusBarUpdates();
}

@Override
public void onBackPressed() {
    // 多选模式下返回键先退出多选
    if (adapter != null && adapter.isMultiSelectMode()) {
        exitMultiSelectMode();
        return;
    }
    super.onBackPressed();
}

@Override protected void onPause() {
     pauseBackgroundVideoIfNeeded();
     stopStatusBarUpdates();
     // 游戏运行中不释放心跳：后台仍需保持「正在玩」
     // 前台服务若开启也会继续 retain；这里仅在非游玩时 release
     if (presenceManager != null && !launchedExternal && activityHeartbeatHeld) {
         presenceManager.releaseHeartbeat();
         activityHeartbeatHeld = false;
     }
     super.onPause();
 }

@Override protected void onStop() {
    clearLaunchLoadingOverlay();
    super.onStop();
}

@Override protected void onDestroy() {
 releaseBackgroundMediaPlayer();
 releaseUiSoundPool();
 stopStatusBarUpdates();
 // 释放本 Activity 持有的心跳；真正离线由 logout / 服务端超时判定
 // 若仍在游玩且前台服务活着，服务会继续 retain
 if (presenceManager != null && activityHeartbeatHeld) {
     try { presenceManager.releaseHeartbeat(); } catch (Throwable ignored) {}
     activityHeartbeatHeld = false;
 }
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
