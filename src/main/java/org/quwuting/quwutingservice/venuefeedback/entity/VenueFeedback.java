package org.quwuting.quwutingservice.venuefeedback.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;

/**
 * 场所信息纠错反馈。
 * <p>
 * 用户在详情页发现场所状态可能过时时提交的反馈，
 * 管理员在管理端查看并处理。handled 标记是否已处理。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_feedbacks", indexes = {
        @Index(name = "qwt_idx_feedbacks_venue_id", columnList = "venueId"),
        @Index(name = "qwt_idx_feedbacks_user_id", columnList = "userId"),
        @Index(name = "qwt_idx_feedbacks_handled", columnList = "handled, createdAt")
})
public class VenueFeedback extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 反馈提交者用户 ID（需登录） */
    @Column(nullable = false)
    private Long userId;

    /** 反馈类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private FeedbackType type;

    /** 补充说明（可选，最多 500 字） */
    @Column(length = 500)
    private String note;

    /** 是否已处理（管理员标记） */
    @Column(nullable = false)
    private boolean handled = false;
}
