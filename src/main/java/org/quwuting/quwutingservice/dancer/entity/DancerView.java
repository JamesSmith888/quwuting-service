package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.venue.enums.ViewSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 舞伴浏览记录（按天按来源去重，V29）。
 * <p>
 * 完全镜像门店浏览表（{@code qwt_venue_views}）的设计（2026-08-14 舞伴统计图
 * 第一期引入——舞伴详情页此前无浏览埋点，浏览趋势/浏览来源两张统计图数据源）：
 * <ul>
 *   <li>同一用户 + 同一舞伴 + 同一天 + 同一来源仅记录一条（联合唯一索引天然去重，
 *       多渠道独立计数——搜索/列表/分享是不同流量，互不覆盖）；</li>
 *   <li>匿名用户 userId 为 null，不参与去重（每次访问均记录，60s IP 频控兜底，
 *       见 {@code DancerViewService}）；</li>
 *   <li>source = 浏览来源，与门店共享 {@link ViewSource} 枚举（跨域复用先例 =
 *       {@code DancerShare} 复用 {@code venueshare.enums.ShareEventType}）；
 *       来源只在插入时写入、不互相覆盖。</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_views", indexes = {
        @Index(name = "qwt_idx_dancer_views_dancer_date", columnList = "dancerId, viewDate"),
        @Index(name = "qwt_idx_dancer_views_user", columnList = "userId"),
        @Index(name = "qwt_idx_dancer_views_source", columnList = "dancerId, viewDate, source")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uq_dancer_views_dedup", columnNames = {"dancerId", "userId", "viewDate", "source"})
})
public class DancerView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long dancerId;

    /** 浏览者用户 ID，匿名访问时为 null */
    private Long userId;

    /** 浏览日期（去重粒度：同一用户同一舞伴同一天仅一条） */
    @Column(nullable = false)
    private LocalDate viewDate;

    /** 浏览来源（列默认值唯一声明通道 = @ColumnDefault；默认 OTHER 兼容未上报来源的写入） */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    @ColumnDefault("'OTHER'")
    private ViewSource source = ViewSource.OTHER;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
