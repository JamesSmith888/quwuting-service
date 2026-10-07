package org.quwuting.quwutingservice.timershare.enums;

/**
 * 分享会话的持久状态（2026-10-07，V42；文档 = docs/agents/54-timer-share.md）。
 * <p>
 * 只有两个值：{@link #ACTIVE} / {@link #CLOSED}。<b>「已过期」不是持久状态而是派生态</b>——
 * 由 {@code expires_at_ms < now} 推出。把过期也存成一个状态值，就需要一个定时任务去翻它，
 * 而该任务一旦漏跑，状态列就会与时间事实矛盾（本库 announcement 域为此专门加过 30s 调度）；
 * 派生态没有这个失败模式——时间本身就是事实源。
 */
public enum TimerShareStatus {
    /** 可加入（仍需通过过期与人数检查） */
    ACTIVE,
    /** 主持方已结束 / 单独结算该场计时，二维码作废 */
    CLOSED
}
