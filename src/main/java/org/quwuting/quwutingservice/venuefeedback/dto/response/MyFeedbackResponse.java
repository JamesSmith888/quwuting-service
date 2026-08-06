package org.quwuting.quwutingservice.venuefeedback.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 当前用户的上报记录（「我的上报记录」数据源）。
 * <p>
 * 供两个用户级/场所级查询共用：
 * <ul>
 *   <li>GET /feedbacks/mine（个人中心：跨场所全部上报）</li>
 *   <li>GET /venues/{venueId}/feedbacks/mine（详情页弹窗：当前门店上报）</li>
 * </ul>
 * 记录附带场所名称（venueName，供列表直接展示，无需前端二次拼接）；
 * 处理结果（handleNote）随管理员处理写入，用户侧原样回显——这是
 * 「管理员处理完成后反馈处理结果给用户」的承载字段。
 */
public record MyFeedbackResponse(
        /** 上报记录 ID */
        Long id,

        /** 场所 ID（前端据此跳转场所详情页） */
        Long venueId,

        /** 场所名称（场所逻辑删除后仍返回原名，保留记录真实性） */
        String venueName,

        /** 上报类型 */
        FeedbackType type,

        /** 上报类型展示文案 */
        String typeDisplay,

        /** 用户补充说明（本人提交内容回显） */
        String note,

        /** 处理状态（PENDING / RESOLVED / DISMISSED） */
        ReportStatus status,

        /** 处理状态展示文案 */
        String statusDisplay,

        /** 管理员处理结果说明（未处理或未填写为 null） */
        String handleNote,

        /** 处理时间（未处理为 null） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime handledAt,

        /** 上报时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
