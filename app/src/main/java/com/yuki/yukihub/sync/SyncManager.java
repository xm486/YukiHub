package com.yuki.yukihub.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SyncManager {
    private static final String TAG = "SyncManager";
    private static final String SYNC_PREFS = "yukihub_sync";
    private static final String APP_PREFS = "yukihub_prefs";
    // 坚果云 WebDAV 根目录通常不可直接写文件，需要写入一个已存在的同步文件夹。
    // 请用户先在坚果云中创建 YukiHub 文件夹。
    private static final String REMOTE_DIR = "YukiHub";
    private static final String REMOTE_FILE = REMOTE_DIR + "/YukiHub_sync.json";

    private static final String KEY_SERVER_URL = "webdav_server";
    private static final String KEY_USERNAME = "webdav_username";
    private static final String KEY_PASSWORD = "webdav_password";
    private static final String KEY_AUTO_SYNC = "auto_sync";
    private static final String KEY_LAST_SYNC = "last_sync_time";
    private static final String KEY_LAST_SYNC_HASH = "last_sync_hash";

    private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String KEY_PROFILE_SIGNATURE = "profile_signature";
    private static final String KEY_PROFILE_AVATAR = "profile_avatar";
    private static final String KEY_AUTH_AVATAR = "auth_avatar";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled";
    private static final String KEY_METADATA_SOURCE = "metadata_source";
    private static final String SOURCE_VNDB = "vndb";
    private static final String SOURCE_BANGUMI = "bangumi";
private static final String SOURCE_BANGUMI_MIRROR = "bangumi_mirror";
private static final String SOURCE_YMGAL = "ymgal";
    private static final String SOURCE_HIKARINAGI = "hikarinagi";
    private static final String KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri";
private static final String KEY_BACKGROUND_DIM_ENABLED = "background_dim_enabled";
    private static final String KEY_BACKGROUND_DIM_ALPHA = "background_dim_alpha";
    private static final String KEY_AUTO_SCAN_ON_STARTUP = "auto_scan_on_startup";
    private static final String KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth";
    private static final String KEY_ENGINE_LABEL_POSITION = "engine_label_position";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_BACKGROUND_VIDEO_SOUND = "background_video_sound";
    private static final String KEY_KR_COMPAT_MODE = "kr_compat_mode";
    private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";
    private static final String KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir";
    private static final String KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir";
    private static final String KEY_UI_FONT_SCALE = "ui_font_scale";
    private static final String KEY_GAME_COLUMNS = "game_columns";
    private static final String KEY_UI_SCALE = "ui_scale";

    // 游戏库/游戏卡片信息必须完整同步；只限制动态类数据（游玩记录）数量。
    // WebDAV 同步和本地备份统一限制，保持一致
    private static final int MAX_PLAY_SESSIONS = 30;
    // 本地备份保留最近30条游玩记录，足够查看历史且控制文件大小
    private static final int LOCAL_BACKUP_PLAY_SESSION_LIMIT = 30;

    public static final int RESOLVE_CANCEL = 0;
    public static final int RESOLVE_USE_LOCAL = 1;
    public static final int RESOLVE_USE_REMOTE = 2;
    public static final int RESOLVE_MERGE = 3;

    private final Context context;
    private final SharedPreferences syncPrefs;
    private final SharedPreferences appPrefs;
    private WebDavClient client;

    public SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.syncPrefs = this.context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE);
        this.appPrefs = this.context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        SyncConfig c = getConfig();
        return !c.serverUrl.trim().isEmpty() && !c.username.trim().isEmpty() && !c.password.trim().isEmpty();
    }

    public SyncConfig getConfig() {
        return new SyncConfig(
                syncPrefs.getString(KEY_SERVER_URL, ""),
                syncPrefs.getString(KEY_USERNAME, ""),
                syncPrefs.getString(KEY_PASSWORD, ""),
                syncPrefs.getBoolean(KEY_AUTO_SYNC, false));
    }

    public void saveConfig(String serverUrl, String username, String password, boolean autoSync) {
        syncPrefs.edit()
                .putString(KEY_SERVER_URL, serverUrl == null ? "" : serverUrl.trim())
                .putString(KEY_USERNAME, username == null ? "" : username.trim())
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .putBoolean(KEY_AUTO_SYNC, autoSync)
                .apply();
        client = null;
    }

    public WebDavClient getClient() {
        if (client == null && isConfigured()) {
            SyncConfig c = getConfig();
            client = new WebDavClient(c.serverUrl, c.username, c.password);
        }
        return client;
    }

    public boolean testConnection() {
        try {
            WebDavClient c = getClient();
            return c != null && c.testConnection();
        } catch (Throwable t) {
            Log.w(TAG, "testConnection failed", t);
            return false;
        }
    }

    public boolean isAutoSyncEnabled() { return syncPrefs.getBoolean(KEY_AUTO_SYNC, false); }
    public long getLastSyncTime() { return syncPrefs.getLong(KEY_LAST_SYNC, 0); }

    public void sync(SyncListener listener) {
        if (!isConfigured()) {
            if (listener != null) listener.onError("WebDAV 未配置");
            return;
        }
        AppExecutors.runOnSingle(() -> {
            try {
                if (listener != null) listener.onSyncStart();
                WebDavClient c = getClient();
                if (c == null) throw new Exception("WebDAV 客户端初始化失败");
                // 坚果云根目录通常不可直接创建同步文件夹；要求用户先在坚果云创建 YukiHub 文件夹。

                // 同步前：如果用户已登录且有本地头像（file:// 开头），先上传到服务器
                tryUploadLocalAvatar();

                JSONObject local = buildLocalSnapshot();
                String localText = local.toString();
                String localHash = sha256(localText);
                String lastHash = syncPrefs.getString(KEY_LAST_SYNC_HASH, "");

                JSONObject remote = null;
                String remoteText = null;
                String remoteHash = "";
                boolean remoteExists = c.exists(REMOTE_FILE);
                if (remoteExists) {
                    byte[] remoteBytes = c.readFile(REMOTE_FILE);
                    remoteText = decompressIfGzip(remoteBytes);
                    remote = new JSONObject(remoteText);
                    if (!"YukiHub".equals(remote.optString("app", ""))) throw new Exception("云端文件不是有效的 YukiHub 同步文件");
                    remoteHash = sha256(remoteText);
                }

                SyncResult result = new SyncResult();
                result.localBytes = localText.getBytes("UTF-8").length;
                result.remoteBytes = remoteText == null ? 0 : remoteText.getBytes("UTF-8").length;
                result.compressedBytes = compressGzip(localText).length;

                if (!remoteExists) {
                    c.writeFile(REMOTE_FILE, compressGzip(localText));
                    markSynced(localHash);
                    result.uploaded = true;
                    if (listener != null) listener.onProgress("首次上传", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                boolean localChanged = !localHash.equals(lastHash);
                boolean remoteChanged = !remoteHash.equals(lastHash);

                // 新设备首次同步：本地没有游戏库而云端已有数据时，直接下载云端，避免默认本地资料参与"智能合并"覆盖云端资料。
                if ((lastHash == null || lastHash.isEmpty()) && remoteExists && isSnapshotEmpty(local)) {
                    importSnapshot(remote);
                    markSynced(remoteHash);
                    result.downloaded = true;
                    if (listener != null) listener.onProgress("首次下载云端数据", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                if (!localChanged && !remoteChanged) {
                    result.noChanges = true;
                    markSynced(localHash);
                    if (listener != null) listener.onProgress("数据检查", false);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }
                if (localChanged && !remoteChanged) {
                    c.writeFile(REMOTE_FILE, compressGzip(localText));
                    markSynced(localHash);
                    result.uploaded = true;
                    if (listener != null) listener.onProgress("上传本地修改", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }
                if (!localChanged && remoteChanged) {
                    importSnapshot(remote);
                    markSynced(remoteHash);
                    result.downloaded = true;
                    if (listener != null) listener.onProgress("下载云端修改", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                Conflict conflict = new Conflict(local, remote, localHash, remoteHash, result.localBytes, result.remoteBytes);
                int decision = listener == null ? RESOLVE_MERGE : listener.onConflict(conflict);
                if (decision == RESOLVE_CANCEL) {
                    result.cancelled = true;
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }
                if (decision == RESOLVE_USE_REMOTE) {
                    importSnapshot(remote);
                    markSynced(remoteHash);
                    result.downloaded = true;
                } else if (decision == RESOLVE_USE_LOCAL) {
                    c.writeFile(REMOTE_FILE, compressGzip(localText));
                    markSynced(localHash);
                    result.uploaded = true;
                } else {
                    JSONObject merged = mergeSnapshots(local, remote);
                    String mergedText = merged.toString();
                    importSnapshot(new JSONObject(mergedText));
                    c.writeFile(REMOTE_FILE, compressGzip(mergedText));
                    markSynced(sha256(mergedText));
                    result.merged = true;
                }
                if (listener != null) listener.onSyncComplete(result);
            } catch (Throwable t) {
                Log.e(TAG, "sync failed", t);
                if (listener != null) listener.onError(t.getMessage() == null ? "未知错误" : t.getMessage());
            }
        });
    }

    // 本地备份同样限制游玩记录数量，避免备份文件过大
    public JSONObject exportSnapshotForLocalBackup() throws Exception {
        return buildLocalSnapshot(LOCAL_BACKUP_PLAY_SESSION_LIMIT);
    }

    public void importSnapshotFromLocalBackup(JSONObject root) throws Exception {
        importSnapshot(root);
    }

    private JSONObject buildLocalSnapshot() throws Exception {
        return buildLocalSnapshot(MAX_PLAY_SESSIONS);
    }

    private JSONObject buildLocalSnapshot(int playSessionLimit) throws Exception {
        GameRepository gameRepo = new GameRepository(context);
        MetadataRepository metaRepo = new MetadataRepository(context);
        JSONObject root = new JSONObject();
        root.put("app", "YukiHub");
        root.put("schema", 5);
        root.put("lightweight", true);
        root.put("created_at", 0);
        root.put("note", "Only text metadata is synced. No game files, save files, or binary cover images are embedded.");

        JSONObject profile = new JSONObject();
        profile.put("name", appPrefs.getString(KEY_PROFILE_NAME, "Yuki"));
        profile.put("signature", appPrefs.getString(KEY_PROFILE_SIGNATURE, ""));
        // 同步时使用 auth_avatar（服务器 URL），profile_avatar 是本地 file:// 路径不跨设备
        String avatarUri = appPrefs.getString(KEY_AUTH_AVATAR, "");
        if (avatarUri != null && (avatarUri.startsWith("http://") || avatarUri.startsWith("https://"))) {
            profile.put("avatar_uri", avatarUri);
        } else {
            profile.put("avatar_uri", "");
        }
        root.put("profile", profile);

        JSONObject settings = new JSONObject();
        settings.put("metadata_source", appPrefs.getString(KEY_METADATA_SOURCE, "vndb"));
        // 不同步扫描目录（last_scan_root_uri/scan_root_uris）：它们通常包含用户本机目录/存储路径，跨设备无效且可能泄露隐私。
        settings.put("auto_scan_on_startup", appPrefs.getBoolean(KEY_AUTO_SCAN_ON_STARTUP, false));
        settings.put("startup_scan_depth", appPrefs.getInt(KEY_STARTUP_SCAN_DEPTH, 2));
        settings.put("engine_label_position", appPrefs.getString(KEY_ENGINE_LABEL_POSITION, "title"));
        settings.put("sort_mode", appPrefs.getString(KEY_SORT_MODE, "recent"));
        settings.put("background_video_sound", appPrefs.getBoolean(KEY_BACKGROUND_VIDEO_SOUND, false));
        settings.put("kr_compat_mode", appPrefs.getBoolean(KEY_KR_COMPAT_MODE, false));
        settings.put("kr_engine_version", appPrefs.getString(KEY_KR_ENGINE_VERSION, "auto"));
        settings.put("kr_scoped_save_dir", appPrefs.getBoolean(KEY_KR_SCOPED_SAVE_DIR, false));
        settings.put("artemis_scoped_save_dir", appPrefs.getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, false));
        settings.put("ui_font_scale", appPrefs.getFloat(KEY_UI_FONT_SCALE, 1.0f));
        // 不同步自定义背景文件引用：本地图片/视频路径跨设备通常无效，且视频背景不应进入同步逻辑。
        settings.put("background_dim_enabled", appPrefs.getBoolean(KEY_BACKGROUND_DIM_ENABLED, true));
        settings.put("background_dim_alpha", appPrefs.getInt(KEY_BACKGROUND_DIM_ALPHA, 120));
        settings.put("game_columns", appPrefs.getInt(KEY_GAME_COLUMNS, 5));
        settings.put("ui_scale", appPrefs.getFloat(KEY_UI_SCALE, 1.0f));
        root.put("settings", settings);

        root.put("games", gameRepo.exportGamesJson());
        JSONArray sessions = gameRepo.exportPlaySessionsJson();
        root.put("play_sessions", playSessionLimit > 0 ? tail(sessions, playSessionLimit) : sessions);
        root.put("metadata_cache", metaRepo.exportMetadataJson());
        return root;
    }

    private int snapshotSizeKb(JSONObject root) {
        try {
            return root == null ? 0 : root.toString().getBytes("UTF-8").length / 1024;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isSnapshotEmpty(JSONObject root) {
        if (root == null) return true;
        JSONArray games = root.optJSONArray("games");
        return games == null || games.length() == 0;
    }

    private void importSnapshot(JSONObject root) throws Exception {
        if (root == null) return;
        if (!"YukiHub".equals(root.optString("app", ""))) throw new Exception("不是有效的 YukiHub 同步文件");
        JSONObject profile = root.optJSONObject("profile");
        if (profile != null) {
            String incomingAvatar = profile.optString("avatar_uri", "");
            if (incomingAvatar == null || !(incomingAvatar.startsWith("http://") || incomingAvatar.startsWith("https://"))) incomingAvatar = "";
            SharedPreferences.Editor ed = appPrefs.edit()
                    .putString(KEY_PROFILE_NAME, profile.optString("name", appPrefs.getString(KEY_PROFILE_NAME, "Yuki")))
                    .putString(KEY_PROFILE_SIGNATURE, profile.optString("signature", appPrefs.getString(KEY_PROFILE_SIGNATURE, "")));
            if (!incomingAvatar.isEmpty()) {
                ed.putString(KEY_AUTH_AVATAR, incomingAvatar);
                // 如果本地没有头像（profile_avatar 为空、非 file:// 路径、或 file:// 但文件已被清理），从服务器下载到本地
                String localAvatar = appPrefs.getString(KEY_PROFILE_AVATAR, "");
                boolean needDownload = true;
                if (localAvatar != null && localAvatar.startsWith("file://")) {
                    java.io.File localFile = new java.io.File(Uri.parse(localAvatar).getPath());
                    if (localFile.exists()) needDownload = false;
                }
                if (needDownload) {
                    String downloaded = downloadAvatarToLocal(incomingAvatar);
                    if (downloaded != null && !downloaded.isEmpty()) {
                        ed.putString(KEY_PROFILE_AVATAR, downloaded);
                    }
                }
            }
            ed.apply();
        }
        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) {
            SharedPreferences.Editor e = appPrefs.edit();
            String source = settings.optString("metadata_source", "");
            if (SOURCE_VNDB.equals(source) || SOURCE_BANGUMI.equals(source) || SOURCE_BANGUMI_MIRROR.equals(source) || SOURCE_YMGAL.equals(source) || SOURCE_HIKARINAGI.equals(source)) e.putString(KEY_METADATA_SOURCE, source);
            // 兼容旧备份：忽略扫描目录（last_scan_root_uri/scan_root_uris），避免导入跨设备无效路径或泄露本机目录。
            if (settings.has("auto_scan_on_startup")) e.putBoolean(KEY_AUTO_SCAN_ON_STARTUP, settings.optBoolean("auto_scan_on_startup", false));
            if (settings.has("startup_scan_depth")) e.putInt(KEY_STARTUP_SCAN_DEPTH, Math.max(1, Math.min(4, settings.optInt("startup_scan_depth", 2))));
            if (settings.has("engine_label_position")) e.putString(KEY_ENGINE_LABEL_POSITION, "cover".equals(settings.optString("engine_label_position", "title")) ? "cover" : "title");
            if (settings.has("sort_mode")) {
                String sort = settings.optString("sort_mode", "recent");
                if ("name".equals(sort) || "newest".equals(sort) || "recent".equals(sort)) e.putString(KEY_SORT_MODE, sort);
            }
            if (settings.has("background_video_sound")) e.putBoolean(KEY_BACKGROUND_VIDEO_SOUND, settings.optBoolean("background_video_sound", false));
            if (settings.has("kr_compat_mode")) e.putBoolean(KEY_KR_COMPAT_MODE, settings.optBoolean("kr_compat_mode", false));
            if (settings.has("kr_engine_version")) {
                String krVersion = settings.optString("kr_engine_version", "auto");
                if ("auto".equals(krVersion) || "1.3.9".equals(krVersion) || "1.3.4".equals(krVersion)) e.putString(KEY_KR_ENGINE_VERSION, krVersion);
            }
            if (settings.has("kr_scoped_save_dir")) e.putBoolean(KEY_KR_SCOPED_SAVE_DIR, settings.optBoolean("kr_scoped_save_dir", false));
            if (settings.has("artemis_scoped_save_dir")) e.putBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, settings.optBoolean("artemis_scoped_save_dir", false));
            if (settings.has("ui_font_scale")) e.putFloat(KEY_UI_FONT_SCALE, (float) Math.max(0.85d, Math.min(1.30d, settings.optDouble("ui_font_scale", 1.0d))));
            // 不导入 custom_background/custom_background_type，避免旧备份里的本地图片/视频路径污染新设备。
            if (settings.has("background_dim_enabled")) e.putBoolean(KEY_BACKGROUND_DIM_ENABLED, settings.optBoolean("background_dim_enabled", true));
            if (settings.has("background_dim_alpha")) e.putInt(KEY_BACKGROUND_DIM_ALPHA, settings.optInt("background_dim_alpha", 120));
            if (settings.has("game_columns")) e.putInt(KEY_GAME_COLUMNS, Math.max(2, Math.min(10, settings.optInt("game_columns", 5))));
            if (settings.has("ui_scale")) e.putFloat(KEY_UI_SCALE, (float) Math.max(0.70d, Math.min(1.50d, settings.optDouble("ui_scale", 1.0d))));
            e.apply();
        }
        GameRepository gameRepo = new GameRepository(context);
        MetadataRepository metaRepo = new MetadataRepository(context);
        gameRepo.importGamesJson(root.optJSONArray("games"));
        gameRepo.importPlaySessionsJson(root.optJSONArray("play_sessions"));
        if (root.has("metadata_cache")) metaRepo.importMetadataJson(root.optJSONArray("metadata_cache"));
    }

    private JSONObject mergeSnapshots(JSONObject local, JSONObject remote) throws Exception {
        // Import local first as a baseline, then overlay remote on top.
        // This ensures that cloud-sourced metadata (titles, covers from VNDB/Bangumi)
        // always wins over auto-generated folder names from local scans.
        importSnapshot(local);
        importSnapshot(remote);
        return buildLocalSnapshot();
    }

    private JSONArray tail(JSONArray arr, int max) throws Exception {
        if (arr == null) return new JSONArray();
        if (arr.length() <= max) return arr;
        JSONArray out = new JSONArray();
        int start = Math.max(0, arr.length() - max);
        for (int i = start; i < arr.length(); i++) out.put(arr.get(i));
        return out;
    }

    private void markSynced(String hash) {
        syncPrefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).putString(KEY_LAST_SYNC_HASH, hash == null ? "" : hash).apply();
    }

    private String sha256(String text) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] bytes = md.digest((text == null ? "" : text).getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ========== 服务器云同步 ==========

    private static final int SYNC_COOLDOWN_MS = 60 * 1000;  // 手动同步冷却 60 秒
    private static volatile long lastServerSyncTime = 0;   // 上次手动同步时间（内存级，防止频繁调用）
    private static final String KEY_SERVER_LAST_SYNC_HASH = "server_last_sync_hash"; // 服务器同步的 lastHash（独立于 WebDAV）

    private void markServerSynced(String hash) {
        lastServerSyncTime = System.currentTimeMillis();
        appPrefs.edit()
                .putString(KEY_SERVER_LAST_SYNC_HASH, hash == null ? "" : hash)
                .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * 云同步到 yukihub.zh.kg 服务器。
     * 流程（参考 WebDAV sync() 逻辑）：
     * 1. 检查登录状态 + 冷却
     * 2. 上传头像（如果有本地 file:// 头像）
     * 3. 构建本地快照 → 算 hash
     * 4. 先下载云端数据（不是先上传！）
     * 5. 根据本地/云端变化情况决定：首次上传 / 首次下载 / 仅上传 / 仅下载 / 智能合并
     */
    public void syncToServer(SyncListener listener) {
        // 冷却检查
        if (System.currentTimeMillis() - lastServerSyncTime < SYNC_COOLDOWN_MS) {
            if (listener != null) listener.onError("同步冷却中，请稍后再试（60秒内仅限一次）");
            return;
        }

        String accessToken = appPrefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
        if (accessToken == null || accessToken.trim().isEmpty()) {
            if (listener != null) listener.onError("未登录，无法同步");
            return;
        }

        AppExecutors.runOnSingle(() -> {
            try {
                if (listener != null) listener.onSyncStart();

                // 先上传头像
                tryUploadLocalAvatar();

                // 构建本地快照
                JSONObject local = buildLocalSnapshot(LOCAL_BACKUP_PLAY_SESSION_LIMIT);
                String localText = local.toString();
                String localHash = sha256(localText);

                // 服务器同步用自己的 lastHash（独立于 WebDAV 的 syncPrefs）
                String lastHash = appPrefs.getString(KEY_SERVER_LAST_SYNC_HASH, "");

                // 先下载云端数据（而不是先上传！）
                JSONObject remote = null;
                String remoteText = null;
                String remoteHash = "";
                boolean remoteExists = false;
                try {
                    if (listener != null) listener.onProgress("检查云端数据", false);
                    byte[] remoteBytes = httpGetBinary(AUTH_BASE_URL + "/sync/download", accessToken);
                    remoteText = decompressIfGzip(remoteBytes);
                    remote = new JSONObject(remoteText);
                    if (!"YukiHub".equals(remote.optString("app", ""))) throw new Exception("云端数据格式无效");
                    remoteHash = sha256(remoteText);
                    remoteExists = true;
                } catch (Exception downloadEx) {
                    // 404 = 首次上传，没有云端数据，正常
                    if (listener != null) listener.onProgress("云端无数据，准备首次上传", true);
                }

                SyncResult result = new SyncResult();
                result.localBytes = localText.getBytes("UTF-8").length;
                result.remoteBytes = remoteText == null ? 0 : remoteText.getBytes("UTF-8").length;

                // 云端没有数据 → 首次上传
                if (!remoteExists) {
                    if (listener != null) listener.onProgress("首次上传", true);
                    byte[] compressed = compressGzip(localText);
                    String uploadResp = httpPostBinary(AUTH_BASE_URL + "/sync/upload", accessToken, compressed);
                    JSONObject uploadJson = new JSONObject(uploadResp);
                    if (!uploadJson.optBoolean("success", false)) {
                        throw new Exception("服务器上传失败: " + uploadJson.optString("error", "未知错误"));
                    }
                    markServerSynced(localHash);
                    result.uploaded = true;
                    result.compressedBytes = compressed.length;
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                // 新设备首次同步：本地没有游戏库而云端已有数据时，直接下载云端
                if ((lastHash == null || lastHash.isEmpty()) && remoteExists && isSnapshotEmpty(local)) {
                    importSnapshot(remote);
                    markServerSynced(remoteHash);
                    result.downloaded = true;
                    result.compressedBytes = result.remoteBytes; // 下载场景用远端大小
                    if (listener != null) listener.onProgress("首次下载云端数据", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                boolean localChanged = !localHash.equals(lastHash);
                boolean remoteChanged = !remoteHash.equals(lastHash);

                // 两边都没变
                if (!localChanged && !remoteChanged) {
                    result.noChanges = true;
                    markServerSynced(localHash);
                    result.compressedBytes = compressGzip(localText).length;
                    if (listener != null) listener.onProgress("数据已是最新", false);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                // 本地变了，云端没变 → 上传
                if (localChanged && !remoteChanged) {
                    if (listener != null) listener.onProgress("上传本地修改", true);
                    byte[] compressed = compressGzip(localText);
                    String uploadResp = httpPostBinary(AUTH_BASE_URL + "/sync/upload", accessToken, compressed);
                    JSONObject uploadJson = new JSONObject(uploadResp);
                    if (!uploadJson.optBoolean("success", false)) {
                        throw new Exception("服务器上传失败: " + uploadJson.optString("error", "未知错误"));
                    }
                    markServerSynced(localHash);
                    result.uploaded = true;
                    result.compressedBytes = compressed.length;
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                // 本地没变，云端变了 → 下载
                if (!localChanged && remoteChanged) {
                    importSnapshot(remote);
                    markServerSynced(remoteHash);
                    result.downloaded = true;
                    result.compressedBytes = result.remoteBytes;
                    if (listener != null) listener.onProgress("下载云端修改", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                // 两边都变了 → 智能合并
                Conflict conflict = new Conflict(local, remote, localHash, remoteHash, result.localBytes, result.remoteBytes);
                int decision = listener == null ? RESOLVE_MERGE : listener.onConflict(conflict);
                if (decision == RESOLVE_CANCEL) {
                    result.cancelled = true;
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }
                if (decision == RESOLVE_USE_REMOTE) {
                    importSnapshot(remote);
                    markServerSynced(remoteHash);
                    result.downloaded = true;
                    result.compressedBytes = result.remoteBytes;
                } else if (decision == RESOLVE_USE_LOCAL) {
                    byte[] compressed = compressGzip(localText);
                    String uploadResp = httpPostBinary(AUTH_BASE_URL + "/sync/upload", accessToken, compressed);
                    JSONObject uploadJson = new JSONObject(uploadResp);
                    if (!uploadJson.optBoolean("success", false)) {
                        throw new Exception("服务器上传失败: " + uploadJson.optString("error", "未知错误"));
                    }
                    markServerSynced(localHash);
                    result.uploaded = true;
                    result.compressedBytes = compressed.length;
                } else {
                    // 智能合并
                    if (listener != null) listener.onProgress("合并云端数据", true);
                    JSONObject merged = mergeSnapshots(local, remote);
                    String mergedText = merged.toString();
                    importSnapshot(new JSONObject(mergedText));
                    byte[] mergedCompressed = compressGzip(mergedText);
                    String uploadResp = httpPostBinary(AUTH_BASE_URL + "/sync/upload", accessToken, mergedCompressed);
                    JSONObject uploadJson = new JSONObject(uploadResp);
                    if (!uploadJson.optBoolean("success", false)) {
                        throw new Exception("服务器上传失败: " + uploadJson.optString("error", "未知错误"));
                    }
                    markServerSynced(sha256(mergedText));
                    result.merged = true;
                    result.compressedBytes = mergedCompressed.length;
                }
                if (listener != null) listener.onSyncComplete(result);

            } catch (Throwable t) {
                Log.e(TAG, "syncToServer failed", t);
                if (listener != null) listener.onError(t.getMessage() == null ? "同步失败" : t.getMessage());
            }
        });
    }

    /**
     * 自动同步到服务器（App 启动时调用）。
     * 条件：已登录 + 自动同步开关打开 + 距上次同步超过 10 分钟。
     * @return true 表示已触发同步（调用者应等待 onComplete/onError），false 表示未触发（调用者应清理状态）
     */
    public boolean maybeAutoSyncToServer(SyncListener listener) {
        String accessToken = appPrefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
        if (accessToken == null || accessToken.trim().isEmpty()) return false;

        boolean autoSync = appPrefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false);
        if (!autoSync) return false;

        long last = prefs_getLastSyncAt();
        if (last > 0 && System.currentTimeMillis() - last < 10L * 60L * 1000L) return false;

        // 冷却检查也要过
        if (System.currentTimeMillis() - lastServerSyncTime < SYNC_COOLDOWN_MS) return false;

        syncToServer(listener);
        return true;
    }

    private static final String KEY_LAST_SYNC_AT = "last_sync_at";

    private long prefs_getLastSyncAt() {
        return appPrefs.getLong(KEY_LAST_SYNC_AT, 0);
    }

    private void prefs_setLastSyncAt() {
        appPrefs.edit().putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis()).apply();
    }

    /**
     * HTTP POST 发送二进制数据，返回响应文本。
     */
    private String httpPostBinary(String urlStr, String accessToken, byte[] data) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(data);
            os.flush();
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while (is != null && (len = is.read(buf)) != -1) bos.write(buf, 0, len);
            response = bos.toString("UTF-8");
        }
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + response);
        }
        return response;
    }

    /**
     * HTTP GET 下载二进制数据。
     */
    private byte[] httpGetBinary(String urlStr, String accessToken) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        int code = conn.getResponseCode();
        if (code == 404) {
            throw new Exception("No sync data");
        }
        if (code < 200 || code >= 300) {
            String err = "";
            try (InputStream is = conn.getErrorStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (is != null) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                    err = bos.toString("UTF-8");
                }
            } catch (Exception ignored) { }
            throw new Exception("HTTP " + code + ": " + err);
        }
        try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toByteArray();
        }
    }

    // ========== 头像上传/下载 ==========

    private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";
    private static final int AVATAR_MAX_PIXELS = 384;   // 压缩到 384px
    private static final int AVATAR_QUALITY = 85;       // JPEG 85%
    private static final int AVATAR_MAX_BYTES = 200 * 1024; // 服务端限制 200KB

    /**
     * 从服务器下载头像到本地存储，返回 file:// 路径。
     * 用于新设备同步时恢复头像。
     */
    private String downloadAvatarToLocal(String urlStr) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Avatar download failed: HTTP " + code);
                return null;
            }
            // 读取图片
            Bitmap bitmap;
            try (InputStream is = conn.getInputStream()) {
                bitmap = BitmapFactory.decodeStream(is);
            }
            if (bitmap == null) {
                Log.w(TAG, "Avatar download: failed to decode bitmap");
                return null;
            }
            // 存到本地 avatars 目录
            java.io.File dir = new java.io.File(context.getFilesDir(), "avatars");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "avatar_sync_" + System.currentTimeMillis() + ".jpg");
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
            bitmap.recycle();
            String localPath = Uri.fromFile(file).toString();
            Log.i(TAG, "Avatar downloaded to: " + localPath);
            return localPath;
        } catch (Throwable t) {
            Log.w(TAG, "downloadAvatarToLocal failed", t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 同步前检测：如果用户已登录且本地头像是 file:// 路径（尚未上传），
     * 则压缩后上传到服务器，成功后把本地路径替换为服务器 URL。
     * 失败不影响后续同步流程。
     */
    private void tryUploadLocalAvatar() {
        try {
            String accessToken = appPrefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
            if (accessToken == null || accessToken.trim().isEmpty()) return;

            String avatarUri = appPrefs.getString(KEY_PROFILE_AVATAR, "");
            if (avatarUri == null || avatarUri.trim().isEmpty()) return;

            // 只处理本地 file:// 路径的头像；http(s):// 已是服务器地址，跳过
            if (!avatarUri.startsWith("file://")) return;

            // 已上传过（auth_avatar 有值）则跳过
            String existingAuth = appPrefs.getString(KEY_AUTH_AVATAR, "");
            if (existingAuth != null && !existingAuth.isEmpty()) return;

            Uri uri = Uri.parse(avatarUri);
            // 读取图片文件
            java.io.File avatarFile = new java.io.File(uri.getPath());
            if (!avatarFile.exists()) return;

            // 读取 + 压缩
            byte[] compressed = compressAvatar(avatarFile);
            if (compressed == null || compressed.length == 0) return;

            if (compressed.length > AVATAR_MAX_BYTES) {
                Log.w(TAG, "Avatar still too large after compression: " + compressed.length + " bytes");
                return;
            }

            // 上传
            String urlStr = AUTH_BASE_URL + "/upload_avatar";
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(compressed);
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Avatar upload failed: HTTP " + code);
                return;
            }
            // 读取响应
            String response;
            try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                response = bos.toString("UTF-8");
            }
            JSONObject resp = new JSONObject(response);
            String avatarUrl = resp.optString("avatarUrl", "");
            if (avatarUrl.isEmpty()) {
                Log.w(TAG, "Avatar upload: server did not return avatarUrl");
                return;
            }
            // 成功：只标记 auth_avatar（服务器 URL），profile_avatar 保留本地路径用于显示
            appPrefs.edit().putString(KEY_AUTH_AVATAR, avatarUrl).apply();
            Log.i(TAG, "Avatar uploaded: " + avatarUrl + " (" + compressed.length + " bytes)");
        } catch (Throwable t) {
            Log.w(TAG, "tryUploadLocalAvatar failed (non-fatal)", t);
        }
    }

    /**
     * 立即上传本地头像到服务器（改头像后直接调用，不等同步）。
     * 公开方法，比 tryUploadLocalAvatar 更宽松：不检查 auth_avatar 是否已有值。
     */
    public void uploadAvatarNow() {
        try {
            String accessToken = appPrefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
            if (accessToken == null || accessToken.trim().isEmpty()) return;

            String avatarUri = appPrefs.getString(KEY_PROFILE_AVATAR, "");
            if (avatarUri == null || avatarUri.trim().isEmpty()) return;

            if (!avatarUri.startsWith("file://")) return;

            Uri uri = Uri.parse(avatarUri);
            java.io.File avatarFile = new java.io.File(uri.getPath());
            if (!avatarFile.exists()) return;

            byte[] compressed = compressAvatar(avatarFile);
            if (compressed == null || compressed.length == 0) return;
            if (compressed.length > AVATAR_MAX_BYTES) {
                Log.w(TAG, "Avatar still too large after compression: " + compressed.length + " bytes");
                return;
            }

            String urlStr = AUTH_BASE_URL + "/upload_avatar";
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(compressed);
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "uploadAvatarNow failed: HTTP " + code);
                return;
            }
            String response;
            try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                response = bos.toString("UTF-8");
            }
            JSONObject resp = new JSONObject(response);
            String avatarUrl = resp.optString("avatarUrl", "");
            if (avatarUrl.isEmpty()) {
                Log.w(TAG, "uploadAvatarNow: server did not return avatarUrl");
                return;
            }
            appPrefs.edit().putString(KEY_AUTH_AVATAR, avatarUrl).apply();
            Log.i(TAG, "Avatar uploaded immediately: " + avatarUrl + " (" + compressed.length + " bytes)");
        } catch (Throwable t) {
            Log.w(TAG, "uploadAvatarNow failed (non-fatal)", t);
        }
    }

    /**
     * 读取本地图片文件，缩放到 AVATAR_MAX_PIXELS，JPEG 压缩为 byte[]。
     */
    private byte[] compressAvatar(java.io.File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            Bitmap bitmap = BitmapFactory.decodeStream(fis);
            if (bitmap == null) return null;
            // 缩放
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w > AVATAR_MAX_PIXELS || h > AVATAR_MAX_PIXELS) {
                float scale = Math.min(AVATAR_MAX_PIXELS / (float) w, AVATAR_MAX_PIXELS / (float) h);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, (int) (w * scale)),
                        Math.max(1, (int) (h * scale)), true);
                bitmap.recycle();
                bitmap = scaled;
            }
            // JPEG 压缩
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
            bitmap.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, bos);
            bitmap.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "compressAvatar failed", t);
            return null;
        }
    }

    /**
     * 将 JSON 文本 gzip 压缩为 byte[]，用于 WebDAV 上传和本地备份写入。
     */
    private static byte[] compressGzip(String text) throws Exception {
        byte[] raw = (text == null ? "" : text).getBytes("UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(256, raw.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(raw);
            gzip.finish();
        }
        return bos.toByteArray();
    }

    /**
     * 读取 WebDAV / 本地备份的 byte[] 数据，自动检测 gzip 格式并解压。
     * 兼容老的纯 JSON 云端文件：如果不是 gzip 格式（没有 0x1f 0x8b 魔数），直接当 UTF-8 文本返回。
     */
    private static String decompressIfGzip(byte[] data) throws Exception {
        if (data == null || data.length == 0) return "";
        // gzip 文件头: 0x1f 0x8b
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data)); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = gzip.read(buf)) != -1) bos.write(buf, 0, len);
                return bos.toString("UTF-8");
            }
        }
        // 不是 gzip，按纯 JSON 文本处理（兼容老格式）
        return new String(data, "UTF-8");
    }

    public static class SyncConfig {
        public final String serverUrl, username, password;
        public final boolean autoSync;
        public SyncConfig(String serverUrl, String username, String password, boolean autoSync) {
            this.serverUrl = serverUrl; this.username = username; this.password = password; this.autoSync = autoSync;
        }
    }

    public static class Conflict {
        public final JSONObject local, remote;
        public final String localHash, remoteHash;
        public final int localBytes, remoteBytes;
        public Conflict(JSONObject local, JSONObject remote, String localHash, String remoteHash, int localBytes, int remoteBytes) {
            this.local = local; this.remote = remote; this.localHash = localHash; this.remoteHash = remoteHash; this.localBytes = localBytes; this.remoteBytes = remoteBytes;
        }
    }

    public static class SyncResult {
        public boolean uploaded, downloaded, merged, noChanges, cancelled;
        public int localBytes, remoteBytes, compressedBytes;
        public boolean hasChanges() { return uploaded || downloaded || merged; }
    }

    public interface SyncListener {
        void onSyncStart();
        void onProgress(String item, boolean changed);
        int onConflict(Conflict conflict);
        void onSyncComplete(SyncResult result);
        void onError(String error);
    }
}