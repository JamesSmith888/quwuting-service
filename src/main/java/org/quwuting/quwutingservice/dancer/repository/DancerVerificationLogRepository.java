package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerVerificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 舞伴信息核验审计日志（2026-08-14 官方认证）。
 * 认证的唯一历史事实源；「曾认证」判定（曾认证被撤销后编辑 → 重新待复核闭环）走本表。
 */
public interface DancerVerificationLogRepository extends JpaRepository<DancerVerificationLog, Long> {

    /** 该舞伴是否存在过「已认证」记录（撤销后编辑触发重新核验的依据） */
    boolean existsByDancerIdAndToStatus(Long dancerId, String toStatus);
}
