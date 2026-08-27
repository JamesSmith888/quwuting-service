package org.quwuting.quwutingservice.points.dto;

/**
 * 客人反馈响应（2026-08-27，V56，docs/agents/25-invite-lifecycle-and-feedback.md；
 * POST /points/demands/{id}/feedback）。
 * <p>
 * 语义：非中转舞伴的客人遇到「没加上 TA / 被 TA 拒绝 / 未回复」时一键反馈——
 * 平台感知真实世界结果 + 自动返还该邀约解锁时的原扣费积分（拿回自己花的分，
 * 无净收益可刷）。幂等：已反馈过返回既有数据（refunded=false，不再重复返还）。
 */
public record FeedbackResponse(
        /** 本次是否新提交反馈（false = 已反馈过，幂等返回） */
        boolean submitted,
        /** 是否发生了积分返还（false = 免费解锁无扣费 / 已返还过） */
        boolean refunded,
        /** 本次返还的积分数量（无返还 = 0） */
        long refundPoints
) {}
