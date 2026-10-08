package org.quwuting.quwutingservice.timershare.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 计时分享加入流水（qwt_timer_share_joins，2026-10-07，V42）：一行 = 一个用户扫码加入了一张会话。
 * <p>
 * 唯一键 (share_id, user_id) 保证同一人重复扫码只有一行；写入由 {@code TimerShareStore#join}
 * 在<b>分享行的悲观锁内</b>完成。
 * <p>
 * 2026-10-08（V45）起本行不再"创建后不变"：加入者结算时更新 settled_at_ms / settled_net_seconds
 * （覆盖语义，见 V45 迁移头注）——{@code updated_at} 随结算上报跳动是预期行为，不是脏写。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_timer_share_joins")
public class TimerShareJoin extends BaseEntity {

    /** 分享会话（qwt_timer_shares.id） */
    @Column(name = "share_id", nullable = false, updatable = false)
    private Long shareId;

    /** 加入者（qwt_users.id） */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 加入时该账号是否为新建（创建距加入 ≤ 15 分钟）——扫码拉新的直接度量 */
    @Column(name = "is_new_user", nullable = false, updatable = false)
    private Boolean newUser;

    /** 加入那一刻按服务端时间轴推算的净时长（秒） */
    @Column(name = "net_seconds_at_join", nullable = false, updatable = false)
    private Integer netSecondsAtJoin;

    /**
     * 加入者结算时刻（服务端时间轴 epoch ms；2026-10-08，V45）。NULL = 未结算。
     * 重复上报覆盖为最新一次（无「已结算不可改」约束，见 V45 迁移头注）。
     */
    @Column(name = "settled_at_ms")
    private Long settledAtMs;

    /** 加入者结算时其计时的净时长（秒）；NULL = 未结算。只作主持方端展示（金额绝不上云） */
    @Column(name = "settled_net_seconds")
    private Integer settledNetSeconds;
}
