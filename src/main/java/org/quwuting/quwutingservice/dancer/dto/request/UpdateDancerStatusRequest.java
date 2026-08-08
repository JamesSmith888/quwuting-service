package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;

/**
 * 管理员状态切换请求体（PUT /admin/dancers/{id}/status）。
 * 状态机：PENDING → NORMAL（认证通过）/ REJECTED（驳回）/ HIDDEN（下架）；NORMAL ↔ HIDDEN 可往返。
 * 管理员是唯一可信认证来源——"先认证、后展示"的隐私边界由本接口承载。
 * <p>
 * {@code reason}（可选）为操作说明：驳回时必填语义建议（未填则站内信仅展示通用文案），
 * 随站内信通知舞伴创建人（2026-08-08 新增，见 AGENTS.md「舞伴审核与站内信」）。
 */
public record UpdateDancerStatusRequest(
        @NotNull(message = "目标状态不能为空")
        DancerStatus status,

        @Size(max = 200, message = "操作说明最长 200 字")
        String reason
) {}
