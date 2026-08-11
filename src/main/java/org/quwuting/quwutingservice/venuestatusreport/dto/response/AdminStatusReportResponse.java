package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;

import java.time.LocalDateTime;

/**
 * 管理端活跃突发事件列表项（GET /admin/status-reports，2026-08-10 新增，
 * 2026-08-11 泛化 reason → type）。
 * <p>
 * 平台级聚合视图：跨场所列出 TTL 窗口内全部活跃突发事件（管理端上下文）——
 * 与公开列表 {@code StatusReportListItem} 的差异：
 * <ul>
 *   <li>上报者<b>真实昵称 + userId</b>（不做昵称脱敏——管理员需识别上报者以处置虚假报告）；</li>
 *   <li>携带 {@code note}（补充说明，审核安全约定"note 仅管理端可见"，公开响应禁止返回）；</li>
 *   <li>附带场所名（venueName）供管理员直接识别目标门店；</li>
 *   <li>{@code peerCount} = 同店同类型活跃信号数（众报置信度，管理端「N人报」聚簇显示）。</li>
 * </ul>
 */
public record AdminStatusReportResponse(
        Long id,
        Long venueId,
        /** 场所名称（JOIN qwt_venues，场所已逻辑删除时 JOIN 仍保留原名） */
        String venueName,
        Long userId,
        /** 上报者真实昵称（管理端上下文不做脱敏；无昵称回退「舞友」） */
        String nickname,
        /** 突发事件类型 */
        ReportType type,
        /** 类型展示文案 */
        String typeDisplay,
        /** 严重级（high/medium/low/recovery） */
        String severity,
        /** 补充说明（仅管理端可见，审核安全约定） */
        String note,
        /** 报告声称的发生时间（可空 = 未填写） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime occurredAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        /** 同店同类型活跃信号数（众报聚簇，管理端「N人报」显示） */
        long peerCount
) {}
