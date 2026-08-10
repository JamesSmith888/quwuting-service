package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;

import java.time.LocalDateTime;

/**
 * 管理端活跃暂停报列表项（GET /admin/status-reports，2026-08-10 新增）。
 * <p>
 * 平台级聚合视图：跨场所列出 TTL 窗口内全部活跃暂停报（管理端上下文）——
 * 与公开列表 {@code StatusReportListItem} 的差异：
 * <ul>
 *   <li>上报者<b>真实昵称 + userId</b>（不做昵称脱敏——管理员需识别上报者以处置虚假报告）；</li>
 *   <li>携带 {@code note}（补充说明，审核安全约定"note 仅管理端可见"，公开响应禁止返回）；</li>
 *   <li>附带场所名（venueName）供管理员直接识别目标门店。</li>
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
        ReportReason reason,
        String reasonDisplay,
        /** 补充说明（仅管理端可见，审核安全约定） */
        String note,
        /** 报告声称的发生时间（可空 = 未填写） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime occurredAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
