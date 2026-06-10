package com.yuki.yukihub.ai;

import com.yuki.yukihub.data.GameRepository.PlayActivity;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class AiReviewPromptBuilder {
    private AiReviewPromptBuilder() { }

    public static String buildSystemPrompt(AiReviewSettings settings) {
        AiReviewSettings s = settings == null ? new AiReviewSettings() : settings;
        s.normalize();
        StringBuilder sb = new StringBuilder();
        sb.append(s.systemPrompt).append("\n\n");
        sb.append("[角色执行优先级 - MUST FOLLOW]\n");
        sb.append("- 你首先是上方定义的人设，不是中立助手、新闻编辑或统计播报员。\n");
        sb.append("- 先按人设开口，再用数据做支撑；所有评论都应像在直接点评玩家本人。\n");
        sb.append("- 允许有鲜明态度，但判断必须能从给定数据中找到依据。\n");
        sb.append("- 不要编造不存在的游戏、时长、通关、开坑或作息情况。\n");
        sb.append("- 如果数据量少，请聚焦最明显的一两个特征，不要为了凑结构硬写。\n\n");

        sb.append("[隐私与环境说明]\n");
        sb.append("- 用户使用的是本地游戏管理和启动器软件。不要在点评里强调软件名。\n");
        sb.append("- 只根据提供的统计点评，不要要求用户上传路径、存档、账号或 API Key。\n");
        sb.append("- 不要提及本地路径、SD 卡路径、存档路径、rootUri 或任何账号信息。\n\n");

        sb.append("[剧透控制 - MUST FOLLOW]\n");
        if ("open".equals(s.spoilerLevel)) {
            sb.append("- 可泛泛结合游戏类型、题材氛围做评论，但不要主动写关键结局、角色命运或大转折。\n");
        } else if ("mild".equals(s.spoilerLevel)) {
            sb.append("- 可以讨论游戏类型、氛围和非关键设定，但避免具体剧情、结局、角色关系发展。\n");
        } else {
            sb.append("- 严禁提及任何具体剧情、结局、角色关系、角色命运或路线发展。\n");
            sb.append("- 仅允许讨论游玩习惯、时长分布、游戏类型倾向和清坑状态。\n");
        }
        sb.append("\n[输出约束]\n");
        sb.append("- 必须输出中文 JSON，不要包裹 Markdown 代码块。\n");
        sb.append("- 字段必须完整：title, subtitle, scoreName, score, roast, highlights, topGamesComment, advice, oneLine。\n");
        sb.append("- score 必须是 0 到 100 的整数。\n");
        sb.append("- roast 控制在 180 字以内，可以比短评更丰满，但不要写成长文。\n");
        sb.append("- highlights 和 advice 各 2-4 条即可，建议要具体，不要只写空话。\n");
        sb.append("- topGamesComment 只点评数据中出现的游戏；每条可以写 1-2 句，结合时长、状态和作品资料。\n");
        sb.append("- 必须尊重游戏当前库状态：状态为“🏆 玩过（等同通关/已完成）”的游戏已经通关，不要建议用户下周再通关它。\n");
        sb.append("- 已通关后本周仍有时长时，应优先理解为重温、回味、补后日谈/FD/CG/BGM、截图留念或写感想，不要默认判定为负面行为。\n");
        sb.append("- 禁止使用或变体表达：重复游玩已通关游戏、再通关已玩过游戏、克制已通关游戏、通关游戏花痴/浪费时间。\n");
        sb.append("- 状态为“🎮 在玩（尚未通关）”的游戏才可以建议继续推进；状态为“☆ 未玩（未开始/未通关）”的游戏可以建议少开坑或先尝试。\n");
        sb.append("- 输出前自检 advice 和 roast：如果某游戏是玩过/已完成，不得把继续打开它说成错误；可以建议“写感想/整理截图/换一部在玩作品推进”。\n");
        sb.append("- 如果上下文包含作品资料增强，只能用来判断类型、风格、长度、标签倾向；不要把简介摘要扩写成剧情介绍，不要泄露关键情节。\n");
        return sb.toString();
    }

    public static String buildContextPrompt(WeeklyPlayStats stats) {
        WeeklyPlayStats s = stats == null ? new WeeklyPlayStats() : stats;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 最近 7 天游玩数据快照 ===\n\n");
        sb.append("统计周期：\n");
        sb.append(dateOnly(s.startTime)).append(" ~ ").append(dateOnly(s.endTime)).append("\n\n");
        sb.append("总体：\n");
        sb.append("- 总游玩时长：").append(TimeFormatUtil.playTime(s.totalDuration)).append("\n");
        sb.append("- 活跃天数：").append(s.activeDays).append(" 天\n");
        sb.append("- 游玩游戏数：").append(s.gameCount()).append(" 款\n");
        sb.append("- 本周涉及游戏状态：玩过/已完成 ").append(s.completedGameCount).append(" 款，在玩 ").append(s.playingGameCount).append(" 款，未玩 ").append(s.unplayedGameCount).append(" 款\n");
        sb.append("- 游玩次数：").append(s.sessionCount).append(" 次\n");
        sb.append("- 平均单次时长：").append(TimeFormatUtil.playTime(s.averageSessionDuration)).append("\n");
        sb.append("- 最长单次：").append(TimeFormatUtil.playTime(s.longestSessionDuration));
        if (s.longestSessionGame != null && !s.longestSessionGame.trim().isEmpty()) sb.append("（《").append(s.longestSessionGame).append("》）");
        sb.append("\n\n");

        if (s.topGames.isEmpty()) {
            sb.append("游玩排行：无\n\n");
        } else {
            sb.append("游玩排行（Top 5）：\n");
            int i = 0;
            for (Map.Entry<String, Long> e : s.topGames.entrySet()) {
                if (i >= 5) break;
                long duration = e.getValue() == null ? 0L : e.getValue();
                int percent = s.totalDuration > 0 ? Math.round(duration * 100f / s.totalDuration) : 0;
                Integer count = s.gameSessionCounts.get(e.getKey());
                String status = s.gameStatuses.get(e.getKey());
                sb.append(i + 1).append(". 《").append(e.getKey()).append("》 ")
                        .append(TimeFormatUtil.playTime(duration)).append("，占比 ").append(percent).append("%");
                if (status != null && !status.trim().isEmpty()) sb.append("，当前库状态：").append(status);
                if (count != null && count > 0) sb.append("，启动 ").append(count).append(" 次");
                sb.append("\n");
                i++;
            }
            sb.append("\n");
        }

        if (!s.gameMetadata.isEmpty()) {
            sb.append("作品资料增强（来自本地已缓存的 VNDB/Bangumi/本地资料；只能作为风格和类型参考，不可编造剧情）：\n");
            int metaCount = 0;
            for (Map.Entry<String, String> e : s.gameMetadata.entrySet()) {
                if (metaCount >= 5) break;
                if (e.getValue() == null || e.getValue().trim().isEmpty()) continue;
                sb.append("- 《").append(e.getKey()).append("》：").append(e.getValue()).append("\n");
                metaCount++;
            }
            sb.append("\n");
        }

        sb.append("游玩时段分布：\n");
        sb.append("- 深夜 22-4 时：").append(s.nightCount).append(" 次\n");
        sb.append("- 上午 8-12 时：").append(s.morningCount).append(" 次\n");
        sb.append("- 下午 13-19 时：").append(s.afternoonCount).append(" 次\n");
        sb.append("- 其他时段：").append(s.otherTimeCount).append(" 次\n");
        sb.append("- 工作日：").append(s.weekdayCount).append(" 次\n");
        sb.append("- 周末：").append(s.weekendCount).append(" 次\n\n");

        sb.append("最近动态：\n");
        if (s.recentSessions.isEmpty()) {
            sb.append("- 无\n");
        } else {
            int count = 0;
            for (PlayActivity a : s.recentSessions) {
                if (a == null || count >= 8) break;
                String title = a.gameTitle == null || a.gameTitle.trim().isEmpty() ? "未命名游戏" : a.gameTitle;
                String status = s.gameStatuses.get(title);
                sb.append("- ").append(shortDate(a.endTime)).append(" 玩了《")
                        .append(title)
                        .append("》 ").append(TimeFormatUtil.playTime(a.duration))
                        .append("，").append(launchTypeLabel(a.launchType));
                if (status != null && !status.trim().isEmpty()) sb.append("，当前库状态：").append(status);
                sb.append("\n");
                count++;
            }
        }
        return sb.toString();
    }

    public static String buildTaskPrompt() {
        return "=== 任务指令 ===\n\n"
                + "请基于以上数据，以你的人设，对欧尼酱最近 7 天的游玩表现写一份 AI 周点评。\n"
                + "优先抓住最鲜明的一两个特征，例如沉迷一款游戏、开坑太多、深夜游玩、清坑效率高、游玩量过低等。\n"
                + "不要写成统计报告，不要自称 AI，不要解释你如何分析。\n"
                + "建议必须结合当前库状态：已标为玩过的游戏等同通关/已完成，不要建议再通关；在玩游戏可建议继续推进；未玩游戏可建议少开坑或先试试。\n"
                + "如果已通关游戏本周仍有时长，请按“重温/回味/补内容/整理感想”处理，不要把它批评成重复游玩、浪费时间或需要克制的坏习惯。\n"
                + "如果数据很少，就轻度吐槽并鼓励继续记录。\n\n"
                + "请严格输出以下 JSON：\n"
                + "{\n"
                + "  \"title\": \"短标题\",\n"
                + "  \"subtitle\": \"一句话总结\",\n"
                + "  \"scoreName\": \"指数名\",\n"
                + "  \"score\": 0,\n"
                + "  \"roast\": \"180字以内主要点评，要叫用户欧尼酱\",\n"
                + "  \"highlights\": [\"亮点1\", \"亮点2\", \"亮点3\"],\n"
                + "  \"topGamesComment\": [{\"game\": \"游戏名\", \"comment\": \"1到2句点评，可结合类型/标签但不剧透\"}],\n"
                + "  \"advice\": [\"建议1\", \"建议2\", \"建议3\"],\n"
                + "  \"oneLine\": \"底部一句话\"\n"
                + "}";
    }

    private static String dateOnly(long t) {
        if (t <= 0) return "未知";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(t));
    }

    private static String shortDate(long t) {
        if (t <= 0) return "未知时间";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
    }

    private static String launchTypeLabel(String launchType) {
        String t = launchType == null ? "" : launchType;
        if (t.startsWith("internal.krkr")) return "内置 KRKR";
        if (t.startsWith("internal.ons")) return "内置 ONS";
        if (t.startsWith("internal.tyrano")) return "内置 Tyrano";
        if (t.startsWith("internal.artemis")) return "内置 Artemis";
        if ("manual".equals(t)) return "手动补记";
        return "外部模拟器";
    }
}