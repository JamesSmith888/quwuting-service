package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;

/**
 * 管理端照片审核请求体（PUT /admin/dancers/photos/{id}/status）。
 * <p>
 * 审核动作：PENDING → PUBLIC（通过，公开）/ PENDING → REJECTED（驳回）。
 * reason 可选（驳回原因，当前仅服务端审计日志；舞伴本人在编辑页可见 REJECTED 状态后
 * 自行删除重传，不新增站内信——照片是低风险内容且本人可自查，见 AGENTS.md 决策记录）。
 */
public record UpdateDancerPhotoStatusRequest(
        @NotNull(message = "目标状态不能为空")
        DancerPhotoStatus status,

        @Size(max = 200, message = "说明最长200个字符")
        String reason
) {}
