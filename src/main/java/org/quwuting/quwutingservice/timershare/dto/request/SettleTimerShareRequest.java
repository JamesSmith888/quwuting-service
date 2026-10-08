package org.quwuting.quwutingservice.timershare.dto.request;

/**
 * 结算事实上报（POST /timer-shares/{token}/settle，2026-10-08，V45）。
 * <p>
 * 只带「这一场结算时的净秒数」——金额<b>刻意不带</b>（各端规则可能不同、金额是账务隐私，
 * 服务端只存时间事实的口径见 V42/V45 迁移头注）。结算时刻由服务端以收到时刻盖章，
 * 不信客户端时间（两端手机时钟互不可信，这是整个计时分享域的既有前提）。
 *
 * @param netElapsedSeconds 结算时这一场的净时长（秒，0 ~ 12 小时；范围与主持方上报读数同护栏）
 */
public record SettleTimerShareRequest(Integer netElapsedSeconds) {
}
