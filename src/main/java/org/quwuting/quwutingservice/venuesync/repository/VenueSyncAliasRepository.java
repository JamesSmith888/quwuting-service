package org.quwuting.quwutingservice.venuesync.repository;

import org.quwuting.quwutingservice.venuesync.entity.VenueSyncAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueSyncAliasRepository extends JpaRepository<VenueSyncAlias, Long> {

    /** 全部有效映射（最近配置在前） */
    List<VenueSyncAlias> findByDeletedFalseOrderByUpdatedAtDesc();

    /** 按 key 查（幂等 upsert 用） */
    Optional<VenueSyncAlias> findByCityAndSourceNameAndDeletedFalse(String city, String sourceName);

    /**
     * 整页批量取（2026-09-16 匹配解释通用化）：列表搜索装配「命中即解释」时一次 IN 覆盖当页
     * 门店，规避 N+1（与 reactions / viewCounts / photos 同一批量装配模式）。
     * <p>
     * 只补展示、不参与过滤——命中集仍由 {@code VenueRepository#KW_MATCH} 决定，
     * 本方法是 {@code matchedHint} 的候选来源之一（{@code VenueMatchField#SYNC_ALIAS}）。
     */
    List<VenueSyncAlias> findByVenueIdInAndDeletedFalse(List<Long> venueIds);
}
