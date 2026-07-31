package org.quwuting.quwutingservice.venuestatusreport.dto.request;

import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;

import java.time.LocalDateTime;

/**
 * 提交状态报告请求体。
 * <p>
 * 所有字段均为可选——极速上报时 body 可为空（{}），系统默认 reason=UNKNOWN。
 * 补充详情时按需携带字段，再次提交为 upsert 覆盖已有报告。
 */
public record SubmitReportRequest(
        /** 暂停原因（可选，null=UNKNOWN） */
        ReportReason reason,

        /** 用户陈述的事件发生时间（可选，null=报告时刻即事件时刻） */
        LocalDateTime occurredAt,

        /** 补充说明（可选，最多 500 字，仅管理端可见） */
        @Size(max = 500, message = "补充说明最多 500 字")
        String note
) {}
