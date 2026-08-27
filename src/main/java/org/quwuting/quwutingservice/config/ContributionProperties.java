package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 社区贡献档案配置（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md）。
 * <p>
 * 配置键：{@code app.contribution.*}（YAML）。贡献档案 = 用户社区共建行为的<b>聚合
 * 记录</b>（只记录、不消耗、不公开广播）——与积分（资产模型，可花）解耦：积分
 * 流水是「上报采纳/打卡」的唯一事实源，贡献值 = 各行为表计数 × 权重聚合。权重与
 * 等级阈值是运营可调参数（上线后按「贡献值分布校准」，改配置重启即生效）。
 * <p>
 * 等级 = 阈值匹配（升序，第 i 个阈值对应 {@link
 * org.quwuting.quwutingservice.points.enums.ContributionLevel} 第 i 级）：
 * score ≥ threshold[i] 即至少第 i 级，匹配最大阈值 = 最高级；thresholds 数量
 * 非法（≠ 等级数）时整体回退默认（安全失败，禁止业务硬编码）。
 */
@ConfigurationProperties(prefix = "app.contribution")
public record ContributionProperties(
        /** 信息上报采纳权重（FEEDBACK_REWARD + STATUS_REPORT_REWARD 同权重） */
        int reportReward,
        /** 每日打卡权重（DAILY_CHECK_IN） */
        int checkInReward,
        /** 舞伴认可权重（DancerRecognition） */
        int recognitionReward,
        /** 门店认领通过权重（VenueClaim status = APPROVED） */
        int claimReward,
        /** 分享权重（VenueShare/DancerShare event_type = SHARE） */
        int shareReward,
        /** 舞伴收藏权重（DancerFavorite，未软删） */
        int favoriteReward,
        /** 等级阈值（升序，数量须 = ContributionLevel 等级数；第 i 级阈值 = levels[i]） */
        List<Integer> levelThresholds
) {

    /** 配置缺失/非法时的安全回退 */
    private static final ContributionProperties DEFAULT = new ContributionProperties(
            2, 1, 1, 2, 1, 1, List.of(0, 10, 50, 150, 400));

    public ContributionProperties {
        if (reportReward <= 0) reportReward = DEFAULT.reportReward();
        if (checkInReward <= 0) checkInReward = DEFAULT.checkInReward();
        if (recognitionReward <= 0) recognitionReward = DEFAULT.recognitionReward();
        if (claimReward <= 0) claimReward = DEFAULT.claimReward();
        if (shareReward <= 0) shareReward = DEFAULT.shareReward();
        if (favoriteReward <= 0) favoriteReward = DEFAULT.favoriteReward();
        if (levelThresholds == null || levelThresholds.size() != 5) {
            levelThresholds = DEFAULT.levelThresholds();
        }
    }
}
