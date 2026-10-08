package org.quwuting.quwutingservice.timershare.dto.response;

/**
 * 结算事实上报的响应（POST /timer-shares/{token}/settle，2026-10-08，V45）。
 * <p>
 * 为什么是 {@code recorded} 布尔而不是抛错：结算上报是<b>尽力而为</b>的一次同步——调用方
 * （客户端）在结算完成后发出，失败与否都不该影响本地结算流程（绝不为同步打断结算主流程）。
 * 「token 不存在 / 我不是这张会话的成员」不是调用方的错误，是这条同步天然不适用的业务状态，
 * 以数据回传（同 join 的 outcome 模式），客户端拿到 false 静默接受即可。
 *
 * @param recorded 是否记录成功（true = 已写入；false = token 无效 / 非本会话成员，静默忽略）
 */
public record TimerShareSettleResponse(boolean recorded) {
}
