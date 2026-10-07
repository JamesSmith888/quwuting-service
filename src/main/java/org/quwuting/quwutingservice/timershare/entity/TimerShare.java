package org.quwuting.quwutingservice.timershare.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.timershare.enums.TimerShareStatus;

/**
 * 计时分享会话（qwt_timer_shares，2026-10-07，V42；文档 = docs/agents/54-timer-share.md）。
 * <p>
 * 一行 = 主持方某一场计时（某一位成员）的一份<b>服务端时间轴快照</b> + 二维码凭据。
 * 列语义与设计理由全部在 V42 迁移头注里（时间列为什么是 bigint 毫秒、excluded 为什么存秒、
 * 规则为什么不含名称），此处不重复，避免两处漂移。
 * <p>
 * 索引与唯一约束只在 V42 迁移里声明（{@code ddl-auto=validate} 不校验索引，实体上再写一份
 * {@code @Index} 只会多一处可漂移的副本，且 columnList 对「显式列名 / 隐式列名」的解析口径不同，
 * 是没必要承担的启动期风险）。
 * <p>
 * ⚠️ 所有列名显式写 {@code @Column(name = ...)}：14 号文档「实体字段以大写字母结尾必须显式列名」
 * 的事故（V33 {@code accuracyM}）说明隐式映射的下划线规则有盲区；本表没有以大写结尾的字段，
 * 显式声明是为了让「实体 ↔ 迁移」的对应关系在代码里一眼可查，而不是靠命名策略推断。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_timer_shares")
public class TimerShare extends BaseEntity {

    /** 二维码凭据（10 位 base62，创建后不可变） */
    @Column(name = "token", nullable = false, length = 16, updatable = false)
    private String token;

    /** 主持方（qwt_users.id） */
    @Column(name = "host_user_id", nullable = false, updatable = false)
    private Long hostUserId;

    /** 主持方端场次标识：起点毫秒[:成员id] */
    @Column(name = "session_key", nullable = false, length = 48, updatable = false)
    private String sessionKey;

    /** 传播链：主持方自己是经哪张会话加入的（可空，创建后不可变） */
    @Column(name = "parent_share_id", updatable = false)
    private Long parentShareId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TimerShareStatus status;

    /** 主持方真实起点（服务端时间轴 epoch ms） */
    @Column(name = "start_server_ms", nullable = false)
    private Long startServerMs;

    /** 已排除出计费的整数秒 */
    @Column(name = "excluded_seconds", nullable = false)
    private Integer excludedSeconds;

    /** 创建 / 刷新时主持方处于暂停则 = 该时刻，否则 null */
    @Column(name = "paused_at_server_ms")
    private Long pausedAtServerMs;

    /** 计价参数 JSON（服务端白名单重序列化，无自由文本） */
    @Column(name = "rule_json", nullable = false, length = 2048)
    private String ruleJson;

    /** 主持方关联门店（已校验存在） */
    @Column(name = "venue_id")
    private Long venueId;

    /** 最近一次创建 / 刷新的服务端时刻 */
    @Column(name = "snapshot_at_ms", nullable = false)
    private Long snapshotAtMs;

    /** 二维码失效时刻 = snapshotAtMs + TTL */
    @Column(name = "expires_at_ms", nullable = false)
    private Long expiresAtMs;

    @Column(name = "max_joins", nullable = false)
    private Integer maxJoins;

    /** 已加入人数（与 joins 表行数一致；只在分享行锁内自增） */
    @Column(name = "join_count", nullable = false)
    private Integer joinCount = 0;

    /** 快照刷新次数（首次创建 = 1） */
    @Column(name = "refresh_count", nullable = false)
    private Integer refreshCount = 1;

    /** 主持方关闭的时刻（status=CLOSED 时非空） */
    @Column(name = "closed_at_ms")
    private Long closedAtMs;
}
