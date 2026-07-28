package com.yuki.yukihub.translate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义翻译 API 的「JSON 响应路径」解析工具。
 *
 * 适配说明：参考 MoeTranslate 的 JsonPathParser.kt，转换为 Java。
 * 保留原项目版权和 LGPL 声明。
 *
 * 支持的写法示例：
 *   - result
 *   - data.translation
 *   - data.list[0].text
 *   - data.items[0][1].value
 *
 * 语法说明：
 *   - "."   分隔对象（JSONObject）的属性名；
 *   - "[n]" 访问数组（JSONArray）中第 n 个元素（下标从 0 开始）；
 *   - 二者可任意组合，如 a.b[0].c、a[0][1] 等。
 *
 * 注意：属性名中不能包含 "."、"["、"]" 这三个用于分隔的字符。
 */
public final class JsonPathParser {

    /** 路径中的单个访问单元：对象属性 或 数组下标 */
    private abstract static class Token {
    }

    private static final class KeyToken extends Token {
        final String name;

        KeyToken(String name) {
            this.name = name;
        }
    }

    private static final class IndexToken extends Token {
        final int index;

        IndexToken(int index) {
            this.index = index;
        }
    }

    /**
     * 按路径从 JSON 对象中取出目标值，并转为字符串返回。
     *
     * @param root 已解析的根 JSON 对象
     * @param path 用户配置的响应路径，如 "data.list[0].text"
     * @return 目标节点的字符串表示
     * @throws IllegalArgumentException  路径语法非法（空路径、下标非法等）
     * @throws IllegalStateException     路径与实际 JSON 结构不匹配（类型不符或键不存在）
     * @throws IndexOutOfBoundsException 数组下标越界
     */
    public static String parse(JSONObject root, String path) {
        List<Token> tokens = tokenize(path);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("JSON path is empty");
        }

        Object current = root;
        StringBuilder traversed = new StringBuilder();

        for (Token token : tokens) {
            if (token instanceof KeyToken) {
                KeyToken key = (KeyToken) token;
                if (!(current instanceof JSONObject)) {
                    throw new IllegalStateException(
                            "Node at \"" + here(traversed) + "\" is not a JSON object, " +
                                    "cannot read key \"" + key.name + "\""
                    );
                }
                JSONObject obj = (JSONObject) current;
                if (!obj.has(key.name)) {
                    throw new IllegalStateException(
                            "Key \"" + key.name + "\" not found at \"" + here(traversed) + "\""
                    );
                }
                if (traversed.length() > 0) traversed.append('.');
                traversed.append(key.name);
                current = obj.opt(key.name);
            } else if (token instanceof IndexToken) {
                IndexToken idx = (IndexToken) token;
                if (!(current instanceof JSONArray)) {
                    throw new IllegalStateException(
                            "Node at \"" + here(traversed) + "\" is not a JSON array, " +
                                    "cannot use index [" + idx.index + "]"
                    );
                }
                JSONArray arr = (JSONArray) current;
                if (idx.index >= arr.length()) {
                    throw new IndexOutOfBoundsException(
                            "Index [" + idx.index + "] out of bounds at " +
                                    "\"" + here(traversed) + "\" (length " + arr.length() + ")"
                    );
                }
                traversed.append('[').append(idx.index).append(']');
                current = arr.opt(idx.index);
            }
        }

        return current == null ? "" : current.toString();
    }

    private static String here(StringBuilder traversed) {
        return traversed.length() > 0 ? traversed.toString() : "<root>";
    }

    /**
     * 将路径字符串解析为 Token 序列。
     */
    private static List<Token> tokenize(String path) {
        List<Token> tokens = new ArrayList<>();
        String[] segments = path.split("\\.");

        for (String segment : segments) {
            int bracketStart = segment.indexOf('[');
            String key = bracketStart == -1 ? segment : segment.substring(0, bracketStart);

            if (key.isEmpty() && bracketStart == -1) {
                throw new IllegalArgumentException("Empty segment in path: \"" + path + "\"");
            }

            if (key.length() > 0) {
                if (key.contains("]")) {
                    throw new IllegalArgumentException("Unexpected ']' in segment: \"" + segment + "\"");
                }
                tokens.add(new KeyToken(key));
            }

            int i = bracketStart == -1 ? segment.length() : bracketStart;
            while (i < segment.length()) {
                if (segment.charAt(i) != '[') {
                    throw new IllegalArgumentException(
                            "Unexpected character '" + segment.charAt(i) + "' in segment: \"" + segment + "\""
                    );
                }
                int close = segment.indexOf(']', i);
                if (close == -1) {
                    throw new IllegalArgumentException("Missing ']' in segment: \"" + segment + "\"");
                }
                String indexText = segment.substring(i + 1, close);
                if (indexText.isEmpty() || !indexText.matches("\\d+")) {
                    throw new IllegalArgumentException(
                            "Invalid array index \"[" + indexText + "]\" in segment: \"" + segment + "\""
                    );
                }
                int index;
                try {
                    index = Integer.parseInt(indexText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Array index \"[" + indexText + "]\" is too large in segment: \"" + segment + "\""
                    );
                }
                tokens.add(new IndexToken(index));
                i = close + 1;
            }
        }

        return tokens;
    }

    private JsonPathParser() {
    }
}