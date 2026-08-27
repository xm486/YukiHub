package com.yuki.yukihub.util;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * .nomedia 管理工具。
 *
 * <p>作用：在游戏扫描目录下创建 {@code .nomedia} 空文件，让系统相册忽略该目录下的图片，
 * 避免解压游戏产生的大量 CG / 立绘塞满用户相册。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>安全判定 fail-closed</b>：路径解析失败、不在已知存储卷内，一律拒绝创建。
 *       在存储卷根目录创建 .nomedia 会导致相册读不到任何图片（包括用户自己的照片）。</li>
 *   <li><b>双 uri 形态</b>：项目里扫描根可能是 {@code file://}（内置文件选择器）
 *       或 {@code content://.../tree/}（原生 SAF），两条路径都要支持。</li>
 *   <li><b>SAF 创建后必须回读校验</b>：ExternalStorageProvider 对 ".nomedia" 这种
 *       纯扩展名文件名可能走 buildUniqueFile() 改名（如 .nomedia.bin），
 *       名字错一个字符功能就静默失效，所以创建后一定回读 displayName 比对。</li>
 * </ul>
 *
 * <p>本类不修改任何扫描逻辑，.nomedia 对 EngineDetector 的特征匹配无命中，
 * 不会被识别成游戏、启动项或封面。
 */
public final class NoMediaHelper {

    private static final String TAG = "NoMediaHelper";

    /** 目标文件名，全项目唯一来源，不要在别处硬编码。 */
    public static final String FILE_NAME = ".nomedia";

    private static final String INTERNAL_ROOT = "/storage/emulated/0";

    /**
     * 系统媒体目录名（存储卷根下的一级目录名，小写比对）。
     *
     * <p>注意：只用于判断"目标目录本身是不是这些目录"，
     * <b>不做路径包含匹配</b>。所以 Download/游戏 这类子目录会正常放行。
     */
    private static final Set<String> MEDIA_DIR_NAMES = new HashSet<>(Arrays.asList(
            "dcim", "pictures", "movies", "music", "download", "downloads",
            "documents", "alarms", "ringtones", "notifications", "podcasts",
            "audiobooks", "recordings", "screenshots", "android"
    ));

    private NoMediaHelper() {}

    // ==================== 安全判定 ====================

    /** 拒绝原因。 */
    public enum Reject {
        /** 允许创建。 */
        NONE,
        /** 目标是存储卷根目录。 */
        STORAGE_ROOT,
        /** 目标本身就是系统媒体目录（DCIM / Download 等）。 */
        MEDIA_DIR_SELF,
        /** 目标在 Android/ 数据目录内。 */
        ANDROID_DIR,
        /** 无法解析出真实路径，或不在已知存储卷内。 */
        UNRESOLVED
    }

    /** 安全判定结果。 */
    public static final class Safety {
        public final boolean allowed;
        public final Reject reject;
        /** 归一化后的真实路径；被拒时可能为 null。 */
        public final String realPath;
        /** 判定涉及的位置描述，用于弹窗文案。 */
        public final String detail;

        Safety(boolean allowed, Reject reject, String realPath, String detail) {
            this.allowed = allowed;
            this.reject = reject;
            this.realPath = realPath;
            this.detail = detail == null ? "" : detail;
        }

        /** 给用户看的拒绝原因（一句话）。 */
        public String message() {
            switch (reject) {
                case STORAGE_ROOT:
                    return "目标是存储卡根目录（" + detail + "）。在这里创建会让相册读不到任何图片，包括你自己拍的照片。";
                case MEDIA_DIR_SELF:
                    return "目标是系统媒体目录（" + detail + "）。在这里创建会让相册丢失该目录下的全部内容。";
                case ANDROID_DIR:
                    return "目标位于 Android 系统数据目录内（" + detail + "），不适用且通常无写入权限。";
                case UNRESOLVED:
                    return "无法确定该目录的真实位置" + (detail.isEmpty() ? "" : "（" + detail + "）") + "，为安全起见已阻止。";
                default:
                    return "";
            }
        }
    }

    /**
     * 判断能否在该目录安全创建 .nomedia。纯函数，不做 IO。
     *
     * <p>规则（顺序即优先级）：
     * <ol>
     *   <li>路径解析失败 / 不在已知存储卷内 → 拒绝（fail-closed）</li>
     *   <li>卷内相对路径为空（即卷根本身）→ 拒绝</li>
     *   <li>相对路径只有一段且该段是系统媒体目录名 → 拒绝</li>
     *   <li>相对路径首段是 android → 拒绝（Android/** 任意深度）</li>
     *   <li>其余放行（含 Download/游戏 这类二级及更深目录）</li>
     * </ol>
     */
    public static Safety check(String rootUri) {
        String realPath = resolveRealPath(rootUri);
        if (realPath == null || realPath.trim().isEmpty()) {
            return new Safety(false, Reject.UNRESOLVED, null, "");
        }

        String p = normalizePath(realPath);

        String volumeRoot;
        String rel;
        if (p.equals(INTERNAL_ROOT)) {
            volumeRoot = p;
            rel = "";
        } else if (p.startsWith(INTERNAL_ROOT + "/")) {
            volumeRoot = INTERNAL_ROOT;
            rel = p.substring(INTERNAL_ROOT.length() + 1);
        } else if (p.startsWith("/storage/")) {
            // /storage/XXXX-XXXX[/...] → 外置 SD 卡
            String tail = p.substring("/storage/".length());
            int slash = tail.indexOf('/');
            if (slash < 0) {
                volumeRoot = p;
                rel = "";
            } else {
                volumeRoot = "/storage/" + tail.substring(0, slash);
                rel = tail.substring(slash + 1);
            }
        } else {
            // 未知挂载点（U 盘、第三方 provider 映射等）→ fail-closed
            return new Safety(false, Reject.UNRESOLVED, null, p);
        }

        if (rel.isEmpty()) {
            return new Safety(false, Reject.STORAGE_ROOT, p, volumeRoot);
        }

        String[] seg = rel.split("/");
        if (seg.length == 0 || seg[0].trim().isEmpty()) {
            return new Safety(false, Reject.STORAGE_ROOT, p, volumeRoot);
        }

        String first = seg[0].toLowerCase(Locale.ROOT);
        if ("android".equals(first)) {
            return new Safety(false, Reject.ANDROID_DIR, p, p);
        }
        // 只有"目标目录本身就是卷根下的一级媒体目录"才拦；更深的子目录放行。
        if (seg.length == 1 && MEDIA_DIR_NAMES.contains(first)) {
            return new Safety(false, Reject.MEDIA_DIR_SELF, p, volumeRoot + "/" + seg[0]);
        }

        return new Safety(true, Reject.NONE, p, p);
    }

    /** 路径归一化：反斜杠转正斜杠、压缩重复斜杠、去尾斜杠、/sdcard 归一化到 /storage/emulated/0。 */
    private static String normalizePath(String raw) {
        String p = raw.trim().replace('\\', '/');
        p = p.replaceAll("/+", "/");
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.equals("/sdcard")) {
            p = INTERNAL_ROOT;
        } else if (p.startsWith("/sdcard/")) {
            p = INTERNAL_ROOT + "/" + p.substring("/sdcard/".length());
        }
        return p;
    }

    // ==================== uri → 真实路径 ====================

    /**
     * 把扫描根 uri 解析成真实文件路径，失败返回 null。
     *
     * <p>支持三种输入：裸路径、{@code file://}（内置文件选择器 FileChooserDialog 产出）、
     * {@code content://.../tree/...}（原生 SAF 产出）。
     * content 分支的算法与 MainActivity#documentIdToPath 保持一致。
     */
    public static String resolveRealPath(String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return null;
        String s = rootUri.trim();

        if (s.startsWith("/")) return s;

        if (s.startsWith("file://") || s.startsWith("file:/")) {
            try {
                String path = Uri.parse(s).getPath();
                if (path != null && !path.isEmpty()) return path;
            } catch (Throwable ignored) { }
            return s.startsWith("file://") ? s.substring("file://".length())
                    : s.substring("file:".length());
        }

        if (s.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(s);
                String docId = null;
                try {
                    docId = DocumentsContract.getDocumentId(uri);
                } catch (Throwable ignored) { }
                if (isBlank(docId)) {
                    try {
                        docId = DocumentsContract.getTreeDocumentId(uri);
                    } catch (Throwable ignored) { }
                }
                if (isBlank(docId)) docId = uri.getLastPathSegment();
                return documentIdToPath(docId);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** documentId（如 {@code primary:Games/Example}）→ 真实路径，无法识别返回 null。 */
    private static String documentIdToPath(String docId) {
        if (isBlank(docId)) return null;
        String id = Uri.decode(docId.trim());
        // 剥掉可能带上的 uri 结构前缀；不能按最后一个 '/' 截断，
        // 因为 primary:Games/Example 里的 '/' 是真实层级。
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
            return rel.isEmpty() ? INTERNAL_ROOT : INTERNAL_ROOT + "/" + rel;
        }
        if (volume.isEmpty()) return null;
        return rel.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + rel;
    }

    // ==================== 存在性探测（只读） ====================

    /**
     * 探测目录下是否已有 .nomedia。纯只读，不写任何文件。
     *
     * <p>三级降级：File.exists（最快最准）→ SAF 单点 query → SAF children 全量精确匹配。
     */
    public static boolean exists(Context ctx, String rootUri) {
        String realPath = resolveRealPath(rootUri);
        if (realPath != null && !realPath.isEmpty()) {
            try {
                File f = new File(normalizePath(realPath), FILE_NAME);
                if (f.exists()) return true;
                // 父目录可读但文件不存在 → 结论可信，直接返回
                File parent = f.getParentFile();
                if (parent != null && parent.canRead()) return false;
            } catch (Throwable t) {
                Log.w(TAG, "File exists probe failed", t);
            }
        }
        if (ctx == null || rootUri == null || !rootUri.trim().startsWith("content://")) return false;
        Uri child = buildChildUri(rootUri, FILE_NAME);
        if (child != null && queryExists(ctx, child)) return true;
        return safListContains(ctx, rootUri, FILE_NAME);
    }

    private static boolean queryExists(Context ctx, Uri docUri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            return c != null && c.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(c);
        }
    }

    /** 全量列出子项精确匹配名字（一次 query，不用 DocumentFile 逐项 IPC）。 */
    private static boolean safListContains(Context ctx, String treeUriStr, String name) {
        Cursor c = null;
        try {
            Uri tree = Uri.parse(treeUriStr);
            String docId = selfDocumentId(tree);
            if (isBlank(docId)) return false;
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            c = ctx.getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c == null) return false;
            while (c.moveToNext()) {
                if (name.equals(c.getString(0))) return true;
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "saf list probe failed", t);
            return false;
        } finally {
            closeQuietly(c);
        }
    }

    // ==================== 创建 / 移除 ====================

    /** 操作结果。 */
    public static final class Result {
        public final boolean success;
        /** 目标本来就已存在（视为成功，但 UI 上区分文案）。 */
        public final boolean alreadyExists;
        public final String realPath;
        public final String error;

        Result(boolean success, boolean alreadyExists, String realPath, String error) {
            this.success = success;
            this.alreadyExists = alreadyExists;
            this.realPath = realPath;
            this.error = error == null ? "" : error;
        }

        static Result ok(String path) { return new Result(true, false, path, null); }
        static Result already(String path) { return new Result(true, true, path, null); }
        static Result fail(String err) { return new Result(false, false, null, err); }
    }

    /**
     * 创建 .nomedia。<b>调用前必须先通过 {@link #check(String)}</b>，
     * 本方法内部会再判一次作为最后防线。
     *
     * <p>成功后自动触发媒体库重扫，让已入库的图片条目被清理。
     */
    public static Result create(Context ctx, String rootUri) {
        Safety safety = check(rootUri);
        if (!safety.allowed) {
            Log.w(TAG, "create blocked: " + safety.reject + " uri=" + rootUri);
            return Result.fail(safety.message());
        }
        if (exists(ctx, rootUri)) {
            return Result.already(safety.realPath);
        }

        // 路径 1：File 直写，文件名 100% 精确，优先走。
        if (canWriteDirectly(safety.realPath)) {
            try {
                File target = new File(safety.realPath, FILE_NAME);
                if (target.exists()) return Result.already(safety.realPath);
                if (target.createNewFile()) {
                    Log.i(TAG, "created via File: " + target.getAbsolutePath());
                    requestMediaRescan(ctx, safety.realPath);
                    return Result.ok(safety.realPath);
                }
                Log.w(TAG, "File.createNewFile returned false: " + target.getAbsolutePath());
            } catch (Throwable t) {
                Log.w(TAG, "File create failed, fallback to SAF", t);
            }
        }

        // 路径 2：SAF createDocument + 回读校验。
        if (ctx == null || rootUri == null || !rootUri.trim().startsWith("content://")) {
            return Result.fail("没有该目录的写入权限，请重新绑定扫描目录并授予写入权限");
        }
        return createViaSaf(ctx, rootUri, safety.realPath);
    }

    private static Result createViaSaf(Context ctx, String rootUri, String realPath) {
        Uri created = null;
        try {
            Uri tree = Uri.parse(rootUri);
            String parentDocId = selfDocumentId(tree);
            if (isBlank(parentDocId)) return Result.fail("无法定位目录，请重新绑定扫描目录");
            Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId);

            created = DocumentsContract.createDocument(
                    ctx.getContentResolver(), parentDoc, "application/octet-stream", FILE_NAME);
            if (created == null) {
                return Result.fail("创建失败，可能没有该目录的写入权限");
            }

            // 关键校验：部分 ROM 的 ExternalStorageProvider 会把 .nomedia 改名
            // （buildUniqueFile 按 mimeType 补扩展名），名字错了功能就静默失效。
            String actual = queryDisplayName(ctx, created);
            Log.i(TAG, "SAF created, displayName=" + actual);
            if (!FILE_NAME.equals(actual)) {
                boolean renamed = false;
                try {
                    Uri after = DocumentsContract.renameDocument(
                            ctx.getContentResolver(), created, FILE_NAME);
                    if (after != null) created = after;
                    renamed = FILE_NAME.equals(queryDisplayName(ctx, created));
                    Log.i(TAG, "rename to .nomedia result=" + renamed);
                } catch (Throwable t) {
                    Log.w(TAG, "renameDocument failed", t);
                }
                if (!renamed) {
                    // 名字修不对就删掉，绝不留一个无效的垃圾文件让用户以为生效了。
                    try {
                        DocumentsContract.deleteDocument(ctx.getContentResolver(), created);
                    } catch (Throwable ignored) { }
                    return Result.fail("系统把文件名改成了「" + actual + "」且无法修正，已撤销。"
                            + "建议在设置中授予「所有文件访问权限」后重试");
                }
            }

            requestMediaRescan(ctx, realPath);
            return Result.ok(realPath);
        } catch (SecurityException se) {
            Log.w(TAG, "SAF create denied", se);
            return Result.fail("没有该目录的写入权限，请重新绑定扫描目录并授予写入权限");
        } catch (Throwable t) {
            Log.w(TAG, "SAF create failed", t);
            return Result.fail("创建失败：" + shortError(t));
        }
    }

    /** 移除 .nomedia（撤销用），同样触发媒体重扫。 */
    public static Result remove(Context ctx, String rootUri) {
        String realPath = resolveRealPath(rootUri);
        String normalized = realPath == null ? null : normalizePath(realPath);

        if (normalized != null && canWriteDirectly(normalized)) {
            try {
                File target = new File(normalized, FILE_NAME);
                if (!target.exists()) {
                    requestMediaRescan(ctx, normalized);
                    return Result.ok(normalized);
                }
                if (target.delete()) {
                    Log.i(TAG, "removed via File: " + target.getAbsolutePath());
                    requestMediaRescan(ctx, normalized);
                    return Result.ok(normalized);
                }
            } catch (Throwable t) {
                Log.w(TAG, "File delete failed, fallback to SAF", t);
            }
        }

        if (ctx == null || rootUri == null || !rootUri.trim().startsWith("content://")) {
            return Result.fail("没有该目录的写入权限，无法移除");
        }
        try {
            Uri child = buildChildUri(rootUri, FILE_NAME);
            if (child == null) return Result.fail("无法定位文件");
            if (!queryExists(ctx, child)) {
                requestMediaRescan(ctx, normalized);
                return Result.ok(normalized);
            }
            boolean deleted = DocumentsContract.deleteDocument(ctx.getContentResolver(), child);
            if (!deleted) return Result.fail("移除失败，可能没有写入权限");
            Log.i(TAG, "removed via SAF: " + child);
            requestMediaRescan(ctx, normalized);
            return Result.ok(normalized);
        } catch (SecurityException se) {
            return Result.fail("没有该目录的写入权限，无法移除");
        } catch (Throwable t) {
            Log.w(TAG, "SAF delete failed", t);
            return Result.fail("移除失败：" + shortError(t));
        }
    }

    // ==================== 媒体库重扫 ====================

    /**
     * 请求系统重扫该目录，让已入库的图片条目被清理。
     *
     * <p>注意：这不是 100% 保证的行为，取决于各 ROM 的 MediaProvider 实现。
     * UI 文案必须提示用户"可能需要等待或重启设备"，不要承诺立即生效。
     */
    public static void requestMediaRescan(Context ctx, String realPath) {
        if (ctx == null || realPath == null || realPath.trim().isEmpty()) return;
        try {
            MediaScannerConnection.scanFile(ctx.getApplicationContext(),
                    new String[]{realPath}, null,
                    (path, uri) -> Log.i(TAG, "media rescan done path=" + path + " uri=" + uri));
        } catch (Throwable t) {
            Log.w(TAG, "media rescan request failed", t);
        }
    }

    // ==================== 内部工具 ====================

    /** 是否可以用 File API 直接写入（有全文件访问权限或旧版本写权限）。 */
    private static boolean canWriteDirectly(String realPath) {
        if (realPath == null || realPath.isEmpty()) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) return false;
            }
            File dir = new File(realPath);
            return dir.isDirectory() && dir.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取 tree uri 自身的 documentId：优先 documentId（子目录场景），回退 treeDocumentId。 */
    private static String selfDocumentId(Uri treeUri) {
        String docId = null;
        try {
            docId = DocumentsContract.getDocumentId(treeUri);
        } catch (Throwable ignored) { }
        if (isBlank(docId)) {
            try {
                docId = DocumentsContract.getTreeDocumentId(treeUri);
            } catch (Throwable ignored) { }
        }
        return docId;
    }

    /** 拼出目录下指定名字的子文档 uri（仅用于探测/删除，不保证存在）。 */
    private static Uri buildChildUri(String treeUriStr, String name) {
        try {
            Uri tree = Uri.parse(treeUriStr);
            String parentDocId = selfDocumentId(tree);
            if (isBlank(parentDocId)) return null;
            String childDocId = parentDocId.endsWith("/")
                    ? parentDocId + name
                    : parentDocId + "/" + name;
            return DocumentsContract.buildDocumentUriUsingTree(tree, childDocId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String queryDisplayName(Context ctx, Uri docUri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(c);
        }
        return null;
    }

    private static void closeQuietly(Cursor c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) { }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String shortError(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) return t.getClass().getSimpleName();
        return msg.length() > 80 ? msg.substring(0, 80) : msg;
    }
}