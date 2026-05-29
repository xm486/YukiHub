package com.yuki.yukihub.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.yuki.yukihub.ons.OnsSettings;

public class EmulatorLauncher {
    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget) {
        return launchGame(context, packageName, rootUri, launchTarget, "game", null);
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget, String winlatorLaunchMode) {
        return launchGame(context, packageName, rootUri, launchTarget, winlatorLaunchMode, "game", null);
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget, String winlatorLaunchMode, String gamehubLocalGameId) {
        return launchGame(context, packageName, rootUri, launchTarget, winlatorLaunchMode, "game", gamehubLocalGameId);
    }

    public static boolean launchGame(Context context, String packageName, String rootUri, String launchTarget, String winlatorLaunchMode, String gamehubLaunchMode, String gamehubLocalGameId) {
if (packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim();
        if ("internal.krkr".equalsIgnoreCase(pkg) || "org.tvp.kirikiri2.internal".equalsIgnoreCase(pkg)) {
            try {
                context.startActivity(buildInternalKrkrIntent(context, rootUri, launchTarget, false));
                return true;
            } catch (Exception ignored) { }
            return false;
        }
        if ("internal.tyrano".equalsIgnoreCase(pkg) || "com.yuki.yukihub.tyrano".equalsIgnoreCase(pkg)) {
            try {
                context.startActivity(buildInternalTyranoIntent(context, rootUri, launchTarget));
                return true;
            } catch (Exception ignored) { }
            return false;
        }
        if ("internal.ons".equalsIgnoreCase(pkg) || "internal.onscripter".equalsIgnoreCase(pkg) || "com.yuki.yukihub.ons".equalsIgnoreCase(pkg)) {
            try {
                context.startActivity(buildInternalOnsIntent(context, rootUri, launchTarget));
                return true;
            } catch (Exception ignored) { }
            return false;
        }
        if ("internal.artemis".equalsIgnoreCase(pkg) || "com.yuki.yukihub.artemis".equalsIgnoreCase(pkg)
                || "internal.artemis.compat".equalsIgnoreCase(pkg) || "internal.artemis.compatible".equalsIgnoreCase(pkg)
                || "internal.artemis.compat.v2".equalsIgnoreCase(pkg) || "internal.artemis.compatible.v2".equalsIgnoreCase(pkg)) {
            try {
                context.startActivity(buildInternalArtemisIntent(context, pkg, rootUri, launchTarget));
                return true;
            } catch (Exception ignored) { }
            return false;
        }
        if (isGameHubPackage(pkg)) {
            String mode = gamehubLaunchMode == null ? "game" : gamehubLaunchMode.trim().toLowerCase(Locale.ROOT);
            if ("program".equals(mode) || "normal".equals(mode)) {
                return launch(context, pkg);
            }
            if (gamehubLocalGameId != null && !gamehubLocalGameId.trim().isEmpty()) {
                String gid = gamehubLocalGameId.trim();
                try {
                    context.startActivity(buildGameHubDetailIntent(pkg, gid, guessGameHubAppName(launchTarget)));
                    return true;
                } catch (Exception ignored) { }
                try {
                    context.startActivity(buildGameHubRouterIntent(pkg, gid, guessGameHubAppName(launchTarget)));
                    return true;
                } catch (Exception ignored) { }
            }
            return false;
        }
        if (isWinlatorPackage(pkg) && isDesktopTarget(launchTarget)) {
return launchWinlatorDesktop(context, pkg, rootUri, launchTarget, winlatorLaunchMode);
}
if (rootUri != null && !rootUri.trim().isEmpty()) {
            List<Uri> launchUris = buildKirikiriLaunchUris(context, rootUri, launchTarget);
            for (Uri uri : launchUris) {
                Intent[] intents = buildLaunchIntents(pkg, uri, rootUri, launchTarget);
                for (Intent intent : intents) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    try {
                        context.startActivity(intent);
                        return true;
                    } catch (Exception ignored) { }
                }
            }
        }
        return launch(context, pkg);
    }

    private static Intent[] buildLaunchIntents(String pkg, Uri uri, String rootUri, String launchTarget) {
        String uriText = uri.toString();
        String rootText = rootUri == null ? uriText : rootUri;
        String target = launchTarget == null ? "" : launchTarget;
        if ("com.akira.tyranoemu".equals(pkg)) {
            return new Intent[]{
                    // Tyranor 2.3.4 exposes this action for web/Tyrano games.
                    explicit("com.akira.tyranoemu", "com.akira.tyranoemu.remote.WebActivity", "android.intent.action.WebGame", uri)
                            .putExtra("path", uriText).putExtra("uri", uriText).putExtra("projectRoot", rootText)
                            .putExtra("launchFile", target).putExtra("filename", target).putExtra("game", uriText)
                            .putExtra("gamedir", rootText).putExtra("gamename", guessName(target, rootText)).putExtra("gametitle", guessName(target, rootText)).putExtra("gameargs", target),
                    // Some builds may route KR2 through the launcher activity.
                    explicit("com.akira.tyranoemu", "com.akira.tyranoemu.app.TyActivity", Intent.ACTION_MAIN, uri)
                            .putExtra("path", uriText).putExtra("uri", uriText).putExtra("projectRoot", rootText)
                            .putExtra("launchFile", target).putExtra("filename", target).putExtra("game", uriText)
                            .putExtra("gamedir", rootText).putExtra("gamename", guessName(target, rootText)).putExtra("gametitle", guessName(target, rootText)).putExtra("gameargs", target),
                    new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "text/html")
                            .putExtra("path", uriText).putExtra("projectRoot", rootText).putExtra("launchFile", target).putExtra("gameargs", target),
                    new Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri)
                            .putExtra("path", uriText).putExtra("projectRoot", rootText).putExtra("launchFile", target).putExtra("gameargs", target)
            };
        }
        return new Intent[]{
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/x-kirikiri"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/octet-stream"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "resource/folder"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "inode/directory"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/x-directory"),
                new Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri),
                new Intent(Intent.ACTION_MAIN).setPackage(pkg)
                        .putExtra("path", uriText)
                        .putExtra("uri", uriText)
                        .putExtra("game", uriText)
                        .putExtra("startup", uriText)
                        .putExtra("projectRoot", rootText)
                        .putExtra("launchFile", target)
        };
    }

    private static String guessName(String target, String rootText) {
        if (target != null && !target.trim().isEmpty() && !"[游戏目录]".equals(target)) return target;
        if (rootText == null || rootText.isEmpty()) return "YukiHubGame";
        int slash = Math.max(rootText.lastIndexOf('/'), rootText.lastIndexOf('%'));
        return slash >= 0 && slash + 1 < rootText.length() ? rootText.substring(slash + 1) : "YukiHubGame";
    }

    private static Intent explicit(String pkg, String cls, String action, Uri uri) {
        Intent i = new Intent(action);
        i.setClassName(pkg, cls);
        if (uri != null) i.setData(uri);
        return i;
    }

    private static boolean isWinlatorPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("winlator") || p.contains("glibc") || p.contains("proot") || p.contains("mobox") || p.contains("winalator");
    }

    private static boolean isDesktopTarget(String launchTarget) {
        return launchTarget != null && launchTarget.trim().toLowerCase(Locale.ROOT).endsWith(".desktop");
    }

    private static boolean launchWinlatorDesktop(Context context, String pkg, String rootUri, String launchTarget, String mode) {
        String desktopPath = resolveDesktopPath(context, rootUri, launchTarget);
        if (desktopPath == null || desktopPath.trim().isEmpty()) return false;
        int containerId = parseWinlatorContainerId(desktopPath);
        String execPath = resolveWinlatorExecPathFromDesktop(desktopPath);
        boolean legacyRootfsShortcut = isWinlatorLegacyRootfsShortcut(desktopPath);
        // WinlatorCN/官版受限 Activity 强制启动需要 container_id；解析不到时默认使用第一个容器。
        if (containerId <= 0 && shouldUseShellWinlatorLaunch(pkg)) containerId = 1;
        PackageManager pm = context.getPackageManager();
        String launchMode = mode == null ? "game" : mode.trim().toLowerCase(Locale.ROOT);
        if ("program".equals(launchMode) || "normal".equals(launchMode)) return launch(context, pkg);
        List<Intent> intents = new ArrayList<>();

        // 官版 v11 源码：Shortcut 直启需要 container_id + shortcut_path。
        // 旧版/rootfs 模式可能没有 container_id，优先尝试直接把 shortcut_path 交给 XServerDisplayActivity/MainActivity。
        if ("com.winlator".equalsIgnoreCase(pkg)) {
            intents.add(addWinlatorExtras(explicit(pkg, "com.winlator.XServerDisplayActivity", Intent.ACTION_MAIN, null), desktopPath, execPath, containerId));
            if (containerId > 0) intents.add(addWinlatorExtras(explicit(pkg, "com.winlator.XrActivity", Intent.ACTION_MAIN, null), desktopPath, execPath, containerId));
        }

        intents.add(addWinlatorExtras(new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(Uri.fromFile(new File(desktopPath)), "application/x-desktop"), desktopPath, containerId));

        Intent normal = pm.getLaunchIntentForPackage(pkg);
        if (normal != null) intents.add(addWinlatorExtras(normal, desktopPath, containerId));

        intents.add(addWinlatorExtras(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), desktopPath, containerId));
        intents.add(addWinlatorExtras(explicit(pkg, pkg + ".MainActivity", Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), desktopPath, containerId));
        intents.add(addWinlatorExtras(explicit(pkg, pkg + ".activities.MainActivity", Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), desktopPath, containerId));

        for (Intent i : intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(i);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean shouldUseShellWinlatorLaunch(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        // WinlatorCN/官版/多数改版包名仍是 com.winlator 或包含 winlator。
        return p.equals("com.winlator") || p.startsWith("com.winlator.") || p.contains("winlator");
    }

    private static boolean isGameHubPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        return "com.xiaoji.egggame".equals(p) || "com.xiaoji.egggamz".equals(p);
    }

    private static Intent buildGameHubDetailIntent(String pkg, String localGameId, String appName) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(pkg);
        i.setClassName(pkg, "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity");
        String storedId = localGameId == null ? "" : localGameId.trim();
        boolean isSteam = storedId.toLowerCase(Locale.ROOT).startsWith("steam:");
        String steamAppId = isSteam ? storedId.substring("steam:".length()).trim() : "";
        String realLocalGameId = isSteam ? "" : storedId;
        i.putExtra("gameType", 0);
        i.putExtra("steamAppId", steamAppId);
        i.putExtra("id", 0);
        i.putExtra("type", 1);
        i.putExtra("localMobileAppId", "");
        i.putExtra("localGameId", realLocalGameId);
        i.putExtra("autoStartGame", true);
        i.putExtra("localPkg", "");
        i.putExtra("localAppName", appName == null || appName.trim().isEmpty() ? storedId : appName.trim());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static Intent buildGameHubRouterIntent(String pkg, String localGameId, String appName) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(pkg);
        i.setClassName(pkg, "com.xj.app.DeepLinkRouterActivity");
        String storedId = localGameId == null ? "" : localGameId.trim();
        boolean isSteam = storedId.toLowerCase(Locale.ROOT).startsWith("steam:");
        String steamAppId = isSteam ? storedId.substring("steam:".length()).trim() : "";
        String realLocalGameId = isSteam ? "" : storedId;
        i.putExtra("gameType", 0);
        i.putExtra("steamAppId", steamAppId);
        i.putExtra("id", 0);
        i.putExtra("type", 1);
        i.putExtra("localMobileAppId", "");
        i.putExtra("localGameId", realLocalGameId);
        i.putExtra("autoStartGame", true);
        i.putExtra("localPkg", "");
        i.putExtra("localAppName", appName == null || appName.trim().isEmpty() ? storedId : appName.trim());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static boolean startGameHubPinnedShortcut(Context context, String pkg, String localGameId) {
        try {
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps == null) return false;
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(pkg);
            query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, android.os.Process.myUserHandle());
            if (shortcuts == null || shortcuts.isEmpty()) return false;
            for (ShortcutInfo si : shortcuts) {
                if (si == null) continue;
                String shortcutGameId = extractGameHubLocalGameId(si);
                if (shortcutGameId == null || !localGameId.equalsIgnoreCase(shortcutGameId)) continue;
                try {
                    Bundle opts = null;
                    launcherApps.startShortcut(pkg, si.getId(), null, opts, android.os.Process.myUserHandle());
                    return true;
                } catch (Throwable t) {
                    Log.w("YukiHub", "startShortcut failed for GameHub", t);
                }
            }
        } catch (Throwable t) {
            Log.w("YukiHub", "startGameHubPinnedShortcut failed", t);
        }
        return false;
    }

    private static String extractGameHubLocalGameId(ShortcutInfo si) {
        if (si == null) return null;
        try {
            Intent[] intents = si.getIntents();
            if (intents != null) {
                for (int i = intents.length - 1; i >= 0; i--) {
                    Intent intent = intents[i];
                    if (intent == null) continue;
                    Bundle extras = intent.getExtras();
                    if (extras != null) {
                        String id = extras.getString("localGameId");
                        if (id != null && !id.trim().isEmpty()) return id.trim();
                    }
                }
            }
        } catch (Throwable ignored) { }
        try {
            Bundle extras = si.getExtras() != null ? new Bundle(si.getExtras()) : null;
            if (extras != null) {
                String id = extras.getString("localGameId");
                if (id != null && !id.trim().isEmpty()) return id.trim();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String guessGameHubAppName(String launchTarget) {
        if (launchTarget == null) return "";
        String t = launchTarget.trim();
        if (t.isEmpty() || t.startsWith("[")) return "";
        return t;
    }
    private static Intent addWinlatorExtras(Intent i, String desktopPath, String execPath, int containerId) {
        String actualExecPath = (execPath == null || execPath.trim().isEmpty()) ? desktopPath : execPath;
        String startPath = dirname(actualExecPath);
        addWinlatorExtras(i, desktopPath, containerId);
        i.putExtra("exec_path", actualExecPath);
        i.putExtra("path", actualExecPath);
        if (startPath != null) i.putExtra("start_path", startPath);
        return i;
    }


    private static Intent addWinlatorExtras(Intent i, String desktopPath, int containerId) {
        // 官版 Winlator 直启关键参数。
        if (containerId > 0) i.putExtra("container_id", containerId);
        i.putExtra("shortcut_path", desktopPath);
        // 其余键仅用于兼容部分改版，不影响官版。
        i.putExtra("desktop_path", desktopPath);
        i.putExtra("path", desktopPath);
        i.putExtra("file", desktopPath);
        i.putExtra("rom", desktopPath);
        return i;
    }

    private static String resolveWinlatorExecPathFromDesktop(String desktopPath) {
        try {
            File f = new File(desktopPath);
            if (!f.isFile()) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String line;
            String exec = null;
            String path = null;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("Exec=")) exec = t.substring(5).trim();
                else if (t.startsWith("Path=")) path = t.substring(5).trim();
            }
            try { br.close(); } catch (Throwable ignored) { }
            if (exec == null || exec.isEmpty()) return null;
            String exe = extractExeFromDesktopExec(exec);
            if (exe == null || exe.isEmpty()) return null;
            exe = exe.replace('\\', '/');
            if (exe.matches("^[A-Za-z]:/.*")) {
                if (path != null && !path.trim().isEmpty()) {
                    String fileName = exe.substring(exe.lastIndexOf('/') + 1);
                    String unixPath = path.replace('\\', '/');
                    return unixPath + (unixPath.endsWith("/") ? "" : "/") + fileName;
                }
                char drive = Character.toLowerCase(exe.charAt(0));
                return "/data/user/0/com.winlator/files/rootfs/home/xuser/.wine/dosdevices/" + drive + ":" + exe.substring(2);
            }
            return exe;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractExeFromDesktopExec(String exec) {
        if (exec == null) return null;
        String s = exec.trim();
        int wineIdx = s.toLowerCase(Locale.ROOT).lastIndexOf("wine ");
        if (wineIdx >= 0) s = s.substring(wineIdx + 5).trim();
        if (s.startsWith("\"")) {
            int end = s.indexOf('"', 1);
            if (end > 1) return s.substring(1, end);
        }
        int exeIdx = s.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (exeIdx >= 0) return s.substring(0, exeIdx + 4).trim();
        return s;
    }

    private static String dirname(String path) {
        if (path == null) return null;
        int idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : null;
    }

    private static boolean isWinlatorLegacyRootfsShortcut(String desktopPath) {
        if (desktopPath == null || desktopPath.trim().isEmpty()) return false;
        String p = desktopPath.replace('\\', '/');
        if (p.contains("/files/rootfs/home/xuser/")) return true;
        try {
            File f = new File(desktopPath);
            if (!f.isFile() || f.length() > 1024 * 1024) return false;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count++ < 80) {
                    if (line.contains("/files/rootfs/home/xuser/") || line.contains("/files/rootfs/")) return true;
                }
            } finally {
                try { br.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static int parseWinlatorContainerId(String desktopPath) {
        if (desktopPath == null) return 0;
        String marker = "/xuser-";
        int idx = desktopPath.indexOf(marker);
        if (idx < 0) {
            marker = "xuser-";
            idx = desktopPath.indexOf(marker);
            if (idx < 0) return 0;
        }
        int start = idx + marker.length();
        int end = start;
        while (end < desktopPath.length() && Character.isDigit(desktopPath.charAt(end))) end++;
        if (end <= start) return 0;
        try { return Integer.parseInt(desktopPath.substring(start, end)); } catch (Throwable ignored) { return 0; }
    }

    private static String resolveDesktopPath(Context context, String rootUri, String launchTarget) {
        String target = launchTarget == null ? "" : launchTarget.trim();
        if (target.startsWith("/") || target.startsWith("file://")) return stripFileScheme(target);
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.trim().isEmpty()) return target;
        if (rootPath.toLowerCase(Locale.ROOT).endsWith(".desktop")) return stripFileScheme(rootPath);
        if (rootPath.startsWith("content://")) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri));
                DocumentFile child = dir == null ? null : dir.findFile(target);
                if (child != null) {
                    String childPath = uriToFilePath(child.getUri().toString());
                    if (childPath != null && !childPath.startsWith("content://")) return childPath;
                }
            } catch (Throwable ignored) { }
            return rootPath;
        }
        return rootPath.endsWith("/") ? rootPath + target : rootPath + "/" + target;
    }

    private static List<Uri> buildKirikiriLaunchUris(Context context, String rootUri, String launchTarget) {
        List<Uri> uris = new ArrayList<>();
        Uri root = Uri.parse(rootUri);
        DocumentFile dir = DocumentFile.fromTreeUri(context, root);
        String target = launchTarget == null || launchTarget.isEmpty() ? "data.xp3" : launchTarget;
        if (dir != null && dir.isDirectory()) {
            if ("[游戏目录]".equals(target) || "DIR".equalsIgnoreCase(target)) {
                uris.add(root);
            } else if ("XP3_FIRST".equalsIgnoreCase(target)) {
                addFirstXp3(uris, dir);
            } else {
                addChildIfExists(uris, dir, target);
            }
            if (!uris.contains(root)) uris.add(root);
        }

        if (!uris.contains(root)) uris.add(root);
        return uris;
    }

    private static void addFirstXp3(List<Uri> uris, DocumentFile dir) {
        DocumentFile[] files = dir.listFiles();
        if (files == null) return;
        for (DocumentFile file : files) {
            String name = file.getName() == null ? "" : file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".xp3")) {
                Uri u = file.getUri();
                if (!uris.contains(u)) uris.add(u);
                return;
            }
        }
    }

    private static void addChildIfExists(List<Uri> uris, DocumentFile dir, String name) {
        DocumentFile child = dir.findFile(name);
        if (child != null && child.exists() && child.isFile() && !uris.contains(child.getUri())) {
            uris.add(child.getUri());
        }
    }

    public static boolean launch(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim();
        PackageManager pm = context.getPackageManager();

        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        }

        // Some emulators do not expose a normal launcher intent to PackageManager.
        // Try common entry activity names as a fallback.
        String[] candidates = new String[]{
                pkg + ".MainActivity",
                pkg + ".AppActivity",
                pkg + ".TyranoActivity",
                pkg + ".PlayerActivity",
                pkg + ".activity.MainActivity"
        };
        for (String cls : candidates) {
            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.addCategory(Intent.CATEGORY_LAUNCHER);
            explicit.setClassName(pkg, cls);
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(explicit);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    public static Intent buildInternalTyranoIntent(Context context, String gamePath, String launchTarget) {
        Intent i = new Intent(context, com.yuki.yukihub.tyrano.TyranoActivity.class);
        String resolvedPath = resolveInternalTyranoPath(gamePath, launchTarget);
        Log.i("EmulatorLauncher", "internal Tyrano root=" + gamePath + " target=" + launchTarget + " resolved=" + resolvedPath);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            String path = stripFileScheme(resolvedPath);
            i.putExtra("path", path);
            i.putExtra("gamePath", path);
            i.putExtra("projectRoot", path);
            i.putExtra("gamedir", path);
        }
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("type", "Tyrano");
        i.putExtra("launchMode", "internal.tyrano");
        i.putExtra("orientation", 6);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return i;
    }

    private static String resolveInternalTyranoPath(String rootUri, String launchTarget) {
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.isEmpty()) return rootUri;
        String target = launchTarget == null ? "" : launchTarget.trim();
        if (target.isEmpty() || "[游戏目录]".equals(target) || "DIR".equalsIgnoreCase(target)) return rootPath;
        if (target.startsWith("/")) {
            File f = new File(target);
            return f.isFile() ? f.getParent() : target;
        }
        File f = new File(rootPath, target);
        return f.isFile() ? f.getParent() : f.getAbsolutePath();
    }

    public static Intent buildInternalOnsIntent(Context context, String gamePath, String launchTarget) {
        Intent i = new Intent(context, com.yuri.onscripter.ONScripter.class);
        String rootPath = stripFileScheme(uriToFilePath(gamePath));
        Log.i("EmulatorLauncher", "internal ONS root=" + gamePath + " target=" + launchTarget + " resolved=" + rootPath);
        OnsSettings settings = OnsSettings.load(context);
        ArrayList<String> args = settings.buildArgs(context, rootPath);
        i.putStringArrayListExtra(OnsSettings.EXTRA_GAME_ARGS, args);
        i.putExtra(OnsSettings.EXTRA_IGNORE_CUTOUT, settings.ignoreCutout);
        i.putExtra("path", rootPath);
        i.putExtra("gamePath", rootPath);
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("launchMode", "internal.ons");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return i;
    }

    public static Intent buildInternalArtemisIntent(Context context, String packageName, String gamePath, String launchTarget) {
        Intent i = new Intent(context, chooseArtemisActivity(packageName));
        String resolvedPath = resolveInternalArtemisPath(gamePath, launchTarget);
        Log.i("EmulatorLauncher", "internal Artemis pkg=" + packageName + " root=" + gamePath + " target=" + launchTarget + " resolved=" + resolvedPath);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            // Tyranor ArtemisActivity uses getIntent().getStringExtra("path") directly
            // and returns it from getExternalFilesDir(). It expects a normal filesystem path.
            i.putExtra("path", stripFileScheme(resolvedPath));
            i.putExtra("gamePath", stripFileScheme(resolvedPath));
        }
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("launchMode", "internal.artemis");
        i.putExtra("orientation", 6);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return i;
    }

    public static Intent buildInternalArtemisIntent(Context context, String gamePath, String launchTarget) {
        return buildInternalArtemisIntent(context, "internal.artemis", gamePath, launchTarget);
    }

    private static Class<?> chooseArtemisActivity(String packageName) {
        String pkg = packageName == null ? "" : packageName.trim().toLowerCase();
        if (pkg.contains("v2") || pkg.endsWith(".2")) return com.akira.tyranoemu.remote.ArtemisActivityV3.class;
        if (pkg.contains("compat")) return com.akira.tyranoemu.remote.ArtemisActivityV2.class;
        return com.akira.tyranoemu.remote.ArtemisActivityV1.class;
    }

    private static String resolveInternalArtemisPath(String rootUri, String launchTarget) {
        // Tyranor's Artemis runner launches by game directory. The .pfs file is only used for detection.
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.isEmpty()) return rootUri;
        return rootPath;
    }

    private static String stripFileScheme(String path) {
        if (path == null) return null;
        return path.startsWith("file://") ? path.substring("file://".length()) : path;
    }

    private static String toFileUrlIfNeeded(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith("file://")) return path;
        if (path.startsWith("/")) return "file://" + path;
        return path;
    }

    public static Intent buildInternalKrkrIntent(Context context, String gamePath, String launchTarget) {
        return buildInternalKrkrIntent(context, gamePath, launchTarget, false);
    }

    public static Intent buildInternalKrkrIntent(Context context, String gamePath, String launchTarget, boolean originMode) {
        Intent i = new Intent(context, originMode ? org.tvp.kirikiri2.KR2Activity.class : com.akira.tyranoemu.remote.Kirikiroid139.class);
        String resolvedPath = originMode ? null : resolveInternalKrkrPath(gamePath, launchTarget);
        Log.i("EmulatorLauncher", "internal KRKR originMode=" + originMode + " root=" + gamePath + " target=" + launchTarget + " resolved=" + resolvedPath);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            String krkrPath = toKrkrFileUrl(resolvedPath);
            Log.i("EmulatorLauncher", "internal KRKR final path=" + krkrPath);
            i.putExtra("gamePath", krkrPath);
            i.putExtra("path", krkrPath);
        }
        i.putExtra("rootUri", gamePath);
        i.putExtra("launchTarget", launchTarget);
        i.putExtra("originMode", originMode);
        i.putExtra("launchMode", originMode ? "internal.krkr.origin" : "internal.krkr");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return i;
    }

    private static String resolveInternalKrkrPath(String rootUri, String launchTarget) {
        // Tyranor KR2 direct launch passes the selected launch file as "path".
        // For KR2 HistoryBean: path = full launch file path, parent = game root, name = launch file name.
        String rootPath = uriToFilePath(rootUri);
        if (rootPath == null || rootPath.isEmpty()) return rootUri;
        String target = launchTarget == null ? "" : launchTarget.trim();
        if (target.isEmpty() || "[游戏目录]".equals(target) || "DIR".equalsIgnoreCase(target) || "XP3_FIRST".equalsIgnoreCase(target)) {
            return rootPath;
        }
        if (target.startsWith("/")) return target;
        return rootPath.endsWith("/") ? rootPath + target : rootPath + "/" + target;
    }

    private static String toKrkrFileUrl(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith("file://")) return path;
        if (path.startsWith("/")) return "file://" + path;
        return path;
    }

    private static String uriToFilePath(String uriText) {
        if (uriText == null || uriText.trim().isEmpty()) return uriText;
        if (uriText.startsWith("/")) return uriText;
        try {
            Uri uri = Uri.parse(uriText);
            if ("file".equalsIgnoreCase(uri.getScheme())) return uri.getPath();
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                String docId = null;
                String path = uri.getPath();
                boolean hasDocumentPart = path != null && path.contains("/document/");
                if (hasDocumentPart) {
                    try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId != null) {
                    int colon = docId.indexOf(':');
                    String volume = colon >= 0 ? docId.substring(0, colon) : docId;
                    String rel = colon >= 0 ? docId.substring(colon + 1) : "";
                    if ("primary".equalsIgnoreCase(volume)) return rel.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + rel;
                    if (volume != null && !volume.isEmpty()) return rel.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + rel;
                }
            }
        } catch (Throwable ignored) { }
        return uriText;
    }
}