package com.yuki.yukihub.scanner;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

public class GameScanner {
    private static final String TAG = "GameScanner";

    public static List<ScanResult> scan(Context context, Uri rootUri) {
        List<ScanResult> results = new ArrayList<>();
        if (context == null || rootUri == null) return results;

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

        DocumentFile[] children;
        try {
            children = root.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "root listFiles failed uri=" + rootUri, t);
            return results;
        }
        if (children == null) return results;

        for (DocumentFile child : children) {
            if (child == null) continue;
            try {
                if (child.isFile()) {
                    String name = safeName(child);
                    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".desktop")) {
                        results.add(new ScanResult(stripDesktopSuffix(name), child.getUri().toString(), com.yuki.yukihub.model.EngineType.WINLATOR, 90, name));
                    }
                    continue;
                }
                if (!child.isDirectory()) continue;
                EngineDetector.Result detected = EngineDetector.detect(child);
                if (detected == null || detected.confidence <= 0) continue;
                results.add(new ScanResult(safeName(child), child.getUri().toString(), detected.engine, detected.confidence, detected.launchTarget));
            } catch (Throwable t) {
                Log.w(TAG, "scan child failed uri=" + safeUri(child), t);
            }
        }
        return results;
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

    private static String stripDesktopSuffix(String name) {
        if (name == null) return "未命名游戏";
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".desktop") ? name.substring(0, Math.max(0, name.length() - 8)) : name;
    }

    private static String safeUri(DocumentFile file) {
        try {
            return file == null || file.getUri() == null ? "null" : file.getUri().toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}