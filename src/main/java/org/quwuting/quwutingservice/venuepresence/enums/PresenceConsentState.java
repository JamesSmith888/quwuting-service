package org.quwuting.quwutingservice.venuepresence.enums;

import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;

/**
 * 到店足迹的「当前采集态」（2026-10-07；文档 = docs/agents/52-venue-presence.md §5 / §6.3）。
 * <p>
 * <b>为什么是四态而不是「开 / 关」两个值</b>：库里存的是 {@code (enabled, source)} 二元组，
 * 而 {@code source=DEFAULT} 的行（09-29 ~ 10-03「默认开启」期服务端补记）<b>用户从未被询问过</b>——
 * 它在 {@code enabled=true} 上与服务端真正的同意长得一模一样。压成布尔会让 admin 页面把
 * 「没问过他」显示成「他允许了」，这在个保法语境下是<b>把证据链的缺口读成证据</b>。
 * 所以派生态把 DEFAULT 单列为 {@link #PENDING_PROMPT}（待补问），与门店列表页头的
 * 「待补问」人数（{@code VenuePresenceConsentStats#legacyDefaultUsers()}）<b>同一口径</b>。
 * <p>
 * 派生判据单点 = {@link VenuePresenceService#consentStateOf(Boolean, ConsentSource)}；
 * ⛔ 禁在 admin 侧或别处重写一遍「enabled && source.isExplicit()」——
 * 门禁（{@code report}）、分布统计、单用户展示三处必须永远同数。
 */
public enum PresenceConsentState {

    /** 从未确立：{@code qwt_venue_presence_consents} 无该用户任何行（未到过店 / 旧版端从未上报） */
    NEVER_ASKED("未询问"),

    /** 已开启：最新一条是显式来源（PROMPT / USER）且 enabled=true —— 服务端门禁会收数据 */
    ENABLED("已开启"),

    /** 已关闭：最新一条是显式来源且 enabled=false —— 用户主动拒绝，门禁拒收 */
    DISABLED("已关闭"),

    /**
     * 待补问：最新一条是历史 DEFAULT（{@code enabled} 恒 true，但<b>用户从未被询问</b>）。
     * ⛔ 不是同意：门禁拒收其上报，直到他在下次真正到店时被首问一次。
     */
    PENDING_PROMPT("待补问（默认开启期未经询问）");

    private final String displayName;

    PresenceConsentState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
