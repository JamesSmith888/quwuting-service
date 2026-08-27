package org.quwuting.quwutingservice.points.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.ContributionProperties;
import org.quwuting.quwutingservice.dancer.repository.DancerFavoriteRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancershare.repository.DancerShareRepository;
import org.quwuting.quwutingservice.points.dto.ContributionResponse;
import org.quwuting.quwutingservice.points.enums.ContributionLevel;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.quwuting.quwutingservice.venueclaim.repository.VenueClaimRepository;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.quwuting.quwutingservice.venueshare.repository.VenueShareRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 社区贡献档案服务（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md）。
 * <p>
 * 语义：贡献档案 = 用户社区共建行为的<b>聚合记录</b>（只记录、不消耗、不公开广播）——
 * 与积分（资产模型，可花）解耦：积分流水是「上报采纳/打卡」的唯一事实源，贡献值
 * = 各行为表计数 × 配置权重（app.contribution.*）聚合，等级 = 阈值匹配的荣誉称号
 * （无任何利益挂钩；合规红线：无充值/无提现/无随机奖励，见 AGENTS.md「积分系统」）。
 * <p>
 * 展示边界：GET /points/contributions = 用户自己看自己（个人中心「我的贡献」）；
 * 管理端用户列表 = 仅 ADMIN 查看（运营查用户/识别刷分）；<b>不建公开用户主页</b>
 * （2026-08-21 用户公开主页因审核驳回下线，见 AGENTS.md「小程序类目合规 UGC 红线」）。
 * <p>
 * 性能：聚合走<b>批量 GROUP BY</b>（一次查询覆盖一页用户，避免 N+1）——
 * {@link #aggregatesFor} 供管理端用户列表与单用户概览共用同一实现。
 */
@Service
@RequiredArgsConstructor
public class ContributionService {

    /** 合规规则文案（后端下发唯一事实源，前端直接渲染，禁止硬编码） */
    private static final String RULES_TEXT =
            "贡献值是你在社区共建中的行为记录：上报采纳、每日打卡、认可舞伴、认领舞厅、" +
            "分享、收藏都会累积贡献值。等级称号仅作纪念，不参与任何积分或兑换。";

    /** 上报采纳来源集合（信息上报 + 暂停营业报告，采纳才发分 = 采纳数，见 PointsSourceType） */
    private static final List<PointsSourceType> REPORT_SOURCE_TYPES =
            List.of(PointsSourceType.FEEDBACK_REWARD, PointsSourceType.STATUS_REPORT_REWARD);

    private final ContributionProperties properties;
    private final PointsTransactionRepository transactionRepository;
    private final DailyCheckinRepository checkinRepository;
    private final DancerRecognitionRepository recognitionRepository;
    private final VenueClaimRepository claimRepository;
    private final VenueShareRepository venueShareRepository;
    private final DancerShareRepository dancerShareRepository;
    private final DancerFavoriteRepository favoriteRepository;

    /** 用户贡献档案（GET /points/contributions；用户自己看自己，不公开广播） */
    @Transactional(readOnly = true)
    public ContributionResponse summary(Long userId) {
        ContributionAggregate agg = aggregatesFor(List.of(userId)).getOrDefault(userId, ContributionAggregate.empty());
        return new ContributionResponse(
                agg.score(),
                agg.level().name(),
                agg.level().displayName(),
                agg.reportedCount(),
                agg.checkInDays(),
                agg.recognitionCount(),
                agg.claimCount(),
                agg.shareCount(),
                agg.favoriteCount(),
                RULES_TEXT);
    }

    /**
     * 批量贡献聚合（管理端用户列表用，一次查询一页用户；空集 → 空 Map）。
     * 各维度独立 GROUP BY（走既有 user 索引），结果集 = 用户数级别，内存合并无压力。
     */
    @Transactional(readOnly = true)
    public Map<Long, ContributionAggregate> aggregatesFor(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> reported = toMap(transactionRepository.countByUserIdsAndSourceTypes(userIds, REPORT_SOURCE_TYPES));
        Map<Long, Long> checkins = toMap(checkinRepository.countGroupByUserIds(userIds));
        Map<Long, Long> recognitions = toMap(recognitionRepository.countGroupByUserIds(userIds));
        Map<Long, Long> claims = toMap(claimRepository.countGroupByUserIdsAndStatus(userIds, ClaimStatus.APPROVED));
        Map<Long, Long> venueShares = toMap(venueShareRepository.countGroupByUserIdsAndEventType(userIds, ShareEventType.SHARE));
        Map<Long, Long> dancerShares = toMap(dancerShareRepository.countGroupByUserIdsAndEventType(userIds, ShareEventType.SHARE));
        Map<Long, Long> favorites = toMap(favoriteRepository.countGroupByUserIds(userIds));
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> build(userId, reported, checkins, recognitions, claims, venueShares, dancerShares, favorites)));
    }

    /** 等级派生：score ≥ 最大可达阈值 = 最高级（thresholds 与枚举一一对应，配置校验见 ContributionProperties） */
    public ContributionLevel resolveLevel(long score) {
        List<Integer> thresholds = properties.levelThresholds();
        int idx = 0;
        for (int i = 0; i < thresholds.size(); i++) {
            if (score >= thresholds.get(i)) {
                idx = i;
            }
        }
        return ContributionLevel.values()[idx];
    }

    private ContributionAggregate build(Long userId, Map<Long, Long> reported, Map<Long, Long> checkins,
                                        Map<Long, Long> recognitions, Map<Long, Long> claims,
                                        Map<Long, Long> venueShares, Map<Long, Long> dancerShares,
                                        Map<Long, Long> favorites) {
        long reportedCount = reported.getOrDefault(userId, 0L);
        long checkInDays = checkins.getOrDefault(userId, 0L);
        long recognitionCount = recognitions.getOrDefault(userId, 0L);
        long claimCount = claims.getOrDefault(userId, 0L);
        long shareCount = venueShares.getOrDefault(userId, 0L) + dancerShares.getOrDefault(userId, 0L);
        long favoriteCount = favorites.getOrDefault(userId, 0L);
        long score = reportedCount * properties.reportReward()
                + checkInDays * properties.checkInReward()
                + recognitionCount * properties.recognitionReward()
                + claimCount * properties.claimReward()
                + shareCount * properties.shareReward()
                + favoriteCount * properties.favoriteReward();
        return new ContributionAggregate(score, resolveLevel(score), reportedCount, checkInDays,
                recognitionCount, claimCount, shareCount, favoriteCount);
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> (Long) row[0],
                row -> (Long) row[1]));
    }

    /**
     * 单用户贡献聚合（内部结构；管理端用户列表经 aggregatesFor 批量获取）。
     * 维度计数与 score 一一对应，前端契约见 {@link ContributionResponse}。
     */
    public record ContributionAggregate(
            long score,
            ContributionLevel level,
            long reportedCount,
            long checkInDays,
            long recognitionCount,
            long claimCount,
            long shareCount,
            long favoriteCount
    ) {
        static ContributionAggregate empty() {
            return new ContributionAggregate(0, ContributionLevel.NOVICE, 0, 0, 0, 0, 0, 0);
        }

        /** 等级 code（ContributionLevel 枚举名，前端契约） */
        public String levelCode() {
            return level.name();
        }

        /** 等级称号（后端权威展示名） */
        public String levelName() {
            return level.displayName();
        }
    }
}
