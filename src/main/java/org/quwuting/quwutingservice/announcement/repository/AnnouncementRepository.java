package org.quwuting.quwutingservice.announcement.repository;

import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Optional<Announcement> findByIdAndDeletedFalse(Long id);

    /**
     * 用户端可见公告列表（分页倒序，pinned 优先）：PUBLISHED + 已生效
     * （publishAt 为空 = 立即生效）且未软删。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
            ORDER BY a.pinned DESC, a.publishAt DESC, a.id DESC
            """)
    Page<Announcement> findVisiblePage(@Param("status") AnnouncementStatus status,
                                       @Param("now") LocalDateTime now,
                                       Pageable pageable);

    /**
     * 未读公告数：可见公告 − 该用户已读（NOT EXISTS 派生，对齐站内信 unread-count 模式）。
     */
    @Query("""
            SELECT COUNT(a) FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND NOT EXISTS (
                  SELECT 1 FROM AnnouncementRead r
                  WHERE r.announcementId = a.id AND r.userId = :userId)
            """)
    long countUnread(@Param("status") AnnouncementStatus status,
                     @Param("now") LocalDateTime now,
                     @Param("userId") Long userId);

    /**
     * 管理端列表（状态/分类/来源三重筛选，全部可空 = 不限制；id 倒序最新在前）。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false
              AND (:status IS NULL OR a.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:source IS NULL OR a.source = :source)
            ORDER BY a.id DESC
            """)
    Page<Announcement> findPageByFilters(@Param("status") AnnouncementStatus status,
                                         @Param("category") AnnouncementCategory category,
                                         @Param("source") AnnouncementSource source,
                                         Pageable pageable);

    /**
     * DATA_UPDATE 同日防重：查询某天已存在的 SYSTEM 数据更新公告。
     * 日期口径 = publishAt 的日期（SYSTEM 公告创建即置 publishAt=now；
     * 兜底 COALESCE 到 createdAt）。与 V7 生成列唯一键同口径，查询防重 +
     * 唯一索引兜底防并发。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false
              AND a.source = :source AND a.category = :category
              AND DATE(COALESCE(a.publishAt, a.createdAt)) = :day
            ORDER BY a.id DESC
            """)
    List<Announcement> findForDay(@Param("source") AnnouncementSource source,
                                  @Param("category") AnnouncementCategory category,
                                  @Param("day") LocalDate day);

    /** 定时发布强转：DRAFT + 计划时间已到 → PUBLISHED（@Scheduled 调用，状态权威） */
    @Modifying
    @Query("""
            UPDATE Announcement a SET a.status = :target, a.publishedAt = :now
            WHERE a.deleted = false AND a.status = :draft
              AND a.publishAt IS NOT NULL AND a.publishAt <= :now
            """)
    int publishDue(@Param("draft") AnnouncementStatus draft,
                   @Param("target") AnnouncementStatus target,
                   @Param("now") LocalDateTime now);

    /** 定时下线强转：PUBLISHED + 计划下线时间已到 → OFFLINE */
    @Modifying
    @Query("""
            UPDATE Announcement a SET a.status = :target, a.offlinedAt = :now
            WHERE a.deleted = false AND a.status = :published
              AND a.offlineAt IS NOT NULL AND a.offlineAt <= :now
            """)
    int offlineDue(@Param("published") AnnouncementStatus published,
                   @Param("target") AnnouncementStatus target,
                   @Param("now") LocalDateTime now);

    /** 已读公告 ID 集合（列表页批量派生 read 布尔，一次 IN 查询） */
    @Query("""
            SELECT r.announcementId FROM AnnouncementRead r
            WHERE r.userId = :userId AND r.announcementId IN :ids
            """)
    Set<Long> findReadAnnouncementIds(@Param("userId") Long userId,
                                      @Param("ids") Collection<Long> ids);
}
