package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;

import java.time.LocalDateTime;

/**
 * 详情页紧急公告区聚合项（GET /venues/{venueId}/announcements，公开读，2026-08-11 新增）。
 * <p>
 * 公告区展示 = 活跃信号（deleted=false）+ 已采纳信号（deleted=true 且 adminAction=
 * ADOPTED，保留展示至 TTL 过期并带"已核实"标记）的<b>按类型聚簇摘要</b>——每类型
 * 一条，消费方（前端紧急公告卡）按 severity 排序渲染。
 * <p>
 * 契约：
 * <ul>
 *   <li>{@code count} = 该类型窗口内报告数（含已采纳）；</li>
 *   <li>{@code adopted} = 是否存在已采纳记录（驱动"已核实"标记）；</li>
 *   <li><b>不返回 note</b>（审核安全约定"note 仅管理端可见"，公开响应禁止携带
 *       ——公告区不展示用户自由文本，规避微信审核风险）；</li>
 *   <li>移除（REMOVED）信号不展示。</li>
 * </ul>
 */
public record AnnouncementSummary(
        /** 突发事件类型 */
        ReportType type,

        /** 类型展示文案 */
        String typeDisplay,

        /** 严重级（high/medium/low/recovery，前端色阶直接消费） */
        String severity,

        /** 该类型窗口内报告数（含已采纳，驱动"N人报X"文案） */
        int count,

        /** 是否存在已采纳记录（驱动"已核实"标记） */
        boolean adopted,

        /** 该类型最近报告时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime latestAt
) {}
