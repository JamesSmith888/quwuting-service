package org.quwuting.quwutingservice.favorite.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "qwt_favorites", indexes = {
        @Index(name = "qwt_idx_fav_user_id", columnList = "userId"),
        @Index(name = "qwt_idx_fav_venue_id", columnList = "venueId"),
        @Index(name = "qwt_idx_fav_venue_deleted", columnList = "venueId, deleted")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_fav_user_venue", columnNames = {"userId", "venueId"})
})
public class Favorite extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long venueId;

    /**
     * 最近一次取消收藏的时刻（V19 新增，取消收藏趋势「取消」序列的数据源）。
     * 语义：动作时刻而非状态时刻——取消收藏时写入 now，重新收藏（restore）时清空为
     * NULL（见 V19 迁移注释与 FavoriteService 唯一写方约定）。
     * NULL = 当前处于收藏态（或该行从未被取消过）。
     */
    @Column
    private LocalDateTime unfavoritedAt;
}
