package org.quwuting.quwutingservice.venueactivity.enums;

import lombok.Getter;

/**
 * 权益类别 —— 活动**唯一必须被枚举的语义维度**。
 * <p>
 * 为什么需要它（而不是让运营随便写文案）：列表页的活动标记只能容纳 ≤4 个字
 * （列表项信息密度已很高，绝不为活动加一行），所以短标签必须来自一个**有限集合**
 * 而不是自由文本。详情卡片的权益区排版也按类别分槽位。
 * <p>
 * {@link #defaultBadgeLabel} 是运营未显式填写时的短标签缺省值；运营可覆盖
 * （门店活动文案千差万别，这一项允许自由填写，只是给了个不用想的默认）。
 * <p>
 * 判据：新增类别 = 「列表页需要一个新短标签」时才加，不要因为文案不同就加类别
 * （文案走 benefitSummary 自由文本）。
 */
@Getter
public enum ActivityBenefitKind {

    TICKET_B1G1("门票买一送一", "买一送一"),
    TICKET_FREE("门票免费", "免门票"),
    TICKET_OFF("门票立减", "门票优惠"),
    GIFT("到店赠品", "有赠品"),
    SPEND_OFF("消费满减", "消费优惠"),
    PACKAGE("套餐优惠", "套餐价"),
    OTHER("其他优惠", "有活动");

    /** 管理后台下拉与详情页权益区标题 */
    private final String displayName;

    /** 列表页短标签缺省值（≤4 字，运营可覆盖） */
    private final String defaultBadgeLabel;

    ActivityBenefitKind(String displayName, String defaultBadgeLabel) {
        this.displayName = displayName;
        this.defaultBadgeLabel = defaultBadgeLabel;
    }
}
