package com.yuki.yukihub.scanner;

import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight engine detector.
 *
 * 扫描器会控制“游戏目录搜索深度”，这里控制“候选游戏目录内部的特征探测深度”。
 * 为了兼容 Tyrano/Electron 壳包装游戏，默认允许查看候选目录下较浅层级的特征，
 * 例如 resources/app.asar、resources/app/package.json、tyrano/、data/ 等。
 */
public class EngineDetector {
    private static final String TAG = "EngineDetector";

    public static class Result {
        public EngineType engine = EngineType.UNKNOWN;
        public int confidence = 0;
        public String launchTarget = "";
    }

    public static Result detect(DocumentFile dir) {
        return detect(dir, 2);
    }

    public static Result detect(DocumentFile dir, int featureDepth) {
        Result r = new Result();
        if (dir == null) return r;
        int depth = Math.max(1, Math.min(4, featureDepth));

        FeatureState s = new FeatureState();
        collectFeatures(dir, "", 1, depth, s);
        if (s.empty) return r;

        // 只对 Artemis 使用原 Tyranor 的判定：
        // system.ini + system/first.iet、root.pfs、或目录内任意 .pfs 都视为 Artemis。
        boolean tyranoRuntime = s.hasTyranoDir || s.hasDataDir || s.names.contains("tyrano.css") || s.names.contains("tyrano.base.js")
                || s.relativeNames.contains("tyrano/tyrano.css") || s.relativeNames.contains("tyrano/tyrano.base.js")
                || s.relativeNames.contains("tyrano/libs/jquery-3.6.0.min.js") || s.relativeNames.contains("tyrano/libs/jquery-2.0.3.min.js");
        boolean electronWrapper = s.hasResourcesDir && (s.hasAppAsar || s.hasElectronPak || s.names.contains("icudtl.dat") || s.names.contains("libegl.dll") || s.names.contains("libglesv2.dll"));
        boolean artemisRuntime = (s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs || s.hasAnyPfsFile;

        if (s.hasIndex && tyranoRuntime) {
            score(r, EngineType.TYRANO, 96, "[游戏目录]");
        } else if (s.hasAppAsar && (s.hasPackageJson || electronWrapper)) {
            score(r, EngineType.TYRANO, 72, "[游戏目录]");
        } else if (s.hasIndex && !electronWrapper) {
            score(r, EngineType.TYRANO, 70, "[游戏目录]");
        } else if (artemisRuntime) {
            score(r, EngineType.ARTEMIS, (s.hasSystemIni && s.hasFirstIet) || s.hasRootPfs ? 95 : 90, "[游戏目录]");
        } else if (s.firstXp3 != null || s.hasStartupTjs || s.hasConfigTjs) {
            score(r, EngineType.KIRIKIRI, s.firstXp3 != null ? 95 : 80, s.firstXp3 != null ? s.firstXp3 : "[游戏目录]");
        } else if (s.hasOnsScript || s.hasOnsArchive) {
            score(r, EngineType.ONS, s.hasOnsScript ? 90 : 70, "[游戏目录]");
        } else if (s.firstDesktop != null) {
            score(r, EngineType.WINLATOR, 90, s.firstDesktop);
        } else if (s.firstPspFile != null) {
            score(r, EngineType.PSP, 95, s.firstPspFile);
        }
        return r;
    }

    private static class FeatureState {
        boolean empty = true;
        Set<String> names = new HashSet<>();
        Set<String> relativeNames = new HashSet<>();
        String firstXp3 = null;
        String firstDesktop = null;
        boolean hasIndex = false;
        boolean hasTyranoDir = false;
        boolean hasDataDir = false;
        boolean hasResourcesDir = false;
        boolean hasScenarioDir = false;
        boolean hasSystemDir = false;
        boolean hasBgimageDir = false;
        boolean hasFgimageDir = false;
        boolean hasImageDir = false;
        boolean hasSoundDir = false;
        boolean hasBgmDir = false;
        boolean hasVoiceDir = false;
        boolean hasVideoDir = false;
        boolean hasMovieDir = false;
        boolean hasFontDir = false;
        boolean hasOthersDir = false;
        boolean hasStartupTjs = false;
        boolean hasConfigTjs = false;
        boolean hasKsScript = false;
        boolean hasTjsScript = false;
        boolean hasSystemIni = false;
        boolean hasFirstIet = false;
        boolean hasRootPfs = false;
        boolean hasAnyPfsFile = false;
        boolean hasOnsScript = false;
        boolean hasOnsArchive = false;
        boolean hasPfs = false;
        boolean hasAppAsar = false;
        boolean hasPackageJson = false;
        boolean hasElectronPak = false;
        String firstPspFile = null;
    }

    private static void collectFeatures(DocumentFile dir, String prefix, int level, int maxLevel, FeatureState s) {
        DocumentFile[] files;
        try {
            if (dir == null || !dir.isDirectory()) return;
            files = dir.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "detect list failed uri=" + safeUri(dir), t);
            return;
        }
        if (files == null || files.length == 0) return;

        for (DocumentFile f : files) {
            if (f == null) continue;
            String lower = safeLowerName(f);
            String original = safeName(f);
            if (lower.length() == 0) continue;
            String rel = prefix.length() == 0 ? lower : prefix + "/" + lower;
            s.empty = false;
            s.names.add(lower);
            s.relativeNames.add(rel);

            boolean directory = false;
            boolean file = false;
            try { directory = f.isDirectory(); } catch (Throwable ignored) { }
            try { file = f.isFile(); } catch (Throwable ignored) { }

            if (directory) {
                if (lower.equals("tyrano")) s.hasTyranoDir = true;
                if (lower.equals("data")) s.hasDataDir = true;
                if (lower.equals("resources")) s.hasResourcesDir = true;
                if (lower.equals("scenario")) s.hasScenarioDir = true;
                if (lower.equals("system")) s.hasSystemDir = true;
                if (lower.equals("bgimage")) s.hasBgimageDir = true;
                if (lower.equals("fgimage")) s.hasFgimageDir = true;
                if (lower.equals("image")) s.hasImageDir = true;
                if (lower.equals("sound")) s.hasSoundDir = true;
                if (lower.equals("bgm")) s.hasBgmDir = true;
                if (lower.equals("voice")) s.hasVoiceDir = true;
                if (lower.equals("video")) s.hasVideoDir = true;
                if (lower.equals("movie")) s.hasMovieDir = true;
                if (lower.equals("font")) s.hasFontDir = true;
                if (lower.equals("others")) s.hasOthersDir = true;
                if (level < maxLevel && shouldDescendForFeature(lower)) collectFeatures(f, rel, level + 1, maxLevel, s);
                continue;
            }
            if (!file) continue;

            if (lower.equals("index.html") || lower.equals("index.htm")) s.hasIndex = true;
            if (lower.equals("startup.tjs")) s.hasStartupTjs = true;
            if (lower.equals("config.tjs")) s.hasConfigTjs = true;
            if (lower.equals("system.ini")) s.hasSystemIni = true;
            if (rel.equals("system/first.iet") || rel.endsWith("/system/first.iet")) s.hasFirstIet = true;
            if (lower.equals("root.pfs")) s.hasRootPfs = true;
            if (lower.endsWith(".pfs")) s.hasAnyPfsFile = true;
            if (lower.endsWith(".ks")) s.hasKsScript = true;
            if (lower.endsWith(".tjs") && !lower.equals("startup.tjs") && !lower.equals("config.tjs")) s.hasTjsScript = true;
            if (lower.equals("0.txt") || lower.equals("00.txt") || lower.equals("nscr_sec.dat") || lower.equals("nscript.dat") || lower.equals("onscript.nt2") || lower.equals("onscript.nt3")) s.hasOnsScript = true;
            if (lower.endsWith(".nsa") || lower.endsWith(".sar")) s.hasOnsArchive = true;
            if (lower.endsWith(".pfs")) { s.hasPfs = true; s.hasAnyPfsFile = true; }
            if (lower.equals("app.asar") || rel.endsWith("/app.asar")) s.hasAppAsar = true;
            if (lower.equals("package.json") || rel.endsWith("/package.json")) s.hasPackageJson = true;
            if (lower.startsWith("chrome_") && lower.endsWith(".pak")) s.hasElectronPak = true;
            if (lower.endsWith(".desktop") && s.firstDesktop == null) s.firstDesktop = original;
            if (lower.endsWith(".xp3")) {
                if (lower.equals("data.xp3")) s.firstXp3 = rel.contains("/") ? rel : "data.xp3";
                else if (s.firstXp3 == null) s.firstXp3 = rel.contains("/") ? rel : original;
            }
            // PSP游戏文件检测
            if (lower.endsWith(".iso") || lower.endsWith(".cso") || lower.endsWith(".chd") || 
                lower.endsWith(".elf") || lower.endsWith(".pbp")) {
                if (s.firstPspFile == null) s.firstPspFile = original;
            }
        }
    }

    private static boolean shouldDescendForFeature(String lowerName) {
        if (lowerName == null) return false;
        return lowerName.equals("resources") || lowerName.equals("app") || lowerName.equals("tyrano") || lowerName.equals("data") || lowerName.equals("scenario") || lowerName.equals("system");
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