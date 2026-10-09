package org.quwuting.quwutingservice.spend.enums;

/**
 * 账目上「一同计时的人」相对账目所有者的关系（2026-10-09，V47）。
 * <p>
 * 只描述<b>对方是怎么进入这场计时的</b>，不描述对方是谁——这是计时二维码同步域的既有口径：
 * 出示二维码的一方 =「主持方」，扫码加入的一方 =「加入者」（见 timershare 域 54 号文档）。
 * 站在账目所有者的视角：
 * <ul>
 *   <li>{@link #HOST}：<b>对方</b>是出示二维码的人（所有者是扫码加入的那一方）；</li>
 *   <li>{@link #JOINER}：<b>对方</b>是扫码加入的人（所有者是出示二维码的那一方）。</li>
 * </ul>
 * 协议值 = 本枚举常量名；客户端字面量只能来自 {@code constants/spendWire.ts}，
 * 由小程序 {@code check:protocol} 与本枚举逐值比对（同 {@link SpendDirection} 的纪律）。
 */
public enum SpendCompanionRelation {
    /** 对方出示二维码，所有者扫码加入 */
    HOST,
    /** 对方扫码加入，所有者出示二维码 */
    JOINER
}
