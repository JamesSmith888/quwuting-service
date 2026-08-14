package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerCity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 舞伴多城市子表仓储（2026-08-14，V29 迁移）。
 * 编辑 = 全量替换（软删旧 + 插新，service 保证去重）；无唯一约束。
 */
public interface DancerCityRepository extends JpaRepository<DancerCity, Long> {

    /** 某舞伴全量城市（按选择序）——详情回显/编辑预填用 */
    List<DancerCity> findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long dancerId);

    /** 批量舞伴城市（列表/详情一次 IN 查询，N+1 规避） */
    @Query("SELECT c FROM DancerCity c WHERE c.dancerId IN :dancerIds AND c.deleted = false ORDER BY c.dancerId, c.sortOrder, c.id")
    List<DancerCity> findByDancerIds(@Param("dancerIds") List<Long> dancerIds);
}
