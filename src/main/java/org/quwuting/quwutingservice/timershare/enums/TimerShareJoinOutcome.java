package org.quwuting.quwutingservice.timershare.enums;

/**
 * 扫码加入的结果（2026-10-07，V42）。<b>这些都是「预期的业务状态」，以数据形式回给客户端，
 * 不是异常</b>：
 * <ul>
 *   <li>客户端请求层（{@code httpRequest}）遇到业务错误码只保留 message、丢掉 code，
 *       无法据此分流；而扫码落地页需要对「已过期 / 已满 / 已结束 / 自己扫自己」各给一句
 *       贴切的话和一个出路——这些状态必须能被<b>程序化区分</b>；</li>
 *   <li>它们也不是「出错」：码过期、人满了，是这个功能正常运转的一部分。</li>
 * </ul>
 * 真正的错误（未登录 → 401、参数非法 → 1041）仍走异常。
 * <p>
 * 客户端镜像：quwuting 仓 {@code constants/timerShareWire.ts}（跨仓门禁 check:timer-share 逐值比对）。
 */
public enum TimerShareJoinOutcome {
    /** 本次新加入（计入人数） */
    JOINED,
    /** 此前已加入过（重复扫码 / 换机重试），幂等地再给一份快照 */
    ALREADY_JOINED,
    /** 扫的是自己的码 */
    SELF,
    /** 二维码已过期（主持方很久没有重新打开弹层） */
    EXPIRED,
    /** 主持方已结束这场计时 */
    CLOSED,
    /** 已达加入人数上限 */
    FULL,
    /** token 格式非法 / 不存在 / 快照损坏——不区分，避免泄露「哪些 token 存在」 */
    NOT_FOUND,
    /** 加入过于频繁（单用户速率保护） */
    TOO_FREQUENT
}
