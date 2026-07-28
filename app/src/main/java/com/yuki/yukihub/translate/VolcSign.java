package com.yuki.yukihub.translate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 火山引擎签名器（移植自萌译 VolcSign.java）。
 * 保留原项目 LGPL 版权声明。
 */
public final class VolcSign {

    private static final BitSet URLENCODER = new BitSet(256);
    private static final String CONST_ENCODE = "0123456789ABCDEF";
    public static final Charset UTF_8 = StandardCharsets.UTF_8;

    static {
        int i;
        for (i = 97; i <= 122; ++i) URLENCODER.set(i);
        for (i = 65; i <= 90; ++i) URLENCODER.set(i);
        for (i = 48; i <= 57; ++i) URLENCODER.set(i);
        URLENCODER.set('-');
        URLENCODER.set('_');
        URLENCODER.set('.');
        URLENCODER.set('~');
    }

    private final String region;
    private final String service;
    private final String schema;
    private final String host;
    private final String path;
    private final String ak;
    private final String sk;

    public VolcSign(String region, String service, String schema, String host, String path, String ak, String sk) {
        this.region = region;
        this.service = service;
        this.host = host;
        this.schema = schema;
        this.path = path;
        this.ak = ak;
        this.sk = sk;
    }

    public String doRequest(String method, Map<String, String> queryList, byte[] body,
                           Date date, String action, String version) throws Exception {
        if (body == null) body = new byte[0];
        String xContentSha256 = hashSHA256(body);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String xDate = sdf.format(date);
        String shortXDate = xDate.substring(0, 8);
        String contentType = "application/json";
        String signHeader = "host;x-date;x-content-sha256;content-type";

        SortedMap<String, String> realQueryList = new TreeMap<>(queryList);
        realQueryList.put("Action", action);
        realQueryList.put("Version", version);
        StringBuilder querySB = new StringBuilder();
        for (String key : realQueryList.keySet()) {
            querySB.append(signStringEncoder(key)).append("=").append(signStringEncoder(realQueryList.get(key))).append("&");
        }
        querySB.deleteCharAt(querySB.length() - 1);

        String canonicalStringBuilder = method + "\n" + path + "\n" + querySB + "\n" +
                "host:" + host + "\n" +
                "x-date:" + xDate + "\n" +
                "x-content-sha256:" + xContentSha256 + "\n" +
                "content-type:" + contentType + "\n" +
                "\n" +
                signHeader + "\n" +
                xContentSha256;

        String hashcanonicalString = hashSHA256(canonicalStringBuilder.getBytes());
        String credentialScope = shortXDate + "/" + region + "/" + service + "/request";
        String signString = "HMAC-SHA256" + "\n" + xDate + "\n" + credentialScope + "\n" + hashcanonicalString;

        byte[] signKey = genSigningSecretKeyV4(sk, shortXDate, region, service);
        String signature = CryptoUtils.hmacSHA256AsHex(signKey, signString);

        URL url = new URL(schema + "://" + host + path + "?" + querySB);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Host", host);
        conn.setRequestProperty("X-Date", xDate);
        conn.setRequestProperty("X-Content-Sha256", xContentSha256);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Authorization", "HMAC-SHA256" +
                " Credential=" + ak + "/" + credentialScope +
                ", SignedHeaders=" + signHeader +
                ", Signature=" + signature);
        if (!method.equals("GET")) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
        }
        conn.connect();

        int responseCode = conn.getResponseCode();
        InputStream is;
        if (responseCode == 200) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
        }
        String responseBody = CryptoUtils.readInputStream(is);
        is.close();

        if (responseCode != 200) {
            throw new Exception("Request failed with code " + responseCode + ": " + responseBody);
        }
        return responseBody;
    }

    private String signStringEncoder(String source) {
        if (source == null) return null;
        StringBuilder buf = new StringBuilder(source.length());
        ByteBuffer bb = UTF_8.encode(source);
        while (bb.hasRemaining()) {
            int b = bb.get() & 255;
            if (URLENCODER.get(b)) {
                buf.append((char) b);
            } else if (b == 32) {
                buf.append("%20");
            } else {
                buf.append("%");
                char hex1 = CONST_ENCODE.charAt(b >> 4);
                char hex2 = CONST_ENCODE.charAt(b & 15);
                buf.append(hex1);
                buf.append(hex2);
            }
        }
        return buf.toString();
    }

    public static String hashSHA256(byte[] content) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return CryptoUtils.bytesToHex(md.digest(content));
    }

    public static byte[] hmacSHA256(byte[] key, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(content.getBytes());
    }

    private byte[] genSigningSecretKeyV4(String secretKey, String date, String region, String service) throws Exception {
        byte[] kDate = hmacSHA256(secretKey.getBytes(), date);
        byte[] kRegion = hmacSHA256(kDate, region);
        byte[] kService = hmacSHA256(kRegion, service);
        return hmacSHA256(kService, "request");
    }
}