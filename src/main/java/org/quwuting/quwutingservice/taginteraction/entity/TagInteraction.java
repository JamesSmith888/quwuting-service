package org.quwuting.quwutingservice.taginteraction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 评分交互记录：用户对舞厅评分维度的打分。
 * <p>
 * 一个用户对一个舞厅的一个评分维度至多一行记录（唯一约束）。
 * tag 字段存储评分维度名称（服务/环境/音响效果/性价比，见 RatingDimensions）。
 * <p>
 * 历史遗留：表名/字段名沿用早期"标签点赞 + 评分"合并设计的命名（liked 列已废弃并移除，
 * 数据库中仍可能存在旧的 liked 列，不再读写），"标签点赞"功能已被 Reaction 快速反馈系统
 * 替代，见 {@link org.quwuting.quwutingservice.venuereaction.entity.VenueReaction}
 * 与 AGENTS.md「Reaction 快速反馈系统」章节。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_tag_interactions", indexes = {
        @Index(name = "qwt_idx_ti_venue_tag", columnList = "venueId, tag"),
        @Index(name = "qwt_idx_ti_user_id", columnList = "userId"),
        @Index(name = "qwt_idx_ti_venue_tag_updated", columnList = "venueId, tag, updatedAt")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_ti_user_venue_tag", columnNames = {"userId", "venueId", "tag"})
})
public class TagInteraction extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long venueId;

    /** 评分维度名称（服务/环境/音响效果/性价比），最长 50 字符 */
    @Column(nullable = false, length = 50)
    private String tag;

    /** 评分 1-10，null 表示未打分 */
    @Column
    private Integer score;
}
