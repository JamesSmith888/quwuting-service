package org.quwuting.quwutingservice.spend.enums;

/**
 * 消费分类（固定 6 类，禁自定义——2026-09-09 用户拍板，docs/agents/44-spend-ledger.md）。
 * <p>
 * 固定枚举而非自由分类：认知成本（输入 1 次点击）+ 合规（无自由文本）+ 统计可比
 * （6 扇区封顶的环形图永不超载）。分类间互斥，一次到店的多笔消费各归各类，
 * 「今晚总共」由父聚合表达（V1 暂不落库父子结构，仅前端折叠）。
 */
public enum SpendCategory {
    /** 门票 */
    TICKET,
    /** 舞伴（计时结算自动入账固定归此类——计费引擎计的就是舞伴费用） */
    PARTNER,
    /** 酒水 */
    DRINK,
    /** 小吃 */
    SNACK,
    /** 交通 */
    TRANSPORT,
    /** 其他 */
    OTHER
}
