package org.quwuting.quwutingservice.announcement.repository;

import org.quwuting.quwutingservice.announcement.entity.AnnouncementRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, Long> {

    /** 阅读统计：单条公告阅读人数 */
    long countByAnnouncementId(Long announcementId);

    /** 幂等标记已读前置检查（存在即跳过写；唯一索引兜底并发 23505） */
    boolean existsByUserIdAndAnnouncementId(Long userId, Long announcementId);

    /**
     * 「全部已读」：为该用户当前<b>全部未读</b>的需触达公告一次性补写已读回执
     * （2026-09-15，docs/agents/34「未读收敛通道」）。
     * <p>
     * <b>为什么是一条原生 INSERT ... SELECT</b>：
     * <ol>
     *   <li><b>幂等靠约束而非应用层判断</b>——{@code INSERT IGNORE} 命中唯一键
     *       {@code (user_id, announcement_id)} 即静默跳过，用户连点 / 多端并发
     *       都收敛到同一结果（应用层"先查后插"在并发下必然出现重复键异常，
     *       而异常会让整个事务 rollback-only，同事务内再查未读数就会炸）；</li>
     *   <li><b>判据与读路径同源</b>——WHERE 条件 = 可见性谓词（未软删 + PUBLISHED +
     *       已生效 + 排除快讯）+ {@code touch_level = 'ALERT'} + 无回执，
     *       与 {@code AnnouncementRepository#countUnread} 逐条对应。
     *       ⚠️ <b>改一处必须同改另一处</b>；</li>
     *   <li>时间戳由 Java 传入（全库约定，禁 DB now()）；列名走 snake_case 原生列
     *       （原生 SQL 不经 Hibernate 命名策略）；</li>
     *   <li>枚举参数按 {@code String} 传（原生查询绑定枚举走 ordinal 是著名坑，
     *       调用方传 {@code .name()}）。</li>
     * </ol>
     *
     * @return 受影响行数（= 本次新补的回执数；0 表示本就无未读）
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO qwt_announcement_reads " +
                   "(user_id, announcement_id, read_at, created_at, updated_at, deleted) " +
                   "SELECT :userId, a.id, :now, :now, :now, 0 " +
                   "FROM qwt_announcements a " +
                   "LEFT JOIN qwt_announcement_reads r " +
                   "       ON r.announcement_id = a.id AND r.user_id = :userId " +
                   "WHERE r.id IS NULL AND a.deleted = 0 " +
                   "  AND a.status = :status AND a.touch_level = :touchLevel " +
                   "  AND a.category <> :excludeCategory " +
                   "  AND (a.publish_at IS NULL OR a.publish_at <= :now)",
           nativeQuery = true)
    int markVisibleReads(@Param("userId") Long userId,
                         @Param("status") String status,
                         @Param("touchLevel") String touchLevel,
                         @Param("excludeCategory") String excludeCategory,
                         @Param("now") LocalDateTime now);
}
