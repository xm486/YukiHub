package com.yuki.yukihub.scanner;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 快速扫描器（新版）。
 *
 * 与 GameScanner 的扫描语义完全一致（特例顺序、递归深度、去重、internalAssetDir 跳过等），
 * 仅把"如何获取目录子项"从 DocumentFile 逐项 IPC 换成：
 *   - 一次 ContentResolver.query 批量取整目录子项（docId/name/mime）
 *   - 全扫描周期内存缓存，消除重复 listFiles
 *   - mime 判定文件/目录（mime 为 null 时按目录处理，对齐 androidx DocumentFile 语义）
 *
 * 兼容模式（旧版）仍走 GameScanner，本类只被"快速模式"调用。
 */
public class FastGameScanner {
    private static final String TAG = "FastGameScanner";
    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR; // vnd.android.document/directory

    public static List<ScanResult> scan(Context context, Uri rootUri, int maxDepth) {
        List<ScanResult> results = new ArrayList<>();
        Set<String> seenUris = new HashSet<>();
        if (context == null || rootUri == null) return results;
        int depth = Math.max(1, Math.min(4, maxDepth));

        String rootUriStr = rootUri.toString();
        if (!isDirectory(context, rootUriStr)) return results;

        ChildCache cache = new ChildCache(context, rootUri);
        scanChildren(rootUriStr, 1, depth, results, seenUris, cache);
        return results;
    }

    // ==================== 目录遍历 ====================

    private static void scanChildren(String dirUri, int level, int maxDepth,
                                     List<ScanResult> results, Set<String> seenUris, ChildCache cache) {
        List<EngineDetector.ChildInfo> children;
        try {
            children = cache.children(dirUri);
        } catch (Throwable t) {
            Log.w(TAG, "children failed uri=" + dirUri, t);
            return;
        }
        if (children == null || children.isEmpty()) return;

        for (EngineDetector.ChildInfo child : children) {
            if (child == null || child.uri == null || child.uri.isEmpty()) continue;
            try {
                String name = child.name == null ? "" : child.name;
                String lowerName = name.toLowerCase(Locale.ROOT);

                if (child.isFile) {
                    // 情况1：单个PSP文件在根目录
                    if (lowerName.endsWith(".iso") || lowerName.endsWith(".cso") || lowerName.endsWith(".chd") ||
                        lowerName.endsWith(".elf") || lowerName.endsWith(".pbp")) {
                        addPspFileResult(results, seenUris, child.uri, name);
                        continue;
                    }
                    if (lowerName.endsWith(".desktop")) {
                        addDesktopResult(results, seenUris, stripDesktopSuffix(name), child.uri, name, "");
                    }
                    continue;
                }
                if (!child.isDir) continue;

                // 情况2/3：优先检查子文件夹里的 PSP 文件。
                if (tryAddPspDirectory(child, cache, results, seenUris)) continue;
                // 情况2/3：优先检查子文件夹里的 desktop。
                if (tryAddDesktopDirectory(child, cache, results, seenUris)) continue;

                String childName = lowerName;
                boolean internalAssetDir = isInternalAssetDir(childName);

                if (!internalAssetDir) {
                    EngineDetector.Result detected = EngineDetector.detect(child.uri, 2, cache);
                    if (detected != null && detected.confidence > 0) {
                        String uri = child.uri;
                        if (markSeen(seenUris, uri)) {
                            results.add(new ScanResult(safeName(child), uri, detected.engine, detected.confidence, detected.launchTarget));
                        }
                        continue;
                    }
                }

                if (level < maxDepth) {
                    scanChildren(child.uri, level + 1, maxDepth, results, seenUris, cache);
                }
            } catch (Throwable t) {
                Log.w(TAG, "scan child failed uri=" + child.uri, t);
            }
        }
    }

    // ==================== PSP / desktop 处理（语义与 GameScanner 一致） ====================

    private static boolean tryAddDesktopDirectory(EngineDetector.ChildInfo dir, ChildCache cache, List<ScanResult> results, Set<String> seenUris) {
        if (dir == null || results == null) return false;
        try {
            List<EngineDetector.ChildInfo> files = cache.children(dir.uri);
            if (files == null || files.isEmpty()) return false;

            List<EngineDetector.ChildInfo> desktops = new ArrayList<>();
            for (EngineDetector.ChildInfo f : files) {
                if (f == null || !f.isFile) continue;
                String name = (f.name == null ? "" : f.name).toLowerCase(Locale.ROOT);
                if (name.endsWith(".desktop")) desktops.add(f);
            }
            if (desktops.isEmpty()) return false;

            String coverUri = "";
            EngineDetector.ChildInfo folderCover = findBestImageInDir(files);
            if (folderCover != null) coverUri = folderCover.uri;

            if (desktops.size() == 1) {
                EngineDetector.ChildInfo desktop = desktops.get(0);
                return addDesktopResult(results, seenUris, safeName(dir), desktop.uri, safeName(desktop), coverUri);
            }

            boolean added = false;
            for (EngineDetector.ChildInfo desktop : desktops) {
                String name = safeName(desktop);
                added |= addDesktopResult(results, seenUris, stripDesktopSuffix(name), desktop.uri, name, coverUri);
            }
            return added;
        } catch (Throwable t) {
            Log.w(TAG, "tryAddDesktopDirectory failed uri=" + dir.uri, t);
            return false;
        }
    }

    private static boolean tryAddPspDirectory(EngineDetector.ChildInfo dir, ChildCache cache, List<ScanResult> results, Set<String> seenUris) {
        if (dir == null || results == null) return false;
        try {
            List<EngineDetector.ChildInfo> files = cache.children(dir.uri);
            if (files == null || files.isEmpty()) return false;

            List<EngineDetector.ChildInfo> pspFiles = new ArrayList<>();
            for (EngineDetector.ChildInfo f : files) {
                if (f == null || !f.isFile) continue;
                String name = (f.name == null ? "" : f.name).toLowerCase(Locale.ROOT);
                if (name.endsWith(".iso") || name.endsWith(".cso") || name.endsWith(".chd") ||
                    name.endsWith(".elf") || name.endsWith(".pbp")) {
                    pspFiles.add(f);
                }
            }
            if (pspFiles.isEmpty()) return false;

            String coverUri = "";
            EngineDetector.ChildInfo folderCover = findBestImageInDir(files);
            if (folderCover != null) coverUri = folderCover.uri;

            if (pspFiles.size() == 1) {
                EngineDetector.ChildInfo pspFile = pspFiles.get(0);
                return addPspFileResultWithCover(results, seenUris, safeName(dir), pspFile.uri, safeName(pspFile), coverUri);
            }

            boolean added = false;
            for (EngineDetector.ChildInfo pspFile : pspFiles) {
                String name = safeName(pspFile);
                String title = name;
                int dotIndex = title.lastIndexOf('.');
                if (dotIndex > 0) title = title.substring(0, dotIndex);
                added |= addPspFileResultWithCover(results, seenUris, title, pspFile.uri, name, coverUri);
            }
            return added;
        } catch (Throwable t) {
            Log.w(TAG, "tryAddPspDirectory failed uri=" + dir.uri, t);
            return false;
        }
    }

    private static boolean addDesktopResult(List<ScanResult> results, Set<String> seenUris, String title, String resultUri, String launchTarget, String coverUri) {
        if (results == null || resultUri == null || !markSeen(seenUris, resultUri)) return false;
        results.add(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名游戏" : title,
                resultUri,
                com.yuki.yukihub.model.EngineType.WINLATOR,
                90,
                launchTarget,
                coverUri
        ));
        return true;
    }

    private static boolean addPspFileResult(List<ScanResult> results, Set<String> seenUris, String uri, String fileName) {
        if (results == null || uri == null || !markSeen(seenUris, uri)) return false;
        String title = fileName;
        int dotIndex = title.lastIndexOf('.');
        if (dotIndex > 0) title = title.substring(0, dotIndex);
        results.add(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名PSP游戏" : title,
                uri,
                com.yuki.yukihub.model.EngineType.PSP,
                95,
                fileName,
                ""
        ));
        return true;
    }

    private static boolean addPspFileResultWithCover(List<ScanResult> results, Set<String> seenUris, String title, String resultUri, String launchTarget, String coverUri) {
        if (results == null || resultUri == null || !markSeen(seenUris, resultUri)) return false;
        results.add(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名PSP游戏" : title,
                resultUri,
                com.yuki.yukihub.model.EngineType.PSP,
                95,
                launchTarget,
                coverUri
        ));
        return true;
    }

    private static EngineDetector.ChildInfo findBestImageInDir(List<EngineDetector.ChildInfo> files) {
        if (files == null) return null;
        EngineDetector.ChildInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (EngineDetector.ChildInfo f : files) {
            if (f == null || !f.isFile) continue;
            String name = f.name == null ? "" : f.name;
            if (!isImageFile(name)) continue;
            int score = coverNameScore(name);
            if (best == null || score > bestScore) {
                best = f;
                bestScore = score;
            }
        }
        return best;
    }

    // ==================== 工具 ====================

    private static boolean isDirectory(Context context, String uriStr) {
        try {
            if (uriStr.startsWith("content://")) {
                Uri uri = Uri.parse(uriStr);
                try (Cursor c = context.getContentResolver().query(uri,
                        new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String mime = c.getString(0);
                        return MIME_DIR.equals(mime);
                    }
                }
                return true; // 查询不到按目录处理（对齐 DocumentFile 语义）
            }
            String path = filePathFromUri(uriStr);
            if (path == null) return false;
            return new File(path).isDirectory();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 把 file:// 或 file:/ 形式的 uri 还原成本地路径。
     * 注意：File.toURI().toString() 生成的是单斜杠 "file:/path"，不能只用 startsWith("file://") 判断。
     */
    private static String filePathFromUri(String uriStr) {
        if (uriStr == null) return null;
        if (uriStr.startsWith("file://") || uriStr.startsWith("file:/")) {
            try {
                return Uri.parse(uriStr).getPath();
            } catch (Throwable t) {
                return uriStr.startsWith("file:") ? uriStr.substring("file:".length()) : uriStr;
            }
        }
        return uriStr;
    }

    private static boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private static int coverNameScore(String name) {
        if (name == null) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("cover.jpg") || lower.equals("cover.png") || lower.equals("cover.webp")) return 100;
        if (lower.equals("folder.jpg") || lower.equals("folder.png") || lower.equals("folder.webp")) return 95;
        if (lower.contains("cover") || lower.contains("folder") || lower.contains("封面")) return 80;
        if (lower.contains("poster") || lower.contains("package") || lower.contains("main")) return 60;
        return 10;
    }

    private static boolean isInternalAssetDir(String name) {
        if (name == null) return false;
        return name.equals("data") || name.equals("tyrano") || name.equals("resources") || name.equals("arc")
                || name.equals("scenario") || name.equals("system") || name.equals("bgimage") || name.equals("fgimage")
                || name.equals("image") || name.equals("sound") || name.equals("bgm") || name.equals("voice") || name.equals("video")
                || name.equals("movie") || name.equals("font") || name.equals("others") || name.equals("app");
    }

    private static String safeName(EngineDetector.ChildInfo file) {
        return file == null || file.name == null || file.name.trim().isEmpty() ? "未命名游戏" : file.name;
    }

    private static boolean markSeen(Set<String> seenUris, String uri) {
        if (seenUris == null) return true;
        String key = com.yuki.yukihub.data.GameRepository.normalizeRootUriKey(uri);
        if (key.isEmpty()) return true;
        return seenUris.add(key);
    }

    private static String stripDesktopSuffix(String name) {
        if (name == null) return "未命名游戏";
        return name.toLowerCase(Locale.ROOT).endsWith(".desktop") ? name.substring(0, Math.max(0, name.length() - 8)) : name;
    }

    // ==================== 子项批量获取 + 缓存 ====================

    private static class ChildCache implements EngineDetector.ChildProvider {
        private final Context context;
        private final Uri rootTreeUri;
        private final Map<String, List<EngineDetector.ChildInfo>> map = new HashMap<>();

        ChildCache(Context context, Uri rootTreeUri) {
            this.context = context;
            this.rootTreeUri = rootTreeUri;
        }

        @Override
        public List<EngineDetector.ChildInfo> children(String dirUri) {
            if (dirUri == null) return new ArrayList<>();
            String key = com.yuki.yukihub.data.GameRepository.normalizeRootUriKey(dirUri);
            if (map.containsKey(key)) return map.get(key);
            List<EngineDetector.ChildInfo> list = loadChildren(dirUri);
            map.put(key, list);
            return list;
        }

        private List<EngineDetector.ChildInfo> loadChildren(String dirUri) {
            List<EngineDetector.ChildInfo> out = new ArrayList<>();
            try {
                if (dirUri.startsWith("content://")) {
                    loadContentChildren(dirUri, out);
                } else {
                    loadFileChildren(dirUri, out);
                }
            } catch (Throwable t) {
                Log.w(TAG, "loadChildren failed uri=" + dirUri, t);
            }
            return out;
        }

        private void loadContentChildren(String dirUri, List<EngineDetector.ChildInfo> out) {
            // 取 dirUri 对应的 documentId
            String docId;
            try {
                docId = DocumentsContract.getDocumentId(Uri.parse(dirUri));
            } catch (Throwable t) {
                try {
                    docId = DocumentsContract.getTreeDocumentId(Uri.parse(dirUri));
                } catch (Throwable t2) {
                    Log.w(TAG, "cannot resolve docId uri=" + dirUri);
                    return;
                }
            }

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, docId);
            try (Cursor c = context.getContentResolver().query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    }, null, null, null)) {
                if (c == null) return;
                while (c.moveToNext()) {
                    String childDocId = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (childDocId == null) continue;
                    // 与 androidx TreeDocumentFile 完全一致：tree-style uri + mime 判定
                    // （mime 为 null 时按文件处理，isFile = !isDirectory，对齐 DocumentFile 语义）
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, childDocId);
                    boolean isDir = MIME_DIR.equals(mime);
                    out.add(new EngineDetector.ChildInfo(
                            childUri.toString(),
                            name == null ? "" : name,
                            mime,
                            isDir,
                            !isDir
                    ));
                }
            }
        }

        private void loadFileChildren(String dirUri, List<EngineDetector.ChildInfo> out) {
            String path = filePathFromUri(dirUri);
            if (path == null) return;
            File dir = new File(path);
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                boolean isDir = f.isDirectory();
                // 与 GameScanner（FileDocumentFile.getUri() = Uri.fromFile）保持一致的 uri 格式：file:///
                out.add(new EngineDetector.ChildInfo(
                        Uri.fromFile(f).toString(),
                        f.getName(),
                        "",
                        isDir,
                        !isDir
                ));
            }
        }
    }
}
