package org.quwuting.quwutingservice.venuefeedback.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 管理端上报列表项（GET /admin/reports）。
 * <p>
 * 平台级聚合视图：跨场所列出全部上报，附带场所名称（venueName）供管理员
 * 直接识别目标门店；处理动作（resolve/dismiss）后 handledAt 落值，
 * handleNote（处理结果说明）为管理员处理时填写的回传内容（2026-08-06 新增）。
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
        /** 管理员处理结果说明（未填写为 null，随「我的上报记录」回传上报者） */
        String handleNote,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime handledAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
