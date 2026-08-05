package org.quwuting.quwutingservice.venuereaction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 用户对场所的 Reaction 记录（toggle 语义，一个用户对一个场所的一个 Reaction 至多一行）。
 * <p>
 * 不做永久累加：{@code deleted=false} 表示当前生效（用户仍持有该态度），取消即软删除。
 * 时效性通过 {@code createdAt}（恢复时刷新）+ 时间窗口查询实现"时间衰减"效果——
 * 见 AGENTS.md「Reaction 时效性设计」：不做周期性清零，而是只统计"当前生效"的记录，
 * 且只有用户重新确认（restore）才会刷新 createdAt 使其重新计入近期窗口。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_reactions", indexes = {
        @Index(name = "qwt_idx_vr_venue_code", columnList = "venueId, reactionCode"),
        @Index(name = "qwt_idx_vr_venue_code_created", columnList = "venueId, reactionCode, createdAt"),
        @Index(name = "qwt_idx_vr_user_id", columnList = "userId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_vr_user_venue_code", columnNames = {"userId", "venueId", "reactionCode"})
})
public class VenueReaction extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long venueId;

    /** 对应 {@link org.quwuting.quwutingservice.venuereaction.ReactionCode} 的枚举名 */
    @Column(nullable = false, length = 30)
    private String reactionCode;
}
