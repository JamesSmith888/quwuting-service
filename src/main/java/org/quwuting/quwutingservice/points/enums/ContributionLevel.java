package org.quwuting.quwutingservice.points.enums;

/**
 * 社区贡献等级（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md）。
 * <p>
 * 与 {@code app.contribution.level-thresholds} 一一对应（第 i 级阈值 =
 * levelThresholds[i]，数量固定 5，配置校验见 ContributionProperties）。
 * <p>
 * <b>合规语义</b>：等级 = 纯荣誉称号（社区共建纪念），不参与任何积分/兑换/权限
 * （合规红线：无充值/无提现/无邀请得积分/无随机奖励，见 AGENTS.md「积分系统」）。
 * 展示边界 = 个人中心「我的贡献」自己可见 + 管理端用户列表（仅 ADMIN）。
 */
public enum ContributionLevel {

    /** 0 分起：注册即达 */
    NOVICE("新晋舞友"),

    /** 10 分起 */
    ACTIVE("活跃舞友"),

    /** 50 分起 */
    SENIOR("资深舞友"),

    /** 150 分起 */
    CITY_REPORTER("城市情报员"),

    /** 400 分起 */
    HALL_MASTER("舞厅百晓生");

    private final String displayName;

    ContributionLevel(String displayName) {
        this.displayName = displayName;
    }

    /** 等级称号展示名（前端零拼接，后端唯一事实源） */
    public String displayName() {
        return displayName;
    }
}
