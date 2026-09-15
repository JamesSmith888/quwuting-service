package org.quwuting.quwutingservice.venueshare.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * 记录分享动作请求体（POST /venues/{id}/shares）。
 * channel 为分享发起渠道（BUTTON / MENU / TIMELINE），
 * 非法值由 @Pattern 校验拒绝（400），防止脏数据入库。
 * <p>
 * activityId（2026-09-16，V28，docs/agents/49-venue-activities.md §8）可空：
 * 门店详情页 / 门店热度页的分享没有活动上下文（恒 null）；只有用户在**活动卡**上点
 * 分享时才带值（来自该按钮的 data-act-id）。不做存在性校验——与 venueId 同风格，
 * 事件端点由已渲染的页面发起，冗余查询对 fire-and-forget 端点是不合理的延迟负担。
 */
public record RecordShareRequest(
        @Pattern(regexp = "^(BUTTON|MENU|TIMELINE)$", message = "无效的分享渠道")
        String channel,
        Long activityId
) {
}
