package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;

/**
 * 舞伴 ↔ 舞厅关系记录（多对多，支持"一个舞伴在多个舞厅出现、随时间变化"的时间属性）。
 * <p>
 * 不设计成强绑定单一舞厅：HOME（常驻）可多个、APPEARANCE（出现记录）可随时间增删；
 * 同一 (dancerId, venueId, relation) 至多一行（唯一约束，重复添加幂等）。
 * <p>
 * 遵循项目"无 JPA 关系、纯 Long 列 + 索引"的既有模式（同 Favorite / VenueReaction）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_venues", indexes = {
        @Index(name = "qwt_idx_dv_dancer", columnList = "dancerId"),
        @Index(name = "qwt_idx_dv_venue", columnList = "venueId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_dv_dancer_venue_relation", columnNames = {"dancerId", "venueId", "relation"})
})
public class DancerVenue extends BaseEntity {

    @Column(nullable = false)
    private Long dancerId;

    @Column(nullable = false)
    private Long venueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DancerVenueRelation relation;

    /** 关系备注（如"每周六晚场出现"），可选 */
    @Column(length = 200)
    private String note;
}
