package org.quwuting.quwutingservice.announcement.repository;

import org.quwuting.quwutingservice.announcement.entity.AnnouncementRead;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, Long> {

    /** 阅读统计：单条公告阅读人数 */
    long countByAnnouncementId(Long announcementId);

    /** 幂等标记已读前置检查（存在即跳过写；唯一索引兜底并发 23505） */
    boolean existsByUserIdAndAnnouncementId(Long userId, Long announcementId);
}
