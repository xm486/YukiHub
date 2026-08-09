package com.yuki.yukihub.metadata;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.yuki.yukihub.R;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MetadataController {

    public static final String SOURCE_VNDB = "vndb";
    public static final String SOURCE_BANGUMI = "bangumi";
    public static final String SOURCE_BANGUMI_MIRROR = "bangumi_mirror";
    public static final String SOURCE_YMGAL = "ymgal";
    public static final String SOURCE_HIKARINAGI = "hikarinagi";

    public static final String KEY_METADATA_SOURCE = "metadata_source";
    public static final String KEY_VISIBLE_METADATA_SOURCE_PREFIX = "visible_metadata_source_";
    public static final String KEY_BANGUMI_TOKEN = "bangumi_token";
    public static final String KEY_SIDE_TRANSLATED_PREFIX = "side_translated_";

    private final Delegate delegate;

    public MetadataController(Delegate delegate) {
        this.delegate = delegate;
    }

    public interface Delegate {
        SharedPreferences prefs();
        MetadataRepository metadataRepository();
        GameRepository gameRepository();
        List<Game> allGames();

        // Context & Activity
        void runOnUiThread(Runnable r);
        android.app.Activity activity();

        // UI state
        Game selectedGame();
        void setSelectedGame(Game game);
        VnMetadata currentSideMetadata();
        void setCurrentSideMetadata(VnMetadata meta);
        boolean sideShowingTranslatedDescription();
        void setSideShowingTranslatedDescription(boolean value);
        String sideFullDescription();
        void setSideFullDescription(String text);

        // UI views
        TextView sideDetailTitle();
        TextView sideDetailOriginalTitle();
        TextView sideDetailHint();
        TextView sideDetailPath();
        TextView sideDetailDeveloper();
        TextView sideDetailDate();
        TextView sideDetailRating();
        TextView sideDetailLength();
        TextView sideDetailTags();
        TextView sideDescToggle();
        TextView sideTranslateToggle();
        TextView sideMetadataSourceBadge();
        ImageView sideDetailCover();
        View sideDetailPlaceholder();
        ImageView sideScreenshot1();
        ImageView sideScreenshot2();
        LinearLayout sideTagContainer();
        TextView sideBtnLaunch();
        TextView sideBtnOptions();
        com.yuki.yukihub.ui.GameAdapter adapter();

        // UI helpers
        int dp(int value);
        int getColorCompat(int id);
        void styleAlertDialogDark(AlertDialog dialog);
        void playUiSound(int type);
        void applyImmersiveToWindow(android.view.Window window);
        void loadRemoteImage(String url, ImageView target, String prefix);
        void setSideDescription(String text);
        void renderSideDescription();
        void renderTagChips(String tagsText);
        void updateTranslateButtonState();
        String emptyText(String s, String fallback);
        String safeCoverUri(Game g);
        String initials(String title);
        String displayPath(String value);
        File persistentRemoteCoverDir();
        File cacheDir();
        boolean isMissingFileUri(String uriText);
        void loadGames();
        void updateSideDetail(Game game);
        void showEditDialog(Game game);
        void showPlayStatusDialog(Game game, Dialog parentDialog);
        void showEditPlayTimeDialog(Game game);
        void showKrSettingsDialog(Game game);
        void showOnsSettingsDialog(Game game);
        void showDetailDialog(Game game);
        void confirmDeleteGame(Game game);
        void showToast(String text, int duration);
        void updateProfilePanel();

        // Translation
        String translateTextToChinese(String text) throws Exception;
    }

    /**
     * 检查Activity是否还存活，防止内存泄漏和崩溃
     */
    private boolean isActivityAlive() {
        try {
            android.app.Activity a = delegate.activity();
            return a != null && !a.isDestroyed() && !a.isFinishing();
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== metadata source ========================

    public String metadataSource() {
        return delegate.prefs() == null ? SOURCE_VNDB : delegate.prefs().getString(KEY_METADATA_SOURCE, SOURCE_VNDB);
    }

    public String metadataSourceLabel() {
        return metadataSourceLabel(metadataSource());
    }

    public String metadataSourceLabel(String source) {
        if (SOURCE_BANGUMI.equals(source)) return "Bangumi";
        if (SOURCE_BANGUMI_MIRROR.equals(source)) return "Bangumi镜像";
        if (SOURCE_YMGAL.equals(source)) return "月幕Gal";
        if (SOURCE_HIKARINAGI.equals(source)) return "Hikarinagi";
        return "VNDB";
    }

    public String normalizeMetadataSource(String source) {
        if (SOURCE_BANGUMI.equals(source) || SOURCE_BANGUMI_MIRROR.equals(source) || SOURCE_YMGAL.equals(source) || SOURCE_HIKARINAGI.equals(source)) return source;
        return SOURCE_VNDB;
    }

    public boolean isValidMetadataSource(String source) {
        return SOURCE_VNDB.equals(source) || SOURCE_BANGUMI.equals(source) || SOURCE_BANGUMI_MIRROR.equals(source) || SOURCE_YMGAL.equals(source) || SOURCE_HIKARINAGI.equals(source);
    }

    public String visibleMetadataSource(long gameId) {
        if (delegate.prefs() == null || gameId <= 0) return "";
        String source = delegate.prefs().getString(KEY_VISIBLE_METADATA_SOURCE_PREFIX + gameId, "");
        return isValidMetadataSource(source) ? source : "";
    }

    public void setVisibleMetadataSource(long gameId, String source) {
        if (delegate.prefs() == null || gameId <= 0) return;
        delegate.prefs().edit().putString(KEY_VISIBLE_METADATA_SOURCE_PREFIX + gameId, normalizeMetadataSource(source)).apply();
    }

    public void clearVisibleMetadataSource(long gameId) {
        if (delegate.prefs() == null || gameId <= 0) return;
        delegate.prefs().edit().remove(KEY_VISIBLE_METADATA_SOURCE_PREFIX + gameId).apply();
    }

    public VnMetadata metadataForSource(long gameId, String source) {
        if (delegate.metadataRepository() == null || gameId <= 0) return null;
        String s = normalizeMetadataSource(source);
        if (SOURCE_YMGAL.equals(s)) return delegate.metadataRepository().getYmgal(gameId);
        if (SOURCE_HIKARINAGI.equals(s)) return delegate.metadataRepository().getHikarinagi(gameId);
        if (SOURCE_BANGUMI.equals(s) || SOURCE_BANGUMI_MIRROR.equals(s)) return delegate.metadataRepository().getBangumi(gameId);
        return delegate.metadataRepository().getVndb(gameId);
    }

    public String metadataSourceForVisibleMetadata(long gameId, VnMetadata meta) {
        if (delegate.metadataRepository() == null || gameId <= 0 || meta == null) return "";
        try {
            String visible = visibleMetadataSource(gameId);
            if (!visible.isEmpty()) {
                VnMetadata visibleMeta = metadataForSource(gameId, visible);
                if (visibleMeta != null && sameMetadataIdentity(visibleMeta, meta)) return visible;
            }
            VnMetadata v = delegate.metadataRepository().getVndb(gameId);
            if (v != null && sameMetadataIdentity(v, meta)) return SOURCE_VNDB;
            VnMetadata b = delegate.metadataRepository().getBangumi(gameId);
            if (b != null && sameMetadataIdentity(b, meta)) return SOURCE_BANGUMI;
            VnMetadata y = delegate.metadataRepository().getYmgal(gameId);
            if (y != null && sameMetadataIdentity(y, meta)) return SOURCE_YMGAL;
            VnMetadata h = delegate.metadataRepository().getHikarinagi(gameId);
            if (h != null && sameMetadataIdentity(h, meta)) return SOURCE_HIKARINAGI;
        } catch (Throwable ignored) { }
        return "";
    }

    public String metadataSourceLabelForVisibleMetadata(long gameId, VnMetadata meta) {
        String source = metadataSourceForVisibleMetadata(gameId, meta);
        return source.isEmpty() ? "" : metadataSourceLabel(source);
    }

    public void updateSideMetadataSourceBadge(String label) {
        if (delegate.sideMetadataSourceBadge() == null) return;
        if (label == null || label.trim().isEmpty()) {
            delegate.sideMetadataSourceBadge().setVisibility(View.GONE);
            delegate.sideMetadataSourceBadge().setText("");
            return;
        }
        String text = label.trim();
        delegate.sideMetadataSourceBadge().setText(text);
        int color = delegate.getColorCompat(R.color.yh_primary);
        if (text.contains("Bangumi")) color = delegate.getColorCompat(R.color.yh_secondary);
        else if (text.contains("月幕")) color = delegate.getColorCompat(R.color.yh_warning);
        else if (text.contains("Hikarinagi")) color = delegate.getColorCompat(R.color.yh_secondary);
        delegate.sideMetadataSourceBadge().setTextColor(color);
        delegate.sideMetadataSourceBadge().setVisibility(View.VISIBLE);
    }

    public boolean usingBangumi() {
        String source = metadataSource();
        return SOURCE_BANGUMI.equals(source) || SOURCE_BANGUMI_MIRROR.equals(source);
    }

    public boolean usingBangumiMirror() {
        return SOURCE_BANGUMI_MIRROR.equals(metadataSource());
    }

    public boolean usingYmgal() {
        return SOURCE_YMGAL.equals(metadataSource());
    }

    public boolean usingHikarinagi() {
        return SOURCE_HIKARINAGI.equals(metadataSource());
    }

    public String bangumiToken() {
        return delegate.prefs() == null ? "" : delegate.prefs().getString(KEY_BANGUMI_TOKEN, "");
    }

    // ======================== fetch metadata ========================

    public void fetchSelectedMetadata(Game game) {
        if (game == null) return;
        String visibleSource = visibleMetadataSource(game.id);
        if (!visibleSource.isEmpty()) {
            VnMetadata visibleCached = metadataForSource(game.id, visibleSource);
            if (visibleCached != null) {
                applyVndbMetadata(visibleCached, game);
                return;
            }
        }
        VnMetadata cached = anyCachedMetadata(game.id);
        if (cached != null) {
            applyVndbMetadata(cached, game);
            return;
        }
        fetchCurrentSourceMetadata(game, false);
    }

    public void fetchSelectedMetadata(Game game, boolean forceRefresh) {
        if (forceRefresh) {
            fetchCurrentSourceMetadata(game, true);
        } else {
            fetchSelectedMetadata(game);
        }
    }

    public void fetchCurrentSourceMetadata(Game game, boolean forceRefresh) {
        if (usingYmgal()) fetchYmgalMetadata(game, forceRefresh);
        else if (usingHikarinagi()) fetchHikarinagiMetadata(game, forceRefresh);
        else if (usingBangumi()) fetchBangumiMetadata(game, forceRefresh);
        else fetchVndbMetadata(game, forceRefresh);
    }

    public VnMetadata currentSourceCachedMetadata(long gameId) {
        return metadataForSource(gameId, metadataSource());
    }

    public VnMetadata anyCachedMetadata(long gameId) {
        if (delegate.metadataRepository() == null || gameId <= 0) return null;
        VnMetadata meta = delegate.metadataRepository().getVndb(gameId);
        if (meta != null) return meta;
        meta = delegate.metadataRepository().getBangumi(gameId);
        if (meta != null) return meta;
        meta = delegate.metadataRepository().getYmgal(gameId);
        if (meta != null) return meta;
        meta = delegate.metadataRepository().getHikarinagi(gameId);
        if (meta != null) return meta;
        return null;
    }

    public VnMetadata otherSourceCachedMetadata(long gameId) {
        if (delegate.metadataRepository() == null || gameId <= 0) return null;
        String current = metadataSource();
        VnMetadata meta;
        if (!SOURCE_VNDB.equals(current)) {
            meta = delegate.metadataRepository().getVndb(gameId);
            if (meta != null) return meta;
        }
        if (!SOURCE_BANGUMI.equals(current) && !SOURCE_BANGUMI_MIRROR.equals(current)) {
            meta = delegate.metadataRepository().getBangumi(gameId);
            if (meta != null) return meta;
        }
        if (!SOURCE_YMGAL.equals(current)) {
            meta = delegate.metadataRepository().getYmgal(gameId);
            if (meta != null) return meta;
        }
        if (!SOURCE_HIKARINAGI.equals(current)) {
            meta = delegate.metadataRepository().getHikarinagi(gameId);
            if (meta != null) return meta;
        }
        return null;
    }

    // ======================== save metadata ========================

    public void saveCurrentSourceMetadata(long gameId, VnMetadata meta) {
        if (delegate.metadataRepository() == null || gameId <= 0 || meta == null) return;
        String source = metadataSource();
        if (SOURCE_YMGAL.equals(source)) delegate.metadataRepository().saveYmgal(gameId, meta);
        else if (SOURCE_HIKARINAGI.equals(source)) delegate.metadataRepository().saveHikarinagi(gameId, meta);
        else if (SOURCE_BANGUMI.equals(source) || SOURCE_BANGUMI_MIRROR.equals(source)) delegate.metadataRepository().saveBangumi(gameId, meta);
        else delegate.metadataRepository().saveVndb(gameId, meta);
        setVisibleMetadataSource(gameId, source);
    }

    public void saveVisibleMetadata(long gameId, VnMetadata meta) {
        if (delegate.metadataRepository() == null || gameId <= 0 || meta == null) return;
        try {
            String visibleSource = visibleMetadataSource(gameId);
            VnMetadata visible = visibleSource.isEmpty() ? null : metadataForSource(gameId, visibleSource);
            if (visible != null && sameMetadataIdentity(visible, meta)) {
                saveMetadataForSource(gameId, visibleSource, meta);
                return;
            }
            String source = metadataSourceForVisibleMetadata(gameId, meta);
            if (!source.isEmpty()) {
                saveMetadataForSource(gameId, source, meta);
                return;
            }
        } catch (Throwable ignored) { }
        saveCurrentSourceMetadata(gameId, meta);
    }

    public void saveMetadataForSource(long gameId, String source, VnMetadata meta) {
        if (delegate.metadataRepository() == null || gameId <= 0 || meta == null) return;
        String s = normalizeMetadataSource(source);
        if (SOURCE_YMGAL.equals(s)) delegate.metadataRepository().saveYmgal(gameId, meta);
        else if (SOURCE_HIKARINAGI.equals(s)) delegate.metadataRepository().saveHikarinagi(gameId, meta);
        else if (SOURCE_BANGUMI.equals(s) || SOURCE_BANGUMI_MIRROR.equals(s)) delegate.metadataRepository().saveBangumi(gameId, meta);
        else delegate.metadataRepository().saveVndb(gameId, meta);
    }

    public boolean sameMetadataIdentity(VnMetadata a, VnMetadata b) {
        if (a == null || b == null) return false;
        String ai = a.id == null ? "" : a.id.trim();
        String bi = b.id == null ? "" : b.id.trim();
        if (!ai.isEmpty() && ai.equals(bi)) return true;
        String at = delegate.emptyText(a.chineseTitle, delegate.emptyText(a.originalTitle, a.romanTitle));
        String bt = delegate.emptyText(b.chineseTitle, delegate.emptyText(b.originalTitle, b.romanTitle));
        return !at.isEmpty() && at.equals(bt);
    }

    public void clearCurrentSourceMetadata(long gameId) {
        if (delegate.metadataRepository() == null || gameId <= 0) return;
        if (usingYmgal()) delegate.metadataRepository().clearYmgal(gameId);
        else if (usingHikarinagi()) delegate.metadataRepository().clearHikarinagi(gameId);
        else if (usingBangumi()) delegate.metadataRepository().clearBangumi(gameId);
        else delegate.metadataRepository().clearVndb(gameId);
        clearVisibleMetadataSource(gameId);
    }

    public VnMetadata currentSourceMetadata(long gameId) {
        if (delegate.metadataRepository() == null || gameId <= 0) return null;
        if (usingYmgal()) return delegate.metadataRepository().getYmgal(gameId);
        if (usingHikarinagi()) return delegate.metadataRepository().getHikarinagi(gameId);
        if (usingBangumi()) return delegate.metadataRepository().getBangumi(gameId);
        return delegate.metadataRepository().getVndb(gameId);
    }

    // ======================== fetch from sources ========================

    public void fetchVndbMetadata(Game game, boolean forceRefresh) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return;
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        VnMetadata cached = delegate.metadataRepository() == null || forceRefresh ? null : delegate.metadataRepository().getVndb(id);
        if (cached != null) {
            setVisibleMetadataSource(id, SOURCE_VNDB);
            applyVndbMetadata(cached, game);
            return;
        }
        delegate.setSideDescription("正在从 VNDB 获取资料…");
        VndbClient.searchCandidatesAsync(keyword, 5, new VndbClient.CandidatesCallback() {
            @Override public void onSuccess(List<VnMetadata> data) {
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (data == null || data.isEmpty()) {
                        applyVndbMetadata(null, game);
                    } else if (data.size() == 1 || isConfidentMatch(game.title, data.get(0))) {
                        saveCurrentSourceMetadata(id, data.get(0));
                        applyVndbMetadata(data.get(0), game);
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            }
            @Override public void onError(Exception error) {
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    delegate.setSideDescription(delegate.emptyText(game.description, "VNDB 暂未匹配到资料。"));
                });
            }
        });
    }

    public void fetchBangumiMetadata(Game game, boolean forceRefresh) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return;
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        VnMetadata cached = delegate.metadataRepository() == null || forceRefresh ? null : delegate.metadataRepository().getBangumi(id);
        if (cached != null) {
            setVisibleMetadataSource(id, metadataSource());
            applyVndbMetadata(cached, game);
            return;
        }
        String token = bangumiToken();
        if (token == null || token.trim().isEmpty()) {
            delegate.sideDetailOriginalTitle().setText("Bangumi 未配置 Token");
            delegate.setSideDescription("请在右上角 设置 -> 元数据源 中填写 Bangumi Access Token。\n\n提示：Bangumi 官方建议账号注册超过三个月后再申请和使用 Token。");
            return;
        }
        delegate.setSideDescription("正在从 Bangumi 获取资料…");
        AppExecutors.runOnIo(() -> {
            try {
                VnMetadata meta = BangumiClient.searchFirst(keyword, token, usingBangumiMirror());
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (meta == null) {
                        applyVndbMetadata(null, game);
                    } else {
                        saveCurrentSourceMetadata(id, meta);
                        applyVndbMetadata(meta, game);
                    }
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "Bangumi metadata failed", t);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    delegate.setSideDescription("Bangumi 获取失败。请检查 Token 是否正确，账号是否满足使用条件，或稍后重试。\n\n" + t.getMessage());
                });
            }
        });
    }

    public void fetchYmgalMetadata(Game game, boolean forceRefresh) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return;
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        VnMetadata cached = delegate.metadataRepository() == null || forceRefresh ? null : delegate.metadataRepository().getYmgal(id);
        if (cached != null) {
            setVisibleMetadataSource(id, SOURCE_YMGAL);
            applyVndbMetadata(cached, game);
            return;
        }
        delegate.setSideDescription("正在从月幕 Gal 获取资料…");
        AppExecutors.runOnIo(() -> {
            try {
                List<VnMetadata> data = YmgalClient.searchCandidates(keyword, 5);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (data == null || data.isEmpty()) {
                        applyVndbMetadata(null, game);
                    } else if (data.size() == 1 || isConfidentMatch(game.title, data.get(0))) {
                        fetchAndApplyYmgalDetail(game, data.get(0));
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "Ymgal metadata failed", t);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    delegate.setSideDescription("月幕 Gal 获取失败。请检查网络或稍后重试。\n\n" + t.getMessage());
                });
            }
        });
    }

    public void fetchAndApplyYmgalDetail(Game game, VnMetadata candidate) {
        if (game == null || candidate == null) return;
        final long id = game.id;
        delegate.setSideDescription("正在从月幕 Gal 获取详情…");
        AppExecutors.runOnIo(() -> {
            try {
                VnMetadata full = YmgalClient.getGame(candidate.id, candidate);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (delegate.metadataRepository() != null) saveCurrentSourceMetadata(id, full == null ? candidate : full);
                    applyVndbMetadata(full == null ? candidate : full, game);
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "Ymgal detail failed", t);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (delegate.metadataRepository() != null) saveCurrentSourceMetadata(id, candidate);
                    applyVndbMetadata(candidate, game);
                    delegate.showToast("月幕详情获取失败，已使用搜索结果", Toast.LENGTH_SHORT);
                });
            }
        });
    }

    // ======================== Hikarinagi ========================

    public void fetchHikarinagiMetadata(Game game, boolean forceRefresh) {
        if (game == null || game.title == null || game.title.trim().isEmpty()) return;
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        VnMetadata cached = delegate.metadataRepository() == null || forceRefresh ? null : delegate.metadataRepository().getHikarinagi(id);
        if (cached != null) {
            setVisibleMetadataSource(id, SOURCE_HIKARINAGI);
            applyVndbMetadata(cached, game);
            return;
        }
        delegate.setSideDescription("正在从 Hikarinagi 获取资料…");
        AppExecutors.runOnIo(() -> {
            try {
                List<VnMetadata> data = HikarinagiClient.searchCandidates(keyword, 5);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (data == null || data.isEmpty()) {
                        applyVndbMetadata(null, game);
                    } else if (data.size() == 1 || isConfidentMatch(game.title, data.get(0))) {
                        fetchAndApplyHikarinagiDetail(game, data.get(0));
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "Hikarinagi metadata failed", t);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    delegate.setSideDescription("Hikarinagi 获取失败。请检查网络或稍后重试。\n\n" + t.getMessage());
                });
            }
        });
    }

    public void fetchAndApplyHikarinagiDetail(Game game, VnMetadata candidate) {
        if (game == null || candidate == null) return;
        final long id = game.id;
        delegate.setSideDescription("正在从 Hikarinagi 获取详情…");
        AppExecutors.runOnIo(() -> {
            try {
                VnMetadata full = HikarinagiClient.getGalgame(candidate.id, candidate);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    if (delegate.metadataRepository() != null) saveCurrentSourceMetadata(id, full == null ? candidate : full);
                    applyVndbMetadata(full == null ? candidate : full, game);
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "Hikarinagi detail failed", t);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != id) return;
                    // 详情拉取失败时：已有完整缓存则保留（避免好数据被搜索摘要降级覆盖），否则才存摘要兜底
                    VnMetadata existing = delegate.metadataRepository() == null ? null : currentSourceMetadata(id);
                    if (existing != null && isMetadataComplete(existing)) {
                        applyVndbMetadata(existing, game);
                        delegate.showToast("Hikarinagi 详情获取失败，已保留原有资料", Toast.LENGTH_SHORT);
                    } else {
                        if (delegate.metadataRepository() != null) saveCurrentSourceMetadata(id, candidate);
                        applyVndbMetadata(candidate, game);
                        delegate.showToast("Hikarinagi 详情获取失败，已使用搜索结果", Toast.LENGTH_SHORT);
                    }
                });
            }
        });
    }

    // ======================== search & candidate dialogs ========================

    public void showCurrentSourceCustomSearchDialog(Game game) {
        if (usingYmgal()) showCustomYmgalSearchDialog(game);
        else if (usingHikarinagi()) showCustomHikarinagiSearchDialog(game);
        else if (usingBangumi()) showCustomBangumiSearchDialog(game);
        else showCustomVndbSearchDialog(game);
    }

    public void searchCurrentSourceWithKeyword(Game game, String keyword) {
        if (usingYmgal()) searchYmgalWithKeyword(game, keyword);
        else if (usingHikarinagi()) searchHikarinagiWithKeyword(game, keyword);
        else if (usingBangumi()) searchBangumiWithKeyword(game, keyword);
        else searchVndbWithKeyword(game, keyword);
    }

    public void showCustomVndbSearchDialog(Game game) {
        if (game == null) return;
        android.app.Activity ctx = delegate.activity();
        EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(delegate.emptyText(game.title, ""));
        input.setSelectAllOnFocus(true);
        input.setHint("输入 VNDB 搜索关键词或原名");
        input.setTextColor(ctx.getResources().getColor(R.color.yh_text));
        input.setHintTextColor(ctx.getResources().getColor(R.color.yh_text_muted));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(delegate.dp(12), 0, delegate.dp(12), 0);
        new AlertDialog.Builder(ctx)
                .setTitle("自定义搜索 VNDB")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    String keyword = input.getText() == null ? "" : input.getText().toString().trim();
                    if (keyword.isEmpty()) { delegate.showToast("请输入搜索关键词", Toast.LENGTH_SHORT); return; }
                    searchVndbWithKeyword(game, keyword);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void showCustomBangumiSearchDialog(Game game) {
        if (game == null) return;
        if (bangumiToken() == null || bangumiToken().trim().isEmpty()) {
            delegate.showToast("请先在设置里填写 Bangumi Token", Toast.LENGTH_SHORT);
            return;
        }
        android.app.Activity ctx = delegate.activity();
        EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(delegate.emptyText(game.title, ""));
        input.setSelectAllOnFocus(true);
        input.setHint("输入 Bangumi 搜索关键词");
        input.setTextColor(ctx.getResources().getColor(R.color.yh_text));
        input.setHintTextColor(ctx.getResources().getColor(R.color.yh_text_muted));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(delegate.dp(12), 0, delegate.dp(12), 0);
        new AlertDialog.Builder(ctx)
                .setTitle("自定义搜索 Bangumi")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    String keyword = input.getText() == null ? "" : input.getText().toString().trim();
                    if (keyword.isEmpty()) { delegate.showToast("请输入搜索关键词", Toast.LENGTH_SHORT); return; }
                    searchBangumiWithKeyword(game, keyword);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void showCustomYmgalSearchDialog(Game game) {
        if (game == null) return;
        android.app.Activity ctx = delegate.activity();
        EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(delegate.emptyText(game.title, ""));
        input.setSelectAllOnFocus(true);
        input.setHint("输入月幕 Gal 搜索关键词");
        input.setTextColor(ctx.getResources().getColor(R.color.yh_text));
        input.setHintTextColor(ctx.getResources().getColor(R.color.yh_text_muted));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(delegate.dp(12), 0, delegate.dp(12), 0);
        new AlertDialog.Builder(ctx)
                .setTitle("自定义搜索月幕 Gal")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    String keyword = input.getText() == null ? "" : input.getText().toString().trim();
                    if (keyword.isEmpty()) { delegate.showToast("请输入搜索关键词", Toast.LENGTH_SHORT); return; }
                    searchYmgalWithKeyword(game, keyword);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void searchBangumiWithKeyword(Game game, String keyword) {
        if (game == null || keyword == null || keyword.trim().isEmpty()) return;
        String token = bangumiToken();
        delegate.setSideDescription("正在按自定义关键词搜索 Bangumi…");
        AppExecutors.runOnIo(() -> {
            try {
                List<VnMetadata> data = BangumiClient.searchCandidates(keyword, token, 8, usingBangumiMirror());
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != game.id) return;
                    if (data == null || data.isEmpty()) {
                        delegate.showToast("没有匹配到 Bangumi 结果", Toast.LENGTH_SHORT);
                        delegate.setSideDescription(delegate.emptyText(game.description, "Bangumi 暂未匹配到资料。"));
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            } catch (Throwable t) {
                if (!isActivityAlive()) return;
                delegate.runOnUiThread(() -> delegate.showToast("Bangumi 搜索失败：" + t.getMessage(), Toast.LENGTH_SHORT));
            }
        });
    }

    public void searchYmgalWithKeyword(Game game, String keyword) {
        if (game == null || keyword == null || keyword.trim().isEmpty()) return;
        delegate.setSideDescription("正在按自定义关键词搜索月幕 Gal…");
        AppExecutors.runOnIo(() -> {
            try {
                List<VnMetadata> data = YmgalClient.searchCandidates(keyword, 8);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != game.id) return;
                    if (data == null || data.isEmpty()) {
                        delegate.showToast("没有匹配到月幕 Gal 结果", Toast.LENGTH_SHORT);
                        delegate.setSideDescription(delegate.emptyText(game.description, "月幕 Gal 暂未匹配到资料。"));
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            } catch (Throwable t) {
                if (!isActivityAlive()) return;
                delegate.runOnUiThread(() -> delegate.showToast("月幕 Gal 搜索失败：" + t.getMessage(), Toast.LENGTH_SHORT));
            }
        });
    }

    public void showCustomHikarinagiSearchDialog(Game game) {
        if (game == null) return;
        android.app.Activity ctx = delegate.activity();
        EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(delegate.emptyText(game.title, ""));
        input.setSelectAllOnFocus(true);
        input.setHint("输入 Hikarinagi 搜索关键词");
        input.setTextColor(ctx.getResources().getColor(R.color.yh_text));
        input.setHintTextColor(ctx.getResources().getColor(R.color.yh_text_muted));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(delegate.dp(12), 0, delegate.dp(12), 0);
        new AlertDialog.Builder(ctx)
                .setTitle("自定义搜索 Hikarinagi")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    String keyword = input.getText() == null ? "" : input.getText().toString().trim();
                    if (keyword.isEmpty()) { delegate.showToast("请输入搜索关键词", Toast.LENGTH_SHORT); return; }
                    searchHikarinagiWithKeyword(game, keyword);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void searchHikarinagiWithKeyword(Game game, String keyword) {
        if (game == null || keyword == null || keyword.trim().isEmpty()) return;
        delegate.setSideDescription("正在按自定义关键词搜索 Hikarinagi…");
        AppExecutors.runOnIo(() -> {
            try {
                List<VnMetadata> data = HikarinagiClient.searchCandidates(keyword, 8);
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != game.id) return;
                    if (data == null || data.isEmpty()) {
                        delegate.showToast("没有匹配到 Hikarinagi 结果", Toast.LENGTH_SHORT);
                        delegate.setSideDescription(delegate.emptyText(game.description, "Hikarinagi 暂未匹配到资料。"));
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            } catch (Throwable t) {
                if (!isActivityAlive()) return;
                delegate.runOnUiThread(() -> delegate.showToast("Hikarinagi 搜索失败：" + t.getMessage(), Toast.LENGTH_SHORT));
            }
        });
    }

    public void searchVndbWithKeyword(Game game, String keyword) {
        if (game == null || keyword == null || keyword.trim().isEmpty()) return;
        delegate.setSideDescription("正在按自定义关键词搜索 VNDB…");
        VndbClient.searchCandidatesAsync(keyword, 8, new VndbClient.CandidatesCallback() {
            @Override public void onSuccess(List<VnMetadata> data) {
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> {
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != game.id) return;
                    if (data == null || data.isEmpty()) {
                        delegate.showToast("没有匹配到 VNDB 结果", Toast.LENGTH_SHORT);
                        delegate.setSideDescription(delegate.emptyText(game.description, "VNDB 暂未匹配到资料。"));
                    } else {
                        showVndbCandidateDialog(game, data);
                    }
                });
            }
            @Override public void onError(Exception error) {
                if (!isActivityAlive()) return;
                delegate.runOnUiThread(() -> delegate.showToast("VNDB 搜索失败", Toast.LENGTH_SHORT));
            }
        });
    }

    // ======================== candidate dialog ========================

    public void showVndbCandidateDialog(Game game, List<VnMetadata> list) {
        if (game == null || list == null || list.isEmpty()) return;
        android.app.Activity ctx = delegate.activity();
        androidx.recyclerview.widget.RecyclerView rv = new androidx.recyclerview.widget.RecyclerView(ctx);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(ctx));
        rv.setPadding(delegate.dp(6), delegate.dp(6), delegate.dp(6), delegate.dp(6));
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
                // Dynamic theme text colors
                com.yuki.yukihub.ui.DynamicTheme dt = com.yuki.yukihub.ui.DynamicTheme.getInstance();
                boolean themed = dt.isEnabled() && dt.getColors() != null;
                TextView tvTitle = itemView.findViewById(R.id.tvCandidateTitle);
                TextView tvOriginal = itemView.findViewById(R.id.tvCandidateOriginal);
                TextView tvInfo = itemView.findViewById(R.id.tvCandidateInfo);
                if (themed) {
                    tvTitle.setTextColor(0xFFF0F4FA);
                    tvOriginal.setTextColor(0xFFB0B8C8);
                    tvInfo.setTextColor(0xFFE8EDF5);
                }
                if (m == null) {
                    String sourceLabel = metadataSourceLabel();
                    ((TextView) itemView.findViewById(R.id.tvCandidateTitle)).setText("不匹配 / 暂不使用" + sourceLabel);
                    ((TextView) itemView.findViewById(R.id.tvCandidateOriginal)).setText("保留当前本地资料");
                    ((TextView) itemView.findViewById(R.id.tvCandidateInfo)).setText("关闭弹窗，不绑定" + sourceLabel);
                    ImageView cover = itemView.findViewById(R.id.ivCandidateCover);
                    delegate.loadRemoteImage("", cover, "cand_empty_" + position);
                } else {
                    ((TextView) itemView.findViewById(R.id.tvCandidateTitle)).setText(delegate.emptyText(m.chineseTitle, delegate.emptyText(m.romanTitle, "未命名")));
                    ((TextView) itemView.findViewById(R.id.tvCandidateOriginal)).setText(delegate.emptyText(m.originalTitle, m.id));
                    ((TextView) itemView.findViewById(R.id.tvCandidateInfo)).setText(delegate.emptyText(m.developer, metadataSourceLabel() + " 候选"));
                    ImageView cover = itemView.findViewById(R.id.ivCandidateCover);
                    cover.setImageDrawable(null);
                    if (m.coverUrl != null && !m.coverUrl.isEmpty()) delegate.loadRemoteImage(m.coverUrl, cover, "cand_" + m.id);
                }
                itemView.setOnClickListener(v -> {
                    delegate.playUiSound(1); // UI_SOUND_CONFIRM
                    if (delegate.selectedGame() == null || delegate.selectedGame().id != game.id) return;
                    if (position >= 0 && position < list.size()) {
                        VnMetadata chosen = list.get(position);
                        if (dialogRef[0] != null) dialogRef[0].dismiss();
                        if (usingYmgal()) {
                            fetchAndApplyYmgalDetail(game, chosen);
                        } else if (usingHikarinagi()) {
                            // Hikarinagi 搜索摘要缺少简介/标签/截图，必须再拉详情
                            fetchAndApplyHikarinagiDetail(game, chosen);
                        } else {
                            saveCurrentSourceMetadata(game.id, chosen);
                            applyVndbMetadata(chosen, game);
                        }
                        return;
                    } else {
                        clearCurrentSourceMetadata(game.id);
                        applyVndbMetadata(null, game);
                        delegate.setSideDescription(delegate.emptyText(game.description, "已跳过" + metadataSourceLabel() + "匹配，使用本地资料。"));
                    }
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                });
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("选择" + metadataSourceLabel() + "匹配结果")
                .setView(rv)
                .setNegativeButton("取消", null)
                .show();
        dialogRef[0] = dialog;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.70f), (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.72f));
        }
    }

    // ======================== apply metadata ========================

    public void applyVndbMetadata(VnMetadata meta, Game game) {
        delegate.setCurrentSideMetadata(meta);
        long gameId = game == null ? -1 : game.id;
        delegate.setSideShowingTranslatedDescription(gameId > 0 && isTranslatedStateFor(gameId) && meta != null && meta.translatedDescription != null && !meta.translatedDescription.trim().isEmpty());
        if (meta == null) {
            updateSideMetadataSourceBadge("");
            delegate.updateTranslateButtonState();
            delegate.sideDetailTitle().setText(delegate.emptyText(game == null ? "" : game.title, "未命名游戏"));
            delegate.sideDetailOriginalTitle().setText("未绑定" + metadataSourceLabel());
            delegate.loadRemoteImage("", delegate.sideDetailCover(), "cover_empty_" + gameId);
            delegate.loadRemoteImage("", delegate.sideScreenshot1(), "shot1_empty_" + gameId);
            delegate.loadRemoteImage("", delegate.sideScreenshot2(), "shot2_empty_" + gameId);
            String localCover = game == null ? "" : delegate.safeCoverUri(game);
            if (localCover != null && !localCover.isEmpty()) {
                try {
                    delegate.sideDetailCover().setImageURI(Uri.parse(localCover));
                    delegate.sideDetailCover().setVisibility(View.VISIBLE);
                    delegate.sideDetailPlaceholder().setVisibility(View.GONE);
                } catch (Throwable ignored) { }
            } else {
                delegate.sideDetailCover().setImageDrawable(null);
                delegate.sideDetailCover().setVisibility(View.GONE);
                delegate.sideDetailPlaceholder().setVisibility(View.VISIBLE);
                if (delegate.sideDetailPlaceholder() instanceof TextView) ((TextView) delegate.sideDetailPlaceholder()).setText(delegate.initials(game == null ? "" : game.title));
            }
            delegate.setSideDescription(delegate.emptyText(game == null ? "" : game.description, metadataSourceLabel() + " 暂未匹配到资料。"));
            return;
        }
        String visibleSourceLabel = metadataSourceLabelForVisibleMetadata(gameId, meta);
        updateSideMetadataSourceBadge(visibleSourceLabel);
        delegate.sideDetailTitle().setText(delegate.emptyText(meta.chineseTitle, delegate.emptyText(game.title, "未命名游戏")));
        delegate.sideDetailOriginalTitle().setText(delegate.emptyText(meta.originalTitle, meta.romanTitle));
        delegate.updateTranslateButtonState();
        delegate.setSideDescription(delegate.sideShowingTranslatedDescription() ? meta.translatedDescription : delegate.emptyText(meta.description, "暂无" + delegate.emptyText(visibleSourceLabel, metadataSourceLabel()) + "简介。"));
        delegate.sideDetailDate().setText("发布日期：" + delegate.emptyText(meta.released, "-"));
        delegate.sideDetailDeveloper().setText("开发商：" + delegate.emptyText(meta.developer, "-"));
        if (delegate.sideDetailPath() != null) delegate.sideDetailPath().setText("路径：" + delegate.displayPath(game.rootUri));
        delegate.sideDetailRating().setText(delegate.emptyText(meta.ratingText, "评分：-/10"));
        if (delegate.sideDetailLength() != null) delegate.sideDetailLength().setText(delegate.emptyText(meta.lengthText, "游玩时长：-"));
        delegate.renderTagChips(delegate.emptyText(meta.tagsText, "-"));
        if (meta.coverUrl != null && !meta.coverUrl.isEmpty()) {
            delegate.sideDetailCover().setVisibility(View.VISIBLE);
            delegate.sideDetailPlaceholder().setVisibility(View.GONE);
            delegate.sideDetailCover().setTag(game);
            delegate.loadRemoteImage(meta.coverUrl, delegate.sideDetailCover(), "cover_" + delegate.emptyText(meta.id, String.valueOf(game.id)));
        } else {
            delegate.sideDetailCover().setTag(null);
            delegate.loadRemoteImage("", delegate.sideDetailCover(), "cover_empty_" + gameId);
            delegate.sideDetailCover().setVisibility(View.GONE);
            delegate.sideDetailPlaceholder().setVisibility(View.VISIBLE);
        }
        if (meta.screenshotUrls.size() > 0) delegate.loadRemoteImage(meta.screenshotUrls.get(0), delegate.sideScreenshot1(), "shot1_" + delegate.emptyText(meta.id, String.valueOf(game.id)));
        else delegate.loadRemoteImage("", delegate.sideScreenshot1(), "shot1_empty_" + gameId);
        if (meta.screenshotUrls.size() > 1) delegate.loadRemoteImage(meta.screenshotUrls.get(1), delegate.sideScreenshot2(), "shot2_" + delegate.emptyText(meta.id, String.valueOf(game.id)));
        else delegate.loadRemoteImage("", delegate.sideScreenshot2(), "shot2_empty_" + gameId);
    }

    // ======================== sync to game card ========================

    public void syncCurrentMetadataToGameCard(Game game) {
        if (game == null) return;
        String label = metadataSourceLabel();
        VnMetadata meta = currentSourceMetadata(game.id);
        if (meta == null) {
            delegate.showToast("请先匹配" + label + "资料", Toast.LENGTH_SHORT);
            return;
        }

        android.app.Activity ctx = delegate.activity();
        TextView progressText = new TextView(ctx);
        progressText.setText("正在准备同步" + label + "资料…");
        progressText.setTextColor(ctx.getResources().getColor(R.color.yh_text));
        progressText.setTextSize(12);
        progressText.setLineSpacing(delegate.dp(2), 1.0f);
        progressText.setPadding(delegate.dp(16), delegate.dp(12), delegate.dp(16), delegate.dp(12));
        progressText.setBackgroundResource(R.drawable.bg_dialog);
        AlertDialog progressDialog = new AlertDialog.Builder(ctx)
                .setTitle("同步游戏资料")
                .setView(progressText)
                .setCancelable(false)
                .create();
        progressDialog.show();
        delegate.styleAlertDialogDark(progressDialog);

        AppExecutors.runOnIo(() -> {
            String localCover = null;
            boolean coverAttempted = false;
            boolean coverFailed = false;
            try {
                if (meta.coverUrl != null && !meta.coverUrl.trim().isEmpty()) {
                    coverAttempted = true;
                    if (isActivityAlive()) {
                        delegate.runOnUiThread(() -> progressText.setText("正在下载并缓存封面…\n这一步取决于图片站点速度，可能需要几秒。"));
                    }
                    localCover = cacheRemoteImageSync(meta.coverUrl, "card_cover_" + delegate.emptyText(meta.id, String.valueOf(game.id)));
                    coverFailed = localCover == null || localCover.isEmpty();
                }

                final String cover = localCover;
                final boolean finalCoverAttempted = coverAttempted;
                final boolean finalCoverFailed = coverFailed;
                if (!isActivityAlive()) return;

                delegate.runOnUiThread(() -> progressText.setText("正在写入游戏卡片…"));

                delegate.runOnUiThread(() -> {
                    try {
                        String newTitle = delegate.emptyText(meta.chineseTitle, delegate.emptyText(meta.originalTitle, meta.romanTitle));
                        if (!newTitle.isEmpty()) game.title = newTitle;
                        if (meta.originalTitle != null && !meta.originalTitle.isEmpty()) game.originalTitle = meta.originalTitle;
                        if (meta.description != null && !meta.description.isEmpty()) game.description = meta.description;
                        if (meta.tagsText != null && !meta.tagsText.isEmpty()) game.tags = meta.tagsText;
                        if (cover != null && !cover.isEmpty()) {
                            game.coverUri = cover;
                            game.coverPersistUri = cover;
                            game.coverSourceType = 1;
                        }
                        delegate.gameRepository().update(game);
                        delegate.loadGames();
                        delegate.updateSideDetail(game);
                        try { progressDialog.dismiss(); } catch (Throwable ignored) { }
                        if (finalCoverAttempted && finalCoverFailed) {
                            delegate.showToast("资料已同步，但封面下载失败。可稍后重试或手动设置封面。", Toast.LENGTH_LONG);
                        } else {
                            delegate.showToast("已同步" + label + "资料到游戏卡片", Toast.LENGTH_SHORT);
                        }
                    } catch (Throwable t) {
                        try { progressDialog.dismiss(); } catch (Throwable ignored) { }
                        delegate.showToast("同步失败：" + (t.getMessage() == null ? "未知错误" : t.getMessage()), Toast.LENGTH_LONG);
                    }
                });
            } catch (Throwable t) {
                if (!isActivityAlive()) return;
                delegate.runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Throwable ignored) { }
                    delegate.showToast("同步失败：" + (t.getMessage() == null ? "未知错误" : t.getMessage()), Toast.LENGTH_LONG);
                });
            }
        });
    }

    // ======================== translation state ========================

    public boolean isTranslatedStateFor(long gameId) {
        if (delegate.prefs() == null || gameId <= 0) return false;
        return delegate.prefs().getBoolean(KEY_SIDE_TRANSLATED_PREFIX + gameId, false);
    }

    public void setTranslatedStateFor(long gameId, boolean translated) {
        if (delegate.prefs() == null || gameId <= 0) return;
        delegate.prefs().edit().putBoolean(KEY_SIDE_TRANSLATED_PREFIX + gameId, translated).apply();
    }

    // ======================== helpers ========================

    public String buildMetadataSearchKeyword(String title) {
        if (title == null) return "";
        String cleaned = title.replaceAll("[【\\[][^】\\]]*[】\\]]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    public boolean isConfidentMatch(String localTitle, VnMetadata meta) {
        if (meta == null || localTitle == null) return false;
        String a = buildMetadataSearchKeyword(localTitle).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5ぁ-んァ-ン一-龯]", "");
        if (a.isEmpty()) return false;
        // 对每个标题单独判断包含关系，避免拼接三个标题导致带前缀的本地名（如 "ty远海咏叹调"）匹配失败
        String[] titles = {meta.chineseTitle, meta.originalTitle, meta.romanTitle};
        for (String t : titles) {
            if (t == null || t.trim().isEmpty()) continue;
            String b = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5ぁ-んァ-ン一-龯]", "");
            if (!b.isEmpty() && (b.contains(a) || a.contains(b))) return true;
        }
        return false;
    }

    public String safeCacheName(String input) {
        if (input == null || input.trim().isEmpty()) return "cache";
        String raw = input.trim();
        String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        // URL 可能很长，直接作为文件名会触发 ENAMETOOLONG，导致封面缓存静默失败。
        // 保留前缀可读性，同时追加 hash 保证稳定且避免冲突。
        String hash = Integer.toHexString(raw.hashCode());
        if (cleaned.length() > 72) cleaned = cleaned.substring(0, 72);
        return cleaned + "_" + hash;
    }

    public boolean downloadImageAllowVndbWarningPage(String imageUrl, File cacheFile, int depth) {
        if (imageUrl == null || imageUrl.trim().isEmpty() || cacheFile == null || depth > 2) return false;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(imageUrl).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 YukiHub/1.0");
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

    public String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0, len;
        while ((len = is.read(buf)) != -1 && total < 256 * 1024) {
            bos.write(buf, 0, len);
            total += len;
        }
        return bos.toString("UTF-8");
    }

    public String extractImageUrlFromHtml(String html, String baseUrl) {
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

    public String cacheRemoteImageSync(String url, String prefix) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            File cacheDir = delegate.persistentRemoteCoverDir();
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

    // ======================== bulk refresh all metadata ========================

    /** 批量重扫相邻两游戏间隔（秒）的 SharedPreferences key，默认 2.5s，范围 0.5~15s。 */
    public static final String KEY_BULK_REFRESH_INTERVAL_SEC = "bulk_refresh_interval_sec";
    private static final float DEFAULT_BULK_REFRESH_INTERVAL_SEC = 2.5f;

    private volatile boolean bulkRefreshRunning = false;
    private volatile boolean bulkRefreshCancelled = false;

    /** 从设置读取批量重扫间隔（毫秒），带范围钳制。 */
    private long bulkRefreshIntervalMs() {
        try {
            SharedPreferences sp = delegate.prefs();
            float sec = sp == null ? DEFAULT_BULK_REFRESH_INTERVAL_SEC
                    : sp.getFloat(KEY_BULK_REFRESH_INTERVAL_SEC, DEFAULT_BULK_REFRESH_INTERVAL_SEC);
            if (sec < 0.5f) sec = 0.5f;
            if (sec > 15f) sec = 15f;
            return (long) (sec * 1000L);
        } catch (Throwable t) {
            return (long) (DEFAULT_BULK_REFRESH_INTERVAL_SEC * 1000L);
        }
    }

    public interface BulkRefreshCallback {
        /** done = 已处理的游戏数（当前正在处理第 done+1 个）；total = 本次实际要重扫的总数。已在 UI 线程回调。 */
        void onProgress(int done, int total, String gameTitle, String status);

        /** 全部结束（含取消）。已在 UI 线程回调。 */
        void onFinished(BulkRefreshResult result);
    }

    public static class BulkRefreshResult {
        public int totalGames = 0;   // 库中有效游戏总数
        public int attempted = 0;    // 实际尝试重扫数
        public int skipped = 0;      // 跳过数（仅重扫不完整模式下 = 资料已完整的游戏数）
        public int success = 0;
        public int failed = 0;
        public int needConfirm = 0;  // 多候选未自动命中，需人工确认
        public boolean cancelled = false;
        public final List<String> details = new ArrayList<>();
    }

    public boolean isBulkRefreshRunning() {
        return bulkRefreshRunning;
    }

    public void cancelBulkRefresh() {
        bulkRefreshCancelled = true;
    }

    /**
     * 一键重扫全部游戏的资料（使用当前选择的资料源）。
     *
     * @param onlyIncomplete true 时仅重扫当前源下缓存缺失/不完整的游戏；false 全量重扫。
     */
    public void refreshAllMetadata(boolean onlyIncomplete, BulkRefreshCallback callback) {
        if (bulkRefreshRunning || callback == null) return;
        bulkRefreshRunning = true;
        bulkRefreshCancelled = false;

        AppExecutors.runOnIo(() -> {
            try {
                List<Game> games = new ArrayList<>();
                for (Game g : delegate.allGames()) {
                    if (g != null && g.id > 0 && g.title != null && !g.title.trim().isEmpty()) games.add(g);
                }
                List<Game> targets = new ArrayList<>();
                if (onlyIncomplete) {
                    for (Game g : games) {
                        VnMetadata cur = currentSourceMetadata(g.id);
                        if (cur == null || !isMetadataComplete(cur)) targets.add(g);
                    }
                } else {
                    targets.addAll(games);
                }
                final int total = targets.size();
                BulkRefreshResult result = new BulkRefreshResult();
                result.totalGames = games.size();
                result.skipped = games.size() - targets.size();
                int done = 0;
                for (Game g : targets) {
                    if (bulkRefreshCancelled) {
                        result.cancelled = true;
                        break;
                    }
                    final String title = g.title;
                    final int current = done;
                    if (isActivityAlive()) {
                        delegate.runOnUiThread(() -> callback.onProgress(current, total, title, ""));
                    }
                    result.attempted++;
                    try {
                        int outcome = refreshSingleMetadataSync(g);
                        if (outcome == 0) {
                            result.success++;
                            result.details.add("✓ " + title);
                        } else if (outcome == 1) {
                            result.needConfirm++;
                            result.details.add("⚠ " + title + "（多候选未自动命中，可手动重扫）");
                        } else {
                            result.failed++;
                            result.details.add("✗ " + title + "（未找到或获取失败）");
                        }
                    } catch (Throwable t) {
                        result.failed++;
                        String em = t.getMessage();
                        result.details.add("✗ " + title + "（" + (em == null || em.trim().isEmpty() ? "未知错误" : em) + "）");
                    }
                    done++;
                    if (!bulkRefreshCancelled && done < total) {
                        try { Thread.sleep(bulkRefreshIntervalMs()); } catch (InterruptedException ignored) { }
                    }
                }
                final BulkRefreshResult fResult = result;
                if (isActivityAlive()) {
                    delegate.runOnUiThread(() -> callback.onFinished(fResult));
                }
            } finally {
                bulkRefreshRunning = false;
            }
        });
    }

    /**
     * 同步重扫单个游戏资料（当前源，不弹候选框，适合批量场景）。
     *
     * @return 0 = 成功；1 = 多候选未自动命中（保留原资料）；2 = 未找到或获取失败
     */
    private int refreshSingleMetadataSync(Game game) throws Exception {
        final long id = game.id;
        final String keyword = buildMetadataSearchKeyword(game.title);
        if (usingYmgal()) {
            List<VnMetadata> data = YmgalClient.searchCandidates(keyword, 5);
            if (data == null || data.isEmpty()) return 2;
            VnMetadata chosen = data.get(0);
            if (data.size() > 1 && !isConfidentMatch(game.title, chosen)) return 1;
            VnMetadata full = YmgalClient.getGame(chosen.id, chosen);
            saveCurrentSourceMetadata(id, full == null ? chosen : full);
            return 0;
        }
        if (usingHikarinagi()) {
            List<VnMetadata> data = HikarinagiClient.searchCandidates(keyword, 5);
            if (data == null || data.isEmpty()) return 2;
            VnMetadata chosen = data.get(0);
            if (data.size() > 1 && !isConfidentMatch(game.title, chosen)) return 1;
            VnMetadata full = HikarinagiClient.getGalgame(chosen.id, chosen);
            saveCurrentSourceMetadata(id, full == null ? chosen : full);
            return 0;
        }
        if (usingBangumi()) {
            String token = bangumiToken();
            if (token == null || token.trim().isEmpty()) throw new Exception("Bangumi未配置 Token");
            VnMetadata meta = BangumiClient.searchFirst(keyword, token, usingBangumiMirror());
            if (meta == null) return 2;
            saveCurrentSourceMetadata(id, meta);
            return 0;
        }
        List<VnMetadata> data = VndbClient.searchCandidates(keyword, 5);
        if (data == null || data.isEmpty()) return 2;
        VnMetadata chosen = data.get(0);
        if (data.size() > 1 && !isConfidentMatch(game.title, chosen)) return 1;
        saveCurrentSourceMetadata(id, chosen);
        return 0;
    }

    /** 判断一份资料是否"完整"（资料卡片核心要素齐全：匹配ID + 标题 + 封面 + 简介 + 至少一项元数据）。 */
    public boolean isMetadataComplete(VnMetadata m) {
        if (m == null) return false;
        if (m.id == null || m.id.trim().isEmpty()) return false;
        boolean hasTitle = !delegate.emptyText(m.chineseTitle, delegate.emptyText(m.originalTitle, m.romanTitle)).isEmpty();
        boolean hasCover = m.coverUrl != null && !m.coverUrl.trim().isEmpty();
        boolean hasDesc = m.description != null && !m.description.trim().isEmpty();
        boolean hasMeta = !delegate.emptyText(m.developer, delegate.emptyText(m.tagsText, m.released)).isEmpty();
        return hasTitle && hasCover && hasDesc && hasMeta;
    }

    /** 当前资料源下，某游戏缓存资料是否完整（无缓存也视为不完整）。 */
    public boolean isCurrentSourceMetadataComplete(long gameId) {
        if (delegate.metadataRepository() == null || gameId <= 0) return false;
        VnMetadata cur = currentSourceMetadata(gameId);
        return cur != null && isMetadataComplete(cur);
    }
}
