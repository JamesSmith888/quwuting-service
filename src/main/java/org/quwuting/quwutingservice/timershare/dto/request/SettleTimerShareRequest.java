package org.quwuting.quwutingservice.timershare.dto.request;

/**
 * 结算事实上报（POST /timer-shares/{token}/settle，2026-10-08，V45；2026-10-09 增 {@code settledAgoMs}）。
 * <p>
 * 只带「这一场结算时的净秒数」——金额<b>刻意不带</b>（各端规则可能不同、金额是账务隐私，
 * 服务端只存时间事实的口径见 V42/V45 迁移头注）。结算时刻由服务端盖章，不信客户端时钟
 * （两端手机时钟互不可信，这是整个计时分享域的既有前提）。
 * <p>
 * <b>{@code settledAgoMs} 的由来（2026-10-09）</b>：原先「结算时刻 = 服务端收到时刻」，隐含假设是
 * 客户端在结算的同一刻就把它发出去。但舞厅地下室弱网是常态——上报失败后客户端必须暂存重放，而
 * 重放时收到的时刻已经是几分钟之后，「对方几点结束」就被推迟了。所以客户端上报的不是「时间戳」
 * （两端时钟不可信），而是「<b>这件事已经过去多久</b>」（同一台手机上的单调差，不受时钟偏移影响），
 * 服务端回推 {@code settled_at = 收到时刻 − settledAgoMs}。与主持方上报 {@code wallElapsedMs}
 * 而非本机时间戳是同一条判据（54 号 §一）。缺省 / null = 0（老客户端：即时上报，行为与 V45 逐字相同）。
 *
 * @param netElapsedSeconds 结算时这一场的净时长（秒，0 ~ 12 小时；范围与主持方上报读数同护栏）
 * @param settledAgoMs      结算已发生多久（毫秒；null / 负数按 0，超过 12 小时按 12 小时截断）
 */
public record SettleTimerShareRequest(Integer netElapsedSeconds, Long settledAgoMs) {
}
