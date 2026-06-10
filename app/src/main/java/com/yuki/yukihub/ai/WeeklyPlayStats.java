package com.yuki.yukihub.ai;

import com.yuki.yukihub.data.GameRepository.PlayActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeeklyPlayStats {
    public long startTime;
    public long endTime;
    public long totalDuration;
    public int sessionCount;
    public int activeDays;
    public long averageSessionDuration;
    public long longestSessionDuration;
    public String longestSessionGame = "";
    public int morningCount;
    public int afternoonCount;
    public int nightCount;
    public int otherTimeCount;
    public int weekdayCount;
    public int weekendCount;
    public int totalGameCount;
    public int completedGameCount;
    public int playingGameCount;
    public int unplayedGameCount;
    public final LinkedHashMap<String, Long> topGames = new LinkedHashMap<>();
    public final List<PlayActivity> recentSessions = new ArrayList<>();
    public final Map<String, Integer> gameSessionCounts = new LinkedHashMap<>();
    public final Map<String, String> gameStatuses = new LinkedHashMap<>();
    public final Map<String, String> gameMetadata = new LinkedHashMap<>();

    public boolean isEmpty() {
        return totalDuration <= 0L || sessionCount <= 0 || topGames.isEmpty();
    }

    public int gameCount() {
        return totalGameCount > 0 ? totalGameCount : topGames.size();
    }
}
