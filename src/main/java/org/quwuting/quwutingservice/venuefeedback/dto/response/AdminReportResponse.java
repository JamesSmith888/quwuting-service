package org.quwuting.quwutingservice.venuefeedback.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 管理端上报列表项（GET /admin/reports）。
 * <p>
 * 平台级聚合视图：跨场所列出全部上报，附带场所名称（venueName）供管理员
 * 直接识别目标门店；处理动作（resolve/dismiss）后 handledAt 落值，
 * handleNote（处理结果说明）为管理员处理时填写的回传内容（2026-08-06 新增）。
 * <p>
 * 2026-08-28 补上报者信息（userId + nickname，管理端上下文真实昵称不脱敏）——
 * 管理员点击上报者行直达用户详情（用户管理模块）识别异常/恶意上报模式；
 * 匿名上报（userId null）昵称回退「匿名」。
 */
public record AdminReportResponse(
        Long id,
        Long venueId,
        /** 场所名称（批量查询 qwt_venues，场所已逻辑删除时回退占位文案） */
        String venueName,
        /** 上报者用户 ID（2026-08-28 新增，管理端识别身份用；匿名上报 = null） */
        Long userId,
        /** 上报者真实昵称（2026-08-28 新增，管理端上下文不做脱敏；匿名回退「匿名」） */
        String nickname,
        FeedbackType type,
        String typeDisplay,
        String note,
        /** 纠错目标字段（2026-08-10 新增，可空 = 非纠错场景；管理端按字段核对） */
        FeedbackField field,
        /** 纠错目标字段展示文案（可空） */
        String fieldDisplay,
        /** 用户认为正确的数据（2026-08-10 新增，可空 = 未提供纠正值） */
        String correctedValue,
        ReportStatus status,
        String statusDisplay,
        /** 管理员处理结果说明（未填写为 null，随「我的上报记录」回传上报者） */
        String handleNote,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime handledAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
