package org.quwuting.quwutingservice.venueclaim.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 管理员审核认领申请请求体（approve / reject 共用）。
 * <p>
 * note 为可选的处理结果说明：通过时填"已核实"等备注、拒绝时填拒绝原因
 * （建议必填，随「我的认领」回传申请人，见 AGENTS.md「认领舞厅 → 审核回传」）。
 * 入库前经 TextSanitizer 清洗，长度上限与 DTO 校验双保险。
 */
public record HandleClaimRequest(
        /** 审核结果说明（可选，最多 200 字） */
        @Size(max = 200, message = "审核说明最多 200 字")
        String note
) {}
