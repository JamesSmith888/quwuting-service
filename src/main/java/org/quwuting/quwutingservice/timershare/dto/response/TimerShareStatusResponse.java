package org.quwuting.quwutingservice.timershare.dto.response;

import java.util.List;

/**
 * 主持方轮询状态的响应（GET /timer-shares/{token}/status，2026-10-07，V42；2026-10-08 V45 增补）。
 * <p>
 * 弹层开着时每 3 秒一次，驱动「对方已同步 N 人」（2026-10-08 起同时驱动「对方是谁 / 第几位已结算」）。
 * 仍是单行点查 + 一次用户批量查询，不带快照。
 *
 * @param status       ACTIVE / CLOSED / EXPIRED。<b>EXPIRED 是派生态</b>（expires_at_ms 已过），不落库
 * @param joinCount    已加入人数
 * @param maxJoins     人数上限
 * @param expiresAtMs  二维码失效时刻
 * @param serverNowMs  服务端当前时刻
 * @param joins        加入者列表（按加入先后，1 起的序号 = 界面「第 N 位」；2026-10-08，V45）——
 *                     昵称 / 头像是「双方互看」的主持方一侧，settled 字段是结算同步的主持方一侧。
 *                     恒非 null（无人加入 = 空数组）；不向主持方下发加入者 userId（见 TimerShareJoinView）
 */
public record TimerShareStatusResponse(
        String status,
        int joinCount,
        int maxJoins,
        long expiresAtMs,
        long serverNowMs,
        List<TimerShareJoinView> joins) {
}
