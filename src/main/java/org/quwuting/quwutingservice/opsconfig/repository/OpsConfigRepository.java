package org.quwuting.quwutingservice.opsconfig.repository;

import org.quwuting.quwutingservice.opsconfig.entity.OpsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 运营配置仓储（IDENTITY 主键 + key 唯一约束；按 key 读写配置）。
 */
public interface OpsConfigRepository extends JpaRepository<OpsConfig, Long> {

    /** 按配置键查询（key 唯一约束 qwt_uk_ops_config_key 保证至多一行） */
    Optional<OpsConfig> findByKey(String key);
}
