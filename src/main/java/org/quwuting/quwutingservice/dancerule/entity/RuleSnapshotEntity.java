package org.quwuting.quwutingservice.dancerule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 计价规则快照（2026-09-13，quwuting 仓 docs/agents/43-dance-timer.md §48）。
 * <p>
 * 每用户一行（user_id 唯一），snapshot_json 承载整份规则配置（客户端生成的
 * 不透明 blob：{ version, rules[], selectedRuleId, presetTombstones[] }）。
 * 本地表是小程序本地规则配置（qwt_dance_rules_v1 + qwt_dance_default_rule_v1
 * + qwt_dance_preset_tombstones_v1）的同步副本与恢复源：本地为源、云端为镜，
 * 写 = POST 幂等整体覆盖，读 = GET 一次拉回。根因：规则配置此前只落单设备
 * wx.storage，清缓存/换机即失（用户报障「选择变回 4 分 20 元」），与消费账本
 * （qwt_spend_entries）同一持久性架构的第二块拼图。
 */
@Getter
@Setter
@Entity(name = "DanceRuleSnapshot")
@Table(name = "qwt_dance_rule_snapshots")
public class RuleSnapshotEntity extends BaseEntity {

    /** 归属用户（接口恒 user-scoped，userId 恒取登录态，禁客户端传入） */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 快照 JSON 原文（不透明 blob，结构校验归客户端；服务端只校验大小上限） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "snapshot_json", nullable = false)
    private String snapshotJson;
}
