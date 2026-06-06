package com.yuki.yukihub.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class StorageProbeHelper {

    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir";
    private static final String KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir";

    private final Context context;

    public StorageProbeHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public static class StorageProbeResult {
        public String engine;
        public String rootUri;
        public String rawPath;
        public boolean rawResolved;
        public boolean rawExists;
        public boolean rawIsDirectory;
        public boolean rawReadOk;
        public boolean rawWriteOk;
        public boolean safCandidate;
        public boolean safTreeCoversPath;
        public boolean safReadOk;
        public boolean safWriteOk;
        public boolean appPrivateWriteOk;
        public String readError;
        public String writeError;
        public String safError;
        public String appPrivateError;
        public long elapsedMs;

        public String toLogLine() {
            return "engine=" + engine
                    + " rawResolved=" + rawResolved
                    + " rawExists=" + rawExists
                    + " rawDir=" + rawIsDirectory
                    + " rawReadOk=" + rawReadOk
                    + " rawWriteOk=" + rawWriteOk
                    + " safCandidate=" + safCandidate
                    + " safCovers=" + safTreeCoversPath
                    + " safReadOk=" + safReadOk
                    + " safWriteOk=" + safWriteOk
                    + " appPrivateWriteOk=" + appPrivateWriteOk
                    + " elapsedMs=" + elapsedMs
                    + " rawPath=" + rawPath
                    + " readErr=" + readError
                    + " writeErr=" + writeError
                    + " safErr=" + safError
                    + " appErr=" + appPrivateError;
        }
    }

    public static class SafPath {
        public final String volume;
        public final String rel;
        public SafPath(String volume, String rel) {
            this.volume = volume;
            this.rel = rel;
        }
    }

    public StorageProbeResult probeGameStorage(Game game) {
        long start = System.currentTimeMillis();
        StorageProbeResult result = new StorageProbeResult();
        result.engine = game == null || game.engine == null ? "unknown" : game.engine.name();
        result.rootUri = game == null ? null : game.rootUri;
        result.rawPath = fastRawPathFromUri(result.rootUri);
        result.rawResolved = result.rawPath != null && result.rawPath.startsWith("/");
        try {
            File appExternal = context.getExternalFilesDir(null);
            result.appPrivateWriteOk = quickWriteProbe(appExternal, ".yukihub_app_probe");
        } catch (Throwable t) {
            result.appPrivateError = shortError(t);
        }
        if (!result.rawResolved) {
            result.elapsedMs = System.currentTimeMillis() - start;
            result.readError = "raw path unavailable";
            return result;
        }
        File root = new File(result.rawPath);
        try {
            result.rawExists = root.exists();
            result.rawIsDirectory = root.isDirectory();
            if (result.rawIsDirectory) {
                String[] names = root.list();
                result.rawReadOk = names != null;
            } else {
                result.rawReadOk = root.isFile() && root.canRead();
            }
        } catch (Throwable t) {
            result.readError = shortError(t);
        }
        if (!result.rawReadOk && result.readError == null) result.readError = "list/canRead failed";
        try {
            File writeDir = root.isDirectory() ? root : root.getParentFile();
            result.rawWriteOk = quickWriteProbe(writeDir, ".yukihub_write_probe");
        } catch (Throwable t) {
            result.writeError = shortError(t);
        }
        if (!result.rawWriteOk && result.writeError == null) result.writeError = "create/write/delete failed";
        if (game != null && game.engine == EngineType.KIRIKIRI) {
            probeSafWriteFallback(result);
        } else if (!result.rawReadOk || !result.rawWriteOk) {
            probeSafWriteFallback(result);
        }
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    public boolean isScopedSaveEnabledFor(EngineType engine) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs == null || engine == null) return false;
        if (engine == EngineType.KIRIKIRI) return prefs.getBoolean(KEY_KR_SCOPED_SAVE_DIR, false);
        if (engine == EngineType.ARTEMIS) return prefs.getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, false);
        return false;
    }

    public boolean shouldProbeBeforeLaunch(Game game) {
        if (game == null || game.rootUri == null || game.rootUri.trim().isEmpty()) return false;
        if (isScopedSaveEnabledFor(game.engine)) return false;
        if (game.engine == EngineType.KIRIKIRI) return true;
        if (game.engine == EngineType.ARTEMIS) return true;
        return false;
    }

    public boolean canUseKrSafFileFallback(Game game, StorageProbeResult r) {
        if (game == null || game.engine != EngineType.KIRIKIRI) return false;
        if (isScopedSaveEnabledFor(game.engine)) return false;
        if (r == null || !r.rawResolved || !r.safTreeCoversPath || !r.safWriteOk) return false;
        return r.rawReadOk || r.safReadOk;
    }

    public boolean shouldUseKrSafFileFallback(Game game, StorageProbeResult result, long probeTime) {
        return canUseKrSafFileFallback(game, result)
                && System.currentTimeMillis() - probeTime <= 5000L;
    }

    public Uri createSafOutputStream(Game game, String relativePath) {
        if (game == null || game.rootUri == null) return null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri treeUri = Uri.parse(game.rootUri);
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            String decodedTreeId = Uri.decode(treeId);
            DocumentFile treeDoc = DocumentFile.fromTreeUri(context, treeUri);
            if (treeDoc == null) return null;
            DocumentFile current = treeDoc;
            if (relativePath != null && !relativePath.isEmpty()) {
                String[] parts = relativePath.split("/");
                for (String part : parts) {
                    if (part == null || part.isEmpty() || ".".equals(part)) continue;
                    DocumentFile child = current.findFile(part);
                    if (child == null) child = current.createDirectory(part);
                    if (child == null) return null;
                    current = child;
                }
            }
            String fileName = "yuki_probe_" + android.os.Process.myPid() + "_" + System.nanoTime() + ".tmp";
            DocumentFile existing = current.findFile(fileName);
            if (existing != null) {
                try { existing.delete(); } catch (Throwable ignored) { }
            }
            DocumentFile probe = current.createFile("application/octet-stream", fileName);
            return probe == null ? null : probe.getUri();
        } catch (Throwable t) {
            return null;
        }
    }

    private void probeSafWriteFallback(StorageProbeResult result) {
        if (result == null || !result.rawResolved || result.rawPath == null || result.rawPath.trim().isEmpty()) return;
        result.safCandidate = result.rawPath.startsWith("/storage/") || result.rawPath.startsWith("/sdcard");
        if (!result.safCandidate) return;
        try {
            SafPath safPath = toSafPath(result.rawPath);
            if (safPath == null || safPath.volume == null || safPath.rel == null) {
                result.safError = "raw path cannot map to SAF doc id";
                return;
            }
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                result.safError = "content resolver unavailable";
                return;
            }
            for (android.content.UriPermission perm : resolver.getPersistedUriPermissions()) {
                if (perm == null || perm.getUri() == null) continue;
                String treeId;
                try { treeId = DocumentsContract.getTreeDocumentId(perm.getUri()); } catch (Throwable ignored) { continue; }
                if (treeId == null) continue;
                String decodedTreeId = Uri.decode(treeId);
                if (decodedTreeId == null || !decodedTreeId.startsWith(safPath.volume + ":")) continue;
                String treeRel = decodedTreeId.substring((safPath.volume + ":").length());
                if (!treeRel.isEmpty() && !safPath.rel.equals(treeRel) && !safPath.rel.startsWith(treeRel + "/")) continue;
                result.safTreeCoversPath = true;
                result.safReadOk = perm.isReadPermission();
                boolean safTargetIsDirectory = result.rawIsDirectory || isSafTargetDirectory(perm.getUri(), decodedTreeId, safPath);
                if (!perm.isWritePermission()) {
                    result.safError = "persisted SAF tree is read-only";
                    return;
                }
                Uri probeUri = createSafProbeDocument(resolver, perm.getUri(), decodedTreeId, safPath, safTargetIsDirectory, ".yukihub_saf_probe_" + android.os.Process.myPid() + "_" + System.nanoTime() + ".tmp");
                if (probeUri == null) {
                    result.safError = "create SAF probe failed";
                    return;
                }
                try (OutputStream out = resolver.openOutputStream(probeUri, "wt")) {
                    if (out == null) {
                        result.safError = "open SAF probe output failed";
                        return;
                    }
                    out.write(new byte[]{'Y', 'H'});
                    out.flush();
                } finally {
                    try { DocumentsContract.deleteDocument(resolver, probeUri); } catch (Throwable ignored) { }
                }
                result.safWriteOk = true;
                result.safError = null;
                return;
            }
            result.safError = "no persisted SAF tree covers raw path";
        } catch (Throwable t) {
            result.safError = shortError(t);
        }
    }

    private boolean isSafTargetDirectory(Uri tree, String decodedTreeId, SafPath safPath) {
        try {
            if (tree == null || safPath == null) return false;
            DocumentFile current = DocumentFile.fromTreeUri(context, tree);
            if (current == null) return false;
            String treePrefix = safPath.volume + ":";
            String localRel = safPath.rel;
            String treeRel = decodedTreeId != null && decodedTreeId.startsWith(treePrefix) ? decodedTreeId.substring(treePrefix.length()) : "";
            if (!treeRel.isEmpty()) {
                if (localRel.equals(treeRel)) localRel = "";
                else if (localRel.startsWith(treeRel + "/")) localRel = localRel.substring(treeRel.length() + 1);
            }
            if (localRel == null || localRel.isEmpty()) return current.isDirectory();
            String[] parts = localRel.split("/");
            for (String part : parts) {
                if (part == null || part.isEmpty() || ".".equals(part)) continue;
                current = current.findFile(part);
                if (current == null) return false;
            }
            return current.isDirectory();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Uri createSafProbeDocument(ContentResolver resolver, Uri tree, String decodedTreeId, SafPath safPath, boolean rawIsDirectory, String probeName) {
        try {
            if (resolver == null || tree == null || safPath == null || probeName == null || probeName.trim().isEmpty()) return null;
            DocumentFile dir = DocumentFile.fromTreeUri(context, tree);
            if (dir == null) return null;
            String treePrefix = safPath.volume + ":";
            String localRel = safPath.rel;
            String treeRel = decodedTreeId != null && decodedTreeId.startsWith(treePrefix) ? decodedTreeId.substring(treePrefix.length()) : "";
            if (!treeRel.isEmpty()) {
                if (localRel.equals(treeRel)) localRel = "";
                else if (localRel.startsWith(treeRel + "/")) localRel = localRel.substring(treeRel.length() + 1);
            }
            String[] parts = localRel.split("/");
            DocumentFile current = dir;
            int end = rawIsDirectory ? parts.length : Math.max(0, parts.length - 1);
            for (int i = 0; i < end; i++) {
                String part = parts[i];
                if (part == null || part.isEmpty() || ".".equals(part)) continue;
                DocumentFile child = current.findFile(part);
                if (child == null) child = current.createDirectory(part);
                if (child == null || !child.isDirectory()) return null;
                current = child;
            }
            DocumentFile existing = current.findFile(probeName);
            if (existing != null) {
                try { existing.delete(); } catch (Throwable ignored) { }
            }
            DocumentFile probe = current.createFile("application/octet-stream", probeName);
            return probe == null ? null : probe.getUri();
        } catch (Throwable t) {
            Log.w("YukiStorageProbe", "create SAF probe failed", t);
            return null;
        }
    }

    public SafPath toSafPath(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.contains("//")) p = p.replace("//", "/");
        String volume;
        String rel;
        if (p.startsWith("/storage/emulated/0/")) {
            volume = "primary";
            rel = p.substring("/storage/emulated/0/".length());
        } else if ("/storage/emulated/0".equals(p)) {
            volume = "primary";
            rel = "";
        } else if (p.startsWith("/sdcard/")) {
            volume = "primary";
            rel = p.substring("/sdcard/".length());
        } else if ("/sdcard".equals(p)) {
            volume = "primary";
            rel = "";
        } else if (p.startsWith("/storage/")) {
            String rest = p.substring("/storage/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            volume = rest.substring(0, slash);
            rel = rest.substring(slash + 1);
        } else {
            return null;
        }
        if (volume == null || volume.isEmpty() || rel == null) return null;
        return new SafPath(volume, rel);
    }

    public String fastRawPathFromUri(String value) {
        if (value == null || value.trim().isEmpty()) return value;
        String s = value.trim();
        if (s.startsWith("file://")) {
            try { return Uri.parse(s).getPath(); } catch (Throwable ignored) { return s.substring("file://".length()); }
        }
        if (s.startsWith("/")) return s;
        try {
            Uri uri = Uri.parse(s);
            String docId = null;
            String path = uri.getPath();
            if (path != null && path.contains("/document/")) {
                try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                try { docId = DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId == null || docId.isEmpty()) {
                try { docId = DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
            }
            if (docId != null && !docId.isEmpty()) {
                int colon = docId.indexOf(':');
                String vol = colon >= 0 ? docId.substring(0, colon) : docId;
                String remaining = colon >= 0 ? docId.substring(colon + 1) : "";
                if ("primary".equalsIgnoreCase(vol)) return remaining.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + remaining;
                if (vol != null && !vol.isEmpty()) return remaining.isEmpty() ? "/storage/" + vol : "/storage/" + vol + "/" + remaining;
            }
            String p = uri.getPath();
            return p == null ? s : p;
        } catch (Throwable t) {
            return s;
        }
    }

    private boolean quickWriteProbe(File dir, String prefix) throws Exception {
        if (dir == null || !dir.isDirectory()) return false;
        File probe = new File(dir, prefix + "_" + android.os.Process.myPid() + "_" + System.nanoTime() + ".tmp");
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(probe, false)) {
            out.write(new byte[]{'Y', 'H'});
            out.flush();
            ok = probe.isFile() && probe.length() >= 2;
        } finally {
            if (probe.exists() && !probe.delete()) Log.w("YukiStorageProbe", "probe delete failed " + probe.getAbsolutePath());
        }
        return ok;
    }

    private String shortError(Throwable t) {
        if (t == null) return null;
        String msg = t.getMessage();
        String name = t.getClass().getSimpleName();
        return msg == null || msg.trim().isEmpty() ? name : name + ": " + msg;
    }
}