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
 * 门店到访痕迹（presence ping，2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * 一行 = 一次「用户在门店附近的可证实事实」，采样主力 = 小程序每次打开（onShow）。
 * <b>2026-10-08（V46）起携带用户坐标</b>（{@link #latitude} / {@link #longitude}，gcj02）：
 * 原「坐标不出端」红线的正当性形态经用户裁决修订为「主动同意 + 用途限定」
 * （见 V46 迁移头注），采集门禁不变；历史行恒 NULL。另仍存端侧自报的
 * {@code distanceM}（来自 /venues/nearby 服务端 Haversine 结果）与 {@code accuracyM}。
 * <p>
 * 写宽松读严格：本表存原始距离，{@code HIT_RADIUS_M} 等「到访/附近」口径在查询侧
 * 判定——阈值调整不需要重新采集，历史数据可回溯。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_presence_pings",
        indexes = {
                @Index(name = "qwt_idx_vp_venue_created", columnList = "venueId, createdAt"),
                @Index(name = "qwt_idx_vp_user_created", columnList = "userId, createdAt")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "qwt_uk_vp_user_venue_bucket",
                        columnNames = {"userId", "venueId", "writeBucket"})
        })
public class VenuePresencePing extends BaseEntity {

    /** 上报用户（qwt_users.id；写接口 requireAuth 保证非空归因） */
    @Column(nullable = false)
    private Long userId;

    /** 命中的门店（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /**
     * 写幂等桶 = floor(epochMinute / 15)（UTC epoch 派生，与时区无关）。
     * 同一 (user, venue, bucket) 唯一：onShow 高频触发由桶吸收，一行 = 一个
     * 15 分钟窗口内的首次命中事实。
     */
    @Column(nullable = false)
    private Long writeBucket;

    /**
     * 用户到该店的距离（米，端侧自报；服务端不复算——隐私红线决定不传坐标）。
     * <p>
     * ⚠️ <b>显式列名（2026-09-29 启动失败事故）</b>：Hibernate 驼峰→下划线策略
     * （isUnderscoreRequired）要求大写字母<b>前后均为小写/数字</b>才插下划线，
     * 尾字符永不生效 ⇒ {@code distanceM} 隐式映射为 {@code distancem}，与 V33 的
     * {@code distance_m} 错位、validate 拒启。<b>实体字段以大写字母结尾时必须
     * 显式 {@code @Column(name = ...)} 对齐迁移列名</b>，勿删本注解。
     */
    @Column(name = "distance_m", nullable = false)
    private Integer distanceM;

    /** 端侧定位精度（米，wx.getLocation accuracy；null = 端侧未提供）。显式列名理由同上。 */
    @Column(name = "accuracy_m")
    private Integer accuracyM;

    /**
     * 用户纬度（gcj02，端侧随足迹上报；2026-10-08 V46 起新记录才有值，历史行 NULL）。
     * <p>
     * ⚠️ 用途限定：仅 admin 内部到访统计与轨迹分析（{@code GET /admin/users/{id}/track}），
     * ⛔ 不向 C 端 / 第三方展示；同意文案四处已与事实同步（见 V46 迁移头注）。
     */
    @Column(name = "latitude")
    private Double latitude;

    /** 用户经度（gcj02，与 {@link #latitude} 成对入库；NULL = 旧端 / 历史记录） */
    @Column(name = "longitude")
    private Double longitude;
}
