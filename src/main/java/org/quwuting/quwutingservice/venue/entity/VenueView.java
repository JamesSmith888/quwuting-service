package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 场所浏览记录（按天去重）。
 * <p>
 * 同一用户 + 同一场所 + 同一天仅记录一条（联合唯一约束天然去重）。
 * 匿名用户 userId 为 null，不参与去重（每次访问均记录，数据仅供参考）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_views", indexes = {
        @Index(name = "qwt_idx_venue_views_venue_date", columnList = "venueId, viewDate"),
        @Index(name = "qwt_idx_venue_views_user", columnList = "userId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uq_venue_views_dedup", columnNames = {"venueId", "userId", "viewDate"})
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

    @CreationTimestamp
    private LocalDateTime createdAt;
}
