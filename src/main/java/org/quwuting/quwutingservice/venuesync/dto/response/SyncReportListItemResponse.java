package org.quwuting.quwutingservice.venuesync.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 门店同步报告列表项（GET /admin/venue-sync/reports）。
 *
 * @param id          报告 ID（详情/应用用）
 * @param reportDate  营业报告日
 * @param sourceId    渠道标识
 * @param sourceLabel 渠道展示名
 * @param createdAt   上报时间
 * @param summary     统计摘要（total_openings/matched/match_rate/…）
 */
public record SyncReportListItemResponse(
        Long id,
        LocalDate reportDate,
        String sourceId,
        String sourceLabel,
        LocalDateTime createdAt,
        Map<String, Object> summary
) {}
