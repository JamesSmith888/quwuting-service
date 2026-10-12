package org.quwuting.quwutingservice.timershare.enums;

/**
 * 一场计时分享会话里的角色（2026-10-12，V48 点赞引入；文档 = docs/agents/54-timer-share.md §十五）。
 * <p>
 * 只描述<b>怎么进入这场计时</b>，不描述是谁——出示二维码的一方 = {@link #HOST}，扫码加入的一方 =
 * {@link #JOINER}。点赞接口里的「目标」就是用它指明的：<b>被赞的那位是主持方还是第几位加入者</b>
 * （加入者用 {@code joins[].seq} 区分，本域不向对方下发 userId，见 54 号 §资料互看的边界）。
 * <p>
 * 协议值 = 常量名；与账本域 {@code SpendCompanionRelation}、前端 {@code types/companion.ts}
 * 的 {@code CompanionRelation} 是<b>同一套词汇</b>（站在不同视角用它），三处由小程序
 * {@code check:protocol} 逐值比对——本域不依赖账本域的枚举，所以另声明一份而不是引用它。
 */
public enum TimerShareRole {
    /** 出示二维码的人 */
    HOST,
    /** 扫码加入的人 */
    JOINER
}
