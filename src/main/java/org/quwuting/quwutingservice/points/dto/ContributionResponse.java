package org.quwuting.quwutingservice.points.dto;

/**
 * 社区贡献档案响应（2026-08-27，GET /points/contributions，需登录）。
 * <p>
 * 语义：用户自己看自己的贡献聚合记录（docs/agents/23）——贡献值 = 各行为计数 ×
 * 配置权重（app.contribution.*），等级 = 阈值匹配的荣誉称号（无利益挂钩）。
 * <b>展示边界</b>：仅本人可见（个人中心「我的贡献」）+ 管理端用户列表（仅 ADMIN）；
 * 不建公开用户主页（2026-08-21 审核驳回沉淀，见 AGENTS.md「小程序类目合规
 * UGC 红线」）。
 * <p>
 * rules = 合规规则文案（后端下发唯一事实源，前端直接渲染——禁止前端硬编码，
 * 与 PointsSummaryResponse.rules 同模式）。
 */
public record ContributionResponse(
        /** 贡献值（Σ 行为计数 × 权重） */
        long score,
        /** 等级 code（ContributionLevel 枚举名） */
        String levelCode,
        /** 等级称号（后端权威展示名） */
        String levelName,
        /** 上报采纳条数（信息上报 + 暂停营业报告，采纳才计） */
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
        long favoriteCount,
        /** 合规规则文案（后端权威） */
        String rules
) {}
