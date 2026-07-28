package com.yuki.yukihub.translate;

/**
 * 加密工具类（移植自萌译 CryptoUtils.java）。
 * 保留原项目 LGPL 版权声明。
 */
public final class CryptoUtils {

    private CryptoUtils() {}

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static byte[] hmacSHA256(byte[] key, String data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes("UTF-8"));
    }

    public static String hmacSHA256AsHex(byte[] key, String data) throws Exception {
        return bytesToHex(hmacSHA256(key, data));
    }

    public static String readInputStream(java.io.InputStream is) throws java.io.IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }
}