package org.quwuting.quwutingservice.venuefeedback.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 用户上报提交响应体。
 * <p>
 * 提交成功返回给上报者：包含处理状态与维护承诺提示（maintenanceHint，
 * 天数来自配置 app.reports.maintenance-days，前端禁止硬编码承诺天数）。
 * <p>
 * {@code trackable}（2026-08-06 新增）：本次上报是否可追踪（userId 是否落库）。
 * false = 匿名上报——无法在个人中心回看处理结果，前端据此提示"登录后上报可查看
 * 管理员处理结果"（不强推登录，匿名可正常提交）。
 * <p>
 * 结构化纠错载荷回显（2026-08-10 新增）：field/fieldDisplay/correctedValue
 * 随提交响应原样回传（前端确认提交内容，与 type/typeDisplay 同模式）。
 */
public record VenueFeedbackResponse(
        Long id,
        Long venueId,
        FeedbackType type,
        String typeDisplay,
        String note,
        /** 纠错目标字段（可空 = 非纠错场景） */
        FeedbackField field,
        /** 纠错目标字段展示文案（可空） */
        String fieldDisplay,
        /** 用户认为正确的数据（可空 = 未提供纠正值） */
        String correctedValue,
        ReportStatus status,
        String statusDisplay,
        /** 维护承诺提示文案（如"已通知管理员，我们会在 3 日内维护好"），前端 toast/空态直接展示 */
        String maintenanceHint,
        /** 是否可追踪（userId 落库 = true；匿名上报 = false），驱动"登录后可查看处理结果"提示 */
        boolean trackable,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
