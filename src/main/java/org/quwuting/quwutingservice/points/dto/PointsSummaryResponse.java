package org.quwuting.quwutingservice.points.dto;

/**
 * 积分页概览响应（GET /points/me）。
 * rules 为合规规则文案（后端下发唯一事实源，前端直接渲染——禁止前端硬编码）。
 */
public record PointsSummaryResponse(
        long balance,
        long todayEarned,
        long todayGifted,
        boolean checkedInToday,
        String rules
) {}
