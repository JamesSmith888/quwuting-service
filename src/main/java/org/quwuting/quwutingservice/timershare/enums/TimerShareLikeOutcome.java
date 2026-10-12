package org.quwuting.quwutingservice.timershare.enums;

/**
 * 给同行者点赞 / 取消赞的结果（2026-10-12，V48；文档 = docs/agents/54-timer-share.md §十五）。
 * <p>
 * 与 {@link TimerShareJoinOutcome} 同一条判据：<b>这些都是「预期的业务状态」，以数据回给客户端而不是
 * 抛异常</b>——客户端请求层遇业务错误码只保留 message、丢弃 code，无法据此分流，而点赞面板需要对
 * 「功能已关闭 / 不是这场的成员 / 点赞窗口已过 / 点太快」各给一句贴切的话。
 * 真正的错误（未登录 → 401）仍走异常。
 * <p>
 * 客户端镜像：quwuting 仓 {@code constants/timerShareWire.ts} 的 {@code TIMER_SHARE_LIKE_OUTCOMES}
 * （跨仓门禁 check:timer-likes 逐值比对）。
 */
public enum TimerShareLikeOutcome {
    /** 已达成请求的状态（赞 / 取消赞；幂等：本来就是该状态也算） */
    OK,
    /** 运营开关关闭（{@code timer.share.like.enabled}） */
    DISABLED,
    /** token 非法 / 不存在 / 调用者既不是主持方也不是这张会话的加入者——不区分，不泄露 token 是否存在 */
    NOT_MEMBER,
    /** 目标不是这位调用者能赞的人（加入者只能赞主持方；主持方只能赞已加入的第 N 位；协议值非法） */
    INVALID_TARGET,
    /** 点赞窗口已过（赞与取消都冻结，见 {@code TimerSharePolicy#LIKE_WINDOW_MS}） */
    WINDOW_CLOSED,
    /** 单用户写速率保护 */
    TOO_FREQUENT
}
