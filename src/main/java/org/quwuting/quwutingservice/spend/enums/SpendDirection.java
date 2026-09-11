package org.quwuting.quwutingservice.spend.enums;

/**
 * 账目方向（2026-09-11，docs/agents/44-spend-ledger.md §24）。
 * <p>
 * 计时器被「客人」与「舞伴」两类身份使用：客人计时 = 陪跳付费（支出），
 * 舞伴计时 = 陪跳收款（收入）。方向由计时会话身份决定，金额恒为正——
 * 方向只决定账目的展示（+/- 前缀）与「收入/结余」口径，不改变 amount。
 * <p>
 * 统计聚合（overview）一律只取 {@code EXPENSE}：统计页定位是「消费分析」
 * （分类占比 / 门店 TOP / 趋势），收入只在账本页显性呈现，两者口径互不污染。
 * 老客户端载荷不带 direction 字段 ⇒ 服务端缺省 EXPENSE（存量语义，兼容收敛）。
 */
public enum SpendDirection {
    /** 支出（客人身份 / 手动补记；缺省值 = 存量账目语义） */
    EXPENSE,
    /** 收入（舞伴身份计时结算） */
    INCOME
}