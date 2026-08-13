package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.venue.enums.ViewSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 场所浏览记录（按天按来源去重）。
 * <p>
 * 同一用户 + 同一场所 + 同一天 + 同一来源仅记录一条（联合唯一索引天然去重，V21 起
 * 唯一键含 source：多渠道独立计数——搜索/列表/分享是不同流量，搜索进入必计 SEARCH）。
 * 匿名用户 userId 为 null，不参与去重（每次访问均记录，60s IP 频控兜底）。
 * <p>
 * source = 浏览来源（LIST/SHARE/SEARCH/OTHER，2026-08-13 新增「浏览来源」统计图）。
 * 已登录用户按来源去重时冲突 DO NOTHING（同来源重复忽略），不同来源各自计数；
 * 匿名每次均按当次来源记录。语义与历史局限见 {@link ViewSource} 类注释。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_views", indexes = {
        @Index(name = "qwt_idx_venue_views_venue_date", columnList = "venueId, viewDate"),
        @Index(name = "qwt_idx_venue_views_user", columnList = "userId"),
        @Index(name = "qwt_idx_venue_views_source", columnList = "venueId, viewDate, source")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uq_venue_views_dedup", columnNames = {"venueId", "userId", "viewDate", "source"})
})
public class VenueView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long venueId;

    /** 浏览者用户 ID，匿名访问时为 null */
    private Long userId;

    /** 浏览日期（去重粒度：同一用户同一场所同一天仅一条） */
    @Column(nullable = false)
    private LocalDate viewDate;

    /** 浏览来源（列默认值唯一声明通道 = @ColumnDefault；默认 OTHER 兼容存量行） */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    @ColumnDefault("'OTHER'")
    private ViewSource source = ViewSource.OTHER;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
