package com.yuki.yukihub.docs;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 把 YukiHub 的两个私有目录暴露给系统「文件」框架（DocumentsProvider）。
 *
 * <p>【为什么做这个】
 * MT 管理器 / 系统文件管理器里「添加本地存储」时，能直接选中 YukiHub，
 * 进而来回拿放存档、替换游戏资源，全程不需要 root。
 * 本类就是让 YukiHub 自己出现在那个列表里的实现。
 *
 * <p>【暴露范围】只开一个根：
 * <ul>
 *   <li>{@code getFilesDir()/krkr_internal/} —— KRKR 内部存储运行的游戏副本（含各自 savedata）</li>
 * </ul>
 * token、数据库、缓存等敏感数据**不在暴露范围内**。
 *
 * <p>【为什么不暴露外部存档目录】{@code getExternalFilesDir()/save/} 位于
 * {@code /storage/emulated/0/Android/data/<pkg>/files/save/}，本身就是**用户可见的外部路径**，
 * MT 管理器不需要我们帮忙就能进，没必要在这里重复暴露。
 *
 * <p>【安全】provider 在 manifest 里声明
 * {@code android:permission="android.permission.MANAGE_DOCUMENTS"}，
 * 只有持有该权限的系统组件能调用；同时所有 documentId 都经过
 * canonical path 越界校验，保证访问不会逃出白名单根目录。
 */
public class YukiHubDocumentsProvider extends DocumentsProvider {

    /** 根 ID 同时也是根目录的 documentId。 */
    private static final String ROOT_KRKR = "krkr_internal";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY,
    };

    @Override
    public boolean onCreate() {
        ensureRoots();
        return true;
    }

    // ==================== Roots ====================

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] proj = projection != null ? projection : DEFAULT_ROOT_PROJECTION;
        MatrixCursor result = new MatrixCursor(proj);
        ensureRoots();

        File krkr = krkrRoot();
        if (krkr != null) {
            HashMap<String, Object> m = new HashMap<>();
            m.put(Root.COLUMN_ROOT_ID, ROOT_KRKR);
            m.put(Root.COLUMN_DOCUMENT_ID, ROOT_KRKR);
            m.put(Root.COLUMN_TITLE, "YukiHub · 内部运行");
            m.put(Root.COLUMN_SUMMARY, "KRKR 内部存储运行的游戏副本（含存档）");
            m.put(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE);
            m.put(Root.COLUMN_MIME_TYPES, "*/*");
            m.put(Root.COLUMN_ICON, com.yuki.yukihub.R.mipmap.ic_launcher_new);
            m.put(Root.COLUMN_AVAILABLE_BYTES, krkr.getUsableSpace());
            emit(result, proj, m);
        }
        return result;
    }

    // ==================== Documents ====================

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        String[] proj = projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
        MatrixCursor result = new MatrixCursor(proj);
        includeFile(result, proj, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        String[] proj = projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
        MatrixCursor result = new MatrixCursor(proj);

        File parent = resolve(parentDocumentId);
        if (parent == null || !parent.isDirectory()) {
            throw new FileNotFoundException("not a directory: " + parentDocumentId);
        }

        File[] children = parent.listFiles();
        if (children != null) {
            List<File> dirs = new ArrayList<>();
            List<File> files = new ArrayList<>();
            for (File c : children) {
                // 隐藏文件（.nomedia 之类）不对外暴露
                if (c.getName().startsWith(".")) continue;
                if (c.isDirectory()) dirs.add(c);
                else files.add(c);
            }
            // 目录在前、各自按名字排序，与系统文件管理器习惯一致
            Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
            Collections.sort(dirs, byName);
            Collections.sort(files, byName);
            for (File d : dirs) includeFile(result, proj, documentIdOf(d));
            for (File f : files) includeFile(result, proj, documentIdOf(f));
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = resolve(documentId);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("not found: " + documentId);
        }
        try {
            return ParcelFileDescriptor.open(file, modeToMode(mode));
        } catch (Exception e) {
            throw new FileNotFoundException("open failed: " + documentId);
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (parent == null || !parent.isDirectory()) {
            throw new FileNotFoundException("bad parent: " + parentDocumentId);
        }
        File target = new File(parent, displayName);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!target.mkdirs() && !target.isDirectory()) {
                    throw new FileNotFoundException("mkdir failed: " + displayName);
                }
            } else if (!target.createNewFile()) {
                throw new FileNotFoundException("create failed: " + displayName);
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("create failed: " + displayName);
        }
        return documentIdOf(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (isRoot(documentId)) {
            throw new FileNotFoundException("cannot delete root: " + documentId);
        }
        File file = resolve(documentId);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("not found: " + documentId);
        }
        if (!deleteRecursively(file)) {
            throw new FileNotFoundException("delete failed: " + documentId);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (isRoot(documentId)) {
            throw new FileNotFoundException("cannot rename root: " + documentId);
        }
        File src = resolve(documentId);
        if (src == null || !src.exists()) {
            throw new FileNotFoundException("not found: " + documentId);
        }
        File parent = src.getParentFile();
        if (parent == null) {
            throw new FileNotFoundException("no parent: " + documentId);
        }
        File dst = new File(parent, displayName);
        if (!src.renameTo(dst)) {
            throw new FileNotFoundException("rename failed: " + documentId);
        }
        return documentIdOf(dst);
    }

    // ==================== 路径解析 ====================

    private File krkrRoot() {
        try {
            return new File(getContext().getFilesDir(), "krkr_internal");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 根目录先建出来，否则文件管理器里看不到条目。 */
    private void ensureRoots() {
        try {
            File f = krkrRoot();
            if (f != null && !f.exists()) f.mkdirs();
        } catch (Throwable ignored) { }
    }

    private File baseOf(String rootId) {
        if (ROOT_KRKR.equals(rootId)) return krkrRoot();
        return null;
    }

    /**
     * documentId → File。越界、非法、或不在白名单根内的一律返回 null。
     *
     * <p>用 canonical path 比对，顺带挡掉 {@code ../} 之类的路径穿越。
     */
    private File resolve(String documentId) {
        if (documentId == null) return null;
        int slash = documentId.indexOf('/');
        String rootId = slash < 0 ? documentId : documentId.substring(0, slash);
        String rel = slash < 0 ? "" : documentId.substring(slash + 1);
        File base = baseOf(rootId);
        if (base == null) return null;

        File target = rel.isEmpty() ? base : new File(base, rel);
        try {
            String cbase = base.getCanonicalPath();
            String ctarget = target.getCanonicalPath();
            if (!ctarget.equals(cbase) && !ctarget.startsWith(cbase + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return target;
    }

    /** File → documentId。不属于白名单根时返回 null。 */
    private String documentIdOf(File file) {
        File base = krkrRoot();
        if (base == null) return null;
        try {
            String cbase = base.getCanonicalPath();
            String cfile = file.getCanonicalPath();
            if (cfile.equals(cbase)) return ROOT_KRKR;
            if (cfile.startsWith(cbase + File.separator)) {
                return ROOT_KRKR + "/" + cfile.substring(cbase.length() + 1);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean isRoot(String documentId) {
        return ROOT_KRKR.equals(documentId);
    }

    // ==================== 行构造 ====================

    /** 按 projection 顺序落一行，缺列填 null（避免 MatrixCursor 列名不匹配报错）。 */
    private void emit(MatrixCursor cursor, String[] projection, HashMap<String, Object> values) {
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            row[i] = values.get(projection[i]);
        }
        cursor.addRow(row);
    }

    private void includeFile(MatrixCursor cursor, String[] projection, String documentId)
            throws FileNotFoundException {
        if (documentId == null) throw new FileNotFoundException("null documentId");
        File file = resolve(documentId);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("not found: " + documentId);
        }

        boolean isDir = file.isDirectory();
        int flags = isDir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
        if (!isRoot(documentId)) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }

        HashMap<String, Object> m = new HashMap<>();
        m.put(Document.COLUMN_DOCUMENT_ID, documentId);
        m.put(Document.COLUMN_DISPLAY_NAME, isRoot(documentId) ? rootTitle(documentId) : file.getName());
        m.put(Document.COLUMN_MIME_TYPE, isDir ? Document.MIME_TYPE_DIR : mimeTypeOf(file));
        m.put(Document.COLUMN_SIZE, isDir ? null : file.length());
        m.put(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        m.put(Document.COLUMN_FLAGS, flags);
        m.put(Document.COLUMN_SUMMARY, null);
        emit(cursor, projection, m);
    }

    private String rootTitle(String rootId) {
        if (ROOT_KRKR.equals(rootId)) return "YukiHub · 内部运行";
        return rootId;
    }

    // ==================== 杂项 ====================

    private static String mimeTypeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static int modeToMode(String mode) {
        if ("r".equals(mode)) return ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equals(mode) || "wt".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        if ("wa".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_APPEND;
        }
        if ("rw".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        }
        if ("rwt".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        throw new IllegalArgumentException("bad mode: " + mode);
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursively(c)) return false;
                }
            }
        }
        return file.delete();
    }
}