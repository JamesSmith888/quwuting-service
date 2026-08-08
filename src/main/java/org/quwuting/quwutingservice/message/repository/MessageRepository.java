package org.quwuting.quwutingservice.message.repository;

import org.quwuting.quwutingservice.message.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** 我的站内信（按创建时间倒序分页——消息中心列表数据源） */
    Page<Message> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 未读消息数（个人中心 / 首页 FAB 未读徽标依据） */
    long countByUserIdAndReadAtIsNullAndDeletedFalse(Long userId);

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
}
