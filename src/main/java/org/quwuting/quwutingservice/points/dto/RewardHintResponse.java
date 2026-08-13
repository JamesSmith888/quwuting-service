package org.quwuting.quwutingservice.points.dto;

/**
 * 上报采纳奖励提示（2026-08-12 新增，上报激励三触点数据源）。
 * <p>
 * 公开只读接口 GET /points/reward-hint 的响应：详情页「信息缺失？上报得积分」入口徽标、
 * 反馈面板激励条、提交成功提示三处消费。金额来自配置 app.points.feedback-reward
 * （唯一事实源，前端禁止硬编码）；整句文案由后端拼接（{@code rewardHint}），
 * 前端零拼接原样透出。匿名可调（激励对匿名同样有意义——"登录后上报可领"登录引导）。
 */
public record RewardHintResponse(
        /** 该上报被采纳后可获得的积分 */
        int rewardAmount,
        /** 采纳奖励整句激励文案（如"上报被采纳后可获得 5 积分，积分可兑换礼物赠送给舞厅/舞伴"） */
        String rewardHint
) {}
