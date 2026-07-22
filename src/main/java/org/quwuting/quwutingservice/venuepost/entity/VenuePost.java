package org.quwuting.quwutingservice.venuepost.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuepost.enums.PostPublisherType;

/**
 * 场所动态（公告 / 通知）。
 * <p>
 * 发布方有两种：门店认领人（OWNER）和平台管理员（ADMIN），
 * 通过 publisherType 区分，publisherName 冗余存储发布方展示名称（避免联表查询）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_posts", indexes = {
        @Index(name = "qwt_idx_posts_venue_id", columnList = "venueId"),
        @Index(name = "qwt_idx_posts_created_at", columnList = "createdAt")
})
public class VenuePost extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 动态标题 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 动态正文 */
    @Column(nullable = false, length = 2000)
    private String content;

    /** 发布方类型：OWNER（商家）/ ADMIN（平台） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private PostPublisherType publisherType;

    /** 发布方展示名称（冗余），如门店名或"去舞厅平台" */
    @Column(length = 64)
    private String publisherName;
}
