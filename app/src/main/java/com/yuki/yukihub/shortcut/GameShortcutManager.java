package com.yuki.yukihub.shortcut;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 桌面游戏快捷方式。
 *
 * 从桌面一键启动指定游戏，走的是 MainActivity 既有的
 * home_target=launch_game + home_game_id 入口（与 HomeActivity 点卡片同一条路），
 * 因此游玩计时、Presence 上报等全部自动生效，不需要另开启动通道。
 *
 * 【为什么不用 ShortcutManagerCompat】
 * 项目没有引入 androidx.core 的 shortcut 依赖，而 minSdk 26 已经原生支持
 * requestPinShortcut（API 26 引入），直接用平台 API 即可，不增加依赖。
 *
 * 【关于已固定到桌面的快捷方式】
 * Android 不允许应用删除用户已固定（pinned）的快捷方式，只能 disable。
 * 因此游戏被删除时只能禁用并给出提示，图标本身需要用户长按自行移除。
 */
public final class GameShortcutManager {

    private static final String TAG = "YukiShortcut";

    /** 快捷方式 id 前缀，后接游戏本地 id。 */
    private static final String ID_PREFIX = "game_";

    /** 与 MainActivity 中的常量保持一致，用于构造启动 Intent。 */
    private static final String EXTRA_HOME_TARGET = "home_target";
    private static final String EXTRA_HOME_GAME_ID = "home_game_id";
    private static final String TARGET_LAUNCH_GAME = "launch_game";

    /** 图标画布边长（px）。桌面图标通常按 48dp 显示，192px 足够各种 DPI 使用。 */
    private static final int ICON_SIZE = 192;

    /** 解码封面时的目标边长：只用于生成图标，不需要原图分辨率。 */
    private static final int COVER_DECODE_TARGET = 256;

    private GameShortcutManager() {
    }

    public static boolean isSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        ShortcutManager sm = shortcutManager(context);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    public static String shortcutId(long gameId) {
        return ID_PREFIX + gameId;
    }

    /**
     * 请求把游戏固定到桌面（一步式，图标在当前线程生成）。
     *
     * 图标生成涉及封面解码与取色，主线程调用可能造成短暂卡顿。
     * 需要避免卡顿时改用 {@link #prepareIcon} + {@link #requestPinWithIcon}：
     * 前者可在 IO 线程调用，后者必须在主线程调用（会弹系统确认框）。
     *
     * @param activityClass 承载启动逻辑的 Activity（传 MainActivity.class）
     * @param coverUri      游戏封面 uri，可为空；为空或解码失败时用标题首字生成图标
     * @return true 表示请求已提交给系统（系统会弹确认框），false 表示当前启动器不支持
     */
    @SuppressLint("NewApi")
    public static boolean requestPin(Context context, long gameId, String title,
                                     String coverUri, Class<?> activityClass) {
        if (context == null || gameId <= 0) return false;
        if (!isSupported(context)) return false;
        return requestPinWithIcon(context, gameId, title,
                prepareIcon(context, title, coverUri), activityClass);
    }

    /**
     * 生成快捷方式图标。可在 IO 线程调用（不触碰 UI）。
     * 返回值可能为 null（极端情况下位图分配失败），调用方需容错。
     */
    @SuppressLint("NewApi")
    public static Icon prepareIcon(Context context, String title, String coverUri) {
        if (context == null) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            return buildIcon(context, coverUri, safeLabel(title));
        } catch (Throwable t) {
            Log.w(TAG, "prepareIcon failed", t);
            return null;
        }
    }

    /**
     * 用已生成好的图标发起固定请求。必须在主线程调用（系统会弹确认框）。
     *
     * @param icon 可为 null，此时由系统使用应用默认图标
     */
    @SuppressLint("NewApi")
    public static boolean requestPinWithIcon(Context context, long gameId, String title,
                                            Icon icon, Class<?> activityClass) {
        if (context == null || gameId <= 0) return false;
        if (!isSupported(context)) return false;
        ShortcutManager sm = shortcutManager(context);
        if (sm == null) return false;
        try {
            String label = safeLabel(title);
            ShortcutInfo.Builder builder = new ShortcutInfo.Builder(context, shortcutId(gameId))
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIntent(buildLaunchIntent(context, gameId, activityClass));
            if (icon != null) builder.setIcon(icon);
            return sm.requestPinShortcut(builder.build(), null);
        } catch (Throwable t) {
            Log.w(TAG, "requestPin failed for game " + gameId, t);
            return false;
        }
    }

    /**
     * 游戏信息变化后刷新已存在的快捷方式（标题/封面）。
     * 只更新已固定的项，不会新建；游戏没有快捷方式时静默跳过。
     */
    @SuppressLint("NewApi")
    public static void updateIfExists(Context context, long gameId, String title,
                                      String coverUri, Class<?> activityClass) {
        if (context == null || gameId <= 0) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        ShortcutManager sm = shortcutManager(context);
        if (sm == null) return;
        try {
            if (!hasPinned(sm, shortcutId(gameId))) return;
            String label = safeLabel(title);
            ShortcutInfo info = new ShortcutInfo.Builder(context, shortcutId(gameId))
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(buildIcon(context, coverUri, label))
                    .setIntent(buildLaunchIntent(context, gameId, activityClass))
                    .build();
            sm.updateShortcuts(java.util.Collections.singletonList(info));
        } catch (Throwable t) {
            Log.w(TAG, "updateIfExists failed for game " + gameId, t);
        }
    }

    /**
     * 游戏被删除时禁用对应快捷方式。
     *
     * 系统不允许应用删除用户已固定的快捷方式，只能禁用：
     * 图标会变灰，点击时弹出这里给的提示文案。用户需长按自行移除图标。
     */
    @SuppressLint("NewApi")
    public static void disableForGame(Context context, long gameId) {
        if (context == null || gameId <= 0) return;
        disableForGames(context, java.util.Collections.singletonList(gameId));
    }

    /** 批量禁用（配合批量删除 / 清空游戏库）。 */
    @SuppressLint("NewApi")
    public static void disableForGames(Context context, java.util.Collection<Long> gameIds) {
        if (context == null || gameIds == null || gameIds.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        ShortcutManager sm = shortcutManager(context);
        if (sm == null) return;
        try {
            List<String> ids = new java.util.ArrayList<>();
            for (Long id : gameIds) {
                if (id == null || id <= 0) continue;
                String sid = shortcutId(id);
                if (hasPinned(sm, sid)) ids.add(sid);
            }
            if (ids.isEmpty()) return;
            sm.disableShortcuts(ids, "游戏已从 YukiHub 移除，请长按删除此图标");
        } catch (Throwable t) {
            Log.w(TAG, "disableForGames failed", t);
        }
    }

    /**
     * 禁用所有已固定的游戏快捷方式（用于清空游戏库）。
     * 只处理本类创建的（id 以 game_ 开头），不碰其它来源的快捷方式。
     */
    @SuppressLint("NewApi")
    public static void disableAll(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        ShortcutManager sm = shortcutManager(context);
        if (sm == null) return;
        try {
            List<String> ids = new java.util.ArrayList<>();
            for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                if (si == null || si.getId() == null) continue;
                if (si.getId().startsWith(ID_PREFIX)) ids.add(si.getId());
            }
            if (ids.isEmpty()) return;
            sm.disableShortcuts(ids, "游戏已从 YukiHub 移除，请长按删除此图标");
        } catch (Throwable t) {
            Log.w(TAG, "disableAll failed", t);
        }
    }

    /** 该游戏是否已经有固定到桌面的快捷方式。 */
    @SuppressLint("NewApi")
    public static boolean isPinned(Context context, long gameId) {
        if (context == null || gameId <= 0) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        ShortcutManager sm = shortcutManager(context);
        if (sm == null) return false;
        try {
            return hasPinned(sm, shortcutId(gameId));
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 内部实现 ====================

    private static ShortcutManager shortcutManager(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            return context.getSystemService(ShortcutManager.class);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressLint("NewApi")
    private static boolean hasPinned(ShortcutManager sm, String id) {
        for (ShortcutInfo si : sm.getPinnedShortcuts()) {
            if (si != null && id.equals(si.getId())) return true;
        }
        return false;
    }

    /**
     * 构造启动 Intent。
     *
     * NEW_TASK 是 pinned shortcut 的硬性要求（系统代为 startActivity，
     * 不在任何 Activity 上下文里，缺少该 flag 会直接抛异常）。
     * MainActivity 已在 manifest 声明 launchMode=singleTask，
     * 应用已在后台时会复用同一实例并走 onNewIntent，不会出现两个实例
     * 各自持有一份「当前正在玩哪个游戏」而导致计时错乱。
     */
    private static Intent buildLaunchIntent(Context context, long gameId, Class<?> activityClass) {
        Intent intent = new Intent(context, activityClass);
        intent.setAction(Intent.ACTION_MAIN);
        intent.putExtra(EXTRA_HOME_TARGET, TARGET_LAUNCH_GAME);
        intent.putExtra(EXTRA_HOME_GAME_ID, gameId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private static String safeLabel(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return "未命名游戏";
        // 桌面标签本身会被启动器截断，这里限长只为避免个别启动器对超长文本处理异常
        return t.length() > 24 ? t.substring(0, 24) : t;
    }

    /**
     * 生成图标：封面居中裁正方形后铺满整个画布。
     *
     * galgame 封面多是 3:4 竖版，整图缩放会变形，所以居中裁成正方形。
     * 裁切位置纵向偏上（取上四分之一起点），保住角色头部。
     * 不留白：图标边缘会被系统圆角裁掉一点，但铺满比留白好看。
     */
    @SuppressLint("NewApi")
    private static Icon buildIcon(Context context, String coverUri, String label) {
        Bitmap cover = decodeCover(context, coverUri);
        Bitmap canvasBmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBmp);

        if (cover != null) {
            int side = Math.min(cover.getWidth(), cover.getHeight());
            // 竖版封面：横向取中间，纵向偏上，避免把角色头部裁掉
            int srcLeft = (cover.getWidth() - side) / 2;
            int srcTop = cover.getHeight() > cover.getWidth()
                    ? (cover.getHeight() - side) / 4
                    : (cover.getHeight() - side) / 2;
            Rect src = new Rect(srcLeft, srcTop, srcLeft + side, srcTop + side);
            Rect dst = new Rect(0, 0, ICON_SIZE, ICON_SIZE);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(cover, src, dst, paint);
            cover.recycle();
        } else {
            // 没有封面：主题底色 + 标题首字
            canvas.drawColor(0xFF1B2438);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFE8EDF5);
            paint.setTextSize(ICON_SIZE * 0.46f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = ICON_SIZE / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(initial(label), ICON_SIZE / 2f, baseline, paint);
        }

        // 不用 createWithAdaptiveBitmap：它会再向内裁掉约 1/3 边距，
        // 封面会被切得只剩中心一小块。
        return Icon.createWithBitmap(canvasBmp);
    }

    /**
     * 解码封面。用 inSampleSize 降采样，避免为了一个 192px 图标把
     * 上千像素的封面整张读进内存。
     */
    private static Bitmap decodeCover(Context context, String coverUri) {
        if (coverUri == null || coverUri.trim().isEmpty()) return null;
        try {
            Uri uri = Uri.parse(coverUri);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = context.getContentResolver().openInputStream(uri);
            if (probe == null) return null;
            try {
                BitmapFactory.decodeStream(probe, null, bounds);
            } finally {
                closeQuietly(probe);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight);
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in, null, opts);
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            Log.w(TAG, "decodeCover failed: " + coverUri, t);
            return null;
        }
    }

    private static int computeSampleSize(int width, int height) {
        int shorter = Math.min(width, height);
        int sample = 1;
        while (shorter / (sample * 2) >= COVER_DECODE_TARGET) sample *= 2;
        return sample;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    private static String initial(String label) {
        String t = label == null ? "" : label.trim();
        if (t.isEmpty()) return "Y";
        return t.substring(0, 1).toUpperCase(Locale.ROOT);
    }
}
