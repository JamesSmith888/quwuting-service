package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 活跃报告摘要（随 {@link org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse} 返回）。
 * <p>
 * 活跃 = 报告 createdAt 在 TTL 窗口内（当前为 4 小时），超过 TTL 的报告不计入活跃数但保留审计数据。
 * 前端据此渲染"N人报告暂停·最新X分钟前"信号。
 */
public record ActiveReportSummary(
        /** 活跃报告数 */
        int activeCount,

        /** 最新活跃报告时间（用于"X分钟前"展示，可能为 null=无活跃报告） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime latestReportTime
) {}
