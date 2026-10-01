package org.quwuting.quwutingservice.venue.dailyopening.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.SuspendCityImpact;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 批量置「暂停营业」的影响面熔断（2026-10-01，方案见 docs/agents/33-venue-sync-skill.md
 * 「关门方向影响面熔断」）。
 *
 * <h2>为什么必须在服务端</h2>
 * 关门方向是「白名单差集推断」：某城被舞讯覆盖、而某店不在名单内 ⇒ 判为未营业。推断的正确性
 * 完全取决于<b>来源当日是否完整</b>——来源漏发一个城市的半份名单、匹配引擎一次回归、Agent 算错
 * 覆盖边界，都会让「整城营业中的门店」在一次调用里被批量改成暂停营业，并给每个收藏者推送
 * 微信服务通知（真实触达，次日恢复时再推一次）。此前服务端只有 {@code @Size(max=500)}，
 * 「范围由调用方保证」意味着护栏全在 Skill 提示词里，而提示词本身就是事故源之一。
 *
 * <h2>判据</h2>
 * 按城市评估：本批将被暂停的门店数 ≥ {@link #KEY_MIN_COUNT}（小样本不熔断，噪声大）
 * <b>且</b> 占该城当前营业中门店的比例 &gt; {@link #KEY_MAX_RATIO_PERCENT}% ⇒ 该城<b>熔断</b>。
 * 熔断不是「禁止」，是「要求显式确认」：调用方在 {@code confirmedCities} 里逐城声明
 * 「已核实，确实大面积未营业」（如节假日整城歇业）即可放行——确认的粒度是城市，
 * 不提供整批一键越过，避免确认一个城顺手放过别的城。
 * 阈值是运营配置（可热更新），{@code max_ratio_percent = 100} 等于关闭比例熔断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuspendBlastRadiusGuard {

    /** 比例熔断的最小样本量：本批对某城暂停数低于它时不熔断（默认 5）。 */
    public static final String KEY_MIN_COUNT = "venue.suspend_guard.min_count";

    /** 单城单批暂停占比上限（百分比，1~100；默认 50；100 = 关闭比例熔断）。 */
    public static final String KEY_MAX_RATIO_PERCENT = "venue.suspend_guard.max_ratio_percent";

    static final int DEFAULT_MIN_COUNT = 5;
    static final int DEFAULT_MAX_RATIO_PERCENT = 50;

    /** 熔断错误码（登记见 docs/agents/12-api-conventions.md） */
    static final int CODE_BLAST_RADIUS_EXCEEDED = 1036;

    /** 门店城市为空时的分组名（不应出现：city 为必填；出现即数据问题，照样参与评估） */
    private static final String UNKNOWN_CITY = "（未填城市）";

    private final VenueRepository venueRepository;
    private final OpsConfigService opsConfigService;

    /**
     * 评估本批计划暂停的门店对各城市的影响面（零副作用）。
     *
     * @param plannedCities   计划暂停的门店所在城市（一店一项，可重复）
     * @param confirmedCities 调用方已确认放行的城市（可空）
     * @return 每个涉及城市一行，按城市名排序（稳定输出，便于汇报与比对）
     */
    public List<SuspendCityImpact> evaluate(Collection<String> plannedCities, Collection<String> confirmedCities) {
        Map<String, Integer> toSuspend = new TreeMap<>();
        for (String city : plannedCities) {
            toSuspend.merge(cityKey(city), 1, Integer::sum);
        }
        if (toSuspend.isEmpty()) {
            return List.of();
        }
        Map<String, Long> openByCity = new HashMap<>();
        for (Object[] row : venueRepository.countByCityInAndStatus(toSuspend.keySet(), VenueStatus.OPEN)) {
            openByCity.put(cityKey((String) row[0]), ((Number) row[1]).longValue());
        }
        Set<String> confirmed = new HashSet<>();
        if (confirmedCities != null) {
            for (String city : confirmedCities) {
                if (city != null && !city.isBlank()) {
                    confirmed.add(city.trim());
                }
            }
        }
        int minCount = readMinCount();
        int maxRatio = readMaxRatioPercent();

        List<SuspendCityImpact> impacts = new ArrayList<>(toSuspend.size());
        for (Map.Entry<String, Integer> entry : toSuspend.entrySet()) {
            String city = entry.getKey();
            int count = entry.getValue();
            // 计划暂停的门店此刻必为 OPEN ⇒ 分母恒 ≥ 分子；取 max 防御并发改状态造成的读偏差
            long open = Math.max(openByCity.getOrDefault(city, 0L), count);
            int ratio = (int) Math.round(count * 100.0 / open);
            boolean tripped = count >= minCount && count * 100L > (long) maxRatio * open;
            impacts.add(new SuspendCityImpact(city, open, count, ratio, tripped, confirmed.contains(city)));
        }
        return impacts;
    }

    /**
     * 存在「熔断且未确认」的城市 ⇒ 拒绝整批（不做部分执行：半批写库会让汇报口径与
     * 用户核对对象错位）。
     */
    public void requireWithinLimits(List<SuspendCityImpact> impacts) {
        List<String> blocked = new ArrayList<>();
        for (SuspendCityImpact impact : impacts) {
            if (impact.tripped() && !impact.confirmed()) {
                blocked.add(impact.city() + " " + impact.toSuspend() + "/" + impact.openCount()
                        + "（" + impact.ratioPercent() + "%）");
            }
        }
        if (blocked.isEmpty()) {
            return;
        }
        log.warn("[venue-daily-openings/batch-suspend] 影响面熔断，整批拒绝：{}", blocked);
        throw new BusinessException(CODE_BLAST_RADIUS_EXCEEDED,
                "以下城市本批将暂停的门店占比超过上限（" + readMaxRatioPercent() + "%）：" + String.join("、", blocked)
                        + "。请先用 dryRun 核对舞讯是否覆盖完整；确认属实后在 confirmedCities 中逐城声明再提交");
    }

    private int readMinCount() {
        int v = opsConfigService.getInt(KEY_MIN_COUNT, DEFAULT_MIN_COUNT);
        if (v < 1) {
            log.warn("ops config {}={} 非法（须 ≥1），退回默认 {}", KEY_MIN_COUNT, v, DEFAULT_MIN_COUNT);
            return DEFAULT_MIN_COUNT;
        }
        return v;
    }

    private int readMaxRatioPercent() {
        int v = opsConfigService.getInt(KEY_MAX_RATIO_PERCENT, DEFAULT_MAX_RATIO_PERCENT);
        if (v < 1 || v > 100) {
            log.warn("ops config {}={} 非法（须 1~100），退回默认 {}", KEY_MAX_RATIO_PERCENT, v, DEFAULT_MAX_RATIO_PERCENT);
            return DEFAULT_MAX_RATIO_PERCENT;
        }
        return v;
    }

    private static String cityKey(String city) {
        return city == null || city.isBlank() ? UNKNOWN_CITY : city.trim();
    }
}
