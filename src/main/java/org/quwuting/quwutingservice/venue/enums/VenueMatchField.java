package org.quwuting.quwutingservice.venue.enums;

/**
 * 列表搜索「匹配解释」的载体标识（2026-09-16 通用化，取代原 {@code matchedAlias} 的单一载体）。
 * <p>
 * <b>为什么只有四个值</b>：本枚举只承载「在搜索结果卡片上<b>找不到</b>的命中来源」——
 * 解释行的唯一职责是把卡片上缺失的那段文本补给用户。卡片上本来就可见的载体
 * （{@code name} 走标题高亮、{@code city}/{@code district} 走位置行、{@code tags} 走标签行）
 * 一律<b>不进本枚举</b>：它们由前端对既有行做染色即可自证，新增枚举值只会诱导出
 * 「同一事实两处表达」的解释行冗余。
 * <p>
 * 完整命中载体清单共八个（见 {@code VenueRepository#KW_MATCH}），本枚举覆盖其中
 * 四个「不可见」者；映射关系是本次通用化时确立的口径，新增命中载体时必须先回答
 * 「它在卡片上可见吗」，可见则<b>禁止</b>加进本枚举。
 */
public enum VenueMatchField {

    /**
     * 门店别名（{@code qwt_venue_aliases.alias}）——身份核验信息，卡片默认不渲染。
     * 来源表受白名单约束：绝不允许回退到 {@code VenueSyncAlias.sourceName}（见 SYNC_ALIAS）。
     */
    ALIAS,

    /** 舞讯收录名（{@code qwt_venue_sync_aliases.source_name}）——管线侧的源店名，卡片默认不渲染。 */
    SYNC_ALIAS,

    /** 详细地址（{@code qwt_venues.address}）——卡片位置行只到区级，门牌/路名级不可见。 */
    ADDRESS,

    /** 门店简介（{@code qwt_venues.description}）——卡片无简介行。 */
    DESCRIPTION
}
