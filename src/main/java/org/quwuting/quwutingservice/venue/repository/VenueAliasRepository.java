package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 门店别名仓库（2026-09-07 门店别名域，docs/agents/38-venue-aliases.md）。
 * <p>
 * 读取方：
 * <ul>
 *   <li>{@code findByVenueIdAndDeletedFalseOrderByIdAsc} — 详情公共部分缓存体
 *       （VenueService#computeVenueDetailPublic，冷启动单查，命中零往返）；</li>
 *   <li>{@code findByDeletedFalseOrderByIdDesc} — 管理端「门店别名」聚合列表；</li>
 *   <li>{@code findByVenueIdAndAlias} — upsert 复活通道（不筛 deleted，
 *       软删行重用，守住生成列部分唯一索引的同店同名幂等）。</li>
 * </ul>
 * 搜索命中不走本仓库（HQL EXISTS 子查询直连实体，见 VenueRepository#KW_MATCH）。
 */
public interface VenueAliasRepository extends JpaRepository<VenueAlias, Long> {

    /** 单店有效别名（录入顺序，详情下发口径） */
    List<VenueAlias> findByVenueIdAndDeletedFalseOrderByIdAsc(Long venueId);

    /** 全部有效别名（管理端聚合列表，id 倒序 = 最近配置在前） */
    List<VenueAlias> findByDeletedFalseOrderByIdDesc();

    /** 同店同名查找（不筛 deleted——含软删行，upsert 时复活重用） */
    Optional<VenueAlias> findByVenueIdAndAlias(Long venueId, String alias);
}
