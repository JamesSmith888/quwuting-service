package org.quwuting.quwutingservice.dancerule.dto;

/**
 * 规则快照读取响应（GET /dance-rules/snapshot）。
 *
 * @param snapshot  快照 JSON 原文；null = 云端没有该用户的快照（从未上过云的
 *                  老用户 / 全新账号）——客户端据此走本地出厂 seed（现状），不得
 *                  把 null 当空对象处理
 * @param updatedAt 云端快照最近写入时刻（epoch 毫秒，服务端时钟；无快照 = 0）。
 *                  客户端把它持久化为「同步水位」，之后与本值比较判新旧——
 *                  双方都用服务端时钟，规避设备时钟偏移（判据见 §48：水位
 *                  比较必须在同一只钟上做）
 */
public record RuleSnapshotResponse(
        String snapshot,
        Long updatedAt
) {
}
