package org.quwuting.quwutingservice.timershare.enums;

/**
 * 点赞记录里一条事件相对「记录的主人」（{@link TimerShareLikeSubject} 指向的那位）的方向
 * （2026-10-13，点赞记录；文档 = docs/agents/55-timer-share-likes.md §八）。
 * <p>
 * 每条事件要么是「别人赞了主人」（{@link #RECEIVED}），要么是「主人赞了别人」（{@link #GIVEN}）。
 * 主人 = 调用者本人（{@code SELF}）或调用者点开的那位同行者（{@code PEER}）；方向永远相对主人，
 * 客户端据 subject 决定怎么说（「赞了你」还是「赞了 TA」）。
 * <p>
 * 协议值 = 常量名；客户端镜像：quwuting 仓 {@code constants/timerShareWire.ts} 的
 * {@code TIMER_SHARE_LIKE_DIRECTIONS}（跨仓门禁 check:timer-likes 逐值比对）。
 */
public enum TimerShareLikeDirection {
    /** 对方赞了主人 */
    RECEIVED,
    /** 主人赞了对方 */
    GIVEN
}
