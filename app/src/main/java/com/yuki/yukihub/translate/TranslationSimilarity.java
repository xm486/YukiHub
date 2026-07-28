package com.yuki.yukihub.translate;

/**
 * 文本相似度计算工具。
 *
 * 适配说明：参考 MoeTranslate UtilTools.calculateSimilarity / levenshteinDistance（Kotlin），
 * 转换为 Java 静态方法。保留原算法逻辑（Levenshtein 编辑距离）。
 *
 * 后续复制/修改相关代码时需保留原项目版权和 LGPL 声明。
 */
public final class TranslationSimilarity {

    private TranslationSimilarity() {
        // 工具类，不可实例化
    }

    /**
     * 计算两个字符串的相似度。
     *
     * @param str1 第一个字符串
     * @param str2 第二个字符串
     * @return 相似度，范围 0.0 ~ 1.0
     */
    public static double calculateSimilarity(String str1, String str2) {
        if (str1 == null) str1 = "";
        if (str2 == null) str2 = "";

        if (str1.isEmpty() && str2.isEmpty()) return 1.0;
        if (str1.isEmpty() || str2.isEmpty()) return 0.0;

        int distance = levenshteinDistance(str1, str2);
        int maxLength = Math.max(str1.length(), str2.length());

        return ((double) (maxLength - distance)) / maxLength;
    }

    /**
     * 计算Levenshtein编辑距离。
     *
     * @param str1 第一个字符串
     * @param str2 第二个字符串
     * @return 编辑距离
     */
    private static int levenshteinDistance(String str1, String str2) {
        int len1 = str1.length();
        int len2 = str2.length();

        // 创建距离矩阵
        int[][] dp = new int[len1 + 1][len2 + 1];

        // 初始化第一行和第一列
        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        // 填充矩阵
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (str1.charAt(i - 1) == str2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[len1][len2];
    }
}