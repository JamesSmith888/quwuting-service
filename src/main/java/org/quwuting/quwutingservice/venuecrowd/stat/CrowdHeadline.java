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
 * 折叠头常驻摘要（2026-10-07，纯函数；2026-10-08 措辞改按营业日分流）。
 *
 * <h3>为什么要有</h3>
 * 真实用户集中在 23:00 之后上报，而有效期一过整张卡变成「暂无舞友上报」——
 * 最有价值的时刻（清晨 / 次日白天查「昨晚怎么样」）反而什么也看不到。摘要把最近一个
 * 营业日的结论带出来，并用<b>相对日期 + 具体时刻</b>如实表明「这不是此刻」。
 *
 * <h3>文案（2026-10-08 起：措辞跟营业日走，与窗口长短解耦）</h3>
 * <ul>
 *   <li>最近一个有票的营业日 = <b>当前营业日</b>：{@code 今晚 约100 · 3人}（无时刻——此刻正在进行的这一场）；</li>
 *   <li>= 前一营业日：{@code 昨晚 23:40 约50 · 1人}（白天时刻 = 昨天；更早 = M月D日）；
 *       仅 {@link #recent} 单独消费窗口外的「今天」票时给 {@code 今天 14:00 …}（带时刻 = 不是此刻）。</li>
 * </ul>
 * 为什么这样分流：窗口（{@link CrowdPolicy#VALID_WINDOW_HOURS}，1 天）只定义「数据还算不算数」；
 * 「今晚」是营业日概念（05:00 分界，{@link BusinessDay}）——窗口短于一个营业夜时两者恰好近似，
 * 一旦放宽（6h → 24h）就会分叉：清晨查昨晚，票仍在有效期内，但绝不许说成「今晚」。
 * <p>
 * 票集必须已剔除认领人；每个营业日内按「一人一票取最新」折票后取中位档。
 * <p>
 * 调用方分两条路径（避免热路径多一次查询）：窗口内有票 ⇒ 只用 {@link #fromWindow}（票已在手）；
 * 窗口内没有数据 ⇒ 才查回看范围，交给 {@link #recent}。{@link #build} 是二者的组合，给单测与一次性场景用。
 */
public final class CrowdHeadline {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日");

    private CrowdHeadline() {
    }

    /** 当前营业日摘要（无时刻）：{@code 今晚 约100 · 3人}。 */
    public static String tonight(CrowdVerdict tonight) {
        return "今晚 " + nameOf(tonight.medianLevel()) + " · " + tonight.voterCount() + "人";
    }

    /**
     * 有效期窗口内票的摘要（2026-10-08，summary() 消费）：按营业日分组取最近一个有票的营业日——
     * 当前营业日 ⇒ {@link #tonight}；否则（窗口放宽到 1 天后，清晨 / 白天窗口内仍留有昨晚的票）
     * ⇒ 与 {@link #recent} 同款的「昨晚 / 昨天 / 更早 + 时刻」措辞。窗口长短只影响
     * 「数据还算不算数」，措辞一律跟营业日走。
     */
    public static Optional<String> fromWindow(LocalDateTime now, List<CrowdVote> votes) {
        return latestNight(now, votes, true);
    }

    /**
     * 窗口外摘要：回看范围内最近一个有票的营业日。
     *
     * @param votes 回看范围内的票（已剔除认领人；可含同一人多张）
     */
    public static Optional<String> recent(LocalDateTime now, List<CrowdVote> votes) {
        LocalDateTime lookbackStart = BusinessDay.startOf(
                BusinessDay.of(now).minusDays(CrowdPolicy.HEADLINE_LOOKBACK_DAYS));
        List<CrowdVote> inLookback = votes.stream()
                .filter(v -> !v.at().isBefore(lookbackStart))
                .toList();
        return latestNight(now, inLookback, false);
    }

    /**
     * 组合入口：窗口内有票 ⇒ 窗口内口径；否则回看最近营业日。
     *
     * @param votes 回看范围内的全部票（已剔除认领人）
     */
    public static Optional<String> build(LocalDateTime now, List<CrowdVote> votes) {
        LocalDateTime windowStart = now.minusHours(CrowdPolicy.VALID_WINDOW_HOURS);
        List<CrowdVote> inWindow = votes.stream().filter(v -> !v.at().isBefore(windowStart)).toList();
        if (!inWindow.isEmpty()) {
            return fromWindow(now, inWindow);
        }
        return recent(now, votes);
    }

    /**
     * 最近一个有票营业日的摘要（上两条路径共用的唯一实现）。
     *
     * @param inWindow 这批票是否来自有效期窗口内——决定「当前营业日」的措辞：窗口内
     *                 （此刻仍有效的实时数据）=「今晚」无时刻；窗口外（回看）=「今天」+ 时刻。
     */
    private static Optional<String> latestNight(LocalDateTime now, List<CrowdVote> votes, boolean inWindow) {
        if (votes.isEmpty()) {
            return Optional.empty();
        }
        Map<LocalDate, List<CrowdVote>> byNight = votes.stream()
                .collect(Collectors.groupingBy(v -> BusinessDay.of(v.at())));
        Map.Entry<LocalDate, List<CrowdVote>> latest = byNight.entrySet().stream()
                .max(Map.Entry.comparingByKey())
                .orElseThrow();
        CrowdVerdict verdict = CrowdConsensus.evaluate(CrowdConsensus.latestPerVoter(latest.getValue()));
        if (inWindow && latest.getKey().equals(BusinessDay.of(now))) {
            return Optional.of(tonight(verdict));
        }
        LocalDateTime latestAt = latest.getValue().stream()
                .map(CrowdVote::at).max(Comparator.naturalOrder()).orElse(now);
        return Optional.of(dayLabel(now, latest.getKey(), latestAt) + " " + latestAt.format(TIME) + " "
                + nameOf(verdict.medianLevel()) + " · " + verdict.voterCount() + "人");
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
