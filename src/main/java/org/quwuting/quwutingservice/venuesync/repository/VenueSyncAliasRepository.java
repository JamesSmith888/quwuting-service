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
}
