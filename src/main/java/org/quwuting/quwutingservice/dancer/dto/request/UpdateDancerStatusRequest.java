package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotNull;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;

/**
 * 管理员状态切换请求体（PUT /admin/dancers/{id}/status）。
 * 状态机：PENDING → NORMAL（认证通过）/ HIDDEN（下架）；NORMAL ↔ HIDDEN 可往返。
 * 管理员是唯一可信认证来源——"先认证、后展示"的隐私边界由本接口承载。
 */
public record UpdateDancerStatusRequest(
        @NotNull(message = "目标状态不能为空")
        DancerStatus status
) {}
