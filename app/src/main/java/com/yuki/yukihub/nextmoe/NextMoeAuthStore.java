package com.yuki.yukihub.nextmoe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * NextMoe 用户令牌存储。
 * <p>
 * NextMoe 契约：refresh token 是长期凭据且每次刷新都会轮换，必须落「操作系统钥匙串」。
 * Android 对应实现为 Android Keystore（硬件级密钥，不可导出）+ AES-256-GCM 加密后存入
 * 应用私有 SharedPreferences。access token（JWT，15 分钟）同样加密保存，省去冷启动刷新。
 * <p>
 * Keystore 初始化失败（极少数 ROM 异常）时退化为普通存储，功能不中断——应用私有目录
 * 本身受系统沙箱保护，只是强度低于硬件密钥，日志里会留 warning。
 */
public final class NextMoeAuthStore {

    private static final String TAG = "NextMoe";
    private static final String PREFS_NAME = "nextmoe_auth";
    private static final String KEY_ENC_REFRESH = "enc_refresh";
    private static final String KEY_ENC_ACCESS = "enc_access";
    private static final String KEY_ACCESS_EXP = "access_exp";
    private static final String KEY_ACCOUNT_LABEL = "account_label";
    private static final String KEYSTORE_ALIAS = "yukihub_nextmoe_key";
    private static final int GCM_TAG_BITS = 128;

    private NextMoeAuthStore() { }

    // ======================== public API ========================

    public static boolean isConnected() {
        return !get(KEY_ENC_REFRESH).isEmpty();
    }

    public static String getRefreshToken() {
        return get(KEY_ENC_REFRESH);
    }

    public static String getAccessToken() {
        return get(KEY_ENC_ACCESS);
    }

    public static long getAccessExpiresAt() {
        return prefs().getLong(KEY_ACCESS_EXP, 0L);
    }

    /** 授权页/同意页上展示的账号标识（鲲站登录名），未连接时为空。 */
    public static String getAccountLabel() {
        return prefs().getString(KEY_ACCOUNT_LABEL, "");
    }

    /** 保存令牌（refresh 轮换时整组覆盖，绝不追加旧值）。 */
    public static void save(String accessToken, long accessExpiresAtMs, String refreshToken, String accountLabel) {
        SharedPreferences.Editor e = prefs().edit();
        e.putString(KEY_ENC_REFRESH, encryptOrPlain(refreshToken));
        e.putString(KEY_ENC_ACCESS, encryptOrPlain(accessToken));
        e.putLong(KEY_ACCESS_EXP, accessExpiresAtMs);
        e.putString(KEY_ACCOUNT_LABEL, accountLabel == null ? "" : accountLabel);
        e.apply();
    }

    /** 授权失效或用户主动断开时清除全部凭据。 */
    public static void clear() {
        prefs().edit().clear().apply();
    }

    // ======================== crypto ========================

    private static String encryptOrPlain(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return "enc:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "keystore encrypt failed, falling back to plain private storage", t);
            return "plain:" + plain;
        }
    }

    private static String get(String key) {
        String stored = prefs().getString(key, "");
        if (stored.isEmpty()) return "";
        if (stored.startsWith("plain:")) return stored.substring(6);
        if (!stored.startsWith("enc:")) return "";
        try {
            byte[] all = Base64.decode(stored.substring(4), Base64.NO_WRAP);
            byte[] iv = Arrays.copyOfRange(all, 0, 12);
            byte[] enc = Arrays.copyOfRange(all, 12, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(enc), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "keystore decrypt failed", t);
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEYSTORE_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (Build.VERSION.SDK_INT >= 28) b.setUserAuthenticationRequired(false);
        kg.init(b.build());
        return kg.generateKey();
    }

    private static SharedPreferences prefs() {
        return appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Context appContext() {
        Context c = AppContextHolder.get();
        if (c == null) throw new IllegalStateException("NextMoeAuthStore 未初始化");
        return c;
    }

    /** 应用启动时注入 ApplicationContext，避免持有 Activity 引用。 */
    public static void init(Context appContext) {
        AppContextHolder.set(appContext.getApplicationContext());
        NextMoeContexts.set(appContext.getApplicationContext());
    }

    private static final class AppContextHolder {
        private static Context ctx;
        static void set(Context c) { ctx = c; }
        static Context get() { return ctx; }
    }
}