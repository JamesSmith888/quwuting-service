package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerAdView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DancerAdViewRepository extends JpaRepository<DancerAdView, Long> {

    /** 当日是否已观看（每日一次防刷的软检查；并发仍靠唯一索引 23505 兜底） */
    Optional<DancerAdView> findByUserIdAndDancerIdAndViewDate(Long userId, Long dancerId, LocalDate viewDate);

    /** 舞伴累计广告观看次数（收益线下结算依据，详情页"已获得 N 次支持"） */
    @Query("SELECT COUNT(v) FROM DancerAdView v WHERE v.dancerId = :dancerId")
    long countByDancerId(@Param("dancerId") Long dancerId);
}
