package com.yuki.yukihub.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 音乐厅的 SAF 辅助（**引用模式核心**）。
 *
 * <p>职责：
 * <ol>
 *   <li>发起系统选择器（选文件夹 / 选音频 / 选视频）</li>
 *   <li>把选中的 URI 做**持久化授权**（否则重启后读不到）</li>
 *   <li>扫描文件夹里的音频文件，读 ID3 元数据（标题 / 艺术家 / 时长）</li>
 *   <li>文件夹内找封面图（cover / folder / album / front）</li>
 * </ol>
 *
 * <p><b>不复制文件</b>：所有方法都只返回 URI 与元数据，文件本体留在原地。
 *
 * <p>线程：扫描与元数据读取都是**阻塞操作**（一个文件夹几十首歌可能要几百毫秒到几秒），
 * 调用方必须放在后台线程，不要在 UI 线程直接调 {@link #scanAudioFolder}。
 */
public class MusicSafHelper {

    private static final String TAG = "MusicSaf";

    /** 认得的音频扩展名（不区分大小写） */
    private static final String[] AUDIO_EXTS = {
            ".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".oga",
            ".wma", ".aiff", ".aif", ".mka", ".ape", ".dsf"
    };

    /** 目录内常见的封面文件名（小写比对，不含扩展名） */
    private static final String[] COVER_STEMS = {
            "cover", "folder", "album", "front", "artwork", "albumart", "thumb"
    };

    /* ==================== 选择器 Intent ==================== */

    /** 选文件夹（用于"添加专辑"）。用户选中的是 SAF 目录树。 */
    public static Intent folderPickerIntent(String startAt) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (startAt != null && !startAt.trim().isEmpty()) {
            try { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(startAt)); } catch (Throwable ignored) { }
        }
        return i;
    }

    /** 选音频文件（用于"添加单曲"）。multi = 允许多选。 */
    public static Intent audioPickerIntent(boolean multi) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg"});
        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    /** 选 PV 视频（用于"绑定 PV"）。 */
    public static Intent videoPickerIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    /** 选封面图（用于"换封面"）。 */
    public static Intent imagePickerIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    /* ==================== 持久化授权 ==================== */

    /**
     * 把 SAF 给的临时授权转成**持久化授权**（重启后仍可读）。
     *
     * @return true = 授权已持久化，可以安全写库
     *
     * <p><b>已知坑</b>：从"最近文件 / 第三方 App 分享"等入口拿到的 URI 可能**不带
     * persistable 标志**，这时会抛 SecurityException。调用方必须检查返回值，
     * **失败就不要写入数据库**（否则重启后是一条永远打不开的死记录）。
     */
    public static boolean persistRead(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        ContentResolver cr = context.getContentResolver();
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (SecurityException denied) {
            Log.w(TAG, "persist failed (no persistable flag): " + uri);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "persist failed: " + uri, t);
            return false;
        }
    }

    /* ==================== 文件夹扫描 ==================== */

    /** 扫描结果条目（尚未写库） */
    public static class ScannedTrack {
        public String audioUri;
        public String displayName;
        public String title;
        public String artist;
        public int trackNo;
        public long durationMs;
        public long sizeBytes;
    }

    /** 扫描结果 */
    public static class ScanResult {
        public String folderName;
        public String folderUri;
        public final List<ScannedTrack> tracks = new ArrayList<>();
        public String coverUri;      // 目录内找到的封面（可为 null）
        public String error;         // 非 null 表示扫描失败
    }

    /**
     * 扫描 SAF 目录树里的音频文件（**阻塞，必须后台线程**）。
     *
     * <p>会递归一层子目录吗？—— **不递归**，只扫当前层。理由：gal 音乐通常就是
     * "专辑文件夹里一堆 mp3"，递归反而会把多张专辑混在一起。
     */
    public static ScanResult scanAudioFolder(Context context, Uri treeUri) {
        ScanResult r = new ScanResult();
        if (context == null || treeUri == null) {
            r.error = "无效的目录";
            return r;
        }
        r.folderUri = treeUri.toString();

        try {
            ContentResolver cr = context.getContentResolver();
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);

            // 先取"文件夹自身的名字"当专辑默认标题
            r.folderName = queryDisplayName(cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId));
            if (r.folderName == null || r.folderName.trim().isEmpty()) r.folderName = "未命名专辑";

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
            // ⚠️ projection 传 null（= SELECT *）而不是写死列名：
            // 部分 DocumentsProvider 不认 COLUMN_SIZE/COLUMN_MIME_TYPE，
            // 写死列名在个别 ROM 上会直接抛异常导致整个扫描失败。
            // 改成拿到 cursor 后按列名安全取值（见下方的 col()）。
            Cursor c = cr.query(childrenUri, null, null, null, null);

            if (c == null) {
                r.error = "无法读取该目录（授权可能已失效）";
                return r;
            }
            try {
                int iDocId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                if (iDocId < 0 || iName < 0) {
                    r.error = "该目录不支持浏览（provider 未返回必要字段）";
                    return r;
                }
                while (c.moveToNext()) {
                    String docId = c.getString(iDocId);
                    String name = iName < 0 ? null : c.getString(iName);
                    String mime = iMime < 0 ? null : c.getString(iMime);
                    long size = (iSize < 0 || c.isNull(iSize)) ? 0L : c.getLong(iSize);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;  // 不递归
                    if (name == null || docId == null) continue;

                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

                    // 封面候选
                    if (r.coverUri == null && isCoverName(name)) {
                        r.coverUri = fileUri.toString();
                        continue;
                    }
                    if (!isAudio(name, mime)) continue;

                    ScannedTrack t = new ScannedTrack();
                    t.audioUri = fileUri.toString();
                    t.displayName = name;
                    t.sizeBytes = size;
                    t.trackNo = parseTrackNo(name);
                    t.title = stripExtension(name);

                    // ID3（失败就保持文件名兜底）
                    readMetadata(context, fileUri, t);

                    r.tracks.add(t);
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanAudioFolder failed", t);
            r.error = "扫描失败：" + t.getMessage();
        }

        // 排序：先按序号（>0 的排前面），再按文件名的自然顺序
        Collections.sort(r.tracks, new Comparator<ScannedTrack>() {
            @Override
            public int compare(ScannedTrack a, ScannedTrack b) {
                boolean an = a.trackNo > 0, bn = b.trackNo > 0;
                if (an && bn && a.trackNo != b.trackNo) return Integer.compare(a.trackNo, b.trackNo);
                if (an != bn) return an ? -1 : 1;
                return String.CASE_INSENSITIVE_ORDER.compare(
                        a.displayName == null ? "" : a.displayName,
                        b.displayName == null ? "" : b.displayName);
            }
        });
        return r;
    }

    /** 读单个音频文件的元数据（单曲添加时用）。 */
    public static ScannedTrack scanSingleAudio(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        ScannedTrack t = new ScannedTrack();
        t.audioUri = uri.toString();
        t.displayName = queryDisplayName(context.getContentResolver(), uri);
        if (t.displayName == null) t.displayName = uri.getLastPathSegment();
        t.title = stripExtension(t.displayName);
        t.trackNo = parseTrackNo(t.displayName);
        readMetadata(context, uri, t);
        return t;
    }

    /* ==================== 元数据（ID3） ==================== */

    /** 用 MediaMetadataRetriever 读标题/艺术家/时长；失败静默（保持文件名兜底）。 */
    private static void readMetadata(Context context, Uri uri, ScannedTrack t) {
        MediaMetadataRetriever mmr = null;
        android.content.res.AssetFileDescriptor afd = null;
        try {
            mmr = new MediaMetadataRetriever();
            // 优先用 FileDescriptor 方式：SAF 的 content URI 在部分 provider 上
            // setDataSource(Context, Uri) 会因"不支持随机访问"而失败，
            // 走 openFileDescriptor 更底层、命中率更高。
            boolean ok = false;
            try {
                afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
                if (afd != null && afd.getFileDescriptor() != null) {
                    mmr.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
            if (!ok) mmr.setDataSource(context, uri);   // 回退

            String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title != null && !title.trim().isEmpty()) t.title = title.trim();

            String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist == null || artist.trim().isEmpty()) {
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            }
            if (artist != null && !artist.trim().isEmpty()) t.artist = artist.trim();

            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try { t.durationMs = Long.parseLong(dur.trim()); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 读不到就算了：标题已用文件名兜底，时长留 0（UI 显示 --:--）
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignored) { }
            }
            if (afd != null) {
                try { afd.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 探测一个 URI 当前是否还能打开（用于失效检测）。
     * 只 open 一个 stream 就关，不读内容。
     */
    public static boolean canOpen(Context context, String uriStr) {
        if (context == null || uriStr == null || uriStr.trim().isEmpty()) return false;
        java.io.InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(Uri.parse(uriStr.trim()));
            return in != null;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /* ==================== 小工具 ==================== */

    public static boolean isAudio(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("audio/")) return true;
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        for (String ext : AUDIO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public static boolean isVideo(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("video/")) return true;
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv")
                || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".avi");
    }

    private static boolean isCoverName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp");
        if (!image) return false;
        String stem = stripExtension(lower);
        for (String s : COVER_STEMS) {
            if (stem.equals(s) || stem.startsWith(s + "-") || stem.startsWith(s + "_")) return true;
        }
        return false;
    }

    /** "01 - 曲名.mp3" → 1；"Track 03.mp3" → 3；读不出返回 0。 */
    public static int parseTrackNo(String name) {
        if (name == null) return 0;
        String stem = stripExtension(name).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:track|disc|cd)?\\s*0*(\\d{1,3})(?=\\s|[-_.]|$)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(stem);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                return (n > 0 && n < 1000) ? n : 0;
            } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** 去掉扩展名 */
    public static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    /** 查一个 document 的显示名（文件夹名 / 文件名）。 */
    private static String queryDisplayName(ContentResolver cr, Uri docUri) {
        Cursor c = null;
        try {
            c = cr.query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            return (c != null && c.moveToFirst()) ? c.getString(0) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }
}