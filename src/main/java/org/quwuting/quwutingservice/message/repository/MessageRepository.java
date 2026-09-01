package org.quwuting.quwutingservice.message.repository;

import org.quwuting.quwutingservice.message.entity.Message;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** 我的站内信（按创建时间倒序分页——消息中心列表数据源） */
    Page<Message> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 未读消息数（个人中心 / 首页 FAB 未读徽标依据） */
    long countByUserIdAndReadAtIsNullAndDeletedFalse(Long userId);

    /**
     * 指定类型的未读消息（最新在前，取前 N 条）——首页「关注门店状态变化」提醒卡片
     * 数据源（type = VENUE_STATUS_CHANGED，见 MessageController#statusAlerts）。
     */
    Page<Message> findByUserIdAndTypeAndReadAtIsNullAndDeletedFalseOrderByCreatedAtDesc(
            Long userId, MessageType type, Pageable pageable);

    /** 按 ID 取本人消息（越权校验：非本人消息返回 empty） */
    Optional<Message> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    /** 单条标记已读（只更新本人未读消息；越权/已读时影响行数为 0，幂等） */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Message m SET m.readAt = :readAt WHERE m.id = :id AND m.userId = :userId AND m.readAt IS NULL")
    int markOneRead(@Param("id") Long id,
                    @Param("userId") Long userId,
                    @Param("readAt") LocalDateTime readAt);

    /** 全部标记已读（用户打开消息中心后批量置为已读，幂等） */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Message m SET m.readAt = :readAt WHERE m.userId = :userId AND m.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId,
                    @Param("readAt") LocalDateTime readAt);

    /**
     * 未读的指定类型消息中、relatedId ∈ venueIds 的门店 ID 去重集合
     * （收藏门店状态变化角标数据源，2026-09-01「收藏即关注」）：一次 IN 覆盖整页收藏，
     * 避免逐店 N+1（同收藏列表批量模式，见 FavoriteService#getFavoriteVenues）。
     */
    @Query("SELECT DISTINCT m.relatedId FROM Message m " +
            "WHERE m.userId = :userId AND m.type = :type AND m.relatedId IN :venueIds " +
            "AND m.readAt IS NULL AND m.deleted = false")
    List<Long> findUnreadVenueIdsByType(@Param("userId") Long userId,
                                        @Param("type") MessageType type,
                                        @Param("venueIds") Collection<Long> venueIds);

    /**
     * 按门店批量标记已读（收藏门店状态变化角标消费，2026-09-01）：只处理本人、指定类型、
     * relatedId = venueId 的未读消息；幂等（无未读时影响行数 0，越权门店不匹配行数 0）。
     * 仅该类型受影响——其他类型站内信（审核/积分等）不被门店打开误消费。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Message m SET m.readAt = :readAt " +
            "WHERE m.userId = :userId AND m.type = :type AND m.relatedId = :venueId AND m.readAt IS NULL")
    int markReadByVenueAndType(@Param("userId") Long userId,
                               @Param("type") MessageType type,
                               @Param("venueId") Long venueId,
                               @Param("readAt") LocalDateTime readAt);
}
