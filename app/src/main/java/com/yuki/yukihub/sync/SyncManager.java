package com.yuki.yukihub.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MetadataRepository;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String KEY_METADATA_SOURCE = "metadata_source";
    private static final String KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri";
    private static final String KEY_BACKGROUND_DIM_ENABLED = "background_dim_enabled";
    private static final String KEY_BACKGROUND_DIM_ALPHA = "background_dim_alpha";

    // 游戏库/游戏卡片信息必须完整同步；只限制动态类数据（游玩记录）数量。
    private static final int MAX_PLAY_SESSIONS = 200;

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
        new Thread(() -> {
            try {
                if (listener != null) listener.onSyncStart();
                WebDavClient c = getClient();
                if (c == null) throw new Exception("WebDAV 客户端初始化失败");
                // 坚果云根目录通常不可直接创建同步文件夹；要求用户先在坚果云创建 YukiHub 文件夹。

                JSONObject local = buildLocalSnapshot();
                String localText = local.toString();
                String localHash = sha256(localText);
                String lastHash = syncPrefs.getString(KEY_LAST_SYNC_HASH, "");

                JSONObject remote = null;
                String remoteText = null;
                String remoteHash = "";
                boolean remoteExists = c.exists(REMOTE_FILE);
                if (remoteExists) {
                    remoteText = c.readText(REMOTE_FILE);
                    remote = new JSONObject(remoteText);
                    if (!"YukiHub".equals(remote.optString("app", ""))) throw new Exception("云端文件不是有效的 YukiHub 同步文件");
                    remoteHash = sha256(remoteText);
                }

                SyncResult result = new SyncResult();
                result.localBytes = localText.getBytes("UTF-8").length;
                result.remoteBytes = remoteText == null ? 0 : remoteText.getBytes("UTF-8").length;

                if (!remoteExists) {
                    c.writeText(REMOTE_FILE, localText);
                    markSynced(localHash);
                    result.uploaded = true;
                    if (listener != null) listener.onProgress("首次上传", true);
                    if (listener != null) listener.onSyncComplete(result);
                    return;
                }

                boolean localChanged = !localHash.equals(lastHash);
                boolean remoteChanged = !remoteHash.equals(lastHash);

                // 新设备首次同步：本地没有游戏库而云端已有数据时，直接下载云端，避免默认本地资料参与“智能合并”覆盖云端资料。
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
                    c.writeText(REMOTE_FILE, localText);
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
                    c.writeText(REMOTE_FILE, localText);
                    markSynced(localHash);
                    result.uploaded = true;
                } else {
                    JSONObject merged = mergeSnapshots(local, remote);
                    String mergedText = merged.toString();
                    importSnapshot(new JSONObject(mergedText));
                    c.writeText(REMOTE_FILE, mergedText);
                    markSynced(sha256(mergedText));
                    result.merged = true;
                }
                if (listener != null) listener.onSyncComplete(result);
            } catch (Throwable t) {
                Log.e(TAG, "sync failed", t);
                if (listener != null) listener.onError(t.getMessage() == null ? "未知错误" : t.getMessage());
            }
        }).start();
    }

    public JSONObject exportSnapshotForLocalBackup() throws Exception {
        return buildLocalSnapshot(-1);
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
        root.put("schema", 4);
        root.put("lightweight", true);
        root.put("created_at", 0);
        root.put("note", "Only text metadata is synced. No game files, save files, or binary cover images are embedded.");

        JSONObject profile = new JSONObject();
        profile.put("name", appPrefs.getString(KEY_PROFILE_NAME, "Yuki"));
        profile.put("signature", appPrefs.getString(KEY_PROFILE_SIGNATURE, ""));
        profile.put("avatar_uri", appPrefs.getString(KEY_PROFILE_AVATAR, ""));
        root.put("profile", profile);

        JSONObject settings = new JSONObject();
        settings.put("metadata_source", appPrefs.getString(KEY_METADATA_SOURCE, "vndb"));
        settings.put("last_scan_root_uri", appPrefs.getString(KEY_LAST_SCAN_ROOT_URI, ""));
        // 不同步自定义背景文件引用：本地图片/视频路径跨设备通常无效，且视频背景不应进入同步逻辑。
        settings.put("background_dim_enabled", appPrefs.getBoolean(KEY_BACKGROUND_DIM_ENABLED, true));
        settings.put("background_dim_alpha", appPrefs.getInt(KEY_BACKGROUND_DIM_ALPHA, 120));
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
            appPrefs.edit()
                    .putString(KEY_PROFILE_NAME, profile.optString("name", appPrefs.getString(KEY_PROFILE_NAME, "Yuki")))
                    .putString(KEY_PROFILE_SIGNATURE, profile.optString("signature", appPrefs.getString(KEY_PROFILE_SIGNATURE, "")))
                    .putString(KEY_PROFILE_AVATAR, profile.optString("avatar_uri", appPrefs.getString(KEY_PROFILE_AVATAR, "")))
                    .apply();
        }
        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) {
            SharedPreferences.Editor e = appPrefs.edit();
            String source = settings.optString("metadata_source", "");
            if ("vndb".equals(source) || "bangumi".equals(source)) e.putString(KEY_METADATA_SOURCE, source);
            if (settings.has("last_scan_root_uri")) e.putString(KEY_LAST_SCAN_ROOT_URI, settings.optString("last_scan_root_uri", ""));
            // 不导入 custom_background/custom_background_type，避免旧备份里的本地图片/视频路径污染新设备。
            if (settings.has("background_dim_enabled")) e.putBoolean(KEY_BACKGROUND_DIM_ENABLED, settings.optBoolean("background_dim_enabled", true));
            if (settings.has("background_dim_alpha")) e.putInt(KEY_BACKGROUND_DIM_ALPHA, settings.optInt("background_dim_alpha", 120));
            e.apply();
        }
        GameRepository gameRepo = new GameRepository(context);
        MetadataRepository metaRepo = new MetadataRepository(context);
        gameRepo.importGamesJson(root.optJSONArray("games"));
        gameRepo.importPlaySessionsJson(root.optJSONArray("play_sessions"));
        if (root.has("metadata_cache")) metaRepo.importMetadataJson(root.optJSONArray("metadata_cache"));
    }

    private JSONObject mergeSnapshots(JSONObject local, JSONObject remote) throws Exception {
        importSnapshot(remote);
        importSnapshot(local);
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
        public int localBytes, remoteBytes;
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