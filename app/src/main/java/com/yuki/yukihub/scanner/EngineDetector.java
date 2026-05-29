package com.yuki.yukihub.scanner;

import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fast, non-recursive engine detector.
 *
 * 旧扫描器会递归进入每个游戏目录，KR/Kirikiri 游戏资源非常多，SAF 查询会很慢，
 * 某些 DocumentFile 还会因为权限边界抛 SecurityException。
 * 现在改成“首层特征存在即添加”：只看候选游戏目录第一层文件/目录名。
 */
public class EngineDetector {
    private static final String TAG = "EngineDetector";

    public static class Result {
        public EngineType engine = EngineType.UNKNOWN;
        public int confidence = 0;
        public String launchTarget = "";
    }

    public static Result detect(DocumentFile dir) {
        Result r = new Result();
        if (dir == null) return r;

        DocumentFile[] files;
        try {
            if (!dir.isDirectory()) return r;
            files = dir.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "detect list failed uri=" + safeUri(dir), t);
            return r;
        }
        if (files == null || files.length == 0) return r;

        Set<String> names = new HashSet<>();
        String firstXp3 = null;
        String firstDesktop = null;
        boolean hasIndex = false;
        boolean hasTyranoDir = false;
        boolean hasDataDir = false;
        boolean hasStartupTjs = false;
        boolean hasConfigTjs = false;
        boolean hasOnsScript = false;
        boolean hasOnsArchive = false;
        boolean hasPfs = false;

        for (DocumentFile f : files) {
            if (f == null) continue;
            String name = safeLowerName(f);
            if (name.length() == 0) continue;
            names.add(name);

            boolean directory = false;
            boolean file = false;
            try { directory = f.isDirectory(); } catch (Throwable ignored) { }
            try { file = f.isFile(); } catch (Throwable ignored) { }

            if (directory) {
                if (name.equals("tyrano")) hasTyranoDir = true;
                if (name.equals("data")) hasDataDir = true;
                continue;
            }
            if (!file) continue;

            if (name.equals("index.html") || name.equals("index.htm")) hasIndex = true;
            if (name.equals("startup.tjs")) hasStartupTjs = true;
            if (name.equals("config.tjs")) hasConfigTjs = true;
            if (name.equals("0.txt") || name.equals("00.txt") || name.equals("nscript.dat")) hasOnsScript = true;
            if (name.endsWith(".nsa") || name.endsWith(".sar")) hasOnsArchive = true;
            if (name.endsWith(".pfs")) hasPfs = true;
            if (name.endsWith(".desktop") && firstDesktop == null) firstDesktop = safeName(f);
            if (name.endsWith(".xp3")) {
                if (name.equals("data.xp3")) firstXp3 = "data.xp3";
                else if (firstXp3 == null) firstXp3 = safeName(f);
            }
        }

        // 优先级：明确 Web/Tyrano > Artemis > KRKR > ONS > Winlator shortcut。
// .desktop 通常是 Winlator/类 Winlator 生成的快捷方式，自动归类为 WINLATOR。
        if (hasIndex && (hasTyranoDir || hasDataDir || names.contains("tyrano.css") || names.contains("tyrano.base.js"))) {
            score(r, EngineType.TYRANO, 95, "[游戏目录]");
        } else if (hasIndex) {
            score(r, EngineType.TYRANO, 80, "[游戏目录]");
        } else if (hasPfs) {
            score(r, EngineType.ARTEMIS, 95, "[游戏目录]");
        } else if (firstXp3 != null || hasStartupTjs || hasConfigTjs) {
            score(r, EngineType.KIRIKIRI, firstXp3 != null ? 95 : 80, firstXp3 != null ? firstXp3 : "[游戏目录]");
        } else if (hasOnsScript || hasOnsArchive) {
            score(r, EngineType.ONS, hasOnsScript ? 90 : 70, "[游戏目录]");
        } else if (firstDesktop != null) {
            score(r, EngineType.WINLATOR, 90, firstDesktop);
        }
        return r;
    }

    private static String safeName(DocumentFile file) {
        try {
            String name = file == null ? null : file.getName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            Log.w(TAG, "getName failed uri=" + safeUri(file), t);
            return "";
        }
    }

    private static String safeLowerName(DocumentFile file) {
        String name = safeName(file);
        return name.length() == 0 ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String safeUri(DocumentFile file) {
        try {
            return file == null || file.getUri() == null ? "null" : file.getUri().toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void score(Result r, EngineType engine, int confidence, String launchTarget) {
        if (r == null) return;
        if (confidence > r.confidence) {
            r.engine = engine;
            r.confidence = confidence;
            r.launchTarget = launchTarget == null ? "" : launchTarget;
        }
    }
}