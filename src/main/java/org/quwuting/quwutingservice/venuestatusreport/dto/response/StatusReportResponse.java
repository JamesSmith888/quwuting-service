package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;

import java.time.LocalDateTime;

/**
 * 状态报告详情响应体（管理端用，含 note 等非公开字段）。
 */
public record StatusReportResponse(
        Long id,
        Long venueId,
        ReportReason reason,
        String reasonDisplay,
        LocalDateTime occurredAt,
        String note,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
