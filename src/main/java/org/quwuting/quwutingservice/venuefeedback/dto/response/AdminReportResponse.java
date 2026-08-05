package org.quwuting.quwutingservice.venuefeedback.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 管理端上报列表项（GET /admin/reports）。
 * <p>
 * 平台级聚合视图：跨场所列出全部上报，附带场所名称（venueName）供管理员
 * 直接识别目标门店；处理动作（resolve/dismiss）后 handledAt 落值。
 */
public record AdminReportResponse(
        Long id,
        Long venueId,
        /** 场所名称（批量查询 qwt_venues，场所已逻辑删除时回退占位文案） */
        String venueName,
        FeedbackType type,
        String typeDisplay,
        String note,
        ReportStatus status,
        String statusDisplay,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime handledAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
