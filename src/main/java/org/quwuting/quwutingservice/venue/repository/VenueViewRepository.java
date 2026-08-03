package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface VenueViewRepository extends JpaRepository<VenueView, Long> {

    /**
     * 无条件幂等写入（单次往返）：依赖联合唯一约束 qwt_uq_venue_views_dedup 去重，
     * 冲突（同一已登录用户同一天重复浏览）时 DO NOTHING 静默忽略。
     * <p>
     * 使用原生 SQL：JPA 的 save() 无法表达 ON CONFLICT 语义。
     * 取代 check-then-act（先 SELECT 存在性再 INSERT）：后者对"当天首次浏览"需要 2 次跨洲
     * DB 往返，而 upsert 恒为 1 次，且天然消除并发竞态窗口（无需 catch 唯一约束异常）。
     * 匿名用户 userId=null 时 Postgres UNIQUE 视 NULL 互不相等，每次均插入成功（不去重是预期语义）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_views (venue_id, user_id, view_date, created_at) " +
                   "VALUES (:venueId, :userId, :viewDate, :createdAt) " +
                   "ON CONFLICT ON CONSTRAINT qwt_uq_venue_views_dedup DO NOTHING",
           nativeQuery = true)
    void upsertView(@Param("venueId") Long venueId,
                    @Param("userId") Long userId,
                    @Param("viewDate") LocalDate viewDate,
                    @Param("createdAt") LocalDateTime createdAt);
}
