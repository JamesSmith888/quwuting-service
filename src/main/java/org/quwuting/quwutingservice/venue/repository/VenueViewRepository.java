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
     * 无条件幂等写入（单次往返）：依赖联合唯一索引 qwt_uq_venue_views_dedup 去重，
     * 冲突（同一已登录用户同一天同一来源的浏览已存在，V21 起按来源分列）时 DO NOTHING
     * 静默忽略——多渠道独立计数，来源只在插入时写入、不互相覆盖（归因语义见 ViewSource
     * 类注释；2026-08-13 晚产品决策：搜索/列表是不同流量，搜索进入必计 SEARCH）。
     * <p>
     * 使用原生 SQL：JPA 的 save() 无法表达 ON CONFLICT 语义。
     * 取代 check-then-act（先 SELECT 存在性再 INSERT）：后者对"当天首次浏览"需要 2 次跨洲
     * DB 往返，而 upsert 恒为 1 次，且天然消除并发竞态窗口（无需 catch 唯一约束异常）。
     * 匿名用户 userId=null 时 Postgres UNIQUE 视 NULL 互不相等，每次均插入成功（不去重是预期语义）。
     * <p>
     * 冲突目标必须用 <b>列清单推断</b>（{@code ON CONFLICT (venue_id, user_id, view_date, source)}）
     * 而非 {@code ON CONFLICT ON CONSTRAINT}：V1 基线创建的是 CREATE UNIQUE INDEX
     * （唯一索引，非约束），ON CONSTRAINT 只匹配约束、不匹配索引——生产库若保持索引形态，
     * 该写法每次抛错且被 fire-and-forget 静默吞掉（浏览来源折线全 0 的潜在根因，V21 修复；
     * 列推断对索引/约束两种形态均健壮）。
     * <p>
     * 返回受影响行数（1=真实插入，0=冲突 DO NOTHING 忽略）——调用方据此决定是否失效热度缓存：
     * 只有真实插入才改变浏览统计（viewTrend/viewSourceTrend/viewCount30d），冲突时统计不变、
     * 不应触发无谓的缓存逐出（与 FavoriteService 的"幂等无写入分支不逐出"同约定，2026-08-13）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_views (venue_id, user_id, view_date, source, created_at) " +
                   "VALUES (:venueId, :userId, :viewDate, CAST(:source AS CHAR), :createdAt) " +
                   "ON DUPLICATE KEY UPDATE id = id",
           nativeQuery = true)
    int upsertView(@Param("venueId") Long venueId,
                   @Param("userId") Long userId,
                   @Param("viewDate") LocalDate viewDate,
                   @Param("source") String source,
                   @Param("createdAt") LocalDateTime createdAt);

    /**
     * 单场所累计浏览量（全量历史口径）：qwt_venue_views 行数（按天按来源去重 PV，含匿名，
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
