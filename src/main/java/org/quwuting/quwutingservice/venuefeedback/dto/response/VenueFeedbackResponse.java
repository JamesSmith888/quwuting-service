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
 * <p>
 * 上报激励（2026-08-12 新增）：rewardAmount / rewardHint 随提交响应下发——
 * 前端在入口/面板/成功提示三处透出"采纳可得积分"激励（根因见 AGENTS.md
 * 「统一用户上报 → 上报激励三触点」）。金额来自配置 app.points.feedback-reward
 * （唯一事实源），文案由后端整句拼接，前端零硬编码零拼接；匿名上报同样下发
 * （登录引导场景复用），是否真能领到由 trackable 决定。
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
        /** 该上报被采纳后可获得的积分（后端配置下发，前端禁止硬编码；匿名同样下发用于登录引导） */
        int rewardAmount,
        /** 采纳奖励整句激励文案（后端拼接，如"上报被采纳后可获得 5 积分，积分可兑换礼物赠送"） */
        String rewardHint,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
