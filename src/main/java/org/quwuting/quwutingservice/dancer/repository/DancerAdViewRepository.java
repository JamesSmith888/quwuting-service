package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerAdView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface DancerAdViewRepository extends JpaRepository<DancerAdView, Long> {

    /**
     * 无条件幂等写入（2026-08-19 根因修复：替代「先查后插 + 23505 异常吞掉」的
     * 不可靠并发幂等——Hibernate flush 失败后持久化上下文状态未定义，catch 后继续
     * 用同一事务执行查询/提交可能抛 UnexpectedRollbackException（HTTP 500）或残留脏
     * 上下文）。本写法恒 1 次 DB 往返、零异常：冲突（同一用户同舞伴同天已记录）时
     * DO NOTHING 返回 0 行，调用方按 affected 行数判定是否计入收益，语义确定。
     * <p>
     * 冲突目标用列清单推断（{@code ON CONFLICT (user_id, dancer_id, view_date)}）——
     * V25 建的是唯一索引（CREATE UNIQUE INDEX qwt_uk_ad_views_user_dancer_date）而非
     * 约束，ON CONFLICT ON CONSTRAINT 只匹配约束、不匹配索引（对齐 V21 教训）。
     *
     * @return 受影响行数：1 = 真实记录（计入收益）；0 = 当日已支持（幂等，不重复计收益）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_dancer_ad_views (dancer_id, user_id, view_date, created_at) " +
                   "VALUES (:dancerId, :userId, :viewDate, :createdAt) " +
                   "ON DUPLICATE KEY UPDATE id = id",
           nativeQuery = true)
    int upsertAdView(@Param("dancerId") Long dancerId,
                     @Param("userId") Long userId,
                     @Param("viewDate") LocalDate viewDate,
                     @Param("createdAt") LocalDateTime createdAt);

    /** 舞伴累计广告观看次数（收益线下结算依据，详情页"已获得 N 次支持"） */
    @Query("SELECT COUNT(v) FROM DancerAdView v WHERE v.dancerId = :dancerId")
    long countByDancerId(@Param("dancerId") Long dancerId);
}
