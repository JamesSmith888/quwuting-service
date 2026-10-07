package org.quwuting.quwutingservice.timershare.dto.response;

/**
 * 主持方关闭分享会话的响应（POST /timer-shares/{token}/close，2026-10-07，V42）。
 * <p>
 * 关闭是 fire-and-forget 的清理动作：token 不存在 / 不是自己的 / 已关闭，统一回 {@code closed=false}
 * 而不报错——既不泄露「哪些 token 存在」，也不让清理调用的失败打扰结算主流程。
 *
 * @param closed 本次调用是否真的把一张 ACTIVE 会话关掉了
 */
public record TimerShareCloseResponse(boolean closed) {
}
