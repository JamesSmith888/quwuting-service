package org.quwuting.quwutingservice.announcement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * 公告已读回执（2026-09-01）：每用户每公告至多一行，幂等标记已读。
 * <p>
 * 设计要点（docs/agents/34）：
 * <ul>
 *   <li>唯一约束 (user_id, announcement_id)：重复标记 → 23505 幂等语义；</li>
 *   <li>未读数 = 可见公告数 − 已读数（NOT EXISTS 派生，对齐站内信 unread-count 模式）；</li>
 *   <li>已读记录<b>不软删</b>（用户已读事实保留）；膨胀预案 = 离线归档（本期不做）；</li>
 *   <li>readAt 由 Java 侧 {@code LocalDateTime.now()} 写入（时间戳红线）。</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_announcement_reads", indexes = {
        @Index(name = "qwt_idx_ann_reads_user_ann", columnList = "userId, announcementId", unique = true),
        @Index(name = "qwt_idx_ann_reads_ann", columnList = "announcementId")
})
public class AnnouncementRead extends BaseEntity {

    /** 读者用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 公告 ID */
    @Column(nullable = false)
    private Long announcementId;

    /** 已读时刻（Java 侧写入） */
    @Column(nullable = false)
    private LocalDateTime readAt;
}
