package org.quwuting.quwutingservice.timershare.enums;

/**
 * 点赞记录「这是谁的记录」（2026-10-13 第二版；文档 = docs/agents/55-timer-share-likes.md §八）。
 * <p>
 * 用户点哪位的头像，就看<b>那位</b>的全部点赞记录（收到的 + 送出的）：
 * <ul>
 *   <li>{@link #SELF}：请求没带 token / relation / seq ⇒ 调用者本人的记录；</li>
 *   <li>{@link #PEER}：请求带了「会话凭据 + 角色 + 序号」且调用者确实是那张会话里这位的对家 ⇒ 这位的记录；</li>
 *   <li>{@link #UNRESOLVED}：带了却解析不出（不是成员 / 凭据失效 / 角色序号对不上，加入者之间也不互通）⇒ <b>什么都不给</b>。
 *       ⚠️ 绝不能退回成「调用者自己的记录」——客户端会把它画在对方名下，那是一个把别人的数据张冠李戴的错。</li>
 * </ul>
 * 协议值 = 常量名；客户端镜像：quwuting 仓 {@code constants/timerShareWire.ts} 的
 * {@code TIMER_SHARE_LIKE_SUBJECTS}（跨仓门禁 check:timer-likes 逐值比对）。
 */
public enum TimerShareLikeSubject {
    /** 调用者本人 */
    SELF,
    /** 调用者点开的那位同行者 */
    PEER,
    /** 请求指明了对方却解析不出：空记录，客户端不画入口 */
    UNRESOLVED
}
