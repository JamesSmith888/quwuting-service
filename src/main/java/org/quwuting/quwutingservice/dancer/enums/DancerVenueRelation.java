package org.quwuting.quwutingservice.dancer.enums;

/**
 * 舞伴与舞厅的关系类型（多舞厅关联，不强制绑定单一舞厅）。
 * <ul>
 *   <li>{@link #HOME}：常驻舞厅——舞伴的主要活动场所（详情页"常去"展示位）；</li>
 *   <li>{@link #APPEARANCE}：出现舞厅——舞伴近期/历史上出现过的场所（随时间可增删，
 *       表达"一个舞伴可能在多个舞厅出现、随时间变化"的时间属性）。</li>
 * </ul>
 * 一个舞伴可有多个 HOME 或多个 APPEARANCE；同一 (dancer, venue, relation) 至多一行
 * （唯一约束，见 {@code DancerVenue}）。
 */
public enum DancerVenueRelation {
    /** 常驻舞厅 */
    HOME,
    /** 出现舞厅（历史/近期出现记录） */
    APPEARANCE
}
