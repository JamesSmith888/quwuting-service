package org.quwuting.quwutingservice.spend.enums;

/**
 * 账目方向（2026-09-11，docs/agents/44-spend-ledger.md §24）。
 * <p>
 * 计时器被「客人」与「舞伴」两类身份使用：客人计时 = 陪跳付费（支出），
 * 舞伴计时 = 陪跳收款（收入）。方向由计时会话身份决定，金额恒为正——
 * 方向只决定账目的展示（+/- 前缀）与「收入/结余」口径，不改变 amount。
 * <p>
 * 统计聚合（overview）按 {@code direction} 参数取**单向口径**（缺省 EXPENSE）：
 * 2026-10-13 起统计页随身份切换——客人看支出（消费分析）、舞伴看收入
 * （此前恒支出口径，舞伴打开整页空态）。缺省保持 EXPENSE = 存量语义，
 * 老客户端不带该参数照常工作。
 * <p>
 * 老客户端载荷不带 direction 字段 ⇒ 服务端缺省 EXPENSE（存量语义，兼容收敛）。
 */
public enum SpendDirection {
    /** 支出（客人身份 / 手动补记；缺省值 = 存量账目语义） */
    EXPENSE,
    /** 收入（舞伴身份计时结算） */
    INCOME
}