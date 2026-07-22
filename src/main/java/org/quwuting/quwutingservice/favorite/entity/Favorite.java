package org.quwuting.quwutingservice.favorite.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

@Getter
@Setter
@Entity
@Table(name = "qwt_favorites", indexes = {
        @Index(name = "qwt_idx_fav_user_id", columnList = "userId"),
        @Index(name = "qwt_idx_fav_venue_id", columnList = "venueId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_fav_user_venue", columnNames = {"userId", "venueId"})
})
public class Favorite extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long venueId;
}
