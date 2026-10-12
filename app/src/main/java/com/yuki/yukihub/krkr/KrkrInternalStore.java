package com.yuki.yukihub.krkr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * KRKR「内部存储启动」的存储管理。
 *
 * <p>【做什么】把整个游戏目录完整复制到应用私有内部存储
 * （{@code getFilesDir()/krkr_internal/<游戏名>/}）后启动，
 * 让引擎的 AutoPath 扫描跑在 f2fs 上（实测小文件遍历快约 5.5 倍），
 * 缓解新版 KR 引擎每次存档重建索引造成的对话卡顿。
 *
 * <p>【存档在哪】存档就写在副本自己的 {@code savedata/} 里 ——
 * 因为启动时会关掉 KR 的 scoped save 重定向，引擎读写的都是真实内部路径。
 * 所以这里**不做任何存档同步**，副本是自包含的。
 *
 * <p>【目录结构】
 * <pre>
 *   getFilesDir()/krkr_internal/
 *     ├── &lt;游戏名&gt;/
 *     │     ├── .yh_manifest     指纹（文件数 + 总字节 + 源路径）
 *     │     ├── data.xp3 ...
 *     │     └── savedata/        引擎存档
 *     └── ...
 * </pre>
 */
public final class KrkrInternalStore {

    private static final String TAG = "KrkrInternalStore";

    /** 内部存储根目录名。 */
    public static final String DIR_NAME = "krkr_internal";

    private static final int BUFFER = 64 * 1024;

    private KrkrInternalStore() { }

    // ==================== 路径 ====================

    /** 内部存储根目录。 */
    public static File root(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * 副本目录名：{@code <游戏ID>_<安全名>}，例如 {@code 42_天使纷扰}。
     *
     * <p>【为什么带游戏 ID】不同游戏可能位于不同路径但**目录同名**
     * （如 {@code Tyranor/天使纷扰} 与 {@code Games/天使纷扰}）。
     * 若只用目录名，两者会共用同一个副本目录 → 互相覆盖、存档串号。
     * 游戏 ID 是数据库主键，改名/移动目录都不会变，可作稳定标识。
     */
    public static String copyDirName(long gameId, String rawName) {
        return gameId + "_" + lowerName(safeName(rawName));
    }

    /** 从副本目录名解析游戏 ID；无法解析返回 -1。 */
    public static long gameIdOfDirName(String dirName) {
        if (dirName == null) return -1L;
        int us = dirName.indexOf('_');
        if (us <= 0) return -1L;
        try {
            return Long.parseLong(dirName.substring(0, us));
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 从副本目录名解析展示名（去掉 ID 前缀）。 */
    public static String displayNameOfDirName(String dirName) {
        if (dirName == null) return "";
        int us = dirName.indexOf('_');
        return us < 0 || us + 1 >= dirName.length() ? dirName : dirName.substring(us + 1);
    }

    /** 某游戏对应的副本目录。 */
    public static File copyDir(Context context, long gameId, String rawName) {
        return new File(root(context), copyDirName(gameId, rawName));
    }

    /** 按副本目录名定位（管理/清理用）。 */
    public static File copyDirByDirName(Context context, String dirName) {
        return new File(root(context), dirName);
    }

    /** 目录名安全化：去掉路径分隔符等非法字符。 */
    public static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "default";
        String n = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.isEmpty() ? "default" : n;
    }

    /**
     * 副本内条目名统一转小写。
     *
     * <p>【为什么必须小写】KR 内核会把**整条路径**转小写后再做 stat/opendir，
     * 而外部 SD 卡走 FUSE（大小写不敏感，随便大小写都能命中），
     * 内部存储却是大小写敏感的 f2fs。副本里若保留原始大小写，
     * 引擎按小写路径访问时全部落空 —— 表现为 AutoPath 扫描到 0 个文件、
     * 启动报找不到入口。中文名不受影响。
     *
     * <p>【例外：引擎固定名文件必须保持原样】引擎除了走 AutoPath 索引，
     * 还会用**硬编码的原始大小写**直接拼路径读取若干固定文件，例如
     * {@code 游戏目录 + "/Kirikiroid2Preference.xml"}（见 libgame.so 字符串）。
     * 这些文件若被小写化，在大小写敏感的内部存储上会**直接读不到**：
     * <ul>
     *   <li>{@code Kirikiroid2Preference.xml} —— 读不到会导致
     *       <b>引擎设置（含 showfps / fps_limit / renderer 等）全部失效</b>，
     *       表现为帧率异常、掉帧、渲染参数不生效；</li>
     *   <li>{@code DroidSansFallback.ttf}、{@code UserDefault.xml}、
     *       {@code AfterStartup.tjs}、{@code Ystartup.tjs} 同理。</li>
     * </ul>
     * 因此这里采用**白名单式**：只对这些引擎固定名保持原始大小写，
     * 其余条目照旧小写化（AutoPath 索引需要）。
     */
    private static String lowerName(String name) {
        if (name == null) return "";
        if (ENGINE_FIXED_NAMES_LOWER.contains(name.toLowerCase(Locale.ROOT))) return name;
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * 引擎以**硬编码原始大小写**直接读取的固定文件（原始名）。
     *
     * <p>来源：{@code libgame.so} / {@code libgame134.so} 字符串表。
     * 这些名字**不能**被小写化，否则内部副本里读不到；
     * 其中 {@code Kirikiroid2Preference.xml} 尤其关键 —— 读不到会导致
     * 引擎设置（showfps / fps_limit / renderer…）全部失效。
     *
     * <p>这些文件同时也是「运行期可变」的，所以启动前还会与源目录同步
     * （见 {@link #syncEngineFixedFiles}）。
     */
    private static final List<String> ENGINE_FIXED_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "Kirikiroid2Preference.xml",   // 引擎设置（showfps/fps_limit/renderer…）
                    "DroidSansFallback.ttf",       // 兜底字体
                    "UserDefault.xml",             // 引擎用户数据
                    "AfterStartup.tjs",            // 引擎固定启动脚本
                    "Ystartup.tjs"                 // 引擎固定启动脚本
            ));

    /** {@link #ENGINE_FIXED_FILES} 的小写形式，供 {@link #lowerName} 快速比对。 */
    private static final Set<String> ENGINE_FIXED_NAMES_LOWER;
    static {
        Set<String> s = new HashSet<>();
        for (String n : ENGINE_FIXED_FILES) s.add(n.toLowerCase(Locale.ROOT));
        ENGINE_FIXED_NAMES_LOWER = Collections.unmodifiableSet(s);
    }

    /**
     * 把源目录里的「引擎固定文件」同步进副本（单向，仅当副本版本更旧时）。
     *
     * <p>【为什么需要】{@code Kirikiroid2Preference.xml} 这类文件是运行期可变的：
     * <ul>
     *   <li>用户通过 YukiHub「引擎设置」修改 → 写到<b>源目录</b>；</li>
     *   <li>用户在游戏内改设置 → 引擎写到<b>当前游戏目录</b>（内部模式下即副本）。</li>
     * </ul>
     * 副本是「存在即复用、不重拷」的，若不同步，源目录的改动永远进不来，
     * 表现为「引擎设置改了但内部模式不生效」。
     *
     * <p>【为什么用 mtime 单向】只在新版本存在且更旧时覆盖，因此
     * 「游戏内改设置」写进副本的内容不会被源目录的旧版本冲掉。
     */
    private static void syncEngineFixedFiles(File srcRoot, File dstRoot) {
        if (srcRoot == null || dstRoot == null) return;
        if (!srcRoot.isDirectory() || !dstRoot.isDirectory()) return;
        for (String name : ENGINE_FIXED_FILES) {
            try {
                File src = new File(srcRoot, name);
                if (!src.isFile()) continue;
                File dst = new File(dstRoot, name);
                // 清掉上一版小写化 bug 留下的同名小写文件，避免副本里
                // 同时存在 kirikiroid2preference.xml 与 Kirikiroid2Preference.xml。
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.equals(name)) {
                    File orphan = new File(dstRoot, lower);
                    if (orphan.isFile() && orphan.delete()) {
                        Log.i(TAG, "remove lowercased orphan " + orphan.getName());
                    }
                }
                if (dst.isFile() && dst.lastModified() >= src.lastModified()) continue;
                if (copyOneFile(src, dst, new long[]{0}, 0L, null)) {
                    Log.i(TAG, "sync engine file " + name + " -> " + dst.getAbsolutePath());
                }
            } catch (Throwable t) {
                Log.w(TAG, "sync engine file failed: " + name, t);
            }
        }
    }

    // ==================== 复制 ====================

    /** 复制进度回调。 */
    public interface ProgressListener {
        /** @param copiedBytes 已复制字节；totalBytes 为预估总量（可能为 0 表示未知） */
        void onProgress(long copiedBytes, long totalBytes);
    }

    /**
     * 确保内部副本存在，不存在则复制。
     *
     * <p>【判定规则（刻意简单）】只看副本目录**是否已存在且非空**：
     * <ul>
     *   <li>已有副本 → 直接返回，**永不重新复制**</li>
     *   <li>没有副本 → 复制一次</li>
     * </ul>
     *
     * <p>【为什么不比对源目录指纹】源目录变化的场景极少（玩家自己动游戏文件），
     * 而重新复制会**删掉副本内的存档**，代价远大于收益。所以宁可"源变了也不重拷"：
     * 玩家要更新游戏，自己删掉副本重进即可。
     *
     * <p>【为什么用 gameId 命名】见 {@link #copyDirName}。
     *
     * @return 复制后的副本目录；失败返回 null
     */
    public static File ensureCopy(Context context, long gameId, String gameTitle, File sourceRoot,
                                  ProgressListener listener) {
        if (context == null || sourceRoot == null || !sourceRoot.isDirectory()) return null;

        File dst = copyDir(context, gameId, gameTitle);

        // 已有副本 → 直接复用（不重拷、不动存档）
        if (dst.isDirectory() && hasAnyContent(dst)) {
            Log.i(TAG, "internal copy exists, reuse name=" + dst.getName());
            // 但「引擎固定文件」是运行期可变的，必须与源目录同步，
            // 否则用户在「引擎设置」里的改动永远不会生效。
            syncEngineFixedFiles(sourceRoot, dst);
            return dst;
        }

        long total = totalBytes(sourceRoot);
        long free = context.getFilesDir().getUsableSpace();
        // 预留 10% 余量，避免刚好塞满导致系统异常
        if (free < total + total / 10) {
            Log.w(TAG, "not enough space: need=" + total + " free=" + free);
            return null;
        }

        // 首次复制（或上次复制残留了空目录）：清干净再拷
        if (dst.exists()) deleteRecursively(dst);
        if (!dst.mkdirs() && !dst.isDirectory()) {
            Log.w(TAG, "mkdir failed " + dst);
            return null;
        }

        long[] copied = new long[]{0};
        boolean ok = copyTree(sourceRoot, dst, copied, total, listener);
        if (!ok) {
            Log.w(TAG, "copy tree failed, cleanup " + dst);
            deleteRecursively(dst);
            return null;
        }
        // copyTree 已按白名单保留引擎固定名的原始大小写；
        // 这里再同步一次，确保「源目录比副本新」的固定文件也一并带过来。
        syncEngineFixedFiles(sourceRoot, dst);

        Log.i(TAG, "internal copy done name=" + dst.getName() + " bytes=" + copied[0]);
        return dst;
    }

    /** 目录内是否有实际内容。 */
    private static boolean hasAnyContent(File dir) {
        File[] children = dir.listFiles();
        return children != null && children.length > 0;
    }

    /** 递归复制目录内容。返回是否全部成功。 */
    private static boolean copyTree(File srcDir, File dstDir, long[] copied, long total, ProgressListener listener) {
        File[] children = srcDir.listFiles();
        if (children == null) return true;
        for (File child : children) {
            if (child == null) continue;
            String cname = child.getName();
            if (cname == null || cname.isEmpty()) continue;

            File target = new File(dstDir, lowerName(cname));
            if (target.exists()) {
                // 小写化后撞名（如 Data.xp3 与 data.xp3 并存），保留先复制的那个。
                Log.w(TAG, "name collision after lowercase, skip " + child.getAbsolutePath());
                continue;
            }
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) return false;
                if (!copyTree(child, target, copied, total, listener)) return false;
            } else {
                if (!copyOneFile(child, target, copied, total, listener)) return false;
            }
        }
        return true;
    }

    private static boolean copyOneFile(File src, File dst, long[] copied, long total, ProgressListener listener) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            byte[] buf = new byte[BUFFER];
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst, false)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    copied[0] += n;
                }
            }
            dst.setLastModified(src.lastModified());
            if (listener != null) listener.onProgress(copied[0], total);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "copy failed " + src + " -> " + dst, t);
            return false;
        }
    }

    // ==================== 统计 ====================

    /** 目录总字节数。 */
    public static long totalBytes(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        long sum = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            if (c == null) continue;
            if (c.isDirectory()) sum += totalBytes(c);
            else sum += c.length();
        }
        return sum;
    }

// ==================== 统计 ====================

    /** 一个游戏副本的概况。 */
    public static class Entry {
        /** 副本目录名（{@code <gameId>_<名称>}）。 */
        public final String dirName;
        /** 解析出的游戏 ID；无法解析为 -1。 */
        public final long gameId;
        /** 展示用名称（去掉 ID 前缀）。 */
        public final String displayName;
        public final File dir;
        public final long bytes;

        Entry(String dirName, File dir, long bytes) {
            this.dirName = dirName;
            this.dir = dir;
            this.bytes = bytes;
            this.gameId = gameIdOfDirName(dirName);
            this.displayName = displayNameOfDirName(dirName);
        }
    }

    /** 列出所有内部副本（按占用从大到小）。 */
    public static List<Entry> list(Context context) {
        List<Entry> out = new ArrayList<>();
        File root = root(context);
        File[] children = root.listFiles();
        if (children == null) return out;
        for (File c : children) {
            if (c == null || !c.isDirectory()) continue;
            out.add(new Entry(c.getName(), c, totalBytes(c)));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.bytes, a.bytes));
        return out;
    }

    /** 已用总量（所有副本合计）。 */
    public static long usedBytes(Context context) {
        long sum = 0;
        for (Entry e : list(context)) sum += e.bytes;
        return sum;
    }

    /** 内部存储可用空间。 */
    public static long availableBytes(Context context) {
        try {
            return context.getFilesDir().getUsableSpace();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================== 清理 ====================

    /** 删除某个副本（含其存档）。参数为副本目录名。 */
    public static boolean remove(Context context, String dirName) {
        File dir = copyDirByDirName(context, dirName);
        if (!dir.exists()) return false;
        boolean ok = deleteRecursively(dir);
        Log.i(TAG, "remove internal copy dirName=" + dirName + " ok=" + ok);
        return ok;
    }

    /** 清空全部副本。 */
    public static boolean removeAll(Context context) {
        File root = root(context);
        if (!root.exists()) return true;
        File[] children = root.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c == null) continue;
                deleteRecursively(c);
            }
        }
        Log.i(TAG, "remove all internal copies");
        return true;
    }

    /** 递归删除。 */
    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursively(c)) return false;
                }
            }
        }
        return file.delete() || !file.exists();
    }

    // ==================== 展示辅助 ====================

    /** 人类可读的大小。 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}