package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 行政区划名录仓库（qwt_regions，2026-09-30，MySQL V35）。
 * <p>
 * 唯一消费方 = {@code RegionService#regionNames}（启动/缓存回填时全量读取一次）。
 * <b>本表不参与任何门店筛选</b>——门店筛选走 {@code qwt_venues.city}，
 * 这里没有（也不许加）任何按 name 的查询：名录是「全集下发 + 前端本地判定」的模型，
 * 逐词回查会把判定真值拆成两处（且给搜索路径加一次往返）。
 */
public interface RegionRepository extends JpaRepository<Region, Long> {

    /** 全量名录（按 id 升序 = 灌入顺序，稳定可复现） */
    List<Region> findAllByOrderByIdAsc();
}
