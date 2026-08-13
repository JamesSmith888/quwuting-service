package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface VenueViewRepository extends JpaRepository<VenueView, Long> {

    /**
     * 无条件幂等写入（单次往返）：依赖联合唯一约束 qwt_uq_venue_views_dedup 去重，
     * 冲突（同一已登录用户同一天重复浏览）时 DO NOTHING 静默忽略——保留「首次来源」
     * （source 只在插入时写入，冲突不更新；归因语义见 ViewSource 类注释）。
     * <p>
     * 使用原生 SQL：JPA 的 save() 无法表达 ON CONFLICT 语义。
     * 取代 check-then-act（先 SELECT 存在性再 INSERT）：后者对"当天首次浏览"需要 2 次跨洲
     * DB 往返，而 upsert 恒为 1 次，且天然消除并发竞态窗口（无需 catch 唯一约束异常）。
     * 匿名用户 userId=null 时 Postgres UNIQUE 视 NULL 互不相等，每次均插入成功（不去重是预期语义）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_views (venue_id, user_id, view_date, source, created_at) " +
                   "VALUES (:venueId, :userId, :viewDate, CAST(:source AS varchar), :createdAt) " +
                   "ON CONFLICT ON CONSTRAINT qwt_uq_venue_views_dedup DO NOTHING",
           nativeQuery = true)
    void upsertView(@Param("venueId") Long venueId,
                    @Param("userId") Long userId,
                    @Param("viewDate") LocalDate viewDate,
                    @Param("source") String source,
                    @Param("createdAt") LocalDateTime createdAt);

    /**
     * 单场所累计浏览量（全量历史口径）：qwt_venue_views 行数（按天去重 PV，含匿名，
     * 与 VenueHeatService 的 viewCount30d 同源同口径的全量版，仅去掉 30 天窗口）。
     * 供详情页基础响应组装（单店 COUNT，命中 (venue_id, view_date) 索引，毫秒级）。
     */
    @Query("SELECT COUNT(vv) FROM VenueView vv WHERE vv.venueId = :venueId")
    long countByVenueId(@Param("venueId") Long venueId);

    /**
     * 批量累计浏览量（列表页/收藏页整页一次 IN 覆盖，避免 N+1）：
     * 返回 (venueId, count) 二元组数组，按 venueId 分组聚合全量历史行数。
     * 与 {@link #countByVenueId} 同口径；调用方判空（venueIds 为空时 IN () 会
     * 触发 SQL 语法错误，参照 batchGetBadges 的空集合防御模式）。
     */
    @Query("""
            SELECT vv.venueId, COUNT(vv) FROM VenueView vv
            WHERE vv.venueId IN :venueIds
            GROUP BY vv.venueId
            """)
    List<Object[]> countByVenueIds(@Param("venueIds") List<Long> venueIds);
}
