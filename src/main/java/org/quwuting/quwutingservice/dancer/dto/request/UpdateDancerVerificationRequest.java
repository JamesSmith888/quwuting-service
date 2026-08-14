package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationAction;

/**
 * 舞伴信息核验管理操作请求体（PUT /admin/dancers/{id}/verification，仅 ADMIN）。
 * <p>
 * {@code reason} 为撤销原因（UNVERIFY 时服务层校验必填——撤销必须留痕理由，
 * 随站内信通知舞伴；VERIFY 时可选，仅服务端审计日志）。
 */
public record UpdateDancerVerificationRequest(
        @NotNull(message = "操作类型不能为空")
        DancerVerificationAction action,

        @Size(max = 200, message = "操作说明最长 200 字")
        String reason
) {}
