package org.quwuting.quwutingservice.venuesync.dto.response;

/**
 * 门店实时状态（2026-09-01，批量查询响应项）。
 *
 * @param status        状态 key（OPEN / RENOVATING / CLOSED / SUSPENDED / CEASED，tag 配色用）
 * @param statusDisplay 展示文案（后端权威，如「营业中 / 已停业」）
 */
public record VenueStatusInfo(String status, String statusDisplay) {
}
