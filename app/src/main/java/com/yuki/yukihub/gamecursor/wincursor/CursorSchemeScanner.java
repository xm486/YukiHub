package com.yuki.yukihub.gamecursor.wincursor;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Windows 光标方案文件夹扫描器。
 *
 * 一个光标包通常是这样：
 *   【某某】鼠标指针/
 *     AutoSetup.inf        安装脚本，里面有角色映射
 *     xxx_普通选择.ani
 *     xxx_链接选择.ani
 *     ...
 *
 * inf 的 [Strings] 段给出角色 → 文件名的映射，这是**唯一可靠**的角色判定依据：
 *   pointer = "从雨_普通选择.ani"     ← 正常状态，就是我们要的
 *   link    = "从雨_链接选择.ani"
 *   busy / work / text / cross / ...
 *
 * 编码坑：inf 是 GBK（简中包），按 UTF-8 读会得到一串乱码，
 * 文件名对不上就全盘失效。这里显式按 GBK 解码。
 */
public final class CursorSchemeScanner {
    /** Windows 光标角色 → 中文显示名。顺序即推荐展示顺序。 */
    private static final String[][] ROLE_NAMES = {
            {"pointer", "普通选择"},
            {"link", "链接选择"},
            {"text", "文本选择"},
            {"help", "帮助选择"},
            {"work", "后台运行"},
            {"busy", "忙"},
            {"cross", "精确选择"},
            {"hand", "手写"},
            {"unavailiable", "不可用"},
            {"unavailable", "不可用"},
            {"vert", "垂直调整"},
            {"horz", "水平调整"},
            {"dgn1", "对角调整1"},
            {"dgn2", "对角调整2"},
            {"move", "移动"},
            {"alternate", "候选"},
    };

    /** 扫描结果里的一项。 */
    public static final class Entry {
        /** 文件显示名。 */
        public final String fileName;
        /** 文件 Uri（SAF）。 */
        public final Uri uri;
        /** 角色中文名，未识别时为空。 */
        public final String roleName;
        /** 是否是「普通选择」——即推荐作为常态光标的那一个。 */
        public final boolean isNormal;
        /** 帧数（解码后填充）。 */
        public int frameCount;

        Entry(String fileName, Uri uri, String roleName, boolean isNormal) {
            this.fileName = fileName;
            this.uri = uri;
            this.roleName = roleName;
            this.isNormal = isNormal;
        }

        /** 列表里显示的标签。 */
        public String label() {
            if (roleName != null && !roleName.isEmpty()) {
                return isNormal ? roleName + "（正常状态）" : roleName;
            }
            return fileName;
        }
    }

    /** 扫描结果。 */
    public static final class Scheme {
        /** 方案名（inf 里的 SCHEME_NAME，没有就用文件夹名）。 */
        public String name;
        public final List<Entry> entries = new ArrayList<>();
        /** 是否成功读到 inf 的角色映射。false 时角色名全为空，需要用户自己认。 */
        public boolean hasRoleInfo;

        /** 推荐项（普通选择）在 entries 里的下标，没有则 -1。 */
        public int normalIndex() {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).isNormal) return i;
            }
            return -1;
        }
    }

    private CursorSchemeScanner() { }

    /**
     * 扫描一个通过 SAF 选中的文件夹。
     *
     * @param treeUri ACTION_OPEN_DOCUMENT_TREE 返回的 Uri
     */
    public static Scheme scan(Context ctx, Uri treeUri) {
        Scheme s = new Scheme();
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(ctx, treeUri);
            if (dir == null || !dir.isDirectory()) return s;
            s.name = dir.getName() != null ? dir.getName() : "光标方案";

            DocumentFile[] files = dir.listFiles();
            if (files == null) return s;

            // 先找 inf 读角色映射
            Map<String, String> roleToFile = new LinkedHashMap<>();
            String schemeName = null;
            for (DocumentFile f : files) {
                String n = f.getName();
                if (n == null || !n.toLowerCase(Locale.US).endsWith(".inf")) continue;
                byte[] raw = readAll(ctx, f.getUri(), 256 * 1024);
                if (raw == null) continue;
                Map<String, String> strings = parseInfStrings(raw);
                if (strings.isEmpty()) continue;
                schemeName = strings.get("scheme_name");
                for (String[] role : ROLE_NAMES) {
                    String v = strings.get(role[0]);
                    if (v != null && !v.isEmpty() && !roleToFile.containsValue(v)) {
                        roleToFile.put(v.toLowerCase(Locale.US), role[0]);
                    }
                }
                if (!roleToFile.isEmpty()) break;
            }
            if (schemeName != null && !schemeName.trim().isEmpty()) s.name = schemeName.trim();
            s.hasRoleInfo = !roleToFile.isEmpty();

            // 收集光标文件
            for (DocumentFile f : files) {
                String n = f.getName();
                if (n == null) continue;
                String lower = n.toLowerCase(Locale.US);
                if (!lower.endsWith(".ani") && !lower.endsWith(".cur")) continue;

                String roleKey = roleToFile.get(lower);
                String roleName = roleKey != null ? displayName(roleKey) : guessRole(lower);
                boolean isNormal = "pointer".equals(roleKey)
                        || (roleKey == null && isNormalByName(lower));
                s.entries.add(new Entry(n, f.getUri(), roleName, isNormal));
            }

            // 推荐项排最前，方便一眼看到
            java.util.Collections.sort(s.entries, (a, b) -> {
                if (a.isNormal != b.isNormal) return a.isNormal ? -1 : 1;
                boolean ar = a.roleName != null && !a.roleName.isEmpty();
                boolean br = b.roleName != null && !b.roleName.isEmpty();
                if (ar != br) return ar ? -1 : 1;
                return a.fileName.compareTo(b.fileName);
            });
        } catch (Throwable ignored) { }
        return s;
    }

    /**
     * 解析 inf 的 [Strings] 段。
     *
     * inf 是 GBK 编码（简中包），必须显式指定 —— 按 UTF-8 读会全是乱码，
     * 文件名匹配就全部失败。
     */
    private static Map<String, String> parseInfStrings(byte[] raw) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = decodeGbkOrUtf8(raw);
        if (text == null) return out;
        boolean inStrings = false;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("//")) continue;
            if (t.startsWith("[")) {
                inStrings = t.toLowerCase(Locale.US).startsWith("[strings]");
                continue;
            }
            if (!inStrings) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase(Locale.US);
            String v = t.substring(eq + 1).trim();
            // 去引号
            if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                v = v.substring(1, v.length() - 1);
            }
            out.put(k, v);
        }
        return out;
    }

    /** 先试 GBK，明显不对（含替换字符）再试 UTF-8。 */
    private static String decodeGbkOrUtf8(byte[] raw) {
        String gbk = tryCharset(raw, "GBK");
        if (gbk != null && gbk.indexOf('\uFFFD') < 0) return gbk;
        String utf8 = tryCharset(raw, "UTF-8");
        if (utf8 != null && utf8.indexOf('\uFFFD') < 0) return utf8;
        return gbk != null ? gbk : utf8;
    }

    private static String tryCharset(byte[] raw, String cs) {
        try {
            return new String(raw, cs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String displayName(String roleKey) {
        for (String[] r : ROLE_NAMES) {
            if (r[0].equals(roleKey)) return r[1];
        }
        return "";
    }

    /** 没有 inf 时按文件名关键词猜角色，猜不到返回空串。 */
    private static String guessRole(String lowerName) {
        if (isNormalByName(lowerName)) return "普通选择";
        if (contains(lowerName, "链接", "link", "hand")) return "链接选择";
        if (contains(lowerName, "文本", "text", "ibeam", "beam")) return "文本选择";
        if (contains(lowerName, "忙", "busy", "wait")) return "忙";
        if (contains(lowerName, "后台", "work", "appstarting")) return "后台运行";
        if (contains(lowerName, "精确", "cross", "precision")) return "精确选择";
        if (contains(lowerName, "帮助", "help")) return "帮助选择";
        if (contains(lowerName, "移动", "move", "sizeall")) return "移动";
        if (contains(lowerName, "不可用", "unavail", "no")) return "不可用";
        return "";
    }

    private static boolean isNormalByName(String lowerName) {
        return contains(lowerName, "普通", "正常", "normal", "arrow", "pointer", "default");
    }

    private static boolean contains(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /** 读取整个文件，超过 limit 返回 null（光标文件不该有几十兆）。 */
    public static byte[] readAll(Context ctx, Uri uri, int limit) {
        InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > limit) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Throwable ignored) { }
        }
    }
}