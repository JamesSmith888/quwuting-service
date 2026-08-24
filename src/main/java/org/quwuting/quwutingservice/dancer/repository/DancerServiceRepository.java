package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerService;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 舞伴服务范围仓库（2026-08-24）。
 * 服务 = admin 录入的黄页内容（平台代发模型），公开读 / admin 写。
 */
public interface DancerServiceRepository extends JpaRepository<DancerService, Long> {

    /** 单舞伴全部在用服务（详情页服务范围卡 / 需求弹层 chip 数据源，按展示顺序） */
    List<DancerService> findByDancerIdAndDeletedFalseAndActiveTrueOrderBySortOrderAscIdAsc(Long dancerId);

    /** 单舞伴全部服务（管理端编辑页回显，含下架项） */
    List<DancerService> findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long dancerId);

    Optional<DancerService> findByIdAndDeletedFalse(Long id);

    /** 同舞伴同标签的在用服务（admin 录入唯一性预检；库内部分唯一索引兜底并发） */
    Optional<DancerService> findByDancerIdAndLabelAndDeletedFalse(Long dancerId, String label);

    /** 当前最大展示顺序（新服务 sortOrder = max + 1；无服务返回 0，同 DancerPhoto 模式） */
    @Query("SELECT COALESCE(MAX(s.sortOrder), 0) FROM DancerService s " +
           "WHERE s.dancerId = :dancerId AND s.deleted = false")
    int findMaxSortOrder(@Param("dancerId") Long dancerId);

    /**
     * 批量舞伴"提供服务类别"判定（P2 需求优先匹配：列表按服务类别筛选舞伴）：
     * 返回 dancer_id 集合（存在 ≥1 个在用且未软删、类别匹配的服务即命中）。
     * 一次 IN 查询覆盖整页舞伴，规避 N+1。
     */
    @Query("SELECT DISTINCT s.dancerId FROM DancerService s " +
           "WHERE s.dancerId IN :dancerIds AND s.deleted = false AND s.active = true " +
           "AND s.category = :category")
    List<Long> findDancerIdsByCategoryIn(
            @Param("dancerIds") Collection<Long> dancerIds, @Param("category") DancerServiceCategory category);
}
