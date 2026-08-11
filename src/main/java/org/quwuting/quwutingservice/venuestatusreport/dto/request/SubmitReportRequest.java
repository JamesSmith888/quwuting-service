package org.quwuting.quwutingservice.venuestatusreport.dto.request;

import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;

import java.time.LocalDateTime;

/**
 * 提交突发事件（紧急公告）报告请求体。
 * <p>
 * 2026-08-11 泛化：原 reason（暂停原因）维度升级为 type（8 类突发事件）。
 * <ul>
 *   <li>{@code type} 缺省兜底 SUSPENDED（暂停营业）——兼容极速上报空 body；</li>
 *   <li>{@code note} 对 SITUATION_UNCLEAR（情况不明）为必填（服务层校验 1011），
 *       其余类型选填；</li>
 *   <li>补充详情时按需携带字段，再次提交为 upsert 覆盖已有报告（含换类型）。</li>
 * </ul>
 */
public record SubmitReportRequest(
        /** 突发事件类型（可选，null=SUSPENDED 暂停营业——极速上报兼容） */
        ReportType type,

        /** 用户陈述的事件发生时间（可选，null=报告时刻即事件时刻） */
        LocalDateTime occurredAt,

        /** 补充说明（可选，最多 500 字，仅管理端可见；SITUATION_UNCLEAR 必填） */
        @Size(max = 500, message = "补充说明最多 500 字")
        String note
) {}
