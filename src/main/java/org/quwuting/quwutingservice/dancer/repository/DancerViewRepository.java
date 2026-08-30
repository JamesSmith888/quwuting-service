package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface DancerViewRepository extends JpaRepository<DancerView, Long> {

    /**
     * 无条件幂等写入（单次往返）：依赖联合唯一索引 qwt_uq_dancer_views_dedup 去重，
     * 冲突（同一已登录用户同一天同一来源的浏览已存在）时 DO NOTHING 静默忽略——
     * 多渠道独立计数，来源只在插入时写入、不互相覆盖（语义与门店
     * {@code VenueViewRepository#upsertView} 完全一致）。
     * <p>
     * 使用原生 SQL：JPA 的 save() 无法表达 ON CONFLICT 语义。
     * 匿名用户 userId=null 时 Postgres UNIQUE 视 NULL 互不相等，每次均插入成功
     * （不去重是预期语义，60s IP 频控兜底）。
     * <p>
     * 冲突目标必须用 <b>列清单推断</b>（{@code ON CONFLICT (dancer_id, user_id, view_date, source)}）
     * 而非 {@code ON CONFLICT ON CONSTRAINT}：V29 建表用的是 CREATE UNIQUE INDEX
     * （唯一索引，非约束），ON CONSTRAINT 只匹配约束、不匹配索引（门店 V21 根因教训，
     * 勿重蹈——生产库保持索引形态时该写法每次抛错且被 fire-and-forget 静默吞掉）。
     * <p>
     * 返回受影响行数（1=真实插入，0=冲突 DO NOTHING 忽略）——调用方据此决定是否失效
     * 统计缓存：只有真实插入才改变浏览统计（viewTrend/viewSourceTrend），冲突时统计
     * 不变、不应触发无谓的缓存逐出（与门店 VenueViewService 同约定）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_dancer_views (dancer_id, user_id, view_date, source, created_at) " +
                   "VALUES (:dancerId, :userId, :viewDate, CAST(:source AS CHAR), :createdAt) " +
                   "ON DUPLICATE KEY UPDATE id = id",
           nativeQuery = true)
    int upsertView(@Param("dancerId") Long dancerId,
                   @Param("userId") Long userId,
                   @Param("viewDate") LocalDate viewDate,
                   @Param("source") String source,
                   @Param("createdAt") LocalDateTime createdAt);

    /**
     * 批量累计浏览量（舞伴列表/收藏列表整页一次 IN 覆盖，避免 N+1）：
     * 返回 (dancerId, count) 二元组数组，按 dancerId 分组聚合全量历史行数。
     * 口径 = qwt_dancer_views 行数（按天按来源去重 PV，含匿名，与
     * {@code DancerStatsService} viewTrend 同源同口径的全量版，仅去掉 30 天窗口；
     * 镜像门店 {@code VenueViewRepository#countByVenueIds}）。
     * 调用方判空（dancerIds 为空时 IN () 会触发 SQL 语法错误，参照门店
     * countByVenueIds 的空集合防御模式）。
     */
    @Query("""
            SELECT dv.dancerId, COUNT(dv) FROM DancerView dv
            WHERE dv.dancerId IN :dancerIds
            GROUP BY dv.dancerId
            """)
    List<Object[]> countByDancerIds(@Param("dancerIds") List<Long> dancerIds);
}
