package org.quwuting.quwutingservice.venueactivity.enums;

import lombok.Getter;

/**
 * 核销方式 —— 决定用户侧出现什么动作、以及归因能不能成立。
 * <p>
 * 平台**不做券、不做核销、不碰资金**：只展示门店提供的信息 + 口令。券的发放与
 * 核销一律在门店线下完成，平台不背书、不担保（合规边界，见 49 号文档）。
 * <p>
 * 归因前提：{@link #CODE_WORD} 的口令**必须让用户与其他人有实际差异**——若门店
 * 对所有人都是同一个优惠，"报口令"就没有信息量，打卡数不能作为平台的贡献证据。
 * 这是与门店谈合作时最容易被忽略、也最致命的一条。
 */
@Getter
public enum ActivityRedemptionMode {

    /** 到店报指定口令（平台默认，也是最可归因的一种） */
    CODE_WORD("到店报口令"),

    /** 到店出示本页（活动卡片） */
    SHOW_PAGE("到店出示本页"),

    /** 需提前预约/电话登记 */
    RESERVE("需提前预约"),

    /** 无需凭证，门店对所有到店客人开放（此类归因不可用） */
    OPEN_TO_ALL("到店即可");

    private final String displayName;

    ActivityRedemptionMode(String displayName) {
        this.displayName = displayName;
    }

    /** 该核销方式能否为平台产生可归因的到店证据 */
    public boolean isAttributable() {
        return this != OPEN_TO_ALL;
    }
}
