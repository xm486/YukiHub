package com.yuki.yukihub.nextmoe;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OAuth 环回回调监听器（RFC 8252 §7 loopback redirect）。
 * <p>
 * NextMoe 注册的回调形状是 http://127.0.0.1/callback，端口无关匹配（§7.3）。
 * 正确时序：{@link #open()} 先绑出监听 socket 拿到端口 → 用该端口拼 redirect_uri 开
 * 系统浏览器 → {@link Handle#await()} 在同一 socket 上阻塞收码——端口必须同轮一致，
 * 否则浏览器 302 回来的端口没人监听。
 * <p>
 * 只绑 127.0.0.1 回环：流量不出设备；await 5 分钟超时（授权页操作时长）。
 */
public final class LoopbackListener {

    private static final String TAG = "NextMoe";
    private static final long TIMEOUT_MS = 5L * 60L * 1000L;

    /** 一次回调的结果。error 非空表示用户取消或授权失败。 */
    public static final class Result {
        public final String code;
        public final String state;
        public final String error;
        Result(String code, String state, String error) {
            this.code = code; this.state = state; this.error = error;
        }
    }

    /** 一次授权会话：open() 到 close() 之间保持监听。 */
    public static final class Handle implements java.io.Closeable {
        private final ServerSocket server;

        Handle(ServerSocket server) { this.server = server; }

        /** 本轮实际端口，redirect_uri 用它拼。 */
        public int getPort() { return server.getLocalPort(); }

        /** 阻塞收一个回调请求（后台线程调用）。 */
        public Result await() {
            try {
                server.setSoTimeout((int) TIMEOUT_MS);
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    String requestLine = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    Map<String, String> q = parseQuery(requestLine == null ? "" : requestLine);
                    respondAndClose(socket);
                    String error = q.get("error");
                    if (error != null && !error.isEmpty()) return new Result("", q.get("state"), error);
                    return new Result(q.get("code"), q.get("state"), "");
                }
            } catch (SocketTimeoutException e) {
                return new Result("", "", "timeout");
            } catch (Throwable t) {
                Log.w(TAG, "loopback await failed", t);
                return new Result("", "", "io");
            }
        }

        @Override public void close() {
            try { server.close(); } catch (Throwable ignored) { }
        }
    }

    /** 绑 127.0.0.1 临时端口开始监听。 */
    public static Handle open() throws java.io.IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Log.i(TAG, "loopback listening on port " + server.getLocalPort());
        return new Handle(server);
    }

    /** 请求行形如 GET /callback?code=..&state=.. HTTP/1.1 */
    private static Map<String, String> parseQuery(String requestLine) {
        Map<String, String> out = new HashMap<>();
        try {
            int sp1 = requestLine.indexOf(' ');
            int sp2 = requestLine.lastIndexOf(' ');
            if (sp1 < 0 || sp2 <= sp1) return out;
            String target = requestLine.substring(sp1 + 1, sp2);
            int qm = target.indexOf('?');
            if (qm < 0) return out;
            for (String pair : target.substring(qm + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse callback query failed", t);
        }
        return out;
    }

    private static void respondAndClose(Socket socket) {
        try {
            String body = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<title>YukiHub</title></head>"
                    + "<body style=\"font-family:sans-serif;text-align:center;padding-top:15vh;\">"
                    + "<h2>授权完成</h2><p>可以关闭此页面，回到 YukiHub。</p></body></html>";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            OutputStream os = socket.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(data);
            os.flush();
        } catch (Throwable ignored) { }
    }
}