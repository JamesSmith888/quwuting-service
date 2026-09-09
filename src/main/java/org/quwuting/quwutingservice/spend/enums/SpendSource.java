package org.quwuting.quwutingservice.spend.enums;

/**
 * 账目来源（统计口径可信度前提：场次 = DANCE 条目数，总额含手动，口径禁漂移）。
 */
public enum SpendSource {
    /** 计时结算自动入账（category 恒为 PARTNER） */
    DANCE,
    /** 手动补记（记一笔：分类 + 金额，V1 零自由文本） */
    MANUAL
}
