package org.cocos2dx.lib;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/* JADX INFO: loaded from: classes.dex */
public class Cocos2dxHttpURLConnection {
    private static final String POST_METHOD = "POST";
    private static final String PUT_METHOD = "PUT";

    public static void addRequestHeader(HttpURLConnection httpURLConnection, String str, String str2) {
        httpURLConnection.setRequestProperty(str, str2);
    }

    public static String combinCookies(List<String> list, String str) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = list.iterator();
        String str2 = "/";
        String str3 = "FALSE";
        String str4 = null;
        String str5 = null;
        String strStr2Seconds = null;
        while (it.hasNext()) {
            for (String str6 : it.next().split(";")) {
                int iIndexOf = str6.indexOf("=");
                if (-1 != iIndexOf) {
                    String[] strArr = {str6.substring(0, iIndexOf), str6.substring(iIndexOf + 1)};
                    if ("expires".equalsIgnoreCase(strArr[0].trim())) {
                        strStr2Seconds = str2Seconds(strArr[1].trim());
                    } else if ("path".equalsIgnoreCase(strArr[0].trim())) {
                        str2 = strArr[1];
                    } else if ("secure".equalsIgnoreCase(strArr[0].trim())) {
                        str3 = strArr[1];
                    } else if ("domain".equalsIgnoreCase(strArr[0].trim())) {
                        str = strArr[1];
                    } else if (!"version".equalsIgnoreCase(strArr[0].trim()) && !"max-age".equalsIgnoreCase(strArr[0].trim())) {
                        str4 = strArr[0];
                        str5 = strArr[1];
                    }
                }
            }
            if (str == null) {
                str = "none";
            }
            sb.append(str);
            sb.append("\tFALSE\t");
            sb.append(str2);
            sb.append('\t');
            sb.append(str3);
            sb.append('\t');
            sb.append(strStr2Seconds);
            sb.append("\t");
            sb.append(str4);
            sb.append("\t");
            sb.append(str5);
            sb.append('\n');
        }
        return sb.toString();
    }

    public static int connect(HttpURLConnection httpURLConnection) {
        try {
            httpURLConnection.connect();
            return 0;
        } catch (IOException e8) {
            Log.e("cocos2d-x debug info", "come in connect");
            Log.e("cocos2d-x debug info", e8.toString());
            return 1;
        }
    }

    public static HttpURLConnection createHttpURLConnection(String str) {
        try {
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
            httpURLConnection.setRequestProperty("Accept-Encoding", "identity");
            httpURLConnection.setDoInput(true);
            return httpURLConnection;
        } catch (Exception e8) {
            Log.e("URLConnection exception", e8.toString());
            return null;
        }
    }

    public static void disconnect(HttpURLConnection httpURLConnection) {
        httpURLConnection.disconnect();
    }

    public static int getResponseCode(HttpURLConnection httpURLConnection) {
        try {
            return httpURLConnection.getResponseCode();
        } catch (IOException e8) {
            Log.e("URLConnection exception", e8.toString());
            return 0;
        }
    }

    public static byte[] getResponseContent(HttpURLConnection httpURLConnection) {
        InputStream errorStream;
        try {
            errorStream = httpURLConnection.getInputStream();
            String contentEncoding = httpURLConnection.getContentEncoding();
            if (contentEncoding != null) {
                if (contentEncoding.equalsIgnoreCase("gzip")) {
                    errorStream = new GZIPInputStream(httpURLConnection.getInputStream());
                } else if (contentEncoding.equalsIgnoreCase("deflate")) {
                    errorStream = new InflaterInputStream(httpURLConnection.getInputStream());
                }
            }
        } catch (IOException unused) {
            errorStream = httpURLConnection.getErrorStream();
        } catch (Exception e8) {
            Log.e("URLConnection exception", e8.toString());
            return null;
        }
        try {
            byte[] bArr = new byte[1024];
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            while (true) {
                int i8 = errorStream.read(bArr, 0, 1024);
                if (i8 == -1) {
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    byteArrayOutputStream.close();
                    return byteArray;
                }
                byteArrayOutputStream.write(bArr, 0, i8);
            }
        } catch (Exception e9) {
            Log.e("URLConnection exception", e9.toString());
            return null;
        }
    }

    public static String getResponseHeaderByIdx(HttpURLConnection httpURLConnection, int i8) {
        Map<String, List<String>> headerFields = httpURLConnection.getHeaderFields();
        if (headerFields == null) {
            return null;
        }
        int i9 = 0;
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            if (i9 == i8) {
                String key = entry.getKey();
                if (key == null) {
                    return listToString(entry.getValue(), ",") + "\n";
                }
                return key + ":" + listToString(entry.getValue(), ",") + "\n";
            }
            i9++;
        }
        return null;
    }

    public static String getResponseHeaderByKey(HttpURLConnection httpURLConnection, String str) {
        Map<String, List<String>> headerFields;
        if (str == null || (headerFields = httpURLConnection.getHeaderFields()) == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            if (str.equalsIgnoreCase(entry.getKey())) {
                return "set-cookie".equalsIgnoreCase(str) ? combinCookies(entry.getValue(), httpURLConnection.getURL().getHost()) : listToString(entry.getValue(), ",");
            }
        }
        return null;
    }

    public static int getResponseHeaderByKeyInt(HttpURLConnection httpURLConnection, String str) {
        String headerField = httpURLConnection.getHeaderField(str);
        if (headerField == null) {
            return 0;
        }
        return Integer.parseInt(headerField);
    }

    public static String getResponseHeaders(HttpURLConnection httpURLConnection) {
        Map<String, List<String>> headerFields = httpURLConnection.getHeaderFields();
        if (headerFields == null) {
            return null;
        }
        String str = "";
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            String key = entry.getKey();
            str = key == null ? str + listToString(entry.getValue(), ",") + "\n" : str + key + ":" + listToString(entry.getValue(), ",") + "\n";
        }
        return str;
    }

    public static String getResponseMessage(HttpURLConnection httpURLConnection) {
        try {
            return httpURLConnection.getResponseMessage();
        } catch (IOException e8) {
            String string = e8.toString();
            Log.e("URLConnection exception", string);
            return string;
        }
    }

    public static String listToString(List<String> list, String str) {
        if (list == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean z = false;
        for (String str2 : list) {
            if (z) {
                sb.append(str);
            }
            if (str2 == null) {
                str2 = "";
            }
            sb.append(str2);
            z = true;
        }
        return sb.toString();
    }

    public static void sendRequest(HttpURLConnection httpURLConnection, byte[] bArr) {
        try {
            OutputStream outputStream = httpURLConnection.getOutputStream();
            if (bArr != null) {
                outputStream.write(bArr);
                outputStream.flush();
            }
            outputStream.close();
        } catch (IOException e8) {
            Log.e("URLConnection exception", e8.toString());
        }
    }

    public static void setReadAndConnectTimeout(HttpURLConnection httpURLConnection, int i8, int i9) {
        httpURLConnection.setReadTimeout(i8);
        httpURLConnection.setConnectTimeout(i9);
    }

    public static void setRequestMethod(HttpURLConnection httpURLConnection, String str) {
        try {
            httpURLConnection.setRequestMethod(str);
            if (str.equalsIgnoreCase(POST_METHOD) || str.equalsIgnoreCase(PUT_METHOD)) {
                httpURLConnection.setDoOutput(true);
            }
        } catch (ProtocolException e8) {
            Log.e("URLConnection exception", e8.toString());
        }
    }

    public static void setVerifySSL(HttpURLConnection httpURLConnection, String str) {
        BufferedInputStream bufferedInputStream;
        if (httpURLConnection instanceof HttpsURLConnection) {
            HttpsURLConnection httpsURLConnection = (HttpsURLConnection) httpURLConnection;
            try {
                if (str.startsWith("/")) {
                    bufferedInputStream = new BufferedInputStream(new FileInputStream(str));
                } else {
                    bufferedInputStream = new BufferedInputStream(Cocos2dxHelper.getActivity().getAssets().open(str.substring(7)));
                }
                Certificate certificateGenerateCertificate = CertificateFactory.getInstance("X.509").generateCertificate(bufferedInputStream);
                System.out.println("ca=" + ((X509Certificate) certificateGenerateCertificate).getSubjectDN());
                bufferedInputStream.close();
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setCertificateEntry("ca", certificateGenerateCertificate);
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);
                SSLContext sSLContext = SSLContext.getInstance("TLS");
                sSLContext.init(null, trustManagerFactory.getTrustManagers(), null);
                httpsURLConnection.setSSLSocketFactory(sSLContext.getSocketFactory());
            } catch (Exception e8) {
                Log.e("URLConnection exception", e8.toString());
            }
        }
    }

    private static String str2Seconds(String str) {
        long timeInMillis;
        Calendar calendar = Calendar.getInstance();
        try {
            calendar.setTime(new SimpleDateFormat("EEE, dd-MMM-yy hh:mm:ss zzz", Locale.US).parse(str));
            timeInMillis = calendar.getTimeInMillis() / 1000;
        } catch (ParseException e8) {
            Log.e("URLConnection exception", e8.toString());
            timeInMillis = 0;
        }
        return Long.toString(timeInMillis);
    }
}
