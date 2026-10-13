package org.quwuting.quwutingservice.venuepresence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 门店到访感谢（2026-10-13，V50；docs/agents/52-venue-presence.md「到访感谢」节）。
 * <p>
 * 一行 = 「某位感谢者对某位贡献者在某门店的到访分享投了一次感谢」（三元组）。
 * <b>为什么必须实体化到「(门店, 贡献者)」这一对</b>：到访在原始数据里是聚合量
 * （一个 {@code (user, venue, 15min桶)} 痕迹，没有「某一次到访」的实体）——不先做
 * 这一层实体化，感谢就没有可挂载、可去重的对象（设计始末见
 * {@code quwuting-venue-visit-thanks-design-2026-10-06.md}）。
 * <p>
 * <b>幂等与通知面</b>：全量唯一 {@code (venueId, thankUserId, targetUserId)} + 无取消
 * 动作——同一感谢者对同一贡献者<b>永不发第二次</b>；{@code INSERT ... ON DUPLICATE KEY
 * UPDATE} 兜住并发与重复点击，受影响行数 = 1（首次插入）才触发被感谢站内信
 * （对齐 {@code VenueCrowdReportLike} 的「首次才通知」由 DB 派生模式，无标志列）。
 * <p>
 * 🚫 <b>红线</b>（与行级点赞同款，违反即死）：
 * <ul>
 *   <li>感谢数<b>永不进任何算法</b>——不进热度公式 / 到访项 / 可信度加权 / 列表角标
 *       （可互刷的社交量进算法必死）；纯展示层精神激励；</li>
 *   <li><b>不产生积分</b>、不产生任何利益挂钩（23 号「纯荣誉称号」同源哲学）；</li>
 *   <li><b>不引入新的身份暴露</b>：被感谢者收到的站内信不点名感谢者；任何接口都不
 *       返回「谁到过这家店」的名单（52 号「永不公开」裁决）。</li>
 * </ul>
 * <b>被感谢集合的口径纪律</b>：只允许来自 {@code VenuePresenceService#nearbyContributors}
 * ——与卡片「感谢 N 位舞友」同一份证据、同一 30 天窗口、同一排除账号集合（52 号
 * 「展示口径一源」同款：⛔ 禁另算一套归因，否则"卡片说 6 人、感谢只谢 4 人"会打脸）。
 * <p>
 * 历史留痕：本表<strong>刻意不建</strong> {@code thanks_count} 冗余列（感谢不进公式，
 * 物化表多一列就多一处同步）与 {@code notified} 标志列（通知义务由唯一键「首次插入」
 * 派生——YAGNI，同设计提案 §3）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_visit_thanks",
        uniqueConstraints = @UniqueConstraint(name = "qwt_uk_vvt_venue_thank_target",
                columnNames = {"venueId", "thankUserId", "targetUserId"}),
        indexes = @Index(name = "qwt_idx_vvt_target", columnList = "targetUserId, venueId"))
public class VenueVisitThank extends BaseEntity {

    /** 被感谢的到访所在门店（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 感谢者（需登录；每次点击感谢该店全部未感谢过的贡献者） */
    @Column(nullable = false)
    private Long thankUserId;

    /** 被感谢的到访贡献者（同一 (门店, 感谢者, 贡献者) 三元组永不重复） */
    @Column(nullable = false)
    private Long targetUserId;
}
