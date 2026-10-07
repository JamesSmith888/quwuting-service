package org.quwuting.quwutingservice.timershare.dto.response;

/**
 * 主持方轮询状态的响应（GET /timer-shares/{token}/status，2026-10-07，V42）。
 * <p>
 * 弹层开着时每 3 秒一次，只为驱动「对方已同步 N 人」——所以是单行点查、不带快照。
 *
 * @param status       ACTIVE / CLOSED / EXPIRED。<b>EXPIRED 是派生态</b>（expires_at_ms 已过），不落库
 * @param joinCount    已加入人数
 * @param maxJoins     人数上限
 * @param expiresAtMs  二维码失效时刻
 * @param serverNowMs  服务端当前时刻
 */
public record TimerShareStatusResponse(
        String status,
        int joinCount,
        int maxJoins,
        long expiresAtMs,
        long serverNowMs) {
}
