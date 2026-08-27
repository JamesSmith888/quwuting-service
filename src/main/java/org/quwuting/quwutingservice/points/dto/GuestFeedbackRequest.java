package org.quwuting.quwutingservice.points.dto;

/**
 * 客人反馈请求（2026-08-27，V56，docs/agents/25「反馈闭环」；
 * POST /points/demands/{id}/feedback body）。
 * <p>
 * reason = DemandGuestFeedback 枚举 code（ADD_FAILED 没加上 / REJECTED 被 TA 拒绝 /
 * NO_REPLY 未回复 / OTHER 其他）——可空（旧客户端/未选，回退 OTHER 语义）；
 * 非法 code 应用层 parseOrNull 按 null 防御。
 */
public record GuestFeedbackRequest(String reason) {}
