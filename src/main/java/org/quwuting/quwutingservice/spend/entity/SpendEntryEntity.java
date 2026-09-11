package org.quwuting.quwutingservice.spend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.spend.enums.SpendCategory;
import org.quwuting.quwutingservice.spend.enums.SpendDirection;
import org.quwuting.quwutingservice.spend.enums.SpendSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 消费账目（V1 上云版，2026-09-09，docs/agents/44-spend-ledger.md §12）。
 * <p>
 * 本地表是小程序本地账目（qwt_dance_ledger_v1）的同步副本 + 聚合数据源：
 * 本地为源、云端为镜，唯一写入口 = POST /spend/entries/sync（批量幂等 upsert）。
 * 幂等键 = user_id + client_entry_id（生成列 client_dedupe 唯一索引，软删行键置
 * NULL 可重复出现——MySQL 无部分唯一索引的全库既有模式，见 V16 迁移注释）。
 */
@Getter
@Setter
@Entity(name = "SpendEntry")
@Table(name = "qwt_spend_entries", indexes = {
        @Index(name = "qwt_idx_spend_user_ts", columnList = "user_id, deleted, ts"),
        @Index(name = "qwt_idx_spend_user_venue", columnList = "user_id, venue_id, deleted, ts"),
        @Index(name = "qwt_idx_spend_user_updated", columnList = "user_id, updated_at"),
})
public class SpendEntryEntity extends BaseEntity {

    /** 归属用户（接口全部 user-scoped，userId 恒取登录态，禁客户端传入） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 客户端生成 id（重放安全；与 user_id 组成幂等键） */
    @Column(name = "client_entry_id", nullable = false, length = 32)
    private String clientEntryId;

    /** 业务发生时刻（结算=停止时刻；手动=记账时刻；Java 传 LocalDateTime，禁 DB now()） */
    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    /** 金额（元，恒正数；0 元账目在客户端已不落） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** 消费分类（固定 6 类禁自定义） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SpendCategory category;

    /** 来源（DANCE=计时结算自动；MANUAL=手动补记） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private SpendSource source;

    /** 方向（EXPENSE 缺省 = 存量语义；INCOME = 舞伴身份计时结算，44 号 §24）。
     *  金额恒为正，方向只决定展示与「收入/结余」口径。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    @ColumnDefault("'EXPENSE'")
    private SpendDirection direction;

    /** 来源关联 id（source=DANCE 时指向客户端 DanceRecord.id，明细↔账目追溯） */
    @Column(name = "source_ref_id", length = 32)
    private String sourceRefId;

    /** 关联门店（可空 = 未关联；定位失败且用户未手动挂是合法状态，聚合时独立桶展示） */
    @Column(name = "venue_id")
    private Long venueId;

    /** 门店名称快照（门店改名/删除后账目行仍可读，对齐 DanceRecord 规则快照思路） */
    @Column(name = "venue_name", length = 100)
    private String venueName;

    /** 结算时长秒数（仅 DANCE 来源；手动无此字段） */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;
}
