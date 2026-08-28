package com.yuki.yukihub.ons;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 负责把 onsyuri 引擎的 .so 从 assets 释放到私有目录并加载。
 *
 * 为什么不用 jniLibs 直接打包：引擎需要支持多版本并存与运行期切换，
 * jniLibs 只能有一份同名 so。放 assets 后按版本目录释放，可以在出问题时
 * 立刻切回旧版本，不必重新打包 APK。
 *
 * 关于 libONSPatch.so：它是对 libonsyuri.so 做单点 inline hook 的补丁库
 * （改写一条 BL 指令，靠运行期计算的绝对地址定位，不依赖符号名）。
 * 因此它与具体的 onsyuri 版本是绑定关系——换引擎版本后 patch 可能打偏。
 * {@link #PATCH_COMPATIBLE_VERSION} 显式声明它只对哪个版本生效，
 * 其他版本一律跳过加载，避免把指令写到错误位置导致硬崩。
 */
public final class OnsLibLoader {

    private static final String TAG = "OnsLibLoader";

    /** 默认使用的引擎版本。 */
    public static final String VERSION_LATEST = "Yuri_0.7.7";
    /** 上一个稳定版本，作为回退选项。 */
    public static final String VERSION_LEGACY = "Yuri_0.7.6";

    /**
     * libONSPatch.so 经过验证只与该版本匹配。
     * 它按硬编码偏移改写 libonsyuri.so 的指令，版本不符时打偏会直接崩溃，
     * 所以只在版本完全一致时才加载。
     */
    private static final String PATCH_COMPATIBLE_VERSION = VERSION_LEGACY;

    /** 可选的引擎版本，顺序即回退顺序。 */
    public static final String[] AVAILABLE_VERSIONS = new String[]{VERSION_LATEST, VERSION_LEGACY};

    private static final String PREF_NAME = "onsyuri";
    private static final String KEY_ENGINE_VERSION = "engine_version";

    /**
     * 加载顺序即依赖顺序，不能随意调整：
     * onsyuri 依赖 SDL2 系列 + lua + jpeg + bz2，被依赖者必须先加载。
     */
    private static final String[] ASSET_LIBS = new String[]{
            "SDL2", "lua", "jpeg", "bz2", "SDL2_image", "SDL2_mixer", "SDL2_ttf", "onsyuri"
    };

    private static boolean loaded;
    /** 本次进程实际加载成功的版本，供 ONScripter 定位 main so 路径。 */
    private static String loadedVersion;

    private OnsLibLoader() { }

    /** 读取用户选择的引擎版本，非法值一律回落到默认版本。 */
    public static String getSelectedVersion(Context context) {
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_ENGINE_VERSION, VERSION_LATEST);
            for (String known : AVAILABLE_VERSIONS) {
                if (known.equals(v)) return v;
            }
        } catch (Throwable t) {
            Log.w(TAG, "read engine version failed", t);
        }
        return VERSION_LATEST;
    }

    public static void setSelectedVersion(Context context, String version) {
        if (version == null) return;
        boolean known = false;
        for (String v : AVAILABLE_VERSIONS) {
            if (v.equals(version)) { known = true; break; }
        }
        if (!known) {
            Log.w(TAG, "reject unknown engine version: " + version);
            return;
        }
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ENGINE_VERSION, version)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save engine version failed", t);
        }
    }

    /**
     * 返回本次已加载的版本。加载前调用则返回用户选择的版本。
     * ONScripter 用它拼 main shared object 路径，必须与实际加载的一致。
     */
    public static synchronized String getActiveVersion(Context context) {
        return loadedVersion != null ? loadedVersion : getSelectedVersion(context);
    }

    public static synchronized void load(Context context) {
        if (loaded) return;
        Context app = context.getApplicationContext();
        copyAssetFile(app, "DroidSansFallback.ttf", new File(app.getFilesDir(), "DroidSansFallback.ttf"));

        String preferred = getSelectedVersion(app);
        // 先试用户选的版本；失败则按 AVAILABLE_VERSIONS 顺序回退，
        // 避免新版 so 在个别设备上加载失败时整个 ONS 功能不可用。
        if (tryLoadVersion(app, preferred)) return;

        for (String fallback : AVAILABLE_VERSIONS) {
            if (fallback.equals(preferred)) continue;
            Log.w(TAG, "fallback to engine version " + fallback);
            if (tryLoadVersion(app, fallback)) {
                // 记住可用版本，下次直接用，不必每次都撞一遍失败的版本。
                setSelectedVersion(app, fallback);
                return;
            }
        }
        throw new RuntimeException("no usable onsyuri engine version");
    }

    private static boolean tryLoadVersion(Context app, String version) {
        File outDir = new File(app.getFilesDir(), "libs/" + version);
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.w(TAG, "mkdir failed: " + outDir);
        }
        try {
            for (String lib : ASSET_LIBS) {
                File so = copyAssetLib(app, version, lib, outDir);
                Log.i(TAG, "load " + so.getAbsolutePath());
                System.load(so.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.e(TAG, "load engine " + version + " failed", t);
            return false;
        }

        loadPatchIfCompatible(version);
        loaded = true;
        loadedVersion = version;
        Log.i(TAG, "engine ready: " + version);
        return true;
    }

    /**
     * ONSPatch 只在版本匹配时加载。
     * 它靠硬编码偏移改写 libonsyuri.so 指令，版本不符时会写到错误位置，
     * 后果是运行中随机崩溃且难以排查，所以宁可不加载。
     */
    private static void loadPatchIfCompatible(String version) {
        if (!PATCH_COMPATIBLE_VERSION.equals(version)) {
            Log.i(TAG, "skip ONSPatch: only verified for " + PATCH_COMPATIBLE_VERSION + ", current " + version);
            return;
        }
        try {
            System.loadLibrary("ONSPatch");
            Log.i(TAG, "ONSPatch loaded for " + version);
        } catch (Throwable t) {
            Log.w(TAG, "load ONSPatch failed, continue", t);
        }
    }

    private static File copyAssetLib(Context context, String version, String lib, File outDir) {
        String asset = "libs/" + version + "/lib" + lib + ".so";
        File out = new File(outDir, "lib" + lib + ".so");
        copyAssetFile(context, asset, out);
        out.setExecutable(true, false);
        return out;
    }

    private static File copyAssetFile(Context context, String asset, File out) {
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "mkdir failed: " + parent);
            }
            // 用长度比对判断是否需要重新释放：升级后 so 大小几乎必然变化，
            // 只判 exists() 会导致旧文件一直不被覆盖。
            long assetSize = assetSize(context, asset);
            boolean needCopy = !out.exists() || out.length() <= 0
                    || (assetSize > 0 && out.length() != assetSize);
            if (needCopy) {
                try (InputStream in = context.getAssets().open(asset); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                out.setReadable(true, false);
                out.setWritable(true, true);
            }
        } catch (Throwable t) {
            throw new RuntimeException("copy asset failed: " + asset, t);
        }
        return out;
    }

    /** 取 asset 的解压后大小，取不到返回 -1（此时退化为只判存在性）。 */
    private static long assetSize(Context context, String asset) {
        try (InputStream in = context.getAssets().open(asset)) {
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) total += n;
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }
}