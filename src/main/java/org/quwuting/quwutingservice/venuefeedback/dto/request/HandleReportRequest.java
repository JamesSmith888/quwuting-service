package org.quwuting.quwutingservice.venuefeedback.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 管理员处理上报请求体（resolve / dismiss / adopt 共用）。
 * <p>
 * note 为可选的处理结果说明（2026-08-06 新增）：管理员处理时填写，随「我的上报记录」
 * 回传上报者，在个人中心展示（"处理结果"）。不填 = 仅流转状态，用户侧只看到处理状态。
 * 入库前经 TextSanitizer 清洗（防注入分层约定见其 javadoc），长度上限与 DTO 校验双保险。
 * <p>
 * reward 为采纳动作的奖励开关（2026-08-10 新增，仅 adopt 消费）：true / null（缺省）=
 * 采纳并奖励（终态 ADOPTED，同事务发分）；false = 采纳不奖励（终态 ADOPTED_NO_REWARD，
 * 不发分）。resolve / dismiss 忽略该字段（不落库）。
 */
public record HandleReportRequest(
        /** 处理结果说明（可选，最多 500 字） */
        @Size(max = 500, message = "处理结果最多 500 字")
        String note,

        /** 采纳动作是否奖励积分（null 视为 true；仅 adopt 消费） */
        Boolean reward
) {}
