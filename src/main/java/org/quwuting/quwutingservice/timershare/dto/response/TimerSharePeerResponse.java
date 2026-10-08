package org.quwuting.quwutingservice.timershare.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 加入者读「对方（主持方）」状态的响应（GET /timer-shares/{token}/peer，2026-10-08，V45）。
 * <p>
 * 用途一 = 结算同步：加入者拉取主持方是否已结算、几点结束——用于「对方已于 xx:xx 结束计时，
 * 是否按这个时间结束我这场」的建议式对齐（不强制，见 54 号 §结算同步）。
 * 用途二 = 主持方资料刷新：加入时拿过一次快照，期间对方若改了昵称头像，本接口顺带下发当前值。
 * <p>
 * <b>CLOSED / EXPIRED 也正常响应</b>：结算事实恰恰大量发生在会话关闭 / 码过期之后（主持方
 * 结算时会顺手关闭凭据），这不是异常状态。{@code serverNowMs} 供客户端把
 * {@code hostSettledAtMs} 用自己的往返校准换算回本机时间（同 join 的时钟链）。
 *
 * @param status               会话状态：ACTIVE / CLOSED / EXPIRED（EXPIRED 是派生态，不落库）
 * @param host                 主持方资料（昵称 / 头像；null = 账号已不可查，客户端兜底）
 * @param hostSettledAtMs      主持方结算时刻（服务端时间轴 epoch ms）；null = 尚未结算
 * @param hostSettledNetSeconds 主持方结算时的净时长（秒）；null = 尚未结算。只作展示，不含金额
 * @param serverNowMs          服务端当前时刻（客户端时钟校准）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TimerSharePeerResponse(
        String status,
        TimerShareProfileView host,
        Long hostSettledAtMs,
        Integer hostSettledNetSeconds,
        long serverNowMs) {
}
