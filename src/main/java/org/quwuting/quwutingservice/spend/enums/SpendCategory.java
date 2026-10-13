package org.quwuting.quwutingservice.spend.enums;

import java.util.List;

/**
 * 消费分类（固定 7 类，禁自定义——2026-09-09 用户拍板，docs/agents/44-spend-ledger.md）。
 * <p>
 * 固定枚举而非自由分类：认知成本（输入 1 次点击）+ 合规（无自由文本）+ 统计可比
 * （扇区封顶的环形图永不超载）。分类间互斥，一次到店的多笔消费各归各类，
 * 「今晚总共」由父聚合表达（V1 暂不落库父子结构，仅前端折叠）。
 * <p>
 * 第 7 类 {@link #GUEST}（客人，2026-09-13 新增）是**收入向**分类：
 * {@link #PARTNER}（舞伴）= 我付给舞伴的钱，{@code GUEST} = 客人付给我的钱，
 * 两者互为反向。列存 varchar，新增枚举值**不需要 DDL 迁移**；下行宽容读
 * （老客户端收到未知值按来源兜底，见 {@code WireEnums.parse}）。
 * <p>
 * ⚠️ 改本枚举必须同步 `miniprogram/constants/spendWire.ts` 的
 * `SPEND_CATEGORY_WIRE`，否则 `npm run check`（check-spend-protocol）报契约漂移。
 */
public enum SpendCategory {
    /** 门票 */
    TICKET,
    /** 舞伴（计时结算自动入账固定归此类——计费引擎计的就是舞伴费用；支出向） */
    PARTNER,
    /** 酒水 */
    DRINK,
    /** 小吃 */
    SNACK,
    /** 交通 */
    TRANSPORT,
    /** 其他 */
    OTHER,
    /** 客人（收入向：客人付给我的钱；与 PARTNER 互为反向） */
    GUEST;

    /**
     * 支出向合法分类集（顺序即 overview.byCategory 的返回顺序 = 前端图例/横条顺序，
     * 高频前 3 在前）。
     */
    private static final List<SpendCategory> EXPENSE_SET =
            List.of(PARTNER, TICKET, DRINK, SNACK, TRANSPORT, OTHER);

    /** 收入向合法分类集（与 {@code SPEND_CATEGORIES_BY_DIRECTION.income} 逐项同序）。 */
    private static final List<SpendCategory> INCOME_SET = List.of(GUEST, OTHER);

    /**
     * 某方向的**合法分类集**（overview 补零的遍历源；2026-10-13 统计口径身份化时新增）。
     * <p>
     * ⚠️ 本表是前端 `miniprogram/types/spend.ts` 的 `SPEND_CATEGORIES_BY_DIRECTION`
     * 的**契约镜像**——两端的集合与顺序必须逐项一致（overview.byCategory 按本表
     * 顺序补零返回，前端按同一顺序渲染图例；顺序漂移 = 两端图例顺序打架）。
     * 改任何一侧必须先改另一侧。
     */
    public static List<SpendCategory> forDirection(SpendDirection direction) {
        return direction == SpendDirection.INCOME ? INCOME_SET : EXPENSE_SET;
    }
}
