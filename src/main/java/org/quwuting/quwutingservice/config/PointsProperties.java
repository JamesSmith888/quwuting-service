package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 积分系统配置（V2 决策：权重与奖励全部配置化，**禁止业务硬编码**）。
 * <p>
 * 配置键：{@code app.points.*}（YAML）。积分参数（奖励值/赠送上限/排名权重）
 * 是运营可调参数——上线后按「权重校准机制」（见 AGENTS.md 积分系统章节）调整，
 * 改配置重启即生效；热度公式文案由后端下发，前端零改动自动同步。
 */
@ConfigurationProperties(prefix = "app.points")
public record PointsProperties(
        /** 每日打卡奖励（积分） */
        int checkInReward,
        /** 上报被管理员采纳后的奖励（积分） */
        int feedbackReward,
        /**
         * 暂停营业报告被管理员采纳后的奖励（积分，2026-08-10 新增）。
         * 与 feedbackReward 同属"众包信号采纳"奖励池；独立键便于运营分别调节。
         */
        int statusReportReward,
        /** 积分对热度公式的权重（校准对象，V2 三阶段校准机制） */
        int heatWeight,
        GiftLimits gift,
        /** 积分解锁门槛限制（2026-08-14 公共模块：单点门槛上限） */
        GateLimits gate
) {

    /** 赠送限制（单次/每日/单目标每日） */
    public record GiftLimits(int maxPerGift, int maxPerDay, int maxPerTargetDay) {}

    /** 积分解锁门槛限制（单点门槛积分上限；0 = 免费即清除门槛，语义见 UpsertGateRequest） */
    public record GateLimits(int maxCost) {}

    /** 配置缺失时的安全回退 */
    private static final PointsProperties DEFAULT = new PointsProperties(2, 5, 5, 2,
            new GiftLimits(10, 20, 5), new GateLimits(50));

    public PointsProperties {
        if (checkInReward <= 0) checkInReward = DEFAULT.checkInReward();
        if (feedbackReward <= 0) feedbackReward = DEFAULT.feedbackReward();
        if (statusReportReward <= 0) statusReportReward = DEFAULT.statusReportReward();
        if (heatWeight < 0) heatWeight = DEFAULT.heatWeight();
        if (gift == null) gift = DEFAULT.gift();
        if (gift.maxPerGift() <= 0 || gift.maxPerDay() <= 0 || gift.maxPerTargetDay() <= 0) {
            gift = DEFAULT.gift();
        }
        if (gate == null) gate = DEFAULT.gate();
        if (gate.maxCost() <= 0) {
            gate = DEFAULT.gate();
        }
    }
}
