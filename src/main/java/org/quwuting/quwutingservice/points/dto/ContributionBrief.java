package org.quwuting.quwutingservice.points.dto;

/**
 * 贡献档案摘要（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md）。
 * <p>
 * 用途：嵌入公开自愿分享响应（GET /users/{id}——用户主动分享邀约 = 默示授权向
 * 接收方舞伴展示自己的社区共建行为记录，与 pointsBalance 同口径，非实名身份
 * 信息）与管理端用户详情（GET /admin/users/{id}，仅 ADMIN）。
 * <p>
 * 合规：维度计数与 score 同 {@link
 * org.quwuting.quwutingservice.points.dto.ContributionResponse} 口径（采纳/通过/
 * 未软删才计）；等级 = 荣誉称号，无利益挂钩。
 */
public record ContributionBrief(
        /** 贡献值（Σ 行为计数 × 配置权重，app.contribution.*） */
        long score,
        /** 等级 code（ContributionLevel 枚举名） */
        String levelCode,
        /** 等级称号（后端权威展示名） */
        String levelName,
        /** 上报采纳条数 */
        long reportedCount,
        /** 打卡天数 */
        long checkInDays,
        /** 认可舞伴次数 */
        long recognitionCount,
        /** 门店认领通过家数 */
        long claimCount,
        /** 分享次数（门店 + 舞伴分享动作） */
        long shareCount,
        /** 舞伴收藏位次 */
        long favoriteCount
) {}
