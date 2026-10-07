package org.quwuting.quwutingservice.venuecrowd.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 上报者可信度（2026-10-07 自 CrowdReportService 抽出）：权重 + 徽标分档的唯一出处。
 * <p>
 * 抽出的原因是<b>依赖方向</b>：详情聚合（CrowdReportService）与点赞名单的分层汇总
 * （CrowdReportLikeService）都要给用户分档，而 CrowdReportService 已经依赖点赞服务——
 * 让点赞服务反向依赖聚合服务会形成环。把「权重 / 徽标」这一个被两边共享的事实下沉成独立 Bean，
 * 环就没有了，也不用 {@code @Lazy} 之类的补丁。
 * <p>
 * 权重 = 1.0 + min(历史上报采纳,5)×0.5 + min(打卡天数,10)×0.1（封顶 4.5）：信号选择对齐
 * 「每信号须预测目标行为」——上报采纳 = 之前报得准；打卡 = 真实到店行为；认领 / 收藏 / 认可 / 分享与
 * 「报人数可信」弱相关，不进入权重。权重是用户历史行为事实（低频变化），缓存 60s；
 * 底层 aggregatesFor 内部 7 表聚合，列表 / 详情 / 历史页 / 点赞名单共享本缓存。
 * <p>
 * 注意：这里的权重是<b>原始可信度</b>，用于徽标分档与单人升级判定；<b>统计用权重</b>另有封顶与
 * 小样本等权规则（{@code CrowdConsensus}），两者不混用。
 */
@Service
@RequiredArgsConstructor
public class CrowdTrustService {

    /** 徽标文案（服务端权威，前端零拼接）。 */
    public static final String BADGE_VETERAN = "资深";
    public static final String BADGE_REGULAR = "常客";
    public static final String BADGE_NORMAL = "普通";
    /** 认领人（门店主）上报的标识：其上报不计入统计（商家自报有营销动机），明细行照常展示但如实标注。 */
    public static final String BADGE_OWNER = "店家";

    private final ContributionService contributionService;

    /** 上报者可信度权重缓存（userId → 权重），TTL 60s。 */
    private final Cache<Long, Double> weightsCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    /**
     * 批量取权重：先查缓存，miss 的一次聚合补齐并回填（含零贡献用户默认 1.0，
     * 避免「查了但无记录」的用户反复 miss）。
     */
    public Map<Long, Double> weights(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> result = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long userId : userIds) {
            Double cached = weightsCache.getIfPresent(userId);
            if (cached != null) {
                result.put(userId, cached);
            } else {
                misses.add(userId);
            }
        }
        if (!misses.isEmpty()) {
            Map<Long, ContributionService.ContributionAggregate> aggregates =
                    contributionService.aggregatesFor(misses);
            for (Long userId : misses) {
                ContributionService.ContributionAggregate agg = aggregates.get(userId);
                long adoptions = agg != null ? agg.reportedCount() : 0L;
                long checkins = agg != null ? agg.checkInDays() : 0L;
                double weight = 1.0 + Math.min(adoptions, 5) * 0.5 + Math.min(checkins, 10) * 0.1;
                weightsCache.put(userId, weight);
                result.put(userId, weight);
            }
        }
        return result;
    }

    /** 权重变化后的显式失效（确认奖励发放会提高获奖者权重；供升级检测读到新鲜值）。 */
    public void invalidate(Long userId) {
        weightsCache.invalidate(userId);
    }

    /** 徽标分档：权重 ≥ 资深阈值 → 资深；≥ 常客阈值 → 常客；其余 → 普通。 */
    public static String badgeOf(double weight) {
        if (weight >= CrowdPolicy.VETERAN_WEIGHT) {
            return BADGE_VETERAN;
        }
        if (weight >= CrowdPolicy.REGULAR_WEIGHT) {
            return BADGE_REGULAR;
        }
        return BADGE_NORMAL;
    }

    /** 用户徽标：认领人恒为「店家」，其余按权重分档（无贡献记录按 1.0 兜底）。 */
    public static String badgeFor(Long userId, Map<Long, Double> weights, Long ownerId) {
        if (userId != null && userId.equals(ownerId)) {
            return BADGE_OWNER;
        }
        return badgeOf(weights.getOrDefault(userId, 1.0));
    }
}
