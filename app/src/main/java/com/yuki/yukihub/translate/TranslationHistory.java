package com.yuki.yukihub.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程级「翻译历史」滚动缓冲。
 *
 * 适配说明：参考 MoeTranslate 的 TranslationHistory（Kotlin object），
 * 转换为 Java 单例。保留原项目版权和 LGPL 声明。
 *
 * 仅对基于提示词的引擎（如 OpenAI、Llama.cpp）有意义：每次成功翻译后
 * 记录一条 (原文, 译文)，构建下一次提示词时按需取最近 N 条作为上下文，
 * 帮助模型在连续 OCR 场景里保持术语 / 人名 / 语气一致。
 *
 * 设计：
 *   - 纯内存保存，进程结束即清空。
 *   - 容量上限 MAX_CAPACITY，避免长时间会话内无限增长。
 *   - 全部方法 synchronized：记录发生在翻译线程，读取发生在构建提示词时，需互斥。
 */
public final class TranslationHistory {

    private static volatile TranslationHistory instance;

    private TranslationHistory() {
    }

    public static TranslationHistory getInstance() {
        if (instance == null) {
            synchronized (TranslationHistory.class) {
                if (instance == null) {
                    instance = new TranslationHistory();
                }
            }
        }
        return instance;
    }

    private static final int MAX_CAPACITY = 100;

    private static final class Record {
        final String source;
        final String translated;

        Record(String source, String translated) {
            this.source = source;
            this.translated = translated;
        }
    }

    private final List<Record> records = new ArrayList<>();

    /**
     * 记录一条成功的翻译；原文或译文为空则忽略。
     * 满 MAX_CAPACITY 时滚动丢弃最旧的一条。
     */
    public synchronized void record(String source, String translated) {
        if (source == null || source.trim().isEmpty()) return;
        if (translated == null || translated.trim().isEmpty()) return;
        records.add(new Record(source, translated));
        while (records.size() > MAX_CAPACITY) {
            records.remove(0);
        }
    }

    /**
     * 取最近 count 条（不足则取全部），按时间顺序（旧→新）返回。
     * count<=0 或无记录时返回空表。
     */
    public synchronized List<String[]> latest(int count) {
        if (count <= 0 || records.isEmpty()) return new ArrayList<>();
        int n = Math.min(count, records.size());
        List<String[]> result = new ArrayList<>(n);
        for (int i = records.size() - n; i < records.size(); i++) {
            Record r = records.get(i);
            result.add(new String[]{r.source, r.translated});
        }
        return result;
    }

    /**
     * 清空缓冲。
     */
    public synchronized void clear() {
        records.clear();
    }

    /**
     * 构建追加到系统提示词后的历史参考文本；无历史时返回空串。
     *
     * 格式：
     * {prefix}
     *
     * 1.
     * {原文}
     * {译文}
     *
     * 2.
     * {原文}
     * {译文}
     */
    public String buildHistoryBlock(String prefix, int count) {
        List<String[]> items = latest(count);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        for (int i = 0; i < items.size(); i++) {
            sb.append("\n\n");
            sb.append(i + 1).append(".\n");
            sb.append(items.get(i)[0]).append('\n');
            sb.append(items.get(i)[1]);
        }
        return sb.toString();
    }

    /**
     * 把历史参考文本追加到 systemPrompt 之后（中间空一行）。
     * 无历史时原样返回 systemPrompt。
     */
    public String appendHistory(String systemPrompt, String prefix, int count) {
        String block = buildHistoryBlock(prefix, count);
        return block.isEmpty() ? systemPrompt : systemPrompt + "\n\n" + block;
    }
}