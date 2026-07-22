package org.quwuting.quwutingservice.taginteraction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 标签交互记录：用户对舞厅标签的点赞与评分。
 * <p>
 * 一个用户对一个舞厅的一个标签至多一行记录（唯一约束），
 * liked 与 score 为两个独立列——可只点赞、只打分、或两者兼有。
 * tag 字段存储标签文本（与 Venue.tags JSON 数组中的字符串一致），
 * 评分维度（服务、环境等）也复用此字段，由 Service 层校验合法性。
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

    /** 标签文本（描述性标签或评分维度名称），最长 50 字符 */
    @Column(nullable = false, length = 50)
    private String tag;

    /** 是否点赞（toggle 语义：true=已赞，false=取消） */
    @Column(nullable = false)
    private boolean liked = true;

    /** 评分 1-10，null 表示未打分 */
    @Column
    private Integer score;
}
