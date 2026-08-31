package org.quwuting.quwutingservice.venuesync.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 门店同步报告详情（GET /admin/venue-sync/reports/{id}）。
 *
 * @param id          报告 ID
 * @param reportDate  营业报告日
 * @param sourceId    渠道标识
 * @param sourceLabel 渠道展示名
 * @param url         信息源地址
 * @param createdAt   上报时间
 * @param summary     统计摘要
 * @param items       条目数组（MatchResult 镜像：city/source_name/status/confidence/alias_key/venue）
 */
public record SyncReportDetailResponse(
        Long id,
        LocalDate reportDate,
        String sourceId,
        String sourceLabel,
        String url,
        LocalDateTime createdAt,
        Map<String, Object> summary,
        List<Map<String, Object>> items
) {}
