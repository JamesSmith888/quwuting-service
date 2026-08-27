package org.quwuting.quwutingservice.points.dto;

/**
 * 管理端拒绝邀约请求（2026-08-27，POST /admin/demands/{id}/reject body；
 * docs/agents/24「P0 拒绝原因闭环」）。
 * <p>
 * reason = DemandRejectReason 枚举 code（可空 = 旧客户端/未选原因——客人侧回退
 * 通用状态文案 DemandStatus.statusText；枚举合法性由 DemandRejectReason.parseOrNull
 * 应用层防御，非法 code 按 null 处理，不 1001——拒绝动作不因原因字段失败）。
 */
public record RejectDemandRequest(String reason) {
}
