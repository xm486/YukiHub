package com.yuki.yukihub.importer;

/**
 * 导入结果统计。
 */
public class ImportResult {
    public int success;
    public int skipped;
    public int failed;
    public java.util.List<String> failedNames = new java.util.ArrayList<>();
    public java.util.List<String> skippedNames = new java.util.ArrayList<>();
    public int sessionsImported;

    @Override
    public String toString() {
        return "成功 " + success + "，跳过 " + skipped + "，失败 " + failed
                + (sessionsImported > 0 ? "，游玩记录 " + sessionsImported : "");
    }
}