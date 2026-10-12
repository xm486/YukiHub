package bin.mt.file.content;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class MTDataFilesProvider extends DocumentsProvider {
    public static final String COLUMN_MT_EXTRAS = "mt_extras";
    public static final String COLUMN_MT_PATH = "mt_path";
    public static final String METHOD_SET_LAST_MODIFIED = "mt:setLastModified";
    public static final String METHOD_SET_PERMISSIONS = "mt:setPermissions";
    public static final String METHOD_CREATE_SYMLINK = "mt:createSymlink";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            COLUMN_MT_EXTRAS,
    };

    private String packageName;
    private File dataDir;
    private File userDeDataDir;
    private File androidDataDir;
    private File androidObbDir;

    /**
     * 【安全加固】私有目录下唯一放行的顶层目录名。
     *
     * <p>背景：MT「添加本地存储」会让外部文件管理器直接读取本应用私有目录，
     * 而 {@code shared_prefs/yukihub_prefs.xml} 中保存着登录令牌
     * （auth_access_token / auth_refresh_token），一旦被读出即可冒充账号登录。
     *
     * <p>因此把两个**私有**根（data、user_de_data）限制为只放行 {@code files/} 子树；
     * shared_prefs、databases、cache 等一律不可见、不可访问。
     *
     * <p>android_data / android_obb 位于外部存储，本身即为用户可见路径、
     * 不含令牌等敏感数据，保持原样以便用户取放存档。
     */
    private static final String ALLOWED_PRIVATE_TOP = "files";

    /** 判断私有根下的相对路径是否在白名单内（空串代表私有根自身，允许访问但列出时会再过滤）。 */
    private static boolean isAllowedPrivateSubPath(String subPath) {
        if (subPath == null || subPath.isEmpty()) return true;
        int slash = subPath.indexOf('/');
        String top = slash < 0 ? subPath : subPath.substring(0, slash);
        return ALLOWED_PRIVATE_TOP.equals(top);
    }
    /** 该目录是否为受保护的私有根（data / user_de_data）。 */
    private boolean isPrivateRootDir(File dir) {
        if (dir == null) return false;
        String p = dir.getPath();
        return (dataDir != null && p.equals(dataDir.getPath()))
                || (userDeDataDir != null && p.equals(userDeDataDir.getPath()));
    }

    /**
     * 【安全加固】校验「新建/移动」产生的目标文件是否落在允许范围内。
     *
     * <p>仅靠 {@link #getFileForDocId} 不够：createDocument 会拼接 displayName，
     * moveDocument 会拼目标目录，攻击者可用 {@code ../shared_prefs} 之类绕过。
     *
     * <p>判定采用**白名单式**（而非黑名单）：只有落在下列位置才放行，其余一律拒绝。
     * <ul>
     *   <li>私有根（data / user_de_data）下的 {@code files/} 子树</li>
     *   <li>外部存储根（android_data / android_obb）及其子树</li>
     * </ul>
     * 这样即使用 {@code ../../} 跳出私有根，也会因「不在任何允许根内」而被拒。
     */
    private boolean isTargetPathAllowed(File target) {
        if (target == null) return false;
        try {
            String tp = target.getCanonicalPath();
            // ① 私有根：只放行 files/ 子树
            for (File root : new File[]{dataDir, userDeDataDir}) {
                if (root == null) continue;
                String rp = root.getCanonicalPath();
                if (tp.equals(rp)) return true;
                if (tp.startsWith(rp + "/")) {
                    return isAllowedPrivateSubPath(tp.substring(rp.length() + 1));
                }
            }
            // ② 外部存储根：本身即用户可见，放行
            for (File root : new File[]{androidDataDir, androidObbDir}) {
                if (root == null) continue;
                String rp = root.getCanonicalPath();
                if (tp.equals(rp) || tp.startsWith(rp + "/")) return true;
            }
            // ③ 其余位置（含用 .. 逃出私有根的）→ 拒绝
            return false;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * @noinspection ALL
     */
    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        packageName = context.getPackageName();
        dataDir = context.getFilesDir().getParentFile();
        String dataDirPath = dataDir.getPath();
        if (dataDirPath.startsWith("/data/user/")) {
            userDeDataDir = new File("/data/user_de/" + dataDirPath.substring(11));
        }
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            androidDataDir = externalFilesDir.getParentFile();
        }
        androidObbDir = context.getObbDir();
    }

    /**
     * 返回null表示在根目录
     */
    private File getFileForDocId(String docId) throws FileNotFoundException {
        return getFileForDocId(docId, true);
    }

    /**
     * 返回null表示在根目录
     */
    private File getFileForDocId(String docId, boolean checkExists) throws FileNotFoundException {
        String filename = docId;
        if (filename.startsWith(packageName)) {
            filename = filename.substring(packageName.length());
        } else {
            throw new FileNotFoundException(docId + " not found");
        }
        if (filename.startsWith("/")) {
            filename = filename.substring(1);
        }
        if (filename.isEmpty()) {
            return null;
        }
        String type;
        String subPath;
        int i = filename.indexOf('/');
        if (i == -1) {
            type = filename;
            subPath = "";
        } else {
            type = filename.substring(0, i);
            subPath = filename.substring(i + 1);
        }
        File f = null;
        if (type.equalsIgnoreCase("data")) {
            // 【安全加固】私有目录只放行 files/ 子树，屏蔽 shared_prefs / databases / cache 等
            if (!isAllowedPrivateSubPath(subPath)) {
                throw new FileNotFoundException(docId + " not found");
            }
            f = new File(dataDir, subPath);
        } else if (type.equalsIgnoreCase("android_data") && androidDataDir != null) {
            f = new File(androidDataDir, subPath);
        } else if (type.equalsIgnoreCase("android_obb") && androidObbDir != null) {
            f = new File(androidObbDir, subPath);
        } else if (type.equalsIgnoreCase("user_de_data") && userDeDataDir != null) {
            // 【安全加固】同上
            if (!isAllowedPrivateSubPath(subPath)) {
                throw new FileNotFoundException(docId + " not found");
            }
            f = new File(userDeDataDir, subPath);
        }
        if (f == null) {
            throw new FileNotFoundException(docId + " not found");
        }
        if (checkExists) {
            try {
                Os.lstat(f.getPath()); // 不能用File.exists: 如果file是个link，且目标无法访问，则exists返回false
            } catch (Exception e) {
                throw new FileNotFoundException(docId + " not found");
            }
        }
        return f;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        final ApplicationInfo applicationInfo = getContext().getApplicationInfo();
        final String applicationName = applicationInfo.loadLabel(getContext().getPackageManager()).toString();
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, packageName);
        row.add(Root.COLUMN_DOCUMENT_ID, packageName);
        row.add(Root.COLUMN_SUMMARY, packageName);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, applicationName);
        row.add(Root.COLUMN_MIME_TYPES, "*/*");
        row.add(Root.COLUMN_ICON, applicationInfo.icon);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (parentDocumentId.endsWith("/")) {
            parentDocumentId = parentDocumentId.substring(0, parentDocumentId.length() - 1);
        }
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        if (parent == null) {
            includeFile(result, parentDocumentId + "/data", dataDir);
            if (androidDataDir != null && androidDataDir.exists()) {
                includeFile(result, parentDocumentId + "/android_data", androidDataDir);
            }
            if (androidObbDir != null && androidObbDir.exists()) {
                includeFile(result, parentDocumentId + "/android_obb", androidObbDir);
            }
            if (userDeDataDir != null && userDeDataDir.exists()) {
                includeFile(result, parentDocumentId + "/user_de_data", userDeDataDir);
            }
        } else {
            File[] files = parent.listFiles();
            if (files != null) {
                // 【安全加固】私有根下只列出 files/，隐藏 shared_prefs / databases / cache 等
                boolean filterPrivate = isPrivateRootDir(parent);
                for (File file : files) {
                    if (filterPrivate && !isAllowedPrivateSubPath(file.getName())) continue;
                    includeFile(result, parentDocumentId + "/" + file.getName(), file);
                }
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId, false);
        if (file == null) {
            throw new FileNotFoundException(documentId + " not found");
        }
        final int fileMode = parseFileMode(mode);
        return ParcelFileDescriptor.open(file, fileMode);
    }

    private static int parseFileMode(String mode) {
        switch (mode) {
            case "r":
                return ParcelFileDescriptor.MODE_READ_ONLY;
            case "w":
            case "wt":
                return ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE;
            case "wa":
                return ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_APPEND;
            case "rw":
                return ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE;
            case "rwt":
                return ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE;
            default:
                throw new IllegalArgumentException("Invalid mode: " + mode);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        if (parent != null) {
            File newFile = new File(parent, displayName);
            // 【安全加固】防止 displayName 用 ../ 逃出白名单
            if (!isTargetPathAllowed(newFile)) {
                throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " with name " + displayName);
            }
            int noConflictId = 2;
            while (newFile.exists()) {
                newFile = new File(parent, displayName + " (" + noConflictId++ + ")");
            }
            try {
                boolean succeeded;
                if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                    succeeded = newFile.mkdir();
                } else {
                    succeeded = newFile.createNewFile();
                }
                if (succeeded) {
                    return parentDocumentId.endsWith("/") ? parentDocumentId + newFile.getName() : parentDocumentId + "/" + newFile.getName();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " with name " + displayName);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (file == null || !deleteFile(file)) {
            throw new FileNotFoundException("Failed to delete document " + documentId);
        }
    }

    private static boolean deleteFile(File file) {
        if (file.isDirectory() && !isSymbolicLink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteFile(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private static boolean isSymbolicLink(File file) {
        try {
            StructStat stat = Os.lstat(file.getPath());
            //noinspection OctalInteger
            return (stat.st_mode & 0170000) == 0120000;
        } catch (ErrnoException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (file != null) {
            File target = new File(file.getParentFile(), displayName);
            if (file.renameTo(target)) {
                int i = documentId.lastIndexOf('/', documentId.length() - 2);
                return documentId.substring(0, i) + "/" + displayName;
            }
        }
        throw new FileNotFoundException("Failed to rename document " + documentId + " to " + displayName);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File sourceFile = getFileForDocId(sourceDocumentId);
        File targetDir = getFileForDocId(targetParentDocumentId);
        if (sourceFile != null && targetDir != null) {
            File targetFile = new File(targetDir, sourceFile.getName());
            // 【安全加固】防止移动到白名单之外的目录
            if (!isTargetPathAllowed(targetFile)) {
                throw new FileNotFoundException("Filed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
            }
            if (!targetFile.exists() && sourceFile.renameTo(targetFile)) {
                return targetParentDocumentId.endsWith("/") ? targetParentDocumentId + targetFile.getName() : targetParentDocumentId + "/" + targetFile.getName();
            }
        }
        throw new FileNotFoundException("Filed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return file == null ? Document.MIME_TYPE_DIR : getMimeType(file);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            final String name = file.getName();
            final int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                final String extension = name.substring(lastDot + 1).toLowerCase();
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
            return "application/octet-stream";
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = super.call(method, arg, extras);
        if (result != null) {
            return result;
        }
        if (!method.startsWith("mt:")) {
            return null;
        }
        Bundle out = new Bundle();
        try {
            Uri uri = extras.getParcelable("uri");
            List<String> pathSegments = uri.getPathSegments();
            String documentId = pathSegments.size() >= 4 ? pathSegments.get(3) : pathSegments.get(1);
            switch (method) {
                case METHOD_SET_LAST_MODIFIED: {
                    File file = getFileForDocId(documentId);
                    if (file == null) {
                        out.putBoolean("result", false);
                    } else {
                        long time = extras.getLong("time");
                        out.putBoolean("result", file.setLastModified(time));
                    }
                    break;
                }
                case METHOD_SET_PERMISSIONS: {
                    File file = getFileForDocId(documentId);
                    if (file == null) {
                        out.putBoolean("result", false);
                    } else {
                        int permissions = extras.getInt("permissions");
                        try {
                            Os.chmod(file.getPath(), permissions);
                            out.putBoolean("result", true);
                        } catch (ErrnoException e) {
                            out.putBoolean("result", false);
                            out.putString("message", e.getMessage());
                        }
                    }
                    break;
                }
                case METHOD_CREATE_SYMLINK: {
                    File file = getFileForDocId(documentId, false);
                    if (file == null) {
                        out.putBoolean("result", false);
                    } else {
                        String path = extras.getString("path");
                        try {
                            Os.symlink(path, file.getPath());
                            out.putBoolean("result", true);
                        } catch (ErrnoException e) {
                            out.putBoolean("result", false);
                            out.putString("message", e.getMessage());
                        }
                    }
                    break;
                }
                default:
                    out.putBoolean("result", false);
                    out.putString("message", "Unsupported method: " + method);
                    break;
            }
        } catch (Exception e) {
            out.putBoolean("result", false);
            out.putString("message", e.toString());
        }
        return out;
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     */
    @SuppressWarnings("OctalInteger")
    private void includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        if (file == null) {
            file = getFileForDocId(docId);
        }
        if (file == null) {
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(Document.COLUMN_DOCUMENT_ID, packageName);
            row.add(Document.COLUMN_DISPLAY_NAME, packageName);
            row.add(Document.COLUMN_SIZE, 0L);
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            row.add(Document.COLUMN_LAST_MODIFIED, 0);
            row.add(Document.COLUMN_FLAGS, 0);
            return;
        }

        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        //noinspection ConstantConditions
        if (file.getParentFile().canWrite()) {
            flags |= Document.FLAG_SUPPORTS_DELETE;
            flags |= Document.FLAG_SUPPORTS_RENAME;
        }
        String path = file.getPath();
        final String displayName;
        boolean addExtras = false;
        if (path.equals(dataDir.getPath())) {
            displayName = "data";
        } else if (androidDataDir != null && path.equals(androidDataDir.getPath())) {
            displayName = "android_data";
        } else if (androidObbDir != null && path.equals(androidObbDir.getPath())) {
            displayName = "android_obb";
        } else if (userDeDataDir != null && path.equals(userDeDataDir.getPath())) {
            displayName = "user_de_data";
        } else {
            displayName = file.getName();
            addExtras = true;
        }
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, getMimeType(file));
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(COLUMN_MT_PATH, file.getAbsolutePath());
        if (addExtras) {
            try {
                StringBuilder sb = new StringBuilder();
                StructStat stat = Os.lstat(path);
                sb.append(stat.st_mode)
                        .append("|").append(stat.st_uid)
                        .append("|").append(stat.st_gid);
                if ((stat.st_mode & 0170000) == 0120000) {
                    sb.append("|").append(Os.readlink(path));
                }
                row.add(COLUMN_MT_EXTRAS, sb.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
