package org.quwuting.quwutingservice.timershare.dto.response;

/**
 * 点赞 / 取消赞的响应（POST /timer-shares/{token}/likes，2026-10-12，V48）。
 *
 * @param outcome     结果（{@code TimerShareLikeOutcome} 常量名）；预期的业务状态以数据回传，见该枚举注释
 * @param liked       <b>仅 {@code outcome=OK} 时有意义</b>：操作后「我赞了对方」的状态（= 请求的 liked）。
 *                    非 OK 一律 false，客户端应按 outcome 处理而不是读它
 * @param serverNowMs 服务端当前时刻
 */
public record TimerShareLikeSetResponse(String outcome, boolean liked, long serverNowMs) {
}
