package org.quwuting.quwutingservice.dancerule.repository;

import org.quwuting.quwutingservice.dancerule.entity.RuleSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RuleSnapshotRepository extends JpaRepository<RuleSnapshotEntity, Long> {

    /** 每用户恒一行（user_id 唯一约束，见 V23） */
    Optional<RuleSnapshotEntity> findByUserId(Long userId);
}
