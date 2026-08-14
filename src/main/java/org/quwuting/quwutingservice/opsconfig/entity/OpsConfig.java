package org.quwuting.quwutingservice.opsconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 运营配置（键值对，feature flag 设施，2026-08-14 新增）。
 * <p>
 * 承载可由运营即时调整的产品规则（如 Reaction「每日唯一表情」开关），
 * 不随发版变更——管理端经 FAB「运营配置」页读写，写入即时生效（缓存失效）。
 * 与 {@code application.yaml} 静态配置（@ConfigurationProperties）职责分离：
 * 本表是<b>可热更新的动态配置</b>，静态配置是部署期常量。
 * <p>
 * 主键沿用项目惯例 IDENTITY（SchemaIntegrityChecker 统一校验），
 * {@code key} 承载唯一约束（qwt_uk_ops_config_key）——配置是"存在即生效"的
 * 声明式数据，不使用 BaseEntity 的 deleted 软删模型；新增配置键的唯一通道 =
 * Flyway 迁移（配置 schema 属于代码契约，管理端只能改值不能造键，
 * 防"无人消费的配置"）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_ops_config", uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_ops_config_key", columnNames = "key")
})
public class OpsConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置键（如 {@code reaction.daily.single}；唯一约束 qwt_uk_ops_config_key） */
    @Column(length = 64, nullable = false)
    private String key;

    /** 配置值（字符串；布尔开关存 {@code "true"} / {@code "false"}） */
    @Column(nullable = false, length = 255)
    private String value;

    /** 最近修改人（管理端用户 ID；seed 默认行为 NULL） */
    @Column(name = "updated_by")
    private Long updatedBy;

    /** 最近修改时刻 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
