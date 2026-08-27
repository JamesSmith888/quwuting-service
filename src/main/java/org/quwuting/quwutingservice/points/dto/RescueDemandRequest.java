package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 管理端代找替代舞伴请求（2026-08-27，POST /admin/demands/{id}/rescue body；
 * docs/agents/24「换乘站」）。
 * <p>
 * targetDancerId = 替代舞伴 id（必填）——管理员已微信人工确认该舞伴同意接单后，
 * 平台以原邀约四要素 + message 原样代建一条 APPROVED 替代邀约，直接发放该舞伴
 * 联系方式给客人（无用户间通信合规：全程平台代发，舞伴已线下同意）。
 */
public record RescueDemandRequest(@NotNull Long targetDancerId) {
}
