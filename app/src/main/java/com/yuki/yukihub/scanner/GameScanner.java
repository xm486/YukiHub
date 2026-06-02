package com.yuki.yukihub.scanner;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class GameScanner {
    private static final String TAG = "GameScanner";

    public static List<ScanResult> scan(Context context, Uri rootUri) {
        return scan(context, rootUri, 2);
    }

    public static List<ScanResult> scan(Context context, Uri rootUri, int maxDepth) {
        List<ScanResult> results = new ArrayList<>();
        Set<String> seenUris = new HashSet<>();
        if (context == null || rootUri == null) return results;
        int depth = Math.max(1, Math.min(4, maxDepth));

        DocumentFile root;
        try {
            root = DocumentFile.fromTreeUri(context, rootUri);
        } catch (Throwable t) {
            Log.w(TAG, "fromTreeUri failed uri=" + rootUri, t);
            return results;
        }
        if (root == null) return results;
        try {
            if (!root.isDirectory()) return results;
        } catch (Throwable t) {
            Log.w(TAG, "root isDirectory failed uri=" + rootUri, t);
            return results;
        }
        scanChildren(root, 1, depth, results, seenUris);
        return results;
    }

    private static void scanChildren(DocumentFile dir, int level, int maxDepth, List<ScanResult> results, Set<String> seenUris) {
        if (dir == null || results == null) return;
        DocumentFile[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "listFiles failed uri=" + safeUri(dir), t);
            return;
        }
        if (children == null) return;

        for (DocumentFile child : children) {
            if (child == null) continue;
            try {
                if (child.isFile()) {
                    String name = safeName(child);
                    if (name.toLowerCase(Locale.ROOT).endsWith(".desktop")) {
                        addDesktopResult(results, seenUris, stripDesktopSuffix(name), child.getUri().toString(), name, "");
                    }
                    continue;
                }
                if (!child.isDirectory()) continue;

                // 情况2/3：优先检查子文件夹里的 desktop。
                if (tryAddDesktopDirectory(child, results, seenUris)) continue;

                String childName = safeName(child).toLowerCase(Locale.ROOT);
                boolean internalAssetDir = isInternalAssetDir(childName);

                if (!internalAssetDir) {
                    EngineDetector.Result detected = EngineDetector.detect(child, 2);
                    if (detected != null && detected.confidence > 0) {
                        String uri = child.getUri().toString();
                        if (markSeen(seenUris, uri)) {
                            results.add(new ScanResult(safeName(child), uri, detected.engine, detected.confidence, detected.launchTarget));
                        }
                        continue;
                    }
                }

                if (level < maxDepth) {
                    scanChildren(child, level + 1, maxDepth, results, seenUris);
                }
            } catch (Throwable t) {
                Log.w(TAG, "scan child failed uri=" + safeUri(child), t);
            }
        }
    }

    private static boolean tryAddDesktopDirectory(DocumentFile dir, List<ScanResult> results, Set<String> seenUris) {
        if (dir == null || results == null) return false;
        try {
            DocumentFile[] files = dir.listFiles();
            if (files == null || files.length == 0) return false;

            List<DocumentFile> desktops = new ArrayList<>();
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = safeName(f).toLowerCase(Locale.ROOT);
                if (name.endsWith(".desktop")) desktops.add(f);
            }
            if (desktops.isEmpty()) return false;

            String coverUri = "";
            DocumentFile folderCover = findBestImageInDir(dir);
            if (folderCover != null) coverUri = folderCover.getUri().toString();

            if (desktops.size() == 1) {
                // 情况2：文件夹内只有一个 desktop，标题取文件夹名，但入口仍然是 .desktop 文件本身。
                DocumentFile desktop = desktops.get(0);
                return addDesktopResult(results, seenUris, safeName(dir), desktop.getUri().toString(), safeName(desktop), coverUri);
            }

            // 情况3：文件夹里有多个 desktop，按多个单独条目识别。
            boolean added = false;
            for (DocumentFile desktop : desktops) {
                String name = safeName(desktop);
                added |= addDesktopResult(results, seenUris, stripDesktopSuffix(name), desktop.getUri().toString(), name, coverUri);
            }
            return added;
        } catch (Throwable t) {
            Log.w(TAG, "tryAddDesktopDirectory failed uri=" + safeUri(dir), t);
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

    private static DocumentFile findBestImageInDir(DocumentFile dir) {
        if (dir == null || !dir.isDirectory()) return null;
        try {
            DocumentFile[] files = dir.listFiles();
            if (files == null) return null;
            DocumentFile best = null;
            int bestScore = Integer.MIN_VALUE;
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = safeName(f);
                if (!isImageFile(name)) continue;
                int score = coverNameScore(name);
                if (best == null || score > bestScore) {
                    best = f;
                    bestScore = score;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
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

    private static String safeName(DocumentFile file) {
        try {
            String name = file == null ? null : file.getName();
            return name == null || name.trim().isEmpty() ? "未命名游戏" : name;
        } catch (Throwable t) {
            Log.w(TAG, "safeName failed uri=" + safeUri(file), t);
            return "未命名游戏";
        }
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

    private static String safeUri(DocumentFile file) {
        try {
            return file == null || file.getUri() == null ? "null" : file.getUri().toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}