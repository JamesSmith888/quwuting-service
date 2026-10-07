package org.quwuting.quwutingservice.venuecrowd.stat;

import org.quwuting.quwutingservice.venuecrowd.enums.CrowdFemaleLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 折叠头常驻摘要（2026-10-07，纯函数）：折叠态也能一眼看到「这家店有没有人报过」。
 *
 * <h3>为什么要有</h3>
 * 真实用户集中在 23:00 之后上报，而 6h 窗口一过整张卡变成「暂无舞友上报」——
 * 最有价值的时刻（清晨 / 次日白天查「昨晚怎么样」）反而什么也看不到。摘要把窗口外最近一个营业日的
 * 结论带出来，并用<b>相对日期 + 具体时刻</b>如实表明「这不是此刻」。
 *
 * <h3>文案</h3>
 * <ul>
 *   <li>窗口内有票：{@code 今晚 约100 · 3人}；</li>
 *   <li>窗口外最近一个有票的营业日：{@code 昨晚 23:40 约50 · 1人}（同营业日 = 今天；前天及更早 = M月D日）。</li>
 * </ul>
 * 票集必须已剔除认领人；每个营业日内按「一人一票取最新」折票后取中位档。
 * <p>
 * 调用方分两条路径（避免热路径多一次查询）：窗口内有数据 ⇒ 只用 {@link #tonight}（结论已在手）；
 * 窗口内没有数据 ⇒ 才查回看范围，交给 {@link #recent}。{@link #build} 是二者的组合，给单测与一次性场景用。
 */
public final class CrowdHeadline {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日");

    private CrowdHeadline() {
    }

    /** 窗口内摘要：{@code 今晚 约100 · 3人}。 */
    public static String tonight(CrowdVerdict tonight) {
        return "今晚 " + nameOf(tonight.medianLevel()) + " · " + tonight.voterCount() + "人";
    }

    /**
     * 窗口外摘要：回看范围内最近一个有票的营业日。
     *
     * @param votes 回看范围内的票（已剔除认领人；可含同一人多张）
     */
    public static Optional<String> recent(LocalDateTime now, List<CrowdVote> votes) {
        LocalDateTime lookbackStart = BusinessDay.startOf(
                BusinessDay.of(now).minusDays(CrowdPolicy.HEADLINE_LOOKBACK_DAYS));
        Map<LocalDate, List<CrowdVote>> byNight = votes.stream()
                .filter(v -> !v.at().isBefore(lookbackStart))
                .collect(Collectors.groupingBy(v -> BusinessDay.of(v.at())));
        return byNight.entrySet().stream()
                .max(Map.Entry.comparingByKey())
                .map(entry -> {
                    CrowdVerdict verdict = CrowdConsensus.evaluate(CrowdConsensus.latestPerVoter(entry.getValue()));
                    LocalDateTime latestAt = entry.getValue().stream()
                            .map(CrowdVote::at).max(Comparator.naturalOrder()).orElse(now);
                    return dayLabel(now, entry.getKey(), latestAt) + " " + latestAt.format(TIME) + " "
                            + nameOf(verdict.medianLevel()) + " · " + verdict.voterCount() + "人";
                });
    }

    /**
     * 组合入口：窗口内有票 ⇒ 今晚摘要；否则回看最近营业日。
     *
     * @param votes 回看范围内的全部票（已剔除认领人）
     */
    public static Optional<String> build(LocalDateTime now, List<CrowdVote> votes) {
        LocalDateTime windowStart = now.minusHours(CrowdPolicy.TONIGHT_WINDOW_HOURS);
        List<CrowdVote> inWindow = votes.stream().filter(v -> !v.at().isBefore(windowStart)).toList();
        if (!inWindow.isEmpty()) {
            return Optional.of(tonight(CrowdConsensus.evaluate(CrowdConsensus.latestPerVoter(inWindow))));
        }
        return recent(now, votes);
    }

    /** 相对日期措辞：同营业日 = 今天；前一营业日 = 昨晚（夜间时刻）/ 昨天；更早 = M月D日。 */
    static String dayLabel(LocalDateTime now, LocalDate businessDate, LocalDateTime latestAt) {
        long diff = ChronoUnit.DAYS.between(businessDate, BusinessDay.of(now));
        if (diff <= 0) {
            return "今天";
        }
        if (diff == 1) {
            return BusinessDay.isNightHour(latestAt) ? "昨晚" : "昨天";
        }
        return businessDate.format(MONTH_DAY);
    }

    private static String nameOf(int level) {
        return CrowdFemaleLevel.of(level).getDisplayName();
    }
}
